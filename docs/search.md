# Search: Lucene, and elasticsearch index definitions

Details for the README's [Search: Elasticsearch instead of
Lucene](../README.md#search-elasticsearch-instead-of-lucene).

To use Lucene (no external search server; its index files then go to the graph database and the datastore):

- **Lucene as well:** in `docker/oak_graph.slingosgifeature`, remove
  `org.apache.sling.jcr.oak.server.internal.index.LuceneIndexRepositoryInitializer` from the prototype's removed
  configurations, then rebuild and recreate Sling: `docker compose -f docker/compose.arcadedb.yaml up -d --build oak`.
  At startup Sling creates `/oak:index/lucene` when it's missing (also in an existing repository), and Oak builds it in
  the background (lane `async`). Both indexes are then kept up to date, and Oak picks the cheaper one for each query.
- **Lucene only:** also remove the configurations `...RepositoryInitializer~oak-graph-elastic` (the repoinit script),
  `...ElasticIndexProviderService` and `...AsyncIndexerService` (and the `oak-search-elastic` bundle) from the feature,
  delete the existing definition (`curl -u admin:admin -F :operation=delete http://localhost:8180/oak:index/fulltext`),
  and remove the `elasticsearch` and `kibana` services (and Sling's dependency on Elasticsearch) from the Compose files.

To add an elasticsearch index, create a definition under `/oak:index` with `type` = `elasticsearch`, `async` =
`elastic-async` and `reindex` = `true` (Oak doesn't build an elasticsearch index by itself). Two Oak details, met here:
the regular expression for all the properties of a node is `^[^\/]*$` (Oak's own `REGEX_ALL_PROPS`, which it
special-cases; `.*` matches none), and a definition node must be an `oak:QueryIndexDefinition`, whose `type` property
is mandatory (the feature's repoinit script creates the definition as `nt:unstructured`, sets its properties, then
changes its type, as repoinit saves after each statement). To rebuild an index, set `reindex` to `true`:
`curl -u admin:admin -F reindex=true -F reindex@TypeHint=Boolean http://localhost:8180/oak:index/fulltext`.
