package org.integratedsemantics.oak.graph.sling;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.integratedsemantics.oak.graph.osgi.GraphNodeStores;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * OSGi component starting Oak's {@code DocumentNodeStore} on a graph database (ArcadeDB or Neo4j), when configured:
 * the replacement of Oak's {@code DocumentNodeStoreService} (MongoDB, RDB) in Sling. See {@link GraphNodeStores} for
 * the configuration.
 * <p>
 * Binaries go to the {@link BlobStore} service, typically Oak's file data store
 * ({@code org.apache.jackrabbit.oak.plugins.blob.datastore.FileDataStore} configuration).
 */
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
public class GraphNodeStoreService {

    @Reference
    private BlobStore blobStore;

    private Closeable nodeStore;

    @Activate
    protected void activate(Map<String, Object> config) {
        nodeStore = GraphNodeStores.start(config, blobStore);
    }

    @Deactivate
    protected void deactivate() throws IOException {
        if (nodeStore != null) {
            nodeStore.close();
            nodeStore = null;
        }
    }

}
