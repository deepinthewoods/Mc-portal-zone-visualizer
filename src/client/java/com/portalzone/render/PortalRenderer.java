package com.portalzone.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.portalzone.PortalZoneVisualizerClient;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import com.portalzone.voronoi.VoronoiCalculator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedRenderCommandQueue;
import net.minecraft.client.renderer.RenderLayer;
import net.minecraft.client.renderer.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Set;

/**
 * Main renderer for portal visualization
 */
public class PortalRenderer {
    private static final int FULLBRIGHT = 0x00F000F0;
    private static final float MARKER_SIZE = 3.0f; // 3 blocks in-game size
    private static final int MIN_PIXEL_SIZE = 20; // Minimum 20 pixels on screen
    private static final int MAX_RENDER_DISTANCE = 256; // Max render distance in blocks

    public static void render(MatrixStack matrices, WorldRenderState renderState, OrderedRenderCommandQueue queue) {
        // Check if rendering is enabled
        if (!PortalZoneVisualizerClient.isRenderingEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        // Get camera position
        Vec3 camPos = mc.gameRenderer.getCamera().getPos();
        ResourceKey<Level> currentDim = mc.level.dimension();

        // Render portals in current dimension (as circles)
        Set<PortalInfo> currentDimPortals = PortalManager.getInstance().getPortalsInDimension(currentDim);
        for (PortalInfo portal : currentDimPortals) {
            double distance = portal.getCenterPos().distanceTo(camPos);
            if (distance > MAX_RENDER_DISTANCE) continue;

            renderPortalCircle(queue, matrices, camPos, portal);
        }

        // Render portals in other dimension (as X marks with translated coordinates)
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        Set<PortalInfo> otherDimPortals = PortalManager.getInstance().getPortalsInDimension(otherDim);
        for (PortalInfo portal : otherDimPortals) {
            Vec3 translatedPos = portal.getTranslatedPos();
            double distance = translatedPos.distanceTo(camPos);
            if (distance > MAX_RENDER_DISTANCE) continue;

            renderPortalX(queue, matrices, camPos, portal, translatedPos);
        }

        // Render Voronoi borders
        VoronoiCalculator.getInstance().render(queue, matrices, camPos, currentDim);
    }

    /**
     * Render a portal as a billboard circle in its actual dimension
     */
    private static void renderPortalCircle(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                           Vec3 camPos, PortalInfo portal) {
        Vec3 worldPos = portal.getCenterPos();
        Vec3 relPos = worldPos.subtract(camPos);
        double distance = relPos.length();

        // Calculate size with minimum pixel size
        float size = calculateBillboardSize(distance, MARKER_SIZE);

        // Get color
        Vector3f color = portal.color;

        // Draw circle as billboard
        drawBillboardCircle(queue, matrices, relPos, size, color.x, color.y, color.z, 0.8f);

        // Draw label below the circle
        Vec3 labelOffset = new Vec3(0, -size * 1.5, 0);
        String displayName = PortalManager.getInstance().getPortalDisplayName(portal);
        renderLabel(queue, matrices, relPos.add(labelOffset), displayName, color, distance);
    }

    /**
     * Render a portal as a billboard X mark with translated coordinates
     */
    private static void renderPortalX(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                      Vec3 camPos, PortalInfo portal, Vec3 translatedPos) {
        Vec3 relPos = translatedPos.subtract(camPos);
        double distance = relPos.length();

        // Calculate size with minimum pixel size
        float size = calculateBillboardSize(distance, MARKER_SIZE);

        // Get color
        Vector3f color = portal.color;

        // Draw X mark as billboard
        drawBillboardX(queue, matrices, relPos, size, color.x, color.y, color.z, 0.8f);

        // Draw label below the X
        Vec3 labelOffset = new Vec3(0, -size * 1.5, 0);
        String displayName = PortalManager.getInstance().getPortalDisplayName(portal);
        renderLabel(queue, matrices, relPos.add(labelOffset), displayName, color, distance);
    }

    /**
     * Calculate billboard size with minimum pixel constraint
     */
    private static float calculateBillboardSize(double distance, float baseSize) {
        // Calculate what size would give us MIN_PIXEL_SIZE pixels at this distance
        // Approximate FOV and screen scaling
        Minecraft mc = Minecraft.getInstance();
        int screenHeight = mc.getWindow().getHeight();
        double fov = mc.options.fov().get();

        // Pixels per block at distance
        double pixelsPerBlock = screenHeight / (2.0 * distance * Math.tan(Math.toRadians(fov / 2.0)));

        // Required size in blocks to achieve MIN_PIXEL_SIZE
        float minSize = (float) (MIN_PIXEL_SIZE / pixelsPerBlock);

        return Math.max(baseSize, minSize);
    }

    /**
     * Draw a billboard circle facing the camera
     */
    private static void drawBillboardCircle(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                            Vec3 center, float size, float r, float g, float b, float a) {
        Minecraft mc = Minecraft.getInstance();
        Quaternionf camRot = mc.gameRenderer.getCamera().rotation();

        // Calculate camera-facing right and up vectors
        Vector3f right = new Vector3f(1, 0, 0).rotate(camRot).mul(size);
        Vector3f up = new Vector3f(0, 1, 0).rotate(camRot).mul(size);

        // Draw circle as approximated polygon
        int segments = 24;
        Vec3 prevPoint = null;

        for (int i = 0; i <= segments; i++) {
            double angle = (i * 2 * Math.PI) / segments;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            Vec3 point = center.add(
                right.x * cos + up.x * sin,
                right.y * cos + up.y * sin,
                right.z * cos + up.z * sin
            );

            if (prevPoint != null) {
                submitLine(queue, matrices, r, g, b, a, FULLBRIGHT,
                    prevPoint.x, prevPoint.y, prevPoint.z,
                    point.x, point.y, point.z);
            }

            prevPoint = point;
        }
    }

    /**
     * Draw a billboard X mark facing the camera
     */
    private static void drawBillboardX(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                       Vec3 center, float size, float r, float g, float b, float a) {
        Minecraft mc = Minecraft.getInstance();
        Quaternionf camRot = mc.gameRenderer.getCamera().rotation();

        // Calculate camera-facing right and up vectors
        Vector3f right = new Vector3f(1, 0, 0).rotate(camRot).mul(size);
        Vector3f up = new Vector3f(0, 1, 0).rotate(camRot).mul(size);

        // Calculate 4 corners
        Vec3 topRight = center.add(right.x + up.x, right.y + up.y, right.z + up.z);
        Vec3 topLeft = center.add(-right.x + up.x, -right.y + up.y, -right.z + up.z);
        Vec3 bottomRight = center.add(right.x - up.x, right.y - up.y, right.z - up.z);
        Vec3 bottomLeft = center.add(-right.x - up.x, -right.y - up.y, -right.z - up.z);

        // Draw X (two diagonals)
        submitLine(queue, matrices, r, g, b, a, FULLBRIGHT,
            topLeft.x, topLeft.y, topLeft.z,
            bottomRight.x, bottomRight.y, bottomRight.z);

        submitLine(queue, matrices, r, g, b, a, FULLBRIGHT,
            topRight.x, topRight.y, topRight.z,
            bottomLeft.x, bottomLeft.y, bottomLeft.z);
    }

    /**
     * Render a text label as a billboard
     */
    private static void renderLabel(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                    Vec3 relPos, String text, Vector3f color, double distance) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Create a PoseStack for text rendering
        PoseStack poseStack = new PoseStack();
        poseStack.translate(relPos.x, relPos.y, relPos.z);

        // Face the camera
        Quaternionf camRot = mc.gameRenderer.getCamera().rotation();
        poseStack.mulPose(camRot);

        // Scale based on distance for readability
        float scale = (float) (0.02f * Math.max(1.0, distance / 20.0));
        poseStack.scale(-scale, -scale, scale);

        // Get buffer source from queue
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // Calculate text width for centering
        int textWidth = font.width(text);

        // Convert color to ARGB format
        int argbColor = 0xFF000000 |
                       ((int)(color.x * 255) << 16) |
                       ((int)(color.y * 255) << 8) |
                       (int)(color.z * 255);

        // Draw the text
        font.drawInBatch(text, -textWidth / 2f, 0, argbColor, false,
                        poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL,
                        0, FULLBRIGHT);

        bufferSource.endBatch();
    }

    /**
     * Submit a line to the render queue
     */
    public static void submitLine(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                   float r, float g, float b, float a, int light,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz) {
        RenderLayer layer = RenderLayer.getLines();
        var bq = queue.getBatchingQueue(1000);
        var cam = Minecraft.getInstance().gameRenderer.getCamera();
        var rot = cam.rotation();
        Vector3f forward = new Vector3f(0f, 0f, -1f).rotate(rot);
        final float nx = forward.x, ny = forward.y, nz = forward.z;

        bq.submitCustom(matrices, layer, (entry, vc) -> {
            vc.vertex(entry, (float)ax, (float)ay, (float)az)
                .color(r, g, b, a)
                .normal(entry, nx, ny, nz);
            vc.vertex(entry, (float)bx, (float)by, (float)bz)
                .color(r, g, b, a)
                .normal(entry, nx, ny, nz);
        });
    }
}
