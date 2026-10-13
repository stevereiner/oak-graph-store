package org.integratedsemantics.oak.graph;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.jackrabbit.oak.plugins.document.UpdateUtils.applyChanges;
import static org.apache.jackrabbit.oak.plugins.document.UpdateUtils.assertUnconditional;
import static org.apache.jackrabbit.oak.plugins.document.UpdateUtils.checkConditions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.Document;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStoreBuilder;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentStoreException;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.Revision;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp;
import org.apache.jackrabbit.oak.plugins.document.cache.CacheChangesTracker;
import org.apache.jackrabbit.oak.plugins.document.cache.CacheInvalidationStats;
import org.apache.jackrabbit.oak.plugins.document.cache.ModificationStamp;
import org.apache.jackrabbit.oak.plugins.document.cache.NodeDocumentCache;
import org.apache.jackrabbit.oak.plugins.document.locks.NodeDocumentLocks;
import org.apache.jackrabbit.oak.plugins.document.locks.StripedNodeDocumentLocks;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base of the Oak {@link DocumentStore}s storing the documents in a graph database: everything that doesn't depend on
 * the database, which subclasses access through a few primitives (find, query, insert, conditional update, deletes).
 * <p>
 * Updates are done like {@link org.apache.jackrabbit.oak.plugins.document.memory.MemoryDocumentStore} does: the
 * document is read, the conditions are checked and the changes applied in Java ({@code UpdateUtils}), and the changed
 * properties are written back with optimistic concurrency on {@link Document#MOD_COUNT}: a conditional update, retried
 * if another writer changed the document in the meantime.
 * <p>
 * The node documents are cached like in the MongoDB store, with Oak's {@link NodeDocumentCache}: an update starts
 * from the cached document (its mod count is checked by the conditional write), the new document computed in Java
 * replaces it, and the changes of other cluster nodes are invalidated by {@link #invalidateCache(Iterable)}, which
 * {@code DocumentNodeStore} calls with the changes it reads from the journal.
 * <p>
 * Node documents are linked to their parent by {@link ChildLink#CHILD_OF} edges, created when they are inserted (and
 * from their existing children to them, e.g. when a parent is recreated) and removed with them.
 */
public abstract class GraphDocumentStore implements DocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(GraphDocumentStore.class);

    /** Maximum number of optimistic update attempts before giving up. */
    protected static final int MAX_ATTEMPTS = 100;

    /** Maximum id length, in UTF-8 bytes (Oak hashes long paths: real ids are shorter). */
    public static final int MAX_ID_BYTES = 1024;

    /**
     * The name of a node (last segment of its path), stored with node documents for graph queries and tools (Neo4j
     * Browser captions nodes with it), as Oak doesn't store it (it's in the id). Not part of the Oak document: added on
     * insert (a node is never renamed: a move creates a new document), removed on read. The Oak key {@code name} (a JCR
     * property named {@code name}) is stored as {@code |name}: see {@link #storedKey(String)}.
     */
    public static final String NAME = "name";

    /** The database property names reserved for the graph view of the documents (Oak keys of these names are escaped). */
    protected static final Set<String> RESERVED_KEYS = Set.of(NAME);

    /** Prefix of the escaped Oak keys: {@code |} is in no Oak key (not allowed in JCR names). */
    protected static final String KEY_ESCAPE = "|";

    /** The database property name of an Oak key: the Oak keys reserved for the graph view are escaped. */
    public static String storedKey(String oakKey) {
        return RESERVED_KEYS.contains(oakKey) ? KEY_ESCAPE + oakKey : oakKey;
    }

    /** The Oak key of a database property name ({@code null} for the properties of the graph view). */
    public static String oakKey(String storedKey) {
        if (storedKey.startsWith(KEY_ESCAPE)) {
            return storedKey.substring(KEY_ESCAPE.length());
        }
        return RESERVED_KEYS.contains(storedKey) ? null : storedKey;
    }

    /** Prefix of the strings stored escaped. Strings starting with a NUL are reserved for the backends' encodings. */
    protected static final String ESCAPED_PREFIX = "\u0000\u0001";

    /** Serializes the loads and updates of a node document in this process. */
    protected final NodeDocumentLocks nodeLocks = new StripedNodeDocumentLocks();

    protected final NodeDocumentCache nodesCache;

    protected volatile boolean disposed;

    /** @param builder the cache settings (cache size and distribution, persistent cache) */
    protected GraphDocumentStore(DocumentNodeStoreBuilder<?> builder) {
        nodesCache = builder.buildNodeDocumentCache(this, nodeLocks);
    }

    // ----- database primitives -----

    /** Reads a document from the database. */
    protected abstract <T extends Document> T findUncached(Collection<T> collection, String id);

    /** The documents with {@code fromKey < id < toKey} (and the indexed property condition), ordered by id. */
    protected abstract <T extends Document> List<T> queryUncached(Collection<T> collection, String fromKey,
            String toKey, String indexedProperty, long startValue, int limit);

    /**
     * Inserts documents (and the edges of the node documents), all or nothing.
     *
     * @return {@code false} if one of them already exists (or on a transient conflict)
     */
    protected abstract boolean insert(Collection<?> collection, List<? extends Document> docs, List<ChildLink> links);

    /**
     * Updates the given properties of a document (a {@code null} value removes the property) and sets its mod count,
     * if its mod count is the given one, atomically.
     *
     * @param modCount the expected mod count, {@code null} for none
     * @return {@code false} if the document was not updated: changed or removed in the meantime, or transient conflict
     */
    protected abstract boolean update(Collection<?> collection, String id, Long modCount, Map<String, Object> changes,
            long newModCount);

    /**
     * Whether the backend applies updates in the database ({@link #modify}, {@link #findAndModify}), like the MongoDB
     * store, instead of in Java ({@link #update}).
     */
    protected boolean modifiesInDatabase() {
        return false;
    }

    /**
     * Applies an update in the database, atomically, if the document has the given mod count and meets the update's
     * conditions, and increments its mod count.
     *
     * @return {@code true} if the document was updated
     */
    protected boolean modify(Collection<?> collection, UpdateOp update, long modCount) {
        throw new UnsupportedOperationException();
    }

    /**
     * Applies an update in the database, atomically, if the document exists and meets the update's conditions, and
     * increments its mod count (MongoDB's {@code findOneAndUpdate}).
     *
     * @return the document before the update, {@code null} if none matched
     */
    protected <T extends Document> T findAndModify(Collection<T> collection, UpdateOp update) {
        throw new UnsupportedOperationException();
    }

    /** Deletes documents (and their edges). */
    protected abstract void delete(Collection<?> collection, List<String> ids);

    /** Deletes the documents whose {@code _modified} value is the given one; returns their count. */
    protected abstract int deleteIfModified(Collection<?> collection, Map<String, Long> toRemove);

    /** Deletes the documents with {@code startValue < property < endValue}; returns their count. */
    protected abstract int deleteRange(Collection<?> collection, String property, long startValue, long endValue);

    /** The mod count and modified time of the given node documents, if they exist (-1 for missing values). */
    protected abstract Map<String, ModificationStamp> getModStamps(List<String> ids);

    /** Closes the database connections. */
    protected abstract void close();

    // ----- values -----

    /** Escapes the strings a backend can't store as is: lone surrogates (not UTF-8), and strings starting with NUL. */
    protected static Object encode(Object value) {
        if (value instanceof String s && (s.startsWith("\u0000") || hasLoneSurrogate(s))) {
            var sb = new StringBuilder(ESCAPED_PREFIX);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    sb.append("\\\\");
                } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(++i));
                } else if (Character.isSurrogate(c)) {
                    sb.append("\\u%04x".formatted((int) c));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return value;
    }

    protected static Object decode(Object value) {
        if (value instanceof String s && s.startsWith(ESCAPED_PREFIX)) {
            var sb = new StringBuilder(s.length());
            for (int i = ESCAPED_PREFIX.length(); i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && s.charAt(i + 1) == 'u') {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                } else if (c == '\\') {
                    sb.append(s.charAt(++i));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return value;
    }

    public static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Ids are stored as is (they are compared): they must be valid UTF-8, and not too long. */
    public static boolean isValidId(String id) {
        return id.length() <= MAX_ID_BYTES && !hasLoneSurrogate(id) && id.getBytes(UTF_8).length <= MAX_ID_BYTES;
    }

    /** The child links of the node documents among the given ones. */
    protected static List<ChildLink> links(Collection<?> collection, List<? extends Document> docs) {
        if (collection != Collection.NODES) {
            return List.of();
        }
        return docs.stream().map(ChildLink::of).filter(Objects::nonNull).toList();
    }

    // ----- reads -----

    @Override
    public <T extends Document> T find(Collection<T> collection, String key) {
        return find(collection, key, true, -1);
    }

    @Override
    public <T extends Document> T find(Collection<T> collection, String key, int maxCacheAge) {
        return find(collection, key, false, maxCacheAge);
    }

    /** Like the MongoDB store: node documents from the cache if allowed, loaded under the document's lock. */
    @SuppressWarnings("unchecked")
    protected <T extends Document> T find(Collection<T> collection, String key, boolean preferCached,
            int maxCacheAge) {
        if (collection != Collection.NODES) {
            return isValidId(key) ? findUncached(collection, key) : null;
        }
        boolean useCache = preferCached || maxCacheAge > 0;
        var doc = useCache ? getCached(key, preferCached, maxCacheAge) : null;
        if (doc == null) {
            var lock = nodeLocks.acquire(key);
            try {
                // another thread may have loaded it in the meantime
                doc = useCache ? getCached(key, preferCached, maxCacheAge) : null;
                if (doc == null) {
                    var loaded = isValidId(key) ? (NodeDocument) findUncached(collection, key) : null;
                    nodesCache.invalidate(key);
                    doc = cacheGet(key, loaded == null ? NodeDocument.NULL : loaded);
                }
            } finally {
                lock.unlock();
            }
        }
        return doc == NodeDocument.NULL ? null : (T) doc;
    }

    /** The cached document (maybe {@link NodeDocument#NULL}), if recent enough. */
    protected NodeDocument getCached(String key, boolean preferCached, int maxCacheAge) {
        var doc = nodesCache.getIfPresent(key);
        if (doc != null && (preferCached || Revision.getCurrentTimestamp() - doc.getCreated() < maxCacheAge)) {
            return doc;
        }
        return null;
    }

    @Override
    public @NotNull <T extends Document> List<T> query(Collection<T> collection, String fromKey, String toKey,
            int limit) {
        return query(collection, fromKey, toKey, null, 0, limit);
    }

    /**
     * Documents with {@code fromKey < id < toKey}, ordered by id, and if {@code indexedProperty} is not null with
     * {@code indexedProperty >= startValue} (booleans counting as 0 and 1, missing values excluded, like MongoDB).
     */
    @Override
    @SuppressWarnings("unchecked")
    public @NotNull <T extends Document> List<T> query(Collection<T> collection, String fromKey, String toKey,
            String indexedProperty, long startValue, int limit) {
        CacheChangesTracker tracker = null;
        if (collection == Collection.NODES && Utils.getParentIdFromLowerLimit(fromKey) != null) {
            // children query: cache the results, unless they changed in the meantime
            tracker = nodesCache.registerTracker(fromKey, toKey);
        }
        try {
            var docs = queryUncached(collection, fromKey, toKey, indexedProperty, startValue, limit);
            if (tracker != null) {
                nodesCache.putNonConflictingDocs(tracker, (List<NodeDocument>) docs);
            }
            return docs;
        } finally {
            if (tracker != null) {
                tracker.close();
            }
        }
    }

    // ----- removals -----

    @Override
    public <T extends Document> void remove(Collection<T> collection, String key) {
        remove(collection, List.of(key));
    }

    @Override
    public <T extends Document> void remove(Collection<T> collection, List<String> keys) {
        var valid = keys.stream().filter(GraphDocumentStore::isValidId).toList();
        if (!valid.isEmpty()) {
            delete(collection, valid);
        }
        invalidate(collection, keys);
    }

    /** Removes the documents whose {@code _modified} value is the given one. */
    @Override
    public <T extends Document> int remove(Collection<T> collection, Map<String, Long> toRemove) {
        var valid = new HashMap<String, Long>();
        toRemove.forEach((k, v) -> {
            if (isValidId(k)) {
                valid.put(k, v);
            }
        });
        int num = valid.isEmpty() ? 0 : deleteIfModified(collection, valid);
        invalidate(collection, toRemove.keySet());
        return num;
    }

    /** Removes the documents with {@code startValue < indexedProperty < endValue}. */
    @Override
    public <T extends Document> int remove(Collection<T> collection, String indexedProperty, long startValue,
            long endValue) throws DocumentStoreException {
        int num = deleteRange(collection, indexedProperty, startValue, endValue);
        if (collection == Collection.NODES) {
            invalidateCache(); // the removed ids are not known
        }
        return num;
    }

    /** Invalidates removed documents, under their locks (a concurrent load may be caching them). */
    protected void invalidate(Collection<?> collection, Iterable<String> keys) {
        if (collection == Collection.NODES) {
            for (var key : keys) {
                var lock = nodeLocks.acquire(key);
                try {
                    nodesCache.invalidate(key);
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    // ----- creation and updates -----

    /**
     * Creates all the documents, or none of them if one already exists.
     */
    @Override
    public <T extends Document> boolean create(Collection<T> collection, List<UpdateOp> updateOps) {
        var docs = new ArrayList<T>(updateOps.size());
        for (var op : updateOps) {
            assertUnconditional(op);
            if (!isValidId(op.getId())) {
                LOG.debug("Create failed, invalid id: {}", op.getId());
                return false;
            }
            T doc = collection.newDocument(this);
            applyChanges(doc, op);
            doc.put(Document.MOD_COUNT, 1L);
            docs.add(doc);
        }
        LOG.trace("CREATE {} {}", collection, updateOps);
        if (!insert(collection, docs, links(collection, docs))) {
            return false;
        }
        if (collection == Collection.NODES) {
            docs.forEach(doc -> nodesCache.putIfAbsent((NodeDocument) doc));
        }
        return true;
    }

    @Override
    public @Nullable <T extends Document> T createOrUpdate(Collection<T> collection, UpdateOp update) {
        assertUnconditional(update);
        return internalCreateOrUpdate(collection, update, false);
    }

    @Override
    public <T extends Document> List<T> createOrUpdate(Collection<T> collection, List<UpdateOp> updateOps) {
        var result = new ArrayList<T>(updateOps.size());
        for (var op : updateOps) {
            result.add(createOrUpdate(collection, op));
        }
        return result;
    }

    @Override
    public <T extends Document> T findAndUpdate(Collection<T> collection, UpdateOp update) {
        return internalCreateOrUpdate(collection, update, true);
    }

    /**
     * Read (from the cache first for node documents), apply in Java, conditional write of the changed properties on
     * the mod count, retry from the database on conflict.
     *
     * @return the old document, or {@code null} if it didn't exist or if the conditions were not met
     */
    @SuppressWarnings("unchecked")
    protected <T extends Document> T internalCreateOrUpdate(Collection<T> collection, UpdateOp update,
            boolean checkConditions) {
        var id = update.getId();
        if (!isValidId(id)) {
            throw new DocumentStoreException("Invalid id: " + id);
        }
        if (modifiesInDatabase()) {
            return modifyInDatabase(collection, update, checkConditions);
        }
        var nodes = collection == Collection.NODES;
        var lock = nodes ? nodeLocks.acquire(id) : null;
        try {
            // the cached document is only a starting point: the conditional write checks its mod count
            boolean fromCache = false;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                T oldDoc = null;
                if (nodes && attempt == 1) {
                    var cached = nodesCache.getIfPresent(id);
                    if (cached != null && cached != NodeDocument.NULL) {
                        oldDoc = (T) cached;
                        fromCache = true;
                    }
                }
                if (oldDoc == null) {
                    oldDoc = findUncached(collection, id);
                    fromCache = false;
                }
                T doc = collection.newDocument(this);
                if (oldDoc == null) {
                    if (!update.isNew()) {
                        if (nodes) {
                            nodesCache.invalidate(id);
                        }
                        return null;
                    }
                } else {
                    oldDoc.deepCopy(doc);
                }
                if (checkConditions && !checkConditions(doc, update.getConditions())) {
                    if (fromCache) {
                        continue; // check again against the database
                    }
                    return null;
                }
                applyChanges(doc, update);
                if (oldDoc == null) {
                    doc.put(Document.MOD_COUNT, 1L);
                    if (insert(collection, List.of(doc), links(collection, List.of(doc)))) {
                        if (nodes) {
                            nodesCache.putIfAbsent((NodeDocument) doc);
                        }
                        return null;
                    }
                } else {
                    var modCount = oldDoc.getModCount();
                    long newModCount = modCount == null ? 1L : modCount + 1;
                    if (update(collection, id, modCount, changes(update, doc), newModCount)) {
                        if (nodes) {
                            doc.put(Document.MOD_COUNT, newModCount);
                            doc.seal();
                            nodesCache.put((NodeDocument) doc);
                        }
                        return oldDoc;
                    }
                }
                LOG.debug("Concurrent write of {}, retrying ({})", id, attempt);
                if (nodes) {
                    nodesCache.invalidate(id);
                }
            }
            throw new DocumentStoreException("Too many concurrent updates of: " + id);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /**
     * Like the MongoDB store: the update is applied by the database, so a concurrent change of the document doesn't
     * send it back here. A cached node document first allows an update conditional on its mod count (the conditions
     * are checked against it); otherwise, or if it changed meanwhile, a find-and-modify returns the document before the
     * update. A missing document is inserted (only by {@code createOrUpdate} of a new one); if it was inserted
     * concurrently, it is then updated.
     *
     * @return the old document, or {@code null} if it didn't exist or if the conditions were not met
     */
    @SuppressWarnings("unchecked")
    protected <T extends Document> T modifyInDatabase(Collection<T> collection, UpdateOp update,
            boolean checkConditions) {
        var id = update.getId();
        var nodes = collection == Collection.NODES;
        var lock = nodes ? nodeLocks.acquire(id) : null;
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                if (nodes && attempt == 1) {
                    var cached = nodesCache.getIfPresent(id);
                    if (cached != null && cached != NodeDocument.NULL && cached.getModCount() != null
                            && (!checkConditions || checkConditions(cached, update.getConditions()))
                            && modify(collection, update, cached.getModCount())) {
                        cacheModified(cached, update);
                        return (T) cached;
                    }
                }
                T oldDoc = findAndModify(collection, update);
                if (oldDoc != null) {
                    if (nodes) {
                        cacheModified((NodeDocument) oldDoc, update);
                    }
                    return oldDoc;
                }
                if (checkConditions || !update.isNew()) {
                    // missing, or the conditions were not met
                    if (nodes) {
                        nodesCache.invalidate(id);
                    }
                    return null;
                }
                T doc = collection.newDocument(this);
                applyChanges(doc, update);
                doc.put(Document.MOD_COUNT, 1L);
                if (insert(collection, List.of(doc), links(collection, List.of(doc)))) {
                    if (nodes) {
                        nodesCache.putIfAbsent((NodeDocument) doc);
                    }
                    return null;
                }
                LOG.debug("Concurrent insert of {}, updating it ({})", id, attempt);
                if (nodes) {
                    nodesCache.invalidate(id);
                }
            }
            throw new DocumentStoreException("Too many concurrent updates of: " + id);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /** Caches a node document as an update made it in the database. */
    protected void cacheModified(NodeDocument oldDoc, UpdateOp update) {
        var doc = Collection.NODES.newDocument(this);
        oldDoc.deepCopy(doc);
        applyChanges(doc, update);
        var modCount = oldDoc.getModCount();
        doc.put(Document.MOD_COUNT, modCount == null ? 1L : modCount + 1);
        doc.seal();
        nodesCache.put(doc);
    }

    /** The new values of the properties changed by an update ({@code null} for removed ones). */
    protected static Map<String, Object> changes(UpdateOp update, Document doc) {
        var names = new LinkedHashSet<String>();
        for (var key : update.getChanges().keySet()) {
            names.add(key.getName());
        }
        names.remove(Document.ID);
        names.remove(Document.MOD_COUNT);
        var changes = new HashMap<String, Object>();
        for (var name : names) {
            changes.put(name, doc.get(name));
        }
        return changes;
    }

    // ----- cache -----

    @Override
    public CacheInvalidationStats invalidateCache() {
        int count = 0;
        for (var key : nodesCache.keys()) {
            nodesCache.invalidate(key.toString());
            count++;
        }
        return new InvalidationStats(count, 0, 0);
    }

    /**
     * Invalidates the cached documents among the given ones whose mod count or modified time changed in the database
     * (called by DocumentNodeStore with the documents changed by other cluster nodes).
     */
    @Override
    public CacheInvalidationStats invalidateCache(Iterable<String> keys) {
        var cached = new ArrayList<String>();
        for (var key : keys) {
            if (nodesCache.getIfPresent(key) != null) {
                cached.add(key);
            }
        }
        int invalidated = 0;
        int queries = 0;
        for (int i = 0; i < cached.size(); i += 1000) {
            var batch = cached.subList(i, Math.min(i + 1000, cached.size()));
            var stamps = getModStamps(batch);
            queries++;
            invalidated += nodesCache.invalidateOutdated(stamps);
            for (var key : batch) {
                if (!stamps.containsKey(key)) {
                    nodesCache.invalidate(key); // removed
                    invalidated++;
                }
            }
        }
        return new InvalidationStats(invalidated, cached.size() - invalidated, queries);
    }

    protected record InvalidationStats(int invalidated, int upToDate, int queries)
            implements CacheInvalidationStats {

        @Override
        public String summaryReport() {
            return toString();
        }
    }

    /** {@code NodeDocumentCache.get(String, loader)}: the loader is a {@code Function} in Oak 2.x, a {@code Callable} in 1.x. */
    protected static final Method CACHE_GET = Arrays.stream(NodeDocumentCache.class.getMethods())
                                                    .filter(m -> m.getName().equals("get")
                                                            && m.getParameterCount() == 2
                                                            && m.getParameterTypes()[0] == String.class)
                                                    .findFirst()
                                                    .orElseThrow();

    protected static final boolean CACHE_GET_FUNCTION = CACHE_GET.getParameterTypes()[1] == Function.class;

    /** Caches a loaded document (or {@link NodeDocument#NULL}) like {@code get} does, change trackers included. */
    protected NodeDocument cacheGet(String key, NodeDocument value) {
        try {
            Object loader = CACHE_GET_FUNCTION ? (Function<String, NodeDocument>) k -> value
                    : (Callable<NodeDocument>) () -> value;
            return (NodeDocument) CACHE_GET.invoke(nodesCache, key, loader);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new DocumentStoreException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new DocumentStoreException(e);
        }
    }

    @Override
    public <T extends Document> void invalidateCache(Collection<T> collection, String key) {
        if (collection == Collection.NODES) {
            nodesCache.invalidate(key);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Document> T getIfCached(Collection<T> collection, String key) {
        if (collection != Collection.NODES) {
            return null;
        }
        var doc = nodesCache.getIfPresent(key);
        return doc == NodeDocument.NULL ? null : (T) doc;
    }

    /**
     * Raw type: {@code Iterable<AbstractCacheStats>} in Oak 2.x, {@code Iterable<CacheStats>} in Oak 1.x; the same
     * method for the JVM, so that one build runs on both (Sling Starter 14 has Oak 1.90).
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Iterable getCacheStats() {
        return nodesCache.getCacheStats();
    }

    // ----- misc -----

    @Override
    public void dispose() {
        disposed = true;
        try {
            nodesCache.close();
        } catch (IOException e) {
            LOG.warn("Failed to close the cache", e);
        }
        close();
    }

    @Override
    public void setReadWriteMode(String readWriteMode) {
        // not applicable
    }

    @Override
    public long determineServerTimeDifferenceMillis() {
        return 0;
    }

}
