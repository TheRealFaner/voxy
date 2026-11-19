package me.cortex.voxy.client.core.rendering.backend.gl46;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;
import static org.lwjgl.opengl.GL45C.glGetNamedFramebufferAttachmentParameteri;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.backend.BackendContext;
import me.cortex.voxy.client.core.rendering.backend.NoopRenderFrame;
import me.cortex.voxy.client.core.rendering.backend.PortalClipPlane;
import me.cortex.voxy.client.core.rendering.backend.PortalConfig;
import me.cortex.voxy.client.core.rendering.backend.PortalViewOptions;
import me.cortex.voxy.client.core.rendering.backend.RenderBackendId;
import me.cortex.voxy.client.core.rendering.backend.RenderFrame;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameMatrices;
import me.cortex.voxy.client.core.rendering.backend.RenderStage;
import me.cortex.voxy.client.core.rendering.backend.RenderStageContext;
import me.cortex.voxy.client.core.rendering.backend.VoxyRenderBackend;
import me.cortex.voxy.client.core.rendering.backend.gl46.pipeline.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.backend.gl46.pipeline.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.backend.gl46.pipeline.RenderPipelineFactory;
import me.cortex.voxy.client.core.rendering.backend.gl46.section.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.backend.gl46.section.UsesMeshlets;
import me.cortex.voxy.client.core.rendering.backend.gl46.section.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.backend.gl46.traversal.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.backend.gl46.traversal.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.backend.gl46.traversal.NodeCleaner;
import me.cortex.voxy.client.core.rendering.backend.gl46.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.geometry.GeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.RenderResourceReuse;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyFlags;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

// Mechanical relocation of the pre-abstraction VoxyRenderSystem rendering core (this repository's
// MDIC implementation). Behaviour is intentionally kept identical to the current branch, including
// the fog capture consumers, RenderResourceReuse geometry buffer reuse and lateStageCompile.
public final class Gl46RenderBackend implements VoxyRenderBackend {
  private final WorldEngine worldIn;

  private final ModelBakerySubsystem modelService;
  private final RenderGenerationService renderGen;
  private final GeometryData geometryData;
  private final AsyncNodeManager nodeManager;
  private final NodeCleaner nodeCleaner;
  private final HierarchicalOcclusionTraverser traversal;

  private final RenderDistanceTracker renderDistanceTracker;
  public final ChunkBoundRenderer chunkBoundRenderer;

  private final ViewportSelector<? extends Gl46Viewport<?>> viewportSelector;

  private final AbstractRenderPipeline pipeline;

  private RenderFrameMatrices lastFrameMatrices = RenderFrameMatrices.identity();

  // Portal LOD state (PORTAL_VIEW_API.md §4.2-§4.5). Viewports are created lazily, one per open
  // depth, from the section renderer's own factory; they never enter the shared extraViewports
  // map and the main view's viewport is never touched by a portal pass.
  private final PortalConfig portalConfig = PortalConfig.fromSystemProperties();
  private final ArrayDeque<Gl46PortalView> portalViews = new ArrayDeque<>();
  private final List<Gl46Viewport<?>> portalViewports = new ArrayList<>();
  private final boolean irisPipeline;
  private boolean portalFailureLatched;
  private boolean loggedIrisPortalDegrade;
  private boolean loggedPortalDegrade;
  private boolean loggedPortalRectMismatch;
  private int lastMainViewportWidth = -1;
  private int lastMainViewportHeight = -1;
  // D3 `voxy.portal.serviceForeign`: host frame id of the last serviced portal pass, so a foreign
  // system is serviced at most once per host frame (§7.4).
  private long lastServicedHostFrame = Long.MIN_VALUE;

  private static AbstractSectionRenderer.Factory<?, ? extends GeometryData>
      getRenderBackendFactory() {
    // TODO: need todo a thing where selects optimal section render based on if supports the
    // pipeline and geometry data type
    return MDICSectionRenderer.FACTORY;
  }

  public Gl46RenderBackend(BackendContext context) {
    WorldEngine world = context.world();
    Logger.info("Creating Voxy GL46 render backend");

    // Warm the shared quad index buffer up front (previously done at client init).
    SharedIndexBuffer.INSTANCE.id();

    if (Minecraft.getInstance().options.renderDistance().get() < 3) {
      String msg =
          "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
      Logger.warn(msg);
      Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
    }

    // Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
    int[] oldBufferBindings = new int[10];
    for (int i = 0; i < oldBufferBindings.length; i++) {
      oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
    }

    // wait for opengl to be finished, this should hopefully ensure all memory allocations are free
    glFinish();
    glFinish();

    this.worldIn = world;

    var backendFactory = getRenderBackendFactory();

    {
      this.modelService = new ModelBakerySubsystem(world.getMapper());
      this.renderGen =
          new RenderGenerationService(
              world,
              this.modelService,
              context.serviceManager(),
              UsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

      this.geometryData =
          new BasicSectionGeometryData(1 << 20, RenderResourceReuse.getOrCreateGeometryBuffer());

      this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
      this.nodeCleaner = new NodeCleaner(this.nodeManager);
      this.traversal =
          new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

      world.setDirtyCallback(this.nodeManager::worldEvent);

      Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
      world.getMapper().setBiomeCallback(this.modelService::addBiome);

      this.nodeManager.start();
    }

    this.pipeline =
        RenderPipelineFactory.createPipeline(
            this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
    this.irisPipeline = this.pipeline instanceof IrisVoxyRenderPipeline;
    this.pipeline.setupExtraModelBakeryData(this.modelService); // Configure the model service

    // Late stage traversal compile for shaders with taa
    this.traversal.lateStageCompile(this.pipeline);

    var sectionRenderer =
        backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
    this.pipeline.setSectionRenderer(sectionRenderer);
    this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

    {
      int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
      int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

      // Do some very cheeky stuff for MiB
      if (VoxyFlags.IS_MINE_IN_ABYSS) { // TODO: make this somehow configurable
        minSec = -8;
        maxSec = 7;
      }

      this.renderDistanceTracker =
          new RenderDistanceTracker(
              40, minSec, maxSec, this.nodeManager::addTopLevel, this.nodeManager::removeTopLevel);

      this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
    }

    this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

    Logger.info(
        "Voxy GL46 backend created with "
            + this.geometryData.getMaxCapacity()
            + " geometry capacity, using pipeline '"
            + this.pipeline.getClass().getSimpleName()
            + "' with renderer '"
            + sectionRenderer.getClass().getSimpleName()
            + "'");

    for (int i = 0; i < oldBufferBindings.length; i++) {
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
    }

    for (int i = 0; i < 12; i++) {
      GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
      GlStateManager._bindTexture(0);
      glBindSampler(i, 0);
    }
  }

  @Override
  public RenderBackendId id() {
    return RenderBackendId.GL46;
  }

  @Override
  public me.cortex.voxy.client.core.model.ModelFactory getModelFactory() {
    return this.modelService.factory;
  }

  @Override
  public boolean rendersLodTerrain() {
    return true;
  }

  @Override
  public RenderFrame runFrameStage(
      RenderStage stage, RenderStageContext context, RenderFrame frame) {
    // D2 owner check: a frame created by another backend/system is never advanced here. Dropping
    // it makes OPAQUE/VIEWPORT_SETUP build a fresh frame for this system instead of drawing (or
    // storing) a foreign system's frame.
    frame = this.acceptFrame(frame);
    if (!this.portalViews.isEmpty()) {
      return this.runPortalFrameStage(stage, context, frame);
    }
    return switch (stage) {
      // Iris beginLevelRendering setup point: always (re)creates the frame, matching the
      // pre-abstraction CAPTURED_VIEWPORT_PARAMETERS.apply() behaviour.
      case VIEWPORT_SETUP -> this.setupFrame(context.frameContext());
      case OPAQUE -> {
        if (IrisUtil.irisShadowActive()) {
          // During the Iris shadow pass the pre-abstraction getViewport() returned null,
          // so nothing was rendered.
          yield frame;
        }
        RenderFrame renderFrame = frame;
        if (renderFrame == null) {
          renderFrame = this.setupFrame(context.frameContext());
        }
        this.renderOpaque(renderFrame);
        yield renderFrame;
      }
      default -> frame;
    };
  }

  /**
   * D2: only frames created by this backend instance may be advanced. A {@link Gl46Frame} from
   * another {@code VoxyRenderSystem}'s backend is replaced with {@code null}, which the stage
   * switch treats as "no frame yet" (fresh {@code setupFrame}), never as a no-op draw of another
   * system's viewport.
   */
  private RenderFrame acceptFrame(RenderFrame frame) {
    if (frame instanceof Gl46Frame gl46Frame && gl46Frame.owner() != this) {
      return null;
    }
    return frame;
  }

  @Override
  public boolean supportsPortalViews() {
    // Iris stays degraded (§4.7): IrisVoxyRenderPipeline binds the pack's draw targets once at
    // construction, and a portal-scoped target set is out of scope for S2.
    return !this.irisPipeline
        && !this.portalFailureLatched
        && this.portalConfig.allowsBackend(RenderBackendId.GL46);
  }

  @Override
  public int portalViewDepthLimit() {
    return this.supportsPortalViews() ? this.portalConfig.maxDepth() : 0;
  }

  @Override
  public boolean beginPortalView(PortalViewOptions options) {
    return this.beginPortalView(options, false);
  }

  @Override
  public boolean beginPortalView(PortalViewOptions options, boolean service) {
    if (options == null) {
      options = PortalViewOptions.disabled();
    }
    if (!this.supportsPortalViews() || this.portalViews.size() >= this.portalViewDepthLimit()) {
      return false;
    }
    if (IrisUtil.irisShaderPackEnabled()) {
      if (!this.loggedIrisPortalDegrade) {
        this.loggedIrisPortalDegrade = true;
        Logger.warn(
            "Voxy portal LOD disabled while an Iris shader pack is active; portal passes are"
                + " suppressed");
      }
      return false;
    }
    try {
      Gl46PortalView view =
          new Gl46PortalView(this.getPortalViewport(this.portalViews.size()), options, service);
      this.portalViews.push(view);
      return true;
    } catch (RuntimeException e) {
      this.latchPortalFailure("beginPortalView", e);
      return false;
    }
  }

  @Override
  public void endPortalView() {
    // The viewport stays pooled for reuse at the same depth (§3.7/§4.2). Idempotent.
    this.portalViews.pollFirst();
  }

  @Override
  public boolean isPortalViewActive() {
    return !this.portalViews.isEmpty();
  }

  /**
   * Portal stage routing (PORTAL_VIEW_API.md §3.5): only OPAQUE draws; every other stage is
   * ignored, so the nested render cannot submit a main-style frame, reconfigure the main viewport,
   * or service render-distance/model work.
   */
  private RenderFrame runPortalFrameStage(
      RenderStage stage, RenderStageContext context, RenderFrame frame) {
    if (stage != RenderStage.OPAQUE) {
      return frame;
    }
    Gl46PortalView view = this.portalViews.peek();
    if (view == null || !view.supported) {
      return frame;
    }
    if (IrisUtil.irisShadowActive()) {
      return frame;
    }
    try {
      if (context.shaderPackActive()) {
        this.degradePortalView(view, "an Iris shader pack became active");
        return frame;
      }
      RenderFrameContext frameContext = context.frameContext();
      if (!isSupportedPortalTarget(frameContext, view.options)) {
        this.degradePortalView(
            view, "unsupported target framebuffer " + frameContext.sourceFramebuffer());
        return frame;
      }
      this.configureViewport(
          view.viewport,
          frameContext,
          view.options.hasClipPlane() ? view.options.clipPlane() : null,
          false);
      this.checkPortalRect(view.viewport);
      // D3: only a foreign system's pass may service (uploads/models/recenter); a home-system
      // portal pass never does (F10). `voxy.portal.serviceForeign` gates the foreign case and the
      // host frame id bounds it to once per host frame.
      boolean service = this.shouldServicePortalView(view);
      this.renderOpaqueInternal(view.viewport, service, true);
      if (service) {
        this.lastServicedHostFrame = view.options.hostFrameId();
      }
    } catch (RuntimeException e) {
      // Never crash the game from a portal path: disable portal LOD for the session (§10/§11).
      view.supported = false;
      this.latchPortalFailure("portal OPAQUE", e);
    }
    return frame;
  }

  /**
   * D3 `voxy.portal.serviceForeign`/§7.4: a foreign system only renders through portal passes, so
   * its uploads, model ticks and LOD recenter must run from those passes; at most once per host
   * frame id. A host frame id of 0 means the adapter could not identify the frame, in which case
   * each pass services (still bounded by the number of portal passes in that frame).
   */
  private boolean shouldServicePortalView(Gl46PortalView view) {
    if (!view.service || !this.portalConfig.serviceForeign()) {
      return false;
    }
    long hostFrameId = view.options.hostFrameId();
    return hostFrameId == 0 || hostFrameId != this.lastServicedHostFrame;
  }

  private Gl46Viewport<?> getPortalViewport(int depth) {
    while (this.portalViewports.size() <= depth) {
      this.portalViewports.add(this.viewportSelector.createViewport());
    }
    return this.portalViewports.get(depth);
  }

  private void degradePortalView(Gl46PortalView view, String reason) {
    view.supported = false;
    if (!this.loggedPortalDegrade) {
      this.loggedPortalDegrade = true;
      Logger.warn("Voxy portal LOD degraded (no LOD for this pass): " + reason);
    }
  }

  private void latchPortalFailure(String where, RuntimeException error) {
    if (this.portalFailureLatched) {
      return;
    }
    this.portalFailureLatched = true;
    // Keep the open pass stack intact: VoxyRenderSystem still balances endPortalView, and while a
    // pass is open its stages must keep routing to the portal path (which now no-ops).
    Logger.error(
        "Voxy GL46 portal views disabled for this session after failure in " + where, error);
  }

  /**
   * S2 target support (§4.3/§4.5): only a non-default framebuffer with a depth attachment (IP's
   * framebuffer renderer). The shared stencil target is an S4 feature and is rejected here.
   */
  private static boolean isSupportedPortalTarget(
      RenderFrameContext context, PortalViewOptions options) {
    int framebuffer = context.sourceFramebuffer();
    if (framebuffer == 0 || options.hasStencilGate() || !glIsFramebuffer(framebuffer)) {
      return false;
    }
    return glGetNamedFramebufferAttachmentParameteri(
            framebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
        != GL_NONE;
  }

  @Override
  public RenderFrame setupFrame(RenderFrameContext context) {
    var viewport = this.getViewport();
    if (viewport == null) {
      return new NoopRenderFrame(RenderBackendId.GL46);
    }

    this.configureViewport(viewport, context, null, true);
    this.lastMainViewportWidth = viewport.width;
    this.lastMainViewportHeight = viewport.height;

    this.lastFrameMatrices =
        new RenderFrameMatrices(viewport.MVP, viewport.modelView, viewport.projection);
    return new Gl46Frame(this, viewport);
  }

  /**
   * Configures a viewport from a host frame context (PORTAL_VIEW_API.md §4.1). The main path runs
   * the previous {@code setupFrame} statements in the same order; the portal path reuses them on
   * its own pooled viewport, so the main viewport, matrices and {@code lastFrameMatrices} are never
   * touched.
   */
  private void configureViewport(
      Gl46Viewport<?> viewport,
      RenderFrameContext context,
      PortalClipPlane clipPlane,
      boolean mainFrame) {
    double cameraX = context.cameraX();
    double cameraY = context.cameraY();
    double cameraZ = context.cameraZ();

    // Do some very cheeky stuff for MiB
    if (VoxyFlags.IS_MINE_IN_ABYSS) {
      int sector = (((int) Math.floor(cameraX) >> 4) + 512) >> 10;
      cameraX -= sector << 14; // 10+4
      cameraY += (16 + (256 - 32 - sector * 30)) * 16;
    }

    var voxyProjection = computeProjectionMat(context.matrices().projection());

    int width = context.viewportWidth();
    int height = context.viewportHeight();

    { // Apply render scaling factor
      var factor = this.pipeline.getRenderScalingFactor();
      if (factor != null) {
        width = (int) (width * factor[0]);
        height = (int) (height * factor[1]);
      }
    }

    viewport
        .setVanillaProjection(context.matrices().projection())
        .setProjection(voxyProjection)
        .setModelView(new Matrix4f(context.matrices().modelView()))
        .setCamera(cameraX, cameraY, cameraZ)
        .setScreenSize(width, height)
        .update();

    // S2 is culling-only (no oblique projection, §4.6/§11): overwrite the far plane, which the
    // traversal shader now tests, with the camera-relative clip plane. The main frame always
    // keeps its far plane.
    if (!mainFrame && clipPlane != null && clipPlane.isValid()) {
      applyPortalClipPlane(viewport, clipPlane);
    }

    if (VoxyClient.getOcclusionDebugState() == 0) {
      viewport.frameId++;
    }
  }

  /**
   * Writes the camera-relative plane into the unused far-plane slot (§4.6, Appendix A.1): {@code n'
   * = n}, {@code d' = -dot(n, planePoint - camera)}; the shader keeps nodes where {@code dot(n',
   * pos) + d' >= 0}.
   */
  private static void applyPortalClipPlane(Gl46Viewport<?> viewport, PortalClipPlane clipPlane) {
    double d =
        -(clipPlane.nx() * (clipPlane.px() - viewport.cameraX)
            + clipPlane.ny() * (clipPlane.py() - viewport.cameraY)
            + clipPlane.nz() * (clipPlane.pz() - viewport.cameraZ));
    viewport.frustumPlanes[5].set(
        (float) clipPlane.nx(), (float) clipPlane.ny(), (float) clipPlane.nz(), (float) d);
  }

  private void checkPortalRect(Gl46Viewport<?> portalViewport) {
    if (this.loggedPortalRectMismatch || this.lastMainViewportWidth <= 0) {
      return;
    }
    if (portalViewport.width == this.lastMainViewportWidth
        && portalViewport.height == this.lastMainViewportHeight) {
      return;
    }
    this.loggedPortalRectMismatch = true;
    Logger.warn(
        "Voxy portal target rect "
            + portalViewport.width
            + "x"
            + portalViewport.height
            + " differs from the main viewport "
            + this.lastMainViewportWidth
            + "x"
            + this.lastMainViewportHeight
            + "; the render pipeline framebuffer will be resized every frame");
  }

  @Override
  public void renderOpaque(RenderFrame frame) {
    // D2 owner check: never draw a frame created by another backend/system.
    if (!(frame instanceof Gl46Frame gl46Frame) || gl46Frame.owner() != this) {
      return;
    }
    this.renderOpaqueInternal(gl46Frame.viewport(), true, false);
  }

  /**
   * Shared setup + draw for the main frame and portal passes (PORTAL_VIEW_API.md §4.1). The main
   * path ({@code service=true, portal=false}) is the previous {@code renderOpaque} statement for
   * statement. A portal pass ({@code portal=true}) preserves the host stencil/scissor state (§4.4)
   * and skips the timing/service block unless it is a serviced foreign system: D3's {@code
   * voxy.portal.serviceForeign} lets a foreign system run uploads/model ticks/recenter once per
   * host frame (§7.4), while a home-system portal pass never does (F10).
   */
  private void renderOpaqueInternal(Gl46Viewport<?> viewport, boolean service, boolean portal) {
    if (viewport == null) {
      return;
    }
    if (viewport.width <= 0 || viewport.height <= 0) {
      return; // Only render on valid viewport
    }

    if (service) {
      TimingStatistics.resetSamplers();
      TimingStatistics.all.start();
    }
    GPUTiming.INSTANCE.marker(); // Start marker
    if (service) {
      TimingStatistics.main.start();
    }

    // TODO: optimize
    int[] oldBufferBindings = new int[10];
    for (int i = 0; i < oldBufferBindings.length; i++) {
      oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
    }

    int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    int boundFB = oldFB;

    int[] dims = new int[4];
    glGetIntegerv(GL_VIEWPORT, dims);

    glViewport(0, 0, viewport.width, viewport.height);

    if (boundFB == 0) {
      throw new IllegalStateException(
          "Cannot use the default framebuffer as cannot source from it");
    }

    // The host may keep a scissor rect for the portal shape; the internal pipeline passes assume a
    // full-target viewport, so disable it for the draw and restore the host state afterwards. This
    // applies to every portal pass, including a serviced foreign one; the main path keeps its
    // historical behavior (no capture).
    PortalGlState hostState = portal ? PortalGlState.capture() : null;
    if (hostState != null) {
      glDisable(GL_SCISSOR_TEST);
    }

    this.pipeline.preSetup(viewport);

    if (service) {
      TimingStatistics.E.start();
    }
    if ((!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
      // A portal pass must not mutate the chunk-bound queues that the main frame owns (§4.1).
      this.chunkBoundRenderer.renderTo(viewport, service);
    } else {
      viewport.depthBoundingBuffer.clear(0);
    }
    if (service) {
      TimingStatistics.E.stop();
    }

    GPUTiming.INSTANCE.marker();
    // The entire rendering pipeline (excluding the chunkbound thing)
    this.pipeline.runPipeline(viewport, boundFB, dims[2], dims[3]);
    GPUTiming.INSTANCE.marker();

    if (service) {
      TimingStatistics.main.stop();
      TimingStatistics.postDynamic.start();

      PrintfDebugUtil.tick();

      // As much dynamic runtime stuff here
      {
        // Tick upload stream (this is ok to do here as upload ticking is just memory management)
        UploadStream.INSTANCE.tick();

        while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)
            && VoxyClient.isFrexActive())
          ; // While FF is active, run until everything is processed
        TimingStatistics.H.start();
        // Done here as is allows less gl state resetup
        do {
          this.modelService.tick(900_000);
        } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
        TimingStatistics.H.stop();
      }
      GPUTiming.INSTANCE.marker();
      TimingStatistics.postDynamic.stop();

      GPUTiming.INSTANCE.tick();
    }

    glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
    glViewport(dims[0], dims[1], dims[2], dims[3]);

    { // Reset state manager stuffs
      glUseProgram(0);
      glEnable(GL_DEPTH_TEST);
      glDisable(GL_STENCIL_TEST);

      GlStateManager._glBindVertexArray(0); // Clear binding

      GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
      for (int i = 0; i < 12; i++) {
        GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
        GlStateManager._bindTexture(0);
        glBindSampler(i, 0);
      }

      IrisUtil.clearIrisSamplers(); // Thanks iris (sigh)

      // TODO: should/needto actually restore all of these, not just clear them
      // Clear all the bindings
      for (int i = 0; i < oldBufferBindings.length; i++) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
      }
    }

    if (hostState != null) {
      hostState.restore();
    }

    if (service) {
      TimingStatistics.all.stop();
    }
  }

  public static float getRenderDistance() {
    return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
  }

  private static Matrix4f computeProjectionMat(Matrix4fc base) {

    var proj = new Matrix4f(base);

    float near = getRenderDistance() <= 32.0f ? 8f : 16f;
    near = VoxyClient.disableSodiumChunkRender() ? 0.1f : near;

    float far = 16 * 3000;

    return proj.m22((far + near) / (near - far)).m32((far + far) * near / (near - far));
  }

  private boolean frexStillHasWork() {
    if (!VoxyClient.isFrexActive()) {
      return false;
    }
    // If frex is running we must tick everything to ensure correctness
    UploadStream.INSTANCE.tick();
    // Done here as is allows less gl state resetup
    this.modelService.tick(100_000_000);
    GL11.glFinish();
    return this.nodeManager.hasWork()
        || this.renderGen.getTaskCount() != 0
        || !this.modelService.areQueuesEmpty();
  }

  @Override
  public void setRenderDistance(float renderDistance) {
    this.renderDistanceTracker.setRenderDistance(
        (int)
            Math.ceil(
                renderDistance
                    + 1)); // the +1 is to cover the outer ring of chunks when rendering a circle
  }

  private Gl46Viewport<?> getViewport() {
    if (IrisUtil.irisShadowActive()) {
      return null;
    }
    return this.viewportSelector.getViewport();
  }

  @Override
  public int getSableOcclusionDepthTexture() {
    return this.pipeline.getSableOcclusionDepthTexture();
  }

  @Override
  public Viewport<?> getSableOcclusionViewport() {
    return this.getViewport();
  }

  @Override
  public RenderFrameMatrices getLastFrameMatrices() {
    return this.lastFrameMatrices;
  }

  @Override
  public void onChunkTrackerReset() {
    this.chunkBoundRenderer.reset();
  }

  @Override
  public void onSectionRenderStateChanged(long sectionPos, boolean present) {
    if (present) {
      this.chunkBoundRenderer.addSection(sectionPos);
    } else {
      this.chunkBoundRenderer.removeSection(sectionPos);
    }
  }

  @Override
  public void onSectionDisposed(long sectionPos) {
    this.chunkBoundRenderer.removeSectionImmediate(sectionPos);
  }

  @Override
  public void addDebugInfo(List<String> debug) {
    debug.add("Voxy backend: GL46");
    debug.add(
        "Buf/Tex [#/Mb]: ["
            + GlBuffer.getCount()
            + "/"
            + (GlBuffer.getTotalSize() / 1_000_000)
            + "],["
            + GlTexture.getCount()
            + "/"
            + (GlTexture.getEstimatedTotalSize() / 1_000_000)
            + "]");
    {
      this.modelService.addDebugData(debug);
      this.renderGen.addDebugData(debug);
      this.nodeManager.addDebug(debug);
      this.pipeline.addDebug(debug);
    }
    {
      TimingStatistics.update();
      debug.add(
          "Voxy frame runtime (millis): "
              + TimingStatistics.dynamic.pVal()
              + ", "
              + TimingStatistics.main.pVal()
              + ", "
              + TimingStatistics.postDynamic.pVal()
              + ", "
              + TimingStatistics.all.pVal());
      debug.add(
          "Extra time: "
              + TimingStatistics.A.pVal()
              + ", "
              + TimingStatistics.B.pVal()
              + ", "
              + TimingStatistics.C.pVal()
              + ", "
              + TimingStatistics.D.pVal());
      debug.add(
          "Extra 2 time: "
              + TimingStatistics.E.pVal()
              + ", "
              + TimingStatistics.F.pVal()
              + ", "
              + TimingStatistics.G.pVal()
              + ", "
              + TimingStatistics.H.pVal()
              + ", "
              + TimingStatistics.I.pVal());
    }
    debug.add(GPUTiming.INSTANCE.getDebug());
    PrintfDebugUtil.addToOut(debug);
  }

  @Override
  public void close() {
    Logger.info("Flushing download stream");
    DownloadStream.INSTANCE.flushWaitClear();
    Logger.info("Shutting down rendering");
    try {
      // Cleanup callbacks
      this.worldIn.setDirtyCallback(null);
      this.worldIn.getMapper().setBiomeCallback(null);
      this.worldIn.getMapper().setStateCallback(null);

      this.nodeManager.stop();

      this.modelService.shutdown();
      this.renderGen.shutdown();
      this.traversal.free();
      this.nodeCleaner.free();
      this.geometryData.free();
      if (((BasicSectionGeometryData) this.geometryData).isExternalGeometryBuffer) {
        RenderResourceReuse.giveBackGeometryBuffer(
            ((BasicSectionGeometryData) this.geometryData).getGeometryBuffer());
      }

      this.chunkBoundRenderer.free();

      // Release portal viewports (HiZ + MDIC buffers) before the shared selector; an open pass at
      // shutdown is dropped without touching GL state (§7.5).
      this.portalViews.clear();
      for (Gl46Viewport<?> viewport : this.portalViewports) {
        viewport.delete();
      }
      this.portalViewports.clear();

      this.viewportSelector.free();
    } catch (Exception e) {
      Logger.error("Error shutting down renderer components", e);
    }
    Logger.info("Shutting down render pipeline");
    try {
      this.pipeline.free();
    } catch (Exception e) {
      Logger.error("Error releasing render pipeline", e);
    }

    Logger.info("Flushing download stream");
    DownloadStream.INSTANCE.flushWaitClear();
  }

  /** One open portal pass; its viewport is pooled per depth and reused across passes (§4.2). */
  private static final class Gl46PortalView {
    private final Gl46Viewport<?> viewport;
    private final PortalViewOptions options;
    // D3: true when this pass belongs to a foreign system and may run the once-per-frame service
    // work (uploads/models/recenter) during its OPAQUE draw.
    private final boolean service;
    private boolean supported = true;

    private Gl46PortalView(Gl46Viewport<?> viewport, PortalViewOptions options, boolean service) {
      this.viewport = viewport;
      this.options = options;
      this.service = service;
    }
  }

  /**
   * Host-owned GL state a portal draw must not leave altered (§4.4): stencil, scissor and
   * (defensively) the clip-distance enables. Voxy never enables clip distances itself.
   */
  private static final class PortalGlState {
    private final boolean stencilTest;
    private final int stencilFunc;
    private final int stencilRef;
    private final int stencilValueMask;
    private final int stencilWriteMask;
    private final int stencilFail;
    private final int stencilDepthFail;
    private final int stencilDepthPass;
    private final boolean scissorTest;
    private final int[] scissorBox = new int[4];
    private final int clipDistanceMask;

    private PortalGlState() {
      this.stencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
      this.stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
      this.stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
      this.stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
      this.stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
      this.stencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
      this.stencilDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
      this.stencilDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);

      this.scissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, this.scissorBox);

      int clipDistances = 0;
      for (int i = 0; i < 8; i++) {
        if (GL11.glIsEnabled(GL_CLIP_DISTANCE0 + i)) {
          clipDistances |= 1 << i;
        }
      }
      this.clipDistanceMask = clipDistances;
    }

    private static PortalGlState capture() {
      return new PortalGlState();
    }

    private void restore() {
      if (this.stencilTest) {
        glEnable(GL_STENCIL_TEST);
      } else {
        glDisable(GL_STENCIL_TEST);
      }
      GL11.glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilValueMask);
      GL11.glStencilMask(this.stencilWriteMask);
      GL11.glStencilOp(this.stencilFail, this.stencilDepthFail, this.stencilDepthPass);

      if (this.scissorTest) {
        glEnable(GL_SCISSOR_TEST);
      } else {
        glDisable(GL_SCISSOR_TEST);
      }
      GL11.glScissor(
          this.scissorBox[0], this.scissorBox[1], this.scissorBox[2], this.scissorBox[3]);

      for (int i = 0; i < 8; i++) {
        int clipDistance = GL_CLIP_DISTANCE0 + i;
        if ((this.clipDistanceMask & (1 << i)) != 0) {
          glEnable(clipDistance);
        } else {
          glDisable(clipDistance);
        }
      }
    }
  }
}
