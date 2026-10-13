package org.integratedsemantics.oak.graph.neo4j;

import java.util.List;

import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.integratedsemantics.oak.graph.AbstractJcrRepositoryTest;

/**
 * A JCR repository (Oak, DocumentNodeStore) on the Neo4j document store.
 */
public class Neo4jJcrRepositoryTest extends AbstractJcrRepositoryTest {

    @Override
    protected DocumentStore newDocumentStore() {
        return new Neo4jDocumentStore(Neo4jTestServer.config());
    }

    @Override
    protected void clearDatabase() {
        Neo4jTestServer.clear();
    }

    @Override
    protected long countNodesWithoutOneParent() {
        return Neo4jEdgeQueries.countNodesWithoutOneParent();
    }

    @Override
    protected List<String> parentIds(String id) {
        return Neo4jEdgeQueries.neighbors(id, "out");
    }

}
