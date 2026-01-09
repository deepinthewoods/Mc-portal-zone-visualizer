package com.portalzone.voronoi;

import com.mojang.blaze3d.vertex.PoseStack;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalLinkingAlgorithm;
import com.portalzone.portal.PortalManager;
import com.portalzone.render.PortalRenderer;
import net.minecraft.client.Minecraft;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Calculates and renders 3D Voronoi cell borders for portal zones
 */
public class VoronoiCalculator {
    private static final VoronoiCalculator INSTANCE = new VoronoiCalculator();

    // LOD (Level of Detail) constants
    private static final int MAX_BORDER_DISTANCE = 2048; // Increase to render farther borders.
    private static final int[] BASE_LOD_RADII = new int[] {256, 1024};
    private static final int[] LOD_SPACING = new int[] {1, 4, 16};
    private static final int TILE_SIZE = 128;

    // Neutral zone color (for areas with no portal in range)
    private static final Vector3f NEUTRAL_ZONE_COLOR = new Vector3f(0.8f, 0.8f, 0.8f);
    private static final int SKIP_INDEX = -2;

    // Cached Voronoi edges organized by group (0-3) for efficient batch rendering
    // bucketsByGroup[group] = List of EdgeBucket for that group
    private final AtomicReference<List<EdgeBucket>[]> cachedBuckets =
        new AtomicReference<>(createEmptyBuckets());

    private volatile ResourceKey<Level> cachedDimension = null;
    private volatile ResourceKey<Level> cachedSourceDimension = null;
    private volatile Vec3 cachedPlayerPos = null;
    private volatile boolean cachedSimulateHeld = false;
    private volatile ResourceKey<Level> lastRequestedDimension = null;
    private volatile ResourceKey<Level> lastRequestedSourceDimension = null;
    private volatile Vec3 lastRequestedPlayerPos = null;
    private volatile boolean lastRequestedSimulateHeld = false;
    private final Object recalcLock = new Object();
    private RecalcRequest pendingRequest = null;
    private final AtomicLong requestId = new AtomicLong();
    private volatile long latestRequestId = 0;

    private VoronoiCalculator() {
        Thread worker = new Thread(this::recalcLoop, "PortalZoneVoronoiWorker");
        worker.setDaemon(true);
        worker.start();
    }

    public static VoronoiCalculator getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings("unchecked")
    private static List<EdgeBucket>[] createEmptyBuckets() {
        List<EdgeBucket>[] buckets = new List[4];
        for (int i = 0; i < 4; i++) {
            buckets[i] = new ArrayList<>();
        }
        return buckets;
    }

    /**
     * Render the Voronoi borders
     * Handles both portal-colored edges and neutral-colored edges (for zones with no portal in range)
     */
    public void render(PoseStack matrices, MultiBufferSource bufferSource, Vec3 camPos, ResourceKey<Level> currentDim, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 playerPos = mc.player != null ? mc.player.position() : camPos;
        ResourceKey<Level> sourceDim = getSourceDimension(currentDim);
        boolean simulateHeld = PortalManager.getInstance().isSimulatePortalHeld();

        // Recalculate if portals have changed, dimension changed, or player moved significantly
        double recalcDistanceThreshold = getRecalcDistanceThreshold();
        boolean needsRecalc = PortalManager.getInstance().hasPortalsChanged()
                           || !currentDim.equals(cachedDimension)
                           || cachedSourceDimension == null
                           || !sourceDim.equals(cachedSourceDimension)
                           || cachedPlayerPos == null
                           || playerPos.distanceTo(cachedPlayerPos) > recalcDistanceThreshold
                           || simulateHeld != cachedSimulateHeld;

        if (needsRecalc) {
            if (shouldQueueRecalc(playerPos, currentDim, sourceDim, simulateHeld)) {
                RecalcRequest request = buildRecalcRequest(playerPos, currentDim, sourceDim, simulateHeld);
                if (request != null) {
                    queueRecalc(request);
                } else {
                    cachedBuckets.set(createEmptyBuckets());
                    cachedDimension = currentDim;
                    cachedSourceDimension = sourceDim;
                    cachedPlayerPos = playerPos;
                    cachedSimulateHeld = simulateHeld;
                }
                PortalManager.getInstance().clearChangedFlag();
                lastRequestedDimension = currentDim;
                lastRequestedSourceDimension = sourceDim;
                lastRequestedPlayerPos = playerPos;
                lastRequestedSimulateHeld = simulateHeld;
            }
        }

        // Calculate normal from camera forward vector (pointing toward camera)
        var rot = camera.rotation();
        Quaternionf cameraRot = new Quaternionf(rot);
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(cameraRot);

        // Render borders with depth control
        boolean bordersAlwaysVisible = PortalManager.getInstance().isBordersAlwaysVisible();
        boolean bordersUseDepth = !bordersAlwaysVisible;
        float closeLineSkip = PortalManager.getInstance().getCloseLineSkip();
        float farLineSkip = PortalManager.getInstance().getFarLineSkip();
        int lod0Distance = PortalManager.getInstance().getLod0Distance();

        long gameTime = mc.level != null ? mc.level.getGameTime() : 0L;

        // Find nearest portal to camera for zone detection
        int nearestPortalIndex = findNearestPortalToCamera(camPos, currentDim, sourceDim);

        // Render cached buckets - organized by group for efficient batch rendering
        List<EdgeBucket>[] buckets = cachedBuckets.get();
        for (int group = 0; group < 4; group++) {
            for (EdgeBucket bucket : buckets[group]) {
                // Calculate color once for this entire bucket
                Vector3f color = selectBucketColor(bucket, nearestPortalIndex, gameTime);

                // Render all segments in this bucket with the calculated color
                for (EdgeSegment segment : bucket.segments) {
                    if (!shouldRenderSegment(segment, camPos, closeLineSkip, farLineSkip, lod0Distance)) {
                        continue;
                    }

                    PortalRenderer.submitLine(matrices, bufferSource,
                        color.x, color.y, color.z, 0.6f,
                        0x00F000F0,
                        segment.start.x, segment.start.y, segment.start.z,
                        segment.end.x, segment.end.y, segment.end.z,
                        forward,
                        bordersUseDepth);
                }
            }
        }
    }

    /**
     * Find the nearest portal to the camera position
     */
    private int findNearestPortalToCamera(Vec3 camPos, ResourceKey<Level> currentDim, ResourceKey<Level> sourceDim) {
        PortalManager portalManager = PortalManager.getInstance();
        Set<PortalInfo> sourcePortals = portalManager.getPortalsInDimension(sourceDim);

        int nearestIndex = -1;
        double nearestDistSq = Double.MAX_VALUE;
        int index = 0;

        for (PortalInfo portal : sourcePortals) {
            if (portalManager.isPortalHidden(portal)) {
                index++;
                continue;
            }

            Vec3 portalPos = sourceDim.equals(currentDim) ? portal.getCenterPos() : portal.getTranslatedPos();
            double dx = portalPos.x - camPos.x;
            double dy = portalPos.y - camPos.y;
            double dz = portalPos.z - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearestIndex = index;
            }
            index++;
        }

        return nearestIndex;
    }

    /**
     * Select color for an entire bucket based on group and zone detection
     * - Each bucket contains edges from one of 4 groups with the same portal pair
     * - Both colors are visible each frame, but which groups show which color rotates
     * - Borders where camera is inside show current zone color 75% of the time
     * - Other borders show 50/50
     */
    private Vector3f selectBucketColor(EdgeBucket bucket, int nearestPortalIndex, long gameTime) {
        // Check if camera is inside this border (one of the two portals is the current zone)
        boolean isInsideBorder = (nearestPortalIndex == bucket.portal1Index || nearestPortalIndex == bucket.portal2Index);

        // Determine which color is the "current zone" color
        boolean currentZoneIsPrimary = (nearestPortalIndex == bucket.portal1Index);

        // Calculate phase from game time
        long phase = (gameTime / 5L) % 4;

        // Determine which color to show based on group and phase
        boolean showPrimary;

        if (isInsideBorder) {
            // Inside border: show current zone 75% of time, other zone 25% of time
            // Rotate which group shows the minority color
            // Phase 0: Group 0 shows other, Groups 1,2,3 show current (75% current)
            // Phase 1: Group 1 shows other, Groups 0,2,3 show current (75% current)
            // Phase 2: Group 2 shows other, Groups 0,1,3 show current (75% current)
            // Phase 3: Group 3 shows other, Groups 0,1,2 show current (75% current)
            boolean showCurrent = (bucket.group != phase);

            if (currentZoneIsPrimary) {
                showPrimary = showCurrent;
            } else {
                showPrimary = !showCurrent;
            }
        } else {
            // Outside border: show 50/50, rotating which groups show which color
            // Phase 0: Groups 0,1 show primary, Groups 2,3 show secondary
            // Phase 1: Groups 1,2 show primary, Groups 0,3 show secondary
            // Phase 2: Groups 2,3 show primary, Groups 0,1 show secondary
            // Phase 3: Groups 3,0 show primary, Groups 1,2 show secondary
            int relativeGroup = (bucket.group - (int)phase + 4) % 4;
            showPrimary = (relativeGroup < 2);
        }

        return showPrimary ? bucket.primaryColor : bucket.secondaryColor;
    }

    /**
     * Calculate dynamic max distance based on portal locations
     * Returns the distance to render Voronoi borders, based on furthest portal + margin
     */
    private int calculateMaxDistance(Vec3[] portalPositions, Vec3 playerPos) {
        if (portalPositions.length == 0) {
            return 256; // Minimum distance
        }

        double maxDistance = 0.0;
        for (Vec3 portalPos : portalPositions) {
            double distance = portalPos.distanceTo(playerPos);
            if (distance > maxDistance) {
                maxDistance = distance;
            }
        }

        // Add margin beyond furthest portal
        int calculatedDistance = (int) Math.ceil(maxDistance + 128.0);

        // Ensure we cover the full configured render distance
        return Math.max(MAX_BORDER_DISTANCE, Math.max(256, calculatedDistance));
    }

    /**
     * Recalculate Voronoi borders with multi-tier LOD system
     * Organizes edges into buckets by (group, portal_pair) for efficient batch rendering
     */
    private boolean recalculateVoronoi(RecalcRequest request, java.util.Map<BucketKey, EdgeBucket> bucketMap) {
        int portalCount = request.portalCenters.length;
        if (portalCount < 2) {
            return true;
        }

        int[] lodRadii = buildLodRadii(PortalManager.getInstance().getLod0Distance());

        // Calculate dynamic max distance based on portal locations
        int maxDistance = calculateMaxDistance(request.portalTranslated, request.playerPos);

        double[] portalX = new double[portalCount];
        double[] portalY = new double[portalCount];
        double[] portalZ = new double[portalCount];
        for (int i = 0; i < portalCount; i++) {
            Vec3 portalPos = request.portalCenters[i];
            portalX[i] = portalPos.x;
            portalY[i] = portalPos.y;
            portalZ[i] = portalPos.z;
        }

        int minRadius = 0;
        for (int i = 0; i < lodRadii.length; i++) {
            int maxRadius = Math.min(lodRadii[i], maxDistance);
            if (maxRadius <= minRadius) {
                continue;
            }

            int spacing = LOD_SPACING[i];

            // Create overlap: each LOD (except first) extends inward by one spacing unit
            int effectiveMinRadius = minRadius;
            if (i > 0 && spacing > 0) {
                effectiveMinRadius = Math.max(0, minRadius - spacing);
            }

            boolean success;
            // Use rectangular grid for all LODs; spacing scales with distance.
            success = calculateVoronoiZonesRectangular(
                request, portalX, portalY, portalZ,
                effectiveMinRadius, maxRadius, spacing, bucketMap);

            if (!success) {
                return false;
            }
            minRadius = maxRadius;
        }

        if (minRadius < maxDistance) {
            int spacing = LOD_SPACING[LOD_SPACING.length - 1];
            // Create overlap with previous LOD
            int effectiveMinRadius = Math.max(0, minRadius - spacing);
            if (!calculateVoronoiZonesRectangular(
                request, portalX, portalY, portalZ,
                effectiveMinRadius, maxDistance, spacing, bucketMap)) {
                return false;
            }
        }

        int totalSegments = bucketMap.values().stream().mapToInt(b -> b.segments.size()).sum();
        System.out.println("[Voronoi] portals=" + portalCount
            + " segments=" + totalSegments
            + " buckets=" + bucketMap.size()
            + " maxDist=" + maxDistance);
        return true;
    }

    private static int[] buildLodRadii(int lod0Max) {
        int clamped = Math.max(4, Math.min(MAX_BORDER_DISTANCE, lod0Max));
        int[] radii = new int[LOD_SPACING.length];
        radii[0] = clamped;
        for (int i = 1; i < radii.length; i++) {
            int baseIndex = Math.min(i - 1, BASE_LOD_RADII.length - 1);
            int candidate = BASE_LOD_RADII[baseIndex];
            radii[i] = Math.max(candidate, radii[i - 1]);
        }
        return radii;
    }


    /**
     * Find which portal would be linked from a given position using Minecraft's portal linking algorithm
     * Returns the index in the portal list, or -1 if no portal is within the search radius
     */
    private int findNearestPortalIndex(double x, double y, double z,
                                       ResourceKey<Level> currentDim,
                                       Vec3[] destinationPortals,
                                       double[] portalX, double[] portalY, double[] portalZ,
                                       boolean useLinkingAlgorithm) {
        if (destinationPortals.length == 0) {
            return -1;
        }

        if (!useLinkingAlgorithm) {
            int nearestIndex = -1;
            double nearestDistance = Double.MAX_VALUE;
            for (int i = 0; i < destinationPortals.length; i++) {
                double dx = portalX[i] - x;
                double dy = portalY[i] - y;
                double dz = portalZ[i] - z;
                double distance = dx * dx + dy * dy + dz * dz;
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestIndex = i;
                }
            }
            return nearestIndex;
        }

        // Compare in current dimension coordinates with current dimension's search radius
        Vec3 sourcePos = new Vec3(x, y, z);
        int searchRadius = PortalLinkingAlgorithm.getSearchRadius(currentDim);

        int nearestIndex = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < destinationPortals.length; i++) {
            // destinationPortals are already translated to current dimension coordinates
            double horizontal = PortalLinkingAlgorithm.horizontalDistance(sourcePos, destinationPortals[i]);
            if (horizontal <= searchRadius) {
                double distance = PortalLinkingAlgorithm.distance3d(sourcePos, destinationPortals[i]);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestIndex = i;
                }
            }
        }

        return nearestIndex;
    }


    /**
     * Clear cached data
     */
    public void clear() {
        cachedBuckets.set(createEmptyBuckets());
        cachedDimension = null;
        cachedSourceDimension = null;
        cachedPlayerPos = null;
        cachedSimulateHeld = false;
        lastRequestedDimension = null;
        lastRequestedSourceDimension = null;
        lastRequestedPlayerPos = null;
        lastRequestedSimulateHeld = false;
    }

    /**
     * Represents a single line segment (just geometry)
     */
    private static class EdgeSegment {
        final Vec3 start;
        final Vec3 end;
        final int spacing;
        final boolean alwaysRender;
        final int hash;

        EdgeSegment(Vec3 start, Vec3 end, int spacing) {
            this.start = start;
            this.end = end;
            this.spacing = spacing;
            this.alwaysRender = spacing == 1;
            this.hash = hashEdge(start, end, spacing);
        }
    }

    /**
     * Represents a bucket of edges that share the same portal pair and group
     * This allows us to batch render and calculate color once per bucket
     */
    private static class EdgeBucket {
        final List<EdgeSegment> segments;
        final Vector3f primaryColor;
        final Vector3f secondaryColor;
        final int portal1Index;
        final int portal2Index;
        final int group;

        EdgeBucket(Vector3f primaryColor, Vector3f secondaryColor, int portal1Index, int portal2Index, int group) {
            this.segments = new ArrayList<>();
            this.primaryColor = new Vector3f(primaryColor);
            this.secondaryColor = new Vector3f(secondaryColor);
            this.portal1Index = portal1Index;
            this.portal2Index = portal2Index;
            this.group = group;
        }

        void addSegment(Vec3 start, Vec3 end, int spacing) {
            segments.add(new EdgeSegment(start, end, spacing));
        }
    }

    /**
     * Key for identifying unique edge buckets
     */
    private static class BucketKey {
        final int group;
        final int portal1;
        final int portal2;

        BucketKey(int group, int portal1, int portal2) {
            this.group = group;
            this.portal1 = portal1;
            this.portal2 = portal2;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BucketKey)) return false;
            BucketKey that = (BucketKey) o;
            return group == that.group && portal1 == that.portal1 && portal2 == that.portal2;
        }

        @Override
        public int hashCode() {
            return group * 31 * 31 + portal1 * 31 + portal2;
        }
    }


    /**
     * Calculate Voronoi edges using rectangular grid.
     * Adds segments to buckets organized by (group, portal_pair)
     */
    private boolean calculateVoronoiZonesRectangular(RecalcRequest request,
                                                     double[] portalX, double[] portalY, double[] portalZ,
                                                     int minRadius, int maxRadius, int spacing,
                                                     java.util.Map<BucketKey, EdgeBucket> bucketMap) {
        Vec3 playerPos = request.playerPos;
        int minX = (int) Math.floor((playerPos.x - maxRadius) / spacing) * spacing;
        int maxX = (int) Math.ceil((playerPos.x + maxRadius) / spacing) * spacing;
        int minY = Math.max((int) Math.floor((playerPos.y - maxRadius) / spacing) * spacing, -64);
        int maxY = Math.min((int) Math.ceil((playerPos.y + maxRadius) / spacing) * spacing, 320);
        int minZ = (int) Math.floor((playerPos.z - maxRadius) / spacing) * spacing;
        int maxZ = (int) Math.ceil((playerPos.z + maxRadius) / spacing) * spacing;

        int xCount = ((maxX - minX) / spacing) + 1;
        int yCount = ((maxY - minY) / spacing) + 1;
        int zCount = ((maxZ - minZ) / spacing) + 1;
        int[] nearestPortalIdx = new int[xCount * yCount * zCount];
        Arrays.fill(nearestPortalIdx, -1);

        // For each sample point, find the linked portal
        for (int x = minX, ix = 0; x <= maxX; x += spacing, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += spacing, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += spacing, iz++) {
                    if (request.id != latestRequestId) {
                        return false;
                    }
                    int index = ((ix * yCount) + iy) * zCount + iz;
                    double dx = x - playerPos.x;
                    double dy = y - playerPos.y;
                    double dz = z - playerPos.z;
                    double distSq = dx * dx + dy * dy + dz * dz;

                    // Use 3D distance check
                    if (distSq < (double) minRadius * minRadius || distSq > (double) maxRadius * maxRadius) {
                        nearestPortalIdx[index] = SKIP_INDEX;
                        continue;
                    }

                    int nearest = findNearestPortalIndex(x, y, z, request.currentDim,
                        request.portalCenters, portalX, portalY, portalZ, request.useLinkingAlgorithm);
                    nearestPortalIdx[index] = nearest;
                }
            }
        }

        // Generate edges where portal zones change and add them to buckets
        for (int x = minX, ix = 0; x <= maxX; x += spacing, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += spacing, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += spacing, iz++) {
                    if (request.id != latestRequestId) {
                        return false;
                    }
                    int index = ((ix * yCount) + iy) * zCount + iz;
                    int portalIndex = nearestPortalIdx[index];
                    if (portalIndex == SKIP_INDEX) {
                        continue;
                    }

                    // Check neighbors in +X, +Y, +Z directions
                    if (ix + 1 < xCount) {
                        int neighborIdx = ((ix + 1) * yCount + iy) * zCount + iz;
                        checkNeighborAndAddToBucket(bucketMap, nearestPortalIdx, index, neighborIdx,
                            x, y, z, x + spacing, y, z, spacing,
                            request.portalCenters, request.portalColors, request.showNeutralBorders, request.showVerticalBorders);
                    }
                    if (iy + 1 < yCount) {
                        int neighborIdx = (ix * yCount + (iy + 1)) * zCount + iz;
                        checkNeighborAndAddToBucket(bucketMap, nearestPortalIdx, index, neighborIdx,
                            x, y, z, x, y + spacing, z, spacing,
                            request.portalCenters, request.portalColors, request.showNeutralBorders, request.showVerticalBorders);
                    }
                    if (iz + 1 < zCount) {
                        int neighborIdx = (ix * yCount + iy) * zCount + (iz + 1);
                        checkNeighborAndAddToBucket(bucketMap, nearestPortalIdx, index, neighborIdx,
                            x, y, z, x, y, z + spacing, spacing,
                            request.portalCenters, request.portalColors, request.showNeutralBorders, request.showVerticalBorders);
                    }
                }
            }
        }

        return true;
    }

    /**
     * Check neighbor and add segments to appropriate buckets for rectangular grid.
     * Each segment is assigned to a group (0-3) based on its position within the border plane.
     */
    private void checkNeighborAndAddToBucket(java.util.Map<BucketKey, EdgeBucket> bucketMap,
                                             int[] nearestPortalIdx,
                                             int index1, int index2,
                                             int x1, int y1, int z1, int x2, int y2, int z2, int spacing,
                                             Vec3[] portalCenters, Vector3f[] portalColors,
                                             boolean showNeutralBorders, boolean showVerticalBorders) {
        int portal1 = nearestPortalIdx[index1];
        int portal2 = nearestPortalIdx[index2];

        if (portal1 == SKIP_INDEX || portal2 == SKIP_INDEX || portal1 == portal2) {
            return;
        }

        Vec3 start = new Vec3(x1, y1, z1);
        Vec3 end = new Vec3(x2, y2, z2);
        Vec3 midpoint = start.add(end).scale(0.5);
        Vec3 direction = end.subtract(start);

        if (direction.lengthSqr() == 0.0) {
            return;
        }

        // Direction from one grid point to the other (perpendicular to the border surface)
        Vec3 borderNormal = direction.normalize();

        // Check if this is a vertical edge (for the vertical borders checkbox)
        if (Math.abs(borderNormal.y) > 0.9 && !showVerticalBorders) {
            return;
        }

        // Determine colors
        Vector3f color1, color2;
        if (portal1 == -1 || portal2 == -1) {
            if (!showNeutralBorders) {
                return;
            }
            color1 = portal1 == -1 ? NEUTRAL_ZONE_COLOR : portalColors[portal1];
            color2 = portal2 == -1 ? NEUTRAL_ZONE_COLOR : portalColors[portal2];
        } else {
            color1 = portalColors[portal1];
            color2 = portalColors[portal2];
        }

        // Normalize ordering so all segments for a portal pair use consistent colors
        Vector3f primaryColor;
        Vector3f secondaryColor;
        int portal1Index;
        int portal2Index;

        if (portal1 == -1 && portal2 >= 0) {
            primaryColor = portalColors[portal2];
            secondaryColor = NEUTRAL_ZONE_COLOR;
            portal1Index = portal2;
            portal2Index = -1;
        } else if (portal2 == -1 && portal1 >= 0) {
            primaryColor = portalColors[portal1];
            secondaryColor = NEUTRAL_ZONE_COLOR;
            portal1Index = portal1;
            portal2Index = -1;
        } else if (portal1 >= 0 && portal2 >= 0) {
            if (portal1 <= portal2) {
                primaryColor = color1;
                secondaryColor = color2;
                portal1Index = portal1;
                portal2Index = portal2;
            } else {
                primaryColor = color2;
                secondaryColor = color1;
                portal1Index = portal2;
                portal2Index = portal1;
            }
        } else {
            return;
        }

        // Draw grid lines aligned with axes, centered on the border plane.
        Vec3 center = midpoint;
        double halfSpacing = spacing * 0.5;
        double minA = -halfSpacing;
        double maxA = halfSpacing;

        // Determine which axis the border is perpendicular to
        // and draw lines along the other two axes
        if (Math.abs(direction.x) > 0.1 && Math.abs(direction.y) < 0.1 && Math.abs(direction.z) < 0.1) {
            // Border normal along X axis - draw lines along Y and Z
            addSegmentToBucket(bucketMap, center.x, center.y + minA, center.z + minA, center.x, center.y + maxA, center.z + minA,
                spacing, 0, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x, center.y + minA, center.z + maxA, center.x, center.y + maxA, center.z + maxA,
                spacing, 1, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x, center.y + minA, center.z + minA, center.x, center.y + minA, center.z + maxA,
                spacing, 2, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x, center.y + maxA, center.z + minA, center.x, center.y + maxA, center.z + maxA,
                spacing, 3, primaryColor, secondaryColor, portal1Index, portal2Index);
        } else if (Math.abs(direction.y) > 0.1 && Math.abs(direction.x) < 0.1 && Math.abs(direction.z) < 0.1) {
            // Border normal along Y axis - draw lines along X and Z
            addSegmentToBucket(bucketMap, center.x + minA, center.y, center.z + minA, center.x + maxA, center.y, center.z + minA,
                spacing, 0, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x + minA, center.y, center.z + maxA, center.x + maxA, center.y, center.z + maxA,
                spacing, 1, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x + minA, center.y, center.z + minA, center.x + minA, center.y, center.z + maxA,
                spacing, 2, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x + maxA, center.y, center.z + minA, center.x + maxA, center.y, center.z + maxA,
                spacing, 3, primaryColor, secondaryColor, portal1Index, portal2Index);
        } else if (Math.abs(direction.z) > 0.1 && Math.abs(direction.x) < 0.1 && Math.abs(direction.y) < 0.1) {
            // Border normal along Z axis - draw lines along X and Y
            addSegmentToBucket(bucketMap, center.x + minA, center.y + minA, center.z, center.x + maxA, center.y + minA, center.z,
                spacing, 0, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x + minA, center.y + maxA, center.z, center.x + maxA, center.y + maxA, center.z,
                spacing, 1, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x + minA, center.y + minA, center.z, center.x + minA, center.y + maxA, center.z,
                spacing, 2, primaryColor, secondaryColor, portal1Index, portal2Index);
            addSegmentToBucket(bucketMap, center.x + maxA, center.y + minA, center.z, center.x + maxA, center.y + maxA, center.z,
                spacing, 3, primaryColor, secondaryColor, portal1Index, portal2Index);
        }
    }

    /**
     * Helper method to add a line segment to the appropriate bucket for a fixed group
     */
    private void addSegmentToBucket(java.util.Map<BucketKey, EdgeBucket> bucketMap,
                                    double x1, double y1, double z1, double x2, double y2, double z2,
                                    int spacing, int group, Vector3f primaryColor, Vector3f secondaryColor,
                                    int portal1Index, int portal2Index) {
        // Create segment positions
        Vec3 start = new Vec3(x1, y1, z1);
        Vec3 end = new Vec3(x2, y2, z2);

        // Get or create bucket for this (group, portal_pair)
        BucketKey key = new BucketKey(group, portal1Index, portal2Index);
        EdgeBucket bucket = bucketMap.computeIfAbsent(key,
            k -> new EdgeBucket(primaryColor, secondaryColor, portal1Index, portal2Index, group));

        // Add segment to bucket
        bucket.addSegment(start, end, spacing);
    }


    private static boolean shouldRenderSegment(EdgeSegment segment, Vec3 camPos, float closeLineSkip,
                                               float farLineSkip, int lod0Distance) {
        // LOD 0 (spacing == 1) always renders every line with 0% drop
        if (segment.alwaysRender) {
            return true;
        }

        // Check border draw distance
        double maxBorderDistance = PortalManager.getInstance().getBorderDrawDistance();
        double midX = (segment.start.x + segment.end.x) * 0.5;
        double midY = (segment.start.y + segment.end.y) * 0.5;
        double midZ = (segment.start.z + segment.end.z) * 0.5;
        double dx = midX - camPos.x;
        double dy = midY - camPos.y;
        double dz = midZ - camPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        // Don't render if beyond border draw distance
        if (distSq > maxBorderDistance * maxBorderDistance) {
            return false;
        }

        // If both skip chances are 0, always render all lines
        if (closeLineSkip <= 0.0f && farLineSkip <= 0.0f) {
            return true;
        }

        // Calculate actual distance from camera
        double distance = Math.sqrt(distSq);
        double lod0Radius = Math.max(0.0, lod0Distance);

        float dropChance;
        if (distance <= lod0Radius) {
            dropChance = closeLineSkip;
        } else {
            // Fade between close and far skip chances beyond the LOD 0 boundary.
            double distanceBeyondLod0 = distance - lod0Radius;
            double fadeRange = maxBorderDistance - lod0Radius;

            if (fadeRange <= 0.0) {
                fadeRange = 1.0; // Avoid division by zero
            }

            // Calculate interpolation factor (0 at LOD 0 boundary, 1 at max distance)
            double t = distanceBeyondLod0 / fadeRange;
            if (t < 0.0) {
                t = 0.0;
            }
            if (t > 1.0) {
                t = 1.0;
            }

            dropChance = (float) (closeLineSkip + (farLineSkip - closeLineSkip) * t);
        }
        return fastRandom(segment) >= dropChance;
    }

    private static float fastRandom(EdgeSegment segment) {
        return (segment.hash & 0x7fffffff) / 2147483647.0f;
    }

    private static int hashEdge(Vec3 start, Vec3 end, int spacing) {
        int h = 0x811c9dc5;
        h = (h ^ quantize(start.x)) * 0x01000193;
        h = (h ^ quantize(start.y)) * 0x01000193;
        h = (h ^ quantize(start.z)) * 0x01000193;
        h = (h ^ quantize(end.x)) * 0x01000193;
        h = (h ^ quantize(end.y)) * 0x01000193;
        h = (h ^ quantize(end.z)) * 0x01000193;
        h = (h ^ spacing) * 0x01000193; // Include spacing to differentiate LOD levels
        return h;
    }

    private static int quantize(double value) {
        return (int) Math.round(value * 16.0);
    }

    private boolean shouldQueueRecalc(Vec3 playerPos, ResourceKey<Level> currentDim,
                                      ResourceKey<Level> sourceDim, boolean simulateHeld) {
        if (lastRequestedDimension == null || !currentDim.equals(lastRequestedDimension)) {
            return true;
        }
        if (lastRequestedSourceDimension == null || !sourceDim.equals(lastRequestedSourceDimension)) {
            return true;
        }
        if (lastRequestedSimulateHeld != simulateHeld) {
            return true;
        }
        if (lastRequestedPlayerPos == null) {
            return true;
        }
        return playerPos.distanceTo(lastRequestedPlayerPos) > getRecalcDistanceThreshold()
            || PortalManager.getInstance().hasPortalsChanged();
    }

    private static double getRecalcDistanceThreshold() {
        if (PortalManager.getInstance().isSimulatePortalHeld()) {
            return 1.0;
        }
        return PortalManager.getInstance().getLod0Distance() * 0.25;
    }

    private void queueRecalc(RecalcRequest request) {
        synchronized (recalcLock) {
            latestRequestId = request.id;
            pendingRequest = request;
            recalcLock.notify();
        }
    }

    private static ResourceKey<Level> getSourceDimension(ResourceKey<Level> currentDim) {
        if (PortalManager.getInstance().isFlipBordersHeld()) {
            return currentDim;
        }
        return Level.NETHER.equals(currentDim) ? Level.OVERWORLD : Level.NETHER;
    }

    private RecalcRequest buildRecalcRequest(Vec3 playerPos, ResourceKey<Level> currentDim,
                                            ResourceKey<Level> sourceDim, boolean simulateHeld) {
        PortalManager portalManager = PortalManager.getInstance();
        Set<PortalInfo> sourcePortals = portalManager.getPortalsInDimension(sourceDim);
        List<PortalInfo> visiblePortals = new ArrayList<>();
        for (PortalInfo portal : sourcePortals) {
            if (!portalManager.isPortalHidden(portal)) {
                visiblePortals.add(portal);
            }
        }

        int extraPortals = simulateHeld ? 1 : 0;
        if (visiblePortals.size() + extraPortals < 2) {
            return null;
        }

        boolean showNeutralBorders = portalManager.isNeutralBordersEnabled();
        boolean showVerticalBorders = portalManager.isVerticalBordersEnabled();
        boolean useLinkingAlgorithm = !sourceDim.equals(currentDim);

        int count = visiblePortals.size() + extraPortals;
        Vec3[] centers = new Vec3[count];
        Vec3[] translated = new Vec3[count];
        Vector3f[] colors = new Vector3f[count];
        int i = 0;
        for (PortalInfo portal : visiblePortals) {
            Vec3 pos = sourceDim.equals(currentDim) ? portal.getCenterPos() : portal.getTranslatedPos();
            centers[i] = pos;
            translated[i] = pos;
            Vector3f color = portalManager.getPortalColor(portal);
            colors[i] = new Vector3f(color);
            i++;
        }

        if (simulateHeld) {
            centers[i] = playerPos;
            translated[i] = playerPos;
            colors[i] = new Vector3f(1.0f, 1.0f, 1.0f);
        }

        long id = requestId.incrementAndGet();
        return new RecalcRequest(id, playerPos, currentDim, sourceDim, simulateHeld,
            centers, translated, colors, showNeutralBorders, showVerticalBorders, useLinkingAlgorithm);
    }

    private void recalcLoop() {
        while (true) {
            RecalcRequest request;
            synchronized (recalcLock) {
                while (pendingRequest == null) {
                    try {
                        recalcLock.wait();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                request = pendingRequest;
                pendingRequest = null;
            }

            // Create bucket map and perform calculation
            java.util.Map<BucketKey, EdgeBucket> bucketMap = new java.util.HashMap<>();
            cachedBuckets.set(createEmptyBuckets());
            cachedDimension = request.currentDim;
            cachedSourceDimension = request.sourceDim;
            cachedPlayerPos = request.playerPos;
            cachedSimulateHeld = request.simulateHeld;

            if (!recalculateVoronoi(request, bucketMap)) {
                continue;
            }

            // Convert bucketMap to array of lists organized by group
            List<EdgeBucket>[] bucketsByGroup = createEmptyBuckets();
            for (EdgeBucket bucket : bucketMap.values()) {
                bucketsByGroup[bucket.group].add(bucket);
            }

            // Update cache
            cachedBuckets.set(bucketsByGroup);
            cachedDimension = request.currentDim;
            cachedSourceDimension = request.sourceDim;
            cachedPlayerPos = request.playerPos;
            cachedSimulateHeld = request.simulateHeld;
        }
    }

    private static class RecalcRequest {
        final long id;
        final Vec3 playerPos;
        final ResourceKey<Level> currentDim;
        final ResourceKey<Level> sourceDim;
        final boolean simulateHeld;
        final Vec3[] portalCenters;
        final Vec3[] portalTranslated;
        final Vector3f[] portalColors;
        final boolean showNeutralBorders;
        final boolean showVerticalBorders;
        final boolean useLinkingAlgorithm;

        RecalcRequest(long id, Vec3 playerPos, ResourceKey<Level> currentDim,
                      ResourceKey<Level> sourceDim, boolean simulateHeld,
                      Vec3[] portalCenters, Vec3[] portalTranslated, Vector3f[] portalColors,
                      boolean showNeutralBorders, boolean showVerticalBorders, boolean useLinkingAlgorithm) {
            this.id = id;
            this.playerPos = playerPos;
            this.currentDim = currentDim;
            this.sourceDim = sourceDim;
            this.simulateHeld = simulateHeld;
            this.portalCenters = portalCenters;
            this.portalTranslated = portalTranslated;
            this.portalColors = portalColors;
            this.showNeutralBorders = showNeutralBorders;
            this.showVerticalBorders = showVerticalBorders;
            this.useLinkingAlgorithm = useLinkingAlgorithm;
        }
    }
}
