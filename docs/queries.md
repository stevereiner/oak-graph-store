# Queries

Details for the README's [Queries](../README.md#queries): Oak's own queries (JCR-SQL2 and XPath), and the graph read
directly with Cypher or SQL.

## Oak queries: JCR-SQL2 and XPath

Oak's [query engine](https://jackrabbit.apache.org/oak/docs/query/query-engine.html) answers
[JCR-SQL2](https://jackrabbit.apache.org/oak/docs/query/grammar-sql2.html) and
[XPath](https://jackrabbit.apache.org/oak/docs/query/grammar-xpath.html) queries (XPath is converted to SQL-2), with
the user's permissions applied. They run unchanged on the graph stores, which are below Oak:

- **Indexes:** for each query Oak asks the index definitions under `/oak:index` for their cost, and uses the cheapest:
  property and node type indexes (repository nodes, so in the graph database), or in the Docker Composes the
  elasticsearch index `/oak:index/fulltext` for `CONTAINS` / `jcr:contains` (see the README's
  [Search](../README.md#search-elasticsearch-instead-of-lucene)).
- **Traversal:** a query no index can answer still runs, by traversing the repository (slow on a large one); Oak logs
  "Traversal query (query without index)", or `Traversed N nodes` warnings. `option(traversal fail)` makes such a
  query fail instead.

Where to run them:

- **Composum Tools' query tool:** in the JCR browser (http://localhost:8180/apps/cpm/tools.browser.html), a query field
  above the results, with its templates and history; results export as JSON or CSV.
- **Oak's API:** through JCR, `session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2)`
  (or `Query.XPATH`), then `execute()`; below JCR, `contentSession.getLatestRoot().getQueryEngine()`.
- **Over HTTP**, with Jackrabbit's JCR remoting (DavEx, `/server` in Sling Starter): a WebDAV `SEARCH` whose body
  element names the language (`JCR-SQL2` or `xpath`):

  ```
  curl -u admin:admin -X SEARCH -H "Content-Type: text/xml" http://localhost:8180/server/default/jcr:root/ \
       --data "<D:searchrequest xmlns:D='DAV:'><JCR-SQL2><![CDATA[SELECT * FROM [nt:file] AS f WHERE ISDESCENDANTNODE(f, [/content])]]></JCR-SQL2></D:searchrequest>"
  ```

### Examples

Each example is JCR-SQL2, then the same in XPath where there is one:

```sql
-- the children of /content
SELECT * FROM [nt:base] AS n WHERE ISCHILDNODE(n, [/content])
/jcr:root/content/*

-- the files under /content, at any depth, newest first
SELECT * FROM [nt:file] AS f WHERE ISDESCENDANTNODE(f, [/content]) ORDER BY f.[jcr:created] DESC
/jcr:root/content//element(*, nt:file) order by @jcr:created descending

-- PDF files: their jcr:content (nt:resource) nodes, by MIME type
SELECT * FROM [nt:resource] AS r WHERE ISDESCENDANTNODE(r, [/content]) AND r.[jcr:mimeType] = 'application/pdf'
/jcr:root/content//element(*, nt:resource)[@jcr:mimeType = 'application/pdf']

-- text files: a join of the files with their jcr:content (JCR-SQL2 only)
SELECT f.* FROM [nt:file] AS f INNER JOIN [nt:resource] AS r ON ISCHILDNODE(r, f)
  WHERE ISDESCENDANTNODE(f, [/content]) AND r.[jcr:mimeType] LIKE 'text/%'

-- versionable nodes (with the mix:versionable mixin)
SELECT * FROM [mix:versionable] AS v WHERE ISDESCENDANTNODE(v, [/content])
/jcr:root/content//element(*, mix:versionable)

-- by a property, with a pattern
SELECT * FROM [nt:base] AS n WHERE ISDESCENDANTNODE(n, [/content]) AND n.[jcr:title] LIKE 'H%'
/jcr:root/content//*[jcr:like(@jcr:title, 'H%')]

-- fulltext, over the properties and the text of files (the elasticsearch index)
SELECT * FROM [nt:base] AS s WHERE CONTAINS(s.*, 'quokka')
//*[jcr:contains(., 'quokka')]

-- the versions in the version storage (JCR-SQL2 only, as written)
SELECT * FROM [nt:version] AS v WHERE ISDESCENDANTNODE(v, [/jcr:system/jcr:versionStorage])
  AND NAME(v) <> 'jcr:rootVersion'
```

## Querying the graph directly

The names are the same in both databases (node documents `OakNode` with `_id` and `name`, edges `CHILD_OF`), so the
same Cypher queries work in Neo4j Browser and in ArcadeDB Studio (whose Cypher support runs them unchanged):

```cypher
// the children of the root, by name
MATCH (:OakNode {_id: '0:/'})<-[:CHILD_OF]-(c) RETURN c.name ORDER BY c.name
// the nodes named content, and their children (also inside Oak's index data, stored as nodes under /oak:index)
MATCH (:OakNode {name: 'content'})<-[:CHILD_OF]-(c) RETURN c.name, c._id
// a node and its ancestors, up to the root
MATCH p = (:OakNode {_id: '3:/a/b/c'})-[:CHILD_OF*]->(:OakNode {_id: '0:/'}) RETURN [n IN nodes(p) | n.name]
```

In ArcadeDB, the same in SQL:

```sql
-- the children of /content
SELECT name, _id FROM (SELECT expand(in('CHILD_OF')) FROM OakNode WHERE _id = '1:/content')
-- a node and its ancestors, up to the root
TRAVERSE out('CHILD_OF') FROM (SELECT FROM OakNode WHERE _id = '3:/a/b/c')
```

Property values are Oak's revision maps (`{revision: value}`, for its multi-version concurrency): JSON strings in Neo4j,
embedded maps in ArcadeDB, with typed values such as `"nam:sling:Folder"` (a JCR NAME). For example, the folders under
`/content`, in Neo4j:

```cypher
MATCH (c:OakNode)-[:CHILD_OF*]->(:OakNode {_id: '1:/content'})
WHERE c.`jcr:primaryType` CONTAINS 'Folder' OR c.`jcr:primaryType` CONTAINS 'nt:folder'
RETURN c.name, c._id
```

The graph is read directly with database access, which is privileged: queries aren't checked against JCR access
control, and writes go through Oak (see the README's [Uses](../README.md#uses)).

The JCR-SQL2 and XPath examples were run on both Docker Composes, through DavEx.
