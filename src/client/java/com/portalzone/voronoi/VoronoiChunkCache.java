package com.portalzone.voronoi;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe cache for Voronoi chunk calculations.
 *
 * <p>This cache stores calculated chunks identified by {@link VoronoiChunkKey}
 * and implements LRU (Least Recently Used) eviction when the cache exceeds
 * its maximum size.
 *
 * <p>Thread Safety: This class is designed for concurrent access from:
 * <ul>
 *   <li>Worker thread: Calculates and puts new chunks</li>
 *   <li>Main render thread: Gets chunks for rendering</li>
 * </ul>
 *
 * <p>Uses ConcurrentHashMap for the backing store and atomic operations
 * for statistics tracking. No external synchronization required.
 *
 * <p>Cache Invalidation:
 * <ul>
 *   <li>Portal configuration change: All chunks with different hash are invalid</li>
 *   <li>Dimension change: Clear all chunks</li>
 *   <li>Manual clear: invalidateAll()</li>
 * </ul>
 */
public class VoronoiChunkCache {

    /**
     * Default maximum number of chunks to cache.
     * At ~10KB per chunk average, this is roughly 80MB of cache.
     */
    private static final int DEFAULT_MAX_CACHE_SIZE = 8192;
    private static final long EVICTION_LOG_COOLDOWN_MS = 5000L;

    /**
     * Backing store for cached chunks.
     * ConcurrentHashMap provides thread-safe access without external locking.
     */
    private final ConcurrentHashMap<VoronoiChunkKey, ChunkData> cache;

    /**
     * Maximum number of chunks to cache before triggering LRU eviction.
     */
    private final int maxCacheSize;

    /**
     * Total number of cache hits (successful get operations).
     * Thread-safe atomic counter.
     */
    private final AtomicLong cacheHits;

    /**
     * Total number of cache misses (get operations returning null).
     * Thread-safe atomic counter.
     */
    private final AtomicLong cacheMisses;

    /**
     * Total number of chunk evictions (LRU removals).
     * Thread-safe atomic counter.
     */
    private final AtomicLong evictions;
    private final AtomicLong lastEvictionLogMs;

    /**
     * Creates a cache with the default maximum size.
     */
    public VoronoiChunkCache() {
        this(DEFAULT_MAX_CACHE_SIZE);
    }

    /**
     * Creates a cache with a specified maximum size.
     *
     * @param maxCacheSize Maximum number of chunks to cache (must be positive)
     * @throws IllegalArgumentException if maxCacheSize is not positive
     */
    public VoronoiChunkCache(int maxCacheSize) {
        if (maxCacheSize <= 0) {
            throw new IllegalArgumentException("maxCacheSize must be positive, got: " + maxCacheSize);
        }
        this.maxCacheSize = maxCacheSize;
        this.cache = new ConcurrentHashMap<>(maxCacheSize);
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
        this.evictions = new AtomicLong(0);
        this.lastEvictionLogMs = new AtomicLong(0);
    }

    /**
     * Gets a chunk from the cache.
     *
     * <p>If the chunk exists, updates its timestamp (for LRU) and increments
     * the cache hit counter. If not found, increments the cache miss counter.
     *
     * <p>Thread-safe: Can be called concurrently from multiple threads.
     *
     * @param key The chunk key to look up
     * @return ChunkData if found, null otherwise
     */
    public ChunkData get(VoronoiChunkKey key) {
        ChunkData data = cache.get(key);

        if (data != null) {
            // Update timestamp for LRU tracking
            data.updateTimestamp();
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }

        return data;
    }

    /**
     * Puts a chunk into the cache.
     *
     * <p>If the cache exceeds maxCacheSize after insertion, triggers LRU
     * eviction to remove the oldest (least recently used) chunks.
     *
     * <p>Thread-safe: Can be called concurrently from multiple threads.
     * However, if multiple threads trigger eviction simultaneously, they
     * will each perform eviction independently (safe but potentially redundant).
     *
     * @param key The chunk key
     * @param data The chunk data (must not be null)
     * @throws NullPointerException if key or data is null
     */
    public void put(VoronoiChunkKey key, ChunkData data) {
        if (key == null || data == null) {
            throw new NullPointerException("key and data must not be null");
        }

        cache.put(key, data);

        // Check if eviction is needed
        int currentSize = cache.size();
        if (currentSize > maxCacheSize) {
            evictLRU();
        }
    }

    /**
     * Invalidates all chunks that don't match the specified portal configuration hash.
     *
     * <p>This is called when the portal configuration changes (portal added/removed/moved/hidden).
     * All chunks with a different portalConfigHash become invalid and are removed.
     *
     * <p>Thread-safe: Uses ConcurrentHashMap's concurrent removal operations.
     *
     * @param newPortalHash The new portal configuration hash
     * @return Number of chunks invalidated
     */
    public int invalidateAll(long newPortalHash) {
        int removed = 0;

        // Remove all entries with different portal hash
        for (Map.Entry<VoronoiChunkKey, ChunkData> entry : cache.entrySet()) {
            VoronoiChunkKey key = entry.getKey();
            if (key.portalConfigHash != newPortalHash) {
                if (cache.remove(key) != null) {
                    removed++;
                }
            }
        }

        if (removed > 0) {
            System.out.println("[VoronoiChunkCache] Invalidated " + removed +
                             " chunks due to portal config change (new hash: " +
                             Long.toHexString(newPortalHash) + ")");
        }

        return removed;
    }

    /**
     * Invalidates all chunks in the cache.
     *
     * <p>This is called when:
     * <ul>
     *   <li>Dimension changes</li>
     *   <li>Major configuration changes</li>
     *   <li>Manual cache clear requested</li>
     * </ul>
     *
     * <p>Thread-safe: ConcurrentHashMap.clear() is atomic.
     */
    public void invalidateAll() {
        int previousSize = cache.size();
        cache.clear();

        if (previousSize > 0) {
            System.out.println("[VoronoiChunkCache] Invalidated all " + previousSize + " chunks");
        }
    }

    /**
     * Gets the current number of cached chunks.
     *
     * <p>Thread-safe: ConcurrentHashMap.size() is thread-safe.
     *
     * @return Current cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Gets the maximum cache size.
     *
     * @return Maximum number of chunks this cache can hold before eviction
     */
    public int getMaxCacheSize() {
        return maxCacheSize;
    }

    /**
     * Gets the total number of cache hits since creation.
     *
     * @return Cache hit count
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Gets the total number of cache misses since creation.
     *
     * @return Cache miss count
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Gets the total number of evictions since creation.
     *
     * @return Eviction count
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * Calculates the cache hit rate as a percentage.
     *
     * @return Hit rate (0.0 to 100.0), or 0.0 if no accesses yet
     */
    public double getHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;

        if (total == 0) {
            return 0.0;
        }

        return (hits * 100.0) / total;
    }

    /**
     * Estimates the total memory usage of cached chunks.
     *
     * <p>This iterates over all cached chunks and sums their estimated memory.
     * It's a rough estimate for monitoring purposes.
     *
     * @return Estimated total memory in bytes
     */
    public long estimateTotalMemoryBytes() {
        long total = 0;
        for (ChunkData data : cache.values()) {
            total += data.getEstimatedMemoryBytes();
        }
        return total;
    }

    /**
     * Resets all statistics counters to zero.
     *
     * <p>Useful for benchmarking or when starting a new profiling session.
     */
    public void resetStatistics() {
        cacheHits.set(0);
        cacheMisses.set(0);
        evictions.set(0);
    }

    /**
     * Evicts the least recently used (LRU) chunks to bring cache size down to maxCacheSize.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Sort all chunks by timestamp (oldest first)</li>
     *   <li>Remove the oldest chunks until size <= maxCacheSize</li>
     * </ol>
     *
     * <p>Thread-safety note: This method is not synchronized, so if multiple
     * threads trigger eviction simultaneously, they may each perform redundant
     * evictions. This is safe (no corruption) but potentially removes more chunks
     * than necessary. In practice, this is rare and acceptable.
     */
    private void evictLRU() {
        int currentSize = cache.size();
        int toRemove = currentSize - maxCacheSize;

        if (toRemove <= 0) {
            return;
        }

        boolean logEviction = shouldLogEviction();
        if (logEviction) {
            System.out.println("[VoronoiChunkCache] Cache size (" + currentSize +
                ") exceeds max (" + maxCacheSize + "), evicting " +
                toRemove + " oldest chunks");
        }

        // Find the N oldest entries by timestamp
        cache.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().getTimestamp()))
            .limit(toRemove)
            .forEach(entry -> {
                if (cache.remove(entry.getKey()) != null) {
                    evictions.incrementAndGet();
                }
            });

        if (logEviction) {
            System.out.println("[VoronoiChunkCache] Eviction complete, new size: " + cache.size());
        }
    }

    private boolean shouldLogEviction() {
        long now = System.currentTimeMillis();
        long last = lastEvictionLogMs.get();
        if (now - last < EVICTION_LOG_COOLDOWN_MS) {
            return false;
        }
        return lastEvictionLogMs.compareAndSet(last, now);
    }

    /**
     * Returns cache statistics as a formatted string.
     *
     * <p>Useful for logging and debugging.
     *
     * @return Statistics string
     */
    public String getStatistics() {
        return String.format(
            "[VoronoiChunkCache] size=%d/%d, hits=%d, misses=%d, hit_rate=%.1f%%, evictions=%d, memory=%.1f MB",
            size(),
            maxCacheSize,
            getCacheHits(),
            getCacheMisses(),
            getHitRate(),
            getEvictions(),
            estimateTotalMemoryBytes() / (1024.0 * 1024.0)
        );
    }

    /**
     * Returns a string representation for debugging.
     *
     * @return String representation
     */
    @Override
    public String toString() {
        return getStatistics();
    }
}
