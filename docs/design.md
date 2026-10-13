# Common design

Details for the README's [Common design](../README.md#common-design).

The backends share everything that doesn't depend on the database (`GraphDocumentStore`); each implements a few
primitives: find, query, insert, conditional update, deletes.

## Updates and concurrency

- **Applying an update:** with Neo4j (and ArcadeDB with `oak.arcadedb.databaseUpdates=false`), as in Oak's
  `MemoryDocumentStore`: the document is read, the conditions checked and the changes applied in Java with Oak's
  `UpdateUtils`, and only the changed properties written back. ArcadeDB applies them in the database by default, like
  the MongoDB store (see [ArcadeDB document store](arcadedb-document-store.md)).
- **Optimistic concurrency:** the write is conditional on `_modCount`, the optimistic versioning of the MongoDB store,
  and retried if another writer changed the document meanwhile. Each backend makes the check and the write atomic in
  its own way (below).
- **`create`:** all the documents are inserted in one transaction, all or none. A duplicate id makes it return
  `false`.

## Cache

Node documents are cached as in the MongoDB store, with Oak's `NodeDocumentCache` (sized by the
`DocumentNodeStoreBuilder` passed to the store, or Oak's defaults):

- **Reads:** `find()` returns the cached document when allowed (`maxCacheAge`), else loads it under a per-document
  lock. Children queries fill the cache, unless the documents changed while the query ran.
- **Updates:** an update starts from the cached document; the conditional write checks its `_modCount` against the
  database, so a stale cached document only costs a retry (from the database; with ArcadeDB's database updates, a
  find-and-modify returning the document before the update). The new document, computed in Java, replaces the cached
  one.
- **Other cluster nodes:** `DocumentNodeStore` reads their changes from the journal and calls `invalidateCache(keys)`,
  which compares the cached `_modCount`/`_modified` with the database and drops outdated documents.

## Child edges

Each node document has a `CHILD_OF` edge to its parent's, so the content tree is a real graph for graph queries and
tools (Neo4j Browser and Bloom, ArcadeDB Studio). Oak itself doesn't need them: as in MongoDB, parent/child is encoded
in the ids (`2:/a/b` is a child of `1:/a`).

- **Created with the document:** inserting a node document links it to its parent, and its existing children to it
  (children can outlive their parent document, for example across revision garbage collection), in the insert's
  transaction, guarded so each edge is created once even when a batch holds a parent and its children.
- **Removed with the document:** deleting a node document deletes its edges.
- **Not linked:** previous documents (revision history split off a node document) and documents whose id is not a node
  id. Long paths, whose ids are hashed, are linked through their `_path`.

## Node names

Oak doesn't store a node's name (it's in the id: `2:/content/slingshot`). For graph queries and tools, each node
document also gets an indexed `name` property: the last segment of its path (`""` for the root). Neo4j Browser captions
nodes with it.

- **Not part of the Oak document:** added on insert (a node is never renamed: a move creates a new document), removed
  on read: Oak never sees it.
- **JCR properties named `name`:** Oak stores a JCR property under its name, so one called `name` (Slingshot's sample
  content has some) would clash: the stores keep it as `|name` in the database (`|` is in no JCR or Oak name) and give
  it back to Oak as `name`.
- **Existing data:** a repository written before node names existed needs a one-time migration (Sling stopped):
  `docker/migrate-name.cypher` (Neo4j) or `docker/migrate-name.sql` (ArcadeDB).

## Values and ids

- **Strings** that UTF-8 can't carry (lone surrogates), and strings starting with a NUL character (reserved for the
  backends' encodings), are stored escaped and read back unchanged.
- **Ids** are stored as is, as they are compared in range queries. They must be valid UTF-8 and at most 1024 bytes
  long; Oak hashes long paths, so real ids are much shorter.
