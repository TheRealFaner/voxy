package me.cortex.voxy.client.core.model;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Drops the Dynamic Trees quads that Voxy cannot place correctly with one depth per face.
 *
 * <p>For surface roots ({@code dropBoundaryCaps == false}) two classes of quads are removed:
 *
 * <ul>
 *   <li>HIGH "vert" columns running from {@code y=2c} up to {@code y=16+2c}. Clipped at the cell
 *       top, they made the whole UP face sit flush with {@code y=16}: on sloped ground every rooted
 *       cell became a one-block-high bark plateau instead of a low root. They belong to the cell
 *       above anyway.
 *   <li>Sleeve end caps that lie exactly on a boundary plane of the cell. They are internal faces
 *       in vanilla (the neighbouring sleeve continues across the boundary), but in Voxy they are
 *       the nearest plane on that side view and drag the whole side face out to the cell edge,
 *       turning every connected root into a vertical post.
 * </ul>
 *
 * <p>With boundary-cap dropping enabled (Dynamic Trees branch variants) the filter also drops the
 * small twig end caps: quads lying exactly in one cell-boundary plane ({@code x}, {@code y} or
 * {@code z} = 0 or 16) whose two in-plane extents are both under 8 model units. A connected
 * neighbouring cell covers that plane in vanilla, but Voxy's per-face depth would drag the face
 * forward into a visible fin/sliver. Large boundary faces (for example a thick trunk's 16x16 top)
 * are kept.
 *
 * <p>Dynamic Trees bakes branch models in a 0..1 vertex space where a full block face spans 1.0,
 * while Voxy's synthetic models (for example {@link SolidBarkBakedModel}) use the usual 0..16
 * space. The filter detects the space per quad and scales 0..1 coordinates to block units before
 * the plane and extent tests, so the plane at 1.0 and a full 16x16 DT face are handled like their
 * 0..16 counterparts, and a one-unit twig cap still measures 1 and is dropped.
 *
 * <p>The caps are dropped per side: {@code dropCapSidesMask} bit {@code i} (where {@code i} is
 * {@link Direction#get3DDataValue()}) means that side's boundary cap may be dropped. {@link
 * #ALL_SIDES_MASK} enables every side (the default {@code all} branch-cap-filter mode); the {@code
 * per-side} mode sets only sides whose neighbour is a connected branch. Plane {@code x=0} maps to
 * WEST(4), {@code x=16} to EAST(5), {@code y=0} to DOWN(0), {@code y=16} to UP(1), {@code z=0} to
 * NORTH(2) and {@code z=16} to SOUTH(3). Air neighbours keep their caps, so a trunk top or an
 * exposed side stays closed.
 */
final class RootVertFilterBakedModel implements BakedModel {
  static final int ALL_SIDES_MASK = 0x3F;

  /** Largest absolute coordinate of a Dynamic Trees quad in its native 0..1 model space. */
  private static final float UNIT_SPACE_MAX = 1.0005f;

  /** Block-unit size of that 0..1 model cube: one DT unit is one sixteenth of a block. */
  private static final float UNIT_SPACE_SCALE = 16.0f;

  private final BakedModel delegate;
  private final float maxY;
  private final int dropCapSidesMask;

  RootVertFilterBakedModel(BakedModel delegate, float maxY, boolean dropBoundaryCaps) {
    this(delegate, maxY, dropBoundaryCaps ? ALL_SIDES_MASK : 0);
  }

  RootVertFilterBakedModel(BakedModel delegate, float maxY, int dropCapSidesMask) {
    this.delegate = delegate;
    this.maxY = maxY;
    this.dropCapSidesMask = dropCapSidesMask;
  }

  BakedModel delegate() {
    return this.delegate;
  }

  List<BakedQuad> filter(List<BakedQuad> quads) {
    if (quads == null || quads.isEmpty()) {
      return quads == null ? List.of() : quads;
    }
    List<BakedQuad> kept = new ArrayList<>(quads.size());
    for (BakedQuad quad : quads) {
      if (withinHeight(quad, this.maxY) && !isBoundaryCap(quad, this.dropCapSidesMask)) {
        kept.add(quad);
      }
    }
    return kept;
  }

  /**
   * True for a small quad lying exactly in one cell-boundary plane ({@code x}, {@code y} or {@code
   * z} = 0 or 16) on a side whose mask bit is set. The two in-plane extents must be small so large
   * legitimate boundary faces (for example a thick trunk's 16x16 top) are kept. Plane-to-side
   * mapping: x=0 → WEST, x=16 → EAST, y=0 → DOWN, y=16 → UP, z=0 → NORTH, z=16 → SOUTH.
   *
   * <p>Coordinates are first converted to block units with {@link #vertexSpaceScale(int[])}: DT
   * branch quads span 0..1, so their plane 1.0 becomes 16 and a full face measures 16 (kept), while
   * a one-unit twig cap measures 1 (dropped). Voxy synthetic 0..16 quads keep scale 1.
   */
  static boolean isBoundaryCap(BakedQuad quad, int dropCapSidesMask) {
    if (dropCapSidesMask == 0) {
      return false;
    }
    int[] vertices = quad.getVertices();
    float scale = vertexSpaceScale(vertices);
    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float minZ = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float maxZ = Float.NEGATIVE_INFINITY;
    for (int i = 0; i < 4; i++) {
      float x = Float.intBitsToFloat(vertices[i * 8]) * scale;
      float y = Float.intBitsToFloat(vertices[i * 8 + 1]) * scale;
      float z = Float.intBitsToFloat(vertices[i * 8 + 2]) * scale;
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
      minZ = Math.min(minZ, z);
      maxZ = Math.max(maxZ, z);
    }
    int side;
    if (near(minX, maxX) && near(minX, 0.0f)) {
      side = Direction.WEST.get3DDataValue();
    } else if (near(minX, maxX) && near(minX, 16.0f)) {
      side = Direction.EAST.get3DDataValue();
    } else if (near(minY, maxY) && near(minY, 0.0f)) {
      side = Direction.DOWN.get3DDataValue();
    } else if (near(minY, maxY) && near(minY, 16.0f)) {
      side = Direction.UP.get3DDataValue();
    } else if (near(minZ, maxZ) && near(minZ, 0.0f)) {
      side = Direction.NORTH.get3DDataValue();
    } else if (near(minZ, maxZ) && near(minZ, 16.0f)) {
      side = Direction.SOUTH.get3DDataValue();
    } else {
      return false;
    }
    if ((dropCapSidesMask & (1 << side)) == 0) {
      return false;
    }
    float extentA;
    float extentB;
    switch (side) {
      case 4, 5 -> { // WEST / EAST: yz plane
        extentA = maxY - minY;
        extentB = maxZ - minZ;
      }
      case 0, 1 -> { // DOWN / UP: xz plane
        extentA = maxX - minX;
        extentB = maxZ - minZ;
      }
      default -> { // NORTH / SOUTH: xy plane
        extentA = maxX - minX;
        extentB = maxY - minY;
      }
    }
    return extentA < 7.9f && extentB < 7.9f;
  }

  private static boolean near(float value, float target) {
    return Math.abs(value - target) < 1.0e-3f;
  }

  /**
   * Block-unit scale factor for the quad's vertex space.
   *
   * <p>Dynamic Trees branch models emit quads in a 0..1 space (a full block face spans 1.0);
   * vanilla-baked and Voxy synthetic models use 0..16. A quad whose every coordinate fits inside
   * the unit cube is treated as DT and scaled by {@link #UNIT_SPACE_SCALE}; anything larger keeps
   * scale 1, so 0..16 quads stay bit-identical to the previous untransformed behaviour.
   */
  private static float vertexSpaceScale(int[] vertices) {
    float maxAbs = 0.0f;
    for (int i = 0; i < 4; i++) {
      int base = i * 8;
      maxAbs = Math.max(maxAbs, Math.abs(Float.intBitsToFloat(vertices[base])));
      maxAbs = Math.max(maxAbs, Math.abs(Float.intBitsToFloat(vertices[base + 1])));
      maxAbs = Math.max(maxAbs, Math.abs(Float.intBitsToFloat(vertices[base + 2])));
    }
    return maxAbs <= UNIT_SPACE_MAX ? UNIT_SPACE_SCALE : 1.0f;
  }

  /**
   * True when no vertex is above the cap height {@code maxY}, expressed in block units. The
   * comparison is scale-aware like {@link #isBoundaryCap}: DT 0..1 quads are scaled by 16 first, so
   * the limit keeps its block-unit meaning for both vertex spaces.
   */
  static boolean withinHeight(BakedQuad quad, float maxY) {
    int[] vertices = quad.getVertices();
    float scale = vertexSpaceScale(vertices);
    for (int i = 0; i < 4; i++) {
      float y = Float.intBitsToFloat(vertices[i * 8 + 1]) * scale;
      if (y > maxY) {
        return false;
      }
    }
    return true;
  }

  @Override
  public List<BakedQuad> getQuads(
      @Nullable BlockState state, @Nullable Direction side, RandomSource random) {
    return this.filter(this.delegate.getQuads(state, side, random));
  }

  @Override
  public boolean useAmbientOcclusion() {
    return this.delegate.useAmbientOcclusion();
  }

  @Override
  public boolean isGui3d() {
    return this.delegate.isGui3d();
  }

  @Override
  public boolean usesBlockLight() {
    return this.delegate.usesBlockLight();
  }

  @Override
  public boolean isCustomRenderer() {
    return this.delegate.isCustomRenderer();
  }

  @Override
  public TextureAtlasSprite getParticleIcon() {
    return this.delegate.getParticleIcon();
  }

  @Override
  public ItemOverrides getOverrides() {
    return this.delegate.getOverrides();
  }

  @Override
  public ItemTransforms getTransforms() {
    return this.delegate.getTransforms();
  }
}
