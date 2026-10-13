package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.ConcurrentDocumentStoreTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link ConcurrentDocumentStoreTest} run against the ArcadeDB document store.
 */
public class ArcadeDBConcurrentTest extends ConcurrentDocumentStoreTest {

    public ArcadeDBConcurrentTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return ArcadeDBFixture.fixtures();
    }

}
