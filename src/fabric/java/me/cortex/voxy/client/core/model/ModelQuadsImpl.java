package me.cortex.voxy.client.core.model;

import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Fabric stub: Fabric has no per-position ModelData equivalent for block models, so neighbour
 * context is ignored and the vanilla three-argument pull is used. Dynamic Trees branch variants are
 * a NeoForge-only feature for now.
 */
public final class ModelQuadsImpl implements ModelQuads {
  @Override
  public List<BakedQuad> getQuads(
      BakedModel model,
      BlockState state,
      @Nullable Direction side,
      RandomSource random,
      @Nullable BlockState[] neighbours) {
    return model.getQuads(state, side, random);
  }
}
