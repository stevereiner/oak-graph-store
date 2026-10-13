package org.integratedsemantics.oak.graph.arcadedb;

import static org.integratedsemantics.oak.graph.arcadedb.ArcadeDBDocumentStore.CHILD_OF_TYPE;
import static org.integratedsemantics.oak.graph.arcadedb.ArcadeDBDocumentStore.NODES_TYPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

/**
 * The edge queries of the tests, in ArcadeDB SQL.
 */
public final class ArcadeDBEdgeQueries {

    private ArcadeDBEdgeQueries() {
        // utility class
    }

    /** The ids of the vertices at the end of the given edge direction ({@code out} or {@code in}) of a node. */
    public static List<String> neighbors(String id, String direction) {
        try (var db = ArcadeDBTestServer.openDatabase()) {
            var rows = db.query("sql", "SELECT %s('%s')._id AS v FROM %s WHERE _id = :pId".formatted(direction,
                    CHILD_OF_TYPE, NODES_TYPE), Map.of("pId", id)).stream().toList();
            assertEquals("no such node: " + id, 1, rows.size());
            List<?> list = rows.get(0).getProperty("v");
            return list.stream().map(String.class::cast).sorted().toList();
        }
    }

    public static String parentOf(String id) {
        var parents = neighbors(id, "out");
        assertTrue("more than one parent: " + parents, parents.size() <= 1);
        return parents.isEmpty() ? null : parents.get(0);
    }

    public static String nameOf(String id) {
        try (var db = ArcadeDBTestServer.openDatabase()) {
            var rows = db.query("sql", "SELECT name AS v FROM %s WHERE _id = :pId".formatted(NODES_TYPE),
                    Map.of("pId", id)).stream().toList();
            assertEquals("no such node: " + id, 1, rows.size());
            return rows.get(0).getProperty("v");
        }
    }

    public static long edgeCount() {
        try (var db = ArcadeDBTestServer.openDatabase()) {
            Number n = db.query("sql", "SELECT count(*) AS c FROM " + CHILD_OF_TYPE).next().getProperty("c");
            return n.longValue();
        }
    }

    /** Node vertices (not the root, not previous documents) without exactly one parent edge. */
    public static long countNodesWithoutOneParent() {
        try (var db = ArcadeDBTestServer.openDatabase()) {
            var template = """
                    SELECT count(*) AS c FROM %s
                    WHERE _id <> '0:/' AND NOT (_id LIKE '%%:p/%%') AND out('%s').size() <> 1""";
            var sql = template.formatted(NODES_TYPE, CHILD_OF_TYPE);
            Number n = db.query("sql", sql).next().getProperty("c");
            return n.longValue();
        }
    }

}
