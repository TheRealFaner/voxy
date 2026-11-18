package me.cortex.voxy.client.core;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;

import java.util.ArrayDeque;
import java.util.List;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.backend.BackendContext;
import me.cortex.voxy.client.core.rendering.backend.NoopRenderFrame;
import me.cortex.voxy.client.core.rendering.backend.PortalConfig;
import me.cortex.voxy.client.core.rendering.backend.PortalViewOptions;
import me.cortex.voxy.client.core.rendering.backend.RenderBackendId;
import me.cortex.voxy.client.core.rendering.backend.RenderBackendSelection;
import me.cortex.voxy.client.core.rendering.backend.RenderFrame;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameMatrices;
import me.cortex.voxy.client.core.rendering.backend.RenderStage;
import me.cortex.voxy.client.core.rendering.backend.RenderStageContext;
import me.cortex.voxy.client.core.rendering.backend.VoxyRenderBackend;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.Gl41MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.backend.gl46.Gl46RenderBackend;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30C;

public class VoxyRenderSystem {
  private final WorldEngine worldIn;
  private final RenderBackendSelection backendSelection;
  private final VoxyRenderBackend backend;

  // Portal pass state (PORTAL_VIEW_API.md D1/D3/D8). The render thread is captured at
  // construction; portal calls from any other thread fail closed (suppression, logged once).
  private final Thread renderThread = Thread.currentThread();
  private final PortalConfig portalConfig = PortalConfig.fromSystemProperties();
  private final ArrayDeque<PortalScope> portalScopes = new ArrayDeque<>();
  private boolean portalFailureLatched;
  private boolean loggedNonRenderThread;

  // Per-renderer frame state (D3), replacing the former global RenderFrameStageState.currentFrame.
  // Every mixin resolves its renderer from Minecraft.levelRenderer, which IP swaps to the
  // destination renderer for nested portal renders, so these fields scope to the correct world.
  private RenderFrame currentFrame;
  private IrisUtil.CapturedViewportParameters capturedViewportParameters;

  // Fog parameters captured before modification by MixinFogRenderer, for Voxy's own fog pass
  private float capturedFogStart;
  private float capturedFogEnd;
  private final float[] capturedFogColor = new float[4];

  // D1/D3 cross-dimension isolation. The dimension this system renders is captured from the owning
  // LevelRenderer's ClientLevel at construction; the WorldEngine is created from that same level,
  // so this is the engine's dimension. IP swaps Minecraft.levelRenderer and client.level for a
  // portal render but never player.level, so the player's level identifies the home dimension.
  private final @Nullable ResourceKey<Level> dimensionKey;

  // D1 nested-render guard: IP renders a portal by calling LevelRenderer.renderLevel again with a
  // swapped global renderer. Counted by MixinLevelRenderer; a nested render that did not open a
  // portal scope must not run any Voxy stage (the scope is the only legitimate portal path).
  private static int levelRenderDepth;

  public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
    this(world, sm, null);
  }

  public VoxyRenderSystem(
      WorldEngine world, ServiceManager sm, @Nullable ResourceKey<Level> dimensionKey) {
    // Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of
    // loading takes more
    // than timeout, we keep the world acquired
    world.acquireRef();
    Logger.info("Creating Voxy render system");

    System.gc();

    this.worldIn = world;
    this.dimensionKey = dimensionKey;
    this.backendSelection = VoxyClient.getRenderBackendSelection();

    try {
      this.backend = this.createBackend(new BackendContext(world, sm, this.backendSelection));
    } catch (RuntimeException e) {
      world.releaseRef(); // If something goes wrong, we must release the world first
      throw e;
    }

    Logger.info("Voxy render system created with backend '" + this.backend.id() + "'");
  }

  private VoxyRenderBackend createBackend(BackendContext context) {
    return switch (context.selection().id()) {
      case GL46 -> new Gl46RenderBackend(context);
      case GL41METAL -> new Gl41MetalRenderBackend(context);
      case DISABLED ->
          throw new IllegalStateException(
              "Cannot create Voxy render system for disabled backend: "
                  + context.selection().reason());
    };
  }

  public void setCapturedFog(float fogStart, float fogEnd, float[] fogColor) {
    this.capturedFogStart = fogStart;
    this.capturedFogEnd = fogEnd;
    System.arraycopy(fogColor, 0, this.capturedFogColor, 0, 4);
  }

  public float getCapturedFogStart() {
    return this.capturedFogStart;
  }

  public float getCapturedFogEnd() {
    return this.capturedFogEnd;
  }

  public float[] getCapturedFogColor() {
    return this.capturedFogColor;
  }

  /** See {@link VoxyRenderBackend#getSableOcclusionDepthTexture()}. */
  public int getSableOcclusionDepthTexture() {
    return this.backend.getSableOcclusionDepthTexture();
  }

  /** See {@link VoxyRenderBackend#getSableOcclusionViewport()}. */
  public Viewport<?> getSableOcclusionViewport() {
    return this.backend.getSableOcclusionViewport();
  }

  public RenderFrame currentFrame() {
    return this.currentFrame;
  }

  public RenderFrame storeFrame(RenderFrame frame) {
    this.currentFrame = frame;
    return frame;
  }

  public RenderFrame consumeFrame() {
    RenderFrame frame = this.currentFrame;
    this.currentFrame = null;
    return frame;
  }

  public void clearFrame() {
    this.currentFrame = null;
  }

  public void captureViewportParameters(IrisUtil.CapturedViewportParameters parameters) {
    this.capturedViewportParameters = parameters;
  }

  public IrisUtil.CapturedViewportParameters capturedViewportParameters() {
    return this.capturedViewportParameters;
  }

  /**
   * Opens a LIFO-scoped portal pass (PORTAL_VIEW_API.md D1/D3). Render thread only. The scope is
   * always opened, even when the backend cannot render portal LOD: while it is open every Voxy
   * stage of the nested render is suppressed, so an Immersive Portals render can never disturb the
   * main frame. Returns true only when this backend will render LOD for the pass (S2: GL46 with
   * {@code voxy.portal.enable}; GL41Metal always false). The caller MUST call {@link
   * #endPortalView()} exactly once for every call, including when this returns false.
   */
  public boolean beginPortalView(PortalViewOptions options) {
    if (options == null) {
      options = PortalViewOptions.disabled();
    }
    PortalScope scope = new PortalScope(options);

    // Save every piece of per-renderer state the nested render could overwrite, then detach it for
    // the duration of the pass (D3/F1/F4).
    scope.savedFrame = this.currentFrame;
    scope.savedViewportParameters = this.capturedViewportParameters;
    scope.savedFogStart = this.capturedFogStart;
    scope.savedFogEnd = this.capturedFogEnd;
    System.arraycopy(this.capturedFogColor, 0, scope.savedFogColor, 0, 4);
    this.currentFrame = null;
    this.capturedViewportParameters = null;

    boolean supported = false;
    if (Thread.currentThread() != this.renderThread) {
      if (!this.loggedNonRenderThread) {
        this.loggedNonRenderThread = true;
        Logger.error(
            "Voxy beginPortalView called off the render thread; portal stages are suppressed");
      }
    } else {
      try {
        this.saveHostGlState(scope);
        supported = this.resolvePortalSupport();
      } catch (RuntimeException e) {
        this.latchPortalFailure("beginPortalView", e);
      }
    }

    scope.supported = supported;
    this.portalScopes.push(scope);

    if (supported) {
      try {
        // D3: only a foreign system's pass may run service work; the home main pass already did.
        boolean serviceForeign = this.portalConfig.serviceForeign() && !this.isHomeSystem();
        if (!this.backend.beginPortalView(options, serviceForeign)) {
          scope.supported = false;
        }
      } catch (RuntimeException e) {
        this.latchPortalFailure("beginPortalView backend", e);
        scope.supported = false;
      }
    }
    return scope.supported;
  }

  /**
   * Closes the innermost portal pass and restores the main frame's per-renderer state and host GL
   * state. Safe to call from a {@code finally} block, after a failed begin, and when no pass is
   * open (no-op).
   */
  public void endPortalView() {
    PortalScope scope = this.portalScopes.pollFirst();
    if (scope == null) {
      return;
    }

    if (scope.supported) {
      try {
        this.backend.endPortalView();
      } catch (RuntimeException e) {
        this.latchPortalFailure("endPortalView", e);
      }
    }

    this.currentFrame = scope.savedFrame;
    this.capturedViewportParameters = scope.savedViewportParameters;
    this.capturedFogStart = scope.savedFogStart;
    this.capturedFogEnd = scope.savedFogEnd;
    System.arraycopy(scope.savedFogColor, 0, this.capturedFogColor, 0, 4);

    if (scope.glStateSaved && Thread.currentThread() == this.renderThread) {
      try {
        this.restoreHostGlState(scope);
      } catch (RuntimeException e) {
        this.latchPortalFailure("restoreHostGlState", e);
      }
    }
  }

  /** True while a portal pass scope is open on this renderer (supported or not). */
  public boolean isPortalViewActive() {
    return !this.portalScopes.isEmpty();
  }

  /** D1 guard counter: called from the {@code LevelRenderer.renderLevel} HEAD injection. */
  public static void enterLevelRender() {
    levelRenderDepth++;
  }

  /** D1 guard counter: called from the {@code LevelRenderer.renderLevel} RETURN injection. */
  public static void exitLevelRender() {
    if (levelRenderDepth > 0) {
      levelRenderDepth--;
    }
  }

  /**
   * D1 guard safety net: a new host frame starts outside every {@code LevelRenderer.renderLevel}
   * call, so resetting here recovers from a leaked count if another mod swallows an exception from
   * a nested render (the RETURN injection does not run in that case).
   */
  public static void resetLevelRenderDepth() {
    levelRenderDepth = 0;
  }

  /**
   * True while a nested {@code LevelRenderer.renderLevel} is on the stack. IP performs every portal
   * render this way; the only legitimate Voxy activity inside it belongs to a portal scope.
   */
  public static boolean isNestedLevelRender() {
    return levelRenderDepth > 1;
  }

  /**
   * D1/D3: the home system renders the dimension the client player is currently in. A system
   * without a captured dimension (old constructor) is always home, preserving previous behavior.
   */
  public boolean isHomeSystem() {
    if (this.dimensionKey == null) {
      return true;
    }
    var player = Minecraft.getInstance().player;
    if (player == null || player.level() == null) {
      return true;
    }
    return this.dimensionKey.equals(player.level().dimension());
  }

  /**
   * D1: true when every main-frame Voxy stage of this system must be ignored. This is a non-home
   * system outside a portal scope (cross-dimension renderer; rolls back with {@code
   * voxy.portal.suppressForeignMain=false}) or any system invoked from a nested {@code
   * LevelRenderer.renderLevel} without an open portal scope (the bridge is absent or failed). With
   * a scope open, the scope decides (unsupported passes never reach the backend).
   */
  private boolean isMainStageSuppressed() {
    if (!this.portalScopes.isEmpty()) {
      return false;
    }
    if (isNestedLevelRender()) {
      return true;
    }
    return this.portalConfig.suppressForeignMain() && !this.isHomeSystem();
  }

  private boolean resolvePortalSupport() {
    if (this.portalFailureLatched) {
      return false;
    }
    if (!this.portalConfig.allowsBackend(this.backend.id())) {
      return false;
    }
    if (this.portalScopes.size() >= this.portalConfig.maxDepth()) {
      return false;
    }
    // D3: cross-dimension LOD is off by default. The scope still opens for a foreign system (the
    // caller always opens one), but it renders no LOD unless voxy.portal.foreignDimensions is set.
    if (!this.isHomeSystem() && !this.portalConfig.foreignDimensions()) {
      return false;
    }
    if (!this.backend.supportsPortalViews()) {
      return false;
    }
    int depthLimit = this.backend.portalViewDepthLimit();
    return depthLimit > 0 && this.portalScopes.size() < depthLimit;
  }

  private void latchPortalFailure(String where, RuntimeException error) {
    if (this.portalFailureLatched) {
      return;
    }
    this.portalFailureLatched = true;
    Logger.error("Voxy portal views disabled for this session after failure in " + where, error);
  }

  // Saves the host GL state a portal pass (or a future portal draw) may change, so the main frame
  // observes exactly the state it had before the nested render. The list follows
  // PORTAL_VIEW_API.md §4.4: viewport + framebuffer, stencil, scissor, and (defensively) the
  // clip-distance enables. Voxy never enables GL_CLIP_DISTANCE; restoring it keeps that invariant.
  private void saveHostGlState(PortalScope scope) {
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, scope.savedViewport);
    scope.savedDrawFramebuffer = GL11.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);

    scope.savedStencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    scope.savedStencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
    scope.savedStencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
    scope.savedStencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
    scope.savedStencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
    scope.savedStencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
    scope.savedStencilDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
    scope.savedStencilDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);

    scope.savedScissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scope.savedScissorBox);

    int clipDistanceMask = 0;
    for (int i = 0; i < 8; i++) {
      if (GL11.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + i)) {
        clipDistanceMask |= 1 << i;
      }
    }
    scope.savedClipDistances = clipDistanceMask;
    scope.glStateSaved = true;
  }

  private void restoreHostGlState(PortalScope scope) {
    GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, scope.savedDrawFramebuffer);
    GL11.glViewport(
        scope.savedViewport[0],
        scope.savedViewport[1],
        scope.savedViewport[2],
        scope.savedViewport[3]);

    if (scope.savedStencilTest) {
      GL11.glEnable(GL11.GL_STENCIL_TEST);
    } else {
      GL11.glDisable(GL11.GL_STENCIL_TEST);
    }
    GL11.glStencilFunc(scope.savedStencilFunc, scope.savedStencilRef, scope.savedStencilValueMask);
    GL11.glStencilMask(scope.savedStencilWriteMask);
    GL11.glStencilOp(
        scope.savedStencilFail, scope.savedStencilDepthFail, scope.savedStencilDepthPass);

    if (scope.savedScissorTest) {
      GL11.glEnable(GL11.GL_SCISSOR_TEST);
    } else {
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
    GL11.glScissor(
        scope.savedScissorBox[0],
        scope.savedScissorBox[1],
        scope.savedScissorBox[2],
        scope.savedScissorBox[3]);

    for (int i = 0; i < 8; i++) {
      int clipDistance = GL30C.GL_CLIP_DISTANCE0 + i;
      if ((scope.savedClipDistances & (1 << i)) != 0) {
        GL11.glEnable(clipDistance);
      } else {
        GL11.glDisable(clipDistance);
      }
    }
  }

  public RenderFrame setupFrame(
      ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
    // setupFrame/renderOpaque are main-frame-only (PORTAL_VIEW_API.md §3.2). Inside a portal pass
    // they must never touch the main viewport or matrices (F2), so they degrade to a no-op frame.
    // D1 adds the cross-dimension/nested-render suppression: a non-home system outside a scope
    // never allocates or configures a main viewport.
    if (!this.portalScopes.isEmpty() || this.isMainStageSuppressed()) {
      return new NoopRenderFrame(this.backend.id());
    }
    return this.backend.setupFrame(this.captureFrameContext(matrices, cameraX, cameraY, cameraZ));
  }

  public RenderFrame runFrameStage(
      RenderStage stage,
      RenderFrame frame,
      ChunkRenderMatrices matrices,
      double cameraX,
      double cameraY,
      double cameraZ,
      boolean irisActive,
      boolean shaderPackActive) {
    return this.runFrameStage(
        stage, frame, matrices, cameraX, cameraY, cameraZ, irisActive, shaderPackActive, null);
  }

  public RenderFrame runFrameStage(
      RenderStage stage,
      RenderFrame frame,
      ChunkRenderMatrices matrices,
      double cameraX,
      double cameraY,
      double cameraZ,
      boolean irisActive,
      boolean shaderPackActive,
      Object payload) {
    if (!this.portalScopes.isEmpty()) {
      // Stage routing (PORTAL_VIEW_API.md §3.5): unsupported passes never reach the backend, so the
      // nested render cannot reconfigure the main viewport/frame/slots. Supported passes delegate
      // to the backend's portal path (S2/S3); S1 backends always report unsupported.
      PortalScope scope = this.portalScopes.peek();
      if (!scope.supported) {
        return frame;
      }
      try {
        return this.backend.runFrameStage(
            stage,
            new RenderStageContext(
                this.captureFrameContext(matrices, cameraX, cameraY, cameraZ),
                irisActive,
                shaderPackActive,
                payload),
            frame);
      } catch (RuntimeException e) {
        this.latchPortalFailure("runFrameStage(" + stage + ")", e);
        return frame;
      }
    }
    // D1: no main-frame stage may run for a foreign system outside a scope, or from a nested
    // renderLevel that did not open a scope.
    if (this.isMainStageSuppressed()) {
      return frame;
    }
    return this.backend.runFrameStage(
        stage,
        new RenderStageContext(
            this.captureFrameContext(matrices, cameraX, cameraY, cameraZ),
            irisActive,
            shaderPackActive,
            payload),
        frame);
  }

  public RenderFrame runFrameStage(
      RenderStage stage,
      RenderFrame frame,
      ChunkRenderMatrices matrices,
      double cameraX,
      double cameraY,
      double cameraZ) {
    return this.runFrameStage(stage, frame, matrices, cameraX, cameraY, cameraZ, false, false);
  }

  private RenderFrameContext captureFrameContext(
      ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
    int[] dims = new int[4];
    glGetIntegerv(GL_VIEWPORT, dims);
    return new RenderFrameContext(
        matrices,
        cameraX,
        cameraY,
        cameraZ,
        GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
        dims[0],
        dims[1],
        dims[2],
        dims[3]);
  }

  public void renderOpaque(RenderFrame frame) {
    if (frame == null) {
      return;
    }
    // Main-frame-only entry point: a portal pass must not draw the main frame into the portal
    // target (F3), nor service/recenter on the nested render (F10). D1 suppression additionally
    // blocks foreign systems and un-scoped nested renders.
    if (!this.portalScopes.isEmpty() || this.isMainStageSuppressed()) {
      return;
    }
    this.backend.renderOpaque(frame);
  }

  public void setRenderDistance(float renderDistance) {
    this.backend.setRenderDistance(renderDistance);
  }

  public RenderFrameMatrices getLastFrameMatrices() {
    return this.backend.getLastFrameMatrices();
  }

  /**
   * GL texture id of the active backend's opaque Voxy distant-terrain depth target, sampled as
   * {@code .r} for [0,1] depth, or 0 if the backend does not expose one. Used by {@code
   * MixinIrisSamplers} to bind {@code vxDepthTexOpaque}; callers fall back to the Iris depth target
   * when this is 0.
   */
  public int getVoxyDistantOpaqueDepthTextureId() {
    return this.backend.voxyDistantOpaqueDepthTextureId();
  }

  /** Translucent depth target bound to {@code vxDepthTexTrans}; 0 selects the Iris fallback. */
  public int getVoxyDistantTranslucentDepthTextureId() {
    return this.backend.voxyDistantTranslucentDepthTextureId();
  }

  public RenderBackendId getRenderBackendId() {
    return this.backend.id();
  }

  public RenderBackendSelection getRenderBackendSelection() {
    return this.backendSelection;
  }

  public boolean rendersLodTerrain() {
    return this.backend.rendersLodTerrain();
  }

  public void onChunkTrackerReset() {
    this.backend.onChunkTrackerReset();
  }

  public void onSectionRenderStateChanged(long sectionPos, boolean present) {
    this.backend.onSectionRenderStateChanged(sectionPos, present);
  }

  public void beginVanillaRenderSectionSync() {
    // D1/§7.3: these hooks mutate main-frame-owned chunk-bound state. A suppressed system must not
    // run them, and a portal pass must never route them (the portal paths are draw-only).
    if (this.isMainStageSuppressed() || !this.portalScopes.isEmpty()) {
      return;
    }
    this.backend.beginVanillaRenderSectionSync();
  }

  public void syncVanillaRenderSections(ChunkRenderListIterable renderLists, boolean reverse) {
    if (this.isMainStageSuppressed() || !this.portalScopes.isEmpty()) {
      return;
    }
    this.backend.syncVanillaRenderSections(renderLists, reverse);
  }

  public void addDebugInfo(List<String> debug) {
    this.backend.addDebugInfo(debug);
  }

  public void shutdown() {
    try {
      // Drop any open portal scopes without restoring GL state: the context is being torn down
      // (PORTAL_VIEW_API.md §7.5). The backend releases its own portal resources in close().
      this.portalScopes.clear();
      this.currentFrame = null;
      this.capturedViewportParameters = null;
      this.backend.close();
    } catch (Exception e) {
      Logger.error("Error shutting down Voxy render backend", e);
    } finally {
      // Release hold on the world
      this.worldIn.releaseRef();
      Logger.info("Render shutdown completed");
    }
  }

  public WorldEngine getEngine() {
    return this.worldIn;
  }

  /** Diagnostic accessor: the ModelFactory of the active backend, or {@code null}. */
  public me.cortex.voxy.client.core.model.ModelFactory getModelFactory() {
    return this.backend.getModelFactory();
  }

  /**
   * One open portal pass: the per-renderer state saved at begin, the host GL state snapshot, and
   * the support decision. Small and transient; freed when the scope is popped.
   */
  private static final class PortalScope {
    private final PortalViewOptions options;
    private boolean supported;
    private boolean glStateSaved;

    private RenderFrame savedFrame;
    private IrisUtil.CapturedViewportParameters savedViewportParameters;
    private float savedFogStart;
    private float savedFogEnd;
    private final float[] savedFogColor = new float[4];

    private final int[] savedViewport = new int[4];
    private int savedDrawFramebuffer;
    private boolean savedStencilTest;
    private int savedStencilFunc;
    private int savedStencilRef;
    private int savedStencilValueMask;
    private int savedStencilWriteMask;
    private int savedStencilFail;
    private int savedStencilDepthFail;
    private int savedStencilDepthPass;
    private boolean savedScissorTest;
    private final int[] savedScissorBox = new int[4];
    private int savedClipDistances;

    private PortalScope(PortalViewOptions options) {
      this.options = options;
    }
  }
}
