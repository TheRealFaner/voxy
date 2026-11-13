package me.cortex.voxy.client.core.model;

import java.util.Collections;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge implementation: builds the model's {@link ModelData} against a synthetic level whose six
 * one-block neighbours are the mesh-time states, then pulls quads with that data.
 */
public final class ModelQuadsImpl implements ModelQuads {
  @Override
  public boolean supportsNeighbourContext() {
    return true;
  }

  @Override
  public List<BakedQuad> getQuads(
      BakedModel model,
      BlockState state,
      @Nullable Direction side,
      RandomSource random,
      @Nullable BlockState[] neighbours) {
    if (neighbours == null || neighbours.length < 6) {
      return model.getQuads(state, side, random);
    }
    // The vert filter forwards ModelData to its delegate: querying the wrapper itself returns
    // EMPTY and silently drops the Dynamic Trees connection data.
    BakedModel dataModel =
        model instanceof RootVertFilterBakedModel rootFilter ? rootFilter.delegate() : model;
    ModelData data =
        dataModel.getModelData(
            new NeighbourGetter(state, neighbours), BlockPos.ZERO, state, ModelData.EMPTY);
    List<BakedQuad> quads =
        (data == null || data == ModelData.EMPTY)
            ? dataModel.getQuads(state, side, random)
            : dataModel.getQuads(state, side, random, data, null);
    // An empty list is a legitimate answer for "this position has no geometry on this side"
    // (Dynamic Trees thick branches have no side quads in their own cell). Never substitute the
    // plain, ModelData-free quads: that would mix unrelated geometry into the variant. Exceptions
    // are deliberately not caught here so bakeVariant* can fail and count the variant.
    if (quads == null) {
      quads = Collections.emptyList();
    }
    return model instanceof RootVertFilterBakedModel rootFilter ? rootFilter.filter(quads) : quads;
  }

  @Override
  public List<BakedQuad> getQuadsCube(
      BakedModel model,
      BlockState state,
      @Nullable Direction side,
      RandomSource random,
      BlockState[] cube) {
    if (model instanceof CompositeRootShellBakedModel composite) {
      // Root models own the ModelData; the shell piece is a static synthetic model. Merge them
      // here so the root keeps its 3x3x3 connection data. The vert filter supplies the DT
      // ModelData through its delegate and filters the resulting root quads.
      BakedModel rootModel = composite.rootModel();
      BakedModel dataModel =
          rootModel instanceof RootVertFilterBakedModel rootFilter
              ? rootFilter.delegate()
              : rootModel;
      ModelData data =
          dataModel.getModelData(
              new CubeGetter(state, cube), BlockPos.ZERO, state, ModelData.EMPTY);
      List<BakedQuad> rootQuads =
          data == null || data == ModelData.EMPTY
              ? dataModel.getQuads(state, side, random)
              : dataModel.getQuads(state, side, random, data, null);
      if (rootModel instanceof RootVertFilterBakedModel filteredRoot) {
        rootQuads = filteredRoot.filter(rootQuads);
      }
      rootQuads = composite.filterRootQuads(side, rootQuads);
      List<BakedQuad> shellQuads = composite.shellModel().getQuads(state, side, random);
      return CompositeRootShellBakedModel.merge(
          rootQuads == null ? Collections.emptyList() : rootQuads,
          shellQuads == null ? Collections.emptyList() : shellQuads);
    }
    BakedModel dataModel =
        model instanceof RootVertFilterBakedModel rootFilter ? rootFilter.delegate() : model;
    ModelData data =
        dataModel.getModelData(new CubeGetter(state, cube), BlockPos.ZERO, state, ModelData.EMPTY);
    List<BakedQuad> quads =
        data == null || data == ModelData.EMPTY
            ? dataModel.getQuads(state, side, random)
            : dataModel.getQuads(state, side, random, data, null);
    if (quads == null) {
      quads = Collections.emptyList();
    }
    return model instanceof RootVertFilterBakedModel rootFilter ? rootFilter.filter(quads) : quads;
  }

  /** A 3x3x3 neighbourhood around {@link BlockPos#ZERO}; anything outside is air. */
  private static final class CubeGetter implements BlockAndTintGetter {
    private final BlockState origin;
    private final BlockState[] cube;

    private CubeGetter(BlockState origin, BlockState[] cube) {
      this.origin = origin;
      this.cube = cube;
    }

    private BlockState stateAt(BlockPos pos) {
      int x = pos.getX();
      int y = pos.getY();
      int z = pos.getZ();
      if (x == 0 && y == 0 && z == 0) {
        return this.origin;
      }
      if (x < -1 || x > 1 || y < -1 || y > 1 || z < -1 || z > 1) {
        return Blocks.AIR.defaultBlockState();
      }
      BlockState state = this.cube[(x + 1) + 3 * (y + 1) + 9 * (z + 1)];
      return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      return this.stateAt(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return this.stateAt(pos).getFluidState();
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
      return 1.0f;
    }

    @Override
    public LevelLightEngine getLightEngine() {
      return null;
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
      return 0;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
      return 0;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
    }

    @Override
    public int getHeight() {
      return 0;
    }

    @Override
    public int getMinBuildHeight() {
      return 0;
    }
  }

  private static final class NeighbourGetter implements BlockAndTintGetter {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final BlockState origin;
    private final BlockState[] neighbours;

    private NeighbourGetter(BlockState origin, BlockState[] neighbours) {
      this.origin = origin;
      this.neighbours = neighbours;
    }

    private BlockState stateAt(BlockPos pos) {
      int x = pos.getX();
      int y = pos.getY();
      int z = pos.getZ();
      for (Direction direction : DIRECTIONS) {
        var normal = direction.getNormal();
        if (x == normal.getX() && y == normal.getY() && z == normal.getZ()) {
          return this.neighbours[direction.get3DDataValue()];
        }
      }
      if (x == 0 && y == 0 && z == 0) {
        return this.origin;
      }
      return Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      return this.stateAt(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return this.stateAt(pos).getFluidState();
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
      return 1.0f;
    }

    @Override
    public LevelLightEngine getLightEngine() {
      return null;
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
      return 0;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
      return 0;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
    }

    @Override
    public int getHeight() {
      return 0;
    }

    @Override
    public int getMinBuildHeight() {
      return 0;
    }
  }
}
