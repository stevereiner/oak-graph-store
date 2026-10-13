# Neo4j document store

Details for the README's [Neo4j document store](../README.md#neo4j-document-store), which has the table of Oak
collections and Neo4j labels.

- **Label prefix:** configurable (`Neo4jConfig.labelPrefix`, `Oak` by default), so several Oak repositories, or other
  data, can share a database (Neo4j Community has a single user database). A unique constraint on `_id` and an index on
  `_modified` are created per label at startup if missing.
- **Revision maps:** scalar values are node properties. Oak's revision maps (revision to value, where `null` records a
  removal) can't be Neo4j properties, which have neither maps nor nulls: each is one JSON string property, with a
  reserved prefix. This also keeps a node's property count small: documents can have thousands of revisions.
- **Conditional update:** Neo4j doesn't re-check a condition after waiting for a lock, so checking `_modCount` first
  would let two concurrent updates pass the same check. The update first takes the node's write lock (by setting a
  temporary property), then checks, then writes, in one statement: `SET d += $changes` (a `null` removes a property).
- **Connections:** one driver per store (or one shared by the caller); sessions use the driver's bookmark manager for
  causal consistency across sessions, so Neo4j clusters and AuraDB (`neo4j+s://`) work.
