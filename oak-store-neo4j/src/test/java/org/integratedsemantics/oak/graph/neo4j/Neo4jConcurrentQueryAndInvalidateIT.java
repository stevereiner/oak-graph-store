package org.integratedsemantics.oak.graph.neo4j;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.ConcurrentQueryAndInvalidateIT;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link ConcurrentQueryAndInvalidateIT} run against the Neo4j document store.
 */
public class Neo4jConcurrentQueryAndInvalidateIT extends ConcurrentQueryAndInvalidateIT {

    public Neo4jConcurrentQueryAndInvalidateIT(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return Neo4jFixture.fixtures();
    }

}
