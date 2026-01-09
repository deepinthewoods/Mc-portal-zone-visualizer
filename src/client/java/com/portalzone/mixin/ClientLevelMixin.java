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
        if (!oldState.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.NETHER_PORTAL)) {
            return;
        }

        manager.invalidateChunk(level.dimension(), new ChunkPos(pos));
    }
}
