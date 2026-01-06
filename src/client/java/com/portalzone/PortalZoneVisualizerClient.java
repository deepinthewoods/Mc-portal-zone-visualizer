package com.portalzone;

import com.portalzone.gui.PortalManagementScreen;
import com.portalzone.portal.PortalManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
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

    // Rendering state
    private static boolean renderingEnabled = true;

    @Override
    public void onInitializeClient() {
        // Register keybindings
        toggleVisualizationKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.portal-zone-visualizer.toggle",
            GLFW.GLFW_KEY_P,
            "category.portal-zone-visualizer"
        ));

        portalManagementKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.portal-zone-visualizer.manage",
            GLFW.GLFW_KEY_O,
            "category.portal-zone-visualizer"
        ));

        // Register tick event to update portal manager
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Handle toggle visualization keybinding
            while (toggleVisualizationKey.consumeClick()) {
                renderingEnabled = !renderingEnabled;
                if (client.player != null) {
                    // TODO: Add feedback message to player
                }
            }

            // Handle portal management keybinding
            while (portalManagementKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new PortalManagementScreen(null));
                }
            }

            // Update portal manager
            if (client.level != null) {
                PortalManager.getInstance().tick();
            }
        });

        // Clear portals when world changes
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) {
                PortalManager.getInstance().clear();
            }
        });
    }

    public static boolean isRenderingEnabled() {
        return renderingEnabled;
    }
}
