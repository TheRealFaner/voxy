package me.cortex.voxy.client.core.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import me.cortex.voxy.common.Logger;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Root blocks captured while Voxy meshes LOD0 sections, for the true-geometry overlay pass.
 *
 * <p>The impostor model format can only ship six face cards per cell, so Dynamic Trees roots
 * rendered through it always collapse to flat cards. This cache keeps the exact block state, the
 * 3x3x3 connection cube and the packed light for every root cell; a client render event then draws
 * the real DT model with the vanilla block renderer, outside the loaded-chunk area where Voxy is
 * the only renderer. Values are immutable snapshots keyed by {@code SectionPos.asLong(sx, sy, sz)}.
 */
public final class DTRootGeometryCache {
  /**
   * Master switch for Dynamic Trees root rendering in Voxy: LOD0 root variants/impostors, the
   * true-geometry overlay, {@code RootCrownKey} requests/bakes and root-cell collection. Disabled
   * by default; all code is retained for future re-enable with the system property {@code
   * voxy.dtRootsEnabled=true}.
   */
  public static final boolean ROOTS_ENABLED =
      Boolean.parseBoolean(System.getProperty("voxy.dtRootsEnabled", "false"));

  /**
   * Overlay sub-switch; only effective when {@link #ROOTS_ENABLED}. Enabled by default; {@code
   * -Dvoxy.dtRootOverlay=false} falls back to the impostor path.
   */
  public static final boolean ENABLED =
      !"false".equalsIgnoreCase(System.getProperty("voxy.dtRootOverlay", "true"));

  private static final AtomicBoolean CONFIG_LOGGED = new AtomicBoolean();

  static {
    // One-shot master-switch state: NeoForge marks this cache active during client init, other
    // loaders log on the first meshing access.
    if (CONFIG_LOGGED.compareAndSet(false, true)) {
      if (ROOTS_ENABLED) {
        Logger.info("DT roots in Voxy ENABLED");
      } else {
        Logger.info(
            "DT roots in Voxy DISABLED (voxy.dtRootsEnabled=false; code retained for future)");
      }
    }
  }

  private static final int MAX_SECTIONS = 8192;
  private static final ConcurrentHashMap<Long, List<RootBlock>> SECTIONS =
      new ConcurrentHashMap<>();

  /** Set by the loader that actually installs the overlay renderer (NeoForge). */
  private static volatile boolean active = false;

  private DTRootGeometryCache() {}

  public static void setActive(boolean value) {
    active = value;
  }

  /**
   * True only when the master switch is on, the overlay sub-switch is on AND the loader registered
   * its renderer.
   */
  public static boolean isActive() {
    return ROOTS_ENABLED && ENABLED && active;
  }

  /**
   * One root cell. {@code x/y/z} are world block coordinates, {@code cube} is the 27-state
   * neighbourhood in Voxy's (x+1)+3*(y+1)+9*(z+1) order and {@code light} is Mapper's packed
   * block/sky light nibbles.
   */
  public record RootBlock(int x, int y, int z, BlockState state, BlockState[] cube, int light) {}

  public static void putSection(long sectionKey, List<RootBlock> roots) {
    if (!ROOTS_ENABLED) {
      // Master switch off: never collect root cells; the LOD0 resolver suppresses them instead.
      return;
    }
    if (roots.isEmpty()) {
      SECTIONS.remove(sectionKey);
      return;
    }
    if (SECTIONS.size() > MAX_SECTIONS) {
      // Bounded cache: a session that explores a huge world must not grow without limit.
      SECTIONS.clear();
    }
    SECTIONS.put(sectionKey, List.copyOf(roots));
  }

  public static Map<Long, List<RootBlock>> sections() {
    return SECTIONS;
  }

  public static boolean isEmpty() {
    return SECTIONS.isEmpty();
  }

  public static void clear() {
    SECTIONS.clear();
  }
}
