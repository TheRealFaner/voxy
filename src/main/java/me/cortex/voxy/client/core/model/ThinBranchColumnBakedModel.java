package me.cortex.voxy.client.core.model;

import java.util.Collections;
import java.util.List;
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
 * Synthetic bark column for Dynamic Trees thin branches (radius 1..8).
 *
 * <p>DT's thin-branch model is generated from neighbour-supplied connection data. On the
 * neighbour-less base bake (LOD1+ and any LOD0 fallback while a variant is capped, back-pressured
 * or not yet baked) it degrades to a connectionless model that only paints the centre band/stick,
 * so a distant thin trunk renders as dashed slivers. Neighbour-aware variants are LOD0-only, so the
 * stick would otherwise persist at every coarser LOD.
 *
 * <p>This model reproduces the branch's own cell: a square bark column spanning the full cell
 * height ({@code y 0..16}), {@code 2*radius} texels wide/deep and centred at 8, textured with the
 * branch model's particle (bark) sprite at one sprite texel per texel. Diagonal branches therefore
 * appear as vertical columns at LOD1+, which is the intended coarse-LOD approximation.
 *
 * <p>Quads are registered as culled faces like {@link SolidBarkBakedModel}: the null-direction
 * query returns nothing and each directional query returns that face. Both Voxy bakeries call every
 * direction plus null, so exactly six quads are emitted: four sides and two end caps.
 */
final class ThinBranchColumnBakedModel implements BakedModel {
  private final TextureAtlasSprite sprite;
  private final BakedQuad[] faces = new BakedQuad[6];

  TextureAtlasSprite sprite() {
    return this.sprite;
  }

  ThinBranchColumnBakedModel(TextureAtlasSprite sprite, int radius) {
    this.sprite = sprite;
    int r = Math.clamp(radius, 1, 8);
    float fromXZ = 8.0f - r;
    float toXZ = 8.0f + r;
    Vector3f from = new Vector3f(fromXZ, 0.0f, fromXZ);
    Vector3f to = new Vector3f(toXZ, 16.0f, toXZ);
    float columnWidth = 2.0f * r;
    FaceBakery bakery = new FaceBakery();
    for (Direction direction : Direction.values()) {
      // One sprite texel per texel: sides span the column width across the full cell height, caps
      // span the column square.
      float uvHeight = direction.getAxis().isVertical() ? columnWidth : 16.0f;
      BlockFaceUV uv = new BlockFaceUV(new float[] {0.0f, 0.0f, columnWidth, uvHeight}, 0);
      BlockElementFace face = new BlockElementFace(null, -1, null, uv);
      this.faces[direction.get3DDataValue()] =
          bakery.bakeQuad(from, to, face, sprite, direction, BlockModelRotation.X0_Y0, null, true);
    }
  }

  @Override
  public List<BakedQuad> getQuads(
      @Nullable BlockState state, @Nullable Direction side, RandomSource random) {
    if (side == null) {
      return Collections.emptyList();
    }
    return Collections.singletonList(this.faces[side.get3DDataValue()]);
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
