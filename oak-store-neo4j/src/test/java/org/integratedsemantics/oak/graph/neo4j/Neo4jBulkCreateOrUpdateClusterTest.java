package org.integratedsemantics.oak.graph.neo4j;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.BulkCreateOrUpdateClusterTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link BulkCreateOrUpdateClusterTest} run against the Neo4j document store.
 */
public class Neo4jBulkCreateOrUpdateClusterTest extends BulkCreateOrUpdateClusterTest {

    public Neo4jBulkCreateOrUpdateClusterTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return Neo4jFixture.fixtures();
    }

}
