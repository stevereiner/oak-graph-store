# ArcadeDB document store

Details for the README's [ArcadeDB document store](../README.md#arcadedb-document-store), which has the table of
Oak collections and ArcadeDB types.

- **Records:** each Oak document is one record holding the whole document, Oak's revision maps (`_revisions`,
  `_commitRoot`, property values keyed by revision, ...) as embedded maps, as in MongoDB. The types, a unique index on
  `_id` and an index on `_modified` are created at startup if missing. The database must exist and holds one Oak
  repository; other data can share it.
- **Updates applied by the database:** like the MongoDB store (`$set`, `$max`, `$inc`, `$unset` in one
  `findOneAndUpdate`), an Oak update is one `UPDATE` statement: `SET p = :v, m = ifnull(m, {}), m[:rev] = :v,
  x = max(ifnull(x, :v), :v), c = ifnull(c, 0) + :v, _modCount = ifnull(_modCount, 0) + 1 REMOVE q, m[:rev]
  RETURN BEFORE WHERE _id = :id AND <conditions>`, the conditions with `UpdateUtils.checkConditions`' semantics
  (`m[:rev] = :v`, `ifnull(m, {}).keys() CONTAINS :rev` for an entry that exists with a null value). With a cached
  node document, the statement is first conditional on its `_modCount` (no document returned), as in the MongoDB
  store. The database computes the new values, so a concurrent change doesn't send the update back to Java: the old
  read-apply-conditional-write loop starved at 8 threads x 150 increments of one document; this doesn't (16 x 50
  tested). `oak.arcadedb.databaseUpdates=false` restores the Java path.
- **Optimistic writes, locked as a fallback:** ArcadeDB has no record locks. Each write is one transaction in one
  server-side SQL script, optimistic first: `BEGIN ISOLATION REPEATABLE_READ; <statement>; COMMIT RETRY 10`. ArcadeDB
  detects a concurrent change of the same record at commit and runs the script again on the current data.
  - Repeatable reads are required: under ArcadeDB's default (read committed), concurrent conditional updates of a
    record were seen to both commit (`ArcadeDBLostUpdateTest` lost increments in 3 runs out of 4 at 3 threads x 150).
  - Inserts are the exception: under repeatable reads, ArcadeDB 26.10.1 skips the unique index check at commit for a
    key the transaction read before (the edge script reads the inserted ids), so a concurrent insert of the same `_id`
    committed a second record missing from the index. Inserts run read committed, where the duplicate fails.
  - A write still conflicting after the retries runs again holding the write lock of the types it changes:
    `BEGIN; LOCK TYPE ...; <statement>; COMMIT`. For nodes, that is every vertex and edge type of the database:
    ArcadeDB refuses to commit changes to a type that isn't locked once one is, and deleting a vertex changes the
    types of its edges and of the vertices they link, possibly other data's. The types are read once, and again when
    a write meets a new one. Readers are never blocked.

  | System property | Default | |
  |---|---|---|
  | `oak.arcadedb.writeLock` | `fallback` | `always`: every write holds the lock (writers to a collection serialized); `never`: optimistic only |
  | `oak.arcadedb.commitRetries` | `10` | server-side retries of an optimistic write (`COMMIT RETRY`) |
  | `oak.arcadedb.databaseUpdates` | `true` | `false`: updates read, applied in Java, written conditionally on `_modCount` |
- **Super-nodes disabled:** since 26.8.1, ArcadeDB moves the edges of a vertex with more than 4096 edges
  (`arcadedb.graph.supernodeThreshold`) to "stripe" buckets (`OakNode_sn_stripe_<n>`) that `LOCK TYPE` doesn't cover,
  and a transaction can hold only one lock statement: every later locked write adding an edge to such a vertex would
  fail. Oak has such vertices (the UUID index node has a child per referenceable node), so the store disables the
  promotion for its database at startup (`ALTER DATABASE`); a database where vertices were already promoted must be
  recreated (the store logs an error).
- **Numbers:** JSON transport turns small longs into integers; they are read back as longs (Oak uses longs).
- **Paging:** the HTTP API caps results at 20,000 rows, so queries are paged (keyset pagination on `_id`).
- **SQL:** the store uses ArcadeDB's native SQL, the only language with what it needs: transactions inside one request
  (`sqlscript`, with `COMMIT RETRY`), `LOCK TYPE`, and `FOREACH` to create the edges in the insert's request. Cypher
  works for reading the graph (above).
