package me.cortex.voxy.client.core.model;

import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Loader-neutral quad pull that can pass per-position neighbour context to baked models.
 *
 * <p>NeoForge models (notably Dynamic Trees) derive geometry from {@code ModelData} computed from
 * the six adjacent block states. The Fabric implementation has no ModelData equivalent and simply
 * delegates to the vanilla three-argument {@link BakedModel#getQuads(BlockState, Direction,
 * RandomSource)}.
 */
public interface ModelQuads {
  /** True when neighbour states actually influence the pulled quads (NeoForge ModelData). */
  default boolean supportsNeighbourContext() {
    return false;
  }

  /**
   * @param neighbours six block states in {@link Direction#get3DDataValue()} order (DOWN, UP,
   *     NORTH, SOUTH, WEST, EAST), or {@code null} for a normal bake
   */
  List<BakedQuad> getQuads(
      BakedModel model,
      BlockState state,
      @Nullable Direction side,
      RandomSource random,
      @Nullable BlockState[] neighbours);

  /**
   * Pulls quads with a full 3x3x3 block-state context (Dynamic Trees surface roots read diagonals
   * one layer above/below their horizontal neighbours).
   *
   * @param cube 27 block states, index {@code (dx+1) + 3*(dy+1) + 9*(dz+1)}; index 13 (the centre)
   *     is ignored in favour of {@code state}
   */
  default List<BakedQuad> getQuadsCube(
      BakedModel model,
      BlockState state,
      @Nullable Direction side,
      RandomSource random,
      BlockState[] cube) {
    return this.getQuads(model, state, side, random, null);
  }
}
