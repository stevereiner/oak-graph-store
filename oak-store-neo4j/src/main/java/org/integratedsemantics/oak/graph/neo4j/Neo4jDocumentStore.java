package org.integratedsemantics.oak.graph.neo4j;

import static org.integratedsemantics.oak.graph.ChildLink.CHILD_OF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.Document;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStoreBuilder;
import org.apache.jackrabbit.oak.plugins.document.DocumentStoreException;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.cache.ModificationStamp;
import org.integratedsemantics.oak.graph.ChildLink;
import org.integratedsemantics.oak.graph.GraphDocumentStore;
import org.jetbrains.annotations.NotNull;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.TransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Oak {@code DocumentStore} storing the documents in Neo4j (or AuraDB), through the Neo4j Java driver (Apache 2.0).
 * <p>
 * Each Oak document is a Neo4j node whose label names its collection ({@code OakNode}, {@code OakClusterNode},
 * {@code OakSetting}, {@code OakJournal}, {@code OakBlob}, with a configurable prefix). Node documents are linked to
 * their parent by {@code CHILD_OF} relationships.
 * <p>
 * Scalar values are node properties. Oak's revision maps (revision to value, where a {@code null} value records a
 * removal) can't be Neo4j properties, which have neither maps nor nulls: each is stored as one JSON string property
 * with a reserved prefix. This also keeps the number of properties of a node small (documents can have thousands of
 * revisions); strings starting with NUL or holding lone surrogates are stored escaped.
 * <p>
 * The conditional update first takes the node's write lock (by setting a temporary property), then checks the mod
 * count, then writes: Neo4j doesn't re-evaluate a condition after waiting for a lock, so checking first would let two
 * concurrent updates pass the same check.
 */
public class Neo4jDocumentStore extends GraphDocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(Neo4jDocumentStore.class);

    /** Prefix of the string properties holding a revision map, as JSON. */
    protected static final String MAP_PREFIX = "\u0000\u0002";

    /** Temporary property set to take a node's write lock ('|' is not allowed in JCR or Oak names). */
    protected static final String LOCK = "`|lock`";

    protected static final int BATCH_SIZE = 5000;

    protected static final String ID = name(Document.ID);

    protected static final String MOD_COUNT = name(Document.MOD_COUNT);

    protected static final String MODIFIED = name(NodeDocument.MODIFIED_IN_SECS);

    protected final Neo4jConfig config;

    protected final Driver driver;

    protected final boolean ownsDriver;

    protected final SessionConfig sessionConfig;

    /** A store with its own driver and the default cache settings. */
    public Neo4jDocumentStore(Neo4jConfig config) {
        this(config, DocumentNodeStoreBuilder.newDocumentNodeStoreBuilder());
    }

    /** A store with its own driver and the cache settings of the builder. */
    public Neo4jDocumentStore(Neo4jConfig config, DocumentNodeStoreBuilder<?> builder) {
        this(config, newDriver(config), true, builder);
    }

    /** A store using a driver owned by the caller (not closed on dispose). */
    public Neo4jDocumentStore(Neo4jConfig config, Driver driver, DocumentNodeStoreBuilder<?> builder) {
        this(config, driver, false, builder);
    }

    protected Neo4jDocumentStore(Neo4jConfig config, Driver driver, boolean ownsDriver,
            DocumentNodeStoreBuilder<?> builder) {
        super(builder);
        this.config = config;
        this.driver = driver;
        this.ownsDriver = ownsDriver;
        var sessionConfig = SessionConfig.builder()
                                         // causal consistency between the sessions (clusters, AuraDB)
                                         .withBookmarkManager(driver.executableQueryBookmarkManager());
        if (config.database() != null) {
            sessionConfig.withDatabase(config.database());
        }
        this.sessionConfig = sessionConfig.build();
        LOG.info("Connecting to {}", config);
        initSchema();
    }

    protected static Driver newDriver(Neo4jConfig config) {
        var auth = config.username() == null || config.username().isEmpty() ? AuthTokens.none()
                : AuthTokens.basic(config.username(), config.password());
        return GraphDatabase.driver(config.uri(), auth);
    }

    /** Escapes a property name for Cypher: {@code `_id`}. */
    protected static String name(String key) {
        return "`" + key.replace("`", "``") + "`";
    }

    /** The label of a collection's documents, escaped. */
    protected String label(Collection<?> collection) {
        String suffix;
        if (collection == Collection.NODES) {
            suffix = "Node";
        } else if (collection == Collection.CLUSTER_NODES) {
            suffix = "ClusterNode";
        } else if (collection == Collection.SETTINGS) {
            suffix = "Setting";
        } else if (collection == Collection.JOURNAL) {
            suffix = "Journal";
        } else if (collection == Collection.BLOBS) {
            suffix = "Blob";
        } else {
            throw new IllegalArgumentException("Unknown collection: " + collection);
        }
        return "`" + config.labelPrefix() + suffix + "`";
    }

    protected static final List<Collection<?>> COLLECTIONS = List.of(Collection.NODES, Collection.CLUSTER_NODES,
            Collection.SETTINGS, Collection.JOURNAL, Collection.BLOBS);

    // ----- sessions -----

    protected <R> R read(Function<TransactionContext, R> work) {
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(work::apply);
        } catch (Neo4jException e) {
            throw new DocumentStoreException(e);
        }
    }

    /** A write transaction, retried by the driver on transient errors (deadlocks). */
    protected <R> R write(Function<TransactionContext, R> work) {
        try (Session session = driver.session(sessionConfig)) {
            return session.executeWrite(work::apply);
        }
    }

    protected static long count(TransactionContext tx, String cypher, Map<String, Object> params) {
        LOG.trace("CYPHER {} {}", cypher, params);
        var records = tx.run(cypher, params).list();
        return records.isEmpty() ? 0 : records.get(0).get("count").asLong();
    }

    protected void initSchema() {
        try (Session session = driver.session(sessionConfig)) {
            for (var collection : COLLECTIONS) {
                var label = label(collection);
                var raw = label.substring(1, label.length() - 1);
                session.run("CREATE CONSTRAINT `%s_id` IF NOT EXISTS FOR (d:%s) REQUIRE d.%s IS UNIQUE".formatted(raw,
                        label, ID)).consume();
                session.run("CREATE INDEX `%s_modified` IF NOT EXISTS FOR (d:%s) ON (d.%s)".formatted(raw, label,
                        MODIFIED)).consume();
            }
            var nodeLabel = label(Collection.NODES);
            session.run("CREATE INDEX `%s_name` IF NOT EXISTS FOR (d:%s) ON (d.%s)".formatted(
                    nodeLabel.substring(1, nodeLabel.length() - 1), nodeLabel, name(NAME))).consume();
            session.run("CALL db.awaitIndexes(300)").consume();
        } catch (Neo4jException e) {
            throw new DocumentStoreException("Cannot initialize the Neo4j schema of " + config, e);
        }
    }

    // ----- conversions -----

    /** Converts an Oak document to the properties of its Neo4j node. */
    protected static Map<String, Object> toNeo4j(Document doc) {
        var map = new LinkedHashMap<String, Object>();
        for (var en : doc.entrySet()) {
            if (en.getValue() != null) {
                map.put(storedKey(en.getKey()), toNeo4jValue(en.getValue()));
            }
        }
        return map;
    }

    protected static Object toNeo4jValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            return MAP_PREFIX + RevisionMapJson.write(m);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        return encode(value);
    }

    /** Converts the properties of a Neo4j node to an Oak document. */
    protected <T extends Document> T toDocument(Collection<T> collection, Map<String, Object> properties) {
        T doc = collection.newDocument(this);
        for (var en : properties.entrySet()) {
            var key = oakKey(en.getKey());
            if (key == null) {
                continue; // the graph view (name), not part of the Oak document
            }
            var value = en.getValue();
            if (value instanceof String s && s.startsWith(MAP_PREFIX)) {
                doc.put(key, RevisionMapJson.read(s.substring(MAP_PREFIX.length())));
            } else if (value != null) {
                doc.put(key, decode(value));
            }
        }
        doc.seal();
        return doc;
    }

    // ----- primitives -----

    @Override
    protected <T extends Document> T findUncached(Collection<T> collection, String id) {
        var cypher = "MATCH (d:%s {%s: $id}) RETURN d".formatted(label(collection), ID);
        var properties = read(tx -> {
            var records = tx.run(cypher, Map.of("id", id)).list();
            return records.isEmpty() ? null : records.get(0).get("d").asNode().asMap();
        });
        return properties == null ? null : toDocument(collection, properties);
    }

    @Override
    protected <T extends Document> List<T> queryUncached(Collection<T> collection, String fromKey, String toKey,
            String indexedProperty, long startValue, int limit) {
        var cypher = new StringBuilder("MATCH (d:%s) WHERE d.%s > $from AND d.%s < $to".formatted(label(collection),
                ID, ID));
        var params = new HashMap<String, Object>();
        params.put("from", fromKey);
        params.put("to", toKey);
        params.put("limit", (long) limit);
        if (indexedProperty != null) {
            var p = "d." + name(storedKey(indexedProperty));
            var condition = " AND (%s >= $start OR (%s = true AND 1 >= $start) OR (%s = false AND 0 >= $start))";
            cypher.append(condition.formatted(p, p, p));
            params.put("start", startValue);
        }
        cypher.append(" RETURN d ORDER BY d.%s LIMIT $limit".formatted(ID));
        LOG.trace("CYPHER {} {}", cypher, params);
        var rows = read(tx -> tx.run(cypher.toString(), params).list(r -> r.get("d").asNode().asMap()));
        var docs = new ArrayList<T>(rows.size());
        for (var row : rows) {
            docs.add(toDocument(collection, row));
        }
        return docs;
    }

    @Override
    protected boolean insert(Collection<?> collection, List<? extends Document> docs, List<ChildLink> links) {
        var label = label(collection);
        var create = "UNWIND $docs AS doc CREATE (d:%s) SET d = doc".formatted(label);
        // the inserted nodes to their parent, then their existing children (parent missing until now) to them;
        // guarded so that each edge is created once, whatever the order of a batch
        var linkParents = """
                UNWIND $links AS l
                MATCH (c:%1$s {%2$s: l.c}) WHERE NOT (c)-[:%3$s]->()
                MATCH (p:%1$s {%2$s: l.p})
                CREATE (c)-[:%3$s]->(p)""".formatted(label, ID, CHILD_OF);
        var linkChildren = """
                UNWIND $links AS l
                MATCH (c:%1$s {%2$s: l.c})
                MATCH (k:%1$s) WHERE k.%2$s > l.lo AND k.%2$s < l.hi AND NOT (k)-[:%3$s]->()
                CREATE (k)-[:%3$s]->(c)""".formatted(label, ID, CHILD_OF);
        var names = new HashMap<String, String>();
        links.forEach(l -> names.put(l.childId(), l.name()));
        var contents = docs.stream().map(doc -> {
            var content = toNeo4j(doc);
            var name = names.get(doc.getId());
            if (name != null) {
                content.put(NAME, name);
            }
            return content;
        }).toList();
        var linkParams = links.stream()
                              .map(l -> Map.of("c", l.childId(), "p", l.parentId(), "lo", l.lowerLimit(), "hi",
                                      l.upperLimit()))
                              .toList();
        try {
            // one transaction: all or nothing (the unique constraint rejects existing ids)
            return write(tx -> {
                for (int i = 0; i < contents.size(); i += BATCH_SIZE) {
                    var batch = contents.subList(i, Math.min(i + BATCH_SIZE, contents.size()));
                    LOG.trace("CYPHER {} {}", create, batch);
                    tx.run(create, Map.of("docs", batch)).consume();
                }
                for (int i = 0; i < linkParams.size(); i += BATCH_SIZE) {
                    var batch = linkParams.subList(i, Math.min(i + BATCH_SIZE, linkParams.size()));
                    tx.run(linkParents, Map.of("links", batch)).consume();
                    tx.run(linkChildren, Map.of("links", batch)).consume();
                }
                return true;
            });
        } catch (ClientException e) {
            if (e.code() != null && e.code().endsWith("ConstraintValidationFailed")) {
                LOG.debug("Insert failed: {}", e.getMessage());
                return false;
            }
            throw new DocumentStoreException(e);
        } catch (TransientException e) {
            LOG.debug("Insert failed: {}", e.getMessage());
            return false;
        } catch (Neo4jException e) {
            throw new DocumentStoreException(e);
        }
    }

    @Override
    protected boolean update(Collection<?> collection, String id, Long modCount, Map<String, Object> changes,
            long newModCount) {
        var check = modCount == null ? "d.%s IS NULL".formatted(MOD_COUNT)
                : "coalesce(d.%s = $modCount, false)".formatted(MOD_COUNT);
        // lock first (SET), then check, then write
        var cypher = """
                MATCH (d:%1$s {%2$s: $id})
                SET d.%3$s = true
                WITH d, %4$s AS ok
                REMOVE d.%3$s
                WITH d, ok WHERE ok
                SET d += $changes, d.%5$s = $newModCount
                RETURN count(d) AS count""".formatted(label(collection), ID, LOCK, check, MOD_COUNT);
        var values = new HashMap<String, Object>();
        changes.forEach((k, v) -> values.put(storedKey(k), v == null ? null : toNeo4jValue(v))); // null removes
        var params = new HashMap<String, Object>();
        params.put("id", id);
        params.put("changes", values);
        params.put("newModCount", newModCount);
        if (modCount != null) {
            params.put("modCount", modCount);
        }
        try {
            return write(tx -> count(tx, cypher, params)) == 1;
        } catch (TransientException e) {
            LOG.debug("Concurrent update of {}: {}", id, e.getMessage());
            return false;
        } catch (Neo4jException e) {
            throw new DocumentStoreException(e);
        }
    }

    @Override
    protected void delete(Collection<?> collection, List<String> ids) {
        var cypher = "MATCH (d:%s) WHERE d.%s IN $ids DETACH DELETE d".formatted(label(collection), ID);
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            var batch = ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()));
            writeOrFail(tx -> tx.run(cypher, Map.of("ids", batch)).consume());
        }
    }

    @Override
    protected int deleteIfModified(Collection<?> collection, Map<String, Long> toRemove) {
        // lock first, then check, then delete
        var cypher = """
                UNWIND $rows AS r
                MATCH (d:%1$s {%2$s: r.id})
                SET d.%3$s = true
                WITH d, coalesce(d.%4$s = r.modified, false) AS ok
                REMOVE d.%3$s
                WITH d, ok WHERE ok
                DETACH DELETE d
                RETURN count(*) AS count""".formatted(label(collection), ID, LOCK, MODIFIED);
        var rows = toRemove.entrySet()
                           .stream()
                           .map(en -> Map.<String, Object> of("id", en.getKey(), "modified", en.getValue()))
                           .toList();
        int num = 0;
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            var batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            num += (int) (long) writeOrFail(tx -> count(tx, cypher, Map.of("rows", batch)));
        }
        return num;
    }

    @Override
    protected int deleteRange(Collection<?> collection, String property, long startValue, long endValue) {
        var p = "d." + name(storedKey(property));
        var template = "MATCH (d:%s) WHERE %s > $start AND %s < $end DETACH DELETE d RETURN count(*) AS count";
        var cypher = template.formatted(label(collection), p, p);
        return (int) (long) writeOrFail(tx -> count(tx, cypher, Map.of("start", startValue, "end", endValue)));
    }

    protected <R> R writeOrFail(Function<TransactionContext, R> work) {
        try {
            return write(work);
        } catch (Neo4jException e) {
            throw new DocumentStoreException(e);
        }
    }

    @Override
    protected Map<String, ModificationStamp> getModStamps(List<String> ids) {
        var template = "MATCH (d:%s) WHERE d.%s IN $ids RETURN d.%s AS id, d.%s AS modCount, d.%s AS modified";
        var cypher = template.formatted(label(Collection.NODES), ID, ID, MOD_COUNT, MODIFIED);
        return read(tx -> {
            var stamps = new HashMap<String, ModificationStamp>();
            for (var r : tx.run(cypher, Map.of("ids", ids)).list()) {
                var modCount = r.get("modCount");
                var modified = r.get("modified");
                stamps.put(r.get("id").asString(), new ModificationStamp(modCount.isNull() ? -1 : modCount.asLong(),
                        modified.isNull() ? -1 : modified.asLong()));
            }
            return stamps;
        });
    }

    @Override
    protected void close() {
        if (ownsDriver) {
            driver.close();
        }
    }

    // ----- misc -----

    @Override
    public Map<String, String> getMetadata() {
        return Map.of("type", "neo4j", "server", config.toString());
    }

    @Override
    public @NotNull Map<String, String> getStats() {
        var stats = new LinkedHashMap<String, String>();
        read(tx -> {
            for (var collection : COLLECTIONS) {
                var label = label(collection);
                long n = tx.run("MATCH (d:%s) RETURN count(d) AS count".formatted(label))
                           .single()
                           .get("count")
                           .asLong();
                stats.put(label.replace("`", "") + ".count", String.valueOf(n));
            }
            var label = label(Collection.NODES);
            long n = tx.run("MATCH (:%s)-[r:%s]->() RETURN count(r) AS count".formatted(label, CHILD_OF))
                       .single()
                       .get("count")
                       .asLong();
            stats.put(CHILD_OF + ".count", String.valueOf(n));
            return null;
        });
        return stats;
    }

}
