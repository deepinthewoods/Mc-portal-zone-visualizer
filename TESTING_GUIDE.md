# Portal Zone Visualizer - Testing Guide

## Implementation Complete

All three tracks have been implemented and integrated:
- ✅ Track A: Data Model & Persistence
- ✅ Track B: Portal Linking Algorithm
- ✅ Track C: Voronoi LOD System
- ✅ Integration Phase

## Build Status
✅ **BUILD SUCCESSFUL** - Ready for testing

## How to Test

### Setup
1. Run the mod: `./gradlew runClient`
2. Create or load a world
3. Press `P` to toggle visualization rendering
4. Press `O` to open portal management screen

---

## T1: Persistence Testing

### Test 1.1: Portal Persistence Across Sessions
**Expected Behavior**: Portals persist after restarting the game

1. Build 2-3 Nether portals in the Overworld
2. Wait for them to be detected (watch console for "Portal added" messages)
3. Note the portal names/colors displayed
4. Exit the world completely
5. Rejoin the world
6. **VERIFY**: Same portals are shown with same names/colors

### Test 1.2: Auto-Removal of Destroyed Portals
**Expected Behavior**: Destroyed portals disappear from visualization

1. Build a portal and wait for detection
2. Note its position and color
3. Break the portal blocks (use water or pickaxe)
4. Walk away and return to force chunk reload OR wait for rescan
5. **VERIFY**: Portal disappears from visualization
6. **VERIFY**: Portal removed from config file (`config/portal-zone-visualizer.json`)

### Test 1.3: Auto-Detection of New Portals
**Expected Behavior**: New portals are automatically detected and persisted

1. Build a new portal
2. Light it and wait ~10 seconds
3. **VERIFY**: Portal appears in visualization
4. Check config file: `config/portal-zone-visualizer.json`
5. **VERIFY**: New portal is saved in the "portals" section

---

## T2: Linking Algorithm Testing

### Test 2.1: Portal at Edge of Search Radius
**Expected Behavior**: Voronoi borders respect search radius (16 blocks Overworld, 128 blocks Nether)

**In Overworld:**
1. Build a portal at coordinates (0, 64, 0)
2. Stand at (120, 64, 0) - this is 15 blocks in Nether coordinates
3. **VERIFY**: Voronoi border shows you're in this portal's zone
4. Move to (136, 64, 0) - this is 17 blocks in Nether coordinates
5. **VERIFY**: Gray neutral zone appears (outside 16-block search radius)

**In Nether:**
1. Build a portal at (0, 64, 0)
2. Stand at (1000, 64, 0) - this is 125 blocks in Overworld coordinates
3. **VERIFY**: Voronoi border shows you're in this portal's zone
4. Move to (1040, 64, 0) - this is 130 blocks in Overworld coordinates
5. **VERIFY**: Gray neutral zone appears (outside 128-block search radius)

### Test 2.2: Neutral Zone Visualization
**Expected Behavior**: Gray borders appear in areas where no portal is in range

1. In Overworld, teleport far from all portals (e.g., `/tp @s 10000 64 10000`)
2. **VERIFY**: Gray/white borders appear instead of colored portal borders
3. Build a portal at your location
4. **VERIFY**: Gray borders are replaced with colored portal zone borders

### Test 2.3: Coordinate Translation
**Expected Behavior**: Portals shown at translated coordinates (8:1 ratio)

1. Build portal in Overworld at (800, 64, 800)
2. **VERIFY**: In Nether, X marker appears at (100, 64, 100)
3. Build portal in Nether at (200, 64, 200)
4. **VERIFY**: In Overworld, X marker appears at (1600, 64, 1600)

---

## T3: LOD (Level of Detail) Testing

### Test 3.1: High Detail Near Player
**Expected Behavior**: 1-block resolution within 64 blocks

1. Build 2 portals close together (30-40 blocks apart) in Overworld
2. Enter the Nether
3. Stand between the two translated portal positions
4. **VERIFY**: Border between zones is smooth and detailed
5. Check console output for edge count
6. **VERIFY**: High "inner=" edge count in console

### Test 3.2: Low Detail Far From Player
**Expected Behavior**: 4-block resolution beyond 64 blocks

1. Build a portal network spread over 500+ blocks
2. Stand in the center
3. Look at borders near you vs. far away
4. **VERIFY**: Nearby borders (<64 blocks) are smooth
5. **VERIFY**: Distant borders (>64 blocks) are chunkier/less detailed
6. Check console: `(inner=X outer=Y)` - outer should be much smaller than if it were high-res

### Test 3.3: Stable Grid-Aligned Borders
**Expected Behavior**: Borders don't shift as player moves

1. Build 3 portals forming a triangle
2. Enter the other dimension
3. Stand at the border between two zones
4. Walk back and forth slowly
5. **VERIFY**: Border stays in the same world position (doesn't follow you)
6. **VERIFY**: No flickering or shifting

---

## T4: Visual Testing

### Test 4.1: Color Consistency
**Expected Behavior**: Portal colors remain consistent

1. Note the colors of 3 portals
2. Exit and rejoin world
3. **VERIFY**: Same portals have same colors

### Test 4.2: Custom Names and Hues
**Expected Behavior**: Custom settings persist

1. Press `O` to open management screen
2. Rename a portal to "Main Base"
3. Adjust hue slider to change color
4. Exit world and rejoin
5. **VERIFY**: Portal still named "Main Base"
6. **VERIFY**: Color matches custom hue

### Test 4.3: Depth Testing Toggle
**Expected Behavior**: Depth testing controls work

1. Press `O` to open management screen
2. Toggle "Portal Markers: Always Visible"
3. **VERIFY**: Markers visible through terrain when enabled
4. **VERIFY**: Markers occluded by terrain when disabled
5. Toggle "Borders: Respect Occlusion"
6. **VERIFY**: Borders respect terrain occlusion when enabled

---

## Performance Testing

### Test P.1: Large Portal Network
**Expected Behavior**: No lag with many portals

1. Build 10+ portals spread across 1000+ blocks
2. Fly around the area
3. Watch FPS and console output
4. **VERIFY**: No significant frame drops
5. **VERIFY**: Console shows reasonable edge counts (not millions)

### Test P.2: Dynamic Max Distance
**Expected Behavior**: Render distance adapts to portal network

1. Build single portal - check console for `maxDist=`
2. **VERIFY**: Should be minimum (256) or small value
3. Build another portal 500 blocks away
4. **VERIFY**: maxDist increases to cover network
5. **VERIFY**: maxDist capped at 2048 even with very distant portals

---

## Known Limitations

1. **Cross-Dimension Scanning**: Currently only scans loaded chunks in current dimension
   - Portals in unloaded chunks of other dimension won't show until those chunks load

2. **Chunk Load Delays**: New portals may take 5-10 seconds to appear
   - This is normal - scanning happens periodically, not instantly

3. **Validation Timing**: Destroyed portals only removed when chunk is rescanned
   - Walk away and return, or wait for automatic rescan interval (200 ticks = 10 seconds)

---

## Debug Console Output

Watch for these log messages:

```
[PortalManager] Portal added: <uuid> at <coords>
[PortalManager] Portal validation: removed N invalid portals
[Voronoi] portals=X edges=Y (inner=A outer=B) maxDist=Z
```

### What the numbers mean:
- `portals=X`: Total portals used for Voronoi calculation
- `edges=Y`: Total border line segments rendered
- `inner=A`: High-detail edges (within 64 blocks)
- `outer=B`: Low-detail edges (beyond 64 blocks)
- `maxDist=Z`: Maximum render distance for Voronoi (256-2048)

---

## Troubleshooting

### Portals Not Appearing
- Check you pressed `P` to enable rendering
- Wait 10 seconds for initial scan
- Check console for "Portal added" messages
- Verify portal is fully formed (valid obsidian frame)

### Portals Not Persisting
- Check `config/portal-zone-visualizer.json` exists
- Look for "portals" section in JSON
- Check file permissions (should be writable)

### Wrong Colors/Borders
- Try pressing `O` and checking settings
- Reset portal colors by deleting UUID from config and rescanning
- Check dimension - Overworld shows Nether portals and vice versa

### Performance Issues
- Check total edge count in console - should be <10,000 typically
- Reduce portal network size if edges >50,000
- Check `maxDist` - if stuck at 2048, may be rendering unnecessary area

---

## Success Criteria Checklist

- [ ] ✅ Portal positions persist across sessions (T1.1)
- [ ] ✅ Destroyed portals auto-removed during chunk scans (T1.2)
- [ ] ✅ Voronoi borders use Minecraft's exact linking algorithm with search radius (T2.1, T2.3)
- [ ] ✅ 2-tier LOD: 1-block <64 blocks, 4-block beyond (T3.1, T3.2)
- [ ] ✅ Neutral zones (no portal in range) shown with gray borders (T2.2)
- [ ] ✅ Dynamic max distance based on portal network (P.2)
- [ ] ✅ No performance issues with large portal networks (P.1)

---

## Implementation Details

### Files Modified/Created

**Track A (Persistence):**
- `src/client/java/com/portalzone/portal/PortalInfo.java`
  - Added: `lastValidated`, `isValid`, `toJson()`, `fromJson()`
- `src/client/java/com/portalzone/portal/PortalManager.java`
  - Added: `persistedPortals` map, persistence in save/load, validation logic

**Track B (Linking Algorithm):**
- `src/client/java/com/portalzone/portal/PortalLinkingAlgorithm.java` (NEW)
  - Full implementation of Minecraft's portal linking logic
  - Search radius: 128 (Nether) / 16 (Overworld)
  - Coordinate translation: 8:1 ratio

**Track C (LOD):**
- `src/client/java/com/portalzone/voronoi/VoronoiCalculator.java`
  - Added: 2-tier LOD system, dynamic max distance, neutral zone support

**Integration:**
- `VoronoiCalculator.findNearestPortalIndex()` now uses `PortalLinkingAlgorithm`
- All systems wired together: persistence ↔ validation ↔ Voronoi recalculation

---

## Next Steps

1. Run through all test scenarios above
2. Report any issues found
3. If all tests pass, implementation is complete!
4. Consider future enhancements:
   - Cross-dimension chunk loading for real-time updates
   - 3D visualization improvements
   - Additional portal statistics/info display
