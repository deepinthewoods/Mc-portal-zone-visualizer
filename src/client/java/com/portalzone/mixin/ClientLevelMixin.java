package com.portalzone.mixin;

import com.portalzone.portal.PortalManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = "setBlock", at = @At("HEAD"))
    private void portalZoneVisualizer$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                 CallbackInfoReturnable<Boolean> cir) {
        PortalManager manager = PortalManager.getInstance();
        if (!manager.isPortalDiscoveryEnabled()) {
            return;
        }

        ClientLevel level = (ClientLevel) (Object) this;
        BlockState oldState = level.getBlockState(pos);

        // Check if this is a portal block change
        boolean isPortalBlockChange = oldState.is(Blocks.NETHER_PORTAL) || state.is(Blocks.NETHER_PORTAL);

        // Check if this is an obsidian block being broken (portal frame destruction)
        boolean isObsidianBreak = oldState.is(Blocks.OBSIDIAN) && !state.is(Blocks.OBSIDIAN);

        if (!isPortalBlockChange && !isObsidianBreak) {
            return;
        }

        // Invalidate the chunk containing the changed block
        ChunkPos chunkPos = new ChunkPos(pos);
        manager.invalidateChunk(level.dimension(), chunkPos);

        // Also invalidate neighboring chunks to catch portals whose center is in an adjacent chunk
        // Portals can span multiple chunks, so we need to check a 3x3 grid
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue; // Already invalidated the center chunk
                ChunkPos neighborPos = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
                manager.invalidateChunk(level.dimension(), neighborPos);
            }
        }
    }
}
