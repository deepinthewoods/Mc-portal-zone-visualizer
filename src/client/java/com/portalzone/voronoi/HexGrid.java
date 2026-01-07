package com.portalzone.voronoi;

import net.minecraft.world.phys.Vec3;

/**
 * Utility class for hexagonal grid calculations using axial coordinates.
 * Uses pointy-top orientation for hexagons.
 */
public class HexGrid {
    // Axial coordinates for a hexagon cell
    public static class HexCoord {
        public final int q; // column
        public final int r; // row
        public final int y; // vertical coordinate

        public HexCoord(int q, int r, int y) {
            this.q = q;
            this.r = r;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HexCoord)) return false;
            HexCoord other = (HexCoord) obj;
            return q == other.q && r == other.r && y == other.y;
        }

        @Override
        public int hashCode() {
            return ((q * 31) + r) * 31 + y;
        }

        @Override
        public String toString() {
            return "Hex(" + q + ", " + r + ", " + y + ")";
        }
    }

    /**
     * Convert world coordinates to hexagonal grid coordinates.
     * For pointy-top hexagons with specified radius.
     */
    public static HexCoord worldToHex(double x, double y, double z, double hexRadius) {
        // For pointy-top hexagons:
        // width = sqrt(3) * radius
        // height = 2 * radius
        double width = Math.sqrt(3.0) * hexRadius;
        double height = 2.0 * hexRadius;

        // Convert world XZ to axial coordinates
        double q = (Math.sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * z) / hexRadius;
        double r = (2.0 / 3.0 * z) / hexRadius;

        // Round to nearest hex
        return roundHex(q, r, (int) Math.round(y));
    }

    /**
     * Convert hexagonal grid coordinates to world position (center of hex).
     */
    public static Vec3 hexToWorld(HexCoord hex, double hexRadius) {
        double x = hexRadius * (Math.sqrt(3.0) * hex.q + Math.sqrt(3.0) / 2.0 * hex.r);
        double z = hexRadius * (3.0 / 2.0 * hex.r);
        return new Vec3(x, hex.y, z);
    }

    /**
     * Round fractional hex coordinates to nearest integer hex.
     */
    private static HexCoord roundHex(double q, double r, int y) {
        double s = -q - r;

        int rq = (int) Math.round(q);
        int rr = (int) Math.round(r);
        int rs = (int) Math.round(s);

        double qDiff = Math.abs(rq - q);
        double rDiff = Math.abs(rr - r);
        double sDiff = Math.abs(rs - s);

        if (qDiff > rDiff && qDiff > sDiff) {
            rq = -rr - rs;
        } else if (rDiff > sDiff) {
            rr = -rq - rs;
        }

        return new HexCoord(rq, rr, y);
    }

    /**
     * Get the 6 neighbors of a hexagon in axial coordinates.
     * Order: E, NE, NW, W, SW, SE
     */
    public static HexCoord[] getNeighbors(HexCoord hex) {
        return new HexCoord[] {
            new HexCoord(hex.q + 1, hex.r,     hex.y),  // E
            new HexCoord(hex.q + 1, hex.r - 1, hex.y),  // NE
            new HexCoord(hex.q,     hex.r - 1, hex.y),  // NW
            new HexCoord(hex.q - 1, hex.r,     hex.y),  // W
            new HexCoord(hex.q - 1, hex.r + 1, hex.y),  // SW
            new HexCoord(hex.q,     hex.r + 1, hex.y),  // SE
        };
    }

    /**
     * Get neighbors in +Y and -Y directions (vertical neighbors).
     */
    public static HexCoord[] getVerticalNeighbors(HexCoord hex, int ySpacing) {
        return new HexCoord[] {
            new HexCoord(hex.q, hex.r, hex.y + ySpacing),
            new HexCoord(hex.q, hex.r, hex.y - ySpacing)
        };
    }

    /**
     * Get all hexagons within a certain radius (in hex grid distance) from a center hex.
     */
    public static java.util.List<HexCoord> getHexesInRadius(HexCoord center, int radius) {
        java.util.List<HexCoord> results = new java.util.ArrayList<>();
        for (int q = -radius; q <= radius; q++) {
            int r1 = Math.max(-radius, -q - radius);
            int r2 = Math.min(radius, -q + radius);
            for (int r = r1; r <= r2; r++) {
                results.add(new HexCoord(center.q + q, center.r + r, center.y));
            }
        }
        return results;
    }

    /**
     * Calculate the 6 vertices of a hexagon in world space (for rendering edges).
     * Returns vertices in order around the hexagon perimeter.
     */
    public static Vec3[] getHexVertices(HexCoord hex, double hexRadius) {
        Vec3 center = hexToWorld(hex, hexRadius);
        Vec3[] vertices = new Vec3[6];

        // For pointy-top hexagons, vertices are at 30, 90, 150, 210, 270, 330 degrees
        for (int i = 0; i < 6; i++) {
            double angleDeg = 60.0 * i + 30.0;
            double angleRad = Math.toRadians(angleDeg);
            double x = center.x + hexRadius * Math.cos(angleRad);
            double z = center.z + hexRadius * Math.sin(angleRad);
            vertices[i] = new Vec3(x, center.y, z);
        }

        return vertices;
    }

    /**
     * Get the edge midpoint between two adjacent hexagons.
     * Returns null if hexagons are not adjacent.
     */
    public static Vec3 getEdgeMidpoint(HexCoord hex1, HexCoord hex2, double hexRadius) {
        // Check if they're neighbors (manhattan distance in cube coordinates = 1)
        int dq = Math.abs(hex1.q - hex2.q);
        int dr = Math.abs(hex1.r - hex2.r);
        int ds = Math.abs((-hex1.q - hex1.r) - (-hex2.q - hex2.r));

        if (dq + dr + ds != 2) {
            return null; // Not adjacent
        }

        Vec3 center1 = hexToWorld(hex1, hexRadius);
        Vec3 center2 = hexToWorld(hex2, hexRadius);
        return center1.add(center2).scale(0.5);
    }
}
