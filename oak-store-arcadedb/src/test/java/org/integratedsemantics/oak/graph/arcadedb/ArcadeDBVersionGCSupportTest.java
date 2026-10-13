package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;
import org.apache.jackrabbit.oak.plugins.document.VersionGCSupportTest;
import org.junit.runners.Parameterized;

/**
 * Oak's {@link VersionGCSupportTest} run against the ArcadeDB document store.
 */
public class ArcadeDBVersionGCSupportTest extends VersionGCSupportTest {

    public ArcadeDBVersionGCSupportTest(DocumentStoreFixture dsf) {
        super(dsf);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return ArcadeDBFixture.fixtures();
    }

}
