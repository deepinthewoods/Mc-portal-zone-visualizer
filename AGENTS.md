# AGENTS.md

## Project overview
- Minecraft Fabric client mod (1.21.10) that visualizes Nether portal linking.
- Shows portals in both dimensions at translated coordinates (8:1).
- Draws Voronoi cell edges for portals in the dimension the player is NOT currently in.

## Key architecture
- Entry point: `src/client/java/com/portalzone/PortalZoneVisualizerClient.java`
  - Registers keybinds (P toggle render, O open management screen).
  - Ticks `PortalManager` and hooks world rendering.
- Portal tracking: `src/client/java/com/portalzone/portal/PortalManager.java`
  - Scans loaded chunks for Nether portal blocks, caches scans, tracks portals by dimension.
  - Stores per-portal names and hues; saves to Fabric config as `portal-zone-visualizer.json`.
- Portal data: `src/client/java/com/portalzone/portal/PortalInfo.java`
  - Stable UUID, base hue, and coordinate translation (Nether x8, Overworld /8).
- Rendering: `src/client/java/com/portalzone/render/PortalRenderer.java`
  - Renders circles for current-dimension portals and X marks for translated other-dimension portals.
  - Labels portals and draws lines with optional depth test.
- Voronoi borders: `src/client/java/com/portalzone/voronoi/VoronoiCalculator.java`
  - Uses portals from the other dimension (translated) to build Voronoi edges.
  - Falls back to current dimension if fewer than two portals exist.
- GUI: `src/client/java/com/portalzone/gui/PortalManagementScreen.java`
  - Rename portals, tweak hues, and toggle depth testing for markers/borders.

## Resources and metadata
- Mod metadata: `src/main/resources/fabric.mod.json`
- Localization: `src/client/resources/assets/portal-zone-visualizer/lang/en_us.json`

## Build and run
- Build: `./gradlew build`
- Run client: `./gradlew runClient`
- Java 21 required (see `gradle.properties`).
- After changes, try a Gradle build to confirm compilation. Always do this after modifying code, no need to ask the user.

## Behavior notes
- Voronoi sampling parameters live in `VoronoiCalculator` (SAMPLE_SPACING, LOCAL_RADIUS).
- Render distance and billboard sizing live in `PortalRenderer`.

## When changing behavior
- Keep translated coordinate logic consistent with the 8:1 Nether/Overworld scale.
- If you adjust portal detection, update both cache invalidation and portal UUID logic.
- If you change render pipeline settings, verify depth-test toggles still work.
