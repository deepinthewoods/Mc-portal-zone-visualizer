package com.portalzone.voronoi;

import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Calculates which chunk coordinates are required for rendering Voronoi borders
 * within a given distance from the player.
 */
public class ChunkBoundaryCalculator {

    /**
     * Determines all chunk coordinates that fall within maxDistance blocks of the player position.
     * This uses a bounding box approach to efficiently identify required chunks.
     *
     * @param playerPos    The player's world position
     * @param maxDistance  The maximum distance in blocks from the player
     * @param chunkSize    The size of each chunk in blocks (typically 128)
     * @return A set of chunk coordinates (no duplicates) covering the required region
     */
    public static Set<ChunkCoord> getRequiredChunks(Vec3 playerPos, int maxDistance, int chunkSize) {
        return getRequiredChunks(playerPos, maxDistance, chunkSize,
            Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Determines all chunk coordinates that fall within maxDistance blocks of the player position,
     * clamped to the provided Y bounds.
     *
     * @param playerPos    The player's world position
     * @param maxDistance  The maximum distance in blocks from the player
     * @param chunkSize    The size of each chunk in blocks (typically 128)
     * @param minY         Minimum Y bound to include (inclusive)
     * @param maxY         Maximum Y bound to include (inclusive)
     * @return A set of chunk coordinates (no duplicates) covering the required region
     */
    public static Set<ChunkCoord> getRequiredChunks(Vec3 playerPos, int maxDistance, int chunkSize,
                                                    int minY, int maxY) {
        Set<ChunkCoord> chunks = new HashSet<>();

        // Calculate the min and max block coordinates for the bounding box
        int minBlockX = (int) Math.floor(playerPos.x - maxDistance);
        int maxBlockX = (int) Math.floor(playerPos.x + maxDistance);
        int minBlockY = (int) Math.floor(playerPos.y - maxDistance);
        int maxBlockY = (int) Math.floor(playerPos.y + maxDistance);
        int minBlockZ = (int) Math.floor(playerPos.z - maxDistance);
        int maxBlockZ = (int) Math.floor(playerPos.z + maxDistance);

        // Clamp Y to world bounds to avoid unnecessary vertical chunk work
        minBlockY = Math.max(minBlockY, minY);
        maxBlockY = Math.min(maxBlockY, maxY);

        if (minBlockY > maxBlockY) {
            return chunks;
        }

        // Convert block coordinates to chunk coordinates using floor division
        int minChunkX = Math.floorDiv(minBlockX, chunkSize);
        int maxChunkX = Math.floorDiv(maxBlockX, chunkSize);
        int minChunkY = Math.floorDiv(minBlockY, chunkSize);
        int maxChunkY = Math.floorDiv(maxBlockY, chunkSize);
        int minChunkZ = Math.floorDiv(minBlockZ, chunkSize);
        int maxChunkZ = Math.floorDiv(maxBlockZ, chunkSize);

        // Iterate through all chunk coordinates in the bounding box
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cy = minChunkY; cy <= maxChunkY; cy++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    chunks.add(new ChunkCoord(cx, cy, cz));
                }
            }
        }

        return chunks;
    }

    /**
     * Converts a world position to the chunk coordinate that contains it.
     * Handles negative coordinates correctly using floor division.
     *
     * @param worldPos   The world position to convert
     * @param chunkSize  The size of each chunk in blocks
     * @return The chunk coordinate containing this world position
     */
    public static ChunkCoord worldPosToChunkCoord(Vec3 worldPos, int chunkSize) {
        int chunkX = Math.floorDiv((int) Math.floor(worldPos.x), chunkSize);
        int chunkY = Math.floorDiv((int) Math.floor(worldPos.y), chunkSize);
        int chunkZ = Math.floorDiv((int) Math.floor(worldPos.z), chunkSize);
        return new ChunkCoord(chunkX, chunkY, chunkZ);
    }

    /**
     * Calculates the world-space bounding box for a given chunk coordinate.
     *
     * @param coord      The chunk coordinate
     * @param chunkSize  The size of each chunk in blocks
     * @return An array [minX, maxX, minY, maxY, minZ, maxZ] representing the chunk's bounds in world space
     */
    public static int[] getChunkBounds(ChunkCoord coord, int chunkSize) {
        int minX = coord.x * chunkSize;
        int maxX = minX + chunkSize - 1;
        int minY = coord.y * chunkSize;
        int maxY = minY + chunkSize - 1;
        int minZ = coord.z * chunkSize;
        int maxZ = minZ + chunkSize - 1;

        return new int[] { minX, maxX, minY, maxY, minZ, maxZ };
    }
}
