package me.cortex.voxy.client.core.rendering.backend;

/**
 * World-space clip plane supplied by a portal adapter (see PORTAL_VIEW_API.md D4 and Appendix A):
 * the {@code (nx, ny, nz)} normal and {@code (px, py, pz)} point define the plane that keeps points
 * where {@code dot(n, p - point) >= 0}.
 *
 * <p>S2 consumes the plane on GL46 as traversal culling only (the GL46 backend writes it into the
 * otherwise-unused {@code frustumPlanes[5]}); exact fragment clipping (oblique projection) is S4
 * and stays off. GL41Metal does not consume it yet (S3).
 */
public record PortalClipPlane(double nx, double ny, double nz, double px, double py, double pz) {
  private static final double MIN_NORMAL_LENGTH_SQUARED = 1e-9;

  /** True when every component is finite and the normal is non-degenerate. */
  public boolean isValid() {
    if (!Double.isFinite(this.nx)
        || !Double.isFinite(this.ny)
        || !Double.isFinite(this.nz)
        || !Double.isFinite(this.px)
        || !Double.isFinite(this.py)
        || !Double.isFinite(this.pz)) {
      return false;
    }
    return this.nx * this.nx + this.ny * this.ny + this.nz * this.nz > MIN_NORMAL_LENGTH_SQUARED;
  }
}
