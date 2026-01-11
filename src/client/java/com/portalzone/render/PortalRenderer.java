package com.portalzone.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.portalzone.PortalZoneVisualizerClient;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import com.portalzone.voronoi.VoronoiCalculator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Main renderer for portal visualization
 */
public class PortalRenderer {
    private static final int FULLBRIGHT = 0x00F000F0;
    private static final float BORDER_LINE_WIDTH = 3.0f;
    private static final float MARKER_LINE_WIDTH = 8.0f;
    private static final float CONNECTION_LINE_WIDTH = 2.0f;
    private static final Vector3f HIDDEN_PORTAL_COLOR = new Vector3f(0.6f, 0.6f, 0.6f);
    private static final RenderType LINES_DEPTH = createLines(true, BORDER_LINE_WIDTH, "portal_zone_lines");
    private static final RenderType LINES_NO_DEPTH = createLines(false, BORDER_LINE_WIDTH, "portal_zone_lines_no_depth");
    private static final RenderType MARKER_LINES_DEPTH = createLines(true, MARKER_LINE_WIDTH, "portal_zone_marker_lines");
    private static final RenderType MARKER_LINES_NO_DEPTH = createLines(false, MARKER_LINE_WIDTH, "portal_zone_marker_lines_no_depth");
    private static final List<LabelEntry> LABELS = new ArrayList<>();

    // Connection line animation state
    private static double connectionLineAnimOffset = 0;
    private static final int CONNECTION_SEGMENT_LENGTH = 3; // World units per segment

    // Line counting for performance monitoring
    private static int lineCountThisFrame = 0;
    private static int totalLineCount = 0;
    private static int frameCount = 0;
    private static long lastReportTime = System.currentTimeMillis();

    public static void render(PoseStack matrices, Camera camera, MultiBufferSource bufferSource) {
        // Check if rendering is enabled
        if (!PortalZoneVisualizerClient.isRenderingEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        // Reset line count for this frame
        lineCountThisFrame = 0;

        // Get camera position
        Vec3 camPos = camera.getPosition();
        ResourceKey<Level> currentDim = mc.level.dimension();
        LABELS.clear();

        // Render Voronoi borders first (with depth control)
        VoronoiCalculator.getInstance().render(matrices, bufferSource, camPos, currentDim, camera);

        // Render portal connection lines after borders but before markers
        // Connection lines always use NO_DEPTH for visibility through walls
        boolean connectionLinesAlwaysVisible = PortalManager.getInstance().isConnectionLinesAlwaysVisible();

        if (connectionLinesAlwaysVisible) {
            // Flush border buffers before changing depth range
            if (bufferSource instanceof MultiBufferSource.BufferSource source) {
                source.endBatch(LINES_DEPTH);
                source.endBatch(LINES_NO_DEPTH);
            }
            GL11.glDepthRange(0.0, 0.01);
        }

        renderPortalConnectionLines(matrices, bufferSource, camPos, currentDim, camera);

        if (connectionLinesAlwaysVisible) {
            // Flush connection line buffers before restoring depth range
            if (bufferSource instanceof MultiBufferSource.BufferSource source) {
                source.endBatch(LINES_DEPTH);
                source.endBatch(LINES_NO_DEPTH);
            }
            GL11.glDepthRange(0.0, 1.0);
        }

        // Render portal markers with depth control (after borders so they're on top)
        boolean portalMarkersAlwaysVisible = PortalManager.getInstance().isPortalMarkersAlwaysVisible();
        boolean portalMarkersUseDepth = !portalMarkersAlwaysVisible;

        // If markers should always be visible, flush borders first then manipulate depth range
        if (portalMarkersAlwaysVisible) {
            // Flush border buffers before changing depth range
            if (bufferSource instanceof MultiBufferSource.BufferSource source) {
                source.endBatch(LINES_DEPTH);
                source.endBatch(LINES_NO_DEPTH);
            }
            GL11.glDepthRange(0.0, 0.01);
        }

        // Render portals in current dimension
        Set<PortalInfo> currentDimPortals = PortalManager.getInstance().getPortalsInDimension(currentDim);
        for (PortalInfo portal : currentDimPortals) {
            renderPortalMarker(matrices, bufferSource, camPos, portal, portal.getCenterPos(), currentDim,
                camera, portalMarkersUseDepth);
        }

        // Render portals in other dimension (with translated coordinates)
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        Set<PortalInfo> otherDimPortals = PortalManager.getInstance().getPortalsInDimension(otherDim);

        for (PortalInfo portal : otherDimPortals) {
            Vec3 translatedPos = portal.getTranslatedPos();
            renderPortalMarker(matrices, bufferSource, camPos, portal, translatedPos, currentDim,
                camera, portalMarkersUseDepth);
        }

        // Restore normal depth range if it was modified
        if (portalMarkersAlwaysVisible) {
            // Flush marker buffers before restoring depth range
            if (bufferSource instanceof MultiBufferSource.BufferSource source) {
                source.endBatch(MARKER_LINES_DEPTH);
                source.endBatch(MARKER_LINES_NO_DEPTH);
            }
            GL11.glDepthRange(0.0, 1.0);
        }

        // Update line count statistics and report every 2 seconds
        totalLineCount += lineCountThisFrame;
        frameCount++;

        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - lastReportTime;

        if (elapsedTime >= 2000) { // 2 seconds = 2000 milliseconds
            // Reset counters
            totalLineCount = 0;
            frameCount = 0;
            lastReportTime = currentTime;
        }
    }

    /**
     * Render a portal as a billboard circle in its actual dimension
     */
    private static void renderPortalMarker(PoseStack matrices, MultiBufferSource bufferSource,
                                           Vec3 camPos, PortalInfo portal, Vec3 worldPos, ResourceKey<Level> currentDim,
                                           Camera camera, boolean useDepthTest) {
        double distance = worldPos.distanceTo(camPos);

        // Check draw distance
        PortalManager manager = PortalManager.getInstance();
        if (!manager.isPortalMarkerDrawDistanceInfinite()) {
            double maxDistance = manager.getPortalMarkerDrawDistance();
            if (distance > maxDistance) {
                return; // Don't render if beyond draw distance
            }
        }

        // Calculate size with minimum screen percentage
        float halfWidth = portal.width * 0.5f;
        float halfHeight = portal.height * 0.5f;
        Vec2 markerHalfSize = ensureMinimumScreenSize(distance, halfWidth, halfHeight);

        // Get color
        Vector3f color = manager.isPortalHidden(portal)
            ? HIDDEN_PORTAL_COLOR
            : manager.getPortalColor(portal);

        // Use world position directly (PoseStack is already camera-relative)
        Vec3 pos = worldPos;

        // Draw marker shape based on portal type and dimension
        if (portal.isSimulated()) {
            // Simulated portals use a portal frame icon (square/rectangle outline)
            drawBillboardPortalFrame(matrices, bufferSource, pos, markerHalfSize.x, markerHalfSize.y,
                color.x, color.y, color.z, 0.8f, camera, useDepthTest);
        } else if (Level.OVERWORLD.equals(portal.dimension)) {
            drawBillboardEllipse(matrices, bufferSource, pos, markerHalfSize.x, markerHalfSize.y,
                color.x, color.y, color.z, 0.8f, camera, useDepthTest);
        } else {
            boolean useProperNDiagonal = Level.NETHER.equals(currentDim);
            drawBillboardN(matrices, bufferSource, pos, markerHalfSize.x, markerHalfSize.y,
                color.x, color.y, color.z, 0.8f, camera, useDepthTest, useProperNDiagonal);
        }

        // Draw label below the marker
        float labelOffsetY = markerHalfSize.y * 1.5f;
        Vec3 labelOffset = new Vec3(0, -labelOffsetY, 0);
        String displayName = PortalManager.getInstance().getPortalDisplayName(portal);
        queueLabel(pos.add(labelOffset), displayName, color, distance);
    }

    public static void renderLabels(PoseStack matrices, Camera camera) {
        if (!PortalZoneVisualizerClient.isRenderingEnabled()) {
            LABELS.clear();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            LABELS.clear();
            return;
        }

        if (LABELS.isEmpty()) {
            return;
        }

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        for (LabelEntry entry : LABELS) {
            renderLabel(matrices, bufferSource, entry.pos, entry.text, entry.color, entry.distance, camera);
        }
        bufferSource.endBatch();
        LABELS.clear();
    }

    private static void queueLabel(Vec3 pos, String text, Vector3f color, double distance) {
        LABELS.add(new LabelEntry(pos, text, new Vector3f(color), distance));
    }

    /**
     * Ensure marker size meets minimum screen percentage on its long side.
     */
    private static Vec2 ensureMinimumScreenSize(double distance, float halfWidth, float halfHeight) {
        Minecraft mc = Minecraft.getInstance();
        int screenHeight = mc.getWindow().getHeight();
        int screenWidth = mc.getWindow().getWidth();
        int longSide = Math.max(screenWidth, screenHeight);
        double fov = mc.options.fov().get();

        if (distance <= 0.0) {
            return new Vec2(halfWidth, halfHeight);
        }

        // Pixels per block at distance (vertical FOV approximation)
        double pixelsPerBlock = screenHeight / (2.0 * distance * Math.tan(Math.toRadians(fov / 2.0)));

        float widthPixels = (float) ((halfWidth * 2.0f) * pixelsPerBlock);
        float heightPixels = (float) ((halfHeight * 2.0f) * pixelsPerBlock);
        float longSidePixels = Math.max(widthPixels, heightPixels);

        float minPercent = PortalManager.getInstance().getMinimumMarkerScreenPercent();
        float minPixels = (float) (longSide * (minPercent / 100.0f));

        if (minPixels <= 0.0f || longSidePixels >= minPixels) {
            return new Vec2(halfWidth, halfHeight);
        }

        float scale = minPixels / longSidePixels;
        return new Vec2(halfWidth * scale, halfHeight * scale);
    }

    /**
     * Draw a billboard ellipse facing the camera
     */
    private static void drawBillboardEllipse(PoseStack matrices, MultiBufferSource bufferSource,
                                             Vec3 center, float halfWidth, float halfHeight,
                                             float r, float g, float b, float a, Camera camera,
                                             boolean useDepthTest) {
        var rot = camera.rotation();

        // Camera rotation already faces the camera; use it directly for billboard axes
        Quaternionf cameraRot = new Quaternionf(rot);

        // Calculate camera-facing right and up vectors
        Vector3f rv = new Vector3f(1, 0, 0).rotate(cameraRot);
        Vector3f uv = new Vector3f(0, 1, 0).rotate(cameraRot);
        Vec3 right = new Vec3(rv.x, rv.y, rv.z).scale(halfWidth);
        Vec3 up = new Vec3(uv.x, uv.y, uv.z).scale(halfHeight);

        // Calculate normal from camera forward vector (pointing toward camera)
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(cameraRot);

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
                submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
                    prevPoint.x, prevPoint.y, prevPoint.z,
                    point.x, point.y, point.z, forward, useDepthTest);
            }

            prevPoint = point;
        }
    }

    /**
     * Draw a billboard N mark facing the camera
     */
    private static void drawBillboardN(PoseStack matrices, MultiBufferSource bufferSource,
                                       Vec3 center, float halfWidth, float halfHeight, float r, float g, float b,
                                       float a, Camera camera, boolean useDepthTest, boolean useProperDiagonal) {
        var rot = camera.rotation();

        // Camera rotation already faces the camera; use it directly for billboard axes
        Quaternionf cameraRot = new Quaternionf(rot);

        // Calculate camera-facing right and up vectors
        Vector3f rv = new Vector3f(1, 0, 0).rotate(cameraRot);
        Vector3f uv = new Vector3f(0, 1, 0).rotate(cameraRot);
        Vec3 right = new Vec3(rv.x, rv.y, rv.z).scale(halfWidth);
        Vec3 up = new Vec3(uv.x, uv.y, uv.z).scale(halfHeight);

        // Calculate normal from camera forward vector (pointing toward camera)
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(cameraRot);

        // Calculate 4 corners
        Vec3 topRight = center.add(right.x + up.x, right.y + up.y, right.z + up.z);
        Vec3 topLeft = center.add(-right.x + up.x, -right.y + up.y, -right.z + up.z);
        Vec3 bottomRight = center.add(right.x - up.x, right.y - up.y, right.z - up.z);
        Vec3 bottomLeft = center.add(-right.x - up.x, -right.y - up.y, -right.z - up.z);

        // Draw N (two verticals and a diagonal)
        submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
            topLeft.x, topLeft.y, topLeft.z,
            bottomLeft.x, bottomLeft.y, bottomLeft.z, forward, useDepthTest);

        submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
            topRight.x, topRight.y, topRight.z,
            bottomRight.x, bottomRight.y, bottomRight.z, forward, useDepthTest);

        if (useProperDiagonal) {
            submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
                topLeft.x, topLeft.y, topLeft.z,
                bottomRight.x, bottomRight.y, bottomRight.z, forward, useDepthTest);
        } else {
            submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
                bottomLeft.x, bottomLeft.y, bottomLeft.z,
                topRight.x, topRight.y, topRight.z, forward, useDepthTest);
        }
    }

    /**
     * Draw a billboard portal frame (square/rectangle outline) facing the camera
     * Used for simulated portals
     */
    private static void drawBillboardPortalFrame(PoseStack matrices, MultiBufferSource bufferSource,
                                                  Vec3 center, float halfWidth, float halfHeight,
                                                  float r, float g, float b, float a, Camera camera,
                                                  boolean useDepthTest) {
        var rot = camera.rotation();

        // Camera rotation already faces the camera; use it directly for billboard axes
        Quaternionf cameraRot = new Quaternionf(rot);

        // Calculate camera-facing right and up vectors
        Vector3f rv = new Vector3f(1, 0, 0).rotate(cameraRot);
        Vector3f uv = new Vector3f(0, 1, 0).rotate(cameraRot);
        Vec3 right = new Vec3(rv.x, rv.y, rv.z).scale(halfWidth);
        Vec3 up = new Vec3(uv.x, uv.y, uv.z).scale(halfHeight);

        // Calculate normal from camera forward vector (pointing toward camera)
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(cameraRot);

        // Calculate 4 corners of the rectangle
        Vec3 topRight = center.add(right.x + up.x, right.y + up.y, right.z + up.z);
        Vec3 topLeft = center.add(-right.x + up.x, -right.y + up.y, -right.z + up.z);
        Vec3 bottomRight = center.add(right.x - up.x, right.y - up.y, right.z - up.z);
        Vec3 bottomLeft = center.add(-right.x - up.x, -right.y - up.y, -right.z - up.z);

        // Draw the rectangle outline (4 sides)
        submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
            topLeft.x, topLeft.y, topLeft.z,
            topRight.x, topRight.y, topRight.z, forward, useDepthTest);

        submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
            topRight.x, topRight.y, topRight.z,
            bottomRight.x, bottomRight.y, bottomRight.z, forward, useDepthTest);

        submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
            bottomRight.x, bottomRight.y, bottomRight.z,
            bottomLeft.x, bottomLeft.y, bottomLeft.z, forward, useDepthTest);

        submitMarkerLine(matrices, bufferSource, r, g, b, a, FULLBRIGHT,
            bottomLeft.x, bottomLeft.y, bottomLeft.z,
            topLeft.x, topLeft.y, topLeft.z, forward, useDepthTest);
    }

    /**
     * Submit a line to the render queue
     */
    public static void submitLine(PoseStack matrices, MultiBufferSource bufferSource,
                                   float r, float g, float b, float a, int light,
                                   double ax, double ay, double az, double bx, double by, double bz,
                                   Vector3f normal, boolean useDepthTest) {
        lineCountThisFrame++; // Count this line

        RenderType renderType = useDepthTest ? LINES_DEPTH : LINES_NO_DEPTH;
        VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);
        Matrix4f pose = matrices.last().pose();
        Vector3f dir = new Vector3f((float) (bx - ax), (float) (by - ay), (float) (bz - az));

        // First vertex
        vertexConsumer.addVertex(pose, (float)ax, (float)ay, (float)az)
                .setColor(r, g, b, a)
                .setLight(light)
                .setNormal(matrices.last(), dir.x, dir.y, dir.z);

        // Second vertex
        vertexConsumer.addVertex(pose, (float)bx, (float)by, (float)bz)
                .setColor(r, g, b, a)
                .setLight(light)
                .setNormal(matrices.last(), -dir.x, -dir.y, -dir.z);
    }

    /**
     * Submit a marker line to the render queue
     */
    private static void submitMarkerLine(PoseStack matrices, MultiBufferSource bufferSource,
                                   float r, float g, float b, float a, int light,
                                   double ax, double ay, double az, double bx, double by, double bz,
                                   Vector3f normal, boolean useDepthTest) {
        lineCountThisFrame++; // Count this line

        RenderType renderType = useDepthTest ? MARKER_LINES_DEPTH : MARKER_LINES_NO_DEPTH;
        VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);
        Matrix4f pose = matrices.last().pose();
        Vector3f dir = new Vector3f((float) (bx - ax), (float) (by - ay), (float) (bz - az));

        // First vertex
        vertexConsumer.addVertex(pose, (float)ax, (float)ay, (float)az)
                .setColor(r, g, b, a)
                .setLight(light)
                .setNormal(matrices.last(), dir.x, dir.y, dir.z);

        // Second vertex
        vertexConsumer.addVertex(pose, (float)bx, (float)by, (float)bz)
                .setColor(r, g, b, a)
                .setLight(light)
                .setNormal(matrices.last(), -dir.x, -dir.y, -dir.z);
    }

    /**
     * Render a text label as a billboard
     */
    private static void renderLabel(PoseStack matrices, MultiBufferSource bufferSource,
                                    Vec3 pos, String text, Vector3f color, double distance, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Save pose stack state
        matrices.pushPose();
        matrices.translate(pos.x, pos.y, pos.z);

        // Face the camera
        matrices.mulPose(camera.rotation());

        // Scale based on distance for readability
        float scale = (float) (0.02f * Math.max(1.0, distance / 20.0));
        matrices.scale(-scale, -scale, scale);

        // Calculate text width for centering
        int textWidth = font.width(text);

        // Convert color to ARGB format
        int argbColor = 0xFF000000 |
                       ((int)(color.x * 255) << 16) |
                       ((int)(color.y * 255) << 8) |
                       (int)(color.z * 255);

        // Draw the text
        font.drawInBatch(text, -textWidth / 2f, 0, argbColor, false,
                        matrices.last().pose(), bufferSource, Font.DisplayMode.NORMAL,
                        0, FULLBRIGHT);

        // Restore pose stack state
        matrices.popPose();
    }

    /**
     * Render portal connection lines showing which portal each portal connects to
     * Uses a 3-phase dashed pattern with animation
     * Renders both: currentDim→otherDim AND otherDim→currentDim
     * Always uses NO_DEPTH testing for visibility through walls
     */
    private static void renderPortalConnectionLines(PoseStack matrices, MultiBufferSource bufferSource,
                                                    Vec3 camPos, ResourceKey<Level> currentDim,
                                                    Camera camera) {
        PortalManager manager = PortalManager.getInstance();

        // Check if connection lines are enabled
        if (!manager.isShowConnectionLines()) {
            return;
        }

        // Update animation offset based on time
        // Animate: cycles through 9 units (3 phases x 3 units) over time
        // Negative for reverse direction (towards destination)
        long currentTime = System.currentTimeMillis();
        connectionLineAnimOffset = ((-currentTime / 50.0) % 9.0);

        // Get the other dimension
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;

        // Render connection lines from current dimension portals to other dimension
        Set<PortalInfo> currentDimPortals = manager.getPortalsInDimension(currentDim);
        for (PortalInfo portal : currentDimPortals) {
            // For simulated portals, draw a simple line to their linked portal
            if (portal.isSimulated()) {
                Vec3 startPos = portal.getCenterPos();
                Vec3 endPos = portal.getTranslatedPos();

                // Check draw distances
                if (!isWithinDrawDistance(startPos, camPos, manager) ||
                    !isWithinDrawDistance(endPos, camPos, manager)) {
                    continue;
                }

                // Get color for this portal
                Vector3f color = manager.isPortalHidden(portal)
                    ? HIDDEN_PORTAL_COLOR
                    : manager.getPortalColor(portal);

                // Draw connection line for simulated portal
                drawDashedLine(matrices, bufferSource, startPos, endPos, color, 1);
                continue;
            }

            // Find all linked portals in the other dimension (per-block linking)
            Map<PortalInfo, Vec3> linkedPortals = manager.getLinkedPortalsWithPositions(portal, currentDim);

            for (Map.Entry<PortalInfo, Vec3> entry : linkedPortals.entrySet()) {
                PortalInfo linkedPortal = entry.getKey();
                Vec3 startPos = entry.getValue(); // Average position of blocks linking to this destination
                Vec3 endPos = linkedPortal.getTranslatedPos(); // Already translated to current dimension

                // Check draw distances for both portals
                if (!isWithinDrawDistance(startPos, camPos, manager) ||
                    !isWithinDrawDistance(endPos, camPos, manager)) {
                    continue;
                }

                // Get color for this portal
                Vector3f color = manager.isPortalHidden(portal)
                    ? HIDDEN_PORTAL_COLOR
                    : manager.getPortalColor(portal);

                // Offset direction 1: currentDim → otherDim
                drawDashedLine(matrices, bufferSource, startPos, endPos, color, 1);
            }
        }

        // Render connection lines from other dimension portals back to current dimension
        Set<PortalInfo> otherDimPortals = manager.getPortalsInDimension(otherDim);
        for (PortalInfo portal : otherDimPortals) {
            // For simulated portals, draw a simple line to their linked portal
            if (portal.isSimulated()) {
                Vec3 startPos = portal.getTranslatedPos();
                Vec3 endPos = portal.getCenterPos();

                // Check draw distances
                if (!isWithinDrawDistance(startPos, camPos, manager) ||
                    !isWithinDrawDistance(endPos, camPos, manager)) {
                    continue;
                }

                // Get color for this portal
                Vector3f color = manager.isPortalHidden(portal)
                    ? HIDDEN_PORTAL_COLOR
                    : manager.getPortalColor(portal);

                // Draw connection line for simulated portal
                drawDashedLine(matrices, bufferSource, startPos, endPos, color, -1);
                continue;
            }

            // Find all linked portals in the current dimension (per-block linking)
            Map<PortalInfo, Vec3> linkedPortals = manager.getLinkedPortalsWithPositions(portal, currentDim);

            for (Map.Entry<PortalInfo, Vec3> entry : linkedPortals.entrySet()) {
                PortalInfo linkedPortal = entry.getKey();
                Vec3 startPos = entry.getValue(); // Average position of blocks linking to this destination (already translated)
                Vec3 endPos = linkedPortal.getCenterPos();

                // Check draw distances for both portals
                if (!isWithinDrawDistance(startPos, camPos, manager) ||
                    !isWithinDrawDistance(endPos, camPos, manager)) {
                    continue;
                }

                // Get color for this portal
                Vector3f color = manager.isPortalHidden(portal)
                    ? HIDDEN_PORTAL_COLOR
                    : manager.getPortalColor(portal);

                // Offset direction -1: otherDim → currentDim (opposite offset)
                drawDashedLine(matrices, bufferSource, startPos, endPos, color, -1);
            }
        }
    }

    /**
     * Draw a dashed line from startPos to endPos with 3-phase pattern
     * Pattern: draw-don't draw-don't draw (each phase is 3 units)
     * Always uses NO_DEPTH testing for visibility through walls
     * @param offsetDirection 1 or -1 to offset the line perpendicular to its direction (for bidirectional lines)
     */
    private static void drawDashedLine(PoseStack matrices, MultiBufferSource bufferSource,
                                       Vec3 startPos, Vec3 endPos, Vector3f color, int offsetDirection) {
        Vec3 lineDir = endPos.subtract(startPos);
        double lineLength = lineDir.length();
        Vec3 normalizedDir = lineDir.normalize();

        // Calculate perpendicular offset to separate bidirectional lines
        // Use a small offset of 0.1 blocks
        Vec3 offset = Vec3.ZERO;
        if (offsetDirection != 0) {
            // Get a perpendicular vector using cross product with an arbitrary up vector
            // Use Y-axis unless the line is vertical, then use X-axis
            Vec3 referenceVec = Math.abs(normalizedDir.y) < 0.9
                ? new Vec3(0, 1, 0)
                : new Vec3(1, 0, 0);
            Vec3 perpendicular = normalizedDir.cross(referenceVec).normalize();
            offset = perpendicular.scale(0.1 * offsetDirection);
        }

        // Apply offset to both start and end positions
        Vec3 offsetStartPos = startPos.add(offset);
        Vec3 offsetEndPos = endPos.add(offset);

        // Pattern: draw (3 units), gap (3 units), gap (3 units) = 9 units total
        int patternLength = CONNECTION_SEGMENT_LENGTH * 3;

        // Calculate starting offset based on animation
        double startOffset = connectionLineAnimOffset % patternLength;

        double distance = -startOffset;
        int segmentIndex = 0;

        while (distance < lineLength) {
            // Determine if this segment should be drawn (3-phase pattern)
            // Segment 0: draw, Segments 1&2: gap
            boolean shouldDraw = (segmentIndex % 3 == 0);

            if (shouldDraw && distance >= 0) {
                double segStart = Math.max(0, distance);
                double segEnd = Math.min(lineLength, distance + CONNECTION_SEGMENT_LENGTH);

                if (segEnd > segStart) {
                    Vec3 p1 = offsetStartPos.add(normalizedDir.scale(segStart));
                    Vec3 p2 = offsetStartPos.add(normalizedDir.scale(segEnd));

                    // Always use false for depth testing (visible through walls)
                    submitLine(matrices, bufferSource, color.x, color.y, color.z, 0.6f, FULLBRIGHT,
                        p1.x, p1.y, p1.z,
                        p2.x, p2.y, p2.z,
                        new Vector3f((float)normalizedDir.x, (float)normalizedDir.y, (float)normalizedDir.z),
                        false);
                }
            }

            distance += CONNECTION_SEGMENT_LENGTH;
            segmentIndex++;
        }
    }

    /**
     * Check if a position is within draw distance
     */
    private static boolean isWithinDrawDistance(Vec3 pos, Vec3 camPos, PortalManager manager) {
        if (manager.isPortalMarkerDrawDistanceInfinite()) {
            return true;
        }
        double distance = pos.distanceTo(camPos);
        return distance <= manager.getPortalMarkerDrawDistance();
    }

    private static RenderType createLines(boolean useDepthTest, float lineWidth, String name) {
        RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withVertexShader(ResourceLocation.parse("portal-zone-visualizer:core/portal_lines"))
            .withFragmentShader(ResourceLocation.parse("portal-zone-visualizer:core/portal_lines"))
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL, com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES)
            .withLocation(useDepthTest
                ? "pipeline/" + name
                : "pipeline/" + name)
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
            .withCull(false)
            .withColorWrite(true);

        if (!useDepthTest) {
            pipelineBuilder.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false);
        }

        RenderPipeline pipeline = pipelineBuilder.build();

        RenderType.CompositeState.CompositeStateBuilder builder = RenderType.CompositeState.builder()
            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of((double) lineWidth)))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET);

        return RenderType.create(
            name,
            1536,
            RenderPipelines.register(pipeline),
            builder.createCompositeState(false)
        );
    }

    private record LabelEntry(Vec3 pos, String text, Vector3f color, double distance) {}

    private record Vec2(float x, float y) {}
}
