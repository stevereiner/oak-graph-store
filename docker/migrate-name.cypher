// One-time migration of an Oak repository written by the Neo4j store before the node "name" property (2026-10-06).
// Run with the Oak/Sling container stopped:
//   docker exec -i oak-graph-neo4j-1 cypher-shell -u neo4j -p password < docker/migrate-name.cypher
// 1. The Oak key "name" (a JCR property named "name") is now stored as "|name".
MATCH (n) WHERE any(l IN labels(n) WHERE l STARTS WITH 'Oak') AND n.name IS NOT NULL
SET n.`|name` = n.name;
// 2. "name" is the node name: the last segment of the path (the long path for hashed ids), "" for the root.
MATCH (n:OakNode) WHERE NOT n._id CONTAINS ':p/'
WITH n, coalesce(n._path, n._id) AS path
SET n.name = split(path, '/')[-1];
