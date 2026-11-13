package me.cortex.voxy.client.core.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
 * Dynamic Trees surface roots replace {@code trunk_shell} cells around a thick trunk. Vanilla draws
 * the trunk's bark there from the core block's 3x3 model; Voxy's per-cell bake would lose it and
 * leave a notch. This model pairs the root model (with its 3x3x3 ModelData) with the pre-built
 * thick-trunk prism piece for the same cell.
 *
 * <p>{@code hiddenRootSides} lists the cell sides where the trunk wall is the outward surface. The
 * root model's quads on those sides lie inside the trunk volume; with one depth per face they would
 * be stamped in front of the wall (MIN) and turn the wall into a bulging plate. They are dropped so
 * the wall plane wins that view, matching what vanilla renders there.
 *
 * <p>This class is loader-neutral: the NeoForge ModelData-aware merge happens in {@code
 * ModelQuadsImpl.getQuadsCube}, which recognises this type. The plain three-argument query merges
 * both models' quads and is used by loaders without ModelData.
 */
final class CompositeRootShellBakedModel implements BakedModel {
  private final BakedModel rootModel;
  private final BakedModel shellModel;
  private final Set<Direction> hiddenRootSides;

  CompositeRootShellBakedModel(BakedModel rootModel, BakedModel shellModel) {
    this(rootModel, shellModel, EnumSet.noneOf(Direction.class));
  }

  CompositeRootShellBakedModel(
      BakedModel rootModel, BakedModel shellModel, Set<Direction> hiddenRootSides) {
    this.rootModel = rootModel;
    this.shellModel = shellModel;
    this.hiddenRootSides =
        hiddenRootSides.isEmpty()
            ? EnumSet.noneOf(Direction.class)
            : EnumSet.copyOf(hiddenRootSides);
  }

  BakedModel rootModel() {
    return this.rootModel;
  }

  BakedModel shellModel() {
    return this.shellModel;
  }

  Set<Direction> hiddenRootSides() {
    return this.hiddenRootSides;
  }

  /** Drops root quads on the trunk-facing sides; used by the cached-quad pull as well. */
  List<BakedQuad> filterRootQuads(@Nullable Direction side, List<BakedQuad> quads) {
    if (this.hiddenRootSides.isEmpty() || quads.isEmpty()) {
      return quads;
    }
    if (side != null) {
      return this.hiddenRootSides.contains(side) ? List.of() : quads;
    }
    List<BakedQuad> kept = new ArrayList<>(quads.size());
    for (BakedQuad quad : quads) {
      if (!this.hiddenRootSides.contains(quad.getDirection())) {
        kept.add(quad);
      }
    }
    return kept;
  }

  static List<BakedQuad> merge(List<BakedQuad> first, List<BakedQuad> second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    List<BakedQuad> merged = new ArrayList<>(first.size() + second.size());
    merged.addAll(first);
    merged.addAll(second);
    return merged;
  }

  @Override
  public List<BakedQuad> getQuads(
      @Nullable BlockState state, @Nullable Direction side, RandomSource random) {
    return merge(
        this.filterRootQuads(side, this.rootModel.getQuads(state, side, random)),
        this.shellModel.getQuads(state, side, random));
  }

  @Override
  public boolean useAmbientOcclusion() {
    return this.rootModel.useAmbientOcclusion();
  }

  @Override
  public boolean isGui3d() {
    return this.rootModel.isGui3d();
  }

  @Override
  public boolean usesBlockLight() {
    return this.rootModel.usesBlockLight();
  }

  @Override
  public boolean isCustomRenderer() {
    return this.rootModel.isCustomRenderer();
  }

  @Override
  public TextureAtlasSprite getParticleIcon() {
    return this.rootModel.getParticleIcon();
  }

  @Override
  public ItemOverrides getOverrides() {
    return this.rootModel.getOverrides();
  }

  @Override
  public ItemTransforms getTransforms() {
    return this.rootModel.getTransforms();
  }
}
