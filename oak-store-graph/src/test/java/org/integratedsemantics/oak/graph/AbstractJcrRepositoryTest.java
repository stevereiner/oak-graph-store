package org.integratedsemantics.oak.graph;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.Value;
import javax.jcr.query.Query;

import org.apache.jackrabbit.api.JackrabbitRepository;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStoreBuilder;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.spi.blob.MemoryBlobStore;
import org.junit.After;
import org.junit.Test;

/**
 * A JCR repository (Oak, DocumentNodeStore) on a graph document store: subclasses give the store and the edge queries.
 */
public abstract class AbstractJcrRepositoryTest {

    /** A new document store on the test database (each node store gets its own). */
    protected abstract DocumentStore newDocumentStore();

    /** Deletes all the documents and edges of the test database. */
    protected abstract void clearDatabase();

    /** The number of node documents (not the root, not previous documents) without exactly one parent edge. */
    protected abstract long countNodesWithoutOneParent();

    /** The ids of the parents of a node document, from its edges. */
    protected abstract List<String> parentIds(String id);

    /** Binaries are not in the graph database: the blob store is shared by the node stores of a test. */
    protected final MemoryBlobStore blobStore = new MemoryBlobStore();

    protected final List<DocumentNodeStore> nodeStores = new ArrayList<>();

    protected final List<Repository> repositories = new ArrayList<>();

    @After
    public void tearDown() {
        repositories.forEach(r -> ((JackrabbitRepository) r).shutdown());
        nodeStores.forEach(DocumentNodeStore::dispose);
        clearDatabase();
    }

    protected DocumentNodeStore newNodeStore(int clusterId) {
        var nodeStore = DocumentNodeStoreBuilder.newDocumentNodeStoreBuilder()
                                                .setDocumentStore(newDocumentStore())
                                                .setBlobStore(blobStore)
                                                .setClusterId(clusterId)
                                                // background operations on, as in production (on Oak 1.x they
                                                // also renew the cluster lease); tests still run them explicitly
                                                .build();
        nodeStores.add(nodeStore);
        return nodeStore;
    }

    protected Repository newRepository(DocumentNodeStore nodeStore) {
        var repository = new Jcr(new Oak(nodeStore)).createRepository();
        repositories.add(repository);
        return repository;
    }

    /** Shuts down a repository and its node store. */
    protected void shutdown(Repository repository, DocumentNodeStore nodeStore) {
        ((JackrabbitRepository) repository).shutdown();
        repositories.remove(repository);
        nodeStore.dispose();
        nodeStores.remove(nodeStore);
    }

    protected static Session login(Repository repository) throws RepositoryException {
        return repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
    }

    @Test
    public void testContentSurvivesRestart() throws Exception {
        var date = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        date.setTimeInMillis(1_700_000_000_000L);
        var bytes = "Hello graph".getBytes(UTF_8);

        var nodeStore = newNodeStore(1);
        var repository = newRepository(nodeStore);
        var session = login(repository);
        try {
            var folder = session.getRootNode().addNode("docs", "nt:folder");
            var file = folder.addNode("hello.txt", "nt:file");
            var content = file.addNode("jcr:content", "nt:resource");
            content.setProperty("jcr:mimeType", "text/plain");
            content.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream(bytes)));
            var props = session.getRootNode().addNode("props", "nt:unstructured");
            props.setProperty("string", "café 𝄞");
            props.setProperty("long", 42L);
            props.setProperty("double", 3.5);
            props.setProperty("boolean", true);
            props.setProperty("date", date);
            props.setProperty("multi", new String[] { "a", "b", "c" });
            props.addNode("toRemove", "nt:unstructured");
            session.save();

            // second save: modify and remove
            props.setProperty("long", 43L);
            props.getNode("toRemove").remove();
            session.save();
        } finally {
            session.logout();
        }
        shutdown(repository, nodeStore);

        // a new repository on the same database
        repository = newRepository(newNodeStore(1));
        session = login(repository);
        try {
            var content = session.getNode("/docs/hello.txt/jcr:content");
            assertEquals("text/plain", content.getProperty("jcr:mimeType").getString());
            try (var in = content.getProperty("jcr:data").getBinary().getStream()) {
                assertArrayEquals(bytes, in.readAllBytes());
            }
            var props = session.getNode("/props");
            assertEquals("café 𝄞", props.getProperty("string").getString());
            assertEquals(43L, props.getProperty("long").getLong());
            assertEquals(3.5, props.getProperty("double").getDouble(), 0);
            assertTrue(props.getProperty("boolean").getBoolean());
            assertEquals(date.getTimeInMillis(), props.getProperty("date").getDate().getTimeInMillis());
            var multi = Arrays.stream(props.getProperty("multi").getValues()).map(AbstractJcrRepositoryTest::string)
                              .toList();
            assertEquals(List.of("a", "b", "c"), multi);
            assertFalse(props.hasNode("toRemove"));
        } finally {
            session.logout();
        }
        assertChildEdges();
    }

    /** Every node document but the root (and previous documents) has exactly one parent edge, to its parent. */
    protected void assertChildEdges() {
        assertEquals("node documents without exactly one parent edge", 0, countNodesWithoutOneParent());
        assertEquals(List.of("2:/docs/hello.txt"), parentIds("3:/docs/hello.txt/jcr:content"));
    }

    @Test
    public void testQuery() throws Exception {
        var repository = newRepository(newNodeStore(1));
        var session = login(repository);
        try {
            var parent = session.getRootNode().addNode("q", "nt:unstructured");
            for (int i = 0; i < 20; i++) {
                var node = parent.addNode("n" + i, "nt:unstructured");
                node.setProperty("color", i % 4 == 0 ? "red" : "blue");
            }
            session.save();

            var sql = "SELECT * FROM [nt:unstructured] AS n WHERE n.color = 'red' AND ISDESCENDANTNODE(n, '/q')";
            var query = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
            var names = new ArrayList<String>();
            for (var it = query.execute().getNodes(); it.hasNext();) {
                names.add(it.nextNode().getName());
            }
            names.sort(null);
            assertEquals(List.of("n0", "n12", "n16", "n4", "n8"), names);
        } finally {
            session.logout();
        }
    }

    @Test
    public void testVersioning() throws Exception {
        var repository = newRepository(newNodeStore(1));
        var session = login(repository);
        try {
            Node doc = session.getRootNode().addNode("versioned", "nt:unstructured");
            doc.addMixin("mix:versionable");
            doc.setProperty("title", "v1");
            session.save();

            var versionManager = session.getWorkspace().getVersionManager();
            var v1 = versionManager.checkin("/versioned");
            versionManager.checkout("/versioned");
            doc.setProperty("title", "v2");
            session.save();
            versionManager.checkin("/versioned");

            var history = versionManager.getVersionHistory("/versioned");
            int count = 0;
            for (var it = history.getAllVersions(); it.hasNext(); it.nextVersion()) {
                count++;
            }
            assertEquals(3, count); // root version, v1, v2

            versionManager.restore(v1, true);
            assertEquals("v1", session.getNode("/versioned").getProperty("title").getString());
        } finally {
            session.logout();
        }
    }

    @Test
    public void testCluster() throws Exception {
        // the second cluster node starts once the first one has written (and published) the initial content:
        // otherwise both write it, and conflict
        var nodeStore1 = newNodeStore(1);
        var session1 = login(newRepository(nodeStore1));
        nodeStore1.runBackgroundOperations();
        var nodeStore2 = newNodeStore(2);
        var session2 = login(newRepository(nodeStore2));
        try {
            session1.getRootNode().addNode("fromCluster1", "nt:unstructured").setProperty("p", "one");
            session1.save();

            // the change becomes visible to the other cluster node through background operations
            assertEquals("one", waitForValue(session2, nodeStore1, nodeStore2, "/fromCluster1", "p", "one"));

            session2.getNode("/fromCluster1").setProperty("p", "two");
            session2.save();
            assertEquals("two", waitForValue(session1, nodeStore2, nodeStore1, "/fromCluster1", "p", "two"));
        } finally {
            session1.logout();
            session2.logout();
        }
    }

    /**
     * Waits (30 s at most) until a session sees a property value written through another cluster node: background
     * operations (also run by the node stores' own threads) publish the change, then make it visible.
     */
    protected static String waitForValue(Session session, DocumentNodeStore writer, DocumentNodeStore reader,
            String path, String property, String expected) throws RepositoryException, InterruptedException {
        String value = null;
        for (long end = System.currentTimeMillis() + 30_000; System.currentTimeMillis() < end;) {
            writer.runBackgroundOperations();
            reader.runBackgroundOperations();
            session.refresh(false);
            value = session.nodeExists(path) && session.getNode(path).hasProperty(property)
                    ? session.getNode(path).getProperty(property).getString() : null;
            if (expected.equals(value)) {
                break;
            }
            Thread.sleep(200);
        }
        return value;
    }

    protected static String string(Value value) {
        try {
            return value.getString();
        } catch (RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

}
