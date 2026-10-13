package org.integratedsemantics.oak.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.Path;
import org.apache.jackrabbit.oak.plugins.document.Revision;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The {@code CHILD_OF} edges between node documents: subclasses give the store and the edge queries.
 */
public abstract class AbstractChildEdgesTest {

    protected GraphDocumentStore store;

    /** A new document store on the test database. */
    protected abstract GraphDocumentStore newDocumentStore();

    /** Deletes all the documents and edges of the test database. */
    protected abstract void clearDatabase();

    /** The id of the parent of a node document, from its edge ({@code null} if none); fails if more than one. */
    protected abstract String parentOf(String id);

    /** The ids of the children of a node document, from their edges, sorted. */
    protected abstract List<String> childrenOf(String id);

    /** The number of {@code CHILD_OF} edges. */
    protected abstract long edgeCount();

    /** The {@code name} stored with a node document ({@code null} if none). */
    protected abstract String nameOf(String id);

    @Before
    public void setUp() {
        store = newDocumentStore();
    }

    @After
    public void tearDown() {
        store.dispose();
        clearDatabase();
    }

    protected static UpdateOp node(String path) {
        var p = Path.fromString(path);
        var op = new UpdateOp(Utils.getIdFromPath(p), true);
        if (Utils.isLongPath(p)) {
            op.set(NodeDocument.PATH, path); // as Oak does: the id is hashed
        }
        return op;
    }

    protected void create(String... paths) {
        var ops = new ArrayList<UpdateOp>();
        for (var path : paths) {
            ops.add(node(path));
        }
        assertTrue(store.create(Collection.NODES, ops));
    }

    protected static String id(String path) {
        return Utils.getIdFromPath(path);
    }

    @Test
    public void testChildBeforeParent() {
        create("/a/b");
        assertNull(parentOf(id("/a/b")));
        // createOrUpdate path: the existing child gets linked to the new parent
        store.createOrUpdate(Collection.NODES, node("/a"));
        create("/");
        assertEquals(id("/a"), parentOf(id("/a/b")));
        assertEquals(id("/"), parentOf(id("/a")));
        assertNull(parentOf(id("/")));
        assertEquals(List.of(id("/a")), childrenOf(id("/")));
        assertEquals(2, edgeCount());
    }

    @Test
    public void testNameNotOakProperty() {
        // a JCR property named "name" (Oak key "name") is kept apart from the node name
        var op = node("/x");
        op.set("name", "a value");
        create("/");
        assertTrue(store.create(Collection.NODES, List.of(op)));
        assertEquals("x", nameOf(id("/x")));
        assertEquals("a value", store.find(Collection.NODES, id("/x"), 0).get("name"));
        var update = new UpdateOp(id("/x"), false);
        update.set("name", "another value");
        store.findAndUpdate(Collection.NODES, update);
        assertEquals("x", nameOf(id("/x")));
        assertEquals("another value", store.find(Collection.NODES, id("/x"), 0).get("name"));
    }

    @Test
    public void testBatch() {
        // children before their parent in the same batch: still one edge each
        create("/a/b", "/a/c", "/a", "/");
        assertEquals(List.of(id("/a/b"), id("/a/c")), childrenOf(id("/a")));
        assertEquals(id("/"), parentOf(id("/a")));
        assertEquals(3, edgeCount());
        // the names, stored for graph queries, not part of the Oak documents
        assertEquals("b", nameOf(id("/a/b")));
        assertEquals("a", nameOf(id("/a")));
        assertEquals("", nameOf(id("/")));
        assertNull(store.find(Collection.NODES, id("/a/b"), 0).get(GraphDocumentStore.NAME));
    }

    @Test
    public void testRemoveAndRecreateParent() {
        create("/", "/a", "/a/b");
        store.remove(Collection.NODES, id("/a"));
        assertNull(parentOf(id("/a/b")));
        assertEquals(List.of(), childrenOf(id("/")));
        assertEquals(0, edgeCount());

        create("/a");
        assertEquals(id("/a"), parentOf(id("/a/b")));
        assertEquals(id("/"), parentOf(id("/a")));
        assertEquals(2, edgeCount());
    }

    @Test
    public void testNotLinked() {
        create("/", "/a");
        // a previous document (revision history of /a), and documents whose ids are not node ids (no path, invalid path)
        var previousId = Utils.getPreviousIdFor(Path.fromString("/a"), new Revision(1, 0, 1), 0);
        assertTrue(store.create(Collection.NODES,
                List.of(new UpdateOp(previousId, true), new UpdateOp("foo", true), new UpdateOp("2:/a/", true))));
        assertNull(parentOf(previousId));
        assertNull(nameOf(previousId));
        assertNull(parentOf("foo"));
        assertNull(parentOf("2:/a/"));
        assertEquals(1, edgeCount());
    }

    @Test
    public void testLongPath() {
        var segment = "x".repeat(200);
        var parent = "/long/" + segment + "/" + segment;
        var child = parent + "/" + segment;
        assertTrue(Utils.isIdFromLongPath(id(child)));
        create("/", "/long", "/long/" + segment, parent, child);
        assertEquals(id(parent), parentOf(id(child)));
        assertEquals(List.of(id(child)), childrenOf(id(parent)));
        assertEquals(segment, nameOf(id(child)));
        assertEquals(4, edgeCount());
    }

}
