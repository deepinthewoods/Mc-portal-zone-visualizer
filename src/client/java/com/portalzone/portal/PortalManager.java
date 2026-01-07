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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3f;

import java.util.*;
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

    // Track which chunks have been scanned
    private final Map<ResourceKey<Level>, Map<ChunkPos, Long>> scannedChunks = new ConcurrentHashMap<>();

    private static final long RESCAN_INTERVAL_TICKS = 200;

    // Flag to indicate portals have changed (for Voronoi recalculation)
    private boolean portalsChanged = true;

    // Store custom names for portals (persistent across rescans)
    private final Map<UUID, String> portalNames = new ConcurrentHashMap<>();

    // Store custom hues for portals (persistent across rescans)
    private final Map<UUID, Float> portalHues = new ConcurrentHashMap<>();

    // Depth testing settings
    private boolean portalMarkersAlwaysVisible = true; // Default: always visible
    private boolean bordersAlwaysVisible = false; // Default: respect occlusion
    private boolean showNeutralBorders = true; // Default: show grey borders
    private boolean showVerticalBorders = false; // Default: no vertical border lines
    private float minimumMarkerScreenPercent = 2.0f; // Default: 2% of long screen side
    private float borderFuzzThreshold = 0.0f; // Default: no fuzz
    private int borderFuzzStartDistance = 64; // Default: start fading at 64 blocks

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

        // Scan chunks in current dimension
        scanLoadedChunks(level);

        // TODO: Scan chunks in the other dimension
        // This requires loading chunks from the other dimension
        // We'll implement this in the cross-dimension scanning task
    }

    /**
     * Scan all loaded chunks in the given level for portals
     */
    private void scanLoadedChunks(ClientLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        Minecraft mc = Minecraft.getInstance();
        long currentTick = level.getGameTime();

        if (mc.player == null) {
            return;
        }

        // Get player chunk position
        BlockPos playerPos = mc.player.blockPosition();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        // Scan chunks in a radius around the player
        int chunkRadius = mc.options.renderDistance().get();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int chunkX = playerChunkX + dx;
                int chunkZ = playerChunkZ + dz;
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

                // Check if chunk is loaded
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                // Try to get the chunk
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                // Skip if recently scanned
                Map<ChunkPos, Long> scanned = scannedChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
                Long lastScannedTick = scanned.get(chunkPos);
                if (lastScannedTick != null && currentTick - lastScannedTick < RESCAN_INTERVAL_TICKS) {
                    continue;
                }

                // Scan this chunk for portals
                scanChunk(level, chunk, dimension);
                scanned.put(chunkPos, currentTick);
            }
        }
    }

    /**
     * Scan a single chunk for portal blocks
     */
    private void scanChunk(ClientLevel level, LevelChunk chunk, ResourceKey<Level> dimension) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int maxZ = chunkPos.getMaxBlockZ();

        Set<BlockPos> processedPositions = new HashSet<>();

        // Scan the chunk
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
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
                        }
                    }
                }
            }
        }

        // Validate persisted portals in this chunk
        validatePersistedPortalsInChunk(level, chunkPos, dimension);
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
        scannedChunks.clear();
        portalNames.clear();
        portalHues.clear();
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
        float clamped = Math.max(0.0f, Math.min(360.0f, hue));
        portalHues.put(portalUuid, clamped);
        portalsChanged = true;
    }

    /**
     * Get the hue for a portal (custom hue if set, otherwise default)
     */
    public float getPortalHue(PortalInfo portal) {
        Float custom = portalHues.get(portal.uuid);
        return custom != null ? custom : portal.getBaseHue();
    }

    /**
     * Get the color for a portal (custom hue if set, otherwise default)
     */
    public Vector3f getPortalColor(PortalInfo portal) {
        return PortalInfo.colorFromHue(getPortalHue(portal));
    }

    /**
     * Set whether portal markers should always be visible (no depth testing)
     */
    public void setPortalMarkersAlwaysVisible(boolean alwaysVisible) {
        this.portalMarkersAlwaysVisible = alwaysVisible;
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
        this.bordersAlwaysVisible = alwaysVisible;
    }

    /**
     * Get whether borders should always be visible (no depth testing)
     */
    public boolean isBordersAlwaysVisible() {
        return bordersAlwaysVisible;
    }

    /**
     * Set whether neutral (grey) borders should be rendered
     */
    public void setNeutralBordersEnabled(boolean enabled) {
        this.showNeutralBorders = enabled;
        portalsChanged = true;
    }

    /**
     * Get whether neutral (grey) borders should be rendered
     */
    public boolean isNeutralBordersEnabled() {
        return showNeutralBorders;
    }

    /**
     * Set whether vertical border lines should be rendered
     */
    public void setVerticalBordersEnabled(boolean enabled) {
        this.showVerticalBorders = enabled;
        portalsChanged = true;
    }

    /**
     * Get whether vertical border lines should be rendered
     */
    public boolean isVerticalBordersEnabled() {
        return showVerticalBorders;
    }

    /**
     * Set the minimum portal marker screen size (percent of long screen side)
     */
    public void setMinimumMarkerScreenPercent(float percent) {
        float clamped = Math.max(0.0f, Math.min(20.0f, percent));
        this.minimumMarkerScreenPercent = clamped;
    }

    /**
     * Get the minimum portal marker screen size (percent of long screen side)
     */
    public float getMinimumMarkerScreenPercent() {
        return minimumMarkerScreenPercent;
    }

    /**
     * Set the maximum border fuzz discard probability (0-1).
     */
    public void setBorderFuzzThreshold(float threshold) {
        this.borderFuzzThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
    }

    /**
     * Get the maximum border fuzz discard probability (0-1).
     */
    public float getBorderFuzzThreshold() {
        return borderFuzzThreshold;
    }

    /**
     * Set the distance (blocks) where border fuzz begins.
     */
    public void setBorderFuzzStartDistance(int distance) {
        this.borderFuzzStartDistance = Math.max(4, Math.min(256, distance));
    }

    /**
     * Get the distance (blocks) where border fuzz begins.
     */
    public int getBorderFuzzStartDistance() {
        return borderFuzzStartDistance;
    }

    /**
     * Set the portal marker draw distance (in blocks).
     * Use -1 for infinite distance.
     */
    public void setPortalMarkerDrawDistance(double distance) {
        this.portalMarkerDrawDistance = distance;
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
        this.borderDrawDistance = Math.max(16.0, Math.min(2048.0, distance));
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
        // Clear live portals
        portalsByDimension.remove(dimension);

        // Clear persisted portals
        persistedPortals.remove(dimension);

        // Clear scanned chunks
        scannedChunks.remove(dimension);

        portalsChanged = true;
        saveSettingsNow();
    }

    /**
     * Clear all portals in all dimensions
     */
    public void clearAllPortals() {
        portalsByDimension.clear();
        persistedPortals.clear();
        scannedChunks.clear();
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
            if (root.has("showVerticalBorders")) {
                showVerticalBorders = root.get("showVerticalBorders").getAsBoolean();
            }
            if (root.has("minimumMarkerScreenPercent")) {
                setMinimumMarkerScreenPercent(root.get("minimumMarkerScreenPercent").getAsFloat());
            }
            if (root.has("borderFuzzThreshold")) {
                setBorderFuzzThreshold(root.get("borderFuzzThreshold").getAsFloat());
            }
            if (root.has("borderFuzzStartDistance")) {
                setBorderFuzzStartDistance(root.get("borderFuzzStartDistance").getAsInt());
            }
            if (root.has("portalMarkerDrawDistance")) {
                setPortalMarkerDrawDistance(root.get("portalMarkerDrawDistance").getAsDouble());
            }
            if (root.has("borderDrawDistance")) {
                setBorderDrawDistance(root.get("borderDrawDistance").getAsDouble());
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
                                System.err.println("[PortalZoneVisualizer] Failed to load portal " + portalEntry.getKey() + ": " + e.getMessage());
                            }
                        }

                        persistedPortals.put(dimension, portalMap);
                    } catch (Exception e) {
                        System.err.println("[PortalZoneVisualizer] Failed to load portals for dimension " + dimensionEntry.getKey() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PortalZoneVisualizer] Failed to load settings: " + e.getMessage());
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

        // Save depth testing settings
        root.addProperty("portalMarkersAlwaysVisible", portalMarkersAlwaysVisible);
        root.addProperty("bordersAlwaysVisible", bordersAlwaysVisible);
        root.addProperty("showNeutralBorders", showNeutralBorders);
        root.addProperty("showVerticalBorders", showVerticalBorders);
        root.addProperty("minimumMarkerScreenPercent", minimumMarkerScreenPercent);
        root.addProperty("borderFuzzThreshold", borderFuzzThreshold);
        root.addProperty("borderFuzzStartDistance", borderFuzzStartDistance);

        // Save draw distance settings
        root.addProperty("portalMarkerDrawDistance", portalMarkerDrawDistance);
        root.addProperty("borderDrawDistance", borderDrawDistance);

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

        try {
            Files.createDirectories(configPath.getParent());
            try (var writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            System.err.println("[PortalZoneVisualizer] Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * Invalidate chunks (e.g., when blocks change)
     */
    public void invalidateChunk(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        Map<ChunkPos, Long> scanned = scannedChunks.get(dimension);
        if (scanned != null) {
            scanned.remove(chunkPos);
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
    }
}
