package me.cortex.voxy.client.core.rendering.backend;

/**
 * Parameters known before the first stage call of a portal pass (PORTAL_VIEW_API.md §3.1). All
 * values are copied into this immutable record; the adapter may discard its instance after {@link
 * me.cortex.voxy.client.core.VoxyRenderSystem#beginPortalView(PortalViewOptions)} returns.
 *
 * @param clipPlane world-space clip plane, or {@code null} when the adapter has none
 * @param stencilRef host stencil reference for the shared-target mode, {@code -1} for no gate
 * @param stencilMask host stencil mask, {@code 0} for no gate
 * @param hostFrameId adapter frame counter ({@code 0} = unknown), used for once-per-frame servicing
 * @param lodFovScaleOverride LOD traversal FOV scale override, {@code <= 0} = auto
 */
public record PortalViewOptions(
    PortalClipPlane clipPlane,
    int stencilRef,
    int stencilMask,
    long hostFrameId,
    float lodFovScaleOverride) {

  public boolean hasClipPlane() {
    return this.clipPlane != null && this.clipPlane.isValid();
  }

  public boolean hasStencilGate() {
    return this.stencilRef >= 0 && this.stencilMask != 0;
  }

  public static PortalViewOptions disabled() {
    return new PortalViewOptions(null, -1, 0, 0, 0f);
  }
}
