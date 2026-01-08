package com.portalzone.gui;

import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GUI screen for managing portal names
 */
public class PortalManagementScreen extends Screen {
    private static final int ENTRY_HEIGHT = 46;
    private static final int ENTRY_SPACING = 6;
    private static final int HEADER_HEIGHT = 16;
    private static final int HEADER_SPACING = 4;
    private static final int SLIDER_OFFSET_Y = 22;
    private static final int SCROLL_SPEED = 10;
    private static final int LIST_START_Y = 140;
    private static final int VIEWPORT_TOP = 135;
    private static final int VIEWPORT_BOTTOM_MARGIN = 35;
    private static final float MIN_MARKER_PERCENT = 0.0f;
    private static final float MAX_MARKER_PERCENT = 20.0f;
    private static final float MIN_BORDER_FUZZ_PERCENT = 0.0f;
    private static final float MAX_BORDER_FUZZ_PERCENT = 100.0f;
    private static final int MIN_BORDER_FUZZ_START_DISTANCE = 4;
    private static final int MAX_BORDER_FUZZ_START_DISTANCE = 256;

    private final Screen parent;
    private final List<PortalEntry> portalEntries = new ArrayList<>();
    private final List<ListEntry> listEntries = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private Checkbox portalMarkersCheckbox;
    private Checkbox bordersCheckbox;
    private Checkbox neutralBordersCheckbox;
    private Checkbox verticalBordersCheckbox;
    private MinMarkerSizeSlider minMarkerSizeSlider;
    private BorderFuzzSlider borderFuzzSlider;
    private BorderFuzzStartSlider borderFuzzStartSlider;
    private PortalMarkerDrawDistanceSlider portalMarkerDrawDistanceSlider;
    private BorderDrawDistanceSlider borderDrawDistanceSlider;

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

        // Add checkboxes for depth testing
        PortalManager manager = PortalManager.getInstance();

        portalMarkersCheckbox = Checkbox.builder(
                Component.literal("Portal Markers Always Visible"),
                this.font)
            .pos(10, 10)
            .selected(manager.isPortalMarkersAlwaysVisible())
            .onValueChange((checkbox, selected) -> {
                manager.setPortalMarkersAlwaysVisible(selected);
            })
            .build();
        this.addRenderableWidget(portalMarkersCheckbox);

        bordersCheckbox = Checkbox.builder(
                Component.literal("Borders Always Visible"),
                this.font)
            .pos(10, 25)
            .selected(manager.isBordersAlwaysVisible())
            .onValueChange((checkbox, selected) -> {
                manager.setBordersAlwaysVisible(selected);
            })
            .build();
        this.addRenderableWidget(bordersCheckbox);

        neutralBordersCheckbox = Checkbox.builder(
                Component.literal("Show Grey Borders"),
                this.font)
            .pos(10, 40)
            .selected(manager.isNeutralBordersEnabled())
            .onValueChange((checkbox, selected) -> {
                manager.setNeutralBordersEnabled(selected);
            })
            .build();
        this.addRenderableWidget(neutralBordersCheckbox);

        verticalBordersCheckbox = Checkbox.builder(
                Component.literal("Show Vertical Borders"),
                this.font)
            .pos(10, 55)
            .selected(manager.isVerticalBordersEnabled())
            .onValueChange((checkbox, selected) -> {
                manager.setVerticalBordersEnabled(selected);
            })
            .build();
        this.addRenderableWidget(verticalBordersCheckbox);

        minMarkerSizeSlider = new MinMarkerSizeSlider(10, 70, 220, 20,
            manager.getMinimumMarkerScreenPercent());
        this.addRenderableWidget(minMarkerSizeSlider);

        borderFuzzSlider = new BorderFuzzSlider(10, 90, 220, 20,
            manager.getBorderFuzzThreshold() * 100.0f);
        this.addRenderableWidget(borderFuzzSlider);

        borderFuzzStartSlider = new BorderFuzzStartSlider(10, 110, 220, 20,
            manager.getBorderFuzzStartDistance());
        this.addRenderableWidget(borderFuzzStartSlider);

        portalMarkerDrawDistanceSlider = new PortalMarkerDrawDistanceSlider(250, 70, 220, 20,
            manager.getPortalMarkerDrawDistance());
        this.addRenderableWidget(portalMarkerDrawDistanceSlider);

        borderDrawDistanceSlider = new BorderDrawDistanceSlider(250, 90, 220, 20,
            manager.getBorderDrawDistance());
        this.addRenderableWidget(borderDrawDistanceSlider);

        // Add clear buttons
        this.addRenderableWidget(Button.builder(
                Component.literal("Clear Current"),
                button -> {
                    if (minecraft != null && minecraft.level != null) {
                        manager.clearCurrentDimension(minecraft.level.dimension());
                        loadPortals(); // Refresh the portal list
                    }
                })
            .bounds(250, 10, 100, 20)
            .build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Clear All"),
                button -> {
                    manager.clearAllPortals();
                    loadPortals(); // Refresh the portal list
                })
            .bounds(360, 10, 100, 20)
            .build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Add Simulated"),
                button -> {
                    if (minecraft != null && minecraft.player != null && minecraft.level != null) {
                        BlockPos pos = minecraft.player.blockPosition();
                        manager.addSimulatedPortal(minecraft.level.dimension(), pos);
                        loadPortals();
                    }
                })
            .bounds(250, 35, 210, 20)
            .build());

        // Load portals
        loadPortals();
    }

    private void loadPortals() {
        portalEntries.clear();
        listEntries.clear();

        if (minecraft == null || minecraft.level == null) {
            return;
        }

        ResourceKey<Level> currentDim = minecraft.level.dimension();
        PortalManager manager = PortalManager.getInstance();

        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        addHeaderEntry(otherDim);
        for (PortalInfo portal : manager.getPortalsInDimension(otherDim)) {
            PortalEntry entry = new PortalEntry(portal, otherDim, false);
            portalEntries.add(entry);
            listEntries.add(ListEntry.portal(entry));
        }

        addHeaderEntry(currentDim);
        for (PortalInfo portal : manager.getPortalsInDimension(currentDim)) {
            PortalEntry entry = new PortalEntry(portal, currentDim, true);
            portalEntries.add(entry);
            listEntries.add(ListEntry.portal(entry));
        }

        // Calculate max scroll
        int totalHeight = 0;
        for (ListEntry entry : listEntries) {
            totalHeight += entry.height;
        }
        int viewportHeight = this.height - (VIEWPORT_TOP + VIEWPORT_BOTTOM_MARGIN);
        maxScroll = Math.max(0, totalHeight - viewportHeight);

        // Initialize edit boxes for each portal
        int y = LIST_START_Y;
        for (ListEntry entry : listEntries) {
            if (entry.type != EntryType.PORTAL) {
                y += entry.height;
                continue;
            }

            PortalEntry portalEntry = entry.portalEntry;
            portalEntry.editBox = new EditBox(this.font, this.width / 2 + 50, y, 200, 20,
                    Component.literal("Portal Name"));
            portalEntry.editBox.setMaxLength(32);
            String currentName = manager.getPortalName(portalEntry.portal.uuid);
            if (currentName != null) {
                portalEntry.editBox.setValue(currentName);
            }
            portalEntry.editBox.setResponder(text -> {
                manager.setPortalName(portalEntry.portal.uuid, text);
            });
            this.addRenderableWidget(portalEntry.editBox);
            if (!portalEntry.portal.isSimulated()) {
                float hue = manager.getPortalHue(portalEntry.portal);
                portalEntry.hueSlider = new HueSlider(this.width / 2 + 50, y + SLIDER_OFFSET_Y, 200, 20,
                    portalEntry.portal.uuid, hue);
                this.addRenderableWidget(portalEntry.hueSlider);
            }
            portalEntry.hideButton = Button.builder(
                    Component.literal(manager.isPortalHidden(portalEntry.portal) ? "Show" : "Hide"),
                    button -> {
                        boolean hidden = !manager.isPortalHidden(portalEntry.portal);
                        manager.setPortalHidden(portalEntry.portal.uuid, hidden);
                        button.setMessage(Component.literal(hidden ? "Show" : "Hide"));
                    })
                .bounds(this.width / 2 + 255, y, 45, 20)
                .build();
            this.addRenderableWidget(portalEntry.hideButton);
            if (portalEntry.portal.isSimulated()) {
                portalEntry.removeButton = Button.builder(
                        Component.literal("-"),
                        button -> {
                            manager.removeSimulatedPortal(portalEntry.portal.uuid);
                            loadPortals();
                        })
                    .bounds(this.width / 2 + 305, y, 18, 20)
                    .build();
                this.addRenderableWidget(portalEntry.removeButton);
            }
            y += entry.height;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render transparent background instead of blurred background
        this.renderTransparentBackground(graphics);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Draw portal entries
        int y = LIST_START_Y - scrollOffset;
        int viewportTop = VIEWPORT_TOP;
        int viewportBottom = this.height - VIEWPORT_BOTTOM_MARGIN;

        for (ListEntry entry : listEntries) {
            if (y + entry.height > viewportTop && y < viewportBottom) {
                if (entry.type == EntryType.HEADER) {
                    renderHeader(graphics, entry.headerText, y);
                } else {
                    renderPortalEntry(graphics, entry.portalEntry, y);
                }
            }
            y += entry.height;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPortalEntry(GuiGraphics graphics, PortalEntry entry, int y) {
        // Update edit box position
        if (entry.editBox != null) {
            entry.editBox.setY(y);
        }
        if (entry.hueSlider != null) {
            entry.hueSlider.setY(y + SLIDER_OFFSET_Y);
        }
        if (entry.removeButton != null) {
            entry.removeButton.setX(this.width / 2 + 305);
            entry.removeButton.setY(y);
        }
        if (entry.hideButton != null) {
            entry.hideButton.setX(this.width / 2 + 255);
            entry.hideButton.setY(y);
        }

        // Draw color indicator
        Vector3f color = PortalManager.getInstance().getPortalColor(entry.portal);
        int colorInt = 0xFF000000 |
                ((int)(color.x * 255) << 16) |
                ((int)(color.y * 255) << 8) |
                (int)(color.z * 255);

        graphics.fill(this.width / 2 - 200, y, this.width / 2 - 180, y + 20, colorInt);

        // Draw portal ID
        String idText = entry.portal.getShortId();
        graphics.drawString(this.font, idText, this.width / 2 - 170, y + 6, 0xFFFFFF);

        // Draw dimension info
        String dimText = entry.portal.isSimulated()
            ? "(Simulated)"
            : (entry.isCurrentDimension ? "(Current)" : "(Other)");
        graphics.drawString(this.font, dimText, this.width / 2 - 120, y + 6, 0xAAAAAA);

        // Draw position
        String posText = String.format("XYZ: %d, %d, %d",
                entry.portal.position.getX(),
                entry.portal.position.getY(),
                entry.portal.position.getZ());
        graphics.drawString(this.font, posText, this.width / 2 - 40, y + 6, 0xAAAAAA);

        // Edit box is rendered by super.render()
    }

    private void renderHeader(GuiGraphics graphics, String text, int y) {
        graphics.drawString(this.font, text, this.width / 2 - 200, y + 4, 0xCCCCCC);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(scrollY * SCROLL_SPEED)));
        return true;
    }

    @Override
    public void removed() {
        super.removed();
        PortalManager.getInstance().saveSettingsNow();
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
        HueSlider hueSlider;
        Button hideButton;
        Button removeButton;

        PortalEntry(PortalInfo portal, ResourceKey<Level> dimension, boolean isCurrentDimension) {
            this.portal = portal;
            this.dimension = dimension;
            this.isCurrentDimension = isCurrentDimension;
        }
    }

    private enum EntryType {
        HEADER,
        PORTAL
    }

    private static class ListEntry {
        final EntryType type;
        final PortalEntry portalEntry;
        final String headerText;
        final int height;

        private ListEntry(EntryType type, PortalEntry portalEntry, String headerText, int height) {
            this.type = type;
            this.portalEntry = portalEntry;
            this.headerText = headerText;
            this.height = height;
        }

        static ListEntry header(String text) {
            return new ListEntry(EntryType.HEADER, null, text, HEADER_HEIGHT + HEADER_SPACING);
        }

        static ListEntry portal(PortalEntry portalEntry) {
            return new ListEntry(EntryType.PORTAL, portalEntry, null, ENTRY_HEIGHT + ENTRY_SPACING);
        }
    }

    private static class HueSlider extends AbstractSliderButton {
        private static final float MAX_HUE = 360.0f;
        private final UUID portalUuid;

        HueSlider(int x, int y, int width, int height, UUID portalUuid, float hue) {
            super(x, y, width, height, Component.empty(), hue / MAX_HUE);
            this.portalUuid = portalUuid;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int hueValue = Math.round((float)(this.value * MAX_HUE));
            this.setMessage(Component.translatable("gui.portal-zone-visualizer.hue", hueValue));
        }

        @Override
        protected void applyValue() {
            float hueValue = (float)(this.value * MAX_HUE);
            PortalManager.getInstance().setPortalHue(portalUuid, hueValue);
        }
    }

    private static class MinMarkerSizeSlider extends AbstractSliderButton {
        private MinMarkerSizeSlider(int x, int y, int width, int height, float percent) {
            super(x, y, width, height, Component.empty(),
                (percent - MIN_MARKER_PERCENT) / (MAX_MARKER_PERCENT - MIN_MARKER_PERCENT));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float percent = (float) (this.value * (MAX_MARKER_PERCENT - MIN_MARKER_PERCENT) + MIN_MARKER_PERCENT);
            int rounded = Math.round(percent);
            this.setMessage(Component.literal("Minimum Portal Marker Size: " + rounded + "%"));
        }

        @Override
        protected void applyValue() {
            float percent = (float) (this.value * (MAX_MARKER_PERCENT - MIN_MARKER_PERCENT) + MIN_MARKER_PERCENT);
            PortalManager.getInstance().setMinimumMarkerScreenPercent(percent);
        }
    }

    private static class BorderFuzzSlider extends AbstractSliderButton {
        private BorderFuzzSlider(int x, int y, int width, int height, float percent) {
            super(x, y, width, height, Component.empty(),
                (percent - MIN_BORDER_FUZZ_PERCENT) / (MAX_BORDER_FUZZ_PERCENT - MIN_BORDER_FUZZ_PERCENT));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float percent = (float) (this.value * (MAX_BORDER_FUZZ_PERCENT - MIN_BORDER_FUZZ_PERCENT) + MIN_BORDER_FUZZ_PERCENT);
            int rounded = Math.round(percent);
            this.setMessage(Component.literal("Border Fuzz Threshold: " + rounded + "%"));
        }

        @Override
        protected void applyValue() {
            float percent = (float) (this.value * (MAX_BORDER_FUZZ_PERCENT - MIN_BORDER_FUZZ_PERCENT) + MIN_BORDER_FUZZ_PERCENT);
            PortalManager.getInstance().setBorderFuzzThreshold(percent / 100.0f);
        }
    }

    private static class BorderFuzzStartSlider extends AbstractSliderButton {
        private BorderFuzzStartSlider(int x, int y, int width, int height, int distance) {
            super(x, y, width, height, Component.empty(),
                (distance - MIN_BORDER_FUZZ_START_DISTANCE)
                    / (double) (MAX_BORDER_FUZZ_START_DISTANCE - MIN_BORDER_FUZZ_START_DISTANCE));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int distance = (int) Math.round(this.value
                * (MAX_BORDER_FUZZ_START_DISTANCE - MIN_BORDER_FUZZ_START_DISTANCE)
                + MIN_BORDER_FUZZ_START_DISTANCE);
            this.setMessage(Component.literal("Border Fuzz Start Distance: " + distance + "b"));
        }

        @Override
        protected void applyValue() {
            int distance = (int) Math.round(this.value
                * (MAX_BORDER_FUZZ_START_DISTANCE - MIN_BORDER_FUZZ_START_DISTANCE)
                + MIN_BORDER_FUZZ_START_DISTANCE);
            PortalManager.getInstance().setBorderFuzzStartDistance(distance);
        }
    }

    private void addHeaderEntry(ResourceKey<Level> dimension) {
        String label = Level.NETHER.equals(dimension) ? "Nether Portals" : "Overworld Portals";
        listEntries.add(ListEntry.header(label));
    }

    private static class PortalMarkerDrawDistanceSlider extends AbstractSliderButton {
        private static final double MIN_DISTANCE = 32.0;
        private static final double MAX_DISTANCE = 2048.0;
        private static final double INFINITE_DISTANCE = -1.0;

        private PortalMarkerDrawDistanceSlider(int x, int y, int width, int height, double distance) {
            super(x, y, width, height, Component.empty(), distanceToSliderValue(distance));
            updateMessage();
        }

        private static double distanceToSliderValue(double distance) {
            if (distance == INFINITE_DISTANCE) {
                return 1.0; // Rightmost position for infinity
            }
            // Map distance [MIN_DISTANCE, MAX_DISTANCE] to [0.0, 0.99]
            return (distance - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE) * 0.99;
        }

        private static double sliderValueToDistance(double value) {
            if (value >= 0.995) {
                return INFINITE_DISTANCE; // Top 0.5% of slider = infinity
            }
            // Map slider [0.0, 0.99] to [MIN_DISTANCE, MAX_DISTANCE]
            return MIN_DISTANCE + (value / 0.99) * (MAX_DISTANCE - MIN_DISTANCE);
        }

        @Override
        protected void updateMessage() {
            double distance = sliderValueToDistance(this.value);
            if (distance == INFINITE_DISTANCE) {
                this.setMessage(Component.literal("Portal Marker Draw Distance: Infinity"));
            } else {
                int rounded = (int) Math.round(distance);
                this.setMessage(Component.literal("Portal Marker Draw Distance: " + rounded + "b"));
            }
        }

        @Override
        protected void applyValue() {
            double distance = sliderValueToDistance(this.value);
            PortalManager.getInstance().setPortalMarkerDrawDistance(distance);
        }
    }

    private static class BorderDrawDistanceSlider extends AbstractSliderButton {
        private static final double MIN_DISTANCE = 32.0;
        private static final double MAX_DISTANCE = 2048.0;

        private BorderDrawDistanceSlider(int x, int y, int width, int height, double distance) {
            super(x, y, width, height, Component.empty(),
                (distance - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double distance = this.value * (MAX_DISTANCE - MIN_DISTANCE) + MIN_DISTANCE;
            int rounded = (int) Math.round(distance);
            this.setMessage(Component.literal("Border Draw Distance: " + rounded + "b"));
        }

        @Override
        protected void applyValue() {
            double distance = this.value * (MAX_DISTANCE - MIN_DISTANCE) + MIN_DISTANCE;
            PortalManager.getInstance().setBorderDrawDistance(distance);
        }
    }
}
