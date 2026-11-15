package me.cortex.voxy.client.core.model;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Fabric model render-type query. Vanilla {@link BakedModel} exposes no render types and the model
 * JSON {@code render_type} field is a NeoForge extension, so there is nothing to query; the shared
 * {@link PlantRenderLayerResolver} falls back to {@code ItemBlockRenderTypes#getChunkRenderType},
 * which Fabric's {@code BlockRenderLayerMap} populates for registered blocks.
 */
public final class ModelRenderTypesImpl {
  private ModelRenderTypesImpl() {}

  public static @Nullable RenderType getDeclaredRenderType(BakedModel model, BlockState state) {
    return null;
  }
}
