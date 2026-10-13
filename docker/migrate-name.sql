-- One-time migration of an Oak repository written by the ArcadeDB store before the node "name" property (2026-10-06).
-- Run with the Oak/Sling container stopped, as an SQL script in ArcadeDB Studio (database oak), or:
--   curl -u root:playwithdata -H "Content-Type: application/json" http://localhost:2490/api/v1/command/oak
--        -d "{\"language\": \"sqlscript\", \"command\": \"$(cat docker/migrate-name.sql | grep -v '^--' | tr '\n' ' ')\"}"
-- 1. The Oak key "name" (a JCR property named "name") is now stored as "|name".
UPDATE OakNode SET `|name` = name WHERE name IS NOT NULL;
-- 2. "name" is the node name: the last segment of the path (the long path for hashed ids), "" for the root.
UPDATE OakNode SET name = ifnull(_path, _id).substring(ifnull(_path, _id).lastIndexOf('/') + 1) WHERE NOT (_id LIKE '%:p/%');
CREATE PROPERTY OakNode.name IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON OakNode (name) NOTUNIQUE;
