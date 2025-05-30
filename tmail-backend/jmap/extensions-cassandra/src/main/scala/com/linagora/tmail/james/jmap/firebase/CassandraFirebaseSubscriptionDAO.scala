/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.tmail.james.jmap.firebase

import java.time.{ZoneOffset, ZonedDateTime}

import com.datastax.oss.driver.api.core.`type`.DataTypes.{TEXT, TIMESTAMP, UUID, frozenSetOf}
import com.datastax.oss.driver.api.core.`type`.codec.registry.CodecRegistry
import com.datastax.oss.driver.api.core.`type`.codec.{TypeCodec, TypeCodecs}
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, Row}
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.{bindMarker, deleteFrom, insertInto, selectFrom}
import com.linagora.tmail.james.jmap.model.{DeviceClientId, FirebaseSubscription, FirebaseSubscriptionExpiredTime, FirebaseSubscriptionId, FirebaseToken}
import jakarta.inject.Inject
import org.apache.james.backends.cassandra.components.CassandraDataDefinition
import org.apache.james.backends.cassandra.utils.CassandraAsyncExecutor
import org.apache.james.core.Username
import org.apache.james.jmap.api.change.TypeStateFactory
import org.apache.james.jmap.api.model.TypeName
import reactor.core.scala.publisher.{SFlux, SMono}

import scala.jdk.javaapi.CollectionConverters

object CassandraFirebaseSubscriptionTable {
  val TABLE_NAME = "firebase_subscription"
  val USER: CqlIdentifier = CqlIdentifier.fromCql("user")
  val DEVICE_CLIENT_ID: CqlIdentifier = CqlIdentifier.fromCql("device_client_id")
  val ID: CqlIdentifier = CqlIdentifier.fromCql("id")
  val EXPIRES: CqlIdentifier = CqlIdentifier.fromCql("expires")
  val TYPES: CqlIdentifier = CqlIdentifier.fromCql("types")
  val TOKEN: CqlIdentifier = CqlIdentifier.fromCql("fcm_token")

  val MODULE: CassandraDataDefinition = CassandraDataDefinition.table(TABLE_NAME)
    .comment("Hold user firebase push subscriptions data")
    .statement(statement => types => statement
      .withPartitionKey(USER, TEXT)
      .withClusteringColumn(DEVICE_CLIENT_ID, TEXT)
      .withColumn(TOKEN, TEXT)
      .withColumn(ID, UUID)
      .withColumn(EXPIRES, TIMESTAMP)
      .withColumn(TYPES, frozenSetOf(TEXT)))
    .build

  val FROZEN_OF_STRINGS_CODEC: TypeCodec[java.util.Set[String]] = CodecRegistry.DEFAULT.codecFor(frozenSetOf(TEXT))
}

class CassandraFirebaseSubscriptionDAO @Inject()(session: CqlSession, typeStateFactory: TypeStateFactory) {

  import CassandraFirebaseSubscriptionTable._

  private val executor: CassandraAsyncExecutor = new CassandraAsyncExecutor(session)

  private val insert: PreparedStatement = session.prepare(insertInto(TABLE_NAME)
    .value(USER, bindMarker(USER))
    .value(DEVICE_CLIENT_ID, bindMarker(DEVICE_CLIENT_ID))
    .value(TOKEN, bindMarker(TOKEN))
    .value(ID, bindMarker(ID))
    .value(EXPIRES, bindMarker(EXPIRES))
    .value(TYPES, bindMarker(TYPES))
    .build())

  private val selectAll: PreparedStatement = session.prepare(selectFrom(TABLE_NAME)
    .all()
    .whereColumn(USER).isEqualTo(bindMarker(USER)).build())

  private val deleteOne: PreparedStatement = session.prepare(deleteFrom(TABLE_NAME)
    .whereColumn(USER).isEqualTo(bindMarker(USER))
    .whereColumn(DEVICE_CLIENT_ID).isEqualTo(bindMarker(DEVICE_CLIENT_ID))
    .build())

  private val deleteAllSubscriptions: PreparedStatement = session.prepare(deleteFrom(TABLE_NAME)
    .whereColumn(USER).isEqualTo(bindMarker(USER))
    .build())

  def insert(username: Username, subscription: FirebaseSubscription): SMono[FirebaseSubscription] = {
    val typeNames = CollectionConverters.asJava(subscription.types.map(_.asString()).toSet)
    val utcInstant = subscription.expires.value.withZoneSameInstant(ZoneOffset.UTC).toInstant

    val insertSubscription = insert.bind()
      .set(USER, username.asString, TypeCodecs.TEXT)
      .set(DEVICE_CLIENT_ID, subscription.deviceClientId.value, TypeCodecs.TEXT)
      .set(ID, subscription.id.value, TypeCodecs.UUID)
      .set(EXPIRES, utcInstant, TypeCodecs.TIMESTAMP)
      .set(TYPES, typeNames, FROZEN_OF_STRINGS_CODEC)
      .set(TOKEN, subscription.token.value, TypeCodecs.TEXT)

    SMono.fromPublisher(executor.executeVoid(insertSubscription)
      .thenReturn(subscription))
  }

  def selectAll(username: Username): SFlux[FirebaseSubscription] =
    SFlux.fromPublisher(executor.executeRows(selectAll.bind().set(USER, username.asString, TypeCodecs.TEXT))
      .map(toFirebaseSubscription))

  def deleteOne(username: Username, deviceClientId: String): SMono[Unit] =
    SMono.fromPublisher(executor.executeVoid(deleteOne.bind()
      .set(USER, username.asString, TypeCodecs.TEXT)
      .set(DEVICE_CLIENT_ID, deviceClientId, TypeCodecs.TEXT)))
      .`then`()

  def deleteAllSubscriptions(username: Username): SMono[Unit] =
    SMono.fromPublisher(executor.executeVoid(deleteAllSubscriptions.bind()
      .set(USER, username.asString, TypeCodecs.TEXT)))
      .`then`()

  private def toFirebaseSubscription(row: Row) =
    FirebaseSubscription(id = FirebaseSubscriptionId.apply(row.get(ID, TypeCodecs.UUID)),
      deviceClientId = DeviceClientId(row.get(DEVICE_CLIENT_ID, TypeCodecs.TEXT)),
      token = FirebaseToken(row.get(TOKEN, TypeCodecs.TEXT)),
      toExpires(row),
      toTypes(row))

  private def toExpires(row: Row): FirebaseSubscriptionExpiredTime =
    FirebaseSubscriptionExpiredTime(ZonedDateTime.ofInstant(row.get(EXPIRES, TypeCodecs.TIMESTAMP), ZoneOffset.UTC))

  private def toTypes(row: Row): Seq[TypeName] =
    CollectionConverters.asScala(row.get(TYPES, FROZEN_OF_STRINGS_CODEC))
      .flatMap(string => typeStateFactory.lenientParse(string).toSeq)
      .toSeq
}
