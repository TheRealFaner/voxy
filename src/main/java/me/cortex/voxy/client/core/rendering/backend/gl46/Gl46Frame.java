package me.cortex.voxy.client.core.rendering.backend.gl46;

import me.cortex.voxy.client.core.rendering.backend.RenderBackendId;
import me.cortex.voxy.client.core.rendering.backend.RenderFrame;

// Only carries the viewport: the gl46 render pass reads the bound framebuffer and GL viewport
// directly at render time (matching the pre-abstraction VoxyRenderSystem behaviour) instead of
// using values captured at frame-setup time, which under Iris happens in beginLevelRendering with
// a different framebuffer bound.
//
// The owner is the backend (and therefore the VoxyRenderSystem) that created the frame. Nested
// Immersive Portals renders can resolve a different renderer between stages; advancing a frame
// that belongs to another system would draw that system's viewport into this host's target
// (PORTAL_VIEW_API.md F3). Gl46RenderBackend rejects frames it does not own (D2).
public record Gl46Frame(Gl46RenderBackend owner, Gl46Viewport<?> viewport) implements RenderFrame {
  @Override
  public RenderBackendId backendId() {
    return RenderBackendId.GL46;
  }
}
