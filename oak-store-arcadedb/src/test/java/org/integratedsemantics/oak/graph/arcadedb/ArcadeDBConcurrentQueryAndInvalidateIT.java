package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.ConcurrentQueryAndInvalidateIT;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link ConcurrentQueryAndInvalidateIT} run against the ArcadeDB document store.
 */
public class ArcadeDBConcurrentQueryAndInvalidateIT extends ConcurrentQueryAndInvalidateIT {

    public ArcadeDBConcurrentQueryAndInvalidateIT(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return ArcadeDBFixture.fixtures();
    }

}
