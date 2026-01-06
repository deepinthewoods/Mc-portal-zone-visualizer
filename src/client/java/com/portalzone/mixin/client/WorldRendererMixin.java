package com.portalzone.mixin.client;

import com.portalzone.render.PortalRenderer;
import net.minecraft.client.renderer.OrderedRenderCommandQueue;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to hook into the world rendering pipeline
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @Inject(
        method = "pushEntityRenders",
        at = @At("TAIL")
    )
    private void portalZoneVisualizer$renderPortals(
        MatrixStack matrices,
        WorldRenderState renderState,
        OrderedRenderCommandQueue queue,
        CallbackInfo ci
    ) {
        PortalRenderer.render(matrices, renderState, queue);
    }
}
