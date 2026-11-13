package me.cortex.voxy.client.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Synthetic low "root pad" for a Dynamic Trees surface-root cell.
 *
 * <p>Voxy stores exactly one depth (face indentation) per 16x16 face, while DT's own root model
 * puts a core, up to four sleeves and HIGH vert columns at several depths into the same face. Any
 * bake of that model therefore has to collapse those planes and looks like flat bark plates.
 *
 * <p>This model is designed for the format instead: one flat-topped prism of height {@code 2r}
 * covering the union of the core and the connected sleeves. Each face direction sees a single plane
 * by construction:
 *
 * <ul>
 *   <li>UP: one plane at {@code y=2r} with the full spread silhouette (core + sleeves);
 *   <li>sides: the core wall at {@code 8-r} (the sleeve sides at {@code 8±c} are never nearer,
 *       because the key clamps {@code c} to {@code r});
 *   <li>arm ends on the cell boundary are not emitted: in vanilla the neighbouring arm continues
 *       there, and under MIN a boundary cap would drag the whole side face to the cell edge. The
 *       core wall behind the opening closes the view.
 * </ul>
 *
 * <p>UVs are absolute within the cell, so the bark texture stays aligned across neighbouring root
 * cells instead of restarting at every block.
 */
final class SyntheticRootPadBakedModel implements BakedModel {
  private final TextureAtlasSprite sprite;
  private final Map<Direction, List<BakedQuad>> faces = new EnumMap<>(Direction.class);

  SyntheticRootPadBakedModel(
      TextureAtlasSprite sprite, int radius, int north, int east, int south, int west) {
    this.sprite = sprite;
    int r = Math.clamp(radius, 1, 8);
    float h = 2.0f * r;
    // Core box: top and the four walls. The bottom is hidden by the ground, so it is not emitted.
    this.addBox(8 - r, 0, 8 - r, 8 + r, h, 8 + r, true, false, true, true, true, true);
    if (r < 8) {
      if (north > 0) {
        int c = Math.clamp(north, 1, r);
        // Arms stop at the cell boundary. The boundary end cap is internal in vanilla (the
        // neighbouring arm continues there) and emitting it would drag the side face to the
        // boundary under MIN. The core wall behind the opening closes the view.
        this.addBox(8 - c, 0, 0, 8 + c, h, 8 - r, true, false, false, false, true, true);
      }
      if (south > 0) {
        int c = Math.clamp(south, 1, r);
        this.addBox(8 - c, 0, 8 + r, 8 + c, h, 16, true, false, false, false, true, true);
      }
      if (west > 0) {
        int c = Math.clamp(west, 1, r);
        this.addBox(0, 0, 8 - c, 8 - r, h, 8 + c, true, false, true, true, false, false);
      }
      if (east > 0) {
        int c = Math.clamp(east, 1, r);
        this.addBox(8 + r, 0, 8 - c, 16, h, 8 + c, true, false, true, true, false, false);
      }
    }
  }

  /**
   * Emits the requested faces of one box. {@code down} is always false (the pad stands on the
   * ground) and box ends that lie on the cell boundary are skipped via the per-face flags.
   */
  private void addBox(
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      boolean up,
      boolean down,
      boolean north,
      boolean south,
      boolean west,
      boolean east) {
    FaceBakery bakery = new FaceBakery();
    Vector3f from = new Vector3f(x1, y1, z1);
    Vector3f to = new Vector3f(x2, y2, z2);
    if (up) {
      this.addQuad(bakery, Direction.UP, from, to, new float[] {x1, z1, x2, z2});
    }
    if (down) {
      this.addQuad(bakery, Direction.DOWN, from, to, new float[] {x1, z1, x2, z2});
    }
    if (north) {
      this.addQuad(bakery, Direction.NORTH, from, to, new float[] {x1, y1, x2, y2});
    }
    if (south) {
      this.addQuad(bakery, Direction.SOUTH, from, to, new float[] {x1, y1, x2, y2});
    }
    if (west) {
      this.addQuad(bakery, Direction.WEST, from, to, new float[] {z1, y1, z2, y2});
    }
    if (east) {
      this.addQuad(bakery, Direction.EAST, from, to, new float[] {z1, y1, z2, y2});
    }
  }

  private void addQuad(
      FaceBakery bakery, Direction direction, Vector3f from, Vector3f to, float[] uvWindow) {
    BlockFaceUV uv = new BlockFaceUV(uvWindow, 0);
    BlockElementFace face = new BlockElementFace(null, -1, null, uv);
    BakedQuad quad =
        bakery.bakeQuad(
            from, to, face, this.sprite, direction, BlockModelRotation.X0_Y0, null, true);
    this.faces.computeIfAbsent(direction, d -> new ArrayList<>(2)).add(quad);
  }

  @Override
  public List<BakedQuad> getQuads(
      @Nullable BlockState state, @Nullable Direction side, RandomSource random) {
    if (side == null) {
      return Collections.emptyList();
    }
    return this.faces.getOrDefault(side, Collections.emptyList());
  }

  @Override
  public boolean useAmbientOcclusion() {
    return false;
  }

  @Override
  public boolean isGui3d() {
    return false;
  }

  @Override
  public boolean usesBlockLight() {
    return false;
  }

  @Override
  public boolean isCustomRenderer() {
    return false;
  }

  @Override
  public TextureAtlasSprite getParticleIcon() {
    return this.sprite;
  }

  @Override
  public ItemOverrides getOverrides() {
    return ItemOverrides.EMPTY;
  }

  @Override
  public ItemTransforms getTransforms() {
    return ItemTransforms.NO_TRANSFORMS;
  }
}
