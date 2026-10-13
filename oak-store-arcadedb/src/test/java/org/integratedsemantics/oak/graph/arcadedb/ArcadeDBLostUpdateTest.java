package org.integratedsemantics.oak.graph.arcadedb;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Concurrent conditional updates of one document must all survive: each increment is a read and a write conditional on
 * {@code _modCount}, and a write that two transactions both commit loses an increment. Each thread has its own store,
 * like cluster nodes sharing a database: a store's own document locks don't serialize them.
 */
public class ArcadeDBLostUpdateTest {

    protected static final int THREADS = Integer.getInteger("oak.test.lostUpdate.threads", 4);

    protected static final int INCREMENTS = Integer.getInteger("oak.test.lostUpdate.increments", 50);

    protected static final String ID = "1:/counter";

    protected ArcadeDBDocumentStore store;

    @Before
    public void setUp() {
        ArcadeDBTestServer.clear();
        store = new ArcadeDBDocumentStore(ArcadeDBTestServer.config());
    }

    @After
    public void tearDown() {
        store.dispose();
    }

    @Test
    public void testConcurrentIncrements() throws Exception {
        var create = new UpdateOp(ID, true);
        create.set("c", 0L);
        store.create(Collection.NODES, List.of(create));

        // the stores are created one after the other (each checks the schema at startup)
        var stores = new ArrayList<ArcadeDBDocumentStore>();
        for (int t = 0; t < THREADS; t++) {
            stores.add(new ArcadeDBDocumentStore(ArcadeDBTestServer.config()));
        }
        var executor = Executors.newFixedThreadPool(THREADS);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (var own : stores) {
                tasks.add(() -> {
                    for (int i = 0; i < INCREMENTS; i++) {
                        var op = new UpdateOp(ID, false);
                        op.increment("c", 1);
                        own.findAndUpdate(Collection.NODES, op);
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);
            stores.forEach(ArcadeDBDocumentStore::dispose);
        }
        store.invalidateCache();
        var doc = store.find(Collection.NODES, ID);
        assertEquals((long) THREADS * INCREMENTS, ((Number) doc.get("c")).longValue());
    }

}
