package org.integratedsemantics.oak.graph.neo4j;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.ConcurrentDocumentStoreTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link ConcurrentDocumentStoreTest} run against the Neo4j document store.
 */
public class Neo4jConcurrentTest extends ConcurrentDocumentStoreTest {

    public Neo4jConcurrentTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return Neo4jFixture.fixtures();
    }

}
