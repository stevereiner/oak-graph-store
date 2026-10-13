# Port mappings

Host ports of the Docker Composes (`docker/compose.arcadedb.yaml`, `docker/compose.neo4j.yaml`). Each port is a variable
in `docker/.env`: change it there.

| Service | ArcadeDB stack | Neo4j stack | `docker/.env` variable | Container port |
|---|---|---|---|---|
| Sling (start page, Composum Tools, OSGi Web Console) | 8180 | 8181 | `SLING_ARCADEDB_PORT`, `SLING_NEO4J_PORT` | the same as the host port (*) |
| ArcadeDB Studio and HTTP API | 2490 | | `ARCADEDB_HTTP_PORT` | 2480 |
| Neo4j Browser (HTTP) | | 7475 | `NEO4J_HTTP_PORT` | 7474 |
| Neo4j Bolt | | 7690 | `NEO4J_BOLT_PORT` | 7687 |
| Elasticsearch | 9206 | 9207 | `OAK_ARCADEDB_ELASTICSEARCH_PORT`, `OAK_NEO4J_ELASTICSEARCH_PORT` | 9200 |
| Kibana | 5607 | 5608 | `OAK_ARCADEDB_KIBANA_PORT`, `OAK_NEO4J_KIBANA_PORT` | 5601 |

(*) Sling listens on the host port itself (`SLING_HTTP_PORT`), so that the URLs it builds match the browser's
(Composum Nodes, replaced by Composum Tools, required it).

Not published on the host: Elasticsearch's transport port (9300).

## Ports avoided

The host ports are chosen not to clash with other stacks that may run on the same machine:

| Ports | Used by |
|---|---|
| 8080, 8081 | Alfresco (proxy), Nuxeo of aborroy/nuxeo-deployment |
| 2480, 2424, 7689 | A default ArcadeDB (HTTP, binary, Bolt) |
| 7474, 7687 | A default Neo4j |
| 7688 | Memgraph |
| 5601-5604, 9200-9203 | Other Elasticsearch / OpenSearch servers and their dashboards |
| 8083, 8084, 2491, 7476, 7691, 9204, 9205, 5605, 5606, 9092, 9093, 8092, 8093 | The Nuxeo graph storage's stacks (nuxeo-graph-storage) |

On Windows, avoid 9248-9347: Hyper-V / WinNAT usually reserves that range, and binding a port in it fails ("An attempt
was made to access a socket in a way forbidden by its access permissions"). Check the reserved ranges with
`netsh interface ipv4 show excludedportrange protocol=tcp`.
