package org.integratedsemantics.oak.graph.neo4j;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.ConcurrentQueryAndUpdate2IT;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link ConcurrentQueryAndUpdate2IT} run against the Neo4j document store.
 */
public class Neo4jConcurrentQueryAndUpdate2IT extends ConcurrentQueryAndUpdate2IT {

    public Neo4jConcurrentQueryAndUpdate2IT(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return Neo4jFixture.fixtures();
    }

}
