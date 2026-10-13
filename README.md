# oak-graph-store

Prototype [Apache Jackrabbit Oak](https://jackrabbit.apache.org/oak/docs/) `DocumentStore` implementations that keep
Oak's node documents (the nodes and their properties, what the MongoDB and RDB document stores hold) in graph
databases: [ArcadeDB](https://arcadedb.com/) and [Neo4j](https://neo4j.com/) (or AuraDB). Binaries (blobs) stay in
Oak's blob store as usual (in the Docker Composes, its file data store). Sibling project of
[nuxeo-graph-storage](https://github.com/stevereiner/nuxeo-graph-storage), which does the same for Nuxeo.

Apache Jackrabbit Oak is a general-purpose content repository for Java content applications, with the JCR API: a
hierarchy of typed nodes, versioning, access control, queries and indexes, observation. It has a long history in web
content management: Day Software, maker of the Communiqué/CQ WCM product, led the JCR standard (JSR-170) and started
Jackrabbit as its reference implementation; Adobe acquired Day in 2010, CQ became Adobe Experience Manager (AEM), and
since AEM 6 AEM is built on Oak, under Apache Sling. This project moves Oak's nodes into a graph database instead of
MongoDB or a SQL database, with Oak and the JCR API unchanged above it.

Status: **prototype**. Both stores pass the Oak test classes that apply to a document store, the ones Oak runs
against its MongoDB and RDB stores (a selection of Oak's tests, not its whole suite; see [Tests](#tests)), and run
Sling with its whole repository in the graph database, with the Composum Tools repository UI (see
[Docker Composes](#docker-composes)).

## Why

The reasons for a graph-backed content repository are those of nuxeo-graph-storage (its
[Why](https://github.com/stevereiner/nuxeo-graph-storage#why) and
[Uses](https://github.com/stevereiner/nuxeo-graph-storage#uses)): connected data traversed directly, business entities
and not only files, knowledge graphs and ontologies managed as content, AI and agents, GraphRAG. With Oak, they also
apply to the web content management built on top of it:

- **WCM, headless content, digital experience:** WCM products and applications are built on Oak (AEM, Sling
  applications). Web content is connected: pages are built from components, which show assets and content fragments,
  reused across pages, sites and languages, with templates, translations and live copies. With these references as edges (a
  [possible feature](#possible-feature-jcr-references)), "which pages, in which sites and languages, show this asset?"
  or "what must be published with this page?" become graph traversals. For applications on Oak and Sling, headless
  delivery, and other JCR applications. Tested with the Sling Starter, not with AEM, which brings its own Oak version
  and requirements.
- **The content tree as a graph:** every node is a graph node with a `CHILD_OF` edge to its parent, queryable with
  Cypher (Neo4j), or Cypher or SQL (ArcadeDB), and visible in Neo4j Browser or ArcadeDB Studio.
- **Content and knowledge together:** knowledge graphs built from the content can link their entities to the content
  nodes they come from.
- **One database:** Oak keeps everything in its repository (users and groups, permissions, property indexes, Sling's
  scripts): one graph database, plus a data store for the binaries (content files) and Elasticsearch for fulltext.

## Uses

The uses of nuxeo-graph-storage ([Uses](https://github.com/stevereiner/nuxeo-graph-storage#uses)) apply to Oak too.
With the repository in a graph database:

- **Web content impact:** before changing or unpublishing an asset, fragment or component, find the pages, sites and
  languages that use it, through any number of references (with references as edges); then edit and publish through
  Sling, as the user.
- **GraphRAG pipelines:** the knowledge graph a pipeline builds from the content can link its extracted entities to
  the content nodes, and share the database: with Neo4j, the repository's labels have their own prefix; with ArcadeDB,
  a database holds one repository, with other types next to it.
- **Exploration:** a whole repository is large (a fresh Sling has about 25,000 nodes): a Cypher query picks the part
  to see (a subtree, a node's neighborhood), in Neo4j Browser, ArcadeDB Studio or a purpose-built graph UI.
- **Permissions:** Oak enforces JCR access control (`rep:policy` nodes) for every session; a direct graph query
  (privileged database access) doesn't. Read what a user may see through Oak (or Sling), as that user.
- **Agents:** find candidates and paths with a graph query, then read and write through Oak's API or Sling's HTTP
  API.

## Developer: How to use

Applications read and write the repository through Oak, unchanged from its MongoDB or RDB stores:

- **Oak's API** ([Javadoc](https://jackrabbit.apache.org/oak/docs/apidocs/)): Oak implements JCR (`javax.jcr`) with
  the Jackrabbit API extensions (`org.apache.jackrabbit.api`: user management, access control, ...), so applications
  use sessions, nodes and properties, versioning, and JCR-SQL2 and XPath queries; below JCR, its own API
  (`org.apache.jackrabbit.oak.api`: `ContentRepository`, `ContentSession`, `Root`, `Tree`) works with Oak's trees
  directly. In Java code in Sling (a session from the `SlingRepository` service, or Sling's Resource API above it), or
  in an application embedding Oak (`new Jcr(new Oak(nodeStore)).createRepository()`, as this project's JCR tests do).
- **Sling's HTTP API**, above Oak: the Sling POST servlet creates, changes, moves, deletes and versions nodes (see
  [docs/composum-tools.md](docs/composum-tools.md)); GET with a `.json` extension reads them
  (`http://localhost:8180/content.1.json`: `/content` and its children); Composum Tools' query tool runs JCR-SQL2 and
  XPath queries.

Both go through Oak's permission checks and commit hooks (versioning, index updates).

The graph can also be read directly, with Cypher (Neo4j, ArcadeDB) or SQL (ArcadeDB): see [Queries](#queries).
That access is privileged: it isn't checked against JCR access control.

Write through Oak only (its API, or Sling above it): the node documents hold Oak's revision maps, and a direct
write would corrupt the repository. Other nodes, and edges between them and the content nodes, are fine: Oak's deletes
(at revision garbage collection) remove the attached edges. With ArcadeDB, a node write that takes the type lock (after
conflicts, or every write with `oak.arcadedb.writeLock=always`) then also locks the other data's types, for that one
statement (see [ArcadeDB document store](#arcadedb-document-store)).

## Modules

| Module | Package | Description |
|---|---|---|
| `oak-store-graph` | `org.integratedsemantics.oak.graph` | Database-independent base (`GraphDocumentStore`): cache, optimistic updates, child edges; shared abstract tests (test-jar) |
| `oak-store-arcadedb` | `org.integratedsemantics.oak.graph.arcadedb` | `ArcadeDBDocumentStore`, using the ArcadeDB Java remote (HTTP) API (Apache 2.0) |
| `oak-store-neo4j` | `org.integratedsemantics.oak.graph.neo4j` | `Neo4jDocumentStore`, using the Neo4j Java driver (Apache 2.0) |
| `oak-graph-fragment` | `org.integratedsemantics.oak.graph.osgi` | OSGi fragment of Oak's `oak-store-document` bundle: the stores, with the ArcadeDB and Neo4j clients embedded |
| `oak-graph-sling` | `org.integratedsemantics.oak.graph.sling` | OSGi component starting the `DocumentNodeStore` on a graph database in Sling |

Group id `org.integratedsemantics.oak.graph`. Built against Oak 2.6.0 (`oak.version`), the ArcadeDB 26.10.1 client
(`arcadedb.version`) and the Neo4j Java driver 6.3.0 (`neo4j-driver.version`), Java 21. The stores also build and run
on Oak 1.90.0 (`-Doak.version=1.90.0`), the Oak of Sling Starter 14.

## Terminology

| Oak | MongoDB store | ArcadeDB store | Neo4j store | JCR |
|---|---|---|---|---|
| node document | document in `nodes` | `OakNode` vertex | `OakNode` node | node (with its properties) |
| `DocumentStore` | `MongoDocumentStore` | `ArcadeDBDocumentStore` | `Neo4jDocumentStore` | (below the JCR API) |
| blob store | GridFS / blobs collection | unchanged (Oak blob/data store) | unchanged | binary property values |

## Common design

The backends share everything that doesn't depend on the database (`GraphDocumentStore`); each implements a few
primitives: find, query, insert, conditional update, deletes. Updates are optimistic, conditional on `_modCount` as in
the MongoDB store; node documents are cached with Oak's `NodeDocumentCache`; each node document has a `CHILD_OF` edge
to its parent's and an indexed `name` property (the last segment of its path, which Oak never sees). Details: updates
and concurrency, cache, child edges, node names, values and ids: [docs/design.md](docs/design.md).

## Queries

- **Oak's queries, JCR-SQL2 and XPath,** run unchanged on the graph stores, with the user's permissions applied: Oak
  answers them with its indexes (property and node type indexes in the graph database, fulltext in Elasticsearch), and
  they run in Composum Tools' query tool, through Oak's API, or over HTTP with DavEx.
- **The graph, read directly:** the names are the same in both databases (node documents `OakNode`, with their Oak id
  `_id`, such as `1:/content`, and their `name`, linked by `CHILD_OF` edges), so the same Cypher queries work in Neo4j
  Browser and in ArcadeDB Studio, and ArcadeDB also answers them in SQL. Property values are Oak's revision maps, with
  typed values.

Examples of both (JCR-SQL2 and XPath; Cypher and SQL), and where to run them: [docs/queries.md](docs/queries.md).

## ArcadeDB document store

| Oak collection | ArcadeDB type |
|---|---|
| `nodes` | `OakNode` (vertex type) |
| `clusterNodes` | `OakClusterNode` (document type) |
| `settings` | `OakSetting` |
| `journal` | `OakJournal` |
| `blobs` | `OakBlob` |
| (parent/child) | `CHILD_OF` edges between `OakNode` vertices |

Each Oak document is one record, with Oak's revision maps as embedded maps, as in MongoDB; the database holds one Oak
repository, and other data can share it. An Oak update is one SQL `UPDATE` applied by the database, like the MongoDB
store's `findOneAndUpdate`. Writes are optimistic (repeatable reads, `COMMIT RETRY`), and a write still conflicting
runs again holding a type lock (`LOCK TYPE`); readers are never blocked. Details: the update statement, isolation and
locking, the system properties (`oak.arcadedb.writeLock`, `oak.arcadedb.commitRetries`,
`oak.arcadedb.databaseUpdates`), super-nodes, numbers, paging, SQL:
[docs/arcadedb-document-store.md](docs/arcadedb-document-store.md).

## Neo4j document store

| Oak collection | Neo4j label (prefix `Oak`) |
|---|---|
| `nodes` | `OakNode` |
| `clusterNodes` | `OakClusterNode` |
| `settings` | `OakSetting` |
| `journal` | `OakJournal` |
| `blobs` | `OakBlob` |
| (parent/child) | `CHILD_OF` relationships between `OakNode` nodes |

The labels have a configurable prefix (`Oak` by default), so other data can share the database; Oak's revision maps
are JSON string properties; a conditional update takes the node's write lock before checking `_modCount`; sessions
use the driver's bookmark manager, so clusters and AuraDB (`neo4j+s://`) work. Details:
[docs/neo4j-document-store.md](docs/neo4j-document-store.md).

## Docker Composes

Oak itself has no UI. [Apache Sling](https://sling.apache.org/) runs Oak with a UI: the Sling Starter and its OSGi Web
Console. `docker/` has two Docker Compose files, one per graph database. Each runs Sling Starter 14 with its Oak
repository in the graph database, Elasticsearch (with Kibana) for Oak's elasticsearch indexes, and
[Composum Tools](https://github.com/ist-dresden/composum-tools) (MIT) as the repository UI: a JCR browser with
properties, JSON and XML views, JCR-SQL2/XPath queries (JSON and CSV export) and staged editing (create, delete, move,
change properties, then commit or revert all), a package manager, a user manager and a dashboard. Composum Tools
replaces the Sling Starter's Composum Nodes, deprecated in favor of it.

Each file is a separate Compose project with its own containers, network and volumes, so both can run at once. The
services they share (Sling with Oak, Elasticsearch, Kibana) are defined once in `docker/oak-base.yaml`, which both
files extend, and both build the same Sling image, `oak-graph-sling:local`. The versions, passwords and host ports are
in `docker/.env`.

| | ArcadeDB stack | Neo4j stack | Login |
|---|---|---|---|
| Sling start page | http://localhost:8180 | http://localhost:8181 | `admin` / `admin` |
| Composum Tools: dashboard | http://localhost:8180/apps/cpm/tools.dashboard.html | http://localhost:8181/apps/cpm/tools.dashboard.html | `admin` / `admin` |
| Composum Tools: JCR browser | http://localhost:8180/apps/cpm/tools.browser.html | http://localhost:8181/apps/cpm/tools.browser.html | `admin` / `admin` |
| Composum Tools: packages / users | `/apps/cpm/tools.packages.html` / `/apps/cpm/tools.users.html` | the same | `admin` / `admin` |
| OSGi Web Console | http://localhost:8180/system/console | http://localhost:8181/system/console | `admin` / `admin` |
| Graph database UI | ArcadeDB Studio, http://localhost:2490 | Neo4j Browser, http://localhost:7475 (Bolt 7690) | `root` / `playwithdata`; `neo4j` / `password` |
| Elasticsearch / Kibana | http://localhost:9206 / http://localhost:5607 | http://localhost:9207 / http://localhost:5608 | none (security disabled) |
| Kibana's index list | http://localhost:5607/app/management/data/index_management/indices | http://localhost:5608/app/management/data/index_management/indices | none |

`admin` is Sling's default administrator; the graph databases' passwords are in `docker/.env`. Elasticsearch and
Kibana have no login: development only. [docs/port-mappings.md](docs/port-mappings.md) lists the host ports and the
ports they avoid (other local stacks, Windows' reserved range 9248-9347).

- **Log in to Sling first**, through its login form (the start page's Login link, or
  http://localhost:8180/system/sling/form/login?resource=%2Fapps%2Fcpm%2Ftools.browser.html): Composum Tools' pages
  open without a login too, but then show only what the anonymous user may read (public content). The start page's
  link to Composum Nodes' browser (`/bin/browser.html`) redirects to Composum Tools' browser. Each Sling has its own
  login cookie (`SLING_AUTH_COOKIE`), as browsers share cookies between the ports of `localhost`.
- **Everything is in the graph database:** content created in Sling (for example `curl -u admin:admin -F title=Hello
  http://localhost:8180/content/demo/hello`) is an `OakNode` with its `CHILD_OF` edge (see [Queries](#queries)), and so
  is the rest of the repository: Sling's own content, users and groups (`/home`), access control lists (`rep:policy`
  nodes), versions and index definitions; a fresh Sling writes about 25,000 node documents. Binaries go to Oak's file
  data store, in the `oak-datastore` volume: unlike Nuxeo, Oak needs no SQL database.

### ArcadeDB: `docker/compose.arcadedb.yaml`

Compose project `oak-graph-arcadedb`: ArcadeDB (`ARCADEDB_VERSION` in `docker/.env`: 26.10.1), with ArcadeDB Studio and
the HTTP API on port 2490, started with the database `oak`; Elasticsearch on port 9206 and Kibana on 5607; Sling on
8180, with `OAK_GRAPH_BACKEND=arcadedb`, reaching ArcadeDB over HTTP (`ARCADEDB_HOST`, `ARCADEDB_DATABASE`,
`ARCADEDB_USERNAME`, `ARCADEDB_PASSWORD`).

```
docker compose -f docker/compose.arcadedb.yaml up --build -d    # build the Sling image (the first time: longer) and start
docker compose -f docker/compose.arcadedb.yaml logs -f oak      # follow Sling's startup
docker compose -f docker/compose.arcadedb.yaml stop             # stop the containers
docker compose -f docker/compose.arcadedb.yaml up -d            # start again
docker compose -f docker/compose.arcadedb.yaml down             # remove the containers and network, keep the volumes
docker compose -f docker/compose.arcadedb.yaml down -v          # also delete the volumes: all of the stack's content
```

### Neo4j: `docker/compose.neo4j.yaml`

Compose project `oak-graph-neo4j`: Neo4j Community (`NEO4J_VERSION` in `docker/.env`: 2026.09.0), with Neo4j Browser on
port 7475 and Bolt on 7690; Elasticsearch on port 9207 and Kibana on 5608; Sling on 8181, with
`OAK_GRAPH_BACKEND=neo4j`, reaching Neo4j over Bolt (`NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`).

```
docker compose -f docker/compose.neo4j.yaml up --build -d    # build the Sling image (if not built yet) and start
docker compose -f docker/compose.neo4j.yaml logs -f oak      # follow Sling's startup
docker compose -f docker/compose.neo4j.yaml stop             # stop the containers
docker compose -f docker/compose.neo4j.yaml up -d            # start again
docker compose -f docker/compose.neo4j.yaml down             # remove the containers and network, keep the volumes
docker compose -f docker/compose.neo4j.yaml down -v          # also delete the volumes: all of the stack's content
```

A stack's volumes belong together: a node's properties are in the graph database, its binaries in the datastore
volume, and the elasticsearch indexes in Elasticsearch. Delete all of them (`down -v`) or none. The services, their
volumes and what each holds: [docs/docker.md](docs/docker.md).

### The Sling image: `docker/`

- **`Dockerfile`:** builds the bundles (`oak-graph-fragment`, `oak-graph-sling`) with Maven for the image's Oak version
  (`OAK_VERSION` in `docker/.env`: 1.90.0, the Oak of `SLING_IMAGE`, `apache/sling:14`), builds Composum Tools from its
  release tag (`COMPOSUM_TOOLS_VERSION`: 1.4.1, not on Maven Central), and adds them, Oak's Elasticsearch bundle and
  the text extraction libraries to the Sling image's artifacts repository. From the repository root, without Compose:
  `docker build -f docker/Dockerfile -t oak-graph-sling:local .`
- **`oak_graph.slingosgifeature`:** the Sling feature the image launches: Sling Starter's `oak_mongo` feature without
  MongoDB, with the graph store, Composum Tools and Elasticsearch (see
  [How it plugs into Sling](#how-it-plugs-into-sling)).
- **`OakSearchElasticPatch.java`, `tika-encoding-detectors/`, `poi-dom-imports/`:** the patch of Oak's Elasticsearch
  bundle and two OSGi fragments for text extraction, used by the image build
  ([docs/sling-integration.md](docs/sling-integration.md)).
- **`migrate-name.cypher`, `migrate-name.sql`:** one-time migrations of a repository written before node names existed
  ([docs/design.md](docs/design.md)).

### Using Composum Tools UI and Sling

In Composum Tools' JCR browser (http://localhost:8180/apps/cpm/tools.browser.html, logged in):

- **Folders and files:** select the parent node in the tree, then the tree's ☰ menu (not the properties view's **+**,
  which adds a property) has **New Node...**: `sling:OrderedFolder` or `sling:Folder` for a folder, `nt:file` for a
  file (with an upload input), `nt:unstructured` for other content. Changes are staged until **Commit All**. Files can
  also be uploaded with Sling's POST servlet:
  `curl -u admin:admin -F "*=@report.pdf" http://localhost:8180/content/docs`.
- **Preview:** the **Preview** tab, next to **Properties**, **JSON** and **XML**, shows the selected file in the
  browser: images, video, text and code, and documents such as PDF; other files as a download link.
- **Search:** the **Query** tool (its magnifier icon) searches below the selected node. Words make a fulltext search
  (answered by the Elasticsearch index, the text of files included); `[nt:file]/content report` limits it to a node
  type and a path; JCR-SQL2 and XPath queries work too (templates and history in its menus). Results export as JSON
  or CSV. Examples: [docs/queries.md](docs/queries.md).
- **Versioning:** Composum Tools has no versioning actions: use Sling's POST servlet (`:operation=checkin`,
  `checkout`, `restore`).

Details: node types, file metadata with `mix:title`, staged changes, preview and search, versioning commands, cancel
checkout, where versions are stored: [docs/composum-tools.md](docs/composum-tools.md).

### How it plugs into Sling

The stores are a fragment of Oak's `oak-store-document` bundle (`oak-graph-fragment`, built for the image's Oak
1.90.0), started by the `oak-graph-sling` component in place of Oak's `DocumentNodeStoreService`, in a Sling feature
(`docker/oak_graph.slingosgifeature`). The image also adds Oak's Elasticsearch bundle (with a patched manifest) and
the libraries Tika needs to extract the text of Office, HTML and text files. Details: why a fragment, the services and
MBeans registered, the feature, the Elasticsearch bundle patch, text extraction:
[docs/sling-integration.md](docs/sling-integration.md).

### Search: Elasticsearch instead of Lucene

Oak answers queries (JCR-SQL2, XPath) with the index definitions under `/oak:index`, choosing the cheapest index for
each query. In these stacks:

- **Property and node type indexes** are repository nodes, in the graph database, updated in the content's commit.
- **Fulltext: Elasticsearch, no Lucene.** Sling Starter normally creates a Lucene index, `/oak:index/lucene`, whose
  index files Oak keeps in the repository (here the graph database and the datastore), mixed with the content. The
  feature removes the configuration of Sling's `LuceneIndexRepositoryInitializer`, so that index isn't created, and
  creates `/oak:index/fulltext` instead: an elasticsearch index (`oak-search-elastic`, lane `elastic-async`) with the
  rules of Sling's Lucene index (the whole repository, String and Binary properties, except `jcr:createdBy`,
  `jcr:lastModifiedBy`, `sling:alias`, `sling:resourceType` and `sling:vanityPath`). The index is in the stack's
  Elasticsearch, alias `oak.fulltext` (Kibana shows it); the graph database holds only the definition. Example, in
  Composum Tools' query tool: `SELECT * FROM [nt:base] AS s WHERE CONTAINS(s.*, 'hello')`. The text of files (Word,
  PDF, HTML, text) is indexed too (see [text extraction](docs/sling-integration.md)). `oak-lucene` itself stays:
  Sling's repository bundle needs it.

Fulltext queries therefore need Elasticsearch: when it is down, they fail, or run without an index (slowly, traversing
the repository). The index is updated asynchronously, every 5 seconds, as Lucene's was. Elasticsearch 8.19 is the
version of Oak 1.90's Elasticsearch client (Oak 2.x has the 9.x client). It must be Elasticsearch: the client checks
the server, so OpenSearch isn't supported.

Using Lucene as well or instead, adding an elasticsearch index, reindexing: [docs/search.md](docs/search.md).

## Edge types

Edges are directed from source to target, with the same names in Neo4j and ArcadeDB:

| Edge type | Direction | Meaning | Status |
|---|---|---|---|
| `CHILD_OF` | child -> parent | Containment (the JCR tree) | both stores |
| `REFERENCES` | source -> target | JCR `REFERENCE`, `WEAKREFERENCE` and `PATH` properties. Edge properties: `name` (the property), `strength` (`strong`, `weak`, `path`), `ordinal` (position in a multi-valued property) | possible feature |

Each link keeps its native meaning (property name, strong or weak, order) rather than becoming a generic edge, so a
GraphRAG pipeline reading the graph can use it. The edge types are shared with the other graph projects, so graphs
from several content repositories can be queried alike.

### Possible feature: JCR references

`REFERENCE`, `WEAKREFERENCE` and `PATH` properties as `REFERENCES` edges, typed by the content model (the node types
say which properties are references). They can't be maintained in the `DocumentStore` like `CHILD_OF`: there, property
values are revision-stamped entries that may belong to uncommitted or rolled-back commits. Only a layer that sees
committed changes (an Oak `Observer`) could, and this project avoids such a projection layer for now. Oak already
enforces referential integrity for strong references; the edges would only mirror it. `nt:linkedFile` would be
covered, as its `jcr:content` is a `REFERENCE`.

## Tests

Most tests are Oak's own test classes, run through a fixture per backend (`ArcadeDBFixture`, `Neo4jFixture`): each
`ArcadeDB...Test` and `Neo4j...Test` subclasses the Oak class named after it. The edge and JCR tests are shared
abstract tests (`oak-store-graph` test-jar), with backend-specific edge queries.

| Test | What | ArcadeDB | Neo4j |
|---|---|---|---|
| **DocumentStore level** | | | |
| `...BasicTest` | Oak's `BasicDocumentStoreTest` | 42/42 | 42/42 |
| `...MultiTest` | `MultiDocumentStoreTest` (two stores, one database) | 9/9 | 9/9 |
| `...ConcurrentTest` | `ConcurrentDocumentStoreTest` | 2/2 | 2/2 |
| `...BulkCreateOrUpdateTest` | `BulkCreateOrUpdateTest` | 8/8 | 8/8 |
| `...CompressedDocumentPropertyStateTest` | `CompressedDocumentPropertyStateTest` | 9/9 | 9/9 |
| **DocumentNodeStore level** (Oak's node store on the store) | | | |
| `...DocumentNodeStoreTest` | `DocumentNodeStoreIT` | 4/4 | 4/4 |
| `...VersionGCSupportTest` | `VersionGCSupportTest` (revision garbage collection) | 15/15 | 15/15 |
| `...MissingLastRevSeekerTest` | `MissingLastRevSeekerTest` (cluster node recovery) | 12/12 | 12/12 |
| `...ResurrectNodeAfterRevisionGCTest` | `ResurrectNodeAfterRevisionGCTest` | 7/7 | 7/7 |
| `...BulkCreateOrUpdateClusterTest` | `BulkCreateOrUpdateClusterTest` (two cluster nodes) | 3/3 | 3/3 |
| **Edges and JCR** | | | |
| `...ChildEdgesTest` | child before parent, batches, parent removed and recreated, previous documents and other ids not linked, long (hashed) paths, node names (and a JCR property named `name`); ArcadeDB also: super-node promotion disabled, removing a node linked to other data's types | 8/8 | 6/6 |
| `...JcrRepositoryTest` | a JCR repository (`Jcr(Oak(DocumentNodeStore))`) on the store, see below | 4/4 | 4/4 |

The JCR tests: content survives a full restart (folders, an `nt:file` with a binary, and string, long, double,
boolean, date and multi-valued properties); a JCR-SQL2 query; versioning (check-in, check-out and restore); clustering
(two cluster nodes on the same database see each other's changes); edges (after a real repository's content is
written, every node document but the root has exactly one parent edge).

Each backend runs these tests (123 for ArcadeDB, 121 for Neo4j) in a few minutes (ArcadeDB about 8, Neo4j about 7),
spent in different places: `BasicDocumentStoreTest` grows one document to 32 MB, 1 MB at a time (ArcadeDB about 3
minutes, Neo4j under one), while the node store and JCR tests make thousands of small writes, each a request (ArcadeDB,
one HTTP request) or a transaction (Neo4j, about two Bolt round trips).

**Stress tests** (`mvn install -Pit`): `...ConcurrentQueryAndUpdateIT`, `...ConcurrentQueryAndUpdate2IT` and
`...ConcurrentQueryAndInvalidateIT` (Oak's `ConcurrentQueryAnd*IT`: thousands of updates racing with queries and cache
invalidations): about 3 minutes on ArcadeDB, 20 on Neo4j (2026-10-12).

Test output: each test class's logs go to `target/surefire-reports/<class>-output.txt` (`target/failsafe-reports/`
for the `*IT` stress tests), not to the console (`redirectTestOutputToFile`), which shows Maven's test summaries and
failures. The tests' `logback-test.xml` cuts stack traces to 3 lines: many of Oak's tests provoke exceptions on purpose
(failed conditional updates, lease timeouts with a virtual clock, a broken compression codec), and its test rules log
disposal warnings after the store's data is cleaned up. `logging.properties` keeps the Neo4j driver's
`java.util.logging` output to warnings.

### Servers for the tests

Docker must be running: by default each module starts its database with Testcontainers. To use an external server
instead (local or remote, Docker or standalone), set the properties below, or uncomment them in the module's `pom.xml`.

- **ArcadeDB:** image `arcadedata/arcadedb:26.10.1` (`arcadedb.version` in the parent `pom.xml`, also the ArcadeDB
  Java client's version), the version of the Docker Compose. An external database must exist and be dedicated to the
  tests, which delete its documents:

  ```
  mvn install -Doak.test.arcadedb.host=localhost -Doak.test.arcadedb.port=2480 \
      -Doak.test.arcadedb.database=oak-test -Doak.test.arcadedb.username=root \
      -Doak.test.arcadedb.password=...
  ```

- **Neo4j:** image `neo4j:2026.09.0` (`neo4j.version` in the parent `pom.xml`), the version of the Docker
  Compose; never an embedded Neo4j.
  External servers can be local, remote or AuraDB (`neo4j+s://`); the tests use the label prefix `OakTest` and only
  touch `OakTest*` nodes, so a database holding other data is safe:

  ```
  mvn install -Doak.test.neo4j.uri=bolt://localhost:7687 -Doak.test.neo4j.username=neo4j \
      -Doak.test.neo4j.password=...
  ```

## Build

```
mvn install                 # build and run the tests of both backends (Docker needed)
mvn install -pl oak-store-graph,oak-store-neo4j    # one backend
mvn install -Pit            # also the stress tests
mvn install -DskipTests
```

The build uses the released Oak artifacts from Maven Central: no Oak source checkout is needed. Behind TLS inspection
on Windows, a local `.mvn/jvm.config` with `-Djavax.net.ssl.trustStoreType=Windows-ROOT` makes Maven trust the Windows
certificate store (the file is git-ignored).

Graph database versions (change each one's two settings together):

- **ArcadeDB (26.10.1):** `ARCADEDB_VERSION` in `docker/.env` sets the Docker Compose's ArcadeDB server;
  `arcadedb.version` in the parent `pom.xml` sets the ArcadeDB Java client and the tests' ArcadeDB image
  (`-Doak.test.arcadedb.image=<image>` for another one).
- **Neo4j (2026.09.0):** `NEO4J_VERSION` in `docker/.env` sets the Docker Compose's Neo4j server; `neo4j.version` in the
  parent `pom.xml` sets the tests' Neo4j image (`-Doak.test.neo4j.image=<image>` for another one). The Neo4j Java
  driver (6.3.0) is `neo4j-driver.version`, also in the parent `pom.xml`.

## Licensing

This project is licensed under the [Apache License 2.0](LICENSE).

Everything linked at runtime is Apache 2.0: Oak, the ArcadeDB Java client, and the Neo4j Java driver (with Netty and
Reactor, embedded in the fragment), and in the Docker image Oak's Elasticsearch bundle (with Elastic's Java client) and
the text extraction libraries (Apache POI, XMLBeans, Commons Compress, Log4j API and its SLF4J bridge, TagSoup).
Testcontainers (tests only) is MIT.

The **Neo4j server is GPLv3** (Community) or commercial (Enterprise, Aura). It's only ever reached over Bolt as a
separate process (the Docker container in tests): no Neo4j server code is embedded, even in tests.

**Docker Composes.** The image is built locally, from `apache/sling:14` (the Sling Starter, Apache 2.0, which includes
Composum Nodes, MIT, removed here) and Composum Tools (MIT, built from its release tag); it isn't published. The
Compose files run the ArcadeDB (Apache 2.0), Neo4j Community (GPLv3), Elasticsearch and Kibana (Elastic License 2.0,
SSPL or AGPLv3) images, as separate processes.
