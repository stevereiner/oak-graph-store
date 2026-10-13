package org.integratedsemantics.oak.graph.arcadedb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.plugins.document.Collection;

import org.integratedsemantics.oak.graph.AbstractChildEdgesTest;
import org.integratedsemantics.oak.graph.GraphDocumentStore;
import org.junit.Test;

/**
 * The {@code CHILD_OF} edges in ArcadeDB.
 */
public class ArcadeDBChildEdgesTest extends AbstractChildEdgesTest {

    @Override
    protected GraphDocumentStore newDocumentStore() {
        return new ArcadeDBDocumentStore(ArcadeDBTestServer.config());
    }

    @Override
    protected void clearDatabase() {
        ArcadeDBTestServer.clear();
    }

    @Override
    protected String parentOf(String id) {
        return ArcadeDBEdgeQueries.parentOf(id);
    }

    @Override
    protected List<String> childrenOf(String id) {
        return ArcadeDBEdgeQueries.neighbors(id, "in");
    }

    @Override
    protected long edgeCount() {
        return ArcadeDBEdgeQueries.edgeCount();
    }

    /** The store disables super-node promotion: promoted vertices' stripe buckets aren't covered by LOCK TYPE. */
    @Test
    public void testSuperNodePromotionDisabled() {
        try (var db = ArcadeDBTestServer.openDatabase()) {
            List<?> settings = db.query("sql", "SELECT settings FROM schema:database").next().getProperty("settings");
            var threshold = settings.stream()
                                    .map(o -> (java.util.Map<?, ?>) (o instanceof com.arcadedb.query.sql.executor.Result r
                                            ? r.toMap() : o))
                                    .filter(m -> "arcadedb.graph.supernodeThreshold".equals(m.get("key")))
                                    .map(m -> ((Number) m.get("value")).intValue())
                                    .findFirst()
                                    .orElseThrow();
            assertEquals(0, (int) threshold);
        }
    }

    /**
     * Other data linked to the content nodes, in types created after the store read the database's types: removing a
     * node removes its edges of any type (the writes lock every vertex and edge type, read again when new ones appear).
     */
    @Test
    public void testRemoveWithOtherEdges() {
        create("/a", "/a/b");
        try (var db = ArcadeDBTestServer.openDatabase()) {
            db.command("sql", "CREATE VERTEX TYPE TestEntity IF NOT EXISTS");
            db.command("sql", "CREATE EDGE TYPE TEST_MENTIONS IF NOT EXISTS");
            db.command("sql", "CREATE VERTEX TestEntity SET name = 'acme'");
            db.command("sql", "CREATE EDGE TEST_MENTIONS FROM (SELECT FROM TestEntity WHERE name = 'acme') "
                    + "TO (SELECT FROM OakNode WHERE _id = :pId)", Map.of("pId", id("/a/b")));
        }
        try {
            store.remove(Collection.NODES, id("/a/b"));
            assertNull(store.find(Collection.NODES, id("/a/b")));
            try (var db = ArcadeDBTestServer.openDatabase()) {
                assertEquals(0L, count(db, "SELECT count(*) AS n FROM TEST_MENTIONS"));
                assertEquals(1L, count(db, "SELECT count(*) AS n FROM TestEntity"));
            }
        } finally {
            try (var db = ArcadeDBTestServer.openDatabase()) {
                db.command("sql", "DROP TYPE TEST_MENTIONS UNSAFE");
                db.command("sql", "DROP TYPE TestEntity UNSAFE");
            }
        }
    }

    protected static long count(com.arcadedb.remote.RemoteDatabase db, String sql) {
        return ((Number) db.query("sql", sql).next().getProperty("n")).longValue();
    }

    @Override
    protected String nameOf(String id) {
        return ArcadeDBEdgeQueries.nameOf(id);
    }

}
