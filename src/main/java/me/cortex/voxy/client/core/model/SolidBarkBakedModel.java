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
 * Synthetic full-cube model textured with one sprite.
 *
 * <p>Used for Dynamic Trees thick branches (radius &gt; 8): their real model paints the trunk
 * surface in the eight <em>neighbouring</em> block cells (a 3x3x1-cell prism), which Voxy's
 * per-cell rasterizer cannot capture, while the branch's own cell contains at most a thin
 * top/bottom cap. A full bark cube at least keeps the trunk column visible and solid at every LOD
 * instead of leaving a stack of detached ring caps.
 *
 * <p>Quads are registered as culled faces, matching a vanilla cube: the null-direction query
 * returns nothing and each directional query returns that face. Both Voxy bakeries call every
 * direction plus null, so exactly six quads are emitted.
 */
final class SolidBarkBakedModel implements BakedModel {
  private final TextureAtlasSprite sprite;
  private final BakedQuad[] faces = new BakedQuad[6];

  TextureAtlasSprite sprite() {
    return this.sprite;
  }

  SolidBarkBakedModel(TextureAtlasSprite sprite) {
    this.sprite = sprite;
    FaceBakery bakery = new FaceBakery();
    Vector3f from = new Vector3f(0.0f, 0.0f, 0.0f);
    Vector3f to = new Vector3f(16.0f, 16.0f, 16.0f);
    for (Direction direction : Direction.values()) {
      BlockFaceUV uv = new BlockFaceUV(new float[] {0.0f, 0.0f, 16.0f, 16.0f}, 0);
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
