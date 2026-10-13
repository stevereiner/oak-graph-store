package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Collection;
import java.util.List;

import org.apache.jackrabbit.oak.plugins.document.DocumentMK;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentStoreFixture;

/**
 * Oak test fixture for the ArcadeDB document store: all the stores it creates share the test database, which is
 * emptied after each test.
 */
public class ArcadeDBFixture extends DocumentStoreFixture {

    public static final ArcadeDBFixture ARCADEDB = new ArcadeDBFixture();

    /** The JUnit parameters of Oak's parameterized document store tests. */
    public static Collection<Object[]> fixtures() {
        return List.<Object[]> of(new Object[] { ARCADEDB });
    }

    @Override
    public String getName() {
        return "ArcadeDB";
    }

    @Override
    public DocumentStore createDocumentStore(DocumentMK.Builder builder) {
        return new ArcadeDBDocumentStore(ArcadeDBTestServer.config());
    }

    @Override
    public boolean hasSinglePersistence() {
        return true;
    }

    @Override
    public void dispose() {
        ArcadeDBTestServer.clear();
    }

}
