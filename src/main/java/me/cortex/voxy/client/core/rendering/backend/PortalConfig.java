package me.cortex.voxy.client.core.rendering.backend;

/**
 * System-property configuration for the portal view API (PORTAL_VIEW_API.md §3.8 and §11), matching
 * the existing backend-config style ({@code gl41metal/Config}).
 *
 * <ul>
 *   <li>{@code voxy.portal.enable} (default {@code true}) — master switch for portal LOD support.
 *       Portal LOD itself arrives in S2/S3; S1 ships only the suppression scope, so with either
 *       value the observable behavior is the same S1 behavior. The scope is never disabled by this
 *       switch: D1 requires every pass to open it so nested portal renders cannot corrupt the main
 *       frame.
 *   <li>{@code voxy.portal.backend} (default all) — backend opt-out: {@code none} disables portal
 *       support everywhere, {@code gl46} / {@code gl41metal} restrict it to that backend.
 *   <li>{@code voxy.portal.maxDepth} (default {@code 2}) — bound on nested pass scopes (D8).
 *   <li>{@code voxy.portal.foreignDimensions} (default {@code false}) — allow LOD for a portal pass
 *       whose destination is not the client player's dimension (PORTAL_VIEW_API.md D7/§3.8). The
 *       scope is still opened when this is false; the pass simply renders no LOD.
 *   <li>{@code voxy.portal.serviceForeign} (default {@code true}) — allow once-per-host-frame
 *       servicing (uploads, model ticks, LOD recenter) of a foreign {@code VoxyRenderSystem} during
 *       its portal pass (PORTAL_VIEW_API.md §7.4). Only reachable when {@link #foreignDimensions()}
 *       is enabled and the pass target is supported.
 *   <li>{@code voxy.portal.suppressForeignMain} (default {@code true}) — cross-dimension isolation
 *       rollback switch (D1): a non-home system whose dimension differs from the client player's
 *       level renders no main-style stages at all while no portal scope is open. Set to {@code
 *       false} to restore the pre-isolation behavior.
 * </ul>
 */
public record PortalConfig(
    boolean enabled,
    String backendFilter,
    int maxDepth,
    boolean foreignDimensions,
    boolean serviceForeign,
    boolean suppressForeignMain) {
  public static PortalConfig fromSystemProperties() {
    String backendFilter = System.getProperty("voxy.portal.backend", "").trim();
    return new PortalConfig(
        readBoolean("voxy.portal.enable", true),
        backendFilter,
        readInt("voxy.portal.maxDepth", 2, 1, 8),
        readBoolean("voxy.portal.foreignDimensions", false),
        readBoolean("voxy.portal.serviceForeign", true),
        readBoolean("voxy.portal.suppressForeignMain", true));
  }

  /** True when portal LOD may be attempted on this backend (the scope itself is always allowed). */
  public boolean allowsBackend(RenderBackendId id) {
    if (!this.enabled) {
      return false;
    }
    if (this.backendFilter.isEmpty() || this.backendFilter.equalsIgnoreCase("all")) {
      return true;
    }
    if (this.backendFilter.equalsIgnoreCase("none")) {
      return false;
    }
    return this.backendFilter.equalsIgnoreCase(id.name())
        || this.backendFilter.equalsIgnoreCase(id.name().replace("_", ""));
  }

  private static boolean readBoolean(String property, boolean fallback) {
    String value = System.getProperty(property);
    return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
  }

  private static int readInt(String property, int fallback, int min, int max) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
