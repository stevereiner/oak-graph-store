package org.integratedsemantics.oak.graph.arcadedb;

import java.util.Locale;

/**
 * How the writes deal with concurrent writers ({@code oak.arcadedb.writeLock}). ArcadeDB has no record locks: a write
 * either runs optimistically (ArcadeDB detects a concurrent change of the same record at commit, and the server runs
 * the transaction again, {@code COMMIT RETRY}), or holds a type lock ({@code LOCK TYPE}) that serializes the writers.
 */
public enum WriteLockMode {

    /** Optimistic, and a write still conflicting after the retries runs again holding the type lock (the default). */
    FALLBACK,

    /** Every write holds the type lock. */
    ALWAYS,

    /** Optimistic only: a write still conflicting after the retries fails. */
    NEVER;

    /** Parses {@code fallback}, {@code always} (or {@code true}), {@code never} (or {@code false}); null: the default. */
    public static WriteLockMode of(String value) {
        if (value == null || value.isBlank()) {
            return FALLBACK;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "fallback" -> FALLBACK;
            case "always", "true" -> ALWAYS;
            case "never", "false" -> NEVER;
            default -> throw new IllegalArgumentException(
                    "Invalid write lock mode: " + value + " (fallback, always or never)");
        };
    }

}
