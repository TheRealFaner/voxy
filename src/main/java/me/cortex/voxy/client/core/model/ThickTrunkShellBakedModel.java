package me.cortex.voxy.client.core.model;

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
 * One cell of a Dynamic Trees thick-trunk prism.
 *
 * <p>DT renders a radius {@code r > 8} trunk as one model covering nine cells: a square prism
 * spanning {@code [8-r, 8+r]} texels in x/z of the core block's local space. The core cell itself
 * contains no side bark; the visible bark lives in the eight {@code trunk_shell} neighbours, which
 * are invisible in vanilla (their state carries no radius or texture). This model reproduces the
 * part of the prism that falls inside one neighbouring cell, so Voxy bakes the trunk at its real
 * width instead of a single bark cube.
 *
 * <p>Inputs are the core's {@code radius} and this cell's offset from the core ({@code -1..1} on
 * x/z). Geometry is emitted in this cell's local coordinates, so the existing per-cell rasterizer
 * records the bark plane at its true inset depth (a radius-16 trunk face sits half a block inside
 * the shell cell).
 */
final class ThickTrunkShellBakedModel implements BakedModel {
  private final TextureAtlasSprite sprite;
  private final Map<Direction, BakedQuad> faces = new EnumMap<>(Direction.class);

  ThickTrunkShellBakedModel(
      TextureAtlasSprite sprite,
      int radius,
      int cellDx,
      int cellDz,
      boolean capUp,
      boolean capDown) {
    this.sprite = sprite;
    int r = Math.clamp(radius, 9, 24);

    // Prism [8-r, 8+r]^2 in core-local texels, intersected with this cell and re-based to it.
    float cx1 = Math.max(8 - r, 16 * cellDx);
    float cx2 = Math.min(8 + r, 16 * cellDx + 16);
    float cz1 = Math.max(8 - r, 16 * cellDz);
    float cz2 = Math.min(8 + r, 16 * cellDz + 16);
    float x1 = cx1 - 16 * cellDx;
    float x2 = cx2 - 16 * cellDx;
    float z1 = cz1 - 16 * cellDz;
    float z2 = cz2 - 16 * cellDz;
    if (x2 - x1 <= 0.0f || z2 - z1 <= 0.0f) {
      return;
    }

    FaceBakery bakery = new FaceBakery();
    // Side faces: only the face pointing away from the core exists in this cell. The box extends
    // towards the trunk interior so FaceBakery has a non-degenerate element (DT does the same).
    if (cellDx < 0) {
      this.addQuad(
          bakery, Direction.WEST, new Vector3f(x1, 0, z1), new Vector3f(16, 16, z2), z2 - z1, 16);
    } else if (cellDx > 0) {
      this.addQuad(
          bakery, Direction.EAST, new Vector3f(0, 0, z1), new Vector3f(x2, 16, z2), z2 - z1, 16);
    }
    if (cellDz < 0) {
      this.addQuad(
          bakery, Direction.NORTH, new Vector3f(x1, 0, z1), new Vector3f(x2, 16, 16), x2 - x1, 16);
    } else if (cellDz > 0) {
      this.addQuad(
          bakery, Direction.SOUTH, new Vector3f(x1, 0, 0), new Vector3f(x2, 16, z2), x2 - x1, 16);
    }

    // End caps are baked per variant and only selected at the actual trunk ends / radius
    // transitions (see ShellModelKey). Unconditional caps produced a bark shelf in every ring
    // cell of every block layer.
    if (capUp) {
      this.addQuad(
          bakery,
          Direction.UP,
          new Vector3f(x1, 0, z1),
          new Vector3f(x2, 16, z2),
          x2 - x1,
          z2 - z1);
    }
    if (capDown) {
      this.addQuad(
          bakery,
          Direction.DOWN,
          new Vector3f(x1, 0, z1),
          new Vector3f(x2, 16, z2),
          x2 - x1,
          z2 - z1);
    }
  }

  private void addQuad(
      FaceBakery bakery,
      Direction direction,
      Vector3f from,
      Vector3f to,
      float uvWidth,
      float uvHeight) {
    BlockFaceUV uv = new BlockFaceUV(new float[] {0.0f, 0.0f, uvWidth, uvHeight}, 0);
    BlockElementFace face = new BlockElementFace(null, -1, null, uv);
    this.faces.put(
        direction,
        bakery.bakeQuad(
            from, to, face, this.sprite, direction, BlockModelRotation.X0_Y0, null, true));
  }

  @Override
  public List<BakedQuad> getQuads(
      @Nullable BlockState state, @Nullable Direction side, RandomSource random) {
    if (side == null) {
      return Collections.emptyList();
    }
    BakedQuad quad = this.faces.get(side);
    return quad == null ? Collections.emptyList() : Collections.singletonList(quad);
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
