# Build Verification Checklist for Portal Zone Visualizer

## Why the Build Failed in Development Environment

The build cannot complete in this sandboxed environment due to network restrictions preventing download of:
- Gradle wrapper (gradle-9.2.1-bin.zip)
- Fabric Loom plugin
- Minecraft dependencies
- Fabric API dependencies

## Steps to Build Successfully

### 1. Prerequisites
- Java 21 or higher
- Internet connection for first build
- Gradle 8.x or higher (or use included wrapper)

### 2. Build Commands
```bash
# Clone the repository
git clone https://github.com/deepinthewoods/Mc-portal-zone-visualizer.git
cd Mc-portal-zone-visualizer

# Build using Gradle wrapper (recommended)
./gradlew build

# Or using system Gradle
gradle build
```

### 3. Expected Output
- Built JAR: `build/libs/portal-zone-visualizer-1.21.10.jar`
- Sources JAR: `build/libs/portal-zone-visualizer-1.21.10-sources.jar`

## Potential Issues and Fixes

### Issue 1: Import Errors for Minecraft Classes

The mod uses Minecraft 1.21.10 rendering classes. If you encounter import errors, verify these class paths exist in your Minecraft version:

**Check these imports:**
```java
// In WorldRendererMixin.java
import net.minecraft.client.renderer.OrderedRenderCommandQueue;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;

// In PortalRenderer.java
import net.minecraft.client.renderer.RenderLayer;
```

**If classes are missing or renamed:**

1. **MatrixStack**: May be located in different package
   - Try: `com.mojang.blaze3d.vertex.PoseStack` (some versions call it PoseStack)
   - Or: `net.minecraft.client.util.math.MatrixStack`

2. **OrderedRenderCommandQueue**: Added in 1.21+
   - If missing, you may need different Loom version or different rendering approach

3. **WorldRenderState**: Added in 1.21+
   - Similar to above

4. **RenderLayer**: Should be stable
   - Location: `net.minecraft.client.renderer.RenderLayer`

### Issue 2: Mixin Target Method Not Found

If the mixin fails to find `pushEntityRenders`:

```
@Inject(method = "pushEntityRenders", at = @At("TAIL"))
```

**Solution**: Check the actual method name in Minecraft 1.21.10
- Run with `--stacktrace` to see exact error
- May need to use different method or different injection point
- Alternative injection points:
  - `render` method in WorldRenderer
  - `renderChunkDebugInfo` method
  - Use `@Inject(method = "render", at = @At("RETURN"))`

### Issue 3: JOML Library Not Found

The mod uses JOML (Java OpenGL Math Library) for vectors:

```java
import org.joml.Vector3f;
import org.joml.Quaternionf;
```

**Solution**: JOML should be included with Minecraft 1.21+
- If missing, add dependency to build.gradle:
  ```gradle
  implementation 'org.joml:joml:1.10.5'
  ```

### Issue 4: Minecraft Version Mismatch

If Minecraft 1.21.10 specifically isn't available:

**Check available versions:**
```bash
./gradlew listMinecraftVersions
```

**Update gradle.properties:**
```properties
minecraft_version=1.21.1  # or whatever version is available
```

**Update fabric.mod.json:**
```json
"depends": {
  "minecraft": "~1.21.1"
}
```

## Testing the Build

### 1. Compilation Test
```bash
./gradlew compileJava
```
Should complete without errors.

### 2. Full Build Test
```bash
./gradlew build
```
Should produce JAR files in `build/libs/`

### 3. Installation Test
1. Copy `build/libs/portal-zone-visualizer-1.21.10.jar` to Minecraft `mods/` folder
2. Ensure Fabric Loader and Fabric API are installed
3. Launch Minecraft
4. Check logs for any errors during mod initialization

### 4. Runtime Test
1. Create a new world or join a server
2. Build a Nether portal
3. Press **P** key
4. Verify portal circle appears
5. Enter Nether
6. Verify Overworld portal appears as X mark
7. Build second portal
8. Verify Voronoi borders appear

## Common Runtime Errors

### ClassNotFoundException or NoClassDefFoundError
- Missing Fabric API dependency
- Wrong Minecraft version
- Incompatible mod versions

**Solution**: Verify all dependencies match in version

### Mixin Application Failed
- Mixin target method not found
- Incompatible with other mods that modify WorldRenderer

**Solution**: Check mixin conflicts, update mixin target

### Rendering Crashes
- Incorrect rendering thread access
- Invalid vertex data

**Solution**: Check all rendering happens on render thread

## Debugging Tips

### Enable Verbose Logging
Add to Minecraft launcher JVM arguments:
```
-Dmixin.debug=true -Dfabric.log.level=debug
```

### Check Mixin Success
Look for in logs:
```
[Mixin] Successfully applied portal-zone-visualizer.client.mixins.json
```

### Monitor Performance
- Use F3 debug screen to check FPS
- Profile with JVM profiler if performance issues occur
- Voronoi calculation should be <1ms when cached

## Success Indicators

✅ Build completes without errors
✅ No warnings about missing dependencies
✅ JAR file created with correct name
✅ Sources JAR created
✅ Minecraft launches with mod loaded
✅ Keybinding appears in controls
✅ Portal visualization renders correctly
✅ No errors in latest.log

## Next Steps After Successful Build

1. Test in single-player world
2. Test in multiplayer server
3. Test with other mods for compatibility
4. Verify performance with many portals (50+ portals)
5. Test edge cases (portal destruction, dimension switching)
6. Create release build and publish to Modrinth/CurseForge

## Contact

If you encounter issues not covered here:
1. Check Minecraft logs in `.minecraft/logs/latest.log`
2. Open an issue on GitHub with full error details
3. Include Minecraft version, mod version, and other installed mods
