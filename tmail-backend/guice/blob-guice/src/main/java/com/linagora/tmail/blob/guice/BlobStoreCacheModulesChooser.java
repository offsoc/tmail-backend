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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

/**
 * This class is copied & adapted from {@link org.apache.james.modules.blobstore.BlobStoreCacheModulesChooser}
 */

package com.linagora.tmail.blob.guice;

import java.io.FileNotFoundException;
import java.util.List;

import jakarta.inject.Named;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.cassandra.components.CassandraDataDefinition;
import org.apache.james.backends.cassandra.init.configuration.InjectionNames;
import org.apache.james.blob.api.BlobStore;
import org.apache.james.blob.api.MetricableBlobStore;
import org.apache.james.blob.cassandra.cache.BlobStoreCache;
import org.apache.james.blob.cassandra.cache.CachedBlobStore;
import org.apache.james.blob.cassandra.cache.CassandraBlobCacheDataDefinition;
import org.apache.james.blob.cassandra.cache.CassandraBlobStoreCache;
import org.apache.james.blob.cassandra.cache.CassandraCacheConfiguration;
import org.apache.james.modules.mailbox.CassandraCacheSessionModule;
import org.apache.james.modules.mailbox.ConfigurationComponent;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

public class BlobStoreCacheModulesChooser {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobStoreCacheModulesChooser.class);

    static class CacheDisabledModule extends AbstractModule {
        @Provides
        @Named(MetricableBlobStore.BLOB_STORE_IMPLEMENTATION)
        @Singleton
        BlobStore provideBlobStore(@Named(CachedBlobStore.BACKEND) BlobStore blobStore) {
            return blobStore;
        }
    }

    static class CacheEnabledModule extends AbstractModule {
        @Override
        protected void configure() {
            bind(CassandraBlobStoreCache.class).in(Scopes.SINGLETON);
            bind(BlobStoreCache.class).to(CassandraBlobStoreCache.class);

            Multibinder.newSetBinder(binder(), CassandraDataDefinition.class, Names.named(InjectionNames.CACHE))
                .addBinding()
                .toInstance(CassandraBlobCacheDataDefinition.MODULE);
        }

        @Provides
        @Named(MetricableBlobStore.BLOB_STORE_IMPLEMENTATION)
        @Singleton
        BlobStore provideBlobStore(CachedBlobStore cachedBlobStore) {
            return cachedBlobStore;
        }

        @Provides
        @Singleton
        CassandraCacheConfiguration providesCacheConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
            try {
                Configuration configuration = propertiesProvider.getConfigurations(ConfigurationComponent.NAMES);
                return CassandraCacheConfiguration.from(configuration);
            } catch (FileNotFoundException e) {
                LOGGER.warn("Could not find " + ConfigurationComponent.NAME + " configuration file, using cassandra cache defaults");
                return CassandraCacheConfiguration.DEFAULT;
            }
        }
    }

    public static List<Module> chooseModules(BlobStoreConfiguration blobStoreConfiguration) {
        if (blobStoreConfiguration.cacheEnabled()) {
            return ImmutableList.of(new CassandraCacheSessionModule(), new CacheEnabledModule());
        }
        return ImmutableList.of(new CacheDisabledModule());
    }
}
