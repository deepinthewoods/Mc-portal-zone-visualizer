# Implementation Plan: Spatial Chunk System for Voronoi Borders

## Overview

Replace the current full-recalculation approach with a spatial chunk-based caching system similar to Minecraft's chunk rendering. This eliminates flickering and reduces recalculation by 80-95%.

**Core Concept:** Divide the 3D Voronoi space into fixed-size chunks (128×128×128 blocks). Each chunk is calculated once and cached with a portal configuration hash. Only recalculate chunks when:
1. Portal configuration changes (portals added/removed/moved/hidden)
2. Chunk is newly in range
3. Player crosses dimension

**No distance culling** - all chunks within configured range are rendered from cache.

---

## Architecture Changes

### Current Flow (Problem)
```
Player moves → Queue recalc → Clear all buckets → Recalculate entire space → Render
                                      ↑
                                  FLICKER HERE
```

### New Flow (Solution)
```
Player moves → Identify needed chunks → Calculate missing chunks → Merge with cache → Render
                                       (background thread)         (no flicker)
```

---

## Component Breakdown (Parallelizable)

### **Component A: Chunk Key & Hash System**
**Owner:** Agent 1
**Dependencies:** None
**Estimated Effort:** 2-3 hours

Create the infrastructure for identifying and hashing chunks.

**Deliverables:**
1. `VoronoiChunkKey` class
   - Fields: `chunkX`, `chunkY`, `chunkZ`, `lodLevel`, `portalConfigHash`
   - Proper `equals()` and `hashCode()` implementations
   - toString() for debugging

2. Portal configuration hashing
   - Method: `long calculatePortalConfigHash(Vec3[] portals, Vector3f[] colors, boolean[] hidden)`
   - Include portal positions, colors, hidden state, neutral borders setting
   - Use stable hash algorithm (FNV-1a)
   - Must detect when configuration changes

**Acceptance Criteria:**
- Hash changes when any portal moves/added/removed/hidden
- Hash is identical for same portal configuration
- ChunkKey equality works correctly in HashMap
- Unit testable (no Minecraft dependencies)

**Files to Create:**
- `src/client/java/com/portalzone/voronoi/VoronoiChunkKey.java`
- `src/client/java/com/portalzone/voronoi/PortalConfigHasher.java`

---

### **Component B: Chunk Boundary Calculator**
**Owner:** Agent 2
**Dependencies:** None
**Estimated Effort:** 2-3 hours

Determine which chunks are needed for current view.

**Deliverables:**
1. `ChunkBoundaryCalculator` class
   - Method: `Set<ChunkCoord> getRequiredChunks(Vec3 playerPos, int maxDistance, int[] lodRadii)`
   - Chunk size: 128 blocks (use existing TILE_SIZE)
   - Returns all chunk coordinates within range
   - Per-LOD chunk identification

2. Coordinate conversion utilities
   - World position → Chunk coordinate
   - Chunk coordinate → World bounds
   - Handle negative coordinates correctly

**Acceptance Criteria:**
- Correctly identifies chunks in all 8 octants around player
- Handles edge cases (player at chunk boundary)
- Handles negative coordinates
- Returns correct chunks for each LOD level
- No duplicate chunk coordinates

**Files to Create:**
- `src/client/java/com/portalzone/voronoi/ChunkBoundaryCalculator.java`
- `src/client/java/com/portalzone/voronoi/ChunkCoord.java`

---

### **Component C: Chunk Cache Manager**
**Owner:** Agent 3
**Dependencies:** Component A (needs VoronoiChunkKey)
**Estimated Effort:** 3-4 hours

Manage the cache of calculated chunks.

**Deliverables:**
1. `VoronoiChunkCache` class
   - `ConcurrentHashMap<VoronoiChunkKey, ChunkData>`
   - Thread-safe read/write operations
   - LRU eviction when cache exceeds size limit (configurable, default 2000 chunks)
   - Method: `ChunkData get(VoronoiChunkKey key)`
   - Method: `void put(VoronoiChunkKey key, ChunkData data)`
   - Method: `void invalidateAll(long newPortalHash)` - clears cache when portals change
   - Method: `void invalidateLOD(int lodLevel)` - clears specific LOD level
   - Statistics tracking (cache hits, misses, size)

2. `ChunkData` class
   - Contains: `Map<BucketKey, EdgeBucket>` (segments in this chunk)
   - Timestamp for LRU
   - Memory footprint estimation

**Acceptance Criteria:**
- Thread-safe for concurrent access
- LRU eviction works correctly
- Cache statistics accurate
- Invalidation clears appropriate entries
- Memory-efficient

**Files to Create:**
- `src/client/java/com/portalzone/voronoi/VoronoiChunkCache.java`
- `src/client/java/com/portalzone/voronoi/ChunkData.java`

---

### **Component D: Chunk Calculator (Refactored Core)**
**Owner:** Agent 4
**Dependencies:** Component A, Component B
**Estimated Effort:** 4-5 hours

Refactor existing voronoi calculation to work per-chunk.

**Deliverables:**
1. Modify `calculateVoronoiZonesRectangular()` to accept chunk bounds
   - Current signature: `(RecalcRequest request, double[] portalX, ..., int minRadius, int maxRadius, int spacing, Map<BucketKey, EdgeBucket> bucketMap)`
   - New: Add parameters `int chunkMinX, int chunkMaxX, int chunkMinY, int chunkMaxY, int chunkMinZ, int chunkMaxZ`
   - Calculate only within chunk boundaries instead of full sphere
   - Return segments only for this chunk

2. Extract method: `calculateSingleChunk(RecalcRequest, ChunkCoord, int lodLevel) → Map<BucketKey, EdgeBucket>`
   - Wrapper around modified calculateVoronoiZonesRectangular
   - Handles coordinate conversion
   - Sets up chunk-specific bounds

**Acceptance Criteria:**
- Produces identical results to current system (when all chunks combined)
- No segments bleed outside chunk boundaries
- Handles chunk boundaries at all LOD levels
- Cancellable via requestId check
- No edge gaps between adjacent chunks

**Files to Modify:**
- `src/client/java/com/portalzone/voronoi/VoronoiCalculator.java` (lines 492-573)

---

### **Component E: Chunk Request Orchestrator**
**Owner:** Agent 5
**Dependencies:** Component B, Component C, Component D
**Estimated Effort:** 5-6 hours

Coordinate which chunks to calculate and merge results.

**Deliverables:**
1. New recalc workflow in `VoronoiCalculator`
   - Replace `recalculateVoronoi()` with `recalculateVoronoiChunked()`
   - Identify required chunks (Component B)
   - Check cache for each chunk (Component C)
   - Calculate missing chunks (Component D)
   - Merge cached + new chunks into final bucket array
   - Handle cancellation gracefully

2. Merge algorithm
   - Combine `Map<BucketKey, EdgeBucket>` from multiple chunks
   - Ensure no duplicate segments
   - Maintain group organization (0-3)
   - Efficient merging (avoid copying)

3. Background thread coordination
   - Update `recalcLoop()` to use chunked approach
   - Ensure old data stays visible during calculation
   - Atomic swap of final results

**Acceptance Criteria:**
- No visual flickering during recalculation
- Cache hits avoid recalculation
- Correct merging of chunks
- Handles interruption gracefully
- Old data visible until new data ready
- Logs cache hit rate

**Files to Modify:**
- `src/client/java/com/portalzone/voronoi/VoronoiCalculator.java` (lines 268-333, 942-983)

---

### **Component F: Cache Invalidation Logic**
**Owner:** Agent 6
**Dependencies:** Component A, Component C
**Estimated Effort:** 2-3 hours

Determine when to invalidate cache entries.

**Deliverables:**
1. Portal change detection enhancement
   - Track portal configuration hash in `cachedPortalConfigHash`
   - Detect when hash changes
   - Trigger full cache invalidation on portal config change

2. Dimension change handling
   - Clear cache when dimension changes
   - Keep cache for previous dimension (for when player returns)
   - Limit total cache size across dimensions

3. Smart invalidation on setting changes
   - Neutral borders toggle: invalidate all
   - Vertical borders toggle: invalidate all
   - LOD0 distance change: invalidate affected LOD levels
   - Border draw distance: no invalidation needed (just rendering)

**Acceptance Criteria:**
- Detects all portal configuration changes
- Invalidates appropriately (not too aggressive)
- Handles dimension switches correctly
- Settings changes handled correctly
- No stale data rendered

**Files to Modify:**
- `src/client/java/com/portalzone/voronoi/VoronoiCalculator.java` (lines 88-116)
- `src/client/java/com/portalzone/portal/PortalManager.java` (lines 54, 547-558)

---

## Integration Phase (Sequential)

### **Phase 1: Infrastructure Setup** (Components A, B, C in parallel)
- Agent 1: Chunk keys and hashing
- Agent 2: Boundary calculator
- Agent 3: Cache manager
- **Duration:** 2-4 hours
- **Deliverable:** Core infrastructure classes

### **Phase 2: Calculation Refactor** (Component D, depends on Phase 1)
- Agent 4: Refactor calculation to work per-chunk
- **Duration:** 4-5 hours
- **Deliverable:** Per-chunk calculation working

### **Phase 3: Orchestration** (Component E, depends on Phase 2)
- Agent 5: Integrate chunks with main recalc loop
- **Duration:** 5-6 hours
- **Deliverable:** End-to-end chunked calculation working

### **Phase 4: Cache Invalidation** (Component F, can start with Phase 2)
- Agent 6: Smart invalidation logic
- **Duration:** 2-3 hours
- **Deliverable:** Cache invalidation correct

### **Phase 5: Testing & Tuning** (All agents)
- Test with various portal configurations
- Verify no flickering
- Check cache hit rates
- Performance profiling
- Edge case testing (chunk boundaries, dimension changes)
- **Duration:** 2-3 hours

---

## Testing Strategy

### Unit Tests (Per Component)
- Component A: Hash stability, key equality
- Component B: Chunk coordinate conversion, boundary detection
- Component C: Cache operations, LRU eviction
- Component D: Chunk calculation produces same results as full calculation
- Component E: Merge algorithm correctness
- Component F: Invalidation triggers

### Integration Tests
1. **No Flickering Test:** Move player continuously, verify no empty frames
2. **Cache Efficiency Test:** Move player back and forth, verify cache reuse
3. **Portal Change Test:** Add/remove portal, verify cache invalidation
4. **Dimension Switch Test:** Switch dimensions, verify correct rendering
5. **Boundary Test:** Stand at chunk boundary, verify no gaps/seams
6. **Multi-LOD Test:** Verify all LOD levels cache correctly

### Performance Benchmarks
- Measure time to calculate 100 chunks from scratch
- Measure time to render from cache
- Memory usage with 1000 cached chunks
- Cache hit rate during normal gameplay

---

## Configuration

Add to `PortalManager`:
```java
// Chunk system config
private int chunkCacheMaxSize = 2000; // Max chunks to cache
private int chunkSize = 128; // Blocks per chunk side
```

Add debug visualization (optional):
- Render chunk boundaries (wireframe cubes)
- Color-code chunks (cached vs newly calculated)
- Display cache statistics on F3 screen

---

## Rollback Plan

If issues arise during implementation:
1. Keep existing `recalculateVoronoi()` method
2. Add flag: `boolean useChunkSystem = true`
3. Can toggle back to old system if needed
4. Remove old system after chunk system proven stable

---

## Success Metrics

- **Zero flickering:** No empty frames during recalculation
- **Cache hit rate:** >80% during normal player movement
- **Recalc time:** <50ms for typical movement (vs current ~200ms)
- **Memory:** <100MB for full cache (2000 chunks)
- **Correctness:** Identical visual output to current system

---

## Open Questions

1. **Chunk size:** 128 blocks optimal? (matches TILE_SIZE)
2. **Cache size:** 2000 chunks adequate for normal gameplay?
3. **Hash algorithm:** FNV-1a sufficient or use better hash?
4. **Dimension cache:** Keep cache per-dimension or single global cache?
5. **Preloading:** Pre-calculate chunks ahead of player movement direction?

---

## Implementation Sequence for Parallel Work

### Day 1 Morning (Parallel)
- **Agent 1:** Component A (Chunk keys, hashing)
- **Agent 2:** Component B (Boundary calculator)
- **Agent 3:** Component C (Cache manager)

### Day 1 Afternoon (Sequential)
- **Agent 4:** Component D (Chunk calculator)
  - Wait for Components A & B to complete

### Day 2 Morning (Parallel)
- **Agent 5:** Component E (Orchestrator)
  - Wait for Components B, C, D to complete
- **Agent 6:** Component F (Invalidation logic)
  - Wait for Components A & C to complete

### Day 2 Afternoon (All together)
- Integration testing
- Bug fixes
- Performance tuning
- Code review

---

## Notes

- All new classes should be in `com.portalzone.voronoi` package
- Use existing LOD constants: `LOD_SPACING = {1, 4, 16}`
- Maintain existing `EdgeBucket` and `EdgeSegment` structures
- Keep existing rendering code unchanged
- Preserve existing thread safety (worker thread + atomic swaps)
- Add logging with `[VoronoiChunk]` prefix for debugging
