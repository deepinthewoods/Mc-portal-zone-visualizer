package com.portalzone.portal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages portal detection and tracking across dimensions
 */
public class PortalManager {
    private static final PortalManager INSTANCE = new PortalManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "portal-zone-visualizer.json";

    private final Path configPath;

    // Store portals by dimension
    private final Map<ResourceKey<Level>, Set<PortalInfo>> portalsByDimension = new ConcurrentHashMap<>();

    // Store persisted portals (loaded from config and saved across sessions)
    private final Map<ResourceKey<Level>, Map<UUID, PortalInfo>> persistedPortals = new ConcurrentHashMap<>();

    // Store simulated portals (manual entries, persisted across sessions)
    private final Map<ResourceKey<Level>, Map<UUID, PortalInfo>> simulatedPortals = new ConcurrentHashMap<>();

    // Track which chunks have been scanned
    private final Map<ResourceKey<Level>, Map<ChunkPos, ScanMode>> scannedChunks = new ConcurrentHashMap<>();

    private static final int COARSE_SCAN_STEP = 3;
    private static final int MAX_CHUNK_SCANS_PER_TICK = 1;
    private final Deque<ChunkScanTask> scanQueue = new ArrayDeque<>();
    private final Map<ResourceKey<Level>, Map<ChunkPos, ScanMode>> pendingScans = new ConcurrentHashMap<>();

    // Flag to indicate portals have changed (for Voronoi recalculation)
    private boolean portalsChanged = true;

    // Store custom names for portals (persistent across rescans)
    private final Map<UUID, String> portalNames = new ConcurrentHashMap<>();

    // Store custom hues for portals (persistent across rescans)
    private final Map<UUID, Float> portalHues = new ConcurrentHashMap<>();

    // Store hidden portals (excluded from border calculations)
    private final Set<UUID> hiddenPortals = ConcurrentHashMap.newKeySet();

    private static final Vector3f SIMULATED_PORTAL_COLOR = new Vector3f(1.0f, 1.0f, 1.0f);

    private boolean flipBordersHeld = false;
    private boolean simulatePortalHeld = false;
    private boolean portalDiscoveryEnabled = true;

    // Depth testing settings
    private boolean portalMarkersAlwaysVisible = true; // Default: always visible
    private boolean bordersAlwaysVisible = false; // Default: respect occlusion
    private boolean showNeutralBorders = true; // Default: show grey borders
    private float minimumMarkerScreenPercent = 2.0f; // Default: 2% of long screen side
    private LineRenderPreset lineRenderPreset = LineRenderPreset.FULL; // Default: render all lines
    private int lod0Distance = 64; // Default: LOD 0 radius in blocks
    private com.portalzone.voronoi.VoronoiCalculator.LodPreset lodPreset = com.portalzone.voronoi.VoronoiCalculator.LodPreset.HEAVY; // Default: HEAVY

    /**
     * Line rendering presets
     */
    public enum LineRenderPreset {
        FULL("Full"),
        MEDIUM("Medium"),
        LOW("Low"),
        PHASED("Phased");

        private final String displayName;

        LineRenderPreset(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public LineRenderPreset next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum ScanMode {
        COARSE,
        FULL
    }

    private static class ChunkScanTask {
        private final ResourceKey<Level> dimension;
        private final ChunkPos chunkPos;
        private final ScanMode mode;

        private ChunkScanTask(ResourceKey<Level> dimension, ChunkPos chunkPos, ScanMode mode) {
            this.dimension = dimension;
            this.chunkPos = chunkPos;
            this.mode = mode;
        }
    }

    // Draw distance settings
    private static final double INFINITE_DRAW_DISTANCE = -1.0; // Special value for infinite distance
    private double portalMarkerDrawDistance = INFINITE_DRAW_DISTANCE; // Default: infinite
    private double borderDrawDistance = 2048.0; // Default: 2048 blocks

    private PortalManager() {
        this.configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        loadSettings();
    }

    public static PortalManager getInstance() {
        return INSTANCE;
    }

    /**
     * Update portal tracking for the current game state
     */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        // Process queued chunk scans for the current dimension
        processScanQueue(level);

        // TODO: Scan chunks in the other dimension
        // This requires loading chunks from the other dimension
        // We'll implement this in the cross-dimension scanning task
    }

    public void handleChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (!portalDiscoveryEnabled) {
            return;
        }
        queueChunkScan(level.dimension(), chunk.getPos(), ScanMode.COARSE);
    }

    public void handleChunkUnload(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        Map<ChunkPos, ScanMode> scanned = scannedChunks.get(dimension);
        if (scanned != null) {
            scanned.remove(chunkPos);
        }
        Map<ChunkPos, ScanMode> pending = pendingScans.get(dimension);
        if (pending != null) {
            pending.remove(chunkPos);
        }
    }

    private void processScanQueue(ClientLevel level) {
        if (!portalDiscoveryEnabled) {
            return;
        }

        int remaining = MAX_CHUNK_SCANS_PER_TICK;
        ResourceKey<Level> dimension = level.dimension();

        while (remaining > 0) {
            ChunkScanTask task = scanQueue.pollFirst();
            if (task == null) {
                return;
            }

            Map<ChunkPos, ScanMode> pending = pendingScans.get(task.dimension);
            if (pending == null) {
                continue;
            }

            ScanMode pendingMode = pending.get(task.chunkPos);
            if (pendingMode != task.mode) {
                continue;
            }

            if (!dimension.equals(task.dimension)) {
                pending.remove(task.chunkPos);
                continue;
            }

            if (!level.hasChunk(task.chunkPos.x, task.chunkPos.z)) {
                pending.remove(task.chunkPos);
                continue;
            }

            Map<ChunkPos, ScanMode> scanned = scannedChunks.computeIfAbsent(task.dimension, k -> new ConcurrentHashMap<>());
            ScanMode scannedMode = scanned.get(task.chunkPos);
            if (isScanSatisfied(scannedMode, task.mode)) {
                pending.remove(task.chunkPos);
                continue;
            }

            LevelChunk chunk = level.getChunk(task.chunkPos.x, task.chunkPos.z);
            if (chunk == null) {
                pending.remove(task.chunkPos);
                continue;
            }

            int yStep = task.mode == ScanMode.COARSE ? COARSE_SCAN_STEP : 1;
            boolean foundPortal = scanChunk(level, chunk, task.dimension, yStep);
            markChunkScanned(scanned, task.chunkPos, task.mode);
            pending.remove(task.chunkPos);
            remaining--;

            if (task.mode == ScanMode.COARSE && foundPortal) {
                queueChunkScan(task.dimension, task.chunkPos, ScanMode.FULL);
            }
        }
    }

    private boolean isScanSatisfied(ScanMode scannedMode, ScanMode requestedMode) {
        if (scannedMode == null) {
            return false;
        }
        if (scannedMode == ScanMode.FULL) {
            return true;
        }
        return requestedMode == ScanMode.COARSE;
    }

    private void markChunkScanned(Map<ChunkPos, ScanMode> scanned, ChunkPos chunkPos, ScanMode mode) {
        if (mode == ScanMode.FULL) {
            scanned.put(chunkPos, ScanMode.FULL);
            return;
        }
        scanned.putIfAbsent(chunkPos, ScanMode.COARSE);
    }

    public void queueChunkScan(ResourceKey<Level> dimension, ChunkPos chunkPos, ScanMode mode) {
        if (!portalDiscoveryEnabled) {
            return;
        }

        Map<ChunkPos, ScanMode> scanned = scannedChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        ScanMode scannedMode = scanned.get(chunkPos);
        if (isScanSatisfied(scannedMode, mode)) {
            return;
        }

        Map<ChunkPos, ScanMode> pending = pendingScans.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        ScanMode pendingMode = pending.get(chunkPos);
        if (pendingMode == ScanMode.FULL) {
            return;
        }

        if (pendingMode == null || (pendingMode == ScanMode.COARSE && mode == ScanMode.FULL)) {
            pending.put(chunkPos, mode);
            scanQueue.addLast(new ChunkScanTask(dimension, chunkPos, mode));
        }
    }

    private void queueLoadedChunksAroundPlayer(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;
        int chunkRadius = mc.options.renderDistance().get();
        ResourceKey<Level> dimension = level.dimension();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int chunkX = playerChunkX + dx;
                int chunkZ = playerChunkZ + dz;
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                queueChunkScan(dimension, new ChunkPos(chunkX, chunkZ), ScanMode.COARSE);
            }
        }
    }

    /**
     * Scan a single chunk for portal blocks
     */
    private boolean scanChunk(ClientLevel level, LevelChunk chunk, ResourceKey<Level> dimension, int yStep) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int maxZ = chunkPos.getMaxBlockZ();

        Set<BlockPos> processedPositions = new HashSet<>();
        boolean foundPortal = false;

        // Scan the chunk
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = level.getMinY(); y < level.getMaxY(); y += yStep) {
                    BlockPos pos = new BlockPos(x, y, z);

                    if (processedPositions.contains(pos)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.NETHER_PORTAL)) {
                        // Found a portal block, try to identify the portal structure
                        PortalInfo portal = identifyPortal(level, pos, processedPositions);
                        if (portal != null) {
                            addPortal(dimension, portal);
                            foundPortal = true;
                        }
                    }
                }
            }
        }

        // Validate persisted portals in this chunk
        validatePersistedPortalsInChunk(level, chunkPos, dimension);
        return foundPortal;
    }

    /**
     * Validate persisted portals in a chunk
     * Checks if portal blocks still exist and marks portals as valid/invalid accordingly
     */
    private void validatePersistedPortalsInChunk(ClientLevel level, ChunkPos chunkPos, ResourceKey<Level> dimension) {
        Map<UUID, PortalInfo> dimensionPersistedPortals = persistedPortals.get(dimension);
        if (dimensionPersistedPortals == null || dimensionPersistedPortals.isEmpty()) {
            return;
        }

        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int maxZ = chunkPos.getMaxBlockZ();

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, PortalInfo> entry : dimensionPersistedPortals.entrySet()) {
            PortalInfo portal = entry.getValue();
            BlockPos portalPos = portal.position;

            // Check if portal is in this chunk
            if (portalPos.getX() < minX || portalPos.getX() > maxX ||
                portalPos.getZ() < minZ || portalPos.getZ() > maxZ) {
                continue;
            }

            // Check if portal blocks still exist
            boolean portalExists = isPortalStillValid(level, portal);

            if (portalExists) {
                // Portal is valid, update timestamp
                portal.setValid(true);
                portal.setLastValidated(System.currentTimeMillis());
            } else {
                // Portal no longer exists, mark for removal
                portal.setValid(false);
                toRemove.add(entry.getKey());
                portalsChanged = true;
            }
        }

        // Remove invalid portals
        for (UUID uuid : toRemove) {
            dimensionPersistedPortals.remove(uuid);
        }

        // Save changes if any portals were removed
        if (!toRemove.isEmpty()) {
            saveSettingsNow();
        }
    }

    /**
     * Check if a portal still exists at its stored location
     */
    private boolean isPortalStillValid(ClientLevel level, PortalInfo portal) {
        BlockPos centerPos = portal.position;

        // Check if there's a portal block at the center position
        BlockState centerState = level.getBlockState(centerPos);
        if (!centerState.is(Blocks.NETHER_PORTAL)) {
            return false;
        }

        // Verify the portal orientation matches
        PortalInfo.Axis currentAxis = getPortalAxis(level, centerPos);
        if (currentAxis != portal.orientation) {
            return false;
        }

        // Portal exists with matching orientation
        return true;
    }

    /**
     * Identify a complete portal structure starting from a portal block
     */
    private PortalInfo identifyPortal(ClientLevel level, BlockPos startPos, Set<BlockPos> processedPositions) {
        // Find the bottom-center of the portal frame
        BlockPos bottomPos = findPortalBottom(level, startPos);
        if (bottomPos == null) {
            processedPositions.add(startPos);
            return null;
        }

        // Determine portal orientation and size
        PortalInfo.Axis axis = getPortalAxis(level, bottomPos);
        if (axis == null) {
            processedPositions.add(startPos);
            return null;
        }

        // Find the leftmost portal block so width is measured consistently
        BlockPos minPos = findPortalMinAlongAxis(level, bottomPos, axis);
        BlockPos maxPos = findPortalMaxAlongAxis(level, bottomPos, axis);

        // Measure portal dimensions
        int width = axis == PortalInfo.Axis.X
            ? (maxPos.getX() - minPos.getX() + 1)
            : (maxPos.getZ() - minPos.getZ() + 1);
        int height = measurePortalHeight(level, minPos);

        if (width < 2 || height < 3) {
            processedPositions.add(startPos);
            return null;
        }

        // Mark all portal blocks as processed
        markPortalProcessed(level, minPos, axis, width, height, processedPositions);

        // Calculate center position
        BlockPos centerPos = calculatePortalCenter(minPos, axis, width, height);

        return new PortalInfo(centerPos, level.dimension(), axis, width, height);
    }

    /**
     * Find the bottom of the portal
     */
    private BlockPos findPortalBottom(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mutable = pos.mutable();

        // Go down until we hit non-portal block
        while (mutable.getY() > level.getMinY() && level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
            mutable.move(0, -1, 0);
        }

        // Move back up one block to the bottom portal block
        mutable.move(0, 1, 0);

        if (level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
            return mutable.immutable();
        }

        return null;
    }

    /**
     * Determine the portal's axis orientation
     */
    private PortalInfo.Axis getPortalAxis(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.NETHER_PORTAL)) {
            return null;
        }

        boolean hasXNeighbor = level.getBlockState(pos.east()).is(Blocks.NETHER_PORTAL)
            || level.getBlockState(pos.west()).is(Blocks.NETHER_PORTAL);
        boolean hasZNeighbor = level.getBlockState(pos.north()).is(Blocks.NETHER_PORTAL)
            || level.getBlockState(pos.south()).is(Blocks.NETHER_PORTAL);

        if (hasXNeighbor && !hasZNeighbor) {
            return PortalInfo.Axis.X;
        }
        if (hasZNeighbor && !hasXNeighbor) {
            return PortalInfo.Axis.Z;
        }

        // Fall back to the axis property if neighbors are ambiguous
        try {
            net.minecraft.core.Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
            return axis == net.minecraft.core.Direction.Axis.X ? PortalInfo.Axis.X : PortalInfo.Axis.Z;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Find the leftmost portal block along the width axis
     */
    private BlockPos findPortalMinAlongAxis(ClientLevel level, BlockPos bottomPos, PortalInfo.Axis axis) {
        BlockPos.MutableBlockPos mutable = bottomPos.mutable();

        while (level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
            if (axis == PortalInfo.Axis.X) {
                mutable.move(-1, 0, 0);
            } else {
                mutable.move(0, 0, -1);
            }
        }

        if (axis == PortalInfo.Axis.X) {
            mutable.move(1, 0, 0);
        } else {
            mutable.move(0, 0, 1);
        }

        return mutable.immutable();
    }

    /**
     * Find the rightmost portal block along the width axis
     */
    private BlockPos findPortalMaxAlongAxis(ClientLevel level, BlockPos bottomPos, PortalInfo.Axis axis) {
        BlockPos.MutableBlockPos mutable = bottomPos.mutable();

        while (level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
            if (axis == PortalInfo.Axis.X) {
                mutable.move(1, 0, 0);
            } else {
                mutable.move(0, 0, 1);
            }
        }

        if (axis == PortalInfo.Axis.X) {
            mutable.move(-1, 0, 0);
        } else {
            mutable.move(0, 0, -1);
        }

        return mutable.immutable();
    }

    /**
     * Measure portal height
     */
    private int measurePortalHeight(ClientLevel level, BlockPos bottomPos) {
        int height = 0;
        BlockPos.MutableBlockPos mutable = bottomPos.mutable();

        while (level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
            height++;
            mutable.move(0, 1, 0);
        }

        return height;
    }

    /**
     * Mark all blocks in a portal as processed
     */
    private void markPortalProcessed(ClientLevel level, BlockPos bottomPos, PortalInfo.Axis axis,
                                      int width, int height, Set<BlockPos> processedPositions) {
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                BlockPos pos;
                if (axis == PortalInfo.Axis.X) {
                    pos = bottomPos.offset(w, h, 0);
                } else {
                    pos = bottomPos.offset(0, h, w);
                }
                processedPositions.add(pos);
            }
        }
    }

    /**
     * Calculate the center position of a portal
     */
    private BlockPos calculatePortalCenter(BlockPos bottomPos, PortalInfo.Axis axis, int width, int height) {
        int centerW = width / 2;
        int centerH = height / 2;

        if (axis == PortalInfo.Axis.X) {
            return bottomPos.offset(centerW, centerH, 0);
        } else {
            return bottomPos.offset(0, centerH, centerW);
        }
    }

    /**
     * Add a portal to the tracking system
     */
    private void addPortal(ResourceKey<Level> dimension, PortalInfo portal) {
        Set<PortalInfo> portals = portalsByDimension.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet());

        if (portals.add(portal)) {
            portalsChanged = true;

            // Also add to persisted portals for cross-session tracking
            Map<UUID, PortalInfo> dimensionPersistedPortals = persistedPortals.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
            dimensionPersistedPortals.put(portal.uuid, portal);

            // Save settings to persist the new portal
            saveSettingsNow();
        }
    }

    /**
     * Get all portals in a specific dimension
     * Returns both live-scanned portals and persisted portals (merged, only valid ones)
     */
    public Set<PortalInfo> getPortalsInDimension(ResourceKey<Level> dimension) {
        Set<PortalInfo> result = new HashSet<>();

        // Add live-scanned portals
        Set<PortalInfo> livePortals = portalsByDimension.get(dimension);
        if (livePortals != null) {
            result.addAll(livePortals);
        }

        // Add persisted portals (only valid ones not already in live set)
        Map<UUID, PortalInfo> dimensionPersistedPortals = persistedPortals.get(dimension);
        if (dimensionPersistedPortals != null) {
            for (PortalInfo portal : dimensionPersistedPortals.values()) {
                // Only include valid portals
                if (portal.isValid()) {
                    result.add(portal);
                }
            }
        }

        // Add simulated portals
        Map<UUID, PortalInfo> dimensionSimulatedPortals = simulatedPortals.get(dimension);
        if (dimensionSimulatedPortals != null) {
            result.addAll(dimensionSimulatedPortals.values());
        }

        return result;
    }

    /**
     * Get all portals across all dimensions
     */
    public Collection<PortalInfo> getAllPortals() {
        List<PortalInfo> allPortals = new ArrayList<>();
        for (Set<PortalInfo> portals : portalsByDimension.values()) {
            allPortals.addAll(portals);
        }
        return allPortals;
    }

    /**
     * Check if portals have changed (for Voronoi recalculation)
     */
    public boolean hasPortalsChanged() {
        return portalsChanged;
    }

    /**
     * Mark portals as processed (after Voronoi recalculation)
     */
    public void clearChangedFlag() {
        portalsChanged = false;
    }

    /**
     * Clear all tracked portals (e.g., when changing worlds)
     */
    public void clear() {
        portalsByDimension.clear();
        persistedPortals.clear();
        simulatedPortals.clear();
        scannedChunks.clear();
        pendingScans.clear();
        scanQueue.clear();
        portalNames.clear();
        portalHues.clear();
        hiddenPortals.clear();
        portalsChanged = true;
    }

    /**
     * Set a custom name for a portal
     */
    public void setPortalName(UUID portalUuid, String name) {
        if (name == null || name.trim().isEmpty()) {
            portalNames.remove(portalUuid);
        } else {
            portalNames.put(portalUuid, name.trim());
        }
    }

    /**
     * Get the custom name for a portal, or null if not set
     */
    public String getPortalName(UUID portalUuid) {
        return portalNames.get(portalUuid);
    }

    /**
     * Get the display name for a portal (custom name if set, otherwise short ID)
     */
    public String getPortalDisplayName(PortalInfo portal) {
        String customName = portalNames.get(portal.uuid);
        return customName != null ? customName : portal.getShortId();
    }

    /**
     * Set a custom hue for a portal
     */
    public void setPortalHue(UUID portalUuid, float hue) {
        if (isSimulatedPortal(portalUuid)) {
            return;
        }
        float clamped = Math.max(0.0f, Math.min(360.0f, hue));
        portalHues.put(portalUuid, clamped);
        portalsChanged = true;
    }

    /**
     * Get the hue for a portal (custom hue if set, otherwise default)
     */
    public float getPortalHue(PortalInfo portal) {
        if (portal.isSimulated()) {
            return 0.0f;
        }
        Float custom = portalHues.get(portal.uuid);
        return custom != null ? custom : portal.getBaseHue();
    }

    /**
     * Get the color for a portal (custom hue if set, otherwise default)
     */
    public Vector3f getPortalColor(PortalInfo portal) {
        if (portal.isSimulated()) {
            return SIMULATED_PORTAL_COLOR;
        }
        return PortalInfo.colorFromHue(getPortalHue(portal));
    }

    public boolean isPortalHidden(PortalInfo portal) {
        return hiddenPortals.contains(portal.uuid);
    }

    public void setPortalHidden(UUID portalUuid, boolean hidden) {
        if (hidden) {
            hiddenPortals.add(portalUuid);
        } else {
            hiddenPortals.remove(portalUuid);
        }
        portalsChanged = true;
    }

    /**
     * Set whether portal markers should always be visible (no depth testing)
     */
    public void setPortalMarkersAlwaysVisible(boolean alwaysVisible) {
        if (this.portalMarkersAlwaysVisible == alwaysVisible) {
            return;
        }
        this.portalMarkersAlwaysVisible = alwaysVisible;
        saveSettingsNow();
    }

    /**
     * Get whether portal markers should always be visible (no depth testing)
     */
    public boolean isPortalMarkersAlwaysVisible() {
        return portalMarkersAlwaysVisible;
    }

    /**
     * Set whether borders should always be visible (no depth testing)
     */
    public void setBordersAlwaysVisible(boolean alwaysVisible) {
        if (this.bordersAlwaysVisible == alwaysVisible) {
            return;
        }
        this.bordersAlwaysVisible = alwaysVisible;
        saveSettingsNow();
    }

    /**
     * Get whether borders should always be visible (no depth testing)
     */
    public boolean isBordersAlwaysVisible() {
        return bordersAlwaysVisible;
    }

    public void setPortalDiscoveryEnabled(boolean enabled) {
        if (this.portalDiscoveryEnabled == enabled) {
            return;
        }
        this.portalDiscoveryEnabled = enabled;
        if (!enabled) {
            pendingScans.clear();
            scanQueue.clear();
        } else {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                queueLoadedChunksAroundPlayer(level);
            }
        }
        saveSettingsNow();
    }

    public boolean isPortalDiscoveryEnabled() {
        return portalDiscoveryEnabled;
    }

    /**
     * Set whether neutral (grey) borders should be rendered
     */
    public void setNeutralBordersEnabled(boolean enabled) {
        if (this.showNeutralBorders == enabled) {
            return;
        }
        this.showNeutralBorders = enabled;
        portalsChanged = true;
        // Invalidate chunk cache since neutral borders affect border visibility
        com.portalzone.voronoi.VoronoiCalculator.getInstance().invalidateCacheForNeutralBordersChange();
        saveSettingsNow();
    }

    /**
     * Get whether neutral (grey) borders should be rendered
     */
    public boolean isNeutralBordersEnabled() {
        return showNeutralBorders;
    }


    /**
     * Set the minimum portal marker screen size (percent of long screen side)
     */
    public void setMinimumMarkerScreenPercent(float percent) {
        if (!Float.isFinite(percent)) {
            if (this.minimumMarkerScreenPercent != 2.0f) {
                this.minimumMarkerScreenPercent = 2.0f;
                saveSettingsNow();
            }
            return;
        }
        float clamped = Math.max(0.0f, Math.min(20.0f, percent));
        if (Float.compare(this.minimumMarkerScreenPercent, clamped) == 0) {
            return;
        }
        this.minimumMarkerScreenPercent = clamped;
        saveSettingsNow();
    }

    /**
     * Get the minimum portal marker screen size (percent of long screen side)
     */
    public float getMinimumMarkerScreenPercent() {
        return minimumMarkerScreenPercent;
    }

    /**
     * Set the line render preset.
     */
    public void setLineRenderPreset(LineRenderPreset preset) {
        if (this.lineRenderPreset == preset) {
            return;
        }
        this.lineRenderPreset = preset;
        saveSettingsNow();
    }

    /**
     * Get the line render preset.
     */
    public LineRenderPreset getLineRenderPreset() {
        return lineRenderPreset;
    }

    /**
     * Set the distance (blocks) where LOD 0 ends.
     */
    public void setLod0Distance(int distance) {
        int clamped = Math.max(4, Math.min(256, distance));
        if (this.lod0Distance == clamped) {
            return;
        }
        this.lod0Distance = clamped;
        // Invalidate chunk cache since LOD0 distance affects which chunks are calculated
        com.portalzone.voronoi.VoronoiCalculator.getInstance().invalidateCacheForLod0DistanceChange();
        saveSettingsNow();
    }

    /**
     * Get the distance (blocks) where LOD 0 ends.
     */
    public int getLod0Distance() {
        return lod0Distance;
    }

    /**
     * Set the LOD preset.
     */
    public void setLodPreset(com.portalzone.voronoi.VoronoiCalculator.LodPreset preset) {
        if (this.lodPreset == preset) {
            return;
        }
        this.lodPreset = preset;
        // Invalidate chunk cache since LOD preset affects which chunks are calculated
        com.portalzone.voronoi.VoronoiCalculator.getInstance().invalidateCacheForLod0DistanceChange();
        saveSettingsNow();
    }

    /**
     * Get the LOD preset.
     */
    public com.portalzone.voronoi.VoronoiCalculator.LodPreset getLodPreset() {
        return lodPreset;
    }

    /**
     * Set the portal marker draw distance (in blocks).
     * Use -1 for infinite distance.
     */
    public void setPortalMarkerDrawDistance(double distance) {
        if (Double.compare(this.portalMarkerDrawDistance, distance) == 0) {
            return;
        }
        this.portalMarkerDrawDistance = distance;
        saveSettingsNow();
    }

    /**
     * Get the portal marker draw distance (in blocks).
     * Returns -1 for infinite distance.
     */
    public double getPortalMarkerDrawDistance() {
        return portalMarkerDrawDistance;
    }

    /**
     * Check if portal marker draw distance is infinite.
     */
    public boolean isPortalMarkerDrawDistanceInfinite() {
        return portalMarkerDrawDistance == INFINITE_DRAW_DISTANCE;
    }

    /**
     * Set the border draw distance (in blocks).
     */
    public void setBorderDrawDistance(double distance) {
        double clamped = Math.max(16.0, Math.min(2048.0, distance));
        if (Double.compare(this.borderDrawDistance, clamped) == 0) {
            return;
        }
        this.borderDrawDistance = clamped;
        saveSettingsNow();
    }

    /**
     * Get the border draw distance (in blocks).
     */
    public double getBorderDrawDistance() {
        return borderDrawDistance;
    }

    /**
     * Clear all portals in the current dimension (removes from live and persisted)
     */
    public void clearCurrentDimension(ResourceKey<Level> dimension) {
        Set<PortalInfo> portalsInDimension = getPortalsInDimension(dimension);
        if (!portalsInDimension.isEmpty()) {
            Set<UUID> toRemove = new HashSet<>();
            for (PortalInfo portal : portalsInDimension) {
                toRemove.add(portal.uuid);
            }
            hiddenPortals.removeAll(toRemove);
        }

        // Clear live portals
        portalsByDimension.remove(dimension);

        // Clear persisted portals
        persistedPortals.remove(dimension);

        // Clear simulated portals
        simulatedPortals.remove(dimension);

        // Clear scanned chunks
        scannedChunks.remove(dimension);
        pendingScans.remove(dimension);

        portalsChanged = true;
        saveSettingsNow();
    }

    /**
     * Clear all portals in all dimensions
     */
    public void clearAllPortals() {
        portalsByDimension.clear();
        persistedPortals.clear();
        simulatedPortals.clear();
        scannedChunks.clear();
        pendingScans.clear();
        scanQueue.clear();
        hiddenPortals.clear();
        portalsChanged = true;
        saveSettingsNow();
    }

    private void loadSettings() {
        if (!Files.exists(configPath)) {
            return;
        }

        try (var reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject names = root.getAsJsonObject("portalNames");
            if (names != null) {
                for (Map.Entry<String, JsonElement> entry : names.entrySet()) {
                    portalNames.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
                }
            }

            JsonObject hues = root.getAsJsonObject("portalHues");
            if (hues != null) {
                for (Map.Entry<String, JsonElement> entry : hues.entrySet()) {
                    portalHues.put(UUID.fromString(entry.getKey()), entry.getValue().getAsFloat());
                }
            }

            JsonObject hidden = root.getAsJsonObject("hiddenPortals");
            if (hidden != null) {
                for (Map.Entry<String, JsonElement> entry : hidden.entrySet()) {
                    if (entry.getValue().getAsBoolean()) {
                        hiddenPortals.add(UUID.fromString(entry.getKey()));
                    }
                }
            }

            // Load depth testing settings
            if (root.has("portalMarkersAlwaysVisible")) {
                portalMarkersAlwaysVisible = root.get("portalMarkersAlwaysVisible").getAsBoolean();
            }
            if (root.has("bordersAlwaysVisible")) {
                bordersAlwaysVisible = root.get("bordersAlwaysVisible").getAsBoolean();
            }
            if (root.has("showNeutralBorders")) {
                showNeutralBorders = root.get("showNeutralBorders").getAsBoolean();
            }
            if (root.has("minimumMarkerScreenPercent")) {
                setMinimumMarkerScreenPercent(root.get("minimumMarkerScreenPercent").getAsFloat());
            }
            // Load line render preset (new system)
            if (root.has("lineRenderPreset")) {
                try {
                    String presetName = root.get("lineRenderPreset").getAsString();
                    lineRenderPreset = LineRenderPreset.valueOf(presetName);
                } catch (Exception e) {
                    lineRenderPreset = LineRenderPreset.FULL;
                }
            } else {
                // Backwards compatibility: convert old skip values to presets
                float closeSkip = root.has("closeLineSkip") ? root.get("closeLineSkip").getAsFloat() : 0.0f;
                float farSkip = root.has("farLineSkip") ? root.get("farLineSkip").getAsFloat() : 0.0f;
                // If using legacy borderFuzzThreshold, map it to farSkip
                if (root.has("borderFuzzThreshold") && !root.has("farLineSkip")) {
                    farSkip = root.get("borderFuzzThreshold").getAsFloat();
                }
                // Convert to preset: if both are 0, use FULL; otherwise use LOW (closest match)
                if (closeSkip <= 0.01f && farSkip <= 0.01f) {
                    lineRenderPreset = LineRenderPreset.FULL;
                } else {
                    lineRenderPreset = LineRenderPreset.LOW;
                }
            }
            if (root.has("lod0Distance")) {
                setLod0Distance(root.get("lod0Distance").getAsInt());
            } else if (root.has("borderFuzzStartDistance")) {
                setLod0Distance(root.get("borderFuzzStartDistance").getAsInt());
            }
            if (root.has("lodPreset")) {
                try {
                    String presetName = root.get("lodPreset").getAsString();
                    lodPreset = com.portalzone.voronoi.VoronoiCalculator.LodPreset.valueOf(presetName);
                } catch (Exception e) {
                    lodPreset = com.portalzone.voronoi.VoronoiCalculator.LodPreset.HEAVY;
                }
            }
            if (root.has("portalMarkerDrawDistance")) {
                setPortalMarkerDrawDistance(root.get("portalMarkerDrawDistance").getAsDouble());
            }
            if (root.has("borderDrawDistance")) {
                setBorderDrawDistance(root.get("borderDrawDistance").getAsDouble());
            }
            if (root.has("portalDiscoveryEnabled")) {
                portalDiscoveryEnabled = root.get("portalDiscoveryEnabled").getAsBoolean();
            }

            // Load persisted portals
            JsonObject portalsJson = root.getAsJsonObject("portals");
            if (portalsJson != null) {
                for (Map.Entry<String, JsonElement> dimensionEntry : portalsJson.entrySet()) {
                    try {
                        // Parse dimension key
                        ResourceLocation dimLocation = ResourceLocation.parse(dimensionEntry.getKey());
                        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLocation);

                        // Parse portals for this dimension
                        JsonObject dimensionPortals = dimensionEntry.getValue().getAsJsonObject();
                        Map<UUID, PortalInfo> portalMap = new ConcurrentHashMap<>();

                        for (Map.Entry<String, JsonElement> portalEntry : dimensionPortals.entrySet()) {
                            try {
                                UUID portalUuid = UUID.fromString(portalEntry.getKey());
                                JsonObject portalJson = portalEntry.getValue().getAsJsonObject();
                                PortalInfo portal = PortalInfo.fromJson(portalJson);
                                portalMap.put(portalUuid, portal);
                            } catch (Exception e) {
                                // Silently skip failed portal loads
                            }
                        }

                        persistedPortals.put(dimension, portalMap);
                    } catch (Exception e) {
                        // Silently skip failed dimension loads
                    }
                }
            }

            // Load simulated portals
            JsonObject simulatedJson = root.getAsJsonObject("simulatedPortals");
            if (simulatedJson != null) {
                for (Map.Entry<String, JsonElement> dimensionEntry : simulatedJson.entrySet()) {
                    try {
                        ResourceLocation dimLocation = ResourceLocation.parse(dimensionEntry.getKey());
                        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLocation);

                        JsonObject dimensionPortals = dimensionEntry.getValue().getAsJsonObject();
                        Map<UUID, PortalInfo> portalMap = new ConcurrentHashMap<>();

                        for (Map.Entry<String, JsonElement> portalEntry : dimensionPortals.entrySet()) {
                            try {
                                UUID portalUuid = UUID.fromString(portalEntry.getKey());
                                JsonObject portalJson = portalEntry.getValue().getAsJsonObject();
                                PortalInfo portal = PortalInfo.fromJson(portalJson);
                                portalMap.put(portalUuid, portal);
                            } catch (Exception e) {
                                // Silently skip failed simulated portal loads
                            }
                        }

                        simulatedPortals.put(dimension, portalMap);
                    } catch (Exception e) {
                        // Silently skip failed simulated dimension loads
                    }
                }
            }
        } catch (Exception e) {
            // Silently skip failed settings loads
        }
    }

    public void saveSettingsNow() {
        JsonObject root = new JsonObject();
        JsonObject names = new JsonObject();
        for (Map.Entry<UUID, String> entry : portalNames.entrySet()) {
            names.addProperty(entry.getKey().toString(), entry.getValue());
        }
        root.add("portalNames", names);

        JsonObject hues = new JsonObject();
        for (Map.Entry<UUID, Float> entry : portalHues.entrySet()) {
            hues.addProperty(entry.getKey().toString(), entry.getValue());
        }
        root.add("portalHues", hues);

        JsonObject hidden = new JsonObject();
        for (UUID portalUuid : hiddenPortals) {
            hidden.addProperty(portalUuid.toString(), true);
        }
        root.add("hiddenPortals", hidden);

        // Save depth testing settings
        root.addProperty("portalMarkersAlwaysVisible", portalMarkersAlwaysVisible);
        root.addProperty("bordersAlwaysVisible", bordersAlwaysVisible);
        root.addProperty("showNeutralBorders", showNeutralBorders);
        root.addProperty("minimumMarkerScreenPercent", minimumMarkerScreenPercent);
        root.addProperty("lineRenderPreset", lineRenderPreset.name());
        root.addProperty("lod0Distance", lod0Distance);
        root.addProperty("lodPreset", lodPreset.name());

        // Save draw distance settings
        root.addProperty("portalMarkerDrawDistance", portalMarkerDrawDistance);
        root.addProperty("borderDrawDistance", borderDrawDistance);
        root.addProperty("portalDiscoveryEnabled", portalDiscoveryEnabled);

        // Save persisted portals
        JsonObject portalsJson = new JsonObject();
        for (Map.Entry<ResourceKey<Level>, Map<UUID, PortalInfo>> dimensionEntry : persistedPortals.entrySet()) {
            ResourceKey<Level> dimension = dimensionEntry.getKey();
            Map<UUID, PortalInfo> dimensionPortals = dimensionEntry.getValue();

            JsonObject dimensionJson = new JsonObject();
            for (Map.Entry<UUID, PortalInfo> portalEntry : dimensionPortals.entrySet()) {
                UUID portalUuid = portalEntry.getKey();
                PortalInfo portal = portalEntry.getValue();
                dimensionJson.add(portalUuid.toString(), portal.toJson());
            }

            portalsJson.add(dimension.location().toString(), dimensionJson);
        }
        root.add("portals", portalsJson);

        // Save simulated portals
        JsonObject simulatedJson = new JsonObject();
        for (Map.Entry<ResourceKey<Level>, Map<UUID, PortalInfo>> dimensionEntry : simulatedPortals.entrySet()) {
            ResourceKey<Level> dimension = dimensionEntry.getKey();
            Map<UUID, PortalInfo> dimensionPortals = dimensionEntry.getValue();

            JsonObject dimensionJson = new JsonObject();
            for (Map.Entry<UUID, PortalInfo> portalEntry : dimensionPortals.entrySet()) {
                UUID portalUuid = portalEntry.getKey();
                PortalInfo portal = portalEntry.getValue();
                dimensionJson.add(portalUuid.toString(), portal.toJson());
            }

            simulatedJson.add(dimension.location().toString(), dimensionJson);
        }
        root.add("simulatedPortals", simulatedJson);

        try {
            Files.createDirectories(configPath.getParent());
            try (var writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            // Silently skip failed settings saves
        }
    }

    /**
     * Invalidate chunks (e.g., when blocks change)
     */
    public void invalidateChunk(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        Map<ChunkPos, ScanMode> scanned = scannedChunks.get(dimension);
        if (scanned != null) {
            scanned.remove(chunkPos);
        }
        Map<ChunkPos, ScanMode> pending = pendingScans.get(dimension);
        if (pending != null) {
            pending.remove(chunkPos);
        }

        // Remove portals in this chunk
        Set<PortalInfo> portals = portalsByDimension.get(dimension);
        if (portals != null) {
            portals.removeIf(portal -> {
                ChunkPos portalChunk = new ChunkPos(portal.position);
                return portalChunk.equals(chunkPos);
            });
            portalsChanged = true;
        }

        if (portalDiscoveryEnabled) {
            queueChunkScan(dimension, chunkPos, ScanMode.FULL);
        }
    }

    public void addSimulatedPortal(ResourceKey<Level> dimension, BlockPos position) {
        Map<UUID, PortalInfo> portals = simulatedPortals.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        PortalInfo portal = PortalInfo.createSimulated(position, dimension);
        portals.put(portal.uuid, portal);
        portalsChanged = true;
        saveSettingsNow();
    }

    public void removeSimulatedPortal(UUID portalUuid) {
        boolean removed = false;
        for (Map<UUID, PortalInfo> portals : simulatedPortals.values()) {
            if (portals.remove(portalUuid) != null) {
                removed = true;
                break;
            }
        }
        if (removed) {
            portalNames.remove(portalUuid);
            portalHues.remove(portalUuid);
            hiddenPortals.remove(portalUuid);
            portalsChanged = true;
            saveSettingsNow();
        }
    }

    private boolean isSimulatedPortal(UUID portalUuid) {
        for (Map<UUID, PortalInfo> portals : simulatedPortals.values()) {
            if (portals.containsKey(portalUuid)) {
                return true;
            }
        }
        return false;
    }

    public void setFlipBordersHeld(boolean held) {
        if (this.flipBordersHeld != held) {
            this.flipBordersHeld = held;
            portalsChanged = true;
        }
    }

    public boolean isFlipBordersHeld() {
        return flipBordersHeld;
    }

    public void setSimulatePortalHeld(boolean held) {
        if (this.simulatePortalHeld != held) {
            this.simulatePortalHeld = held;
            portalsChanged = true;
        }
    }

    public boolean isSimulatePortalHeld() {
        return simulatePortalHeld;
    }
}
