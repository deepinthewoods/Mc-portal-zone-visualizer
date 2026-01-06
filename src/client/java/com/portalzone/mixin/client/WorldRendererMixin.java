package com.portalzone.mixin.client;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.portalzone.render.PortalRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to hook into the world rendering pipeline
 */
@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    @Inject(
        method = "renderLevel",
        at = @At("TAIL")
    )
    private void portalZoneVisualizer$renderPortals(
        GraphicsResourceAllocator allocator,
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        org.joml.Matrix4f frustumMatrix,
        org.joml.Matrix4f projectionMatrix,
        org.joml.Matrix4f clipMatrix,
        com.mojang.blaze3d.buffers.GpuBufferSlice fogData,
        org.joml.Vector4f fogColor,
        boolean updateChunksOnly,
        CallbackInfo ci
    ) {
        // Create a new PoseStack for our rendering
        PoseStack poseStack = new PoseStack();
        PortalRenderer.render(poseStack, camera);
    }
}
