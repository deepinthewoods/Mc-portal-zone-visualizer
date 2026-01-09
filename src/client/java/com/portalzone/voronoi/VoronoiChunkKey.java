package com.portalzone.voronoi;

import java.util.Objects;

/**
 * Immutable key for identifying a unique Voronoi chunk in the cache.
 *
 * <p>A chunk is uniquely identified by its spatial position (chunkX, chunkY, chunkZ),
 * level of detail (lodLevel), and the portal configuration hash (portalConfigHash).
 *
 * <p>This class is designed to be used as a key in HashMap/ConcurrentHashMap,
 * with properly implemented equals() and hashCode() methods for correct cache behavior.
 *
 * <p>Chunks are invalidated when:
 * <ul>
 *   <li>The portal configuration changes (different portalConfigHash)</li>
 *   <li>The chunk is evicted from cache (LRU policy)</li>
 * </ul>
 *
 * <p>All fields are final to ensure immutability and thread safety.
 */
public final class VoronoiChunkKey {

    /**
     * Chunk X coordinate (world space divided by chunk size)
     */
    public final int chunkX;

    /**
     * Chunk Y coordinate (world space divided by chunk size)
     */
    public final int chunkY;

    /**
     * Chunk Z coordinate (world space divided by chunk size)
     */
    public final int chunkZ;

    /**
     * Level of Detail (0, 1, or 2 based on LOD_SPACING array)
     * <ul>
     *   <li>LOD 0: spacing = 1 (highest detail, closest to player)</li>
     *   <li>LOD 1: spacing = 4 (medium detail)</li>
     *   <li>LOD 2: spacing = 16 (lowest detail, farthest from player)</li>
     * </ul>
     */
    public final int lodLevel;

    /**
     * Hash of the portal configuration (positions, colors, hidden states, settings).
     * Calculated by {@link PortalConfigHasher#calculatePortalConfigHash}.
     *
     * <p>When this hash changes, all cached chunks with the old hash are invalidated.
     */
    public final long portalConfigHash;

    // Cached hash code for performance (since this object is immutable)
    private final int hashCode;

    /**
     * Creates a new VoronoiChunkKey.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkY Chunk Y coordinate
     * @param chunkZ Chunk Z coordinate
     * @param lodLevel Level of detail (0-2)
     * @param portalConfigHash Hash of portal configuration
     */
    public VoronoiChunkKey(int chunkX, int chunkY, int chunkZ, int lodLevel, long portalConfigHash) {
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;
        this.lodLevel = lodLevel;
        this.portalConfigHash = portalConfigHash;

        // Pre-calculate hash code for performance
        this.hashCode = calculateHashCode();
    }

    /**
     * Calculates the hash code for this chunk key.
     *
     * <p>Uses a combination of all fields to ensure good distribution
     * in HashMap/ConcurrentHashMap.
     *
     * @return Hash code value
     */
    private int calculateHashCode() {
        int result = 1;
        result = 31 * result + chunkX;
        result = 31 * result + chunkY;
        result = 31 * result + chunkZ;
        result = 31 * result + lodLevel;
        result = 31 * result + Long.hashCode(portalConfigHash);
        return result;
    }

    /**
     * Returns the pre-calculated hash code.
     *
     * <p>Since this object is immutable, the hash code is calculated once
     * in the constructor and cached for performance.
     *
     * @return Hash code value
     */
    @Override
    public int hashCode() {
        return hashCode;
    }

    /**
     * Checks equality with another object.
     *
     * <p>Two VoronoiChunkKey objects are equal if and only if all their fields
     * (chunkX, chunkY, chunkZ, lodLevel, portalConfigHash) are equal.
     *
     * @param obj Object to compare with
     * @return true if objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        VoronoiChunkKey other = (VoronoiChunkKey) obj;
        return chunkX == other.chunkX
            && chunkY == other.chunkY
            && chunkZ == other.chunkZ
            && lodLevel == other.lodLevel
            && portalConfigHash == other.portalConfigHash;
    }

    /**
     * Returns a string representation for debugging.
     *
     * <p>Format: VoronoiChunkKey[x=1, y=2, z=3, lod=0, hash=1234567890abcdef]
     *
     * @return String representation
     */
    @Override
    public String toString() {
        return String.format("VoronoiChunkKey[x=%d, y=%d, z=%d, lod=%d, hash=%016x]",
                           chunkX, chunkY, chunkZ, lodLevel, portalConfigHash);
    }
}
