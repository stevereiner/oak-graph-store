package org.integratedsemantics.oak.graph.osgi;

import static org.apache.jackrabbit.oak.spi.whiteboard.WhiteboardUtils.registerMBean;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.jackrabbit.oak.api.jmx.CheckpointMBean;
import org.apache.jackrabbit.oak.osgi.OsgiWhiteboard;
import org.apache.jackrabbit.oak.plugins.blob.BlobGC;
import org.apache.jackrabbit.oak.plugins.blob.BlobGCMBean;
import org.apache.jackrabbit.oak.plugins.document.DocumentCheckpointMBean;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStoreBuilder;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.GarbageCollectableBlobStore;
import org.apache.jackrabbit.oak.spi.cluster.ClusterRepositoryInfo;
import org.apache.jackrabbit.oak.spi.commit.ObserverTracker;
import org.apache.jackrabbit.oak.spi.state.Clusterable;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.RevisionGC;
import org.apache.jackrabbit.oak.spi.state.RevisionGCMBean;
import org.apache.jackrabbit.oak.spi.whiteboard.Registration;
import org.apache.jackrabbit.oak.spi.whiteboard.WhiteboardExecutor;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;
import org.integratedsemantics.oak.graph.arcadedb.ArcadeDBConfig;
import org.integratedsemantics.oak.graph.arcadedb.ArcadeDBDocumentStore;
import org.integratedsemantics.oak.graph.neo4j.Neo4jConfig;
import org.integratedsemantics.oak.graph.neo4j.Neo4jDocumentStore;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a {@code DocumentNodeStore} on a graph {@code DocumentStore} in OSGi, and registers it like Oak's
 * {@code DocumentNodeStoreService} does for MongoDB and RDB.
 * <p>
 * This class is in a fragment of the {@code oak-store-document} bundle: that bundle doesn't export the
 * {@code DocumentStore} API, so the stores and this class run in its class loader. The services are registered with
 * its bundle context, as {@code NodeStore} (what Sling's repository waits for), {@code DocumentNodeStore} (what Oak's
 * discovery lite service waits for) and {@code Clusterable}; the OSGi {@code Observer} services are attached to the
 * node store. The checkpoint MBean is registered too (Oak's Lucene index provider waits for it), and the revision and
 * blob garbage collection MBeans: Sling's maintenance tasks run them.
 * <p>
 * Configuration ({@code backend} {@code arcadedb} or {@code neo4j}):
 * <ul>
 * <li>{@code arcadedb.host}, {@code arcadedb.port} (2480), {@code arcadedb.database}, {@code arcadedb.username},
 * {@code arcadedb.password}</li>
 * <li>{@code neo4j.uri}, {@code neo4j.username}, {@code neo4j.password}, {@code neo4j.database} (none: the server's
 * default), {@code neo4j.labelPrefix} ({@code Oak})</li>
 * <li>{@code clusterId}: 0 (default) to acquire one automatically</li>
 * </ul>
 */
public final class GraphNodeStores {

    private static final Logger LOG = LoggerFactory.getLogger(GraphNodeStores.class);

    /** Revisions and binaries older than this can be garbage collected (Oak's default). */
    private static final long GC_MAX_AGE_SECONDS = TimeUnit.DAYS.toSeconds(1);

    private GraphNodeStores() {
        // utility class
    }

    /**
     * Starts and registers a node store.
     *
     * @param config the configuration
     * @param blobStore where binaries go (not in the graph database)
     * @return what stops and unregisters it
     */
    public static Closeable start(Map<String, ?> config, BlobStore blobStore) {
        var builder = DocumentNodeStoreBuilder.newDocumentNodeStoreBuilder();
        var store = newDocumentStore(config, builder);
        DocumentNodeStore nodeStore;
        try {
            nodeStore = builder.setDocumentStore(store)
                               .setBlobStore(blobStore)
                               .setClusterId(integer(config, "clusterId", 0))
                               .build();
        } catch (RuntimeException e) {
            store.dispose();
            throw e;
        }
        var context = FrameworkUtil.getBundle(DocumentNodeStore.class).getBundleContext();
        var props = new Hashtable<String, Object>();
        props.put(Constants.SERVICE_PID, DocumentNodeStore.class.getName());
        props.put("oak.nodestore.description", store.getMetadata()
                                                    .entrySet()
                                                    .stream()
                                                    .map(en -> en.getKey() + "=" + en.getValue())
                                                    .toArray(String[]::new));
        var registration = context.registerService(new String[] { NodeStore.class.getName(),
                DocumentNodeStore.class.getName(), Clusterable.class.getName() }, nodeStore, props);
        var observerTracker = new ObserverTracker(nodeStore);
        observerTracker.start(context);
        var whiteboard = new OsgiWhiteboard(context);
        var executor = new WhiteboardExecutor();
        executor.start(whiteboard);
        var mbeans = registerMBeans(nodeStore, blobStore, whiteboard, executor);
        LOG.info("Started DocumentNodeStore on {} (cluster id {})", store.getMetadata().get("server"),
                nodeStore.getClusterId());
        return () -> {
            mbeans.forEach(Registration::unregister);
            executor.stop();
            observerTracker.stop();
            registration.unregister();
            nodeStore.dispose(); // disposes the document store too
            LOG.info("Stopped DocumentNodeStore on {}", store.getMetadata().get("server"));
        };
    }

    /**
     * The checkpoint, revision garbage collection and blob garbage collection MBeans, like Oak's
     * {@code DocumentNodeStoreService}. Oak's {@code LuceneIndexProviderService} requires the {@link CheckpointMBean}
     * service: without it, Lucene indexes are neither updated nor used.
     */
    static ArrayList<Registration> registerMBeans(DocumentNodeStore nodeStore, BlobStore blobStore,
            OsgiWhiteboard whiteboard, WhiteboardExecutor executor) {
        var registrations = new ArrayList<Registration>();
        registrations.add(registerMBean(whiteboard, CheckpointMBean.class, new DocumentCheckpointMBean(nodeStore),
                CheckpointMBean.TYPE, "Document node store checkpoint management"));
        if (blobStore instanceof GarbageCollectableBlobStore) {
            var gc = nodeStore.createBlobGarbageCollector(GC_MAX_AGE_SECONDS,
                    ClusterRepositoryInfo.getOrCreateId(nodeStore), whiteboard, StatisticsProvider.NOOP);
            registrations.add(registerMBean(whiteboard, BlobGCMBean.class, new BlobGC(gc, executor), BlobGCMBean.TYPE,
                    "Document node store blob garbage collection"));
        }
        var versionGC = nodeStore.getVersionGarbageCollector();
        Runnable startGC = () -> {
            try {
                LOG.info("Revision garbage collection: {}", versionGC.gc(GC_MAX_AGE_SECONDS, TimeUnit.SECONDS));
            } catch (IOException e) {
                LOG.warn("Revision garbage collection failed", e);
            }
        };
        registrations.add(registerMBean(whiteboard, RevisionGCMBean.class,
                new RevisionGC(startGC, versionGC::cancel, versionGC::getStatus, executor), RevisionGCMBean.TYPE,
                "Document node store revision garbage collection"));
        return registrations;
    }

    static DocumentStore newDocumentStore(Map<String, ?> config, DocumentNodeStoreBuilder<?> builder) {
        var backend = string(config, "backend", "arcadedb");
        return switch (backend) {
            case "arcadedb" -> new ArcadeDBDocumentStore(new ArcadeDBConfig(string(config, "arcadedb.host",
                    "localhost"), integer(config, "arcadedb.port", ArcadeDBConfig.DEFAULT_PORT),
                    string(config, "arcadedb.database", "oak"), string(config, "arcadedb.username", "root"),
                    string(config, "arcadedb.password", "")), builder);
            case "neo4j" -> new Neo4jDocumentStore(new Neo4jConfig(
                    string(config, "neo4j.uri", "bolt://localhost:7687"), string(config, "neo4j.username", "neo4j"), string(config, "neo4j.password", ""),
                    string(config, "neo4j.database", null), string(config, "neo4j.labelPrefix", null)), builder);
            default -> throw new IllegalArgumentException("Unknown backend: " + backend + " (arcadedb or neo4j)");
        };
    }

    static String string(Map<String, ?> config, String key, String defaultValue) {
        var value = config.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString().trim();
    }

    static int integer(Map<String, ?> config, String key, int defaultValue) {
        var value = string(config, key, null);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

}
