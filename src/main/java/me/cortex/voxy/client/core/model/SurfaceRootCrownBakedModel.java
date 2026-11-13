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
 * Synthetic Dynamic Trees surface-root "crown".
 *
 * <p>DT's own root model combines a small core, sleeves of different radii and HIGH-connection
 * verts. A single Voxy face can only store one depth for the whole face impostor, so those multiple
 * planes produced a ragged bake (faces shifted inward, steps and gaps between root cells). This
 * model keeps the root path (a sleeve toward every connected side) but uses the cell's own radius
 * and a uniform height {@code 2r} for every piece, so each of the six views sees exactly one plane:
 * the face depth is unambiguous and the bake is clean.
 *
 * <p>The price is that sleeves no longer taper to a thinner neighbour; the root chain still steps
 * down by the core radii.
 */
final class SurfaceRootCrownBakedModel implements BakedModel {
  private final TextureAtlasSprite sprite;
  private final Map<Direction, List<BakedQuad>> faces = new EnumMap<>(Direction.class);

  SurfaceRootCrownBakedModel(
      TextureAtlasSprite sprite,
      int radius,
      boolean grounded,
      int northRadius,
      int eastRadius,
      int southRadius,
      int westRadius) {
    this.sprite = sprite;
    int r = Math.clamp(radius, 1, 8);
    // DT 1.7.x never sets GROUNDED; keep the crown visible anyway (an empty cell is worse than a
    // small root plate).
    float coreHeight = 2.0f * r;
    this.addBox(8 - r, 0, 8 - r, 8 + r, coreHeight, 8 + r, null);
    if (r < 8) {
      int n = Math.clamp(northRadius, 0, r);
      int e = Math.clamp(eastRadius, 0, r);
      int s = Math.clamp(southRadius, 0, r);
      int w = Math.clamp(westRadius, 0, r);
      // DT's sleeves are 2*connectionRadius tall, not 2*ownRadius. Using the own height made
      // every cell a flat plate whose vertical edge stuck out at the cell boundary; the vanilla
      // chain steps down inside the cell and meets the neighbour flush at the boundary.
      if (n > 0) {
        this.addBox(8 - n, 0, 0, 8 + n, 2.0f * n, 8 - n, Direction.SOUTH);
      }
      if (s > 0) {
        this.addBox(8 - s, 0, 8 + s, 8 + s, 2.0f * s, 16, Direction.NORTH);
      }
      if (w > 0) {
        this.addBox(0, 0, 8 - w, 8 - w, 2.0f * w, 8 + w, Direction.EAST);
      }
      if (e > 0) {
        this.addBox(8 + e, 0, 8 - e, 16, 2.0f * e, 8 + e, Direction.WEST);
      }
    }
  }

  private void addBox(
      float x1, float y1, float z1, float x2, float y2, float z2, @Nullable Direction skipFace) {
    FaceBakery bakery = new FaceBakery();
    Vector3f from = new Vector3f(x1, y1, z1);
    Vector3f to = new Vector3f(x2, y2, z2);
    for (Direction direction : Direction.values()) {
      if (direction == skipFace) {
        continue;
      }
      float uvWidth;
      float uvHeight;
      switch (direction.getAxis()) {
        case X -> {
          uvWidth = z2 - z1;
          uvHeight = y2 - y1;
        }
        case Y -> {
          uvWidth = x2 - x1;
          uvHeight = z2 - z1;
        }
        default -> {
          uvWidth = x2 - x1;
          uvHeight = y2 - y1;
        }
      }
      BlockFaceUV uv = new BlockFaceUV(new float[] {0.0f, 0.0f, uvWidth, uvHeight}, 0);
      BlockElementFace face = new BlockElementFace(null, -1, null, uv);
      this.faces
          .computeIfAbsent(direction, d -> new ArrayList<>(1))
          .add(
              bakery.bakeQuad(
                  from, to, face, this.sprite, direction, BlockModelRotation.X0_Y0, null, true));
    }
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
