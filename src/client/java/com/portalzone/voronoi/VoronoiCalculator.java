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

    /**
     * LOD preset configurations
     * NONE = No LOD reduction, full mesh detail (spacing 1 everywhere)
     * LIGHT = Light LOD reduction, balanced quality/performance
     * HEAVY = Heavy LOD reduction, most aggressive detail reduction
     */
    public enum LodPreset {
        NONE("Full Detail", new int[] {}, new int[] {1}),
        LIGHT("Light", new int[] {100, 200, 400, 800, 1200}, new int[] {1, 2, 4, 8, 16, 32}),
        HEAVY("Heavy", new int[] {100, 300, 500, 700}, new int[] {1, 3, 9, 27, 91});

        private final String displayName;
        private final int[] baseLodRadii;
        private final int[] lodSpacing;

        LodPreset(String displayName, int[] baseLodRadii, int[] lodSpacing) {
            this.displayName = displayName;
            this.baseLodRadii = baseLodRadii;
            this.lodSpacing = lodSpacing;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int[] getBaseLodRadii() {
            return baseLodRadii;
        }

        public int[] getLodSpacing() {
            return lodSpacing;
        }

        public LodPreset next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    // LOD (Level of Detail) constants
    private static final int MAX_BORDER_DISTANCE = 2048; // Increase to render farther borders.

    private static final int TILE_SIZE = 128;
    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 320;

    // Neutral zone color (for areas with no portal in range)
    private static final Vector3f NEUTRAL_ZONE_COLOR = new Vector3f(0.8f, 0.8f, 0.8f);
    private static final int SKIP_INDEX = -2;

    // Chunk cache system
    private final VoronoiChunkCache chunkCache;
    private long cachedPortalConfigHash = 0L;
    private int chunkSize = 128; // Matches TILE_SIZE
    private int chunkCacheMaxSize = 8192;

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
    private volatile RecalcRequest latestQueuedRequest = null;
    private final AtomicLong requestId = new AtomicLong();

    private VoronoiCalculator() {
        // Initialize chunk cache
        this.chunkCache = new VoronoiChunkCache(chunkCacheMaxSize);

        Thread worker = new Thread(this::recalcLoop, "PortalZoneVoronoiWorker");
        worker.setDaemon(true);
        worker.start();
    }

    public static VoronoiCalculator getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings("unchecked")
    private static List<EdgeBucket>[] createEmptyBuckets() {
        List<EdgeBucket>[] buckets = new List[8];
        for (int i = 0; i < 8; i++) {
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

        // Check for portal configuration changes and invalidate cache if needed
        checkAndInvalidateCacheIfNeeded(currentDim, sourceDim);

        // Check for dimension change and invalidate cache
        if (cachedDimension != null && !currentDim.equals(cachedDimension)) {
            System.out.println("[VoronoiChunk] Dimension changed from " +
                cachedDimension.location() + " to " + currentDim.location() + ", invalidating cache");
            chunkCache.invalidateAll();
            cachedPortalConfigHash = 0L;
        }

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
        PortalManager.LineRenderPreset preset = PortalManager.getInstance().getLineRenderPreset();
        int lod0Distance = PortalManager.getInstance().getLod0Distance();
        int[] lodRadii = buildLodRadii(lod0Distance);

        long gameTime = mc.level != null ? mc.level.getGameTime() : 0L;
        long phase = (gameTime / 8L) % 8; // 8-phase rotation for line skipping (8 ticks per phase)

        // Find nearest portal to camera for zone detection
        int nearestPortalIndex = findNearestPortalToCamera(camPos, currentDim, sourceDim);

        // Render cached buckets - organized by group for efficient batch rendering
        List<EdgeBucket>[] buckets = cachedBuckets.get();
        for (int group = 0; group < 8; group++) {
            for (EdgeBucket bucket : buckets[group]) {
                // Calculate color once for this entire bucket
                Vector3f color = selectBucketColor(bucket, nearestPortalIndex, gameTime);

                // Render all segments in this bucket with the calculated color
                for (EdgeSegment segment : bucket.segments) {
                    // Use bucket.group to ensure we're checking the bucket's actual assigned group
                    if (!shouldRenderSegment(segment, camPos, bucket.group, preset, phase, lod0Distance, lodRadii)) {
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
     * - Each bucket contains edges from one of 8 groups with the same portal pair
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
        long phase = (gameTime / 8L) % 8;

        // Determine which color to show based on group and phase
        boolean showPrimary;

        if (isInsideBorder) {
            // Inside border: show current zone 75% of time, other zone 25% of time
            // Rotate which groups (2 out of 8) show the minority color
            // 6/8 groups show current, 2/8 show other
            int minorityGroup1 = (int)phase;
            int minorityGroup2 = ((int)phase + 4) % 8;
            boolean showCurrent = (bucket.group != minorityGroup1 && bucket.group != minorityGroup2);

            if (currentZoneIsPrimary) {
                showPrimary = showCurrent;
            } else {
                showPrimary = !showCurrent;
            }
        } else {
            // Outside border: alternate colors every 4 phases (50/50 split)
            // Add portal indices as offset so different borders alternate at different times
            int offset = (bucket.portal1Index + bucket.portal2Index) % 8;
            int adjustedPhase = ((int)phase + offset) % 8;
            showPrimary = adjustedPhase < 4;
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
        int[] lodSpacing = getLodSpacing();
        for (int i = 0; i < lodRadii.length; i++) {
            int maxRadius = Math.min(lodRadii[i], maxDistance);
            if (maxRadius <= minRadius) {
                continue;
            }

            int spacing = lodSpacing[i];

            // Create overlap: each LOD (except first) extends inward by one spacing unit
            int effectiveMinRadius = minRadius;
            if (i > 0 && spacing > 0) {
                effectiveMinRadius = Math.max(0, minRadius - spacing);
            }

            boolean success;
            // Use rectangular grid for all LODs; spacing scales with distance.
            success = calculateVoronoiZonesRectangular(
                request, portalX, portalY, portalZ,
                effectiveMinRadius, maxRadius, spacing, i, true, bucketMap,
                null, null, null, null, null, null);

            if (!success) {
                return false;
            }
            minRadius = maxRadius;
        }

        if (minRadius < maxDistance) {
            int spacing = lodSpacing[lodSpacing.length - 1];
            // Create overlap with previous LOD
            int effectiveMinRadius = Math.max(0, minRadius - spacing);
            if (!calculateVoronoiZonesRectangular(
                request, portalX, portalY, portalZ,
                effectiveMinRadius, maxDistance, spacing, lodSpacing.length - 1, true, bucketMap,
                null, null, null, null, null, null)) {
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

    /**
     * Recalculate Voronoi borders using the chunk-based caching system.
     * This method replaces the full recalculation approach with a chunked system that:
     * - Divides space into fixed-size chunks (128x128x128 blocks)
     * - Caches calculated chunks with portal configuration hash
     * - Only recalculates chunks when portal config changes or chunk is newly in range
     * - Eliminates flickering by merging cached + new chunks before atomic swap
     *
     * @param request The recalculation request containing player position and portal data
     * @param bucketMap The output bucket map to populate with merged chunk data
     * @return true if calculation succeeded, false if cancelled
     */
    private boolean recalculateVoronoiChunked(RecalcRequest request, java.util.Map<BucketKey, EdgeBucket> bucketMap) {
        int portalCount = request.portalCenters.length;
        if (portalCount < 2) {
            return true;
        }

        long startTime = System.currentTimeMillis();

        // Get current portal configuration hash
        boolean neutralBorders = PortalManager.getInstance().isNeutralBordersEnabled();
        long portalConfigHash = PortalConfigHasher.calculatePortalConfigHash(
            request.portalCenters, request.portalColors,
            new boolean[request.portalCenters.length], // All visible in request
            neutralBorders);

        // Calculate dynamic max distance based on portal locations
        int maxDistance = calculateMaxDistance(request.portalTranslated, request.playerPos);
        int[] lodRadii = buildLodRadii(PortalManager.getInstance().getLod0Distance());

        // Track statistics
        int totalChunks = 0;
        int cacheHits = 0;
        int cacheMisses = 0;

        // Process each LOD level
        int minRadius = 0;
        for (int lodLevel = 0; lodLevel < lodRadii.length; lodLevel++) {
            int maxRadius = Math.min(lodRadii[lodLevel], maxDistance);
            if (maxRadius <= minRadius) {
                continue;
            }

            // Get required chunks for this LOD level
            Set<ChunkCoord> requiredChunks = ChunkBoundaryCalculator.getRequiredChunks(
                request.playerPos, maxRadius, chunkSize, WORLD_MIN_Y, WORLD_MAX_Y);

            System.out.println("[VoronoiChunk] LOD " + lodLevel + ": " + requiredChunks.size() +
                " chunks in range (radius " + minRadius + "-" + maxRadius + ")");

            // For each required chunk, check cache or calculate
            for (ChunkCoord chunkCoord : requiredChunks) {
                // Check for cancellation
                if (shouldCancelRequest(request)) {
                    return false;
                }

                totalChunks++;

                // Create chunk key
                VoronoiChunkKey key = new VoronoiChunkKey(
                    chunkCoord.x, chunkCoord.y, chunkCoord.z,
                    lodLevel, portalConfigHash);

                // Try to get from cache
                ChunkData cachedData = chunkCache.get(key);

                if (cachedData != null) {
                    // Cache hit - merge cached data into bucket map
                    cacheHits++;
                    mergeChunkIntoBucketMap(cachedData.getBucketMap(), bucketMap);
                } else {
                    // Cache miss - calculate chunk
                    cacheMisses++;
                    java.util.Map<BucketKey, EdgeBucket> chunkBuckets =
                        calculateSingleChunk(request, chunkCoord, lodLevel, chunkSize);

                    if (chunkBuckets == null) {
                        // Calculation was cancelled
                        return false;
                    }

                    // Merge into final bucket map
                    mergeChunkIntoBucketMap(chunkBuckets, bucketMap);

                    // Cache the newly calculated chunk
                    ChunkData newData = new ChunkData(chunkBuckets);
                    chunkCache.put(key, newData);
                }
            }

            minRadius = maxRadius;
        }

        // Handle final LOD level beyond configured radii
        if (minRadius < maxDistance) {
            int lodLevel = getFinalLodLevel();
            Set<ChunkCoord> requiredChunks = ChunkBoundaryCalculator.getRequiredChunks(
                request.playerPos, maxDistance, chunkSize, WORLD_MIN_Y, WORLD_MAX_Y);

            System.out.println("[VoronoiChunk] Final LOD: " + requiredChunks.size() +
                " chunks in range (radius " + minRadius + "-" + maxDistance + ")");

            for (ChunkCoord chunkCoord : requiredChunks) {
                if (shouldCancelRequest(request)) {
                    return false;
                }

                totalChunks++;

                VoronoiChunkKey key = new VoronoiChunkKey(
                    chunkCoord.x, chunkCoord.y, chunkCoord.z,
                    lodLevel, portalConfigHash);

                ChunkData cachedData = chunkCache.get(key);

                if (cachedData != null) {
                    cacheHits++;
                    mergeChunkIntoBucketMap(cachedData.getBucketMap(), bucketMap);
                } else {
                    cacheMisses++;
                    java.util.Map<BucketKey, EdgeBucket> chunkBuckets =
                        calculateSingleChunk(request, chunkCoord, lodLevel, chunkSize);

                    if (chunkBuckets == null) {
                        return false;
                    }

                    mergeChunkIntoBucketMap(chunkBuckets, bucketMap);
                    ChunkData newData = new ChunkData(chunkBuckets);
                    chunkCache.put(key, newData);
                }
            }
        }

        long endTime = System.currentTimeMillis();
        int totalSegments = bucketMap.values().stream().mapToInt(b -> b.segments.size()).sum();
        double hitRate = totalChunks > 0 ? (cacheHits * 100.0 / totalChunks) : 0.0;

        System.out.println("[VoronoiChunk] Calculation complete: " +
            "portals=" + portalCount +
            ", chunks=" + totalChunks +
            ", hits=" + cacheHits +
            ", misses=" + cacheMisses +
            ", hitRate=" + String.format("%.1f%%", hitRate) +
            ", segments=" + totalSegments +
            ", buckets=" + bucketMap.size() +
            ", time=" + (endTime - startTime) + "ms" +
            ", " + chunkCache.getStatistics());

        return true;
    }

    /**
     * Merges edge buckets from a chunk into the global bucket map.
     * This combines segments from multiple chunks without duplicates.
     *
     * @param chunkBuckets The bucket map from a single chunk
     * @param globalBuckets The global bucket map to merge into
     */
    private void mergeChunkIntoBucketMap(
            java.util.Map<BucketKey, EdgeBucket> chunkBuckets,
            java.util.Map<BucketKey, EdgeBucket> globalBuckets) {

        for (java.util.Map.Entry<BucketKey, EdgeBucket> entry : chunkBuckets.entrySet()) {
            BucketKey key = entry.getKey();
            EdgeBucket chunkBucket = entry.getValue();

            // Get or create bucket in global map
            EdgeBucket globalBucket = globalBuckets.computeIfAbsent(key,
                k -> new EdgeBucket(chunkBucket.primaryColor, chunkBucket.secondaryColor,
                                   chunkBucket.portal1Index, chunkBucket.portal2Index,
                                   chunkBucket.group));

            // Add all segments from chunk bucket to global bucket
            // Note: EdgeBucket.segments is a List, and we're adding all segments
            // Duplicate detection happens at the edge hashing level during calculation
            for (EdgeSegment segment : chunkBucket.segments) {
                globalBucket.segments.add(segment);
            }
        }
    }

    /**
     * Get the current LOD preset configuration
     */
    private static LodPreset getCurrentLodPreset() {
        return PortalManager.getInstance().getLodPreset();
    }

    /**
     * Get the LOD spacing array for the current preset
     */
    private static int[] getLodSpacing() {
        return getCurrentLodPreset().getLodSpacing();
    }

    /**
     * Get the base LOD radii array for the current preset
     */
    private static int[] getBaseLodRadii() {
        return getCurrentLodPreset().getBaseLodRadii();
    }

    /**
     * Get the final LOD level for the current preset
     */
    private static int getFinalLodLevel() {
        return getLodSpacing().length;
    }

    private static int[] buildLodRadii(int lod0Max) {
        int[] lodSpacing = getLodSpacing();
        int[] baseLodRadii = getBaseLodRadii();

        int clamped = Math.max(4, Math.min(MAX_BORDER_DISTANCE, lod0Max));
        int[] radii = new int[lodSpacing.length];
        radii[0] = clamped;
        for (int i = 1; i < radii.length; i++) {
            int baseIndex = Math.min(i - 1, baseLodRadii.length - 1);
            int candidate = baseLodRadii.length > 0 ? baseLodRadii[baseIndex] : 0;
            radii[i] = Math.max(candidate, radii[i - 1]);
        }
        return radii;
    }

    private static int getSpacingForLod(int lodLevel) {
        int[] lodSpacing = getLodSpacing();
        if (lodLevel < 0) {
            return lodSpacing[0];
        }
        if (lodLevel >= lodSpacing.length) {
            return lodSpacing[lodSpacing.length - 1];
        }
        return lodSpacing[lodLevel];
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

        // Compare in current dimension coordinates
        // The portals are already translated to current dimension coordinates
        // Use the current dimension's search radius for portal linking
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
        chunkCache.invalidateAll();
        cachedPortalConfigHash = 0L;
    }

    /**
     * Check if portal configuration has changed and invalidate cache if needed.
     * This detects changes in:
     * - Portal positions (add/remove/move)
     * - Portal colors
     * - Portal hidden states
     * - Neutral borders setting
     */
    private void checkAndInvalidateCacheIfNeeded(ResourceKey<Level> currentDim, ResourceKey<Level> sourceDim) {
        PortalManager portalManager = PortalManager.getInstance();

        // Build arrays for hash calculation
        Set<PortalInfo> sourcePortals = portalManager.getPortalsInDimension(sourceDim);
        List<PortalInfo> visibleAndHiddenPortals = new ArrayList<>();
        for (PortalInfo portal : sourcePortals) {
            visibleAndHiddenPortals.add(portal);
        }

        // Handle simulate portal held
        boolean simulateHeld = portalManager.isSimulatePortalHeld();
        int extraPortals = simulateHeld ? 1 : 0;

        // Build arrays for hash calculation
        int count = visibleAndHiddenPortals.size() + extraPortals;
        if (count == 0) {
            // No portals, hash is 0
            if (cachedPortalConfigHash != 0L) {
                System.out.println("[VoronoiChunk] No portals detected, invalidating cache");
                chunkCache.invalidateAll();
                cachedPortalConfigHash = 0L;
            }
            return;
        }

        Vec3[] portalPositions = new Vec3[count];
        Vector3f[] portalColors = new Vector3f[count];
        boolean[] hiddenStates = new boolean[count];

        int i = 0;
        for (PortalInfo portal : visibleAndHiddenPortals) {
            Vec3 pos = sourceDim.equals(currentDim) ? portal.getCenterPos() : portal.getTranslatedPos();
            portalPositions[i] = pos;
            portalColors[i] = portalManager.getPortalColor(portal);
            hiddenStates[i] = portalManager.isPortalHidden(portal);
            i++;
        }

        // Add simulated portal if held
        if (simulateHeld) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 playerPos = mc.player != null ? mc.player.position() : Vec3.ZERO;
            portalPositions[i] = playerPos;
            portalColors[i] = new Vector3f(1.0f, 1.0f, 1.0f);
            hiddenStates[i] = false;
        }

        // Calculate hash
        boolean neutralBorders = portalManager.isNeutralBordersEnabled();
        long newHash = PortalConfigHasher.calculatePortalConfigHash(
            portalPositions, portalColors, hiddenStates, neutralBorders);

        // Check if hash changed
        if (cachedPortalConfigHash != newHash) {
            System.out.println("[VoronoiChunk] Portal configuration changed (hash: " +
                Long.toHexString(cachedPortalConfigHash) + " -> " + Long.toHexString(newHash) +
                "), invalidating cache");
            chunkCache.invalidateAll();
            cachedPortalConfigHash = newHash;
        }
    }

    /**
     * Called by PortalManager when neutral borders setting changes.
     * Invalidates all cached chunks since border visibility changes.
     */
    public void invalidateCacheForNeutralBordersChange() {
        System.out.println("[VoronoiChunk] Neutral borders setting changed, invalidating cache");
        chunkCache.invalidateAll();
        cachedPortalConfigHash = 0L; // Force recalculation of hash
    }

    /**
     * Called by PortalManager when vertical borders setting changes.
     * Invalidates all cached chunks since border visibility changes.
     */
    public void invalidateCacheForVerticalBordersChange() {
        System.out.println("[VoronoiChunk] Vertical borders setting changed, invalidating cache");
        chunkCache.invalidateAll();
        cachedPortalConfigHash = 0L; // Force recalculation of hash
    }

    /**
     * Called by PortalManager when LOD0 distance changes.
     * Currently invalidates all chunks. Could be optimized to only invalidate
     * affected LOD levels in the future.
     */
    public void invalidateCacheForLod0DistanceChange() {
        System.out.println("[VoronoiChunk] LOD0 distance changed, invalidating cache");
        chunkCache.invalidateAll();
        cachedPortalConfigHash = 0L; // Force recalculation of hash
    }

    /**
     * Represents a single line segment (just geometry)
     */
    private static class EdgeSegment {
        final Vec3 start;
        final Vec3 end;
        final int spacing;
        final boolean alwaysRender;
        final int lodLevel;
        final int hash;

        EdgeSegment(Vec3 start, Vec3 end, int spacing, int lodLevel) {
            this.start = start;
            this.end = end;
            this.spacing = spacing;
            this.alwaysRender = spacing == 1;
            this.lodLevel = lodLevel;
            this.hash = hashEdge(start, end, spacing);
        }
    }

    /**
     * Represents a bucket of edges that share the same portal pair and group
     * This allows us to batch render and calculate color once per bucket
     */
    static class EdgeBucket {
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

        void addSegment(Vec3 start, Vec3 end, int spacing, int lodLevel) {
            segments.add(new EdgeSegment(start, end, spacing, lodLevel));
        }
    }

    /**
     * Key for identifying unique edge buckets
     */
    static class BucketKey {
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
     *
     * @param chunkMinX Minimum X bound for chunk (null = use sphere bounds)
     * @param chunkMaxX Maximum X bound for chunk (null = use sphere bounds)
     * @param chunkMinY Minimum Y bound for chunk (null = use sphere bounds)
     * @param chunkMaxY Maximum Y bound for chunk (null = use sphere bounds)
     * @param chunkMinZ Minimum Z bound for chunk (null = use sphere bounds)
     * @param chunkMaxZ Maximum Z bound for chunk (null = use sphere bounds)
     */
    private boolean calculateVoronoiZonesRectangular(RecalcRequest request,
                                                     double[] portalX, double[] portalY, double[] portalZ,
                                                     int minRadius, int maxRadius, int spacing, int lodLevel,
                                                     boolean useDistanceFilter,
                                                     java.util.Map<BucketKey, EdgeBucket> bucketMap,
                                                     Integer chunkMinX, Integer chunkMaxX,
                                                     Integer chunkMinY, Integer chunkMaxY,
                                                     Integer chunkMinZ, Integer chunkMaxZ) {
        Vec3 playerPos = request.playerPos;

        int minX, maxX, minY, maxY, minZ, maxZ;
        if (chunkMinX != null && chunkMaxX != null && chunkMinY != null &&
            chunkMaxY != null && chunkMinZ != null && chunkMaxZ != null) {
            // Align chunk bounds to spacing grid
            minX = (int) Math.floor((double) chunkMinX / spacing) * spacing;
            maxX = (int) Math.ceil((double) chunkMaxX / spacing) * spacing;
            minY = (int) Math.floor((double) chunkMinY / spacing) * spacing;
            maxY = (int) Math.ceil((double) chunkMaxY / spacing) * spacing;
            minZ = (int) Math.floor((double) chunkMinZ / spacing) * spacing;
            maxZ = (int) Math.ceil((double) chunkMaxZ / spacing) * spacing;

            if (useDistanceFilter) {
                // Calculate bounds from sphere (old behavior)
                int sphereMinX = (int) Math.floor((playerPos.x - maxRadius) / spacing) * spacing;
                int sphereMaxX = (int) Math.ceil((playerPos.x + maxRadius) / spacing) * spacing;
                int sphereMinY = Math.max((int) Math.floor((playerPos.y - maxRadius) / spacing) * spacing, WORLD_MIN_Y);
                int sphereMaxY = Math.min((int) Math.ceil((playerPos.y + maxRadius) / spacing) * spacing, WORLD_MAX_Y);
                int sphereMinZ = (int) Math.floor((playerPos.z - maxRadius) / spacing) * spacing;
                int sphereMaxZ = (int) Math.ceil((playerPos.z + maxRadius) / spacing) * spacing;

                // Intersect with sphere bounds to avoid calculating outside the LOD range
                minX = Math.max(minX, sphereMinX);
                maxX = Math.min(maxX, sphereMaxX);
                minY = Math.max(minY, sphereMinY);
                maxY = Math.min(maxY, sphereMaxY);
                minZ = Math.max(minZ, sphereMinZ);
                maxZ = Math.min(maxZ, sphereMaxZ);
            }
        } else {
            // No chunk bounds - use sphere bounds (backward compatibility)
            int sphereMinX = (int) Math.floor((playerPos.x - maxRadius) / spacing) * spacing;
            int sphereMaxX = (int) Math.ceil((playerPos.x + maxRadius) / spacing) * spacing;
            int sphereMinY = Math.max((int) Math.floor((playerPos.y - maxRadius) / spacing) * spacing, WORLD_MIN_Y);
            int sphereMaxY = Math.min((int) Math.ceil((playerPos.y + maxRadius) / spacing) * spacing, WORLD_MAX_Y);
            int sphereMinZ = (int) Math.floor((playerPos.z - maxRadius) / spacing) * spacing;
            int sphereMaxZ = (int) Math.ceil((playerPos.z + maxRadius) / spacing) * spacing;

            minX = sphereMinX;
            maxX = sphereMaxX;
            minY = sphereMinY;
            maxY = sphereMaxY;
            minZ = sphereMinZ;
            maxZ = sphereMaxZ;
        }

        // Check if intersection is empty (chunk entirely outside sphere bounds)
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            // Chunk is entirely outside the distance range - nothing to calculate
            return true;
        }

        int xCount = ((maxX - minX) / spacing) + 1;
        int yCount = ((maxY - minY) / spacing) + 1;
        int zCount = ((maxZ - minZ) / spacing) + 1;
        int[] nearestPortalIdx = new int[xCount * yCount * zCount];
        Arrays.fill(nearestPortalIdx, -1);

        // For each sample point, find the linked portal
        for (int x = minX, ix = 0; x <= maxX; x += spacing, ix++) {
            for (int y = minY, iy = 0; y <= maxY; y += spacing, iy++) {
                for (int z = minZ, iz = 0; z <= maxZ; z += spacing, iz++) {
                    if (shouldCancelRequest(request)) {
                        return false;
                    }
                    int index = ((ix * yCount) + iy) * zCount + iz;
                    if (useDistanceFilter) {
                        double dx = x - playerPos.x;
                        double dy = y - playerPos.y;
                        double dz = z - playerPos.z;
                        double distSq = dx * dx + dy * dy + dz * dz;

                        // Use 3D distance check
                        if (distSq < (double) minRadius * minRadius || distSq > (double) maxRadius * maxRadius) {
                            nearestPortalIdx[index] = SKIP_INDEX;
                            continue;
                        }
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
                    if (shouldCancelRequest(request)) {
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
                            request.portalCenters, request.portalColors, request.showNeutralBorders, request.showVerticalBorders, lodLevel);
                    }
                    if (iy + 1 < yCount) {
                        int neighborIdx = (ix * yCount + (iy + 1)) * zCount + iz;
                        checkNeighborAndAddToBucket(bucketMap, nearestPortalIdx, index, neighborIdx,
                            x, y, z, x, y + spacing, z, spacing,
                            request.portalCenters, request.portalColors, request.showNeutralBorders, request.showVerticalBorders, lodLevel);
                    }
                    if (iz + 1 < zCount) {
                        int neighborIdx = (ix * yCount + iy) * zCount + (iz + 1);
                        checkNeighborAndAddToBucket(bucketMap, nearestPortalIdx, index, neighborIdx,
                            x, y, z, x, y, z + spacing, spacing,
                            request.portalCenters, request.portalColors, request.showNeutralBorders, request.showVerticalBorders, lodLevel);
                    }
                }
            }
        }

        return true;
    }

    /**
     * Calculate Voronoi edges for a single chunk at a specific LOD level.
     * This is a wrapper around calculateVoronoiZonesRectangular that converts
     * chunk coordinates to world-space boundaries and sets up the calculation.
     *
     * @param request The recalculation request containing portal data
     * @param chunkCoord The chunk coordinate to calculate
     * @param lodLevel The LOD level (0, 1, or 2) determining spacing
     * @param chunkSize The size of the chunk in blocks (typically 128)
     * @return Map of edge buckets for this chunk, or null if calculation was cancelled
     */
    private java.util.Map<BucketKey, EdgeBucket> calculateSingleChunk(
            RecalcRequest request,
            ChunkCoord chunkCoord,
            int lodLevel,
            int chunkSize) {

        // Get world-space bounds for this chunk
        int[] bounds = ChunkBoundaryCalculator.getChunkBounds(chunkCoord, chunkSize);
        int minX = bounds[0];
        int maxX = bounds[1];
        int minY = bounds[2];
        int maxY = bounds[3];
        int minZ = bounds[4];
        int maxZ = bounds[5];

        // Prepare portal position arrays
        int portalCount = request.portalCenters.length;
        double[] portalX = new double[portalCount];
        double[] portalY = new double[portalCount];
        double[] portalZ = new double[portalCount];
        for (int i = 0; i < portalCount; i++) {
            Vec3 portalPos = request.portalCenters[i];
            portalX[i] = portalPos.x;
            portalY[i] = portalPos.y;
            portalZ[i] = portalPos.z;
        }

        // Get LOD spacing
        int spacing = getSpacingForLod(lodLevel);

        // Get LOD radii to determine minRadius and maxRadius for this LOD level
        int[] lodRadii = buildLodRadii(PortalManager.getInstance().getLod0Distance());
        int maxDistance = calculateMaxDistance(request.portalTranslated, request.playerPos);

        int minRadius = 0;
        int maxRadius = lodLevel < lodRadii.length ? Math.min(lodRadii[lodLevel], maxDistance) : maxDistance;

        // Apply overlap for LOD levels > 0
        if (lodLevel > 0 && lodLevel < lodRadii.length && spacing > 0) {
            // Find the maxRadius from the previous LOD level
            for (int i = 0; i < lodLevel; i++) {
                int prevMaxRadius = Math.min(lodRadii[i], maxDistance);
                if (prevMaxRadius > minRadius) {
                    minRadius = prevMaxRadius;
                }
            }
            // Extend inward by one spacing unit for overlap
            minRadius = Math.max(0, minRadius - spacing);
        }

        // Create bucket map for this chunk
        java.util.Map<BucketKey, EdgeBucket> bucketMap = new java.util.HashMap<>();

        // Calculate Voronoi edges within chunk bounds
        boolean success = calculateVoronoiZonesRectangular(
            request, portalX, portalY, portalZ,
            minRadius, maxRadius, spacing, lodLevel, false, bucketMap,
            minX, maxX, minY, maxY, minZ, maxZ);

        if (!success) {
            return null; // Calculation was cancelled
        }

        return bucketMap;
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
                                             boolean showNeutralBorders, boolean showVerticalBorders, int lodLevel) {
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
                spacing, 0, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x, center.y + minA, center.z + maxA, center.x, center.y + maxA, center.z + maxA,
                spacing, 1, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x, center.y + minA, center.z + minA, center.x, center.y + minA, center.z + maxA,
                spacing, 2, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x, center.y + maxA, center.z + minA, center.x, center.y + maxA, center.z + maxA,
                spacing, 3, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
        } else if (Math.abs(direction.y) > 0.1 && Math.abs(direction.x) < 0.1 && Math.abs(direction.z) < 0.1) {
            // Border normal along Y axis - draw lines along X and Z
            addSegmentToBucket(bucketMap, center.x + minA, center.y, center.z + minA, center.x + maxA, center.y, center.z + minA,
                spacing, 0, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x + minA, center.y, center.z + maxA, center.x + maxA, center.y, center.z + maxA,
                spacing, 1, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x + minA, center.y, center.z + minA, center.x + minA, center.y, center.z + maxA,
                spacing, 2, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x + maxA, center.y, center.z + minA, center.x + maxA, center.y, center.z + maxA,
                spacing, 3, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
        } else if (Math.abs(direction.z) > 0.1 && Math.abs(direction.x) < 0.1 && Math.abs(direction.y) < 0.1) {
            // Border normal along Z axis - draw lines along X and Y
            addSegmentToBucket(bucketMap, center.x + minA, center.y + minA, center.z, center.x + maxA, center.y + minA, center.z,
                spacing, 0, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x + minA, center.y + maxA, center.z, center.x + maxA, center.y + maxA, center.z,
                spacing, 1, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x + minA, center.y + minA, center.z, center.x + minA, center.y + maxA, center.z,
                spacing, 2, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
            addSegmentToBucket(bucketMap, center.x + maxA, center.y + minA, center.z, center.x + maxA, center.y + maxA, center.z,
                spacing, 3, primaryColor, secondaryColor, portal1Index, portal2Index, lodLevel);
        }
    }

    /**
     * Helper method to add a line segment to the appropriate bucket with pseudo-random group assignment
     */
    private void addSegmentToBucket(java.util.Map<BucketKey, EdgeBucket> bucketMap,
                                    double x1, double y1, double z1, double x2, double y2, double z2,
                                    int spacing, int geometricGroup, Vector3f primaryColor, Vector3f secondaryColor,
                                    int portal1Index, int portal2Index, int lodLevel) {
        // Create segment positions
        Vec3 start = new Vec3(x1, y1, z1);
        Vec3 end = new Vec3(x2, y2, z2);

        // Use hash-based pseudo-random group assignment for more organic-looking patterns
        // This prevents visible grid patterns when render skipping occurs
        int assignedGroup = computePseudoRandomGroup(start, end, portal1Index, portal2Index, geometricGroup, spacing);

        // Get or create bucket for this (group, portal_pair)
        BucketKey key = new BucketKey(assignedGroup, portal1Index, portal2Index);
        EdgeBucket bucket = bucketMap.computeIfAbsent(key,
            k -> new EdgeBucket(primaryColor, secondaryColor, portal1Index, portal2Index, assignedGroup));

        // Add segment to bucket
        bucket.addSegment(start, end, spacing, lodLevel);
    }

    /**
     * Compute a pseudo-random group (0-7) based on line segment coordinates and portal indices.
     * This creates a more organic distribution of groups rather than geometric patterns.
     * Uses a deterministic hash so each line always gets the same group (no flickering).
     *
     * Normalizes coordinates by spacing to ensure consistent group distribution across all LOD levels.
     */
    private static int computePseudoRandomGroup(Vec3 start, Vec3 end, int portal1, int portal2, int geometricGroup, int spacing) {
        // Normalize coordinates by spacing to ensure consistent distribution across LOD levels
        // This prevents clustering of higher LOD segments into the same groups
        int x1 = (int) Math.floor(start.x / spacing);
        int y1 = (int) Math.floor(start.y / spacing);
        int z1 = (int) Math.floor(start.z / spacing);
        int x2 = (int) Math.floor(end.x / spacing);
        int y2 = (int) Math.floor(end.y / spacing);
        int z2 = (int) Math.floor(end.z / spacing);

        // Use a strong mixing function (based on MurmurHash3's finalizer)
        // This creates more random-looking patterns than FNV-1a
        long hash = 0;
        hash = hash * 31 + x1;
        hash = hash * 31 + y1;
        hash = hash * 31 + z1;
        hash = hash * 31 + x2;
        hash = hash * 31 + y2;
        hash = hash * 31 + z2;
        hash = hash * 31 + portal1;
        hash = hash * 31 + portal2;
        hash = hash * 31 + geometricGroup;

        // MurmurHash3 finalizer - provides excellent bit mixing
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;

        // Map to group 0-7
        return (int) (Math.abs(hash) % 8);
    }


    /**
     * Determine if a segment should be rendered based on preset, group, phase, and distance
     * Uses 3-phase rotation to ensure all lines are eventually rendered
     */
    private static boolean shouldRenderSegment(EdgeSegment segment, Vec3 camPos, int group,
                                               PortalManager.LineRenderPreset preset, long phase,
                                               int lod0Distance, int[] lodRadii) {
        // LOD 0 (spacing == 1) always renders every line
        double maxBorderDistance = PortalManager.getInstance().getBorderDrawDistance();
        double midX = (segment.start.x + segment.end.x) * 0.5;
        double midY = (segment.start.y + segment.end.y) * 0.5;
        double midZ = (segment.start.z + segment.end.z) * 0.5;
        double dx = midX - camPos.x;
        double dy = midY - camPos.y;
        double dz = midZ - camPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double distance = Math.sqrt(distSq);

        if (!isSegmentInLodRange(segment, distance, lodRadii)) {
            return false;
        }

        if (segment.alwaysRender) {
            return true;
        }

        // Don't render if beyond border draw distance
        if (distSq > maxBorderDistance * maxBorderDistance) {
            return false;
        }

        // Apply preset-based rendering logic
        switch (preset) {
            case FULL:
                // Render all lines
                return true;

            case MEDIUM:
                // Render 50% of lines using 8-phase rotation (4 out of 8 groups)
                // Each phase renders 4 consecutive groups, rotating which ones
                int mediumOffset = (int)phase % 8;
                int mediumRelativeGroup = (group - mediumOffset + 8) % 8;
                return mediumRelativeGroup < 4;

            case LOW:
                // Render 12.5% of lines (1 out of 8 groups) using 8-phase rotation
                // Each phase renders a different group
                return group == (int)phase;

            case PHASED:
                // Within LOD 0: render all lines (100%)
                if (distance <= lod0Distance) {
                    return true;
                }

                // Beyond LOD 0: discrete density steps based on distance
                // Divide the range from lod0Distance to maxBorderDistance into zones
                double fadeRange = maxBorderDistance - lod0Distance;
                if (fadeRange <= 0.0) {
                    return true; // Edge case: render all if no fade range
                }

                double distanceBeyondLod0 = distance - lod0Distance;
                double t = distanceBeyondLod0 / fadeRange;
                t = Math.max(0.0, Math.min(1.0, t)); // Clamp to [0, 1]

                // Map to density zones:
                // 0.0 - 0.33: 75% (3 out of 4 groups)
                // 0.33 - 0.67: 50% (2 out of 4 groups)
                // 0.67 - 1.0: 25% (1 out of 4 groups)
                int numGroups;
                if (t < 0.33) {
                    numGroups = 3; // 75%
                } else if (t < 0.67) {
                    numGroups = 2; // 50%
                } else {
                    numGroups = 1; // 25%
                }

                // Use 3-phase rotation to select which groups to render
                return shouldRenderGroupForDensity(group, (int)phase, numGroups);

            default:
                return true;
        }
    }

    /**
     * Determine if a group should be rendered given the phase and target number of groups
     */
    private static boolean shouldRenderGroupForDensity(int group, int phase, int numGroups) {
        switch (numGroups) {
            case 8: // 100% - all groups
                return true;

            case 6: // 75% - 6 out of 8 groups, rotating which two are excluded
                // Each phase excludes a different pair of groups
                int excludedPair = phase % 4;
                int excluded1 = excludedPair * 2;
                int excluded2 = excludedPair * 2 + 1;
                return group != excluded1 && group != excluded2;

            case 4: // 50% - 4 out of 8 groups
                // Each phase renders 4 consecutive groups, rotating
                int offset4 = phase % 8;
                int relativeGroup4 = (group - offset4 + 8) % 8;
                return relativeGroup4 < 4;

            case 2: // 25% - 2 out of 8 groups
                // Each phase renders 2 consecutive groups, rotating
                int offset2 = (phase % 4) * 2;
                int relativeGroup2 = (group - offset2 + 8) % 8;
                return relativeGroup2 < 2;

            case 1: // 12.5% - 1 out of 8 groups, rotating which one
                return group == (phase % 8);

            default:
                return false;
        }
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
            pendingRequest = request;
            latestQueuedRequest = request;
            recalcLock.notify();
        }
    }

    private static boolean isSegmentInLodRange(EdgeSegment segment, double distance, int[] lodRadii) {
        if (segment.lodLevel <= 0) {
            return distance <= lodRadii[0];
        }
        if (segment.lodLevel < lodRadii.length) {
            // Account for overlap: segments extend inward by one spacing unit
            // This matches the overlap created during generation (effectiveMinRadius = minRadius - spacing)
            double minDist = Math.max(0, lodRadii[segment.lodLevel - 1] - segment.spacing);
            return distance > minDist && distance <= lodRadii[segment.lodLevel];
        }
        return distance > lodRadii[lodRadii.length - 1];
    }

    private boolean shouldCancelRequest(RecalcRequest request) {
        RecalcRequest latest = latestQueuedRequest;
        if (latest == null || latest.id == request.id) {
            return false;
        }
        if (!latest.currentDim.equals(request.currentDim)) {
            return true;
        }
        if (!latest.sourceDim.equals(request.sourceDim)) {
            return true;
        }
        if (latest.simulateHeld != request.simulateHeld) {
            return true;
        }
        if (latest.useLinkingAlgorithm != request.useLinkingAlgorithm) {
            return true;
        }
        if (latest.showNeutralBorders != request.showNeutralBorders) {
            return true;
        }
        return latest.showVerticalBorders != request.showVerticalBorders;
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

            // Create bucket map and perform calculation using chunked approach
            // Old data stays visible in cachedBuckets until calculation is complete
            java.util.Map<BucketKey, EdgeBucket> bucketMap = new java.util.HashMap<>();

            // Use the new chunked calculation approach
            boolean success = recalculateVoronoiChunked(request, bucketMap);

            if (!success) {
                // Calculation was cancelled - don't update cache
                System.out.println("[VoronoiChunk] Recalculation cancelled, keeping old data visible");
                continue;
            }

            // Convert bucketMap to array of lists organized by group
            List<EdgeBucket>[] bucketsByGroup = createEmptyBuckets();
            for (EdgeBucket bucket : bucketMap.values()) {
                bucketsByGroup[bucket.group].add(bucket);
            }

            // Atomic swap - update all cached data at once
            // This ensures no flickering: old data visible until new data ready
            cachedBuckets.set(bucketsByGroup);
            cachedDimension = request.currentDim;
            cachedSourceDimension = request.sourceDim;
            cachedPlayerPos = request.playerPos;
            cachedSimulateHeld = request.simulateHeld;

            System.out.println("[VoronoiChunk] Recalculation complete, new data now visible");
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
