package com.portalzone.voronoi;

import java.util.Objects;

/**
 * Represents a chunk coordinate in 3D space.
 * Stores chunk coordinates (not block coordinates).
 * Immutable and suitable for use as a HashMap key.
 */
public class ChunkCoord {
    /** Chunk X coordinate */
    public final int x;

    /** Chunk Y coordinate */
    public final int y;

    /** Chunk Z coordinate */
    public final int z;

    /**
     * Creates a new chunk coordinate.
     *
     * @param x Chunk X coordinate (not block coordinate)
     * @param y Chunk Y coordinate (not block coordinate)
     * @param z Chunk Z coordinate (not block coordinate)
     */
    public ChunkCoord(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ChunkCoord other = (ChunkCoord) obj;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "ChunkCoord{x=" + x + ", y=" + y + ", z=" + z + "}";
    }
}
