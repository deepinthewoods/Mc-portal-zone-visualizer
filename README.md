# Portal Zone Visualizer

A Minecraft Fabric mod for 1.21.10 that visualizes Nether portal zones and linking boundaries in 3D.

## Features

### Portal Visualization
- **Current Dimension Portals**: Rendered as colorful billboard circles at their actual locations
- **Other Dimension Portals**: Rendered as X marks at their translated coordinates (8:1 ratio)
- **Unique Colors**: Each portal has a randomly generated vibrant color based on its UUID

### 3D Voronoi Borders
- Shows the boundaries between portal linking zones
- Visualizes which portal will link from any position in the current dimension
- Helps prevent portal linking issues by showing zone boundaries in 3D space

### Smart Rendering
- Billboard markers always face the camera
- Minimum size of 20 pixels for visibility at any distance
- Maximum render distance: 256 blocks
- Optimized Voronoi calculation with caching

## Controls

- **P Key**: Toggle portal visualization on/off (configurable in Controls settings)

## How It Works

1. **Visit both dimensions** (Overworld and Nether) to discover portals
2. **Portals are automatically scanned** from loaded chunks and remembered
3. **In the Overworld**:
   - Overworld portals appear as circles
   - Nether portals appear as X marks (at ×8 coordinates)
   - Voronoi borders show which Nether portal you'd link to
4. **In the Nether**:
   - Nether portals appear as circles
   - Overworld portals appear as X marks (at ÷8 coordinates)
   - Voronoi borders show which Overworld portal you'd link to

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/)
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download Portal Zone Visualizer
4. Place the mod JAR in your `mods` folder
5. Launch Minecraft

## Building from Source

```bash
git clone https://github.com/deepinthewoods/Mc-portal-zone-visualizer.git
cd Mc-portal-zone-visualizer
./gradlew build
```

The built JAR will be in `build/libs/`

## Use Cases

- **Portal Planning**: See which portals will link together before building
- **Debugging**: Identify portal linking issues visually
- **Optimization**: Plan optimal portal placement for hub systems
- **Education**: Understand how Minecraft's portal linking algorithm works

## Performance

- Minimal performance impact
- Only scans chunks once (cached)
- Voronoi borders recalculate only when portals change
- Optimized sampling (4 block spacing) for smooth performance

## Compatibility

- **Minecraft**: 1.21.10
- **Mod Loader**: Fabric
- **Side**: Client-only
- **Dependencies**: Fabric API

## Credits

- Rendering technique inspired by the Craneshot mod
- Created by deepinthewoods

## License

CC0-1.0 (see LICENSE file)

## Contributing

Issues and pull requests are welcome! See [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) for technical details.
