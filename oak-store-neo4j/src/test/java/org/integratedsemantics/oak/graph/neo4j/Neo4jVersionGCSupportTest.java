package org.integratedsemantics.oak.graph.neo4j;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.VersionGCSupportTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link VersionGCSupportTest} run against the Neo4j document store.
 */
public class Neo4jVersionGCSupportTest extends VersionGCSupportTest {

    public Neo4jVersionGCSupportTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return Neo4jFixture.fixtures();
    }

}
