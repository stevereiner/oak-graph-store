package org.integratedsemantics.oak.graph.arcadedb;

import static org.integratedsemantics.oak.graph.ChildLink.CHILD_OF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.Document;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStoreBuilder;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.Revision;
import org.apache.jackrabbit.oak.plugins.document.StableRevisionComparator;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Condition;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Key;
import org.apache.jackrabbit.oak.plugins.document.UpdateUtils;
import org.apache.jackrabbit.oak.plugins.document.cache.ModificationStamp;
import org.integratedsemantics.oak.graph.ChildLink;
import org.integratedsemantics.oak.graph.GraphDocumentStore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.remote.RemoteDatabase;

/**
 * Oak {@code DocumentStore} storing the documents in ArcadeDB, through its Java remote (HTTP) API.
 * <p>
 * Each Oak collection is an ArcadeDB type ({@code OakNode} is a vertex type, the others document types), each Oak
 * document a record holding the whole document: Oak's revision maps are embedded maps, like in MongoDB. Node vertices
 * are linked to their parent vertex by {@code CHILD_OF} edges.
 * <p>
 * Each write is one server-side transaction ({@code sqlscript}). By default it runs optimistically: ArcadeDB detects
 * a concurrent change of the same record at commit, and runs the transaction again ({@code COMMIT RETRY}); a write
 * still conflicting after that runs again holding the write lock of the types it changes ({@code LOCK TYPE}), see
 * {@link WriteLockMode}.
 * <p>
 * ArcadeDB specifics: the HTTP API caps results at 20,000 rows (queries are paged), JSON transport turns small longs
 * into integers (integral numbers are read back as longs, as Oak uses longs), and strings that UTF-8 can't carry (lone
 * surrogates) are stored escaped.
 */
public class ArcadeDBDocumentStore extends GraphDocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(ArcadeDBDocumentStore.class);

    public static final String NODES_TYPE = "OakNode";

    public static final String CLUSTER_NODES_TYPE = "OakClusterNode";

    public static final String SETTINGS_TYPE = "OakSetting";

    public static final String JOURNAL_TYPE = "OakJournal";

    public static final String BLOBS_TYPE = "OakBlob";

    public static final List<String> TYPES = List.of(NODES_TYPE, CLUSTER_NODES_TYPE, SETTINGS_TYPE, JOURNAL_TYPE,
            BLOBS_TYPE);

    /** Edge type from a node vertex to its parent node vertex. */
    public static final String CHILD_OF_TYPE = CHILD_OF;

    /** The document types and the edge type. */
    public static final List<String> ALL_TYPES = List.of(NODES_TYPE, CLUSTER_NODES_TYPE, SETTINGS_TYPE, JOURNAL_TYPE,
            BLOBS_TYPE, CHILD_OF_TYPE);

    /**
     * Links inserted node documents ({@code :pLinks}) to their parent, and their existing children (whose parent was
     * missing, e.g. recreated) to them. Guarded so that a batch inserting a parent and its children creates each edge
     * once, in any order.
     */
    protected static final String LINK_SCRIPT = """
            FOREACH ($l IN :pLinks) {
              CREATE EDGE %1$s FROM (SELECT FROM %2$s WHERE `_id` = $l.c AND out('%1$s').size() = 0)
                TO (SELECT FROM %2$s WHERE `_id` = $l.p);
              CREATE EDGE %1$s FROM (SELECT FROM %2$s WHERE `_id` > $l.lo AND `_id` < $l.hi AND out('%1$s').size() = 0)
                TO (SELECT FROM %2$s WHERE `_id` = $l.c);
            }
            """.formatted(CHILD_OF_TYPE, NODES_TYPE);

    /** Page size for reads and batch writes, well below the 20,000 rows cap of the HTTP API. */
    protected static final int PAGE_SIZE = 5000;

    protected static final String ID = name(Document.ID);

    protected static final String MOD_COUNT = name(Document.MOD_COUNT);

    protected static final String MODIFIED = name(NodeDocument.MODIFIED_IN_SECS);

    protected final ArcadeDBConfig config;

    /** Idle remote database clients (not thread-safe: a client is used by one operation at a time). */
    protected final ConcurrentLinkedDeque<RemoteDatabase> pool = new ConcurrentLinkedDeque<>();

    /** The lock statement of the node writes, with the database's vertex and edge types; read again when they change. */
    protected volatile String nodesLock;

    /** System property: the {@link WriteLockMode} ({@code fallback} by default). */
    public static final String WRITE_LOCK_PROPERTY = "oak.arcadedb.writeLock";

    /** System property: the server-side retries of an optimistic write that conflicts with another (10 by default). */
    public static final String COMMIT_RETRIES_PROPERTY = "oak.arcadedb.commitRetries";

    protected final WriteLockMode writeLock = WriteLockMode.of(System.getProperty(WRITE_LOCK_PROPERTY));

    protected final int commitRetries = Integer.getInteger(COMMIT_RETRIES_PROPERTY, 10);

    /**
     * System property: whether updates are applied by the database (one {@code UPDATE ... RETURN BEFORE}, like the
     * MongoDB store), or read, applied in Java and written conditionally on the mod count ({@code false}). Default
     * {@code true}.
     */
    public static final String DATABASE_UPDATES_PROPERTY = "oak.arcadedb.databaseUpdates";

    protected final boolean databaseUpdates = !"false".equals(System.getProperty(DATABASE_UPDATES_PROPERTY));

    /** Count of the optimistic writes run again with the type lock (for tests and monitoring). */
    protected static final AtomicLong LOCK_FALLBACKS = new AtomicLong();

    /** A store with the default cache settings. */
    public ArcadeDBDocumentStore(ArcadeDBConfig config) {
        this(config, DocumentNodeStoreBuilder.newDocumentNodeStoreBuilder());
    }

    /** A store with the cache settings of the builder (cache size and distribution, persistent cache). */
    public ArcadeDBDocumentStore(ArcadeDBConfig config, DocumentNodeStoreBuilder<?> builder) {
        super(builder);
        this.config = config;
        LOG.info("Connecting to {}", config);
        initSchema();
    }

    /** Escapes a property name for SQL: {@code `_id`}. */
    protected static String name(String key) {
        return "`" + key.replace("`", "\\`") + "`";
    }

    protected static String type(Collection<?> collection) {
        if (collection == Collection.NODES) {
            return NODES_TYPE;
        } else if (collection == Collection.CLUSTER_NODES) {
            return CLUSTER_NODES_TYPE;
        } else if (collection == Collection.SETTINGS) {
            return SETTINGS_TYPE;
        } else if (collection == Collection.JOURNAL) {
            return JOURNAL_TYPE;
        } else if (collection == Collection.BLOBS) {
            return BLOBS_TYPE;
        }
        throw new IllegalArgumentException("Unknown collection: " + collection);
    }

    /**
     * The lock statement of the writes to a collection: ArcadeDB refuses to commit changes to types that aren't locked
     * once one is, and allows one lock statement per transaction. For nodes, that is every vertex and edge type of the
     * database: deleting a vertex also changes the types of its edges and of the vertices at their other end, which
     * can be other data's (a knowledge graph linked to the content nodes, for example).
     */
    protected String lockStatement(Collection<?> collection) {
        if (collection != Collection.NODES) {
            return "LOCK TYPE " + type(collection);
        }
        var lock = nodesLock;
        if (lock == null) {
            var types = new TreeSet<>(List.of(NODES_TYPE, CHILD_OF_TYPE));
            for (var result : query("SELECT name FROM schema:types WHERE type IN ['vertex', 'edge']", Map.of())) {
                types.add(result.getProperty("name"));
            }
            lock = "LOCK TYPE " + String.join(", ", types);
            nodesLock = lock;
        }
        return lock;
    }

    /**
     * Runs a locked write, again with the types read anew if the database has types the lock didn't cover (created
     * since they were read): the failed transaction was rolled back.
     */
    protected <R> R withLockRetry(Supplier<R> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            if (!isUnlockedTypes(e)) {
                throw e;
            }
            LOG.debug("New types in the database, locking them too: {}", e.getMessage());
            nodesLock = null;
            return write.get();
        }
    }

    protected static boolean isUnlockedTypes(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("not all the modified resources were locked")) {
                return true;
            }
        }
        return false;
    }

    // ----- connections -----

    protected RemoteDatabase newRemoteDatabase() {
        return new RemoteDatabase(config.host(), config.port(), config.database(), config.username(),
                config.password());
    }

    /** Runs a function with a pooled client (not in a transaction unless the function begins one). */
    protected <R> R withDatabase(Function<RemoteDatabase, R> function) {
        var db = pool.pollFirst();
        if (db == null) {
            db = newRemoteDatabase();
        }
        try {
            return function.apply(db);
        } finally {
            if (db.isTransactionActive()) {
                try {
                    db.rollback();
                } catch (RuntimeException e) {
                    LOG.debug("Rollback failed", e);
                }
            }
            if (disposed) {
                db.close();
            } else {
                pool.offerFirst(db);
            }
        }
    }

    protected List<Result> query(String sql, Map<String, Object> params) {
        LOG.trace("QUERY {} {}", sql, params);
        return withDatabase(db -> db.query("sql", sql, params).stream().toList());
    }

    /** The commit of an optimistic write: ArcadeDB runs the transaction again if it conflicts. */
    protected String optimisticCommit() {
        return commitRetries > 0 ? "COMMIT RETRY " + commitRetries : "COMMIT";
    }

    /**
     * Runs an optimistic write first (unless the mode is {@link WriteLockMode#ALWAYS}), and the locked one if it still
     * conflicts (mode {@link WriteLockMode#FALLBACK}): the optimistic transaction was rolled back, and the locked one
     * runs on the current data.
     */
    protected <R> R optimisticThenLocked(Supplier<R> optimistic, Supplier<R> locked) {
        if (writeLock != WriteLockMode.ALWAYS) {
            try {
                return optimistic.get();
            } catch (NeedRetryException e) {
                if (writeLock == WriteLockMode.NEVER) {
                    throw e;
                }
                LOG.debug("Write still conflicting after {} retries, running it again with the type lock: {}",
                        commitRetries, e.getMessage());
                LOCK_FALLBACKS.incrementAndGet();
            }
        }
        return withLockRetry(locked);
    }

    /**
     * Runs a write statement in a server-side transaction: optimistic, or holding the write lock of the collection's
     * types, see {@link #optimisticThenLocked}. The optimistic one reads with repeatable reads: with read committed
     * (ArcadeDB's default), two concurrent conditional updates of a record ({@code WHERE _modCount = n}) can both
     * commit.
     *
     * @return the count of written records (for UPDATE and DELETE)
     */
    protected long lockedCommand(Collection<?> collection, String statement, Map<String, Object> params) {
        return count(lockedResults(collection, statement, params));
    }

    /** Runs a write statement like {@link #lockedCommand}, and returns its results ({@code RETURN BEFORE}). */
    protected List<Result> lockedResults(Collection<?> collection, String statement, Map<String, Object> params) {
        return optimisticThenLocked(() -> {
            var script = "BEGIN ISOLATION REPEATABLE_READ; LET r = %s; %s; RETURN $r;".formatted(statement,
                    optimisticCommit());
            LOG.trace("SCRIPT {} {}", script, params);
            return withDatabase(db -> db.command("sqlscript", script, params).stream().toList());
        }, () -> {
            var script = "BEGIN; %s; LET r = %s; COMMIT; RETURN $r;".formatted(lockStatement(collection), statement);
            LOG.trace("SCRIPT {} {}", script, params);
            return withDatabase(db -> db.command("sqlscript", script, params).stream().toList());
        });
    }

    protected static long count(List<Result> results) {
        if (results.isEmpty()) {
            return 0;
        }
        Number n = results.get(0).getProperty("count");
        return n == null ? 0 : n.longValue();
    }

    /**
     * Creates the types and indexes if missing, and disables super-node promotion: since 26.8.1, ArcadeDB moves the
     * edges of a vertex with more than {@code arcadedb.graph.supernodeThreshold} (4096) edges to stripe buckets that
     * {@code LOCK TYPE} doesn't cover, so every later write touching it would fail (in Oak, for example, the UUID index
     * node has a child per referenceable node).
     */
    protected void initSchema() {
        // DDL with a client that is not pooled: a client's schema cache predates the types it didn't see created
        try (var db = newRemoteDatabase()) {
            try {
                db.command("sql", "ALTER DATABASE `arcadedb.graph.supernodeThreshold` 0");
            } catch (RuntimeException e) {
                LOG.warn("Cannot disable super-node promotion (set arcadedb.graph.supernodeThreshold=0 on the server): {}",
                        e.getMessage());
            }
            for (var type : TYPES) {
                var kind = NODES_TYPE.equals(type) ? "VERTEX" : "DOCUMENT";
                db.command("sql", "CREATE %s TYPE %s IF NOT EXISTS BUCKETS 8".formatted(kind, type));
                db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS STRING".formatted(type, ID));
                db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s) UNIQUE".formatted(type, ID));
                // declared as LONG so that they are read back as longs
                db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS LONG".formatted(type, MOD_COUNT));
                db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS LONG".formatted(type, MODIFIED));
                db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s) NOTUNIQUE".formatted(type, MODIFIED));
            }
            db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS STRING".formatted(NODES_TYPE, name(NAME)));
            db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s) NOTUNIQUE".formatted(NODES_TYPE, name(NAME)));
            db.command("sql", "CREATE EDGE TYPE %s IF NOT EXISTS BUCKETS 8".formatted(CHILD_OF_TYPE));
            var stripes = db.query("sql", "SELECT name FROM schema:buckets WHERE name LIKE :pPrefix",
                    Map.of("pPrefix", NODES_TYPE + "_sn_stripe_%")).stream().toList();
            if (!stripes.isEmpty()) {
                // the stripes can't be locked with the type (one lock statement per transaction)
                LOG.error("Database {} has vertices promoted to super-nodes (before promotion was disabled): writes "
                        + "adding child edges to them fail; recreate the database", config);
            }
        }
    }

    // ----- conversions -----

    /** Converts an Oak document to the map stored in ArcadeDB. */
    protected static Map<String, Object> toArcade(Document doc) {
        var map = new LinkedHashMap<String, Object>();
        for (var en : doc.entrySet()) {
            map.put(storedKey(en.getKey()), toArcadeValue(en.getValue()));
        }
        return map;
    }

    /** Converts an Oak value: revision map keys as strings, strings escaped if needed. */
    protected static Object toArcadeValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            var converted = new LinkedHashMap<String, Object>();
            m.forEach((k, v) -> converted.put(k.toString(), encode(v)));
            return converted;
        }
        return encode(value);
    }

    /** Converts a record read from ArcadeDB to an Oak document (like the MongoDB store does). */
    protected <T extends Document> T toDocument(Collection<T> collection, Map<String, Object> map) {
        T doc = collection.newDocument(this);
        for (var en : map.entrySet()) {
            if (en.getKey().startsWith("@")) {
                continue; // @rid, @type, @cat
            }
            var key = oakKey(en.getKey());
            if (key == null) {
                continue; // the graph view (name), not part of the Oak document
            }
            var value = en.getValue();
            if (value instanceof Map<?, ?> m) {
                var revisions = new TreeMap<Revision, Object>(StableRevisionComparator.REVERSE);
                m.forEach((k, v) -> revisions.put(Revision.fromString((String) k), toScalar(v)));
                doc.put(key, revisions);
            } else if (value != null) {
                doc.put(key, toScalar(value));
            }
        }
        doc.seal();
        return doc;
    }

    /** JSON transport turns small longs into integers: Oak uses longs. */
    protected static Object toScalar(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        return decode(value);
    }

    // ----- primitives -----

    @Override
    protected <T extends Document> T findUncached(Collection<T> collection, String id) {
        var results = query("SELECT FROM %s WHERE %s = :pId".formatted(type(collection), ID), Map.of("pId", id));
        return results.isEmpty() ? null : toDocument(collection, results.get(0).toMap());
    }

    @Override
    protected <T extends Document> List<T> queryUncached(Collection<T> collection, String fromKey, String toKey,
            String indexedProperty, long startValue, int limit) {
        var where = new StringBuilder("%s > :pAfter AND %s < :pTo".formatted(ID, ID));
        var params = new HashMap<String, Object>();
        params.put("pTo", toKey);
        if (indexedProperty != null) {
            var p = name(storedKey(indexedProperty));
            var condition = " AND (%s >= :pStart OR (%s = true AND 1 >= :pStart) OR (%s = false AND 0 >= :pStart))";
            where.append(condition.formatted(p, p, p));
            params.put("pStart", startValue);
        }
        var sql = "SELECT FROM %s WHERE %s ORDER BY %s LIMIT ".formatted(type(collection), where, ID);
        var docs = new ArrayList<T>();
        var after = fromKey;
        // keyset pagination on the id, to stay below the 20,000 rows cap
        while (docs.size() < limit) {
            int pageSize = Math.min(PAGE_SIZE, limit - docs.size());
            params.put("pAfter", after);
            var results = query(sql + pageSize, params);
            for (var result : results) {
                docs.add(toDocument(collection, result.toMap()));
            }
            if (results.size() < pageSize) {
                break;
            }
            after = docs.get(docs.size() - 1).getId();
        }
        return docs;
    }

    /** Script inserting documents ({@code :pList}) and linking the node documents ({@code :pLinks}). */
    protected static String insertScript(Collection<?> collection, List<?> links) {
        var script = "INSERT INTO %s FROM (SELECT expand(:pList));".formatted(type(collection));
        return links.isEmpty() ? script : script + "\n" + LINK_SCRIPT;
    }

    protected static Map<String, Object> insertParams(List<? extends Document> docs, List<ChildLink> links) {
        var params = new HashMap<String, Object>();
        var names = new HashMap<String, String>();
        links.forEach(l -> names.put(l.childId(), l.name()));
        params.put("pList", docs.stream().map(doc -> {
            var content = toArcade(doc);
            var name = names.get(doc.getId());
            if (name != null) {
                content.put(NAME, name);
            }
            return content;
        }).toList());
        if (!links.isEmpty()) {
            params.put("pLinks", links.stream()
                                      .map(l -> Map.of("c", l.childId(), "p", l.parentId(), "lo", l.lowerLimit(), "hi",
                                              l.upperLimit()))
                                      .toList());
        }
        return params;
    }

    @Override
    protected boolean insert(Collection<?> collection, List<? extends Document> docs, List<ChildLink> links) {
        try {
            optimisticThenLocked(() -> insertOnce(collection, docs, links, false),
                    () -> insertOnce(collection, docs, links, true));
            return true;
        } catch (DuplicatedKeyException | NeedRetryException e) {
            // duplicates are detected at commit
            LOG.debug("Insert failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Inserts the documents in one transaction, optimistic or holding the write lock. Read committed, also when
     * optimistic: with repeatable reads, ArcadeDB 26.10.1 doesn't check the unique index at commit for a key the
     * transaction read before (the link script reads the inserted ids), so a concurrent insert of the same id commits
     * a second record, missing from the index.
     */
    protected Void insertOnce(Collection<?> collection, List<? extends Document> docs, List<ChildLink> links,
            boolean locked) {
        if (docs.size() <= PAGE_SIZE) {
            // one request
            var params = insertParams(docs, links);
            var script = locked
                    ? "BEGIN; %s; %s COMMIT;".formatted(lockStatement(collection), insertScript(collection, links))
                    : "BEGIN; %s %s;".formatted(insertScript(collection, links), optimisticCommit());
            LOG.trace("SCRIPT {} {}", script, params);
            withDatabase(db -> db.command("sqlscript", script, params));
        } else {
            // one transaction over several requests: all or nothing (not retried by the server)
            withDatabase(db -> {
                db.begin();
                if (locked) {
                    db.command("sql", lockStatement(collection));
                }
                for (int i = 0; i < docs.size(); i += PAGE_SIZE) {
                    var batch = docs.subList(i, Math.min(i + PAGE_SIZE, docs.size()));
                    var batchLinks = links(collection, batch);
                    db.command("sqlscript", insertScript(collection, batchLinks), insertParams(batch, batchLinks));
                }
                db.commit();
                return null;
            });
        }
        return null;
    }

    /**
     * {@code UPDATE T SET a = :pV0, ..., _modCount = :pNewModCount REMOVE b, ... WHERE _id = :pId AND _modCount =
     * :pModCount}: only the changed properties are sent.
     */
    @Override
    protected boolean update(Collection<?> collection, String id, Long modCount, Map<String, Object> changes,
            long newModCount) {
        var set = new ArrayList<String>();
        var remove = new ArrayList<String>();
        var params = new HashMap<String, Object>();
        int i = 0;
        for (var en : changes.entrySet()) {
            var key = name(storedKey(en.getKey()));
            if (en.getValue() == null) {
                remove.add(key);
            } else {
                set.add("%s = :pV%d".formatted(key, i));
                params.put("pV" + i, toArcadeValue(en.getValue()));
                i++;
            }
        }
        set.add(MOD_COUNT + " = :pNewModCount");
        var sql = new StringBuilder("UPDATE ").append(type(collection))
                                              .append(" SET ")
                                              .append(String.join(", ", set));
        if (!remove.isEmpty()) {
            sql.append(" REMOVE ").append(String.join(", ", remove));
        }
        sql.append(" WHERE ").append(ID).append(" = :pId AND ").append(MOD_COUNT);
        sql.append(modCount == null ? " IS NULL" : " = :pModCount");
        params.put("pId", id);
        params.put("pNewModCount", newModCount);
        if (modCount != null) {
            params.put("pModCount", modCount);
        }
        try {
            return lockedCommand(collection, sql.toString(), params) == 1;
        } catch (NeedRetryException e) {
            LOG.debug("Concurrent update of {}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    protected boolean modifiesInDatabase() {
        return databaseUpdates;
    }

    @Override
    protected boolean modify(Collection<?> collection, UpdateOp update, long modCount) {
        var params = new HashMap<String, Object>();
        params.put("pModCount", modCount);
        var sql = updateStatement(collection, update, MOD_COUNT + " = :pModCount", false, params);
        try {
            return lockedCommand(collection, sql, params) == 1;
        } catch (NeedRetryException e) {
            LOG.debug("Concurrent update of {}: {}", update.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    protected <T extends Document> T findAndModify(Collection<T> collection, UpdateOp update) {
        var params = new HashMap<String, Object>();
        var sql = updateStatement(collection, update, null, true, params);
        var results = lockedResults(collection, sql, params);
        return results.isEmpty() ? null : toDocument(collection, results.get(0).toMap());
    }

    /**
     * The {@code UPDATE} statement applying an Oak update in the database, like the MongoDB store's {@code $set},
     * {@code $max}, {@code $inc} and {@code $unset}: {@code SET p = :pV0, m = ifnull(m, {}), m[:pK1] = :pV1,
     * x = max(ifnull(x, :pV2), :pV2), c = ifnull(c, 0) + :pV3, _modCount = ifnull(_modCount, 0) + 1 REMOVE q, m[:pK4]
     * [RETURN BEFORE] WHERE _id = :pId AND <conditions>}.
     *
     * @param extraCondition an additional condition, or {@code null}
     * @param params filled with the statement's parameters
     */
    protected static String updateStatement(Collection<?> collection, UpdateOp update, String extraCondition,
            boolean returnBefore, Map<String, Object> params) {
        var set = new ArrayList<String>();
        var remove = new ArrayList<String>();
        var maps = new HashSet<String>();
        int i = 0;
        for (var en : update.getChanges().entrySet()) {
            var key = en.getKey();
            if (Document.ID.equals(key.getName()) || Document.MOD_COUNT.equals(key.getName())) {
                continue;
            }
            var p = name(storedKey(key.getName()));
            var op = en.getValue();
            var v = ":pV" + i;
            var k = ":pK" + i;
            switch (op.type) {
                case SET -> {
                    set.add("%s = %s".formatted(p, v));
                    params.put("pV" + i, toArcadeValue(op.value));
                }
                case SET_MAP_ENTRY -> {
                    if (maps.add(p)) {
                        set.add("%s = ifnull(%s, {})".formatted(p, p));
                    }
                    set.add("%s[%s] = %s".formatted(p, k, v));
                    params.put("pK" + i, key.getRevision().toString());
                    params.put("pV" + i, encode(op.value));
                }
                case MAX -> {
                    set.add("%s = max(ifnull(%s, %s), %s)".formatted(p, p, v, v));
                    params.put("pV" + i, op.value);
                }
                case INCREMENT -> {
                    set.add("%s = ifnull(%s, 0) + %s".formatted(p, p, v));
                    params.put("pV" + i, op.value);
                }
                case REMOVE -> remove.add(p);
                case REMOVE_MAP_ENTRY -> {
                    remove.add("%s[%s]".formatted(p, k));
                    params.put("pK" + i, key.getRevision().toString());
                }
            }
            i++;
        }
        set.add("%s = ifnull(%s, 0) + 1".formatted(MOD_COUNT, MOD_COUNT));
        var sql = new StringBuilder("UPDATE ").append(type(collection)).append(" SET ").append(String.join(", ", set));
        if (!remove.isEmpty()) {
            sql.append(" REMOVE ").append(String.join(", ", remove));
        }
        if (returnBefore) {
            sql.append(" RETURN BEFORE");
        }
        sql.append(" WHERE ").append(ID).append(" = :pId");
        params.put("pId", update.getId());
        if (extraCondition != null) {
            sql.append(" AND ").append(extraCondition);
        }
        int c = 0;
        for (var en : update.getConditions().entrySet()) {
            sql.append(" AND ").append(condition(en.getKey(), en.getValue(), c++, params));
        }
        return sql.toString();
    }

    /** An Oak condition in SQL, with {@link UpdateUtils#checkConditions}'s semantics. */
    protected static String condition(Key key, Condition condition, int c, Map<String, Object> params) {
        var p = name(storedKey(key.getName()));
        var revision = key.getRevision();
        var k = ":pCk" + c;
        var v = ":pC" + c;
        if (revision != null) {
            params.put("pCk" + c, revision.toString());
        }
        return switch (condition.type) {
            case EXISTS -> {
                boolean exists = Boolean.TRUE.equals(condition.value);
                if (revision == null) {
                    yield exists ? p + " IS NOT NULL" : p + " IS NULL";
                }
                // an entry with a null value exists
                var contains = "ifnull(%s, {}).keys() CONTAINS %s".formatted(p, k);
                yield exists ? contains : "NOT (" + contains + ")";
            }
            case EQUALS, NOTEQUALS -> {
                var expr = revision == null ? p : "%s[%s]".formatted(p, k);
                boolean equals = condition.type == Condition.Type.EQUALS;
                if (condition.value == null) {
                    yield expr + (equals ? " IS NULL" : " IS NOT NULL");
                }
                params.put("pC" + c, encode(condition.value));
                yield equals ? "%s = %s".formatted(expr, v) : "(%s IS NULL OR %s <> %s)".formatted(expr, expr, v);
            }
        };
    }

    @Override
    protected void delete(Collection<?> collection, List<String> ids) {
        for (int i = 0; i < ids.size(); i += PAGE_SIZE) {
            var batch = ids.subList(i, Math.min(i + PAGE_SIZE, ids.size()));
            lockedCommand(collection, "DELETE FROM %s WHERE %s IN :pIds".formatted(type(collection), ID),
                    Map.of("pIds", batch));
        }
    }

    @Override
    protected int deleteIfModified(Collection<?> collection, Map<String, Long> toRemove) {
        int num = 0;
        var sql = "DELETE FROM %s WHERE %s = :pId AND %s = :pModified".formatted(type(collection), ID, MODIFIED);
        for (var en : toRemove.entrySet()) {
            num += (int) lockedCommand(collection, sql, Map.of("pId", en.getKey(), "pModified", en.getValue()));
        }
        return num;
    }

    @Override
    protected int deleteRange(Collection<?> collection, String property, long startValue, long endValue) {
        var p = name(storedKey(property));
        var sql = "DELETE FROM %s WHERE %s > :pStart AND %s < :pEnd".formatted(type(collection), p, p);
        return (int) lockedCommand(collection, sql, Map.of("pStart", startValue, "pEnd", endValue));
    }

    @Override
    protected Map<String, ModificationStamp> getModStamps(List<String> ids) {
        var stamps = new HashMap<String, ModificationStamp>();
        var sql = "SELECT %s, %s, %s FROM %s WHERE %s IN :pIds".formatted(ID, MOD_COUNT, MODIFIED, NODES_TYPE, ID);
        for (var result : query(sql, Map.of("pIds", ids))) {
            Number modCount = result.getProperty(Document.MOD_COUNT);
            Number modified = result.getProperty(NodeDocument.MODIFIED_IN_SECS);
            stamps.put(result.getProperty(Document.ID), new ModificationStamp(
                    modCount == null ? -1 : modCount.longValue(), modified == null ? -1 : modified.longValue()));
        }
        return stamps;
    }

    @Override
    protected void close() {
        RemoteDatabase db;
        while ((db = pool.pollFirst()) != null) {
            db.close();
        }
    }

    // ----- misc -----

    @Override
    public Map<String, String> getMetadata() {
        return Map.of("type", "arcadedb", "server", config.toString());
    }

    @Override
    public @NotNull Map<String, String> getStats() {
        var stats = new LinkedHashMap<String, String>();
        for (var type : ALL_TYPES) {
            var results = query("SELECT count(*) AS count FROM " + type, Map.of());
            stats.put(type + ".count", String.valueOf(count(results)));
        }
        return stats;
    }

}
