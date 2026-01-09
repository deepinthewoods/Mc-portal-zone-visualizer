package com.portalzone;

import com.portalzone.gui.PortalManagementScreen;
import com.portalzone.portal.PortalManager;
import com.portalzone.render.PortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Main client-side entry point for Portal Zone Visualizer
 */
public class PortalZoneVisualizerClient implements ClientModInitializer {
    public static final String MOD_ID = "portal-zone-visualizer";

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
}
