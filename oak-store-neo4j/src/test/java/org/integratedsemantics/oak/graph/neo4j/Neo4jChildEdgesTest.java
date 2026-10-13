package org.integratedsemantics.oak.graph.neo4j;

import java.util.List;

import org.integratedsemantics.oak.graph.AbstractChildEdgesTest;
import org.integratedsemantics.oak.graph.GraphDocumentStore;

/**
 * The {@code CHILD_OF} relationships in Neo4j.
 */
public class Neo4jChildEdgesTest extends AbstractChildEdgesTest {

    @Override
    protected GraphDocumentStore newDocumentStore() {
        return new Neo4jDocumentStore(Neo4jTestServer.config());
    }

    @Override
    protected void clearDatabase() {
        Neo4jTestServer.clear();
    }

    @Override
    protected String parentOf(String id) {
        return Neo4jEdgeQueries.parentOf(id);
    }

    @Override
    protected List<String> childrenOf(String id) {
        return Neo4jEdgeQueries.neighbors(id, "in");
    }

    @Override
    protected long edgeCount() {
        return Neo4jEdgeQueries.edgeCount();
    }

    @Override
    protected String nameOf(String id) {
        return Neo4jEdgeQueries.nameOf(id);
    }

}
