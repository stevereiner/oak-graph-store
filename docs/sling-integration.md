# How it plugs into Sling

Details for the README's [Docker Composes](../README.md#docker-composes).

- **A fragment of Oak's bundle:** in OSGi, Oak's `oak-store-document` bundle doesn't export the `DocumentStore` API
  (only a few SPI packages), so a separate bundle can't implement a document store. The stores are therefore a fragment
  of that bundle (`oak-graph-fragment`), running in its class loader, with the ArcadeDB client and the Neo4j driver
  (with Netty and Reactor) embedded, private to it. A fragment must match its host's version exactly: it is built for
  the Oak version of the Sling image (1.90.0 for Sling Starter 14).
- **Registered like Oak's own service:** Oak's `DocumentNodeStoreService` only knows MongoDB and RDB. The
  `oak-graph-sling` component (configured, it replaces it) starts the `DocumentNodeStore` on the graph store and
  registers it as `NodeStore` (what Sling's repository waits for), `DocumentNodeStore` (Oak's discovery lite service)
  and `Clusterable`, attaches the OSGi observers, and registers the MBeans Oak's own service does: checkpoint
  management (without it, Oak's Lucene index provider waits, and Lucene indexes, if any, are neither updated nor used),
  and the revision and blob garbage collection used by Sling's maintenance tasks.
- **A Sling feature:** `docker/oak_graph.slingosgifeature` is Sling Starter 14's `oak_mongo` feature (a prototype)
  minus its MongoDB bundles and configuration, plus the two bundles, Oak's Elasticsearch bundle and their configuration,
  read from environment variables (`OAK_GRAPH_BACKEND`, `ARCADEDB_HOST`, `NEO4J_URI`, `ELASTICSEARCH_HOST`, ...). The
  image is `apache/sling:14` with these files added; the Maven build of the bundles runs in the Docker build.
- **Oak's Elasticsearch bundle, patched:** Sling Starter doesn't include `oak-search-elastic`; the image adds it, in
  its Oak version. Its Elasticsearch client finds its JSON-P provider on the thread's context class loader, which in
  Sling finds Johnzon's (`org.apache.sling.commons.johnzon`), built on Sling's `jakarta.json` API, not on the copy
  embedded in the bundle: every write to Elasticsearch failed ("not a subtype"). The build rewrites the bundle's
  manifest to import Sling's `jakarta.json` packages instead of embedding them (`docker/OakSearchElasticPatch.java`),
  and the feature lists the result under this project's group id, as it isn't Oak's released artifact.
- **Text extraction (Tika):** Oak extracts the text of binaries with Apache Tika, which Sling Starter ships with few of
  its libraries: out of the box only PDF was extracted. The image adds, as OSGi bundles, Apache POI 5.2.2 (the version
  Tika 1.28.5 is built with: Office documents, and the charset detection of every text file), XMLBeans 5 (docx, xlsx,
  pptx), Commons Compress, TagSoup (HTML) and the Log4j 2 API that XMLBeans needs (routed to Sling's logging by
  `log4j-to-slf4j`), and two small fragments for OSGi class loading:
  - `docker/tika-encoding-detectors`, on tika-core: Tika looks its encoding detectors up with tika-core's class
    loader, which can't see the tika-parsers bundle that lists them, so every text file failed with "Failed to detect
    the character encoding of a document";
  - `docker/poi-dom-imports`, on the ServiceMix POI bundle, whose manifest lacks the `org.w3c.dom` imports: POI's
    OOXML support failed to load.

  Plain text, HTML, Word (docx) and PDF are verified; Excel and PowerPoint use the same libraries. A file whose text
  can't be extracted is indexed with `TextExtractionError` in its fulltext (found by name and properties, not text).
