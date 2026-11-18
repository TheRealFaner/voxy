package me.cortex.voxy.client.core.rendering.backend;

import java.util.List;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;

public interface VoxyRenderBackend extends AutoCloseable {
  RenderBackendId id();

  /**
   * Diagnostic accessor: the model factory this backend bakes block models into, or {@code null}
   * when the backend does not own one. Used by {@code /voxy dump} and the LOD A/B switches only.
   */
  default ModelFactory getModelFactory() {
    return null;
  }

  boolean rendersLodTerrain();

  /**
   * Advance this backend through a host frame stage. Backends ignore stages they do not participate
   * in by returning {@code frame} unchanged.
   */
  RenderFrame runFrameStage(RenderStage stage, RenderStageContext context, RenderFrame frame);

  RenderFrame setupFrame(RenderFrameContext context);

  void renderOpaque(RenderFrame frame);

  void setRenderDistance(float renderDistance);

  void addDebugInfo(List<String> debug);

  RenderFrameMatrices getLastFrameMatrices();

  /**
   * GL texture id of this backend's opaque Voxy distant-terrain depth target for the most recent
   * frame, or 0 if it does not own one. The depth-component texture yields normalised [0,1] depth
   * from {@code texelFetch(...).r}; Iris shader packs sample it as {@code vxDepthTexOpaque}.
   * Backends that render into the Iris-managed pipeline framebuffer keep the default 0 and let the
   * caller fall back to the Iris depth target.
   */
  default int voxyDistantOpaqueDepthTextureId() {
    return 0;
  }

  /**
   * Translucent counterpart of {@link #voxyDistantOpaqueDepthTextureId()}, sampled by Iris packs as
   * {@code vxDepthTexTrans}.
   */
  default int voxyDistantTranslucentDepthTextureId() {
    return 0;
  }

  /**
   * GL texture id of the depth target that holds Voxy's LOD depth when it is NOT written to the
   * vanilla depth buffer (Iris pipeline with a private depth target), used by the Sable
   * compatibility depth shim to occlude contraptions against distant terrain. 0 when the backend
   * writes depth to the vanilla buffer already (no shim needed) or does not support the shim.
   */
  default int getSableOcclusionDepthTexture() {
    return 0;
  }

  /**
   * The viewport that produced {@link #getSableOcclusionDepthTexture()} (needed for its MVP and
   * dimensions), or null when the shim is unsupported.
   */
  default Viewport<?> getSableOcclusionViewport() {
    return null;
  }

  void onChunkTrackerReset();

  void onSectionRenderStateChanged(long sectionPos, boolean present);

  /**
   * A Sodium render section was disposed (chunk unloaded / left the render distance), as opposed to
   * the transient build-state change reported by {@link #onSectionRenderStateChanged}. Fired from
   * the {@code RenderSectionManager.onSectionRemoved} HEAD injection, before Sodium deletes the
   * section. Backends that delay chunk-bound removals (to let the LOD build catch up on rebuilds)
   * must remove the section immediately here: the LOD for the disposed area is already prepared, so
   * the delay window would only leave a see-through gap. Default no-op.
   */
  default void onSectionDisposed(long sectionPos) {}

  default void beginVanillaRenderSectionSync() {}

  default void syncVanillaRenderSections(ChunkRenderListIterable renderLists, boolean reverse) {}

  /**
   * True when this backend can render LOD during portal passes (PORTAL_VIEW_API.md D2). GL46
   * supports it in S2; GL41Metal remains a pure suppression scope until S3. Backends must return
   * false after their per-session failure latch.
   */
  default boolean supportsPortalViews() {
    return false;
  }

  /** Maximum simultaneous (nested) portal passes this backend can render; 0 when unsupported. */
  default int portalViewDepthLimit() {
    return 0;
  }

  /**
   * Opens a portal pass. Render thread only. The caller MUST call {@link #endPortalView()} exactly
   * once for every call, including when this returns false. Returns true when this backend will
   * render LOD for the pass; {@link me.cortex.voxy.client.core.VoxyRenderSystem} opens its
   * suppression scope regardless of the result.
   */
  default boolean beginPortalView(PortalViewOptions options) {
    return false;
  }

  /**
   * Variant of {@link #beginPortalView(PortalViewOptions)} that also states whether this pass may
   * run the once-per-host-frame foreign-system service work (uploads, model ticks, LOD recenter;
   * PORTAL_VIEW_API.md §7.4). Home-system passes must pass {@code false}: the main OPAQUE already
   * serviced that system. Backends that do not service portal passes can keep the one-argument
   * overload.
   */
  default boolean beginPortalView(PortalViewOptions options, boolean service) {
    return this.beginPortalView(options);
  }

  /** Closes the innermost portal pass. Idempotent when no pass is open. */
  default void endPortalView() {}

  /** True while a backend portal pass is open (supported or not). */
  default boolean isPortalViewActive() {
    return false;
  }

  @Override
  void close();
}
