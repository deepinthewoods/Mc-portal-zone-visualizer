package com.portalzone.voronoi;

import java.util.Map;

/**
 * Immutable data structure representing a cached Voronoi chunk.
 *
 * <p>Contains the calculated edge segments for a specific chunk, along with
 * metadata for cache management (LRU eviction, memory monitoring).
 *
 * <p>Each chunk contains a map of edge buckets organized by portal pairs,
 * which is the output of the Voronoi calculation for this spatial region.
 *
 * <p>Thread-safety: This class is immutable after construction. The bucketMap
 * should not be modified after the ChunkData is created.
 */
public final class ChunkData {

    /**
     * Map of edge buckets for this chunk, organized by (group, portal1, portal2).
     * This is the core data structure containing all edge segments in this chunk.
     */
    private final Map<VoronoiCalculator.BucketKey, VoronoiCalculator.EdgeBucket> bucketMap;

    /**
     * Timestamp when this chunk was last accessed (for LRU eviction).
     * Uses System.currentTimeMillis() for millisecond precision.
     * Updated on every cache access via VoronoiChunkCache.get().
     */
    private volatile long timestamp;

    /**
     * Estimated memory footprint of this chunk in bytes.
     * Used for cache size monitoring and debugging.
     * This is a rough estimate based on bucket count and segment count.
     */
    private final int estimatedMemoryBytes;

    /**
     * Creates a new ChunkData with the given bucket map.
     *
     * <p>The timestamp is automatically set to the current time.
     * Memory footprint is estimated based on the bucket map contents.
     *
     * @param bucketMap Map of edge buckets for this chunk (must not be null)
     * @throws NullPointerException if bucketMap is null
     */
    public ChunkData(Map<VoronoiCalculator.BucketKey, VoronoiCalculator.EdgeBucket> bucketMap) {
        if (bucketMap == null) {
            throw new NullPointerException("bucketMap cannot be null");
        }
        this.bucketMap = bucketMap;
        this.timestamp = System.currentTimeMillis();
        this.estimatedMemoryBytes = estimateMemoryFootprint();
    }

    /**
     * Gets the bucket map for this chunk.
     *
     * <p>Note: This returns the internal map. Callers should not modify it.
     * In a production system, this could return an unmodifiable view.
     *
     * @return Map of edge buckets
     */
    public Map<VoronoiCalculator.BucketKey, VoronoiCalculator.EdgeBucket> getBucketMap() {
        return bucketMap;
    }

    /**
     * Gets the last access timestamp for this chunk.
     *
     * <p>This timestamp is updated by VoronoiChunkCache.get() for LRU tracking.
     *
     * @return Timestamp in milliseconds (System.currentTimeMillis())
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Updates the timestamp to the current time.
     *
     * <p>Called by VoronoiChunkCache.get() when chunk is accessed.
     * Uses volatile field for thread-safe updates without locks.
     */
    public void updateTimestamp() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the estimated memory footprint of this chunk.
     *
     * @return Estimated memory in bytes
     */
    public int getEstimatedMemoryBytes() {
        return estimatedMemoryBytes;
    }

    /**
     * Estimates the memory footprint of this chunk based on its contents.
     *
     * <p>Calculation:
     * <ul>
     *   <li>Base overhead: 64 bytes per ChunkData object</li>
     *   <li>HashMap overhead: 48 bytes + 32 bytes per entry</li>
     *   <li>BucketKey: 24 bytes each</li>
     *   <li>EdgeBucket: 128 bytes base + 48 bytes per segment</li>
     *   <li>EdgeSegment: 48 bytes each (two Vec3 + spacing)</li>
     * </ul>
     *
     * <p>This is a rough estimate for monitoring purposes, not exact measurement.
     *
     * @return Estimated memory in bytes
     */
    private int estimateMemoryFootprint() {
        int bytes = 64; // Base ChunkData object overhead

        if (bucketMap.isEmpty()) {
            return bytes;
        }

        // HashMap overhead
        bytes += 48; // HashMap object
        bytes += bucketMap.size() * 32; // Entry objects

        // Count total segments across all buckets
        int totalSegments = 0;
        for (VoronoiCalculator.EdgeBucket bucket : bucketMap.values()) {
            totalSegments += bucket.segments.size();
        }

        // BucketKey objects (3 ints = ~24 bytes each)
        bytes += bucketMap.size() * 24;

        // EdgeBucket objects (base + segments)
        // Base EdgeBucket: ~128 bytes (lists, vectors, indices)
        bytes += bucketMap.size() * 128;

        // EdgeSegment objects (~48 bytes each: 2 Vec3 + int)
        bytes += totalSegments * 48;

        return bytes;
    }

    /**
     * Returns a string representation for debugging.
     *
     * <p>Format: ChunkData[buckets=X, segments=Y, memory=Z KB, age=W ms]
     *
     * @return String representation
     */
    @Override
    public String toString() {
        int totalSegments = bucketMap.values().stream()
            .mapToInt(bucket -> bucket.segments.size())
            .sum();

        long ageMs = System.currentTimeMillis() - timestamp;

        return String.format("ChunkData[buckets=%d, segments=%d, memory=%d KB, age=%d ms]",
                           bucketMap.size(),
                           totalSegments,
                           estimatedMemoryBytes / 1024,
                           ageMs);
    }
}
