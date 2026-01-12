package com.portalzone;

import com.portalzone.gui.PortalManagementScreen;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalLinkingAlgorithm;
import com.portalzone.portal.PortalManager;
import com.portalzone.render.PortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main client-side entry point for Portal Zone Visualizer
 */
public class PortalZoneVisualizerClient implements ClientModInitializer {
    public static final String MOD_ID = "portal-zone-visualizer";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Keybinding for toggling visualization
    public static KeyMapping toggleVisualizationKey;

    // Keybinding for portal management screen
    public static KeyMapping portalManagementKey;

    // Keybinding for flipping border source (momentary)
    public static KeyMapping flipBordersKey;

    // Keybinding for simulating a portal at the player position (momentary)
    public static KeyMapping simulatePortalKey;

    // Rendering state
    private static boolean renderingEnabled = true;

    // Track previous dimension for portal traversal detection
    private static ResourceKey<Level> previousDimension = null;

    @Override
    public void onInitializeClient() {
        // Register keybindings
        toggleVisualizationKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.portal-zone-visualizer.toggle",
            GLFW.GLFW_KEY_P,
            KeyMapping.Category.MISC
        ));

        portalManagementKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.portal-zone-visualizer.manage",
            GLFW.GLFW_KEY_I,
            KeyMapping.Category.MISC
        ));

        flipBordersKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.portal-zone-visualizer.flip_borders",
            GLFW.GLFW_KEY_O,
            KeyMapping.Category.MISC
        ));

        simulatePortalKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.portal-zone-visualizer.simulate_portal",
            GLFW.GLFW_KEY_U,
            KeyMapping.Category.MISC
        ));

        // Register tick event to update portal manager
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PortalManager manager = PortalManager.getInstance();
            manager.setFlipBordersHeld(flipBordersKey.isDown());
            manager.setSimulatePortalHeld(simulatePortalKey.isDown());

            // Handle toggle visualization keybinding
            while (toggleVisualizationKey.consumeClick()) {
                renderingEnabled = !renderingEnabled;
                if (client.player != null) {
                    // TODO: Add feedback message to player
                }
            }

            // Handle portal management keybinding
            while (portalManagementKey.consumeClick()) {
                if (client.screen instanceof PortalManagementScreen) {
                    client.setScreen(null);
                } else if (client.screen == null) {
                    client.setScreen(new PortalManagementScreen(null));
                }
            }

            // Update portal manager
            if (client.level != null) {
                manager.tick();

                // Detect dimension changes (portal traversal)
                ResourceKey<Level> currentDimension = client.level.dimension();
                if (previousDimension != null && !previousDimension.equals(currentDimension) && client.player != null) {
                    // Player changed dimensions - likely went through a portal
                    logPortalTraversal(client, previousDimension, currentDimension);
                }
                previousDimension = currentDimension;
            } else {
                previousDimension = null;
            }
        });

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
            PortalManager.getInstance().handleChunkLoad(level, chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
            PortalManager.getInstance().handleChunkUnload(level.dimension(), chunk.getPos()));

        // Clear portals only after fully disconnecting from a world/server
        // Track the last connection state to detect true disconnects
        final boolean[] wasConnected = {false};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean isConnected = client.level != null || client.getConnection() != null;

            // Only clear when we transition from connected to fully disconnected
            if (wasConnected[0] && !isConnected) {
                PortalManager.getInstance().clear();
            }

            wasConnected[0] = isConnected;
        });

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            var matrices = context.matrices();
            if (matrices == null) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }

            var camera = mc.gameRenderer.getMainCamera();
            var camPos = camera.getPosition();
            matrices.pushPose();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);
            PortalRenderer.render(matrices, camera, context.consumers());
            matrices.popPose();
        });

        WorldRenderEvents.END_MAIN.register(context -> {
            var matrices = context.matrices();
            if (matrices == null) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }

            var camera = mc.gameRenderer.getMainCamera();
            var camPos = camera.getPosition();
            matrices.pushPose();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);
            PortalRenderer.renderLabels(matrices, camera);
            matrices.popPose();
        });
    }

    public static boolean isRenderingEnabled() {
        return renderingEnabled;
    }

    /**
     * Log detailed information about portal traversal for debugging
     */
    private static void logPortalTraversal(Minecraft client, ResourceKey<Level> fromDimension, ResourceKey<Level> toDimension) {
        if (client.player == null) return;

        PortalManager manager = PortalManager.getInstance();
        Vec3 playerPos = client.player.position();

        LOGGER.info("========================================");
        LOGGER.info("PORTAL TRAVERSAL DETECTED");
        LOGGER.info("From: {} to {}", fromDimension.location(), toDimension.location());
        LOGGER.info("Player position in new dimension: {}", playerPos);
        LOGGER.info("========================================");

        // Log all portals in the source dimension
        var sourcePortals = manager.getPortalsInDimension(fromDimension);
        LOGGER.info("Portals in source dimension ({}): {}", fromDimension.location(), sourcePortals.size());
        for (PortalInfo portal : sourcePortals) {
            LOGGER.info("  Portal {} at {} ({}x{}, {})",
                manager.getPortalDisplayName(portal),
                portal.position,
                portal.width,
                portal.height,
                portal.orientation);
        }

        // Log all portals in the destination dimension
        var destPortals = manager.getPortalsInDimension(toDimension);
        LOGGER.info("Portals in destination dimension ({}): {}", toDimension.location(), destPortals.size());
        for (PortalInfo portal : destPortals) {
            LOGGER.info("  Portal {} at {} ({}x{}, {})",
                manager.getPortalDisplayName(portal),
                portal.position,
                portal.width,
                portal.height,
                portal.orientation);
        }

        // Log calculated portal destinations for each source portal
        LOGGER.info("Calculated portal destinations:");
        for (PortalInfo sourcePortal : sourcePortals) {
            Vec3 translatedPos = sourcePortal.getTranslatedPos();

            // Find the linked portal using the portal linking algorithm
            PortalInfo linkedPortal = PortalLinkingAlgorithm.findLinkedPortal(
                sourcePortal.getCenterPos(),
                sourcePortal.dimension,
                destPortals
            );

            if (linkedPortal != null) {
                double distance = PortalLinkingAlgorithm.distance3d(translatedPos, linkedPortal.getCenterPos());
                LOGGER.info("  {} -> {} (distance: {} blocks)",
                    manager.getPortalDisplayName(sourcePortal),
                    manager.getPortalDisplayName(linkedPortal),
                    String.format("%.1f", distance));
            } else {
                LOGGER.info("  {} -> [Would create new portal at ({}, {}, {})]",
                    manager.getPortalDisplayName(sourcePortal),
                    String.format("%.1f", translatedPos.x),
                    String.format("%.1f", translatedPos.y),
                    String.format("%.1f", translatedPos.z));
            }
        }

        // Find and log the portal the player came out of
        PortalInfo nearestPortal = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PortalInfo portal : destPortals) {
            double distance = PortalLinkingAlgorithm.distance3d(playerPos, portal.getCenterPos());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestPortal = portal;
            }
        }

        if (nearestPortal != null) {
            LOGGER.info("Player exited through portal: {} at {} (distance: {} blocks)",
                manager.getPortalDisplayName(nearestPortal),
                nearestPortal.position,
                String.format("%.1f", nearestDistance));
        } else {
            LOGGER.info("No nearby portal found in destination dimension");
        }

        LOGGER.info("========================================");
    }
}
