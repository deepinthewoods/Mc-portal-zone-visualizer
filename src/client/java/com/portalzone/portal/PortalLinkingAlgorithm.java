package com.portalzone.portal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * Utility class that simulates Minecraft's portal linking algorithm.
 * This determines which portal in the destination dimension a source portal would link to.
 */
public class PortalLinkingAlgorithm {

    /**
     * Search radius for finding linked portals in the Nether dimension.
     * Minecraft uses 128 blocks in the Nether because coordinate scaling makes distances smaller.
     */
    public static final int SEARCH_RADIUS_NETHER = 128;

    /**
     * Search radius for finding linked portals in the Overworld dimension.
     * Minecraft uses 16 blocks in the Overworld due to the 8:1 coordinate ratio.
     */
    public static final int SEARCH_RADIUS_OVERWORLD = 16;

    /**
     * Find which portal a position would link to in the destination dimension.
     * This simulates Minecraft's portal linking behavior:
     * 1. Translates coordinates based on dimension scaling (8:1 Nether:Overworld)
     * 2. Searches for portals within a radius (128 in Nether, 16 in Overworld)
     * 3. Returns the nearest portal within the search radius
     * 4. Returns null if no portal exists in range (would create new portal)
     *
     * @param sourcePos The position in the source dimension
     * @param sourceDim The source dimension (where the portal is being entered from)
     * @param destinationPortals Collection of all portals in the destination dimension
     * @return The PortalInfo that would be linked to, or null if no portal in range
     */
    public static PortalInfo findLinkedPortal(
        Vec3 sourcePos,
        ResourceKey<Level> sourceDim,
        Collection<PortalInfo> destinationPortals
    ) {
        // Translate coordinates from source to destination dimension
        Vec3 translatedPos = translateCoordinates(sourcePos, sourceDim);

        // Determine the destination dimension
        ResourceKey<Level> destDim = (sourceDim == Level.NETHER) ? Level.OVERWORLD : Level.NETHER;

        // Get the search radius for the destination dimension
        int searchRadius = getSearchRadius(destDim);

        // Find the nearest portal within the search radius
        PortalInfo nearestPortal = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PortalInfo portal : destinationPortals) {
            // Calculate horizontal distance (Y coordinate is less important for portal linking)
            double distance = horizontalDistance(translatedPos, portal.getCenterPos());

            // Check if portal is within search radius and closer than current nearest
            if (distance <= searchRadius && distance < nearestDistance) {
                nearestDistance = distance;
                nearestPortal = portal;
            }
        }

        return nearestPortal;
    }

    /**
     * Get the search radius for a given dimension.
     * Minecraft uses different search radii based on the dimension's coordinate scaling.
     *
     * @param dimension The dimension to get the search radius for
     * @return 128 for Nether, 16 for Overworld
     */
    public static int getSearchRadius(ResourceKey<Level> dimension) {
        return (dimension == Level.NETHER) ? SEARCH_RADIUS_NETHER : SEARCH_RADIUS_OVERWORLD;
    }

    /**
     * Translate coordinates from one dimension to another.
     * Minecraft scales coordinates by 8:1 when traveling between Nether and Overworld.
     *
     * @param pos The position in the source dimension
     * @param fromDim The source dimension
     * @return The translated position in the destination dimension
     */
    public static Vec3 translateCoordinates(Vec3 pos, ResourceKey<Level> fromDim) {
        boolean isFromNether = (fromDim == Level.NETHER);

        // Nether to Overworld: multiply by 8
        // Overworld to Nether: divide by 8
        double scale = isFromNether ? 8.0 : 0.125;

        return new Vec3(
            pos.x * scale,
            pos.y,  // Y coordinate is not scaled
            pos.z * scale
        );
    }

    /**
     * Calculate the horizontal distance between two positions, ignoring Y coordinate.
     * Portal linking primarily considers horizontal distance since portals can be at different heights.
     *
     * @param a First position
     * @param b Second position
     * @return The horizontal distance between the two positions
     */
    public static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
