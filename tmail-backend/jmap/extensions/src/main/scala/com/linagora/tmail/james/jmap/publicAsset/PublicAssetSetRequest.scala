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

package com.linagora.tmail.james.jmap.publicAsset

import java.util.UUID

import com.linagora.tmail.james.jmap.json.PublicAssetSerializer.PublicAssetSetUpdateReads
import com.linagora.tmail.james.jmap.method.PublicAssetSetMethod.LOGGER
import com.linagora.tmail.james.jmap.method.standardErrorMessage
import com.linagora.tmail.james.jmap.publicAsset.ImageContentType.ImageContentType
import org.apache.james.jmap.api.model.IdentityId
import org.apache.james.jmap.core.Id.Id
import org.apache.james.jmap.core.SetError.SetErrorDescription
import org.apache.james.jmap.core.{AccountId, SetError, UuidState}
import org.apache.james.jmap.method.WithAccountId
import org.apache.james.jmap.routes.BlobNotFoundException
import play.api.libs.json.{JsError, JsObject, JsSuccess}

import scala.util.Try

case class PublicAssetSetRequest(accountId: AccountId,
                                 create: Option[Map[PublicAssetCreationId, JsObject]],
                                 update: Option[Map[UnparsedPublicAssetId, PublicAssetPatchObject]],
                                 destroy: Option[Seq[UnparsedPublicAssetId]]) extends WithAccountId

case class PublicAssetCreationId(id: Id)

case class UnparsedPublicAssetId(id: String) {

  def tryAsPublicAssetId: Either[IllegalArgumentException, PublicAssetId] =
    PublicAssetId.fromString(id).toEither
      .left.map {
        case e: IllegalArgumentException => e
        case e => new IllegalArgumentException(e)
      }
}

object PublicAssetCreationResponse {
  def from(publicAsset: PublicAssetStorage): PublicAssetCreationResponse = {
    PublicAssetCreationResponse(publicAsset.id,
      publicAsset.publicURI,
      publicAsset.size.value,
      publicAsset.contentType)
  }
}

case class PublicAssetCreationResponse(id: PublicAssetId,
                                       publicURI: PublicURI,
                                       size: Long,
                                       contentType: ImageContentType)

case class PublicAssetSetResponse(accountId: AccountId,
                                  oldState: Option[UuidState],
                                  newState: UuidState,
                                  created: Option[Map[PublicAssetCreationId, PublicAssetCreationResponse]],
                                  notCreated: Option[Map[PublicAssetCreationId, SetError]],
                                  updated: Option[Map[PublicAssetId, PublicAssetUpdateResponse]],
                                  notUpdated: Option[Map[UnparsedPublicAssetId, SetError]],
                                  destroyed: Option[Seq[PublicAssetId]],
                                  notDestroyed: Option[Map[UnparsedPublicAssetId, SetError]])

case class PublicAssetCreationParseException(setError: SetError) extends PublicAssetException {
  override val message: String = s"Invalid public asset creation request: ${setError.description}"
}

case class PublicAssetInvalidBlobIdException(blobId: String) extends PublicAssetException {
  override val message: String = s"Invalid blobId: $blobId"
}

case class PublicAssetBlobIdNotFoundException(blobId: String) extends PublicAssetException {
  override val message: String = s"BlobId not found: $blobId"
}

case class PublicAssetInvalidIdentityIdException(identityId: String) extends PublicAssetException {
  override val message: String = s"Invalid identityId: $identityId"
}

sealed trait PublicAssetCreationResult {
  def publicAssetCreationId: PublicAssetCreationId
}

case class PublicAssetCreationSuccess(publicAssetCreationId: PublicAssetCreationId, publicAssetCreationResponse: PublicAssetCreationResponse) extends PublicAssetCreationResult

case class PublicAssetCreationFailure(publicAssetCreationId: PublicAssetCreationId, exception: Throwable) extends PublicAssetCreationResult {
  def asPublicAssetSetError: SetError = {
    val errorDescription = SetError.SetErrorDescription(exception.getMessage)
    exception match {
      case e: PublicAssetCreationParseException =>
        LOGGER.debug("Error parsing public creation asset request {}", publicAssetCreationId.id.value, exception)
        e.setError
      case _: PublicAssetQuotaLimitExceededException =>
        LOGGER.info("Quota limit exceeded when creating public asset {}", publicAssetCreationId.id.value, exception)
        SetError.overQuota(errorDescription)
      case _: PublicAssetException | _: BlobNotFoundException | _: IllegalArgumentException =>
        LOGGER.debug("Error creating public asset {}", publicAssetCreationId.id.value, exception)
        SetError.invalidArguments(errorDescription)
      case _ =>
        LOGGER.warn("Unexpected exception when creating public asset {}", publicAssetCreationId.id.value, exception)
        SetError.serverFail(errorDescription)
    }
  }
}

case class PublicAssetCreationResults(created: Seq[PublicAssetCreationResult]) {
  def retrieveCreated: Map[PublicAssetCreationId, PublicAssetCreationResponse] = created
    .flatMap(result => result match {
      case success: PublicAssetCreationSuccess => Some(success.publicAssetCreationId, success.publicAssetCreationResponse)
      case _ => None
    })
    .toMap
    .map(creation => (creation._1, creation._2))

  def retrieveErrors: Map[PublicAssetCreationId, SetError] = created
    .flatMap(result => result match {
      case failure: PublicAssetCreationFailure => Some(failure.publicAssetCreationId, failure.asPublicAssetSetError)
      case _ => None
    }).toMap
}

object PublicAssetPatchObject {
  val identityIdsProperty: String = "identityIds"

  def validateIdentityId(identityId: String): Either[IllegalArgumentException, IdentityId] =
    Try(UUID.fromString(identityId))
      .toEither
      .map(IdentityId(_))
      .left.map {
        case e: IllegalArgumentException => e
        case e => new IllegalArgumentException(e)
      }
}

case class PublicAssetPatchObject(value: JsObject) {

  def validate: Either[PublicAssetPatchUpdateValidationException, ValidatedPublicAssetPatchObject] =
    (PublicAssetSetUpdateReads.reads(value) match {
      case JsError(errors) => Left(PublicAssetPatchUpdateValidationException(standardErrorMessage(errors)))
      case JsSuccess(patchObject, _) => Right(patchObject)
    }).flatMap(e => e.validate())
}

case class PublicAssetPatchUpdateValidationException(error: String) extends PublicAssetException {
  override val message: String = error
}

case class ValidatedPublicAssetPatchObject(resetIdentityIds: Option[Seq[IdentityId]],
                                           identityIdsToAdd: Seq[IdentityId] = Seq.empty,
                                           identityIdsToRemove: Seq[IdentityId] = Seq.empty) {

  // if resetIdentityIds is defined, then identityIdsToAdd and identityIdsToRemove must be empty
  // if resetIdentityIds is not defined, then identityIdsToAdd and identityIdsToRemove must not be empty
  // the value of identityIdsToAdd and identityIdsToRemove must be not conflict with each other
  def validate(): Either[PublicAssetPatchUpdateValidationException, ValidatedPublicAssetPatchObject] =
    resetIdentityIds match {
      case Some(_) => (identityIdsToAdd, identityIdsToRemove) match {
        case (Seq(), Seq()) => Right(this)
        case _ => Left(PublicAssetPatchUpdateValidationException("Cannot reset identityIds and add/remove identityIds at the same time"))
      }
      case None => (identityIdsToAdd, identityIdsToRemove) match {
        case (Seq(), Seq()) => Left(PublicAssetPatchUpdateValidationException("Cannot update identityIds with empty request"))
        case _ => if (identityIdsToAdd.intersect(identityIdsToRemove).nonEmpty)
          Left(PublicAssetPatchUpdateValidationException("Cannot add and remove the same identityId at the same time"))
        else
          Right(this)
      }
    }

  def isReset: Boolean = resetIdentityIds.isDefined
}

case class PublicAssetUpdateResponse()

sealed trait PublicAssetUpdateResult

case class PublicAssetUpdateFailure(id: UnparsedPublicAssetId, exception: Throwable) extends PublicAssetUpdateResult {
  def asSetError: SetError = exception match {
    case e: PublicAssetException =>
      LOGGER.info("Has an error when update public asset ", e)
      SetError.invalidArguments(SetErrorDescription(e.getMessage))
    case e: IllegalArgumentException =>
      LOGGER.info("Has an error when update public asset ", e)
      SetError.invalidArguments(SetErrorDescription(e.getMessage))
    case _ =>
      LOGGER.error("Unexpected exception when update public asset ", exception)
      SetError.serverFail(SetErrorDescription(exception.getMessage))
  }
}

case class PublicAssetUpdateSuccess(id: PublicAssetId) extends PublicAssetUpdateResult

case class PublicAssetUpdateResults(results: Seq[PublicAssetUpdateResult]) {
  def updated: Map[PublicAssetId, PublicAssetUpdateResponse] =
    results.flatMap(result => result match {
      case success: PublicAssetUpdateSuccess => Some((success.id, PublicAssetUpdateResponse()))
      case _ => None
    }).toMap

  def notUpdated: Map[UnparsedPublicAssetId, SetError] =
    results.flatMap(result => result match {
      case failure: PublicAssetUpdateFailure => Some(failure.id, failure.asSetError)
      case _ => None
    }).toMap
}

sealed trait PublicAssetDeletionResult

case class PublicAssetDeletionSuccess(id: PublicAssetId) extends PublicAssetDeletionResult

case class PublicAssetDeletionFailure(id: UnparsedPublicAssetId, exception: Throwable) extends PublicAssetDeletionResult {
  def asSetError: SetError = exception match {
    case e: Exception if e.isInstanceOf[PublicAssetException] || e.isInstanceOf[IllegalArgumentException] =>
      LOGGER.info("Has error when delete public asset ", exception)
      SetError.invalidArguments(SetErrorDescription(e.getMessage))
    case _ =>
      LOGGER.warn("Unexpected exception when delete public asset ", exception)
      SetError.serverFail(SetErrorDescription(exception.getMessage))
  }
}

case class PublicAssetDeletionResults(results: Seq[PublicAssetDeletionResult]) {
  def destroyed: Seq[PublicAssetId] =
    results.flatMap(result => result match {
      case success: PublicAssetDeletionSuccess => Some(success.id)
      case _ => None
    })

  def retrieveErrors: Map[UnparsedPublicAssetId, SetError] =
    results.flatMap(result => result match {
      case failure: PublicAssetDeletionFailure => Some(failure.id, failure.asSetError)
      case _ => None
    }).toMap
}