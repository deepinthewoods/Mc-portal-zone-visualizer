package com.portalzone.voronoi;

import com.mojang.blaze3d.vertex.PoseStack;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import com.portalzone.render.PortalRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Calculates and renders 3D Voronoi cell borders for portal zones
 */
public class VoronoiCalculator {
    private static final VoronoiCalculator INSTANCE = new VoronoiCalculator();

    // Voronoi calculation parameters
    private static final int SAMPLE_SPACING = 4; // Sample every 4 blocks for performance
    private static final int LOCAL_RADIUS = 64; // Only calculate within 64 blocks of player

    // Cached Voronoi edges
    private final List<VoronoiEdge> cachedEdges = new ArrayList<>();
    private ResourceKey<Level> cachedDimension = null;

    private VoronoiCalculator() {
    }

    public static VoronoiCalculator getInstance() {
        return INSTANCE;
    }

    /**
     * Render the Voronoi borders
     */
    public void render(PoseStack matrices, MultiBufferSource bufferSource, Vec3 camPos, ResourceKey<Level> currentDim, Camera camera) {
        // Recalculate if portals have changed or dimension changed
        if (PortalManager.getInstance().hasPortalsChanged() || !currentDim.equals(cachedDimension)) {
            recalculateVoronoi(camPos, currentDim);
            PortalManager.getInstance().clearChangedFlag();
            cachedDimension = currentDim;
        }

        // Calculate normal from camera forward vector (pointing toward camera)
        var rot = camera.rotation();
        Quaternionf cameraRot = new Quaternionf(rot);
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(cameraRot);

        // Render cached edges with world coordinates (PoseStack is already camera-relative)
        for (VoronoiEdge edge : cachedEdges) {
            Vector3f color = edge.color;

            PortalRenderer.submitLine(matrices, bufferSource,
                color.x, color.y, color.z, 0.6f,
                0x00F000F0,
                edge.start.x, edge.start.y, edge.start.z,
                edge.end.x, edge.end.y, edge.end.z,
                forward);
        }
    }

    /**
     * Recalculate Voronoi borders
     */
    private void recalculateVoronoi(Vec3 playerPos, ResourceKey<Level> currentDim) {
        cachedEdges.clear();

        // Get portals from the OTHER dimension (the ones we would link to)
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        Set<PortalInfo> otherDimPortals = PortalManager.getInstance().getPortalsInDimension(otherDim);
        Set<PortalInfo> portalSource = otherDimPortals;
        boolean useTranslatedPositions = true;

        if (portalSource.size() < 2) {
            // Fallback to current dimension portals so borders still render
            portalSource = PortalManager.getInstance().getPortalsInDimension(currentDim);
            useTranslatedPositions = false;
        }

        if (portalSource.size() < 2) {
            // Need at least 2 portals to have borders
            return;
        }

        // Convert to list for easier indexing
        List<PortalInfo> portalList = new ArrayList<>(portalSource);

        // Sample points in 3D space around the player
        int minX = ((int) playerPos.x - LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;
        int maxX = ((int) playerPos.x + LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;
        int minY = Math.max(((int) playerPos.y - LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING, -64);
        int maxY = Math.min(((int) playerPos.y + LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING, 320);
        int minZ = ((int) playerPos.z - LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;
        int maxZ = ((int) playerPos.z + LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;

        // Create a 3D grid to store nearest portal for each sample point
        Map<BlockPos, PortalInfo> nearestPortalMap = new HashMap<>();

        // For each sample point, find the nearest portal (in translated coordinates)
        for (int x = minX; x <= maxX; x += SAMPLE_SPACING) {
            for (int y = minY; y <= maxY; y += SAMPLE_SPACING) {
                for (int z = minZ; z <= maxZ; z += SAMPLE_SPACING) {
                    Vec3 samplePoint = new Vec3(x, y, z);
                    PortalInfo nearest = findNearestPortal(samplePoint, portalList, useTranslatedPositions);

                    if (nearest != null) {
                        nearestPortalMap.put(new BlockPos(x, y, z), nearest);
                    }
                }
            }
        }

        // Find edges where the nearest portal changes
        for (int x = minX; x <= maxX; x += SAMPLE_SPACING) {
            for (int y = minY; y <= maxY; y += SAMPLE_SPACING) {
                for (int z = minZ; z <= maxZ; z += SAMPLE_SPACING) {
                    BlockPos pos = new BlockPos(x, y, z);
                    PortalInfo portal = nearestPortalMap.get(pos);

                    if (portal == null) continue;

                    // Check all 6 neighbors
                    checkAndAddEdge(nearestPortalMap, pos, pos.offset(SAMPLE_SPACING, 0, 0), portal);
                    checkAndAddEdge(nearestPortalMap, pos, pos.offset(0, SAMPLE_SPACING, 0), portal);
                    checkAndAddEdge(nearestPortalMap, pos, pos.offset(0, 0, SAMPLE_SPACING), portal);
                }
            }
        }
    }

    /**
     * Find the nearest portal to a point (using translated coordinates)
     */
    private PortalInfo findNearestPortal(Vec3 point, List<PortalInfo> portals, boolean useTranslatedPositions) {
        PortalInfo nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (PortalInfo portal : portals) {
            Vec3 portalPos = useTranslatedPositions ? portal.getTranslatedPos() : portal.getCenterPos();
            double distance = portalPos.distanceToSqr(point);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = portal;
            }
        }

        return nearest;
    }

    /**
     * Check if there's an edge between two sample points and add it if so
     */
    private void checkAndAddEdge(Map<BlockPos, PortalInfo> nearestPortalMap,
                                  BlockPos pos1, BlockPos pos2, PortalInfo portal1) {
        PortalInfo portal2 = nearestPortalMap.get(pos2);

        if (portal2 != null && !portal1.equals(portal2)) {
            // Edge found! Add it
            Vec3 start = new Vec3(pos1.getX(), pos1.getY(), pos1.getZ());
            Vec3 end = new Vec3(pos2.getX(), pos2.getY(), pos2.getZ());

            // Use the color of portal1 (or could blend both)
            Vector3f color = portal1.color;

            cachedEdges.add(new VoronoiEdge(start, end, color));
        }
    }

    /**
     * Clear cached data
     */
    public void clear() {
        cachedEdges.clear();
        cachedDimension = null;
    }

    /**
     * Represents a single edge in the Voronoi diagram
     */
    private static class VoronoiEdge {
        final Vec3 start;
        final Vec3 end;
        final Vector3f color;

        VoronoiEdge(Vec3 start, Vec3 end, Vector3f color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }
    }
}
