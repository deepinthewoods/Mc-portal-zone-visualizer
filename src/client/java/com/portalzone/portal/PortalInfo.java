package com.portalzone.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
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
