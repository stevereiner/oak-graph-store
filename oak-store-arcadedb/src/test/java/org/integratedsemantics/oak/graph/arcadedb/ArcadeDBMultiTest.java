package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.MultiDocumentStoreTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link MultiDocumentStoreTest} run against the ArcadeDB document store.
 */
public class ArcadeDBMultiTest extends MultiDocumentStoreTest {

    public ArcadeDBMultiTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return ArcadeDBFixture.fixtures();
    }

}
