package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.ConcurrentQueryAndUpdate2IT;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link ConcurrentQueryAndUpdate2IT} run against the ArcadeDB document store.
 */
public class ArcadeDBConcurrentQueryAndUpdate2IT extends ConcurrentQueryAndUpdate2IT {

    public ArcadeDBConcurrentQueryAndUpdate2IT(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return ArcadeDBFixture.fixtures();
    }

}
