package com.portalzone.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.portalzone.PortalZoneVisualizerClient;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import com.portalzone.voronoi.VoronoiCalculator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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

    public static void render(PoseStack poseStack, Camera camera) {
        // Check if rendering is enabled
        if (!PortalZoneVisualizerClient.isRenderingEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        // Get camera position
        Vec3 camPos = camera.getPosition();
        ResourceKey<Level> currentDim = mc.level.dimension();

        // Get buffer source for rendering
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // Render portals in current dimension (as circles)
        Set<PortalInfo> currentDimPortals = PortalManager.getInstance().getPortalsInDimension(currentDim);
        for (PortalInfo portal : currentDimPortals) {
            double distance = portal.getCenterPos().distanceTo(camPos);
            if (distance > MAX_RENDER_DISTANCE) continue;

            renderPortalCircle(poseStack, bufferSource, camPos, portal, camera);
        }

        // Render portals in other dimension (as X marks with translated coordinates)
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        Set<PortalInfo> otherDimPortals = PortalManager.getInstance().getPortalsInDimension(otherDim);
        for (PortalInfo portal : otherDimPortals) {
            Vec3 translatedPos = portal.getTranslatedPos();
            double distance = translatedPos.distanceTo(camPos);
            if (distance > MAX_RENDER_DISTANCE) continue;

            renderPortalX(poseStack, bufferSource, camPos, portal, translatedPos, camera);
        }

        // Render Voronoi borders
        VoronoiCalculator.getInstance().render(poseStack, bufferSource, camPos, currentDim, camera);

        // End batch to flush all rendering
        bufferSource.endBatch();
    }

    /**
     * Render a portal as a billboard circle in its actual dimension
     */
    private static void renderPortalCircle(PoseStack poseStack, MultiBufferSource bufferSource,
                                           Vec3 camPos, PortalInfo portal, Camera camera) {
        Vec3 worldPos = portal.getCenterPos();
        Vec3 relPos = worldPos.subtract(camPos);
        double distance = relPos.length();

        // Calculate size with minimum pixel size
        float size = calculateBillboardSize(distance, MARKER_SIZE);

        // Get color
        Vector3f color = portal.color;

        // Draw circle as billboard
        drawBillboardCircle(poseStack, bufferSource, relPos, size, color.x, color.y, color.z, 0.8f, camera);

        // Draw label below the circle
        Vec3 labelOffset = new Vec3(0, -size * 1.5, 0);
        String displayName = PortalManager.getInstance().getPortalDisplayName(portal);
        renderLabel(poseStack, bufferSource, relPos.add(labelOffset), displayName, color, distance, camera);
    }

    /**
     * Render a portal as a billboard X mark with translated coordinates
     */
    private static void renderPortalX(PoseStack poseStack, MultiBufferSource bufferSource,
                                      Vec3 camPos, PortalInfo portal, Vec3 translatedPos, Camera camera) {
        Vec3 relPos = translatedPos.subtract(camPos);
        double distance = relPos.length();

        // Calculate size with minimum pixel size
        float size = calculateBillboardSize(distance, MARKER_SIZE);

        // Get color
        Vector3f color = portal.color;

        // Draw X mark as billboard
        drawBillboardX(poseStack, bufferSource, relPos, size, color.x, color.y, color.z, 0.8f, camera);

        // Draw label below the X
        Vec3 labelOffset = new Vec3(0, -size * 1.5, 0);
        String displayName = PortalManager.getInstance().getPortalDisplayName(portal);
        renderLabel(poseStack, bufferSource, relPos.add(labelOffset), displayName, color, distance, camera);
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
    private static void drawBillboardCircle(PoseStack poseStack, MultiBufferSource bufferSource,
                                            Vec3 center, float size, float r, float g, float b, float a, Camera camera) {
        Quaternionf camRot = camera.rotation();

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
                submitLine(poseStack, bufferSource, r, g, b, a, FULLBRIGHT,
                    prevPoint.x, prevPoint.y, prevPoint.z,
                    point.x, point.y, point.z, camRot);
            }

            prevPoint = point;
        }
    }

    /**
     * Draw a billboard X mark facing the camera
     */
    private static void drawBillboardX(PoseStack poseStack, MultiBufferSource bufferSource,
                                       Vec3 center, float size, float r, float g, float b, float a, Camera camera) {
        Quaternionf camRot = camera.rotation();

        // Calculate camera-facing right and up vectors
        Vector3f right = new Vector3f(1, 0, 0).rotate(camRot).mul(size);
        Vector3f up = new Vector3f(0, 1, 0).rotate(camRot).mul(size);

        // Calculate 4 corners
        Vec3 topRight = center.add(right.x + up.x, right.y + up.y, right.z + up.z);
        Vec3 topLeft = center.add(-right.x + up.x, -right.y + up.y, -right.z + up.z);
        Vec3 bottomRight = center.add(right.x - up.x, right.y - up.y, right.z - up.z);
        Vec3 bottomLeft = center.add(-right.x - up.x, -right.y - up.y, -right.z - up.z);

        // Draw X (two diagonals)
        submitLine(poseStack, bufferSource, r, g, b, a, FULLBRIGHT,
            topLeft.x, topLeft.y, topLeft.z,
            bottomRight.x, bottomRight.y, bottomRight.z, camRot);

        submitLine(poseStack, bufferSource, r, g, b, a, FULLBRIGHT,
            topRight.x, topRight.y, topRight.z,
            bottomLeft.x, bottomLeft.y, bottomLeft.z, camRot);
    }

    /**
     * Render a text label as a billboard
     */
    private static void renderLabel(PoseStack poseStack, MultiBufferSource bufferSource,
                                    Vec3 relPos, String text, Vector3f color, double distance, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Save pose stack state
        poseStack.pushPose();
        poseStack.translate(relPos.x, relPos.y, relPos.z);

        // Face the camera
        Quaternionf camRot = camera.rotation();
        poseStack.mulPose(camRot);

        // Scale based on distance for readability
        float scale = (float) (0.02f * Math.max(1.0, distance / 20.0));
        poseStack.scale(-scale, -scale, scale);

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

        // Restore pose stack state
        poseStack.popPose();
    }

    /**
     * Submit a line to the render queue
     */
    public static void submitLine(PoseStack poseStack, MultiBufferSource bufferSource,
                                   float r, float g, float b, float a, int light,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz,
                                   Quaternionf rotation) {
        RenderType renderType = RenderType.lines();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);

        Vector3f forward = new Vector3f(0f, 0f, -1f).rotate(rotation);
        Matrix4f pose = poseStack.last().pose();

        // First vertex
        vertexConsumer.addVertex(pose, (float)ax, (float)ay, (float)az)
                .setColor(r, g, b, a)
                .setNormal(poseStack.last(), forward.x, forward.y, forward.z);

        // Second vertex
        vertexConsumer.addVertex(pose, (float)bx, (float)by, (float)bz)
                .setColor(r, g, b, a)
                .setNormal(poseStack.last(), forward.x, forward.y, forward.z);
    }
}
