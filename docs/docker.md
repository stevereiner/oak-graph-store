# Docker Composes: services and volumes

Details for the README's [Docker Composes](../README.md#docker-composes).

Both stacks, `docker/compose.arcadedb.yaml` and `docker/compose.neo4j.yaml`, have the same services and volumes, except
the graph database. Docker prefixes the volumes with the Compose project name, for example
`oak-graph-arcadedb_oak-datastore`.

| Service | Volume | Holds |
|---|---|---|
| `arcadedb` / `neo4j` | `arcadedb-databases` / `neo4j-data` | The graph database: all the repository's nodes (`OakNode` vertices or nodes, `CHILD_OF` edges), and Oak's own records: cluster nodes, settings, journal |
| `oak` (Sling with Oak and Composum Tools) | `oak-datastore` | Oak's file data store: the binaries of 4 KB or more (files); smaller ones are stored in the nodes |
| `elasticsearch`, `kibana` | `elasticsearch-data` | Oak's elasticsearch indexes (rebuilt by reindexing if lost) |

Oak keeps everything else in its repository, so there is no other database: content, users and groups (`/home`),
permissions, node types, index definitions and property index data, Sling's scripts and content (`/apps`, `/libs`,
`/conf`) are all nodes in the graph database. The OSGi framework cache (`/opt/sling/launcher`) is a `tmpfs`, rebuilt on
each start, so that a rebuilt image is used as is.

`docker compose ... down` removes the containers and the network and keeps the volumes, so the next `up` finds the
content again; `down -v` also deletes the volumes. Keep or delete a stack's volumes together: a node's properties are
in the graph database, its binaries in the datastore, and the elasticsearch indexes in Elasticsearch. Deleting only
some of them leaves nodes without their binaries, or indexes without their content.
