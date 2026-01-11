package com.portalzone.gui;

import com.portalzone.PortalZoneVisualizerClient;
import com.portalzone.portal.PortalInfo;
import com.portalzone.portal.PortalManager;
import fi.dy.masa.malilib.gui.BaseScreen;
import fi.dy.masa.malilib.gui.GuiTextFieldDouble;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.interfaces.ISliderCallback;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.gui.widgets.WidgetSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class PortalManagementScreen extends BaseScreen {
    private static final int ENTRY_HEIGHT = 90;
    private static final int ENTRY_SPACING = 6;
    private static final int HEADER_HEIGHT = 16;
    private static final int HEADER_SPACING = 4;
    private static final int SCROLL_SPEED = 10;
    private static final int LIST_START_Y = 160;
    private static final int VIEWPORT_TOP = 155;
    private static final int VIEWPORT_BOTTOM_MARGIN = 35;

    private static final Method EDIT_BOX_LAYOUT = findEditBoxLayoutMethod();

    private final List<PortalEntry> portalEntries = new ArrayList<>();
    private final List<ListEntry> listEntries = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private double lastFinitePortalMarkerDrawDistance = 2048.0;
    private boolean wasKeyDown = false;

    public PortalManagementScreen(Screen parent) {
        super();
        this.setParent(parent);
        this.title = "Portal Zone Visualizer";
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearElements();

        int x = 10;
        int y = 20;

        // Add checkboxes and sliders at the top
        this.createSettingsWidgets(x, y);

        // Add done button
        ButtonGeneric doneButton = new ButtonGeneric(this.width / 2 - 100, this.height - 28, 200, 20, "Done");
        this.addButton(doneButton, (button, mouseButton) -> this.closeGui(true));

        // Load portals
        this.loadPortals();
    }

    private void createSettingsWidgets(int x, int y) {
        PortalManager manager = PortalManager.getInstance();

        // Portal Markers checkbox
        WidgetCheckBox portalMarkersCheckbox = new WidgetCheckBox(
            x,
            y,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Portal Markers Always Visible"
        );
        portalMarkersCheckbox.setChecked(manager.isPortalMarkersAlwaysVisible());
        portalMarkersCheckbox.setListener((checkBox) ->
            manager.setPortalMarkersAlwaysVisible(checkBox.isChecked()));
        this.addWidget(portalMarkersCheckbox);
        y += 15;

        // Portal discovery checkbox
        WidgetCheckBox discoveryCheckbox = new WidgetCheckBox(
            x,
            y,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Enable Portal Discovery"
        );
        discoveryCheckbox.setChecked(manager.isPortalDiscoveryEnabled());
        discoveryCheckbox.setListener((checkBox) -> manager.setPortalDiscoveryEnabled(checkBox.isChecked()));
        this.addWidget(discoveryCheckbox);
        y += 15;

        // Borders checkbox
        WidgetCheckBox bordersCheckbox = new WidgetCheckBox(
            x,
            y,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Borders Always Visible"
        );
        bordersCheckbox.setChecked(manager.isBordersAlwaysVisible());
        bordersCheckbox.setListener((checkBox) -> manager.setBordersAlwaysVisible(checkBox.isChecked()));
        this.addWidget(bordersCheckbox);
        y += 15;

        // Neutral borders checkbox
        WidgetCheckBox neutralBordersCheckbox = new WidgetCheckBox(
            x,
            y,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Show Grey Borders"
        );
        neutralBordersCheckbox.setChecked(manager.isNeutralBordersEnabled());
        neutralBordersCheckbox.setListener((checkBox) -> manager.setNeutralBordersEnabled(checkBox.isChecked()));
        this.addWidget(neutralBordersCheckbox);
        y += 15;

        // Show connection lines checkbox
        WidgetCheckBox showConnectionLinesCheckbox = new WidgetCheckBox(
            x,
            y,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Show Connection Lines"
        );
        showConnectionLinesCheckbox.setChecked(manager.isShowConnectionLines());
        showConnectionLinesCheckbox.setListener((checkBox) -> manager.setShowConnectionLines(checkBox.isChecked()));
        this.addWidget(showConnectionLinesCheckbox);
        y += 15;

        // Connection lines always visible checkbox
        WidgetCheckBox connectionLinesAlwaysVisibleCheckbox = new WidgetCheckBox(
            x,
            y,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Connection Lines Always Visible"
        );
        connectionLinesAlwaysVisibleCheckbox.setChecked(manager.isConnectionLinesAlwaysVisible());
        connectionLinesAlwaysVisibleCheckbox.setListener((checkBox) -> manager.setConnectionLinesAlwaysVisible(checkBox.isChecked()));
        this.addWidget(connectionLinesAlwaysVisibleCheckbox);

        // Minimum marker size slider
        y += 20;
        this.createDoubleField(x, y,
            manager::getMinimumMarkerScreenPercent,
            (val) -> manager.setMinimumMarkerScreenPercent((float) val),
            "Min Portal Marker Size (%)", 0.0, 20.0, 1);

        // Line render preset cycle button
        y += 35;
        WidgetLabel presetLabel = new WidgetLabel(x + 12, y, 200, 10, 0xFFFFFFFF, "Line Render Detail");
        this.addWidget(presetLabel);
        y += 10;
        ButtonGeneric presetButton = new ButtonGeneric(x + 12, y, 100, 20,
            "Detail: " + manager.getLineRenderPreset().getDisplayName());
        this.addButton(presetButton, (button, mouseButton) -> {
            PortalManager.LineRenderPreset newPreset = manager.getLineRenderPreset().next();
            manager.setLineRenderPreset(newPreset);
            presetButton.setDisplayString("Detail: " + newPreset.getDisplayName());
        });

        // LOD preset cycle button
        y += 25;
        WidgetLabel lodPresetLabel = new WidgetLabel(x + 12, y, 200, 10, 0xFFFFFFFF, "LOD Preset");
        this.addWidget(lodPresetLabel);
        y += 10;
        ButtonGeneric lodPresetButton = new ButtonGeneric(x + 12, y, 100, 20,
            "LOD: " + manager.getLodPreset().getDisplayName());
        this.addButton(lodPresetButton, (button, mouseButton) -> {
            com.portalzone.voronoi.VoronoiCalculator.LodPreset newLodPreset = manager.getLodPreset().next();
            manager.setLodPreset(newLodPreset);
            lodPresetButton.setDisplayString("LOD: " + newLodPreset.getDisplayName());
        });

        // LOD 0 distance
        y += 25;
        this.createIntField(x, y,
            manager::getLod0Distance,
            manager::setLod0Distance,
            "LOD 0 Distance", 4, 256);

        // Portal marker draw distance
        int rightX = x + 250;
        y = 20;
        this.createPortalMarkerDistanceControls(rightX, y, manager);

        // Border draw distance
        y += 50;
        this.createDoubleSlider(rightX, y,
            manager::getBorderDrawDistance,
            manager::setBorderDrawDistance,
            "Border Draw Distance", 128.0, 2048.0);

        // Clear current button
        y += 40;
        ButtonGeneric clearCurrentButton = new ButtonGeneric(rightX, y, 100, 20, "Clear Current");
        this.addButton(clearCurrentButton, (button, mouseButton) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                manager.clearCurrentDimension(mc.level.dimension());
                this.loadPortals();
            }
        });

        // Clear all button
        ButtonGeneric clearAllButton = new ButtonGeneric(rightX + 110, y, 100, 20, "Clear All");
        this.addButton(clearAllButton, (button, mouseButton) -> {
            manager.clearAllPortals();
            this.loadPortals();
        });

        // Add simulated button
        y += 25;
        ButtonGeneric addSimulatedButton = new ButtonGeneric(rightX, y, 210, 20, "Add Simulated Portal");
        this.addButton(addSimulatedButton, (button, mouseButton) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null) {
                BlockPos pos = mc.player.blockPosition();
                manager.addSimulatedPortal(mc.level.dimension(), pos);
                this.loadPortals();
            }
        });
    }

    private void createDoubleField(int x, int y,
                                   java.util.function.DoubleSupplier supplier,
                                   java.util.function.DoubleConsumer consumer,
                                   String label, double min, double max, double step) {
        WidgetLabel labelWidget = new WidgetLabel(x + 12, y, 200, 10, 0xFFFFFFFF, label);
        this.addWidget(labelWidget);
        y += 10;

        GuiTextFieldDouble textField = new GuiTextFieldDouble(x + 12, y, 60, 16, this.textRenderer);
        textField.setTextWrapper(String.valueOf(supplier.getAsDouble()));
        this.addTextField(textField, new ITextFieldListener<GuiTextFieldDouble>() {
            @Override
            public boolean onTextChange(GuiTextFieldDouble field) {
                String text = field.getTextWrapper();
                try {
                    double value = Double.parseDouble(text);
                    if (!Double.isFinite(value)) {
                        return false;
                    }
                    double clamped = Math.max(min, Math.min(max, value));
                    if (clamped != value) {
                        field.setTextWrapper(String.valueOf(clamped));
                    }
                    consumer.accept(clamped);
                } catch (NumberFormatException ignored) {
                    return false;
                }
                return true;
            }
        });

        ButtonGeneric button = new ButtonGeneric(x + 74, y, MaLiLibIcons.BTN_PLUSMINUS_16);
        this.addButton(button, (btn, mouseButton) -> {
            try {
                double current = Double.parseDouble(textField.getTextWrapper());
                if (!Double.isFinite(current)) {
                    throw new NumberFormatException("Non-finite input");
                }
                double delta = step * getClickStepMultiplier();
                delta = (mouseButton == 1) ? -delta : delta;
                double next = Math.max(min, Math.min(max, current + delta));
                consumer.accept(next);
                textField.setTextWrapper(String.valueOf(next));
            } catch (NumberFormatException e) {
                // Fallback to supplier if text field has invalid value
                double current = supplier.getAsDouble();
                textField.setTextWrapper(String.valueOf(current));
            }
        });
    }

    private void createIntField(int x, int y,
                                java.util.function.IntSupplier supplier,
                                java.util.function.IntConsumer consumer,
                                String label, int min, int max) {
        WidgetLabel labelWidget = new WidgetLabel(x + 12, y, 200, 10, 0xFFFFFFFF, label);
        this.addWidget(labelWidget);
        y += 10;

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + 12, y, 60, 16, this.textRenderer);
        textField.setTextWrapper(String.valueOf(supplier.getAsInt()));
        this.addTextField(textField, new ITextFieldListener<GuiTextFieldInteger>() {
            @Override
            public boolean onTextChange(GuiTextFieldInteger field) {
                String text = field.getTextWrapper();
                try {
                    int value = Integer.parseInt(text);
                    int clamped = Math.max(min, Math.min(max, value));
                    if (clamped != value) {
                        field.setTextWrapper(String.valueOf(clamped));
                    }
                    consumer.accept(clamped);
                } catch (NumberFormatException ignored) {
                    return false;
                }
                return true;
            }
        });

        ButtonGeneric button = new ButtonGeneric(x + 74, y, MaLiLibIcons.BTN_PLUSMINUS_16);
        this.addButton(button, (btn, mouseButton) -> {
            try {
                int current = Integer.parseInt(textField.getTextWrapper());
                int delta = getClickStepMultiplier();
                delta = (mouseButton == 1) ? -delta : delta;
                int next = Math.max(min, Math.min(max, current + delta));
                consumer.accept(next);
                textField.setTextWrapper(String.valueOf(next));
            } catch (NumberFormatException e) {
                // Fallback to supplier if text field has invalid value
                int current = supplier.getAsInt();
                textField.setTextWrapper(String.valueOf(current));
            }
        });
    }

    private void createDoubleSlider(int x, int y,
                                    java.util.function.DoubleSupplier supplier,
                                    java.util.function.DoubleConsumer consumer,
                                    String label, double min, double max) {
        createDoubleSlider(x, y, supplier, consumer, label, min, max, false);
    }

    private void createDoubleSlider(int x, int y,
                                    java.util.function.DoubleSupplier supplier,
                                    java.util.function.DoubleConsumer consumer,
                                    String label, double min, double max, boolean showDecimals) {
        WidgetLabel labelWidget = new WidgetLabel(x + 12, y, 200, 10, 0xFFFFFFFF, label);
        this.addWidget(labelWidget);
        y += 10;

        GuiTextFieldDouble textField = new GuiTextFieldDouble(x + 12, y, 60, 16, this.textRenderer);
        if (showDecimals) {
            textField.setTextWrapper(String.format("%.1f", supplier.getAsDouble()));
        } else {
            textField.setTextWrapper(String.valueOf((int)supplier.getAsDouble()));
        }

        WidgetSlider slider = new WidgetSlider(
            x + 74,
            y + 1,
            140,
            12,
            new ISliderCallback() {
                @Override
                public int getMaxSteps() {
                    return showDecimals ? (int)((max - min) * 10) : (int)(max - min);
                }

                @Override
                public double getValueRelative() {
                    return (supplier.getAsDouble() - min) / (max - min);
                }

                @Override
                public void setValueRelative(double value) {
                    double actualValue = min + (value * (max - min));
                    consumer.accept(actualValue);
                    if (showDecimals) {
                        textField.setTextWrapper(String.format("%.1f", actualValue));
                    } else {
                        textField.setTextWrapper(String.valueOf((int)actualValue));
                    }
                }

                @Override
                public String getFormattedDisplayValue() {
                    if (showDecimals) {
                        return String.format("%.1f", supplier.getAsDouble());
                    }
                    return String.valueOf((int)supplier.getAsDouble());
                }
            }
        );
        this.addWidget(slider);

        this.addTextField(textField, new ITextFieldListener<GuiTextFieldDouble>() {
            @Override
            public boolean onTextChange(GuiTextFieldDouble field) {
                String text = field.getTextWrapper();
                try {
                    double value = Double.parseDouble(text);
                    double clamped = Math.max(min, Math.min(max, value));
                    if (clamped != value) {
                        if (showDecimals) {
                            field.setTextWrapper(String.format("%.1f", clamped));
                        } else {
                            field.setTextWrapper(String.valueOf((int)clamped));
                        }
                    }
                    consumer.accept(clamped);
                } catch (NumberFormatException ignored) {
                    return false;
                }
                return true;
            }
        });
    }

    private void createPortalMarkerDistanceControls(int x, int y, PortalManager manager) {
        double min = 128.0;
        double max = 2048.0;
        if (!manager.isPortalMarkerDrawDistanceInfinite()) {
            lastFinitePortalMarkerDrawDistance = manager.getPortalMarkerDrawDistance();
        }

        WidgetLabel labelWidget = new WidgetLabel(x + 12, y, 200, 10, 0xFFFFFFFF, "Portal Marker Draw Distance");
        this.addWidget(labelWidget);
        y += 10;

        GuiTextFieldDouble textField = new GuiTextFieldDouble(x + 12, y, 60, 16, this.textRenderer);
        textField.setTextWrapper(String.valueOf((int) getPortalMarkerDistanceForDisplay(manager, max)));

        WidgetCheckBox infiniteCheckbox = new WidgetCheckBox(
            x + 12,
            y + 18,
            MaLiLibIcons.MINUS,
            MaLiLibIcons.PLUS,
            "Infinite Marker Distance"
        );
        infiniteCheckbox.setChecked(manager.isPortalMarkerDrawDistanceInfinite());
        infiniteCheckbox.setListener((checkBox) -> {
            if (checkBox.isChecked()) {
                manager.setPortalMarkerDrawDistance(-1.0);
                textField.setTextWrapper(String.valueOf((int) max));
            } else {
                double next = Math.max(min, Math.min(max, lastFinitePortalMarkerDrawDistance));
                manager.setPortalMarkerDrawDistance(next);
                textField.setTextWrapper(String.valueOf((int) next));
            }
        });
        this.addWidget(infiniteCheckbox);

        WidgetSlider slider = new WidgetSlider(
            x + 74,
            y + 1,
            140,
            12,
            new ISliderCallback() {
                @Override
                public int getMaxSteps() {
                    return (int) (max - min);
                }

                @Override
                public double getValueRelative() {
                    double display = getPortalMarkerDistanceForDisplay(manager, max);
                    return (display - min) / (max - min);
                }

                @Override
                public void setValueRelative(double value) {
                    double actualValue = min + (value * (max - min));
                    lastFinitePortalMarkerDrawDistance = actualValue;
                    if (manager.isPortalMarkerDrawDistanceInfinite()) {
                        infiniteCheckbox.setChecked(false);
                    }
                    manager.setPortalMarkerDrawDistance(actualValue);
                    textField.setTextWrapper(String.valueOf((int) actualValue));
                }

                @Override
                public String getFormattedDisplayValue() {
                    return String.valueOf((int) getPortalMarkerDistanceForDisplay(manager, max));
                }
            }
        );
        this.addWidget(slider);

        this.addTextField(textField, new ITextFieldListener<GuiTextFieldDouble>() {
            @Override
            public boolean onTextChange(GuiTextFieldDouble field) {
                String text = field.getTextWrapper();
                try {
                    double value = Double.parseDouble(text);
                    double clamped = Math.max(min, Math.min(max, value));
                    if (clamped != value) {
                        field.setTextWrapper(String.valueOf((int) clamped));
                    }
                    lastFinitePortalMarkerDrawDistance = clamped;
                    if (manager.isPortalMarkerDrawDistanceInfinite()) {
                        infiniteCheckbox.setChecked(false);
                    }
                    manager.setPortalMarkerDrawDistance(clamped);
                } catch (NumberFormatException ignored) {
                    return false;
                }
                return true;
            }
        });
    }

    private static double getPortalMarkerDistanceForDisplay(PortalManager manager, double max) {
        return manager.isPortalMarkerDrawDistanceInfinite()
            ? max
            : manager.getPortalMarkerDrawDistance();
    }

    private void loadPortals() {
        // Clear existing entries
        portalEntries.clear();
        listEntries.clear();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        ResourceKey<Level> currentDim = mc.level.dimension();
        PortalManager manager = PortalManager.getInstance();

        // Add portals from other dimension first
        ResourceKey<Level> otherDim = currentDim == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        addHeaderEntry(otherDim);
        for (PortalInfo portal : manager.getPortalsInDimension(otherDim)) {
            PortalEntry entry = new PortalEntry(portal, otherDim, false);
            portalEntries.add(entry);
            listEntries.add(ListEntry.portal(entry));
        }

        // Add portals from current dimension
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

        // Initialize widgets for each portal
        int yPos = LIST_START_Y;
        for (ListEntry entry : listEntries) {
            if (entry.type != EntryType.PORTAL) {
                yPos += entry.height;
                continue;
            }

            PortalEntry portalEntry = entry.portalEntry;
            createPortalWidgets(portalEntry, yPos);
            yPos += entry.height;
        }
    }

    private void createPortalWidgets(PortalEntry entry, int y) {
        PortalManager manager = PortalManager.getInstance();
        int leftX = this.width / 2 - 200;

        // Portal name field
        int nameX = leftX + 200;
        WidgetLabel nameLabel = new WidgetLabel(nameX, y + 2, 50, 10, 0xFFFFFFFF, "Name:");
        this.addWidget(nameLabel);
        entry.nameLabel = nameLabel;

        GuiTextFieldGeneric nameField = new GuiTextFieldGeneric(nameX + 40, y, 200, 16, this.textRenderer);
        nameField.setTextWrapper(manager.getPortalName(entry.portal.uuid));
        this.addTextField(nameField, new ITextFieldListener<GuiTextFieldGeneric>() {
            @Override
            public boolean onTextChange(GuiTextFieldGeneric field) {
                manager.setPortalName(entry.portal.uuid, field.getTextWrapper());
                return true;
            }
        });
        entry.nameField = nameField;

        // Position editing with +- buttons
        y += 20;
        WidgetLabel posLabel = new WidgetLabel(nameX, y + 2, 60, 10, 0xFFFFFFFF, "Position:");
        this.addWidget(posLabel);
        entry.positionLabel = posLabel;

        // X coordinate
        int coordX = nameX + 50;
        boolean isEditable = entry.portal.isSimulated();
        this.createCoordinateField(coordX, y,
            () -> entry.portal.position.getX(),
            (newX) -> updatePortalPosition(entry, newX, entry.portal.position.getY(), entry.portal.position.getZ()),
            "X:", entry, isEditable);

        // Y coordinate
        coordX += 80;
        this.createCoordinateField(coordX, y,
            () -> entry.portal.position.getY(),
            (newY) -> updatePortalPosition(entry, entry.portal.position.getX(), newY, entry.portal.position.getZ()),
            "Y:", entry, isEditable);

        // Z coordinate
        coordX += 80;
        this.createCoordinateField(coordX, y,
            () -> entry.portal.position.getZ(),
            (newZ) -> updatePortalPosition(entry, entry.portal.position.getX(), entry.portal.position.getY(), newZ),
            "Z:", entry, isEditable);

        // Hue slider for color (all portals, including simulated with 25% saturation)
        y += 20;
        String hueLabel = entry.portal.isSimulated() ? "Hue (25% sat):" : "Hue:";
        WidgetLabel hueLabelWidget = new WidgetLabel(nameX, y + 2, 100, 10, 0xFFFFFFFF, hueLabel);
        this.addWidget(hueLabelWidget);
        entry.hueLabel = hueLabelWidget;

        double currentHue = manager.getPortalHue(entry.portal);
        GuiTextFieldDouble hueField = new GuiTextFieldDouble(nameX + 90, y, 50, 16, this.textRenderer);
        hueField.setTextWrapper(String.valueOf(currentHue));
        this.addTextField(hueField, new ITextFieldListener<GuiTextFieldDouble>() {
            @Override
            public boolean onTextChange(GuiTextFieldDouble field) {
                try {
                    double hue = Double.parseDouble(field.getTextWrapper());
                    float wrappedHue = (float) ((hue % 360.0 + 360.0) % 360.0);
                    manager.setPortalHue(entry.portal.uuid, wrappedHue);
                } catch (NumberFormatException ignored) {
                    return false;
                }
                return true;
            }
        });
        entry.hueField = hueField;

        // Hue slider
        WidgetSlider hueSlider = new WidgetSlider(
            nameX + 145,
            y + 1,
            120,
            12,
            new ISliderCallback() {
                @Override
                public int getMaxSteps() {
                    return 360;
                }

                @Override
                public double getValueRelative() {
                    return manager.getPortalHue(entry.portal) / 360.0;
                }

                @Override
                public void setValueRelative(double value) {
                    double hue = value * 360.0;
                    float wrappedHue = (float) ((hue % 360.0 + 360.0) % 360.0);
                    manager.setPortalHue(entry.portal.uuid, wrappedHue);
                    hueField.setTextWrapper(String.valueOf(manager.getPortalHue(entry.portal)));
                }

                @Override
                public String getFormattedDisplayValue() {
                    return String.format("%.1f", manager.getPortalHue(entry.portal));
                }
            }
        );
        this.addWidget(hueSlider);
        entry.hueSlider = hueSlider;

        // Hide/Show button
        y += 25;
        boolean isHidden = manager.isPortalHidden(entry.portal);
        ButtonGeneric hideButton = new ButtonGeneric(nameX, y, 45, 20, isHidden ? "Show" : "Hide");
        this.addButton(hideButton, (button, mouseButton) -> {
            boolean hidden = !manager.isPortalHidden(entry.portal);
            manager.setPortalHidden(entry.portal.uuid, hidden);
            hideButton.setDisplayString(hidden ? "Show" : "Hide");
        });
        entry.hideButton = hideButton;

        // Remove button for simulated portals
        if (entry.portal.isSimulated()) {
            ButtonGeneric removeButton = new ButtonGeneric(nameX + 50, y, 18, 20, "-");
            this.addButton(removeButton, (button, mouseButton) -> {
                manager.removeSimulatedPortal(entry.portal.uuid);
                this.loadPortals();
            });
            entry.removeButton = removeButton;
        }
    }

    private void createCoordinateField(int x, int y,
                                       java.util.function.IntSupplier supplier,
                                       java.util.function.IntConsumer consumer,
                                       String label,
                                       PortalEntry entry,
                                       boolean isEditable) {
        WidgetLabel labelWidget = new WidgetLabel(x - 15, y + 2, 20, 10, 0xFFFFFFFF, label);
        this.addWidget(labelWidget);
        entry.coordLabels.add(labelWidget);

        if (isEditable) {
            // Create editable text field and +- button for simulated portals
            GuiTextFieldInteger textField = new GuiTextFieldInteger(x, y, 40, 16, this.textRenderer);
            textField.setTextWrapper(String.valueOf(supplier.getAsInt()));
            this.addTextField(textField, new ITextFieldListener<GuiTextFieldInteger>() {
                @Override
                public boolean onTextChange(GuiTextFieldInteger field) {
                    String text = field.getTextWrapper();
                    try {
                        int value = Integer.parseInt(text);
                        consumer.accept(value);
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                    return true;
                }
            });
            entry.coordFields.add(textField);

            ButtonGeneric button = new ButtonGeneric(x + 42, y, MaLiLibIcons.BTN_PLUSMINUS_16);
            this.addButton(button, (btn, mouseButton) -> {
                try {
                    int current = Integer.parseInt(textField.getTextWrapper());
                    int delta = getClickStepMultiplier();
                    delta = (mouseButton == 1) ? -delta : delta;
                    int next = current + delta;
                    consumer.accept(next);
                    textField.setTextWrapper(String.valueOf(next));
                } catch (NumberFormatException e) {
                    // Fallback to supplier if text field has invalid value
                    int current = supplier.getAsInt();
                    textField.setTextWrapper(String.valueOf(current));
                }
            });
            entry.coordButtons.add(button);
        } else {
            // Just show the value as a label for real portals
            WidgetLabel valueLabel = new WidgetLabel(x, y + 2, 40, 10, 0xFFAAAAAA, String.valueOf(supplier.getAsInt()));
            this.addWidget(valueLabel);
            entry.coordLabels.add(valueLabel);
        }
    }

    private int getClickStepMultiplier() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasControlDown() && mc.hasShiftDown()) {
            return 100;
        }
        if (mc.hasControlDown()) {
            return 50;
        }
        if (mc.hasShiftDown()) {
            return 10;
        }
        return 1;
    }

    private void updatePortalPosition(PortalEntry entry, int x, int y, int z) {
        // For simulated portals, we need to update the position
        // For real portals, this would be read-only, but we'll allow it for simulated ones
        if (entry.portal.isSimulated()) {
            BlockPos newPos = new BlockPos(x, y, z);
            PortalManager manager = PortalManager.getInstance();
            // Remove old and add new with updated position
            manager.removeSimulatedPortal(entry.portal.uuid);
            manager.addSimulatedPortal(entry.dimension, newPos);
            this.loadPortals();
        }
    }

    private void renderPortalEntry(GuiGraphics graphics, PortalEntry entry, int y) {
        int leftX = this.width / 2 - 200;

        // Draw color indicator box
        Vector3f color = PortalManager.getInstance().getPortalColor(entry.portal);
        int colorInt = 0xFF000000 |
                ((int)(color.x * 255) << 16) |
                ((int)(color.y * 255) << 8) |
                (int)(color.z * 255);
        graphics.fill(leftX, y, leftX + 20, y + 20, colorInt);

        // Draw portal ID
        String idText = entry.portal.getShortId();
        graphics.drawString(this.textRenderer, idText, leftX + 25, y + 6, 0xFFFFFFFF);

        // Draw dimension info
        String dimText = entry.portal.isSimulated()
            ? "(Simulated)"
            : (entry.isCurrentDimension ? "(Current)" : "(Other)");
        graphics.drawString(this.textRenderer, dimText, leftX + 65, y + 6, 0xFFAAAAAA);
    }

    private void updatePortalWidgetPositions(PortalEntry entry, int y) {
        // Update positions of all widgets for this portal entry based on scroll
        entry.currentY = y;

        int nameX = this.width / 2 - 200 + 200;

        // Update name label and field positions
        if (entry.nameLabel != null) {
            entry.nameLabel.setY(y + 2);
        }
        if (entry.nameField != null) {
            setTextFieldY(entry.nameField, y);
        }

        // Update position label
        int coordY = y + 20;
        if (entry.positionLabel != null) {
            entry.positionLabel.setY(coordY + 2);
        }

        // Update coordinate labels, fields, and buttons
        for (WidgetLabel label : entry.coordLabels) {
            label.setY(coordY + 2);
        }
        for (GuiTextFieldInteger field : entry.coordFields) {
            setTextFieldY(field, coordY);
        }
        for (ButtonGeneric button : entry.coordButtons) {
            button.setY(coordY);
        }

        // Update hue label, field and slider positions if they exist
        if (entry.hueLabel != null) {
            entry.hueLabel.setY(coordY + 22);
        }
        if (entry.hueField != null) {
            setTextFieldY(entry.hueField, coordY + 20);
        }
        if (entry.hueSlider != null) {
            entry.hueSlider.setY(coordY + 21);
        }

        // Update hide button position
        if (entry.hideButton != null) {
            int buttonY = coordY + 45;
            entry.hideButton.setY(buttonY);
        }

        // Update remove button position (for simulated portals)
        if (entry.removeButton != null) {
            int buttonY = coordY + 45;
            entry.removeButton.setY(buttonY);
        }
    }

    private static void setTextFieldY(GuiTextFieldGeneric field, int y) {
        field.setYWrapper(y);
        if (EDIT_BOX_LAYOUT != null) {
            try {
                EDIT_BOX_LAYOUT.invoke(field);
            } catch (ReflectiveOperationException ignored) {
                // Fall back to the wrapper position if the reflective call fails.
            }
        }
    }

    private static Method findEditBoxLayoutMethod() {
        Method named = findDeclaredMethod(net.minecraft.client.gui.components.EditBox.class, "method_71504");
        if (named != null) {
            return named;
        }

        for (Method method : net.minecraft.client.gui.components.EditBox.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())
                && method.getReturnType() == void.class
                && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method;
            }
        }

        return null;
    }

    private static Method findDeclaredMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method method = owner.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private void renderHeader(GuiGraphics graphics, String text, int y) {
        int leftX = this.width / 2 - 200;
        graphics.drawString(this.textRenderer, text, leftX, y + 4, 0xFFCCCCCC);
    }

    private void addHeaderEntry(ResourceKey<Level> dimension) {
        String label = Level.NETHER.equals(dimension) ? "Nether Portals" : "Overworld Portals";
        listEntries.add(ListEntry.header(label));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(scrollY * SCROLL_SPEED)));
        return true;
    }

    @Override
    protected void drawScreenBackground(GuiGraphics graphics, int mouseX, int mouseY) {
        super.drawScreenBackground(graphics, mouseX, mouseY);

        // Check if the portal management key is pressed to close the GUI
        // We track state to only trigger once per press (not every frame while held)
        boolean isKeyDown = PortalZoneVisualizerClient.portalManagementKey.isDown();
        if (isKeyDown && !wasKeyDown) {
            this.closeGui(true);
            return;
        }
        wasKeyDown = isKeyDown;

        // Draw title
        graphics.drawCenteredString(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);

        // Draw portal entries with scissor test for scrolling
        int viewportTop = VIEWPORT_TOP;
        int viewportBottom = this.height - VIEWPORT_BOTTOM_MARGIN;
        int y = LIST_START_Y - scrollOffset;

        // Update all widget positions first (even for off-screen entries)
        int updateY = LIST_START_Y - scrollOffset;
        for (ListEntry entry : listEntries) {
            if (entry.type == EntryType.PORTAL) {
                updatePortalWidgetPositions(entry.portalEntry, updateY);
            }
            updateY += entry.height;
        }

        // Then render only visible entries
        y = LIST_START_Y - scrollOffset;
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
    }

    @Override
    public void onClose() {
        super.onClose();
        PortalManager.getInstance().saveSettingsNow();
    }

    private static class PortalEntry {
        final PortalInfo portal;
        final ResourceKey<Level> dimension;
        final boolean isCurrentDimension;
        GuiTextFieldGeneric nameField;
        WidgetLabel nameLabel;
        final List<GuiTextFieldInteger> coordFields = new ArrayList<>();
        final List<ButtonGeneric> coordButtons = new ArrayList<>();
        final List<WidgetLabel> coordLabels = new ArrayList<>();
        WidgetLabel positionLabel;
        GuiTextFieldDouble hueField;
        WidgetSlider hueSlider;
        WidgetLabel hueLabel;
        ButtonGeneric hideButton;
        ButtonGeneric removeButton;
        int currentY;

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
}
