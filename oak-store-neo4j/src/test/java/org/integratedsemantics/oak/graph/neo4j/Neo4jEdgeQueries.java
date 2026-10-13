package org.integratedsemantics.oak.graph.neo4j;

import static org.integratedsemantics.oak.graph.ChildLink.CHILD_OF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

/**
 * The edge queries of the tests, in Cypher.
 */
public final class Neo4jEdgeQueries {

    private static final String NODE = "`" + Neo4jTestServer.LABEL_PREFIX + "Node`";

    private Neo4jEdgeQueries() {
        // utility class
    }

    /** The ids of the parents ({@code out}) or children ({@code in}) of a node, sorted. */
    public static List<String> neighbors(String id, String direction) {
        var pattern = "out".equals(direction) ? "(d)-[:%s]->(n)" : "(d)<-[:%s]-(n)";
        var template = "MATCH (d:%s {_id: $id}) OPTIONAL MATCH " + pattern + " RETURN collect(n._id) AS v";
        var cypher = template.formatted(NODE, CHILD_OF);
        var records = Neo4jTestServer.query(cypher, Map.of("id", id));
        assertEquals("no such node: " + id, 1, records.size());
        return records.get(0).get("v").asList(v -> v.asString()).stream().sorted().toList();
    }

    public static String parentOf(String id) {
        var parents = neighbors(id, "out");
        assertTrue("more than one parent: " + parents, parents.size() <= 1);
        return parents.isEmpty() ? null : parents.get(0);
    }

    public static String nameOf(String id) {
        var records = Neo4jTestServer.query("MATCH (d:%s {_id: $id}) RETURN d.name AS v".formatted(NODE),
                Map.of("id", id));
        assertEquals("no such node: " + id, 1, records.size());
        var v = records.get(0).get("v");
        return v.isNull() ? null : v.asString();
    }

    public static long edgeCount() {
        return Neo4jTestServer.query("MATCH (:%s)-[r:%s]->() RETURN count(r) AS c".formatted(NODE, CHILD_OF),
                Map.of()).get(0).get("c").asLong();
    }

    /** Node documents (not the root, not previous documents) without exactly one parent edge. */
    public static long countNodesWithoutOneParent() {
        var cypher = """
                MATCH (d:%s) WHERE d._id <> '0:/' AND NOT d._id CONTAINS ':p/'
                  AND COUNT { (d)-[:%s]->() } <> 1
                RETURN count(d) AS c""".formatted(NODE, CHILD_OF);
        return Neo4jTestServer.query(cypher, Map.of()).get(0).get("c").asLong();
    }

}
