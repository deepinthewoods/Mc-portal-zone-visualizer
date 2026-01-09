# Portal Zone Visualizer - Parallel Implementation Plan

## Overview
This plan divides work into 3 independent tracks that can be executed in parallel by multiple agents, followed by an integration phase.

---

## 🔵 TRACK A: Data Model & Persistence
**Agent Focus**: Portal data structures, serialization, and validation
**Files**: `PortalInfo.java`, `PortalManager.java`
**No dependencies** - Can start immediately

### Tasks

#### A1: Enhance PortalInfo.java for serialization
- Add `lastValidated` timestamp field (long)
- Add `isValid` boolean flag (default: true)
- Add `toJson()` method - serialize position, dimension, uuid, axis, width, height, lastValidated
- Add `fromJson()` static method - deserialize from JsonObject
- Keep existing UUID generation and color logic

#### A2: Add portal persistence to PortalManager.java
- Add `Map<ResourceKey<Level>, Map<UUID, PortalInfo>> persistedPortals`
- Modify `saveSettingsNow()`:
  - Add "portals" section to JSON
  - For each dimension, serialize all PortalInfo objects
  - Keep existing portalNames and portalHues
- Modify `loadSettings()`:
  - Load "portals" section from JSON
  - Deserialize into persistedPortals map
  - Keep existing name/hue loading

#### A3: Implement auto-validation during chunk scans
- Add `validatePersistedPortalsInChunk(ChunkPos, ResourceKey<Level>)` method
- Call from `scanChunk()` after scanning completes
- For each persisted portal in the chunk:
  - Check if portal blocks still exist at position
  - If yes: mark valid, update lastValidated timestamp
  - If no: mark invalid, remove from persistedPortals
  - Update portalsChanged flag if anything changes

#### A4: Update getPortalsInDimension() to use persisted data
- Return both live-scanned AND persisted portals
- Merge the two sets (persisted + newly scanned)
- Add newly scanned portals to persistedPortals
- Only return valid portals

**Deliverable**: Portal persistence works, survives session restarts, auto-validates

---

## 🟢 TRACK B: Portal Linking Algorithm
**Agent Focus**: Minecraft's portal linking logic
**Files**: Create new `PortalLinkingAlgorithm.java`
**No dependencies** - Can start immediately

### Tasks

#### B1: Create PortalLinkingAlgorithm.java utility class
Location: `src/client/java/com/portalzone/portal/PortalLinkingAlgorithm.java`

#### B2: Implement search radius constants
```java
public static final int SEARCH_RADIUS_NETHER = 128; // blocks
public static final int SEARCH_RADIUS_OVERWORLD = 16; // blocks
```

#### B3: Implement findLinkedPortal() method
```java
/**
 * Find which portal a position would link to in the destination dimension
 * Returns null if no portal exists in search radius (would create new portal)
 */
public static PortalInfo findLinkedPortal(
    Vec3 sourcePos,
    ResourceKey<Level> sourceDim,
    Collection<PortalInfo> destinationPortals
)
```

Logic:
1. Translate coordinates (×8 for Nether→Overworld, ÷8 for Overworld→Nether)
2. Determine search radius based on destination dimension
3. Find all portals within search radius (horizontal distance only, Y doesn't matter as much)
4. Return nearest portal in radius, or null if none

#### B4: Add helper methods
- `getSearchRadius(ResourceKey<Level> dimension)` - returns 128 or 16
- `translateCoordinates(Vec3 pos, ResourceKey<Level> fromDim)` - returns translated Vec3
- `horizontalDistance(Vec3 a, Vec3 b)` - returns distance ignoring Y

**Deliverable**: Standalone class that accurately simulates Minecraft portal linking

---

## 🟡 TRACK C: Voronoi LOD System
**Agent Focus**: Multi-resolution Voronoi border calculation
**Files**: `VoronoiCalculator.java`, `PortalRenderer.java`
**No dependencies initially** - Can implement LOD structure, will integrate linking algorithm later

### Tasks

#### C1: Add LOD constants to VoronoiCalculator.java
```java
private static final int HIGH_DETAIL_RADIUS = 64; // blocks from player
private static final int HIGH_DETAIL_SPACING = 1; // 1 block resolution
private static final int LOW_DETAIL_SPACING = 4; // 4 block resolution
```

#### C2: Implement dynamic max distance calculation
- Add `calculateMaxDistance(Collection<PortalInfo> portals, Vec3 playerPos)` method
- Find furthest portal from player
- Add margin (e.g., 128 blocks beyond furthest portal)
- Minimum 256 blocks, maximum 2048 blocks

#### C3: Refactor recalculateVoronoi() for 2-tier sampling
- Split sampling into two zones:
  - **Inner zone** (0-64 blocks): sample at 1-block spacing
  - **Outer zone** (64-maxDistance): sample at 4-block spacing
- Use same Voronoi edge detection logic for both
- Merge edge lists

#### C4: Add support for "no portal in range" zones
- Modify `findNearestPortalIndex()` to return -1 if no portal in search radius
- In `checkAndAddEdge()`, if one side is -1:
  - Create edge with neutral color (gray/white: `new Vector3f(0.8f, 0.8f, 0.8f)`)
  - Add to cachedEdges

#### C5: Update edge rendering for neutral zones
- Modify `render()` method to handle neutral-colored edges
- Keep existing depth test logic

**Deliverable**: 2-tier LOD Voronoi system with neutral zone support (using simple nearest-portal for now)

---

## 🔗 INTEGRATION PHASE
**All agents coordinate**
**Dependencies**: Requires Tracks A, B, C complete

### Tasks

#### I1: Wire PortalLinkingAlgorithm into VoronoiCalculator
- Replace `findNearestPortalIndex()` in VoronoiCalculator.java
- Call `PortalLinkingAlgorithm.findLinkedPortal()` instead
- Handle null return (no portal in range) → assign special index for neutral zones

#### I2: Ensure persistence works with validation
- Test that persisted portals load correctly
- Test that validation removes destroyed portals
- Test that new portals are discovered and persisted

#### I3: Verify dimension switching
- Test Overworld → Nether visualization
- Test Nether → Overworld visualization
- Confirm translated coordinates are correct
- Confirm search radius differences (16 vs 128)

#### I4: Update PortalManager.tick() to use all systems
- Ensure scans trigger validation
- Ensure validation updates Voronoi (sets portalsChanged flag)

---

## 🧪 TESTING PHASE

### T1: Persistence Testing
- [ ] Create portals, exit world, rejoin → portals still shown
- [ ] Destroy portal → auto-removed from visualization
- [ ] Build new portal → auto-detected and added

### T2: Linking Algorithm Testing
- [ ] Build portal at edge of search radius → links correctly
- [ ] Build portal outside search radius → shows neutral zone
- [ ] Verify different search radius in Nether vs Overworld

### T3: LOD Testing
- [ ] Near player (<64 blocks) → 1-block resolution borders
- [ ] Far from player (>64 blocks) → 4-block resolution borders
- [ ] Move around → borders remain stable (grid-aligned)

### T4: Visual Testing
- [ ] Portal colors consistent across sessions
- [ ] Neutral zones show gray borders
- [ ] Portal names/hues persist
- [ ] Depth testing works for borders and markers

---

## 📦 Parallel Execution Strategy

### Phase 1: Parallel Work (3 agents simultaneously)
- **Agent 1** → Track A (Data Model & Persistence)
- **Agent 2** → Track B (Portal Linking Algorithm)
- **Agent 3** → Track C (Voronoi LOD System)

### Phase 2: Integration (1 agent or coordinated)
- Wire Track B into Track C
- Ensure Track A works with Track B and C

### Phase 3: Testing (1 agent)
- Run all test scenarios
- Fix any issues

---

## 🎯 Success Criteria

1. ✅ Portal positions persist across sessions
2. ✅ Destroyed portals auto-removed during chunk scans
3. ✅ Voronoi borders use Minecraft's exact linking algorithm with search radius
4. ✅ 2-tier LOD: 1-block <64 blocks, 4-block beyond
5. ✅ Neutral zones (no portal in range) shown with gray borders
6. ✅ Dynamic max distance based on portal network
7. ✅ No performance issues with large portal networks

---

## 📝 Notes for Agents

- **Track A** and **Track B** are completely independent - start immediately
- **Track C** can start immediately but will need Track B for final integration
- All tracks should build successfully independently (no compile errors)
- Use feature flags if needed to keep code compilable during development
- Each track should add appropriate logging for debugging
