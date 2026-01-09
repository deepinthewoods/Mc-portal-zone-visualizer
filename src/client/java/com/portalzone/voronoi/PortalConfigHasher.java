package com.portalzone.voronoi;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Calculates stable hash codes for portal configurations to detect when cached
 * Voronoi chunks need to be invalidated.
 *
 * <p>Uses the FNV-1a (Fowler-Noll-Vo) hash algorithm for fast, stable hashing.
 * The hash includes:
 * <ul>
 *   <li>Portal positions (Vec3 coordinates)</li>
 *   <li>Portal colors (Vector3f RGB values)</li>
 *   <li>Hidden portal states (boolean array)</li>
 *   <li>Neutral borders setting (boolean)</li>
 * </ul>
 *
 * <p>The hash will change whenever:
 * <ul>
 *   <li>A portal is added or removed</li>
 *   <li>A portal moves to a different position</li>
 *   <li>A portal's color changes</li>
 *   <li>A portal is hidden or unhidden</li>
 *   <li>The neutral borders setting is toggled</li>
 * </ul>
 *
 * <p>The hash will be identical for the same portal configuration, making it
 * suitable for cache invalidation detection.
 */
public class PortalConfigHasher {

    // FNV-1a 64-bit hash constants
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * Calculates a hash for the current portal configuration.
     *
     * <p>This method uses the FNV-1a hash algorithm to create a stable hash that:
     * <ul>
     *   <li>Changes when any portal parameter changes</li>
     *   <li>Is identical for the same portal configuration</li>
     *   <li>Distributes hash values uniformly</li>
     *   <li>Computes quickly (O(n) where n = number of portals)</li>
     * </ul>
     *
     * @param portals Array of portal positions (may be null if no portals)
     * @param colors Array of portal colors (must match portals length if portals not null)
     * @param hidden Array of hidden states for each portal (must match portals length if portals not null)
     * @param neutralBorders Whether neutral (grey) borders should be rendered
     * @return A 64-bit hash code representing the current portal configuration
     * @throws IllegalArgumentException if arrays have mismatched lengths
     */
    public static long calculatePortalConfigHash(Vec3[] portals, Vector3f[] colors,
                                                  boolean[] hidden, boolean neutralBorders) {
        long hash = FNV_OFFSET_BASIS;

        // Hash the neutral borders setting first
        hash = hashBoolean(hash, neutralBorders);

        // Handle null/empty portal arrays
        if (portals == null || portals.length == 0) {
            // Hash a sentinel value for empty configuration
            hash = hashLong(hash, 0L);
            return hash;
        }

        // Validate array lengths
        if (colors == null || hidden == null) {
            throw new IllegalArgumentException("colors and hidden arrays must not be null when portals array is non-null");
        }
        if (colors.length != portals.length || hidden.length != portals.length) {
            throw new IllegalArgumentException(
                String.format("Array length mismatch: portals=%d, colors=%d, hidden=%d",
                              portals.length, colors.length, hidden.length));
        }

        // Hash the number of portals
        hash = hashInt(hash, portals.length);

        // Hash each portal's data
        for (int i = 0; i < portals.length; i++) {
            Vec3 portal = portals[i];
            Vector3f color = colors[i];
            boolean isHidden = hidden[i];

            // Hash portal position (x, y, z)
            if (portal != null) {
                hash = hashDouble(hash, portal.x);
                hash = hashDouble(hash, portal.y);
                hash = hashDouble(hash, portal.z);
            } else {
                // Hash sentinel for null portal
                hash = hashLong(hash, -1L);
            }

            // Hash portal color (r, g, b)
            if (color != null) {
                hash = hashFloat(hash, color.x);
                hash = hashFloat(hash, color.y);
                hash = hashFloat(hash, color.z);
            } else {
                // Hash sentinel for null color
                hash = hashLong(hash, -2L);
            }

            // Hash hidden state
            hash = hashBoolean(hash, isHidden);
        }

        return hash;
    }

    /**
     * Hashes a single byte using FNV-1a algorithm.
     *
     * @param hash Current hash value
     * @param value Byte to hash
     * @return Updated hash value
     */
    private static long hashByte(long hash, byte value) {
        hash ^= (value & 0xff);
        hash *= FNV_PRIME;
        return hash;
    }

    /**
     * Hashes an integer by processing each byte.
     *
     * @param hash Current hash value
     * @param value Integer to hash
     * @return Updated hash value
     */
    private static long hashInt(long hash, int value) {
        hash = hashByte(hash, (byte) (value));
        hash = hashByte(hash, (byte) (value >>> 8));
        hash = hashByte(hash, (byte) (value >>> 16));
        hash = hashByte(hash, (byte) (value >>> 24));
        return hash;
    }

    /**
     * Hashes a long by processing each byte.
     *
     * @param hash Current hash value
     * @param value Long to hash
     * @return Updated hash value
     */
    private static long hashLong(long hash, long value) {
        hash = hashByte(hash, (byte) (value));
        hash = hashByte(hash, (byte) (value >>> 8));
        hash = hashByte(hash, (byte) (value >>> 16));
        hash = hashByte(hash, (byte) (value >>> 24));
        hash = hashByte(hash, (byte) (value >>> 32));
        hash = hashByte(hash, (byte) (value >>> 40));
        hash = hashByte(hash, (byte) (value >>> 48));
        hash = hashByte(hash, (byte) (value >>> 56));
        return hash;
    }

    /**
     * Hashes a float by converting to int bits.
     *
     * @param hash Current hash value
     * @param value Float to hash
     * @return Updated hash value
     */
    private static long hashFloat(long hash, float value) {
        return hashInt(hash, Float.floatToIntBits(value));
    }

    /**
     * Hashes a double by converting to long bits.
     *
     * @param hash Current hash value
     * @param value Double to hash
     * @return Updated hash value
     */
    private static long hashDouble(long hash, double value) {
        return hashLong(hash, Double.doubleToLongBits(value));
    }

    /**
     * Hashes a boolean as a single byte (0 or 1).
     *
     * @param hash Current hash value
     * @param value Boolean to hash
     * @return Updated hash value
     */
    private static long hashBoolean(long hash, boolean value) {
        return hashByte(hash, (byte) (value ? 1 : 0));
    }

    // Private constructor to prevent instantiation of utility class
    private PortalConfigHasher() {
        throw new AssertionError("Utility class should not be instantiated");
    }
}
