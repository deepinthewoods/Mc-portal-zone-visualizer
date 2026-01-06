package com.portalzone.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI screen for managing portal names
 */
public class PortalManagementScreen extends Screen {
    private static final int ENTRY_HEIGHT = 24;
    private static final int ENTRY_SPACING = 4;
    private static final int SCROLL_SPEED = 10;

    private final Screen parent;
    private final List<PortalEntry> portalEntries = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public PortalManagementScreen(Screen parent) {
        super(Component.translatable("gui.portal-zone-visualizer.portal_list"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Add close button
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> this.minecraft.setScreen(parent))
            .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
            .build());

        // Load portals
        loadPortals();
    }

    private void loadPortals() {
        portalEntries.clear();

        if (minecraft == null || minecraft.level == null) {
            return;
        }

        ResourceKey<Level> currentDim = minecraft.level.dimension();
        PortalManager manager = PortalManager.getInstance();

        // Add portals from current dimension
        for (PortalInfo portal : manager.getPortalsInDimension(currentDim)) {
            portalEntries.add(new PortalEntry(portal, currentDim, true));
        }

        // Add portals from other dimension
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        for (PortalInfo portal : manager.getPortalsInDimension(otherDim)) {
            portalEntries.add(new PortalEntry(portal, otherDim, false));
        }

        // Calculate max scroll
        int totalHeight = portalEntries.size() * (ENTRY_HEIGHT + ENTRY_SPACING);
        int viewportHeight = this.height - 60;
        maxScroll = Math.max(0, totalHeight - viewportHeight);

        // Initialize edit boxes for each portal
        int y = 40;
        for (PortalEntry entry : portalEntries) {
            entry.editBox = new EditBox(this.font, this.width / 2 + 50, y, 200, 20,
                    Component.literal("Portal Name"));
            entry.editBox.setMaxLength(32);
            String currentName = manager.getPortalName(entry.portal.uuid);
            if (currentName != null) {
                entry.editBox.setValue(currentName);
            }
            entry.editBox.setResponder(text -> {
                manager.setPortalName(entry.portal.uuid, text);
            });
            this.addRenderableWidget(entry.editBox);
            y += ENTRY_HEIGHT + ENTRY_SPACING;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Draw portal entries
        int y = 40 - scrollOffset;
        int viewportTop = 35;
        int viewportBottom = this.height - 35;

        for (PortalEntry entry : portalEntries) {
            if (y + ENTRY_HEIGHT > viewportTop && y < viewportBottom) {
                renderPortalEntry(graphics, entry, y);
            }
            y += ENTRY_HEIGHT + ENTRY_SPACING;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPortalEntry(GuiGraphics graphics, PortalEntry entry, int y) {
        // Update edit box position
        if (entry.editBox != null) {
            entry.editBox.setY(y);
        }

        // Draw color indicator
        Vector3f color = entry.portal.color;
        int colorInt = 0xFF000000 |
                ((int)(color.x * 255) << 16) |
                ((int)(color.y * 255) << 8) |
                (int)(color.z * 255);

        graphics.fill(this.width / 2 - 200, y, this.width / 2 - 180, y + 20, colorInt);

        // Draw portal ID
        String idText = entry.portal.getShortId();
        graphics.drawString(this.font, idText, this.width / 2 - 170, y + 6, 0xFFFFFF);

        // Draw dimension info
        String dimText = entry.isCurrentDimension ? "(Current)" : "(Other)";
        graphics.drawString(this.font, dimText, this.width / 2 - 120, y + 6, 0xAAAAAA);

        // Draw position
        String posText = String.format("XYZ: %d, %d, %d",
                entry.portal.position.getX(),
                entry.portal.position.getY(),
                entry.portal.position.getZ());
        graphics.drawString(this.font, posText, this.width / 2 - 40, y + 6, 0xAAAAAA);

        // Edit box is rendered by super.render()
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(scrollY * SCROLL_SPEED)));
        return true;
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class PortalEntry {
        final PortalInfo portal;
        final ResourceKey<Level> dimension;
        final boolean isCurrentDimension;
        EditBox editBox;

        PortalEntry(PortalInfo portal, ResourceKey<Level> dimension, boolean isCurrentDimension) {
            this.portal = portal;
            this.dimension = dimension;
            this.isCurrentDimension = isCurrentDimension;
        }
    }
}
