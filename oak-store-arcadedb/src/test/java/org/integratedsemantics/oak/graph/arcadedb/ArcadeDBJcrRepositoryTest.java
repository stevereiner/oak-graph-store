package org.integratedsemantics.oak.graph.arcadedb;

import java.util.List;

import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.integratedsemantics.oak.graph.AbstractJcrRepositoryTest;

/**
 * A JCR repository (Oak, DocumentNodeStore) on the ArcadeDB document store.
 */
public class ArcadeDBJcrRepositoryTest extends AbstractJcrRepositoryTest {

    @Override
    protected DocumentStore newDocumentStore() {
        return new ArcadeDBDocumentStore(ArcadeDBTestServer.config());
    }

    @Override
    protected void clearDatabase() {
        ArcadeDBTestServer.clear();
    }

    @Override
    protected long countNodesWithoutOneParent() {
        return ArcadeDBEdgeQueries.countNodesWithoutOneParent();
    }

    @Override
    protected List<String> parentIds(String id) {
        return ArcadeDBEdgeQueries.neighbors(id, "out");
    }

}
