package me.cortex.voxy.client.core.model;

import java.util.List;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge model render-type query. Lives in the NeoForge source set because {@link ModelData},
 * {@link ChunkRenderTypeSet} and {@code BakedModel#getRenderTypes} are NeoForge-only APIs; the
 * shared {@link PlantRenderLayerResolver} reaches it through the same-FQN dual implementation
 * pattern used by {@code ModelQuadsImpl}.
 */
public final class ModelRenderTypesImpl {
  private ModelRenderTypesImpl() {}

  /**
   * Returns the single layer Voxy should bake the model with, chosen from the model's declared
   * chunk render types, or {@code null} when the model declares none. The default NeoForge
   * implementation of {@code getRenderTypes} defers to {@link ItemBlockRenderTypes#getRenderLayers}
   * and therefore still returns solid for blocks that never registered a layer.
   */
  public static @Nullable RenderType getDeclaredRenderType(BakedModel model, BlockState state) {
    ChunkRenderTypeSet types = model.getRenderTypes(state, RandomSource.create(0), ModelData.EMPTY);
    if (types == null || types.isEmpty()) {
      return null;
    }
    List<RenderType> layers = types.asList();
    if (layers.size() == 1) {
      return layers.get(0);
    }
    // Voxy bakes one layer per block. Prefer the registered layer when the model declares it
    // (that is what the bake paths used before this resolver), then a declared cutout layer, and
    // only then translucent. Vanilla models without an explicit render_type return a
    // single-element set, so this branch only affects models that declare multiple layers.
    RenderType registered = ItemBlockRenderTypes.getChunkRenderType(state);
    if (registered != RenderType.solid() && types.contains(registered)) {
      return registered;
    }
    if (types.contains(RenderType.cutoutMipped())) {
      return RenderType.cutoutMipped();
    }
    if (types.contains(RenderType.cutout())) {
      return RenderType.cutout();
    }
    if (types.contains(RenderType.tripwire())) {
      return RenderType.tripwire();
    }
    if (types.contains(RenderType.translucent())) {
      return RenderType.translucent();
    }
    return RenderType.solid();
  }
}
