/**
 * Standalone test to verify portal linking algorithm logic
 * Run this to verify the coordinate transformations are correct
 *
 * Compile: javac PortalLinkingTest.java
 * Run: java PortalLinkingTest
 */
public class PortalLinkingTest {

    // Mock the search radius constants
    private static final int SEARCH_RADIUS_NETHER = 128;
    private static final int SEARCH_RADIUS_OVERWORLD = 1024;

    // Simple Vec3 class
    static class Vec3 {
        final double x, y, z;
        Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        public String toString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Portal Linking Algorithm Test ===\n");

        int passed = 0;
        int failed = 0;

        // Test 1: Overworld to Nether - exact match
        System.out.println("Test 1: Overworld position (800, 64, 800) -> Nether portal at (100, 64, 100)");
        {
            Vec3 overworldPos = new Vec3(800, 64, 800);
            Vec3 netherPortal = new Vec3(100, 64, 100);
            Vec3 translatedPos = translateToNether(overworldPos);
            double distance = horizontalDistance(translatedPos, netherPortal);

            System.out.println("  Overworld pos: " + overworldPos);
            System.out.println("  Translated to Nether: " + translatedPos);
            System.out.println("  Nether portal: " + netherPortal);
            System.out.println("  Horizontal distance: " + distance + " blocks");
            System.out.println("  Search radius (Nether): " + SEARCH_RADIUS_NETHER);

            if (distance <= SEARCH_RADIUS_NETHER) {
                System.out.println("  ✓ PASS: Within search radius, would link!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Outside search radius, would NOT link!");
                failed++;
            }
        }
        System.out.println();

        // Test 2: Overworld to Nether - just inside radius
        System.out.println("Test 2: Overworld position (1824, 64, 800) -> Nether portal at (100, 64, 100)");
        {
            Vec3 overworldPos = new Vec3(1824, 64, 800);
            Vec3 netherPortal = new Vec3(100, 64, 100);
            Vec3 translatedPos = translateToNether(overworldPos);
            double distance = horizontalDistance(translatedPos, netherPortal);

            System.out.println("  Overworld pos: " + overworldPos);
            System.out.println("  Translated to Nether: " + translatedPos);
            System.out.println("  Nether portal: " + netherPortal);
            System.out.println("  Horizontal distance: " + distance + " blocks");
            System.out.println("  Search radius (Nether): " + SEARCH_RADIUS_NETHER);
            System.out.println("  Expected: distance = 128, just at boundary");

            if (Math.abs(distance - 128.0) < 0.01) {
                System.out.println("  ✓ PASS: Correct distance calculation!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Distance calculation wrong!");
                failed++;
            }

            if (distance <= SEARCH_RADIUS_NETHER) {
                System.out.println("  ✓ PASS: Within search radius, would link!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Outside search radius, would NOT link!");
                failed++;
            }
        }
        System.out.println();

        // Test 3: Overworld to Nether - just outside radius
        System.out.println("Test 3: Overworld position (1832, 64, 800) -> Nether portal at (100, 64, 100)");
        {
            Vec3 overworldPos = new Vec3(1832, 64, 800);
            Vec3 netherPortal = new Vec3(100, 64, 100);
            Vec3 translatedPos = translateToNether(overworldPos);
            double distance = horizontalDistance(translatedPos, netherPortal);

            System.out.println("  Overworld pos: " + overworldPos);
            System.out.println("  Translated to Nether: " + translatedPos);
            System.out.println("  Nether portal: " + netherPortal);
            System.out.println("  Horizontal distance: " + distance + " blocks");
            System.out.println("  Search radius (Nether): " + SEARCH_RADIUS_NETHER);
            System.out.println("  Expected: distance > 128, outside boundary");

            if (distance > SEARCH_RADIUS_NETHER) {
                System.out.println("  ✓ PASS: Outside search radius, would NOT link!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Within search radius, should NOT link!");
                failed++;
            }
        }
        System.out.println();

        // Test 4: Nether to Overworld - exact match
        System.out.println("Test 4: Nether position (100, 64, 100) -> Overworld portal at (800, 64, 800)");
        {
            Vec3 netherPos = new Vec3(100, 64, 100);
            Vec3 overworldPortal = new Vec3(800, 64, 800);
            Vec3 translatedPos = translateToOverworld(netherPos);
            double distance = horizontalDistance(translatedPos, overworldPortal);

            System.out.println("  Nether pos: " + netherPos);
            System.out.println("  Translated to Overworld: " + translatedPos);
            System.out.println("  Overworld portal: " + overworldPortal);
            System.out.println("  Horizontal distance: " + distance + " blocks");
            System.out.println("  Search radius (Overworld): " + SEARCH_RADIUS_OVERWORLD);

            if (distance <= SEARCH_RADIUS_OVERWORLD) {
                System.out.println("  ✓ PASS: Within search radius, would link!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Outside search radius, would NOT link!");
                failed++;
            }
        }
        System.out.println();

        // Test 5: Nether to Overworld - just inside radius
        System.out.println("Test 5: Nether position (228, 64, 100) -> Overworld portal at (800, 64, 800)");
        {
            Vec3 netherPos = new Vec3(228, 64, 100);
            Vec3 overworldPortal = new Vec3(800, 64, 800);
            Vec3 translatedPos = translateToOverworld(netherPos);
            double distance = horizontalDistance(translatedPos, overworldPortal);

            System.out.println("  Nether pos: " + netherPos);
            System.out.println("  Translated to Overworld: " + translatedPos);
            System.out.println("  Overworld portal: " + overworldPortal);
            System.out.println("  Horizontal distance: " + distance + " blocks");
            System.out.println("  Search radius (Overworld): " + SEARCH_RADIUS_OVERWORLD);
            System.out.println("  Expected: distance = 1024, just at boundary");

            if (Math.abs(distance - 1024.0) < 0.01) {
                System.out.println("  ✓ PASS: Correct distance calculation!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Distance calculation wrong! Got: " + distance);
                failed++;
            }
        }
        System.out.println();

        // Test 6: Multiple portals - should pick nearest
        System.out.println("Test 6: Overworld (800, 64, 800) with 2 Nether portals");
        {
            Vec3 overworldPos = new Vec3(800, 64, 800);
            Vec3 netherPortal1 = new Vec3(100, 64, 100);  // Distance: 0
            Vec3 netherPortal2 = new Vec3(150, 64, 100);  // Distance: 50

            Vec3 translatedPos = translateToNether(overworldPos);
            double dist1 = distance3d(translatedPos, netherPortal1);
            double dist2 = distance3d(translatedPos, netherPortal2);

            System.out.println("  Overworld pos: " + overworldPos);
            System.out.println("  Translated to Nether: " + translatedPos);
            System.out.println("  Portal 1: " + netherPortal1 + " - Distance: " + dist1);
            System.out.println("  Portal 2: " + netherPortal2 + " - Distance: " + dist2);

            if (dist1 < dist2) {
                System.out.println("  ✓ PASS: Portal 1 is nearest!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Portal 2 should not be nearest!");
                failed++;
            }
        }
        System.out.println();

        // Test 7: Y-coordinate difference matters for final distance (but not search radius)
        System.out.println("Test 7: Y-coordinate affects final distance selection");
        {
            Vec3 overworldPos = new Vec3(800, 64, 800);
            Vec3 netherPortal1 = new Vec3(100, 64, 100);   // Same Y, horizontal dist: 0, 3D dist: 0
            Vec3 netherPortal2 = new Vec3(100, 100, 100);  // Y+36, horizontal dist: 0, 3D dist: 36

            Vec3 translatedPos = translateToNether(overworldPos);
            double horizDist1 = horizontalDistance(translatedPos, netherPortal1);
            double horizDist2 = horizontalDistance(translatedPos, netherPortal2);
            double dist3d1 = distance3d(translatedPos, netherPortal1);
            double dist3d2 = distance3d(translatedPos, netherPortal2);

            System.out.println("  Overworld pos: " + overworldPos);
            System.out.println("  Translated to Nether: " + translatedPos);
            System.out.println("  Portal 1 (Y=64): horiz=" + horizDist1 + ", 3d=" + dist3d1);
            System.out.println("  Portal 2 (Y=100): horiz=" + horizDist2 + ", 3d=" + dist3d2);

            if (horizDist1 == horizDist2 && dist3d1 < dist3d2) {
                System.out.println("  ✓ PASS: Both pass horizontal check, Portal 1 closer in 3D!");
                passed++;
            } else {
                System.out.println("  ✗ FAIL: Distance calculation wrong!");
                failed++;
            }
        }
        System.out.println();

        // Summary
        System.out.println("=== Test Summary ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Total: " + (passed + failed));

        if (failed == 0) {
            System.out.println("\n✓ All tests passed! Algorithm logic is correct.");
        } else {
            System.out.println("\n✗ Some tests failed! Algorithm needs fixing.");
        }
    }

    private static Vec3 translateToNether(Vec3 overworldPos) {
        return new Vec3(overworldPos.x * 0.125, overworldPos.y, overworldPos.z * 0.125);
    }

    private static Vec3 translateToOverworld(Vec3 netherPos) {
        return new Vec3(netherPos.x * 8.0, netherPos.y, netherPos.z * 8.0);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distance3d(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
