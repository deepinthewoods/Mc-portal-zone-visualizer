package com.portalzone.voronoi;

import com.mojang.blaze3d.vertex.PoseStack;
import com.portalzone.portal.PortalInfo;
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

        // Sample points in 3D space around the player
        int minX = ((int) playerPos.x - LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;
        int maxX = ((int) playerPos.x + LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;
        int minY = Math.max(((int) playerPos.y - LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING, -64);
        int maxY = Math.min(((int) playerPos.y + LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING, 320);
        int minZ = ((int) playerPos.z - LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;
        int maxZ = ((int) playerPos.z + LOCAL_RADIUS) / SAMPLE_SPACING * SAMPLE_SPACING;

        int xCount = ((maxX - minX) / SAMPLE_SPACING) + 1;
        int yCount = ((maxY - minY) / SAMPLE_SPACING) + 1;
        int zCount = ((maxZ - minZ) / SAMPLE_SPACING) + 1;
        int[] nearestPortalIdx = new int[xCount * yCount * zCount];
        Arrays.fill(nearestPortalIdx, -1);

        // For each sample point, find the nearest portal (in translated coordinates)
        for (int x = minX, ix = 0; x <= maxX; x += SAMPLE_SPACING, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += SAMPLE_SPACING, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += SAMPLE_SPACING, iz++) {
                    int nearest = findNearestPortalIndex(x, y, z, portalX, portalY, portalZ);
                    if (nearest >= 0) {
                        int index = ((ix * yCount) + iy) * zCount + iz;
                        nearestPortalIdx[index] = nearest;
                    }
                }
            }
        }

        // Find edges where the nearest portal changes
        for (int x = minX, ix = 0; x <= maxX; x += SAMPLE_SPACING, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += SAMPLE_SPACING, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += SAMPLE_SPACING, iz++) {
                    int index = ((ix * yCount) + iy) * zCount + iz;
                    int portalIndex = nearestPortalIdx[index];
                    if (portalIndex < 0) {
                        continue;
                    }

                    // Check neighbors in +X, +Y, +Z directions
                    if (ix + 1 < xCount) {
                        checkAndAddEdge(nearestPortalIdx, portals, index,
                            ((ix + 1) * yCount + iy) * zCount + iz,
                            x, y, z, x + SAMPLE_SPACING, y, z);
                    }
                    if (iy + 1 < yCount) {
                        checkAndAddEdge(nearestPortalIdx, portals, index,
                            (ix * yCount + (iy + 1)) * zCount + iz,
                            x, y, z, x, y + SAMPLE_SPACING, z);
                    }
                    if (iz + 1 < zCount) {
                        checkAndAddEdge(nearestPortalIdx, portals, index,
                            (ix * yCount + iy) * zCount + (iz + 1),
                            x, y, z, x, y, z + SAMPLE_SPACING);
                    }
                }
            }
        }

        System.out.println("[Voronoi] portals=" + portalCount
            + " edges=" + cachedEdges.size()
            + " grid=" + xCount + "x" + yCount + "x" + zCount);
    }

    /**
     * Find the nearest portal to a point (using translated coordinates)
     */
    private int findNearestPortalIndex(double x, double y, double z,
                                       double[] portalX, double[] portalY, double[] portalZ) {
        int nearestIndex = -1;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < portalX.length; i++) {
            double dx = portalX[i] - x;
            double dy = portalY[i] - y;
            double dz = portalZ[i] - z;
            double distance = (dx * dx) + (dy * dy) + (dz * dz);

            if (distance < minDistance) {
                minDistance = distance;
                nearestIndex = i;
            }
        }

        return nearestIndex;
    }

    /**
     * Check if there's an edge between two sample points and add it if so
     */
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
