package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.BulkCreateOrUpdateTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link BulkCreateOrUpdateTest} run against the ArcadeDB document store.
 */
public class ArcadeDBBulkCreateOrUpdateTest extends BulkCreateOrUpdateTest {

    public ArcadeDBBulkCreateOrUpdateTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return ArcadeDBFixture.fixtures();
    }

}
