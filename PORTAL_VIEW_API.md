# Portal View API for Voxy — Stage 0 design

Status: design proposal (no source changes made).

Repository: `D:/SYSADMIN/SHARED/minecraft/minecraft_servers/mod-forks/voxy-neoforge`
Branch: `multiversion`
HEAD: `255ef01ae7cd9ea87d5994ca2b60facd16057e4d` (`fix(diag): make vanillaTerrain bypass Voxy without disabling Sodium`)

Goal: a backend-agnostic "portal view" API that lets Immersive Portals (IP) render Voxy LOD
inside portal views on **both** backends:

- GL46 (Windows/Linux; `Gl46RenderBackend`), and
- GL41Metal (macOS; `Gl41MetalRenderBackend`).

Non-goals for this stage: full Iris shader-pack support inside portal views, guaranteed LOD for
foreign dimensions, portal entity rendering, non-rectangular/odd-mirror portal correctness, and any
change to Voxy world data/ingest.

Method: every claim about this repository is anchored to `file:line` at the HEAD above. Claims
about Immersive Portals come from the adjacent checkout
`D:/SYSADMIN/SHARED/minecraft/minecraft_servers/mod-forks/immersive-portals-neoforge` (branch
`1.21`, tag `v6.0.7`) and are marked `[IP]`. Prior-art behavior is read from the decompiled classes
of the installed compatibility jar and is marked `[prior art]` (bytecode has no source line
numbers).

---

## 0. Decisions (TL;DR)

| # | Decision |
|---|---|
| D1 | Portal passes are explicit, render-thread, LIFO-scoped operations on a `VoxyRenderSystem`: `beginPortalView(PortalViewOptions)` / `endPortalView()`. Every pass opens a scope even when the backend cannot render LOD, so the scope doubles as a **suppression guard** that prevents today's main-frame corruption. |
| D2 | The API is a `VoxyRenderBackend` extension (`supportsPortalViews`, `portalViewDepthLimit`, `beginPortalView`, `endPortalView`), defaulting to "unsupported". GL46 and GL41Metal implement it with **dedicated** per-depth state (viewport / Metal slot), never with the main view's state. |
| D3 | The frame state that is global today (`RenderFrameStageState.currentFrame`, `IrisUtil.CAPTURED_VIEWPORT_PARAMETERS`) moves into `VoxyRenderSystem`. This is the root fix for nested IP renders; it is required even when portal LOD is disabled. |
| D4 | Clip plane: an optional world-space plane supplied by the IP adapter. GL46 and GL41Metal use it for **traversal culling** (GL46: overwrite unused `frustumPlanes[5]` + one extra plane test in `lod/frustum.glsl`; Metal: new `SceneUniformHost.clipPlane` + a test in `classifyNode`). Exact fragment clipping is done by an **oblique near plane** applied after `computeProjectionMat`; if validation fails, the proven culling-only behavior is the fallback (see §11). |
| D5 | Portal views render only into a target that is safe for them: IP's **compatibility (framebuffer) portal renderer** (dedicated full-size target, portal shape applied later by IP) is the default. Stencil-gated rendering into IP's shared stencil target is supported by the API (`stencilRef`/`stencilMask`) but is a secondary mode with extra GL-state work. |
| D6 | Iris shader packs are an explicit **degraded mode** in phase 1 on both backends: the portal pass opens (suppression on), Voxy LOD is not rendered, main frame is untouched. Both Iris paths own main-view targets (`IrisVoxyRenderPipeline` binds pack targets at construction; GL41Metal's `GbufferCompositor` builds its own FBO), so per-portal Iris is a separate project. |
| D7 | Cross-dimension portal LOD is allowed by the API (the destination dimension's `VoxyRenderSystem` is used, as IP swaps `client.levelRenderer`) but disabled by default via config, because each foreign `VoxyRenderSystem` is a full engine plus (on macOS) a native Metal context. Same-dimension portals are the phase-1 target. |
| D8 | Bounded state: portal state is a LIFO stack of at most `voxy.portal.maxDepth` (default 2). No unbounded map keyed by portal identity; no GPU/resource allocation per pass (only small transient scope objects). |

Top risk: IP renders portals by a **nested `gameRenderer.renderLevel`** with a swapped renderer
(`[IP] MyGameRenderer.java:114-235`), so the same code paths run twice per host frame. The design
must make all frame-owned state renderer-local; that is D3.

---

## 1. As-built: how Voxy renders a frame

### 1.1 Backend contract

`VoxyRenderBackend` (`.../backend/VoxyRenderBackend.java:8-84`) exposes:

- `id()`, `rendersLodTerrain()` (:9, :19)
- `runFrameStage(RenderStage, RenderStageContext, RenderFrame)` (:25)
- `setupFrame(RenderFrameContext)` (:27)
- `renderOpaque(RenderFrame)` (:29)
- `getLastFrameMatrices()` (:35)
- Iris depth accessors (:44-54), Sable accessors (:62-72)
- section lifecycle (:74-76) and no-op Sodium sync hooks (:78-80)

There is no portal concept in the interface.

`RenderFrameContext` (`.../backend/RenderFrameContext.java:5-14`) carries `ChunkRenderMatrices`
matrices, camera position, `sourceFramebuffer`, and a viewport rectangle. `VoxyRenderSystem`
captures the rectangle and the current draw framebuffer from GL at each stage
(`.../core/VoxyRenderSystem.java:148-162`), so a nested render with a different target and viewport
is already representable.

`RenderStage` (`.../backend/RenderStage.java:3-20`) has `FRAME_BEGIN`, `VIEWPORT_SETUP`,
`SODIUM_SOLID_SYNC`, `SODIUM_CUTOUT_SYNC`, `PRE_TRANSLUCENT`, `TRANSLUCENT`, `OPAQUE`, `FRAME_END`.

### 1.2 Stage dispatch and main-frame state

Stage dispatch is mixin-driven. `ClientVoxyMixinPlugin` registers **either** the Valkyrien Skies
variant **or** `sodium.MixinDefaultChunkRenderer`
(`.../mixin/ClientVoxyMixinPlugin.java:30-46`). In a normal install (no VS) the path is:

- Sodium solid/cutout chunk passes call `runFrameStage` (`.../mixin/sodium/MixinDefaultChunkRenderer.java:91-141`).
- Non-Iris mode clears the frame after OPAQUE (:136-140); Iris mode keeps it for the later
  `TRANSLUCENT` hook.
- Iris-only stages (FRAME_BEGIN, VIEWPORT_SETUP, PRE_TRANSLUCENT, TRANSLUCENT) are injected in
  `.../mixin/iris/MixinIrisRenderingPipeline.java:100-181`.

The main-frame state that currently lives outside the backend:

| State | Location | Notes |
|---|---|---|
| Current frame | `RenderFrameStageState.currentFrame` static (`.../backend/RenderFrameStageState.java:3-26`) | one global for all renderers/backends |
| Captured main matrices | `IrisUtil.CAPTURED_VIEWPORT_PARAMETERS` static (`.../core/util/IrisUtil.java:51`) | set in `.../mixin/iris/MixinLevelRenderer.java:26-52` |
| GL46 viewport | `ViewportSelector` map + default (`.../rendering/ViewportSelector.java:15-59`), backend field (`.../gl46/Gl46RenderBackend.java:82`, created at :159) | `getViewport()` (`Gl46RenderBackend.java:433-438`) returns the default or Vivecraft/Iris-shadow viewport |
| Last matrices (Iris/Sable consumers) | `Gl46RenderBackend.lastFrameMatrices` (:86, written :282-283); `Gl41MetalRenderBackend.lastFrameMatrices` (:37, written :137-139) | main-view contract |
| GL41Metal held translucent slot | `heldTranslucentSlot/heldTranslucentFrame/heldTranslucentContext` (`Gl41MetalRenderBackend.java:44-46`) | released at the start of every main submit (:122-124) |
| GL41Metal current bound | `currentBound` (:36) | written by opaque (:171-174), read by translucent (:255, :279) |
| Model/render-distance servicing | `Gl46RenderBackend.java:350-357`; `Gl41MetalRenderBackend.java:125` (`TerrainResources.tick`) | should run once per host frame |
| Fog captured for the main view | `VoxyRenderSystem.setCapturedFog` (`.../core/VoxyRenderSystem.java:72-88`) | updated by `MixinFogRenderer` (`.../mixin/minecraft/MixinFogRenderer.java:30-59`) |

### 1.3 What Immersive Portals actually does

`[IP]` facts that drive the design:

1. IP renders portal content by swapping the world renderer, `client.level`, Sodium context and Iris
   pipeline, then calling **`client.gameRenderer.renderLevel(...)` recursively**
   (`.../render/MyGameRenderer.java:114-235`; swap at :171, Iris pipeline nulled at :221, nested
   render at :229-235, restore at :241-273).
2. Portal rendering runs at the translucent boundary, after the main opaque pass and before main
   translucency (`.../mixin/client/render/MixinLevelRenderer.java:151-161`; renderer call at
   `.../render/renderer/RendererUsingStencil.java:40-42`).
3. The default stencil renderer uses the **main framebuffer's stencil** with the portal layer as
   the reference value (`RendererUsingStencil.java:84-100` enables stencil and clears to 0;
   :277-289 sets `glStencilFunc(GL_EQUAL, layer, 0xFF)` for the nested world render).
4. The compatibility framebuffer renderer renders each portal into a **dedicated full-size
   `SecondaryFrameBuffer`** (`.../render/renderer/RendererUsingFrameBuffer.java:48, :68-76`;
   `.../render/SecondaryFrameBuffer.java:7-30`) and composites it through the portal area; it does
   **not** support nesting (`RendererUsingFrameBuffer.java:68-71`).
5. Same-dimension portals use the main `LevelRenderer` (the dimension→renderer map stores the main
   renderer, `[IP] ClientWorldLoader.java:368`); other dimensions get a synthetic `LevelRenderer`
   and client `ClientLevel` (`ClientWorldLoader.java:267-289, :394-461`).
6. The active portal clipping plane is a world-space `Plane` in the current render coordinate
   (`.../render/context_management/PortalRendering.java:176-212`); `FrontClipping` converts it to a
   camera-relative equation and uses legacy `GL_CLIP_PLANE0` / a shader uniform
   (`.../render/FrontClipping.java:21-119, :166-172`).

Because of (1), the Voxy mixins fire **inside the nested render** with portal matrices and camera,
while `Minecraft.getInstance().levelRenderer` already points at the destination renderer (so the
`IVoxyRenderSystemHolder` lookup in the mixins resolves the destination `VoxyRenderSystem` —
`.../core/IVoxyRenderSystemHolder.java:23-28`).

### 1.4 Failure modes today (must be fixed regardless of portal LOD)

**F1 — global frame state crosses renderers.** `RenderFrameStageState` is one static
(`RenderFrameStageState.java:3-26`). A nested portal render sees the main frame (Iris mode) or null
(non-Iris). When non-null, it is a frame owned by another `VoxyRenderSystem`/viewport.

**F2 — GL46 reconfigures the main viewport.** In non-Iris mode the static frame is null during the
nested OPAQUE (`MixinDefaultChunkRenderer.java:136-140`), so `Gl46RenderBackend.runFrameStage`
calls `setupFrame` (`Gl46RenderBackend.java:228-232`). `setupFrame` uses `getViewport()`
(:241, :433-438) → the default main viewport, and overwrites its matrices/camera/size (:270-276),
its `frameId` (:278-280) and `lastFrameMatrices` (:282-283). It also ticks models and may move the
LOD center to the portal camera (`renderOpaque` :350-357). The main viewport object is shared, so
the main view's viewport, Sable accessor (`getSableOcclusionViewport` :446-448) and Iris uniforms
are wrong after the portal pass. The pipeline's framebuffers get resized if the portal target
differs in size (:262-268 and `NormalRenderPipeline.setup` `.../gl46/pipeline/NormalRenderPipeline.java:65-101`).

**F3 — GL46 draws the wrong frame with Iris.** With a pack, the main frame is kept, so the nested
OPAQUE reuses the main `Gl46Frame` and draws the main viewport (main matrices) into the portal
target (`Gl46RenderBackend.java:222-234`); the result is main-view LOD pasted into the portal.

**F4 — global captured matrices are poisoned.** The Iris compat mixin overwrites
`IrisUtil.CAPTURED_VIEWPORT_PARAMETERS` at every `renderLevel` HEAD, including the nested one
(`.../mixin/iris/MixinLevelRenderer.java:26-52`). The main frame's later TRANSLUCENT stage reads the
global (`MixinIrisRenderingPipeline.java:159-181`) → the main distant water uses portal
matrices/camera.

**F5 — GL41Metal releases the main translucent slot.** In non-Iris mode the nested OPAQUE gets a
null frame and calls `submitMetalFrame(portal context)` (`Gl41MetalRenderBackend.java:110-118`);
`submitMetalFrame` first calls `releaseHeldTranslucentSlot()` (:122-124), retiring the main frame's
held slot before the main TRANSLUCENT stage runs. Main distant water is then never drawn. In Iris
mode `FRAME_BEGIN` does the same if the Iris pipeline hook fires in the nested render (:86-87).

**F6 — GL41Metal `currentBound` is shared.** Every opaque draw overwrites `currentBound`
(:171-174, :209-212) and every translucent draw reads it (:255, :279). A portal draw between main
opaque and main translucent makes the main translucent use the portal's bound texture.

**F7 — host GL state is not preserved.** GL46 `renderOpaque` ends with `glDisable(GL_STENCIL_TEST)`
(`Gl46RenderBackend.java:368-389`, disable at :371) and `AbstractRenderPipeline.transformBlitDepth`
disables stencil around the final composite (`.../gl46/pipeline/AbstractRenderPipeline.java:180-205`,
:190 and :203). GL41Metal `drawRanges`/`drawTranslucentRanges` call `disableHostClipState()` which
disables scissor **and** stencil (`.../gl41metal/DrawlistOpaqueRenderer.java:936, :1407-1410, :1597`).
IP's stencil mask (`RendererUsingStencil.java:277-289`) and any host scissor are therefore lost or
bypassed; LOD color can be written outside the portal silhouette in the stencil renderer. The
`StateSnapshot` does not save/restore stencil func/ref/mask/op (`DrawlistOpaqueRenderer.java:2326-2571`).
It also does not touch `GL_CLIP_DISTANCE` (Voxy never enables it; it must stay that way, see D4).

**F8 — the main `ViewportSelector` map is unbounded and not portal-aware.**
`ViewportSelector.extraViewports` (`ViewportSelector.java:17-18`) is a plain `HashMap` with a
private `getOrCreate` (:25-27) and no eviction; `getViewport()` (:39-53) has no notion of a portal
pass, and each `MDICViewport` allocates large buffers (`.../gl46/section/mdic/MDICViewport.java:9-30`:
~12 MB draw-call buffer, 3.2 MB scratch, ~4 MB visibility, etc.).

**F9 — GL41Metal slot accounting ignores nested passes.** Slots are a fixed native pool
(`Config.java:3-9`, default 3; native requires ≥2 `[native] gl41metal_context.mm:91-94`). A held main
slot plus portal slots can exhaust the pool; `acquireWriteSlot` returns -1 and the frame is silently
skipped (`Gl41MetalRenderBackend.java:140-147`, `SlotScheduler.java:28-32`).

**F10 — model/upload/render-distance servicing is per-call, not per-frame.** GL46 services in every
`renderOpaque` (main and portal candidate) and may move the LOD center to the portal camera
(:350-357); GL41Metal calls `terrainResources.tick` in every submit (:125). Nested passes multiply
the work and can thrash the LOD ring (`RenderDistanceTracker.setCenterAndProcess`:
`.../rendering/RenderDistanceTracker.java:49-60`).

The prior-art patch (§2) confirms these failure modes: it forces IP into its framebuffer renderer
because "the default stencil renderer corrupts Voxy's LOD terrain", freezes the LOD recenter and
chunk-bound mutation during portal passes, services foreign systems once per frame, and disables
scissor around Voxy draws.

---

## 2. Prior art in the installed ecosystem (important)

`D:/SYSADMIN/SHARED/minecraft/minecraft_servers/mod-forks/micimmptl-voxy/out/make-it-compatible-immersive-portals-neoforge-1.21.1.jar`
contains a dedicated Voxy×IP patch (`dev/micimmptl/voxy/**`, decompiled). It is currently disabled:
`micimmptl-voxy/disable_voxy_config.py` strips its `[[mixins]]` block, and the patched jar's
`META-INF/neoforge.mods.toml` no longer references `micimmptl.voxy.mixins.json` (the `.orig` jar
does).

`[prior art]` behaviors that are hard-won requirements:

| Class / method | Behavior |
|---|---|
| `PortalVoxyState.isPortalPass` | Detects IP portal passes via `PortalRendering.isRendering()` / `WorldRenderInfo.isRendering()`. |
| `PortalVoxyState.shouldSkipLodDraw` | Skips LOD in odd-mirror passes and whenever `IrisUtil.irisShaderPackEnabled()` is true. |
| `PortalVoxyState.viewportFor` | A portal viewport keyed by `portal UUID + layer` obtained from `ViewportSelector.getOrCreate`; the default viewport is claimed once per frame for the viewer world; keys evicted after 10 s. |
| `PortalVoxyState.applyPortalClipPlane` | Converts IP's active clip plane to camera-relative `d = -(n·(plane.pos - camera))` and overwrites `viewport.frustumPlanes[4]` (the near plane) so the traversal culls behind it. No fragment clipping. |
| `PortalVoxyState.sweep` | Deletes viewports idle > 10 s and removes them from `ViewportSelector.extraViewports`. |
| `VoxyRenderSystemPortalMixin` | Redirects `getViewport()` to the portal viewport; applies the clip plane after setup; skips drawing with Iris/mirrors; disables `GL_SCISSOR_TEST` around the Voxy draw and restores it; for a foreign `VoxyRenderSystem`, services (uploads/models) at most once per IP frame; blocks LOD recenter during portal passes. |
| `ChunkBoundRendererOncePerFrameMixin` | Freezes `ChunkBoundRenderer` queue/ring mutation after the first call per frame. |
| `RenderSectionManagerVoxyFeedGateMixin` | Gates `ChunkBoundRenderer.addSection/removeSection` during portal managers. |
| `RendererUsingFrameBufferNestingMixin` + `NestedPortalFramebuffers` | Adds nested secondary framebuffers for IP's framebuffer renderer (which natively supports one layer). |
| `VoxyImmersivePortalsPatch` | Forces `IPGlobal.renderMode = compatibility` (framebuffer renderer) at load and re-asserts every client tick. |

Takeaways for this design: use a dedicated target (framebuffer renderer), per-view state, no
recenter/bound mutation in portal passes, once-per-frame servicing, preserved host scissor/stencil,
and no LOD with Iris. The new API replaces the brittle internals (accessor into private
`ViewportSelector`, writing `frustumPlanes[4]`, static per-portal maps) with first-class types.

---

## 3. Proposed API

New package-level types, all under
`src/main/java/me/cortex/voxy/client/core/rendering/backend/`:

### 3.1 Types

```java
/** World-space plane; points with dot(n, p) + d > 0 are kept. */
public record PortalClipPlane(double nx, double ny, double nz,
                              double px, double py, double pz) {
  public boolean isValid() { /* finite, |n|^2 > 1e-9 */ }
}

/**
 * Parameters known before the first stage call of a portal pass.
 * All values are copied; the object is not retained.
 * hostFrameId lets a backend service a foreign VoxyRenderSystem at most once per host frame
 * (adapter supplies IP's RenderStates.frameIndex, or its own counter; 0 = unknown).
 */
public record PortalViewOptions(
    PortalClipPlane clipPlane,   // nullable; world-space (see Appendix A)
    int stencilRef,              // -1 = no host stencil gate
    int stencilMask,             // 0 when no gate
    long hostFrameId,            // 0 = unknown
    float lodFovScaleOverride) { // <= 0 = auto (GL41Metal traversal threshold)

  public boolean hasClipPlane() { return clipPlane != null && clipPlane.isValid(); }
  public boolean hasStencilGate() { return stencilRef >= 0 && stencilMask != 0; }
  public static PortalViewOptions disabled() { return new PortalViewOptions(null, -1, 0, 0, 0f); }
}

/** Host state applied to the writes that reach the host target (GL41Metal direct draw). */
public record PortalDrawState(int stencilRef, int stencilMask,
                              int scissorX, int scissorY,
                              int scissorWidth, int scissorHeight) {}
```

Why separate options from `RenderFrameContext`: the adapter knows the plane/layer at
`MyGameRenderer.renderWorldNew` HEAD, but the matrices/target/viewport only arrive with the first
`runFrameStage` call inside the nested `renderLevel` (see §7.1).

### 3.2 `VoxyRenderBackend` additions (default methods)

```java
public interface VoxyRenderBackend extends AutoCloseable {
  // ... existing members unchanged ...

  /** True when this backend can render LOD during portal passes. */
  default boolean supportsPortalViews() { return false; }

  /** Maximum simultaneous (nested) portal passes; 0 when unsupported. */
  default int portalViewDepthLimit() { return 0; }

  /**
   * Opens a portal pass. Render thread only. The caller MUST call endPortalView() exactly once
   * for every beginPortalView() call, including when this returns false.
   *
   * Returns true when this backend will render LOD for the pass. VoxyRenderSystem opens a
   * suppression scope regardless of the return value: all runFrameStage calls until
   * endPortalView() are ignored when this returns false, protecting the main frame.
   */
  default boolean beginPortalView(PortalViewOptions options) { return false; }

  /** Closes the innermost portal pass. Render thread only. Idempotent when no pass is open. */
  default void endPortalView() {}

  /** True while a portal pass is open on this backend (supported or not). */
  default boolean isPortalViewActive() { return false; }
}
```

Everything existing stays source/binary compatible. `renderOpaque(RenderFrame)` remains main-only;
portal drawing is driven through `runFrameStage`.

### 3.3 `VoxyRenderSystem` additions

```java
public final class VoxyRenderSystem {
  // per-renderer frame state (replaces the global static; see §7.2)
  private RenderFrame currentFrame;
  private IrisUtil.CapturedViewportParameters capturedViewportParameters;
  private final ArrayDeque<PortalScope> portalScopes = new ArrayDeque<>();

  public boolean beginPortalView(PortalViewOptions options) {
    // depth/limit/thread checks; pushes a PortalScope{supported, savedFrame, savedParams,
    // savedFog...}; nulls currentFrame/captured params for the duration; delegates to the
    // backend only when supported.
  }
  public void endPortalView() { /* pops, restores saved state, delegates */ }
  public boolean isPortalViewActive() { return !this.portalScopes.isEmpty(); }

  public RenderFrame currentFrame() { return this.currentFrame; }
  public RenderFrame storeFrame(RenderFrame frame) { this.currentFrame = frame; return frame; }
  public RenderFrame consumeFrame() { var f = this.currentFrame; this.currentFrame = null; return f; }
  public void clearFrame() { this.currentFrame = null; }

  public void captureViewportParameters(IrisUtil.CapturedViewportParameters p) { ... }
  public IrisUtil.CapturedViewportParameters capturedViewportParameters() { ... }
}
```

`beginPortalView` guards (returns false, scope still pushed):

1. not on the render thread (thread captured at construction) → log once, suppress;
2. `!backend.supportsPortalViews()` or `portalScopes.size() >= backend.portalViewDepthLimit()`;
3. a per-session "portal rendering failed" latch (set after a backend exception; see §10).

`endPortalView` is safe to call from a `finally` block and after a failed begin.

### 3.4 Lifecycle contract

```
host frame (main renderer)
  ... main FRAME_BEGIN / VIEWPORT_SETUP / OPAQUE ...
  IP renders portal:
    vrs.beginPortalView(opts)            // pushes scope, clears per-frame view state
      nested renderLevel:
        runFrameStage(OPAQUE, portal ctx)  // backend uses its own portal state
        runFrameStage(TRANSLUCENT, ...)    // optional
    vrs.endPortalView()                  // pops scope, restores main frame state
  ... main TRANSLUCENT / FRAME_END ...
```

Rules:

- Calls are balanced; nesting is LIFO; one pass per depth; no interleaving of begin/end across
  depths.
- The pass may only start after the host frame's main OPAQUE (this is where IP starts portals:
  `[IP] MixinLevelRenderer.java:151-161`). The API does not require it, but the GL41Metal slot
  budget assumes the main slot is already held (see §5.7).
- If the pass is unsupported, stages must be ignored entirely; the backend must not touch any main
  state (this is what F2/F3/F5 need).
- The adapter must open a pass for **every** IP portal render (even when it decides not to use LOD)
  so the suppression guard is active.

### 3.5 Stage routing

| Stage | Main pass | Portal pass, supported | Portal pass, unsupported |
|---|---|---|---|
| `FRAME_BEGIN` | existing behavior | ignore (do not submit main-style frame) | ignore |
| `VIEWPORT_SETUP` | existing (GL46 setup; GL41Metal ignore) | ignore (GL46 sets up at OPAQUE) | ignore |
| `SODIUM_*_SYNC` | no-op today (`VoxyRenderBackend.java:78-80`) | ignore | ignore |
| `PRE_TRANSLUCENT` | existing (GL41Metal Iris bridge) | ignore in phase 1 (Iris degraded) | ignore |
| `OPAQUE` | existing | GL46: setup portal viewport + draw; GL41Metal: submit + sample portal slot | ignore |
| `TRANSLUCENT` | existing | GL41Metal: draw portal held slot; GL46: no-op (in-pipeline) | ignore |
| `FRAME_END` | existing | ignore (pass ends via `endPortalView`) | ignore |

`runFrameStage` in `VoxyRenderSystem` checks the top scope **before** delegating, so unsupported
passes never reach the backend.

### 3.6 Threading expectations

- `beginPortalView` / `endPortalView` / all stage calls: Minecraft render thread, same thread that
  owns the GL context. The backend records `Thread.currentThread()` at construction and fails
  closed (log + suppression) on mismatch.
- No Java-side async work is introduced. GL41Metal's native traversal stays asynchronous on Metal
  (`[native] traversal_pipeline.mm:55-206`); the render thread blocks in
  `NativeBindings.waitCurrent` (`[native] slot_scheduler.mm:39-68`; default `waitTimeoutMs=0` is a
  blocking wait, `Config.java:7`).
- `endPortalView` must be callable while Metal commands from the pass are still in flight; it only
  queues slot retirement through the existing GL fence path (`SlotScheduler.queueSampledSlotRetirement`,
  `SlotScheduler.java:56-67`).

### 3.7 Ownership, allocation, limits

| Resource | Owner | Allocation | Freed |
|---|---|---|---|
| GL46 portal viewport (`Gl46Viewport`, includes HiZ + MDIC buffers) | `Gl46RenderBackend` portal stack | lazily, one per depth, on first supported pass | `Gl46RenderBackend.close()` |
| GL41Metal portal slot/frame/bound state | `Gl41MetalRenderBackend` portal stack | lazily, one per depth | `close()`; slots return to the native pool on `endPortalView` |
| Frame/params/fog save slots | `VoxyRenderSystem.PortalScope` | per pass, small object | on pop |
| Clip plane / options | copied into backend pass state | per pass, small | on pass end |

No portal-identity keyed maps, no growth with portal count. The prior art needed a 10 s eviction
sweep because it keyed viewports by portal UUID; this design avoids that class of leak entirely.

### 3.8 Configuration

System properties, matching the existing backend-config style (`Config.fromSystemProperties`,
`[backend] gl41metal/Config.java:3-9`). Proposed:

| Property | Default | Meaning |
|---|---|---|
| `voxy.portal.enable` | `false` in the first build | Master switch for portal LOD (the suppression scope is always active) |
| `voxy.portal.maxDepth` | `2` | Max nested portal passes with LOD |
| `voxy.portal.iris` | `false` | Attempt LOD in portal passes when a shader pack is active (phase 2) |
| `voxy.portal.obliqueClip` | `false` in S2, `true` after validation | Oblique near-plane clipping (vs culling-only) |
| `voxy.portal.foreignDimensions` | `false` | Allow LOD for non-player-dimension portal passes |
| `voxy.portal.serviceForeign` | `true` | Allow once-per-frame servicing of a foreign `VoxyRenderSystem` |

The GL41Metal slot count must satisfy `slotCount >= 1 + maxDepth`; the backend checks this at
construction and lowers `portalViewDepthLimit()` accordingly (`Config.java:6` default 3).

---

## 4. GL46 mapping (behaviour-preserving)

### 4.1 Extraction, not rewrite

`Gl46RenderBackend.setupFrame` (:240-285) is split into:

```java
private void configureViewport(Gl46Viewport<?> viewport, RenderFrameContext context,
                               PortalClipPlane clipPlane, boolean mainFrame) {
  // :246-268 verbatim: MiB camera adjustment, computeProjectionMat, render scaling
  // + if (clipPlane != null && VoxyConfig portal oblique enabled) ObliqueProjection.apply(...)
  // :270-280 verbatim: setVanillaProjection/setProjection/setModelView/setCamera/setScreenSize
  //                    .update(); viewport.frameId++ (own viewport => own counter)
}
```

`setupFrame` becomes `configureViewport(getViewport(), context, null, true)` plus the existing
`lastFrameMatrices` write (:282-283). The main path executes the same statements in the same order;
no new state is read.

`renderOpaque` (:288-392) is split into:

```java
private void renderOpaqueInternal(Gl46Viewport<?> viewport, boolean service) {
  // :296-341 verbatim (viewport checks, SSBO capture, FB/viewport capture, glViewport,
  //                 pipeline.preSetup, chunkBoundRenderer, pipeline.runPipeline)
  if (service) {
    // :345-359 verbatim (UploadStream tick, setCenterAndProcess, modelService.tick)
    // :300-303/:340-341/:391 TimingStatistics (main only)
  }
  // :365-389 state reset, with stencil/scissor preservation added for portal views
}
```

The public main path calls `renderOpaqueInternal(mainViewport, true)`. A portal pass calls
`renderOpaqueInternal(portalViewport, false)` (no recenter, no model tick — the main pass already
serviced the home system; see §7.4).

`chunkBoundRenderer.render(viewport)` (:328-332) mutates its add/remove queues
(`ChunkBoundRenderer.java:82-95, :164-166`). Portal passes must not mutate them. Add
`renderTo(viewport, boolean mutateQueues)` and pass `false` for portal views; this mirrors the prior
art's once-per-frame freeze. The portal viewport's own `depthBoundingBuffer` (`Viewport.java:9`) is
still cleared and re-rasterized, so near-chunk masking remains correct.

### 4.2 Portal viewport pool

`ViewportSelector` gains one public method:

```java
public T createViewport() { return this.creator.get(); }
```

`Gl46RenderBackend` keeps `ArrayDeque<Gl46PortalView>`; `Gl46PortalView` is
`{ Gl46Viewport<?> viewport; boolean supported; PortalViewOptions options; RenderFrameContext frame; }`.
Viewports are created with `viewportSelector.createViewport()` (uses
`sectionRenderer::createViewport`, `Gl46RenderBackend.java:159`, `MDICSectionRenderer.java:424-426`)
and deleted in `close()` (`Gl46RenderBackend.java:524-562`). This avoids touching the
`extraViewports` map and its lack of eviction (F8).

### 4.3 Portal setup

On the first `OPAQUE` of a supported pass:

1. `context = context.frameContext()` (portal matrices, camera, target FB, rect).
2. `configureViewport(portal.viewport, context, options.clipPlane(), false)`; the portal projection
   comes from the portal `ChunkRenderMatrices.projection()` with the same `computeProjectionMat`
   (:398-408), then the oblique plane if enabled.
3. Guard: `context.sourceFramebuffer() != 0` (GL46 refuses FBO 0, `Gl46RenderBackend.java:320-323`)
   and the target has a depth attachment (`AbstractRenderPipeline.initDepthStencil` reads it,
   `AbstractRenderPipeline.java:160-163`). If not, mark the pass unsupported for this frame and
   draw nothing (log once).
4. Do **not** write `lastFrameMatrices`, do **not** call `getViewport()`, do **not** touch the
   default viewport.

### 4.4 Portal draw and GL state

`renderOpaqueInternal(portalViewport, false)` keeps the existing SSBO/FB/viewport capture and
restore (:307-316, :365-366, :386-388), but the portal path additionally saves/restores:

- stencil enable, func, ref, value mask, write mask, sfail/dpfail/dppass;
- scissor enable and box;
- (defensively) `GL_CLIP_DISTANCE0..7` enables — Voxy never enables them; see D4.

These are needed because the host (IP) owns the stencil/scissor in the shared-target mode and
because the existing main path leaves stencil disabled (F7). The portal pass saves the host scissor
and disables it for the internal pipeline passes (the pipeline assumes a full-target viewport),
restoring it after the pass; the final composite is gated by the host stencil when the target is
shared (D5). This is the clean version of the prior art's disable/restore of `GL_SCISSOR_TEST`
around the Voxy draw.

Composite gating: `AbstractRenderPipeline.runPipeline` gets an overload
`runPipeline(viewport, sourceFB, srcW, srcH, StencilGate gate)`; the gate is passed to `finish` and
`transformBlitDepth`. `NormalRenderPipeline.finish`
(`NormalRenderPipeline.java:112-159`) changes:

- if `gate != null`, **force** the final blit even when `fogCoversAllRendering` (currently skipped
  at :143-156);
- around `transformBlitDepth`, do not call `glDisable(GL_STENCIL_TEST)`; enable stencil and set
  `func = GL_EQUAL, ref, mask` for the composite, then restore the saved state.

`transformBlitDepth` (`AbstractRenderPipeline.java:180-205`) gets an optional gate parameter; when
null it behaves exactly as today (stencil disabled at :190/:203). The main view never passes a gate.

Why gate the composite rather than the internal draws: the pipeline renders into its own FBO
(`AbstractRenderPipeline.fb` (`AbstractRenderPipeline.java:57`) and `NormalRenderPipeline.setup` (`:65-101`)) where it is free to use stencil for the near-terrain mask
(`initDepthStencil` :142-176). Only the final composite touches the host target, so the host
stencil is applied exactly once and is never modified. This is the clean version of what the prior
art did by disabling scissor around the whole draw.

### 4.5 Target modes

- **Framebuffer renderer (default)**: the portal target is IP's `SecondaryFrameBuffer`, a dedicated
  full-size FBO (`[IP] RendererUsingFrameBuffer.java:48, :68-76`); `stencilRef = -1`. The composite
  is unrestricted inside the target; IP composites it through the portal shape. No host stencil is
  needed.
- **Stencil renderer (secondary)**: the target is IP's main FBO with the portal mask; the adapter
  passes `stencilRef = PortalRendering.getPortalLayer()`, `stencilMask = 0xFF` (`[IP]
  RendererUsingStencil.java:277-289`). The composite blit is stencil-gated (D5). This mode also
  needs the saved/restored stencil state above.

IP's own projected size is the full main framebuffer in both modes (`[IP] SecondaryFrameBuffer.java:7-30`;
the stencil path uses the main FBO), so Voxy's render-scaling factor produces the same target size
as the main view and the pipeline's per-size framebuffer (`NormalRenderPipeline.java:66-77`) does
not churn. The design still includes a one-time assertion/log if the portal rect differs from the
main rect, because a mismatch reallocates the pipeline FBO every frame.

### 4.6 Clip plane on GL46

Two independent mechanisms (D4):

1. **Traversal culling.** After `viewport.update()` (`Viewport.java:84-107`) computes
   `frustumPlanes[0..5]`, overwrite `frustumPlanes[5]` (the far plane, which the traversal shader
   currently ignores) with the camera-relative clip plane:
   `n' = n`, `d' = -dot(n, planePoint - camera)` — exactly the prior-art math. Change
   `assets/voxy/shaders/lod/frustum.glsl:10-14` to test plane 5 as well:
   `&& testPlane(frustum.planes[5], pos, size)`. The uniform already uploads six planes
   (`HierarchicalOcclusionTraverser.java:181-187`), and the traversal space is camera-relative
   (`screenspace.glsl:65-67`: `basePos = ((node.pos<<lod)-camSecPos)<<5 - camSubSecPos`). No new
   uniform, no compute-shader ABI change; when no clip plane is supplied, plane 5 remains the far
   plane and the test is harmless.
2. **Fragment clipping (exact).** After `computeProjectionMat` (:257), apply the oblique near-plane
   transform to the Voxy projection in eye space (Appendix A). This clips geometry behind the plane
   in every shader path, including Iris-patched shaders. Feature-flagged (`voxy.portal.obliqueClip`)
   until validated.

The GL46 section shaders use one `MVP` for geometry and write their own Voxy depth
(`assets/voxy/shaders/lod/quad_util.glsl:162`); the final composite inverse-projects the same
`viewport.MVP` (`AbstractRenderPipeline.java:195`), so an oblique projection stays consistent through
the composite — no depth-swap logic exists on GL46.

### 4.7 Iris branch on GL46

`RenderPipelineFactory` chooses the Iris pipeline once at backend construction when Iris is
installed and the pack pipeline data exists (`.../gl46/pipeline/RenderPipelineFactory.java:20-54`),
and `IrisVoxyRenderPipeline` binds the pack's draw targets to its own framebuffers at construction
(`IrisVoxyRenderPipeline.java:48-67`; `setupAndBindOpaque` :237-241). Rendering into a portal target
would require a second, portal-scoped Iris target set; this is out of scope.

Decision: if the selected pipeline is `IrisVoxyRenderPipeline` **or** a pack is active during the
pass, `beginPortalView` returns false (suppression on, no LOD). This matches the prior art's
`shouldSkipLodDraw` for Iris. The main frame is unaffected because the pass scope clears/restores
the per-renderer frame and captured params (§7.2).

### 4.8 GL46 call-site list

| File | Anchors | Change |
|---|---|---|
| `.../backend/VoxyRenderBackend.java` | :8-84 | default portal methods |
| `.../core/VoxyRenderSystem.java` | :100-169, :226-237 | API, per-renderer frame/params, scope save/restore |
| `.../backend/gl46/Gl46RenderBackend.java` | :82, :159, :216-237, :240-285, :288-392, :433-438, :524-562 | portal stack, split setup/draw, clip plane, state save/restore, free |
| `.../rendering/ViewportSelector.java` | :15-59 | `createViewport()` public; no map changes |
| `.../gl46/pipeline/AbstractRenderPipeline.java` | :99-140, :180-205 | gate-aware `runPipeline`/`finish`/`transformBlitDepth` |
| `.../gl46/pipeline/NormalRenderPipeline.java` | :112-159 | portal composite gate, force blit |
| `.../gl46/pipeline/IrisVoxyRenderPipeline.java` | :41, :48-67, :237-249 | no change; detected as unsupported |
| `.../mixin/sodium/MixinDefaultChunkRenderer.java` | :97-140 | per-renderer frame state |
| `.../mixin/sodium/MixinSodiumWorldRendererVS.java` | :61-75 | per-renderer frame state |
| `.../mixin/iris/MixinIrisRenderingPipeline.java` | :100-181 | per-renderer frame/params; skip during portal pass |
| `.../mixin/iris/MixinLevelRenderer.java` | :26-52 | per-renderer capture; skip during portal pass |
| `.../core/util/IrisUtil.java` | :26-51 | remove the static capture (or keep as a deprecated shim) |
| `.../backend/RenderFrameStageState.java` | :3-26 | delete/deprecate |
| `assets/voxy/shaders/lod/frustum.glsl` | :10-14 | add plane-5 test |

---

## 5. GL41Metal mapping

### 5.1 New per-view state

```java
final class PortalMetalView {
  PortalViewOptions options;                 // copied
  RenderFrameContext context;                // first OPAQUE stage context
  DistantRenderer.FrameMatrices matrices;    // computed at submit, reused for draw
  Frame frame;                               // native Frame (slot, ids, matrices)
  int writeSlot = -1;
  LoadedVolumeBound bound = LoadedVolumeBound.DISABLED;
  int heldSlot = -1;                         // translucent hold for this view
  Frame heldFrame;
  RenderFrameContext heldContext;
  boolean opaqueDrawn;
  boolean unsupported;                       // Iris/degraded/slotless
}
```

`Gl41MetalRenderBackend` keeps an `ArrayDeque<PortalMetalView>` bounded by
`min(config maxDepth, slotCount - 1)`.

### 5.2 Orchestration changes

Main-frame fields stay main-only; portal views never read/write them:

- `lastFrameMatrices` (:37, :137-139): main only.
- `currentBound` (:36): keep it for the main path, but capture it into a `heldTranslucentBound`
  field when holding: `holdTranslucentSlot(slot, frame, context, bound)` (:312-316); main
  translucent sampling reads `heldTranslucentBound` instead of the live `currentBound` (:255, :279).
  Portal views store their own bound on the pass. This alone fixes F6 and is a behaviour-preserving
  main-path refactor.
- `heldTranslucentSlot/Frame/Context` (:44-46): main only. `submitMetalFrame` still releases it
  (:122-124); **portal submits use a separate method that does not call it**.
- `nextFrameId` (:38): shared, monotonically increasing; used for labels and native slot state
  (`[native] traversal_pipeline.mm:36-46, :61-62`), uniqueness per slot is what matters.

New flow:

```java
runFrameStage(stage, ctx, frame):
  if (portalActive()) return portalStage(stage, ctx, frame);  // never touches main state
  ... existing switch ...
```

`portalStage`:

- `FRAME_BEGIN`, `VIEWPORT_SETUP`, `SODIUM_*`, `PRE_TRANSLUCENT` → return `frame` (do not submit).
- `OPAQUE` → `submitPortalTraversal(pass, ctx.frameContext())` once, then `drawPortalOpaque(pass)`;
  if `pass.unsupported` → return `frame`.
- `TRANSLUCENT` → `drawPortalTranslucent(pass)` if it held a slot; else return.
- `FRAME_END` → return.

`submitPortalTraversal` is the existing `submitMetalFrame` body (:122-149) refactored:

- no `releaseHeldTranslucentSlot()`;
- no `lastFrameMatrices` write;
- `DistantRenderer.computeFrameMatrices(context, clipPlane)` and the explicit plane/fov-scale args;
- `slotScheduler.acquireWriteSlot` (:141); on -1 mark the pass slotless (degrade, log once per
  session) — this is the existing no-free-slot behavior (:145-147) but scoped to the pass.

`drawPortalOpaque` mirrors `sampleDrawlistOpaque` (:156-191):

- `waitForSlot` (`SlotScheduler.selectSlotForSampling` :40-54; blocking by default);
- use the **stored** `pass.matrices` (not a recomputed stage context) so traversal and draw agree;
- `bound = renderCurrentBound(pass.matrices.drawMvp(), pass.context)` (:318-337);
- `drawlistRenderer.render(..., PortalDrawState...)` into `context.sourceFramebuffer()`;
- if translucent work exists (`NativeBindings.isSlotTranslucentValid` :166-167), hold the slot on
  the pass; otherwise queue retirement (:186-189).

`drawPortalTranslucent` mirrors `sampleVanillaTranslucent` (:264-284) with the pass's held slot,
context, matrices and bound; then release. Iris translucent (`sampleIrisTranslucent` :234-262) is
not entered for portal passes in phase 1.

`endPortalView` releases any held slot, retires an unsampled write slot, clears the pass.

### 5.3 Matrices, oblique projection, per-view FOV

`DistantRenderer` (`.../gl41metal/DistantRenderer.java`):

- `computeFrameMatrices(context)` (:121-134) gains `(context, PortalClipPlane)`; applies the oblique
  transform to `projection` before building `traversalMvp`/`drawMvp`; `vanillaDrawMvp` stays the
  unmodified `base * modelView` (it feeds `vVanillaNdcDepth` and the near-scene mask). The
  translation by negative camera sub-section (:136-145) is unchanged.
- `computeDrawlistLodFovScale` (:49-70) currently derives the LOD subdivision scale from the global
  `Minecraft.options.fov()` (:57) and `context.matrices().projection().m11()` (:65). Portal views can
  have portal scaling/FOV changes; add `float lodFovScaleOverride` and use it verbatim when > 0.
  Otherwise keep the existing clamp 1..16 (:69).
- `computeProjectionMat` (:104-113) is unchanged; the oblique hook is applied to its result. This is
  the "hook after computeProjectionMat" required by the task.

Per-view FOV on GL41Metal otherwise comes from the portal base projection (m00/m11 preserved by
`:104-113`), which is correct by construction.

### 5.4 Metal traversal plane

New per-view clipping in the traversal, implemented in three places:

1. `[native] gl41metal_abi.h:17-37` — append `float clipPlane[4];` to `SceneUniformHost`;
   `SCENE_UNIFORM_BYTES` 224 → 240; static-assert the new offset (224). `rasterLimits[1]` is unused
   (written 0 at `traversal_pipeline.mm:110`) and becomes the "clip enabled" flag.
2. `[native] shaders/traversal_worklist.metal:3-12` — append `float4 clipPlane;` to `SceneUniform`.
   In `classifyNode` (:250-361), after `base`/`size` are known (:251-254), before the 5-plane test
   (:307-321):
   ```metal
   if (scene.rasterLimits.y != 0u) {
     float3 pv = base + select(float3(0.0f), float3(size),
                               scene.clipPlane.xyz > float3(0.0f));
     if (dot(scene.clipPlane.xyz, pv) + scene.clipPlane.w < 0.0f) return 0u;
   }
   ```
   `base` is camera-relative (same space as `cameraSubPos`, :253-254), so the plane must be
   camera-relative (Appendix A). This is the exact AABB p-vertex test and costs 1 dot product per
   node.
3. `[native] traversal_pipeline.mm:16-20, :77-113` — extend the JNI signature with
   `jlong clipPlaneAddress`; when non-null, `memcpy(scene->clipPlane, ..., 16)` and set
   `scene->rasterLimits[1] = 1`.
4. `NativeBindings.submitTraversal` (`NativeBindings.java:105-119`) — add the address parameter.
   `DistantRenderer.submitTraversal` (:16-47) grows its `MemoryBuffer` from 32 to 48 floats and
   writes the plane at offset 32*4; pass `0` when disabled.

Note: the oblique `traversalMvp` already makes the shader's near-plane test (`planes[4] = row3 + row2`,
:307-321) equal to the clip plane, so this explicit test is redundant in the oblique path. It is
still required for (a) the culling-only fallback, (b) exactness when the oblique matrix is within
epsilon, and (c) the task's "plane in Metal traversal" contract. It is cheap and independent.

### 5.5 Draw path and host state

`DrawlistOpaqueRenderer` (`.../gl41metal/DrawlistOpaqueRenderer.java`):

- Add a nullable `PortalDrawState` (same gate fields as `PortalViewOptions`) to
  `render`/`renderTranslucent`/`renderRanges`/`drawRanges`/`drawTranslucentRanges`
  (:438-465, :506-587, :602-647, :927-1019, :1587-1666).
- In `drawRanges` (:927-1019):
  - with a gate: do not call `disableHostClipState()` (:936); set
    `glEnable(GL_STENCIL_TEST)`, `glStencilFunc(GL_EQUAL, ref, mask)`,
    `glStencilOp(KEEP, KEEP, KEEP)`, `glStencilMask(0)`; keep host scissor as-is.
  - without a gate: existing behavior.
  - force `uUseVoxyDepth = 1` (`opaque_ranges.vert:249-253`) so the oblique clip plane actually
    clips; today it is set to `useSsao ? 1 : 0` (:981-983) and portal views disable SSAO.
  - disable SSAO for portal views (`computeSsaoSteps` :76-90 returns 0 when the pack is active;
    add an explicit portal flag); this also avoids the SSAO composite path (:1420+) writing an
    ungated full-screen result.
  - `snapshotSourceDepth` still reads `context.sourceFramebuffer()`'s depth (portal targets have
    depth); the near-depth uniforms are unused with Voxy depth forced.
- In `drawTranslucentRanges` (:1587-1666): same gate; the shader already uses Voxy depth
  (`translucent_ranges.vert:253-254`), so no depth-switch change.
- `StateSnapshot` (:2326-2571): add stencil func/ref/value mask/write mask/sfail/dpfail/dppass to
  the captured/restored set. Scissor enable/box is already captured (:2348-2349, :2499-2506,
  :2531-2549). `GL_CLIP_DISTANCE` is intentionally not used by Voxy (core profile shaders do not
  write `gl_ClipDistance`); if a future path enables it, it must be added here.
- `GbufferCompositor` (`.../bridge/GbufferCompositor.java:98-168`): no change in phase 1; portal
  passes never enter the Iris bridge. The full-support option is described in §5.6.

On the backend side, `Gl41MetalRenderBackend.renderCurrentBound` (:318-337) still uses `drawMvp`
(now oblique). The translucent bound shader compares `vVoxyDepth` (`translucent_bound.frag:21`),
which is derived from the same oblique draw MVP (`translucent_ranges.vert:262`), so bound and draw
stay in the same depth space. Requirement: the bound renderer and the draw must always receive the
same `drawMvp`; never mix oblique and non-oblique here.

### 5.6 Iris branch options

| Option | Work | Verdict |
|---|---|---|
| **A. Explicit degraded mode (phase 1)** | Portal pass opens with `unsupported=true`; no Metal traversal, no bridge, no GL draw; main Iris path untouched. | **Recommended.** Matches prior art (`shouldSkipLodDraw` with a pack). Zero risk to the pack's targets. |
| B. Full support: portal-scoped Iris bridge | Build a `DistantBridgeJob` whose `targetTextureIds`/`sourceDepthTextureId`/output size point at the portal FBO (or at per-portal pack targets), plus a portal-scoped `GbufferCompositor` FBO and portal-correct custom uniforms/texture state. Requires pack-specific gbuffer semantics inside a nested render where IP has already nulled the Iris pipeline (`[IP] MyGameRenderer.java:221`). | Separate project; not phase 1. |
| C. Draw into the portal FBO *without* pack semantics (vanilla-looking LOD) | Use the non-Iris drawlist path while a pack is active. | Rejected: visibly inconsistent with the pack's lighting/water and can double-darken. |

The important part is that A is safe: portal stages are suppressed before `context.shaderPackActive()`
is consulted, so `sampleDrawlistIrisOpaque`/`sampleIrisTranslucent` never run in a portal pass.

### 5.7 Slot budget

Default slots = 3 (`Config.java:6`; native minimum 2, `[native] gl41metal_context.mm:91-94`).
Per-slot native memory is ~37 MB (`[native] terrain_resources.mm:20-70`: 2×8 MB scratch queues,
2×6.4 MB worklists, 2×4 MB range arrays, plus small buffers). Nested pass accounting:

```
main held translucent slot (1)
+ portal depth 1 held slot (1)
+ portal depth 2 held slot (1)
= 3  -> exactly the default
```

`portalViewDepthLimit()` is therefore `slotCount - 1` (2 by default). Deeper or slotless passes
degrade (no LOD for that pass) and log once; they never throw. Raising `maxDepth` requires raising
`voxy.gl41metal.slotCount` and accepting ~37 MB per extra slot.

IP's own recursion limit is `IPGlobal.maxPortalLayer = 5` (`[IP] IPGlobal.java:33`), but the
framebuffer renderer stops after one layer (`[IP] RendererUsingFrameBuffer.java:68-71`). The
default `maxDepth=2` is future-proofing for a patched IP renderer; with unpatched IP only depth 1
occurs and the depth-2 state is never allocated.

### 5.8 GL41Metal call-site list

| File | Anchors | Change |
|---|---|---|
| `.../backend/VoxyRenderBackend.java` | :8-84 | default portal methods |
| `.../backend/gl41metal/Gl41MetalRenderBackend.java` | :36-46, :82-120, :122-149, :156-191, :234-284, :312-350, :406-416 | portal stack, split submit/draw, per-view bound, degrade, free |
| `.../backend/gl41metal/DistantRenderer.java` | :16-47, :49-70, :76-90, :104-134 | oblique matrices, clip plane, fov override, portal SSAO=0 |
| `.../backend/gl41metal/DrawlistOpaqueRenderer.java` | :438-465, :506-587, :602-647, :649-728, :927-1019, :1407-1410, :1587-1666, :2326-2571 | portal draw state, stencil gate, Voxy depth, SSAO off, state restore |
| `.../backend/gl41metal/jni/NativeBindings.java` | :105-119 | clip-plane arg |
| `.../backend/gl41metal/Config.java` | :3-9 | (or new `PortalConfig`) depth/slot validation |
| `[native] gl41metal_abi.h` | :12, :17-37 | SceneUniformHost field/size/asserts |
| `[native] shaders/traversal_worklist.metal` | :3-12, :250-361 | struct + classifyNode plane test |
| `[native] traversal_pipeline.mm` | :16-20, :77-113 | JNI arg + scene write |
| `.../bridge/GbufferCompositor.java` | :98-168 | no change (degraded) |

---

## 6. Native / shader changes and rebuild

### 6.1 Exact files and functions

| File | Function / region | Change |
|---|---|---|
| `src/main/native/gl41metal/gl41metal_abi.h` | `SceneUniformHost` :17-37; `SCENE_UNIFORM_BYTES` :12 | append `float clipPlane[4]`; 224 → 240; add offset assert |
| `src/main/native/gl41metal/shaders/traversal_worklist.metal` | `SceneUniform` :3-12; `classifyNode` :250-361 | append `float4 clipPlane`; AABB p-vertex plane test after :254 |
| `src/main/native/gl41metal/traversal_pipeline.mm` | JNI `submitTraversal` :16-20; scene write :77-113 | new `jlong clipPlaneAddress`; copy + `rasterLimits[1]` flag |
| `src/main/java/.../gl41metal/jni/NativeBindings.java` | :105-119 | signature |
| `src/main/java/.../gl41metal/DistantRenderer.java` | :16-47 | 48-float buffer, plane write, fov scale |

No changes to `slot_scheduler.mm`, `terrain_resources.mm`, `drawlist_pipeline.mm`, or the GLSL
drawlist shaders are required for the clipping mechanism (the oblique is a matrix change; the
`uUseVoxyDepth=1` change is a Java uniform upload).

### 6.2 Rebuild (macOS only)

`build.gradle`:

- `compileGl41MetalNative` (:55-99): `clang++ -std=c++17 -ObjC++ -fobjc-arc -O3 ... -arch x86_64
  -arch arm64 -framework Metal ...` → `build/gl41metal-native/libvoxy_gl41metal.dylib`;
  `onlyIf { isMacOsHost }` (:58).
- `compileGl41MetalShaders` (:101-123): `xcrun metal -std=metal3.0 -O ...` →
  `build/gl41metal-shaders/voxy_gl41metal.metallib`; `onlyIf { isMacOsHost }` (:104).

Commands on a Mac with Xcode:

```bash
./gradlew compileGl41MetalShaders compileGl41MetalNative
./gradlew :neoforge:build        # or :fabric:build
```

Packaging: `neoforge/build.gradle:82-92` and `fabric/build.gradle:190-199` copy the dylib into
`natives/macos/` and the metallib into `natives/macos/gl41metal/`. `NativeBindings` loads the
bundled resources first and the `build/...` dev paths second (`NativeBindings.java:11-15, :46-86`).

**The dylib and metallib must be rebuilt together** whenever `SCENE_UNIFORM_BYTES` changes: the
host writes 240 bytes, the shader reads the tail. A stale pair reads garbage clip planes (or
mismatched offsets), so the portal clip feature must be gated on a native build check or version
string. Windows/Linux never load these artifacts (`Gl41MetalSupport` requires macOS,
`.../gl41metal/Gl41MetalSupport.java:10-14`), so GL46-only changes need no native build. CI only runs
the native tasks on macOS (`onlyIf`, `build.gradle:58, :104`), so a design that forgets to rebuild on
a Mac will silently ship a stale metallib.

---

## 7. Threading and frames

### 7.1 Interleaving

```
Host frame F (Minecraft render thread)
├─ main renderLevel
│  ├─ Voxy FRAME_BEGIN (Iris only; GL41Metal submits main traversal)
│  ├─ Voxy VIEWPORT_SETUP (Iris only; GL46 configures main viewport)
│  ├─ Sodium opaque:
│  │    Voxy SODIUM_*_SYNC  (no-op today)
│  │    Voxy OPAQUE         (GL46: setup+draw; GL41Metal: submit+sample+hold)
│  └─ IP beginTranslucents boundary
│     └─ for each portal:  [IP MixinLevelRenderer.java:151-161]
│        ├─ VoxyRenderSystem.beginPortalView(options)
│        ├─ nested gameRenderer.renderLevel on the destination renderer  [IP MyGameRenderer.java:229-235]
│        │    Voxy OPAQUE / TRANSLUCENT for the portal pass
│        └─ VoxyRenderSystem.endPortalView()
│  └─ main TRANSLUCENT (GL41Metal draws its main held slot)
└─ main FRAME_END
```

The nested pass's matrices/target/viewport are captured per stage by
`VoxyRenderSystem.captureFrameContext` (:148-162), exactly as for the main pass.

### 7.2 Moving frame state into `VoxyRenderSystem`

Required changes (F1/F4):

- `RenderFrameStageState.currentFrame` → `VoxyRenderSystem.currentFrame`. Replace every use:
  `MixinDefaultChunkRenderer.java:97-139`, `MixinSodiumWorldRendererVS.java:61-75`,
  `MixinIrisRenderingPipeline.java:107-109, :127-129, :146`. Each mixin already resolves the
  renderer from `Minecraft.getInstance().levelRenderer` (e.g. `MixinDefaultChunkRenderer.java:80-84`),
  which IP has swapped to the destination renderer, so the per-renderer field automatically scopes
  to the correct world.
- `IrisUtil.CAPTURED_VIEWPORT_PARAMETERS` → `VoxyRenderSystem.capturedViewportParameters`.
  `MixinLevelRenderer` (iris) captures into the renderer it is attached to (:36-50); the Iris stage
  mixin reads it from the same renderer (`MixinIrisRenderingPipeline.java:102-109, :123-131, :143-147,
  :168-179`).
- `PortalScope` saves and clears the frame and captured params at `beginPortalView`, restores at
  `endPortalView`. Even if a backend returns a portal `RenderFrame` from a stage, it cannot leak into
  the main frame's later TRANSLUCENT stage.
- `MixinLevelRenderer` (iris) must skip capture when `renderer.isPortalViewActive()`; otherwise the
  nested render overwrites the main view's params (F4). Because the scope nulls the field anyway,
  skipping is defense in depth.

This is behavior-preserving for the main frame: with one renderer, the same value is stored/read at
the same points.

### 7.3 Sodium sync stages

`beginVanillaRenderSectionSync`/`syncVanillaRenderSections` are no-ops for both backends today
(`VoxyRenderBackend.java:78-80`; no overrides found). They are called during the nested Sodium pass
(`MixinDefaultChunkRenderer.java:95-96, :114`), so they resolve the destination renderer, which is
harmless. Portal passes must never route them to a main renderer. If these stages become real, they
must be per-renderer and portal-gated; the API deliberately does not expose them for portal passes.

### 7.4 Servicing (uploads, models, render distance)

Rules:

- The **home** `VoxyRenderSystem` is serviced by its main OPAQUE exactly as today
  (`Gl46RenderBackend.java:345-359`; `Gl41MetalRenderBackend.java:125`). Portal passes on the home
  system must **not** service: no `setCenterAndProcess` with the portal camera (F10), no duplicate
  model/upload ticks.
- A **foreign** `VoxyRenderSystem` (a synthetic IP dimension) only ever renders through portal
  passes. Its first pass in a host frame services it once (`hostFrameId` + a `lastServicedHostFrame`
  field). This mirrors the prior art's `mic$serviceThisPass` logic.
- `RenderDistanceTracker` may recenter for a foreign system (the portal camera is in that world)
  but never for the home system's portal pass.

Detecting "home": Voxy can record the `VoxyRenderSystem` that last ran a non-portal OPAQUE
(`homeSystem` static weak ref) and compare in `beginPortalView`. The adapter may instead pass
`hostFrameId` and let the backend decide; both are listed here because the adapter knows IP's frame
index while Voxy may not.

### 7.5 Shutdown and leaks

- `VoxyRenderSystem.shutdown` (:226-237) clears the frame (`RenderFrameStageState.clear()` today) and
  closes the backend; add: clear the portal scope stack and the per-renderer frame/params before
  `backend.close()`.
- Backends free portal viewports (GL46) and release portal slots (GL41Metal) in `close()`
  (`Gl46RenderBackend.java:524-562`, `Gl41MetalRenderBackend.java:406-416`). The native context
  itself waits for pending command buffers before destroy (`[native] gl41metal_context.mm:117-125`),
  so in-flight portal traversals cannot outlive the context.
- IP may dispose a dimension renderer (`[IP] ClientWorldLoader.disposeDimensionDynamically`), which
  triggers Voxy shutdown through `allChanged`/`close`; the scope stack must tolerate being ended
  after shutdown (no-op).

---

## 8. File-by-file change list

Size: S < 50 lines, M 50-200, L > 200 (rough Java/native lines including comments). Risk is
relative to regression severity.

| # | File | Anchors | Work | Size | Risk |
|---|---|---|---|---|---|
| 1 | `.../backend/PortalViewOptions.java` (new) | — | options + clip plane validity | S | low |
| 2 | `.../backend/PortalClipPlane.java` (new) | — | normal+point record | S | low |
| 3 | `.../backend/PortalDrawState.java` (new) | — | gate/scissor state passed to the drawlist | S | low |
| 4 | `.../backend/VoxyRenderBackend.java` | :8-84 | default portal methods | S | low |
| 5 | `.../core/VoxyRenderSystem.java` | :100-169, :226-237 | begin/end, scope stack, per-renderer frame/params, save/restore | M | med |
| 6 | `.../backend/RenderFrameStageState.java` | :3-26 | delete/deprecate | S | med (all call sites) |
| 7 | `.../mixin/sodium/MixinDefaultChunkRenderer.java` | :97-140 | per-renderer frame | S | med |
| 8 | `.../mixin/sodium/MixinSodiumWorldRendererVS.java` | :61-75 | per-renderer frame | S | med |
| 9 | `.../mixin/iris/MixinIrisRenderingPipeline.java` | :100-181 | per-renderer frame/params; portal skip | M | med |
| 10 | `.../mixin/iris/MixinLevelRenderer.java` | :26-52 | per-renderer capture; portal skip | S | med |
| 11 | `.../core/util/IrisUtil.java` | :26-51 | remove static capture | S | med |
| 12 | `.../gl46/Gl46RenderBackend.java` | :216-237, :240-285, :288-392, :524-562 | portal stack, split setup/draw, clip, state | L | high |
| 13 | `.../rendering/ViewportSelector.java` | :15-59 | `createViewport()` | S | low |
| 14 | `.../gl46/pipeline/AbstractRenderPipeline.java` | :99-140, :180-205 | gate-aware pipeline/transform | M | high |
| 15 | `.../gl46/pipeline/NormalRenderPipeline.java` | :112-159 | gate composite, force blit | S | high |
| 16 | `.../gl46/pipeline/IrisVoxyRenderPipeline.java` | :41, :48-67 | detect unsupported (no render change) | S | low |
| 17 | `.../gl41metal/Gl41MetalRenderBackend.java` | :36-46, :82-120, :122-149, :156-191, :234-350, :406-416 | portal stack, split submit/draw, per-view bound, degrade | L | high |
| 18 | `.../gl41metal/DistantRenderer.java` | :16-47, :49-70, :104-134 | oblique, clip plane, fov override | M | med |
| 19 | `.../gl41metal/DrawlistOpaqueRenderer.java` | :438-465, :506-587, :602-647, :927-1019, :1407-1410, :1587-1666, :2326-2571 | portal draw state, stencil gate, depth, state restore | L | high |
| 20 | `.../gl41metal/jni/NativeBindings.java` | :105-119 | JNI signature | S | med |
| 21 | `[native] gl41metal_abi.h` | :12, :17-37 | clip plane field/ABI | S | med |
| 22 | `[native] traversal_worklist.metal` | :3-12, :250-361 | struct + classifyNode | S | med |
| 23 | `[native] traversal_pipeline.mm` | :16-20, :77-113 | JNI + scene write | S | med |
| 24 | `.../gl41metal/Config.java` (or new `PortalConfig`) | :3-9 | depth/slot validation + properties | S | low |
| 25 | `assets/voxy/shaders/lod/frustum.glsl` | :10-14 | test plane 5 | S | low |
| 26 | `.../gl46/ObliqueProjection.java` (new) | — | shared clip-plane math + unit test | S | high (math) |
| 27 | `.../bridge/GbufferCompositor.java` | :98-168 | none (degraded); full support is a separate phase | — | — |

Total: roughly 900-1400 changed lines, dominated by #12, #17, #19. No build-file changes are needed
(the native tasks already exist).

---

## 9. Definition of done

### GL46

1. With `voxy.portal.enable=true` and IP's compatibility (framebuffer) renderer, a same-dimension
   portal at 8-256 blocks shows Voxy LOD inside the portal with a correct near/far transition; no
   LOD is visible outside the portal area when the portal is composited.
2. The main view is pixel-identical (within noise) to a portal-free run outside portals: the main
   viewport object, `lastFrameMatrices`, `getSableOcclusionViewport()`, and the main fog capture are
   unchanged across portal frames (debug assertions + screenshots).
3. No pipeline framebuffer reallocation in steady state (resource counters/log stable); no new
   GPU/resource allocation per portal pass.
4. With `voxy.portal.obliqueClip=true`, geometry behind the portal plane is clipped (visual test with
   a plane through terrain); with `false`, culling-only behavior is used and no sky/hole artifacts
   appear.
5. Nested pass (depth 2) renders or degrades cleanly (no exception, no state leak, main frame
   intact).
6. Iris pack active: portal LOD is suppressed, the pack's main render is unchanged, and no Iris
   target is touched during the pass.
7. No-portal gameplay and `/voxy dump` are unchanged when the feature is off; the suppression scope
   alone does not alter main rendering.

### GL41Metal

1. Same visual criteria as GL46 on macOS with `-Dvoxy.renderBackend=gl41metal`, both with and
   without a shader pack (pack = degraded).
2. Main distant translucent water in the main view is still drawn after portal passes (regression
   test for F5/F6).
3. Portal traversal uses the portal camera and clip plane; nodes behind the plane are culled
   (debug counter or a visual test with the plane through a hill), and `classifyNode` receives the
   new `SceneUniformHost` field (native build check/log).
4. Slot accounting: no invalid-state exceptions, no `waitCurrent` deadlock; slotless/deep passes
   degrade with one log; default `slotCount=3` supports `maxDepth=2`.
5. The rebuilt dylib+metallib are packaged in the jar and load on macOS; a stale native pair is
   rejected or disables portal clipping (version check).
6. Iris pack active: portal pass enters degraded mode before any `sampleDrawlistIris*` path; the
   pack's main gbuffer/depth targets are untouched.

### Both

- `beginPortalView`/`endPortalView` are exception-safe (adapter uses `finally`); a failing backend
  latches portal LOD off for the session, never crashes the game.
- World reload/dimension disposal with an open or closed pass leaves no leaked viewports, slots, or
  native buffers.
- The main-frame behavior with no portals is byte-for-byte equivalent in code paths (same calls,
  same order) to HEAD.

---

## 10. Risks and unknowns

| # | Risk | Evidence | Mitigation |
|---|---|---|---|
| R1 | IP's nested `renderLevel` means the same Java code runs twice with swapped renderer/world. Any remaining global state corrupts the main frame. | `[IP] MyGameRenderer.java:114-235`; F1-F6 | D3: all frame-owned state per renderer; pass scope save/restore; suppression for unsupported passes. |
| R2 | IP's stencil renderer shares the main framebuffer; Voxy's pipeline owns stencil and disables it on composite. | `[IP] RendererUsingStencil.java:277-289`; `Gl46RenderBackend.java:368-389`; `AbstractRenderPipeline.java:190-203`; `DrawlistOpaqueRenderer.java:1407-1410` | Default to IP's framebuffer renderer (prior art enforces it); implement the stencil gate as a secondary mode; save/restore stencil+scissor. |
| R3 | Iris pack targets are main-view-owned (GL46 fixed at construction; GL41Metal bridge FBOs). | `IrisVoxyRenderPipeline.java:48-67, :237-249`; `GbufferCompositor.java:98-168` | Explicit degraded mode; gate before any Iris path; phase-2 per-portal targets. |
| R4 | Cross-dimension LOD may have no data and costs a full engine + native context per dimension. | `WorldIdentifier` per dimension (`.../commonImpl/WorldIdentifier.java:23-41`); `VoxyRenderSystem` constructor (`:39-59`); GL41Metal native context per backend (`Gl41MetalRenderBackend.java:48-59`) | Default `voxy.portal.foreignDimensions=false`; allow same-dimension only; optional VRS creation guard for non-player levels (separate change). |
| R5 | IP may create a `VoxyRenderSystem` for every synthetic dimension, multiplying memory (macOS: ~110 MB native + engine per dimension). | `[IP] ClientWorldLoader.java:394-461` (creation + `onResourceManagerReload`); `MixinLevelRenderer.java:36-45` (creation on `allChanged`; vanilla 1.21.1 calls it from the reload path — verify in a decompile); `[native] terrain_resources.mm:20-70` per slot ×3 | Document; cap/guard; measure with 3 dimensions; consider a shared native context later. |
| R6 | GL41Metal slot starvation with depth 2 and a held main slot. | `Config.java:6` (3), native min 2 (`gl41metal_context.mm:91-94`), ~37 MB/slot (`terrain_resources.mm:20-70`) | `portalViewDepthLimit = slotCount - 1`; degrade; document `-Dvoxy.gl41metal.slotCount=4+`. |
| R7 | Oblique projection math/sign errors cause missing or unclipped terrain. | no existing use in the repo; prior art used culling only | Feature flag, unit test (project known points), fallback to culling-only + framebuffer target. |
| R8 | Metal ABI mismatch (dylib vs metallib) after changing `SCENE_UNIFORM_BYTES`. | `[native] gl41metal_abi.h:12, :17-37`; `NativeBindings.java:11-15, :68-86` | Rebuild both on macOS; add a version/native-build check; portal clip auto-disables if the native build predates the field. |
| R9 | IP's compatibility renderer does not nest; nested portal layers may not be rendered at all. | `[IP] RendererUsingFrameBuffer.java:68-71` | Phase 1 depth 1; depth-2 code is dormant; the prior art's nested framebuffers show the required IP-side change. |
| R10 | FOV/scaling portals change LOD thresholds. | `DistantRenderer.java:49-70` uses global FOV | `lodFovScaleOverride`; GL46's screen-space metric is already projection-driven (`screenspace.glsl:65-77`). |
| R11 | Bound/draw depth-space mismatch if the oblique draw MVP and the bound matrix diverge. | `Gl41MetalRenderBackend.java:318-337`; `translucent_bound.frag:21`; `translucent_ranges.vert:262` | Always pass the same `drawMvp` to `renderCurrentBound` and the draw; assert matrix identity in debug. |
| R12 | IP may change internals between versions (the patch targets 6.0.7). | `[IP]` checkout v6.0.7; `micimmptl` is version-pinned | Keep the Voxy API independent; adapter lives in the IP fork/compat mod and can be updated alone. |
| R13 | `glGetIntegerv(GL_VIEWPORT)`/FBO 0 targets. | `VoxyRenderSystem.java:148-162`; `Gl46RenderBackend.java:320-323` | Reject `sourceFramebuffer==0` and missing depth attachments for portal passes; degrade. |

Unknowns to resolve during implementation (not blocking the design):

- Whether IP's Iris pipeline hooks fire during a nested `renderLevel` after
  `IrisInterface.setPipeline(..., null)` (`[IP] MyGameRenderer.java:221`). Degraded mode handles both.
- Whether Voxy truly ingests chunks for IP's synthetic dimensions (the per-dimension engine exists;
  data volume is unknown). This decides whether cross-dimension LOD is ever useful.
- Exact IP stencil/scissor state at the moment of the nested OPAQUE; the design saves/restores
  rather than assuming.

---

## 11. Fallback plan (ship safely on both platforms)

Stages, each independently shippable:

**S1 — Safety only (no portal LOD).**
Implement D1/D3: portal pass scope, per-renderer frame/params, suppression of all portal stages,
state save/restore around the pass. The adapter opens a pass around every IP portal render. Result:
the main frame is never corrupted by nested IP renders on either backend. This is the minimum that
must ship even if nothing else is done.

**S2 — GL46 portal LOD (no native build).**
Add the portal viewport pool, split setup/draw, no recenter/no model tick, clip-plane culling via
`frustumPlanes[5]` + `frustum.glsl`, framebuffer-renderer target. Oblique stays off. Iris stays
degraded. This is the lowest-risk visible feature.

**S3 — GL41Metal portal LOD.**
Add per-view slot/frame/bound, refactor `submitMetalFrame`, clip plane in the native traversal,
per-view FOV, optional stencil-gate support for the shared stencil target, preserve the main held
slot. Rebuild and package natives on macOS. Iris stays degraded.

**S4 — Exact clipping and nesting.**
Enable oblique clipping after unit + in-game validation; enable depth-2 rendering where IP can
nest; add the stencil-renderer gate for users who keep IP's default renderer.

**Kill switches / rollback**:

- `voxy.portal.enable=false` → S1 behavior (scope on, no LOD), no crash.
- `voxy.portal.backend=none|gl46|gl41metal` → per-backend opt-out.
- Backend failure latch: any exception in a portal path logs once, sets
  `supportsPortalViews=false` for the session, and returns false; the game continues without portal
  LOD.
- If native rebuild is unavailable, `voxy.portal.obliqueClip=false` and `clipPlane=null` yield the
  pre-clip behavior (LOD still renders inside framebuffer portals, just without plane culling).

---

## Appendix A — clip plane conventions and oblique projection

### A.1 Plane spaces

- IP provides a world-space plane (normal + point, keep side positive): `[IP]
  PortalRendering.getActiveClippingPlane()` (`PortalRendering.java:176-212`); `FrontClipping`
  converts it to a camera-relative equation with `d = -dot(n, point - camera)`
  (`FrontClipping.java:101-119`).
- Voxy's traversal space is camera-relative world space:
  GL46 `basePos = ((node.pos << lod) - camSecPos) << 5 - camSubSecPos`
  (`assets/voxy/shaders/lod/gl46/hierarchical/screenspace.glsl:65-67`); GL41Metal `base =
  (pos*scale - baseSectionFrame)*32 - cameraSubPos` (`traversal_worklist.metal:251-254`).
- Therefore both backends convert the world plane with the **stage camera**:
  `n' = n`, `d' = -dot(n, planePoint - camera)`; keep where `dot(n', p) + d' >= 0`.
  This is exactly the prior-art `applyPortalClipPlane` math.

### A.2 Oblique near plane (GL convention)

Given the eye-space plane `P = (a,b,c,d)` with keep side `a*x+b*y+c*z+d >= 0`, and the Voxy
projection `M` (perspective; `m20=m21=0`, `m23=-1`), the standard Lengyel form in JOML accessors is:

```
qx = (sign(a) + M.m20()) / M.m00()
qy = (sign(b) + M.m21()) / M.m11()
qz = -1
qw = (1 + M.m22()) / M.m23()
s  = 2 / (a*qx + b*qy + c*qz + d*qw)
M.m20 = a*s
M.m21 = b*s
M.m22 = c*s + 1
M.m23 = d*s
```

Caveats that must be locked by a unit test before enabling (`voxy.portal.obliqueClip`):

- Transform `P` from camera-relative world space to eye space with the inverse-transpose of the
  view matrix used to build `M` (JOML: `new Matrix4f(view).invert().transpose()` applied to the
  plane), then normalize it.
- Reject a degenerate plane (`|n|² <= 1e-9`, non-finite components) and fall back to culling-only.
- Verify with a small CPU test: project a point at `dot(n,p)+d = +ε` and one at `-ε`; the former is
  inside the clip volume, the latter is clipped. IP's portal camera can be arbitrarily rotated, so
  test at least one rotated plane.

If the test fails, do **not** ship oblique: use §A.3, which is what the prior art used successfully.

### A.3 Culling-only fallback

- GL46: overwrite `frustumPlanes[5]` and test it in `frustum.glsl` (no shader ABI, no oblique).
- GL41Metal: the `SceneUniformHost.clipPlane` test in `classifyNode` (no oblique).
- Targets: use IP's framebuffer renderer so the portal shape is applied by IP's composite, and no
  fragment can appear outside the portal; inside the portal, geometry behind the plane may still be
  drawn (the artifact this fallback accepts). This is strictly better than today's behavior and is
  proven by the prior art.

---

## Appendix B — adapter contract (IP side, outside this repository)

The Voxy API is consumed by a thin adapter in the IP fork/compat mod. Required call sites and data:

1. Wrap `MyGameRenderer.renderWorldNew` (or `switchAndRenderTheWorld`) with
   `beginPortalView`/`endPortalView` on the **destination dimension's** `VoxyRenderSystem`:
   `((IVoxyRenderSystemHolder) ClientWorldLoader.getWorldRenderer(worldRenderInfo.world.dimension()))
   .voxy$getRenderSystem()` (`[IP] ClientWorldLoader.java:267-289`; `[Voxy] IVoxyRenderSystemHolder.java:23-28`).
   The adapter opens the pass even when it expects degradation (the scope protects the main frame).
2. Options:
   - clip plane: `PortalRendering.getActiveClippingPlane()` (`[IP] PortalRendering.java:176-212`),
     converted to `PortalClipPlane(normal, point)`; null if `IPGlobal.enableClippingMechanism` is
     false or the plane is absent.
   - stencil gate: only for the stencil renderer, `PortalRendering.getPortalLayer()` /
     mask `0xFF` (matches `[IP] RendererUsingStencil.java:277-289`); `-1/0` for the framebuffer
     renderer.
   - `hostFrameId`: IP's `RenderStates.frameIndex` (used for once-per-frame foreign servicing).
   - `lodFovScaleOverride`: 1.0 by default; set from the portal scale if LOD thresholds misbehave.
3. Skip the pass for odd-mirror layers (`PortalRendering.isRenderingOddNumberOfMirrors()`), as the
   prior art did; mirror projections flip winding/culling and are out of scope.
4. If the adapter wants the proven render path, force
   `IPGlobal.renderMode = compatibility` (framebuffer renderer), as the prior art did
   (`VoxyImmersivePortalsPatch.enforceCompatibilityRenderMode`). This also avoids the stencil-gate
   mode and its extra GL-state work.
5. Deeper layers: the unpatched framebuffer renderer stops at one layer
   (`[IP] RendererUsingFrameBuffer.java:68-71`); Voxy's depth-2 support only becomes active if the
   IP side implements nested secondary framebuffers (the prior art's `NestedPortalFramebuffers` is
   the reference).

---

## Appendix C — evidence index (selected)

| Claim | Anchor |
|---|---|
| No portal concept in the backend | `VoxyRenderBackend.java:8-84` |
| GL46 viewport selector/private getOrCreate | `ViewportSelector.java:15-59`; `Gl46RenderBackend.java:82, :159, :433-438` |
| GL46 main setup mutates the shared viewport | `Gl46RenderBackend.java:240-285` |
| GL46 main draw services models/recenter | `Gl46RenderBackend.java:345-359` |
| GL46 pipeline FBOs are per-pipeline and size-keyed | `NormalRenderPipeline.java:65-101` |
| GL46 Iris targets fixed at construction | `IrisVoxyRenderPipeline.java:48-67, :237-249` |
| Global frame state | `RenderFrameStageState.java:3-26` |
| Global captured matrices | `IrisUtil.java:51`; `MixinLevelRenderer.java:26-52` |
| GL41Metal main held slot/currentBound | `Gl41MetalRenderBackend.java:36-46, :122-124, :171-174, :255, :279` |
| GL41Metal slot pool/limits | `Config.java:3-9`; `[native] gl41metal_context.mm:91-94`; `[native] terrain_resources.mm:20-70` |
| GL41Metal matrices | `DistantRenderer.java:104-134` |
| GL41Metal drawlist state | `DrawlistOpaqueRenderer.java:927-1019, :1407-1410, :1587-1666, :2326-2571` |
| Metal traversal coordinate space | `[native] traversal_worklist.metal:250-361` |
| Scene uniform ABI | `[native] gl41metal_abi.h:12, :17-37` |
| Native build/package | `build.gradle:55-123`; `neoforge/build.gradle:82-92`; `fabric/build.gradle:190-199` |
| IP nested render | `[IP] MyGameRenderer.java:114-235` |
| IP portal timing | `[IP] MixinLevelRenderer.java:151-161` |
| IP stencil layer | `[IP] RendererUsingStencil.java:84-100, :277-289` |
| IP framebuffer renderer | `[IP] RendererUsingFrameBuffer.java:48, :68-76`; `[IP] SecondaryFrameBuffer.java:7-30` |
| IP clipping plane | `[IP] PortalRendering.java:176-212`; `[IP] FrontClipping.java:66-119` |
| Prior-art patch (disabled) | `micimmptl-voxy/out/...jar` classes; `micimmptl-voxy/disable_voxy_config.py` |
