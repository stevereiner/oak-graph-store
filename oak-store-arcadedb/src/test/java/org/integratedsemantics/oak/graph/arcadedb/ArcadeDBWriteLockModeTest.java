package org.integratedsemantics.oak.graph.arcadedb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.function.Supplier;

import org.junit.After;
import org.junit.Test;

import com.arcadedb.exception.ConcurrentModificationException;

/**
 * The write lock modes: which of the optimistic and locked writes run when the optimistic one conflicts.
 */
public class ArcadeDBWriteLockModeTest {

    protected ArcadeDBDocumentStore store;

    @After
    public void tearDown() {
        System.clearProperty(ArcadeDBDocumentStore.WRITE_LOCK_PROPERTY);
        if (store != null) {
            store.dispose();
        }
    }

    protected ArcadeDBDocumentStore store(String mode) {
        System.setProperty(ArcadeDBDocumentStore.WRITE_LOCK_PROPERTY, mode);
        store = new ArcadeDBDocumentStore(ArcadeDBTestServer.config());
        return store;
    }

    protected static Supplier<String> conflicting() {
        return () -> {
            throw new ConcurrentModificationException("conflict");
        };
    }

    @Test
    public void testParse() {
        assertEquals(WriteLockMode.FALLBACK, WriteLockMode.of(null));
        assertEquals(WriteLockMode.FALLBACK, WriteLockMode.of("Fallback"));
        assertEquals(WriteLockMode.ALWAYS, WriteLockMode.of("true"));
        assertEquals(WriteLockMode.ALWAYS, WriteLockMode.of("always"));
        assertEquals(WriteLockMode.NEVER, WriteLockMode.of("false"));
        assertEquals(WriteLockMode.NEVER, WriteLockMode.of("never"));
        assertThrows(IllegalArgumentException.class, () -> WriteLockMode.of("sometimes"));
    }

    @Test
    public void testFallback() {
        var s = store("fallback");
        assertEquals("optimistic", s.optimisticThenLocked(() -> "optimistic", () -> "locked"));
        var before = ArcadeDBDocumentStore.LOCK_FALLBACKS.get();
        assertEquals("locked", s.optimisticThenLocked(conflicting(), () -> "locked"));
        assertEquals(before + 1, ArcadeDBDocumentStore.LOCK_FALLBACKS.get());
    }

    @Test
    public void testAlways() {
        var s = store("always");
        assertEquals("locked", s.optimisticThenLocked(() -> "optimistic", () -> "locked"));
    }

    @Test
    public void testNever() {
        var s = store("never");
        assertEquals("optimistic", s.optimisticThenLocked(() -> "optimistic", () -> "locked"));
        assertThrows(ConcurrentModificationException.class, () -> s.optimisticThenLocked(conflicting(), () -> "locked"));
    }

}
