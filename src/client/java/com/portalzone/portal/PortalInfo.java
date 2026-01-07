package com.portalzone.portal;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stores information about a detected Nether portal
 */
public class PortalInfo {
    public final BlockPos position;
    public final ResourceKey<Level> dimension;
    public final UUID uuid;
    public final Vector3f color;
    private final float baseHue;
    public final Axis orientation;

    // Portal dimensions
    public final int width;
    public final int height;

    // Persistence and validation
    private long lastValidated;
    private boolean isValid;

    public enum Axis {
        X, Z
    }

    public PortalInfo(BlockPos position, ResourceKey<Level> dimension, Axis orientation, int width, int height) {
        this.position = position;
        this.dimension = dimension;
        this.orientation = orientation;
        this.width = width;
        this.height = height;

        // Generate UUID based on position and dimension
        // This ensures the same portal always has the same UUID and color
        this.uuid = generateUUID(position, dimension);
        this.baseHue = generateHue(uuid);
        this.color = colorFromHue(baseHue);

        // Initialize persistence fields
        this.lastValidated = System.currentTimeMillis();
        this.isValid = true;
    }

    /**
     * Private constructor for deserialization
     */
    private PortalInfo(BlockPos position, ResourceKey<Level> dimension, UUID uuid, Axis orientation,
                      int width, int height, long lastValidated, boolean isValid) {
        this.position = position;
        this.dimension = dimension;
        this.uuid = uuid;
        this.orientation = orientation;
        this.width = width;
        this.height = height;
        this.lastValidated = lastValidated;
        this.isValid = isValid;

        this.baseHue = generateHue(uuid);
        this.color = colorFromHue(baseHue);
    }

    /**
     * Get the center position of the portal as a Vec3
     */
    public Vec3 getCenterPos() {
        return new Vec3(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
    }

    /**
     * Get the translated position in the other dimension
     */
    public Vec3 getTranslatedPos() {
        boolean isNether = dimension == Level.NETHER;
        double scale = isNether ? 8.0 : 0.125;

        return new Vec3(
            position.getX() * scale + 0.5,
            position.getY() + 0.5,
            position.getZ() * scale + 0.5
        );
    }

    /**
     * Get the dimension this portal links to
     */
    public ResourceKey<Level> getLinkedDimension() {
        return dimension == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
    }

    /**
     * Get the last 3 characters of the UUID for display
     */
    public String getShortId() {
        String uuidStr = uuid.toString().replace("-", "");
        return uuidStr.substring(Math.max(0, uuidStr.length() - 3));
    }

    /**
     * Get the last validated timestamp
     */
    public long getLastValidated() {
        return lastValidated;
    }

    /**
     * Set the last validated timestamp
     */
    public void setLastValidated(long timestamp) {
        this.lastValidated = timestamp;
    }

    /**
     * Check if the portal is valid
     */
    public boolean isValid() {
        return isValid;
    }

    /**
     * Set the portal validity
     */
    public void setValid(boolean valid) {
        this.isValid = valid;
    }

    /**
     * Get the default hue for this portal (based on UUID)
     */
    public float getBaseHue() {
        return baseHue;
    }

    /**
     * Generate a consistent UUID for this portal based on its position and dimension
     */
    private static UUID generateUUID(BlockPos pos, ResourceKey<Level> dimension) {
        // Use position and dimension to create a consistent UUID
        String key = dimension.location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a consistent hue based on UUID
     */
    private static float generateHue(UUID uuid) {
        // Use UUID bytes to generate a vibrant hue
        long bits = uuid.getMostSignificantBits();

        // Extract hue from UUID
        return ((bits & 0xFFFF) / 65535.0f) * 360.0f;
    }

    /**
     * Convert a hue to an RGB color (HSV with fixed saturation/value)
     */
    public static Vector3f colorFromHue(float hue) {
        // Use HSV to RGB conversion for vibrant colors
        // Saturation = 0.8, Value = 1.0 for bright colors
        float saturation = 0.8f;
        float value = 1.0f;

        float c = value * saturation;
        float x = c * (1 - Math.abs(((hue / 60.0f) % 2) - 1));
        float m = value - c;

        float r, g, b;
        if (hue < 60) {
            r = c; g = x; b = 0;
        } else if (hue < 120) {
            r = x; g = c; b = 0;
        } else if (hue < 180) {
            r = 0; g = c; b = x;
        } else if (hue < 240) {
            r = 0; g = x; b = c;
        } else if (hue < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }

        return new Vector3f(r + m, g + m, b + m);
    }

    /**
     * Serialize this portal to JSON
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        // Position
        JsonObject posJson = new JsonObject();
        posJson.addProperty("x", position.getX());
        posJson.addProperty("y", position.getY());
        posJson.addProperty("z", position.getZ());
        json.add("position", posJson);

        // Dimension
        json.addProperty("dimension", dimension.location().toString());

        // UUID
        json.addProperty("uuid", uuid.toString());

        // Orientation
        json.addProperty("axis", orientation.name());

        // Dimensions
        json.addProperty("width", width);
        json.addProperty("height", height);

        // Validation
        json.addProperty("lastValidated", lastValidated);
        json.addProperty("isValid", isValid);

        return json;
    }

    /**
     * Deserialize a portal from JSON
     */
    public static PortalInfo fromJson(JsonObject json) {
        // Parse position
        JsonObject posJson = json.getAsJsonObject("position");
        BlockPos position = new BlockPos(
            posJson.get("x").getAsInt(),
            posJson.get("y").getAsInt(),
            posJson.get("z").getAsInt()
        );

        // Parse dimension
        ResourceLocation dimLocation = ResourceLocation.parse(json.get("dimension").getAsString());
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimLocation);

        // Parse UUID
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());

        // Parse orientation
        Axis orientation = Axis.valueOf(json.get("axis").getAsString());

        // Parse dimensions
        int width = json.get("width").getAsInt();
        int height = json.get("height").getAsInt();

        // Parse validation (with defaults for backwards compatibility)
        long lastValidated = json.has("lastValidated") ? json.get("lastValidated").getAsLong() : System.currentTimeMillis();
        boolean isValid = json.has("isValid") ? json.get("isValid").getAsBoolean() : true;

        return new PortalInfo(position, dimension, uuid, orientation, width, height, lastValidated, isValid);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PortalInfo other)) return false;
        return this.uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
