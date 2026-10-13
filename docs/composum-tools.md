# Using Composum Tools UI and Sling

Details for the README's
[Using Composum Tools UI and Sling](../README.md#using-composum-tools-ui-and-sling): creating folders and files,
previewing, searching and versioning them.

## Creating folders and files

In the JCR browser, select the parent node, then open the tree's menu: the ☰ button above the tree, next to its
refresh button, with **New Node...**, **Move...**, **Delete**, **Copy** and **Paste**. (The **+** in the properties
view adds a property to the selected node, not a node.) **New Node...** asks for a name and a primary type:

| To create | Primary type | Notes |
|---|---|---|
| A folder | `sling:OrderedFolder` (ordered children, like `/content`) or `sling:Folder` | `nt:folder` (JCR's own) only accepts files and folders as children. |
| A file | `nt:file` | Choosing it shows a **File** input for the upload; the node gets a `jcr:content` child (`nt:resource`) with the content (`jcr:data`) and its MIME type. |
| Other content (a page, a record) | `nt:unstructured` | Any properties and children; add properties with the properties view's **+**. |

A file's metadata: `nt:file` (and its `jcr:content`, an `nt:resource`) only allows the properties its type defines, so
a property such as `title` is refused. Add a mixin defining the properties first: on the file node, **+**
`jcr:mixinTypes` (type Name) = `mix:title`, which defines `jcr:title` and `jcr:description`; then add those two with
**+** (before the mixin they are refused). `jcr:mixinTypes` replaces the node's list of mixins: a versionable file
needs `mix:versionable` and `mix:title` (and must be checked out). Other metadata needs a mixin that defines it (a
content model: node types and mixins registered with repoinit).

Changes are staged: a badge counts them, **Commit All** writes them to the repository, **Revert All** discards them.
If the tree looks out of date after a commit, reload the page, or log out and back in: Composum Tools 1.4.1 can leave
its editing session open on Sling 14 (Sling then logs "Closed unclosed ResourceResolver").

Files can also be uploaded with Sling's POST servlet: `curl -u admin:admin -F "*=@report.pdf" http://localhost:8180/content/docs`
creates `/content/docs/report.pdf` (posting to a URL ending in `/` creates a new, auto-named child node first).
Uploading a file of the same name again replaces its content.

## Previewing files

Select a file in the JCR browser's tree: the **Preview** tab, next to **Properties**, **JSON** and **XML**, shows it in
the browser, by type: images, video (with a player), text and code, and documents such as PDF; other files appear as
a link to download them. The file node and its `jcr:content` child both show the file.

## Searching

The **Query** tool (its magnifier icon, next to **Favorites**) searches below the node selected in the tree. The query
field takes:

| Query | Finds |
|---|---|
| `quokka` (words) | Fulltext: nodes whose properties, or file text, contain the words (the Elasticsearch index `/oak:index/fulltext`) |
| `[nt:file]/content/docs report` | Fulltext limited to a node type and a path |
| `/jcr:root/content//element(*, mix:versionable)` | An XPath query |
| `SELECT * FROM [nt:file] AS f WHERE ISDESCENDANTNODE(f, [/content])` | A JCR-SQL2 query |

Its menus have query templates (fulltext, by resource type, files by text) with `${path}` and `${1}` placeholders, and
the query history. Results export as CSV (path, title, text, resource type, primary type, modified) or JSON; the
export is also an HTTP API, below the path to search:

```
curl -u admin:admin -G http://localhost:8180/apps/cpm/tools.browser.tool.query.save.json/content --data-urlencode "query=quokka"
```

More JCR-SQL2 and XPath examples: [queries.md](queries.md).

## Versioning files

Composum Tools 1.4.1 has no versioning actions: use Sling's POST servlet (or Oak's API, through JCR's
`VersionManager`). A node is versionable once it has the `mix:versionable` mixin; checking it in creates a version and
makes it read-only until it is checked out:

```
B=http://localhost:8180/content/docs/report.pdf
curl -u admin:admin -F jcr:mixinTypes=mix:versionable $B          # make it versionable
curl -u admin:admin -F :operation=checkin $B                      # version 1.0 (read-only now)
curl -u admin:admin -F :operation=checkout $B                     # editable again
curl -u admin:admin -F "*=@report.pdf" http://localhost:8180/content/docs   # new content (same file name)
curl -u admin:admin -F :operation=checkin $B                      # version 1.1
curl -u admin:admin -F :operation=restore -F :version=1.0 $B      # back to version 1.0
```

- **Cancel checkout:** JCR has no such operation; restoring the base version (the one the file was checked out from:
  the version node whose `jcr:uuid` is the file's `jcr:baseVersion`) discards the changes made since the checkout and
  checks the file in again: `curl -u admin:admin -F :operation=restore -F :version=1.1 $B`.
- **Branches:** a version created after restoring an older one branches from it: after restoring 1.0, the next
  check-in is `1.0.0`, not `1.2`.
- **Where versions are:** the node's `jcr:versionHistory` and `jcr:baseVersion` identify its history. The versions
  (`1.0`, `1.1`, ...) are nodes under `/jcr:system/jcr:versionStorage/<a>/<b>/<c>/<history uuid>` (the history uuid is
  the file's `jcr:uuid`, `a`, `b`, `c` its first three pairs of characters), in the graph database like any other node,
  each with a `jcr:frozenNode` copy of the file, its content readable at `.../1.0/jcr:frozenNode/jcr:content/jcr:data`.
- **Checked-in files:** changes fail (409); with Sling's `:autoCheckout=true` parameter, a change checks the file out,
  applies, and checks it in again (a new version). `nt:file` only allows its `jcr:content` child: properties such as
  `jcr:mimeType` are set on `jcr:content`.
