# Portal Zone Visualizer - Implementation Notes

## Overview
This mod visualizes Nether portal zones and linking boundaries in 3D. It helps players understand which portals will link together by showing:
- Portal locations in the current dimension (rendered as billboard circles)
- Translated portal positions from the other dimension (rendered as X marks)
- 3D Voronoi cell boundaries showing portal linking zones

## Architecture

### Core Components

#### 1. Portal Detection (`PortalManager`)
- Scans loaded chunks for Nether portal blocks
- Identifies complete portal structures
- Tracks portal position, orientation, size, and dimension
- Caches scanned chunks to avoid redundant scanning
- Invalidates chunks when blocks change

#### 2. Portal Visualization (`PortalRenderer`)
- Renders portals in current dimension as billboard circles
- Renders translated portal positions as X marks
- Uses camera-facing billboards with minimum pixel size (20px)
- Each portal has a unique color based on its UUID

#### 3. Voronoi Calculation (`VoronoiCalculator`)
- Calculates 3D Voronoi cell boundaries around player (64 block radius)
- Samples every 4 blocks for performance
- Caches results and only recalculates when portals change
- Renders borders between portal zones

#### 4. Rendering System
- Hooks into `WorldRenderer.pushEntityRenders()` via Mixin
- Uses `RenderLayer.getLines()` for line rendering
- Camera-relative coordinates for all rendering
- Full brightness lighting for visibility

### Key Features

#### Portal Color Generation
Each portal gets a unique, vibrant color based on its UUID:
- Colors generated using HSV color space (saturation=0.8, value=1.0)
- Hue derived from portal position and dimension
- Ensures consistent colors for the same portal

#### Billboard Rendering
Portals are rendered as camera-facing billboards:
- Base size: 3 blocks in-game
- Minimum size: 20 pixels on screen (scales with distance)
- Circles approximated with 24 segments
- X marks drawn as two diagonal lines

#### Coordinate Translation
Portal positions are translated between dimensions using Minecraft's 8:1 ratio:
- Nether → Overworld: multiply by 8
- Overworld → Nether: divide by 8

#### Performance Optimizations
- Maximum render distance: 256 blocks
- Voronoi sampling spacing: 4 blocks
- Voronoi calculation radius: 64 blocks around player
- Chunk scan caching
- Only recalculate when portals change

### Controls
- **P key**: Toggle visualization on/off (configurable keybinding)

## Limitations and Future Improvements

### Cross-Dimension Portal Tracking
**Current Implementation**: The mod stores all discovered portals in memory, persisting them across dimension changes. Portals discovered in the Nether remain tracked when the player returns to the Overworld, and vice versa.

**How It Works**:
1. Player visits Nether → Nether portals are scanned and stored
2. Player returns to Overworld → Nether portals are still in memory
3. Nether portals render as X marks at translated coordinates in Overworld
4. Voronoi borders show which Nether portal would link from any Overworld position

**Limitation**: Players must visit both dimensions at least once to discover portals. The mod cannot scan chunks in dimensions that aren't currently loaded (limitation of client-only mods).

**Result**: This provides a practical solution - players explore both dimensions naturally during gameplay, and the mod visualizes all discovered portal relationships.

### API Compatibility
This mod is designed for Minecraft 1.21.10 with Fabric. Some rendering classes may need adjustment based on the exact Minecraft version:
- `MatrixStack` location may vary
- `OrderedRenderCommandQueue` and `WorldRenderState` are 1.21+ classes
- Verify imports match your exact Minecraft version

## File Structure

```
src/client/java/com/portalzone/
├── PortalZoneVisualizerClient.java   # Main entry point, keybinding
├── portal/
│   ├── PortalInfo.java                # Portal data structure
│   └── PortalManager.java             # Portal detection and tracking
├── render/
│   └── PortalRenderer.java            # Main rendering logic
├── voronoi/
│   └── VoronoiCalculator.java         # Voronoi border calculation
└── mixin/
    └── client/
        └── WorldRendererMixin.java    # Rendering hook

src/client/resources/
├── portal-zone-visualizer.client.mixins.json  # Mixin configuration
└── assets/portal-zone-visualizer/
    └── lang/
        └── en_us.json                 # Localization
```

## Building and Testing

### Build
```bash
./gradlew build
```

### Testing Checklist
- [ ] Portals are detected and colored correctly
- [ ] Portal circles render in current dimension
- [ ] X marks render for portals in other dimension
- [ ] Coordinate translation is accurate (8:1 ratio)
- [ ] Voronoi borders appear between portal zones
- [ ] Borders update when portals are created/destroyed
- [ ] Keybinding toggles visualization
- [ ] Performance is acceptable with many portals
- [ ] Billboard sizes scale correctly with distance

## Known Issues to Verify
1. **Import paths**: Verify all Minecraft class imports are correct for 1.21.10
2. **Cross-dimension**: Currently cannot scan chunks in unloaded dimensions
3. **Performance**: Large numbers of portals (>100) may impact frame rate

## Future Enhancements
- [ ] Portal persistence across dimension changes
- [ ] Configuration file for colors, render distance, etc.
- [ ] Different visualization modes (e.g., only borders, only markers)
- [ ] Portal labels/distance indicators
- [ ] Color customization
- [ ] Support for custom portal mods
