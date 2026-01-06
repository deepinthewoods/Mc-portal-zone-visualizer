package com.portalzone.portal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages portal detection and tracking across dimensions
 */
public class PortalManager {
    private static final PortalManager INSTANCE = new PortalManager();

    // Store portals by dimension
    private final Map<ResourceKey<Level>, Set<PortalInfo>> portalsByDimension = new ConcurrentHashMap<>();

    // Track which chunks have been scanned
    private final Map<ResourceKey<Level>, Set<ChunkPos>> scannedChunks = new ConcurrentHashMap<>();

    // Flag to indicate portals have changed (for Voronoi recalculation)
    private boolean portalsChanged = true;

    // Store custom names for portals (persistent across rescans)
    private final Map<UUID, String> portalNames = new ConcurrentHashMap<>();

    private PortalManager() {
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

        // Get all loaded chunks
        for (LevelChunk chunk : level.getChunkSource().getLoadedChunks()) {
            ChunkPos chunkPos = chunk.getPos();

            // Skip if already scanned
            Set<ChunkPos> scanned = scannedChunks.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet());
            if (scanned.contains(chunkPos)) {
                continue;
            }

            // Scan this chunk for portals
            scanChunk(level, chunk, dimension);
            scanned.add(chunkPos);
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
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
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

        // Measure portal dimensions
        int width = measurePortalWidth(level, bottomPos, axis);
        int height = measurePortalHeight(level, bottomPos);

        if (width < 2 || height < 3) {
            processedPositions.add(startPos);
            return null;
        }

        // Mark all portal blocks as processed
        markPortalProcessed(level, bottomPos, axis, width, height, processedPositions);

        // Calculate center position
        BlockPos centerPos = calculatePortalCenter(bottomPos, axis, width, height);

        return new PortalInfo(centerPos, level.dimension(), axis, width, height);
    }

    /**
     * Find the bottom of the portal
     */
    private BlockPos findPortalBottom(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mutable = pos.mutable();

        // Go down until we hit non-portal block
        while (mutable.getY() > level.getMinBuildHeight() && level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
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

        // Check the axis property
        try {
            net.minecraft.core.Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
            return axis == net.minecraft.core.Direction.Axis.X ? PortalInfo.Axis.X : PortalInfo.Axis.Z;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Measure portal width
     */
    private int measurePortalWidth(ClientLevel level, BlockPos bottomPos, PortalInfo.Axis axis) {
        int width = 0;
        BlockPos.MutableBlockPos mutable = bottomPos.mutable();

        while (level.getBlockState(mutable).is(Blocks.NETHER_PORTAL)) {
            width++;
            if (axis == PortalInfo.Axis.X) {
                mutable.move(1, 0, 0);
            } else {
                mutable.move(0, 0, 1);
            }
        }

        return width;
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
        }
    }

    /**
     * Get all portals in a specific dimension
     */
    public Set<PortalInfo> getPortalsInDimension(ResourceKey<Level> dimension) {
        return portalsByDimension.getOrDefault(dimension, Collections.emptySet());
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
        scannedChunks.clear();
        portalNames.clear();
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
     * Invalidate chunks (e.g., when blocks change)
     */
    public void invalidateChunk(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        Set<ChunkPos> scanned = scannedChunks.get(dimension);
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
