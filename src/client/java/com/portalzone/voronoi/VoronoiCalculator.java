package com.portalzone.voronoi;

import com.mojang.blaze3d.vertex.PoseStack;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalLinkingAlgorithm;
import com.portalzone.portal.PortalManager;
import com.portalzone.render.PortalRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Calculates and renders 3D Voronoi cell borders for portal zones
 */
public class VoronoiCalculator {
    private static final VoronoiCalculator INSTANCE = new VoronoiCalculator();

    // Voronoi calculation parameters
    private static final int SAMPLE_SPACING = 1; // Sample every 1 block
    private static final int LOCAL_RADIUS = 128;

    // LOD (Level of Detail) constants
    private static final int HIGH_DETAIL_RADIUS = 64; // blocks from player
    private static final int HIGH_DETAIL_SPACING = 1; // 1 block resolution
    private static final int LOW_DETAIL_SPACING = 4; // 4 block resolution

    // Neutral zone color (for areas with no portal in range)
    private static final Vector3f NEUTRAL_ZONE_COLOR = new Vector3f(0.8f, 0.8f, 0.8f);

    // Cached Voronoi edges
    private final List<VoronoiEdge> cachedEdges = new ArrayList<>();
    private ResourceKey<Level> cachedDimension = null;
    private Vec3 cachedPlayerPos = null;
    private static final double RECALC_DISTANCE_THRESHOLD = 32.0; // Recalculate if player moves 32 blocks

    private VoronoiCalculator() {
    }

    public static VoronoiCalculator getInstance() {
        return INSTANCE;
    }

    /**
     * Render the Voronoi borders
     * Handles both portal-colored edges and neutral-colored edges (for zones with no portal in range)
     */
    public void render(PoseStack matrices, MultiBufferSource bufferSource, Vec3 camPos, ResourceKey<Level> currentDim, Camera camera) {
        // Recalculate if portals have changed, dimension changed, or player moved significantly
        boolean needsRecalc = PortalManager.getInstance().hasPortalsChanged()
                           || !currentDim.equals(cachedDimension)
                           || cachedPlayerPos == null
                           || camPos.distanceTo(cachedPlayerPos) > RECALC_DISTANCE_THRESHOLD;

        if (needsRecalc) {
            recalculateVoronoi(camPos, currentDim);
            PortalManager.getInstance().clearChangedFlag();
            cachedDimension = currentDim;
            cachedPlayerPos = camPos;
        }

        // Calculate normal from camera forward vector (pointing toward camera)
        var rot = camera.rotation();
        Quaternionf cameraRot = new Quaternionf(rot);
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(cameraRot);

        // Render borders with depth control
        boolean bordersAlwaysVisible = PortalManager.getInstance().isBordersAlwaysVisible();
        boolean bordersUseDepth = !bordersAlwaysVisible;

        // Render cached edges with world coordinates (PoseStack is already camera-relative)
        for (VoronoiEdge edge : cachedEdges) {
            Vector3f color = edge.color;

            PortalRenderer.submitLine(matrices, bufferSource,
                color.x, color.y, color.z, 0.6f,
                0x00F000F0,
                edge.start.x, edge.start.y, edge.start.z,
                edge.end.x, edge.end.y, edge.end.z,
                forward,
                bordersUseDepth);
        }
    }

    /**
     * Calculate dynamic max distance based on portal locations
     * Returns the distance to render Voronoi borders, based on furthest portal + margin
     */
    private int calculateMaxDistance(java.util.Collection<PortalInfo> portals, Vec3 playerPos) {
        if (portals.isEmpty()) {
            return 256; // Minimum distance
        }

        double maxDistance = 0.0;
        for (PortalInfo portal : portals) {
            double distance = portal.getCenterPos().distanceTo(playerPos);
            if (distance > maxDistance) {
                maxDistance = distance;
            }
        }

        // Add margin beyond furthest portal
        int calculatedDistance = (int) Math.ceil(maxDistance + 128.0);

        // Clamp to min/max bounds
        return Math.max(256, Math.min(2048, calculatedDistance));
    }

    /**
     * Recalculate Voronoi borders with 2-tier LOD system
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

        // Calculate dynamic max distance based on portal locations
        int maxDistance = calculateMaxDistance(portalSource, playerPos);

        // Convert to list for easier indexing
        List<PortalInfo> portalList = new ArrayList<>(portalSource);
        int portalCount = portalList.size();
        PortalInfo[] portals = portalList.toArray(new PortalInfo[0]);
        double[] portalX = new double[portalCount];
        double[] portalY = new double[portalCount];
        double[] portalZ = new double[portalCount];
        for (int i = 0; i < portalCount; i++) {
            PortalInfo portal = portals[i];
            Vec3 portalPos = useTranslatedPositions ? portal.getTranslatedPos() : portal.getCenterPos();
            portalX[i] = portalPos.x;
            portalY[i] = portalPos.y;
            portalZ[i] = portalPos.z;
        }

        // === INNER ZONE: High detail (0-64 blocks, 1-block spacing) ===
        List<VoronoiEdge> innerEdges = calculateVoronoiZone(
            playerPos, portals, portalX, portalY, portalZ,
            HIGH_DETAIL_RADIUS, HIGH_DETAIL_SPACING,
            currentDim, otherDim, portalList
        );

        // === OUTER ZONE: Low detail (64-maxDistance blocks, 4-block spacing) ===
        List<VoronoiEdge> outerEdges = calculateVoronoiZone(
            playerPos, portals, portalX, portalY, portalZ,
            maxDistance, LOW_DETAIL_SPACING,
            currentDim, otherDim, portalList
        );

        // Merge edge lists
        cachedEdges.addAll(innerEdges);
        cachedEdges.addAll(outerEdges);

        System.out.println("[Voronoi] portals=" + portalCount
            + " edges=" + cachedEdges.size()
            + " (inner=" + innerEdges.size() + " outer=" + outerEdges.size() + ")"
            + " maxDist=" + maxDistance);
    }

    /**
     * Calculate Voronoi edges for a specific zone with given radius and spacing
     */
    private List<VoronoiEdge> calculateVoronoiZone(Vec3 playerPos, PortalInfo[] portals,
                                                     double[] portalX, double[] portalY, double[] portalZ,
                                                     int radius, int spacing,
                                                     ResourceKey<Level> currentDim, ResourceKey<Level> otherDim,
                                                     List<PortalInfo> portalList) {
        List<VoronoiEdge> edges = new ArrayList<>();

        // Sample points in 3D space around the player
        // Align to world grid to prevent borders from shifting as player moves
        int minX = (int) Math.floor((playerPos.x - radius) / spacing) * spacing;
        int maxX = (int) Math.ceil((playerPos.x + radius) / spacing) * spacing;
        int minY = Math.max((int) Math.floor((playerPos.y - radius) / spacing) * spacing, -64);
        int maxY = Math.min((int) Math.ceil((playerPos.y + radius) / spacing) * spacing, 320);
        int minZ = (int) Math.floor((playerPos.z - radius) / spacing) * spacing;
        int maxZ = (int) Math.ceil((playerPos.z + radius) / spacing) * spacing;

        int xCount = ((maxX - minX) / spacing) + 1;
        int yCount = ((maxY - minY) / spacing) + 1;
        int zCount = ((maxZ - minZ) / spacing) + 1;
        int[] nearestPortalIdx = new int[xCount * yCount * zCount];
        Arrays.fill(nearestPortalIdx, -1);

        // For each sample point, find the linked portal using Minecraft's portal linking algorithm
        for (int x = minX, ix = 0; x <= maxX; x += spacing, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += spacing, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += spacing, iz++) {
                    int nearest = findNearestPortalIndex(x, y, z, currentDim, portalList);
                    int index = ((ix * yCount) + iy) * zCount + iz;
                    nearestPortalIdx[index] = nearest; // -1 if no portal in range
                }
            }
        }

        // Find edges where the nearest portal changes
        for (int x = minX, ix = 0; x <= maxX; x += spacing, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += spacing, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += spacing, iz++) {
                    int index = ((ix * yCount) + iy) * zCount + iz;
                    int portalIndex = nearestPortalIdx[index];
                    if (portalIndex < 0) {
                        continue;
                    }

                    // Check neighbors in +X, +Y, +Z directions
                    if (ix + 1 < xCount) {
                        checkAndAddEdgeToList(edges, nearestPortalIdx, portals, index,
                            ((ix + 1) * yCount + iy) * zCount + iz,
                            x, y, z, x + spacing, y, z);
                    }
                    if (iy + 1 < yCount) {
                        checkAndAddEdgeToList(edges, nearestPortalIdx, portals, index,
                            (ix * yCount + (iy + 1)) * zCount + iz,
                            x, y, z, x, y + spacing, z);
                    }
                    if (iz + 1 < zCount) {
                        checkAndAddEdgeToList(edges, nearestPortalIdx, portals, index,
                            (ix * yCount + iy) * zCount + (iz + 1),
                            x, y, z, x, y, z + spacing);
                    }
                }
            }
        }

        return edges;
    }

    /**
     * Find which portal would be linked from a given position using Minecraft's portal linking algorithm
     * Returns the index in the portal list, or -1 if no portal is within the search radius
     */
    private int findNearestPortalIndex(double x, double y, double z,
                                       ResourceKey<Level> currentDim,
                                       List<PortalInfo> destinationPortals) {
        Vec3 sourcePos = new Vec3(x, y, z);

        // Use the portal linking algorithm to find which portal this position would link to
        PortalInfo linkedPortal = PortalLinkingAlgorithm.findLinkedPortal(
            sourcePos, currentDim, destinationPortals
        );

        // If no portal in range, return -1 (neutral zone)
        if (linkedPortal == null) {
            return -1;
        }

        // Find the index of the linked portal in the list
        return destinationPortals.indexOf(linkedPortal);
    }

    /**
     * Check if there's an edge between two sample points and add it to a specific list
     * Supports neutral zones: if one side is -1 (no portal in range), creates a neutral-colored edge
     */
    private void checkAndAddEdgeToList(List<VoronoiEdge> edgeList, int[] nearestPortalIdx, PortalInfo[] portals,
                                       int index1, int index2, int x1, int y1, int z1, int x2, int y2, int z2) {
        int portal1Index = nearestPortalIdx[index1];
        int portal2Index = nearestPortalIdx[index2];

        // Check if there's a boundary between different zones
        if (portal1Index != portal2Index) {
            Vec3 start = new Vec3(x1, y1, z1);
            Vec3 end = new Vec3(x2, y2, z2);

            Vector3f color;
            if (portal1Index == -1 || portal2Index == -1) {
                // One side has no portal in range - use neutral color
                color = NEUTRAL_ZONE_COLOR;
            } else {
                // Both sides have portals - use the color of portal1
                color = PortalManager.getInstance().getPortalColor(portals[portal1Index]);
            }

            edgeList.add(new VoronoiEdge(start, end, color));
        }
    }

    /**
     * Check if there's an edge between two sample points and add it if so
     * @deprecated Use checkAndAddEdgeToList instead
     */
    @Deprecated
    private void checkAndAddEdge(int[] nearestPortalIdx, PortalInfo[] portals, int index1, int index2,
                                 int x1, int y1, int z1, int x2, int y2, int z2) {
        int portal1Index = nearestPortalIdx[index1];
        int portal2Index = nearestPortalIdx[index2];

        if (portal1Index >= 0 && portal2Index >= 0 && portal1Index != portal2Index) {
            // Edge found! Add it
            Vec3 start = new Vec3(x1, y1, z1);
            Vec3 end = new Vec3(x2, y2, z2);

            // Use the color of portal1 (or could blend both)
            Vector3f color = PortalManager.getInstance().getPortalColor(portals[portal1Index]);

            cachedEdges.add(new VoronoiEdge(start, end, color));
        }
    }

    /**
     * Clear cached data
     */
    public void clear() {
        cachedEdges.clear();
        cachedDimension = null;
        cachedPlayerPos = null;
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
