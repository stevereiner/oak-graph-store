package org.integratedsemantics.oak.graph.neo4j;

import java.util.Collection;
import java.util.List;

import org.apache.jackrabbit.oak.plugins.document.DocumentMK;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;

/**
 * Oak test fixture for the Neo4j document store: all the stores it creates share the test database, whose test nodes
 * are deleted after each test.
 */
public class Neo4jFixture extends DocumentStoreFixture {

    public static final Neo4jFixture NEO4J = new Neo4jFixture();

    /** The JUnit parameters of Oak's parameterized document store tests. */
    public static Collection<Object[]> fixtures() {
        return List.<Object[]> of(new Object[] { NEO4J });
    }

    @Override
    public String getName() {
        return "Neo4j";
    }

    @Override
    public DocumentStore createDocumentStore(DocumentMK.Builder builder) {
        return new Neo4jDocumentStore(Neo4jTestServer.config());
    }

    @Override
    public boolean hasSinglePersistence() {
        return true;
    }

    @Override
    public void dispose() {
        Neo4jTestServer.clear();
    }

}
