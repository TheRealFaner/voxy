package me.cortex.voxy.client.core.model;

import java.util.concurrent.atomic.AtomicBoolean;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the single chunk render layer Voxy bakes a block state with.
 *
 * <p>The bake paths used {@link ItemBlockRenderTypes#getChunkRenderType(BlockState)}, which returns
 * {@link RenderType#solid()} for any block that never registered a layer (NeoForge also maps
 * unknown/unregistered blocks to solid). Plants from mods that only ship block models
 * (BiomesOPlenty, dtbop, DynamicTreesPlus saplings, ...) were therefore baked as solid: no
 * alpha-discard metadata was emitted, transparent texels were written with RGB/alpha zero, and the
 * whole 16x16 card rendered as an opaque stretched square.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>Fluids keep the vanilla fluid layer (the bake paths handle liquids separately; this keeps
 *       the resolver a drop-in replacement).
 *   <li>Leaves are forced to {@link RenderType#solid()} (Voxy's existing leaf behaviour).
 *   <li>Render types declared by the baked model (NeoForge {@code BakedModel#getRenderTypes}),
 *       which honours the model JSON {@code render_type} field, e.g. Dynamic Trees saplings
 *       declaring {@code cutout_mipped}.
 *   <li>The block's registered layer from {@link ItemBlockRenderTypes#getChunkRenderType}.
 *   <li>If the result is solid and the block is plant-like, use {@link RenderType#cutout()} so
 *       cutout alpha testing is actually baked.
 * </ol>
 *
 * <p>Blocks already registered cutout/cutout_mipped/translucent keep the exact layer they had
 * before; only the solid fallback of plant-like blocks changes.
 */
public final class PlantRenderLayerResolver {
  // Dynamic Trees / Dynamic Trees Plus class names. Voxy deliberately has no compile-time DT
  // dependency, so these are matched against the block's class chain by name.
  private static final String DT_DYNAMIC_SAPLING_CLASS =
      "com.dtteam.dynamictrees.block.sapling.DynamicSaplingBlock";
  private static final String DTP_DYNAMIC_CAP_CLASS =
      "com.dtteam.dynamictreesplus.block.mushroom.DynamicCapBlock";
  private static final String DTP_DYNAMIC_CAP_CENTER_CLASS =
      "com.dtteam.dynamictreesplus.block.mushroom.DynamicCapCenterBlock";
  private static final String DTP_MUSHROOM_BRANCH_CLASS =
      "com.dtteam.dynamictreesplus.block.mushroom.MushroomBranchBlock";

  private static final AtomicBoolean DECLARED_TYPE_FAILURE_LOGGED = new AtomicBoolean();

  private PlantRenderLayerResolver() {}

  public static RenderType resolve(BlockState state) {
    return resolve(state, null);
  }

  /**
   * @param model the model that will be baked, or {@code null} to look it up from the block model
   *     shaper
   */
  public static RenderType resolve(BlockState state, @Nullable BakedModel model) {
    if (state.getBlock() instanceof LiquidBlock) {
      return ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
    }
    if (state.getBlock() instanceof LeavesBlock || state.is(BlockTags.LEAVES)) {
      return RenderType.solid();
    }

    RenderType layer = declaredRenderType(state, model);
    if (layer == null) {
      layer = ItemBlockRenderTypes.getChunkRenderType(state);
    }
    if (layer == RenderType.solid() && isPlantLike(state)) {
      return RenderType.cutout();
    }
    return layer;
  }

  private static @Nullable RenderType declaredRenderType(
      BlockState state, @Nullable BakedModel model) {
    if (model == null) {
      try {
        model =
            Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
      } catch (Throwable t) {
        return null;
      }
    }
    if (model == null) {
      return null;
    }
    try {
      return ModelRenderTypesImpl.getDeclaredRenderType(model, state);
    } catch (Throwable t) {
      if (DECLARED_TYPE_FAILURE_LOGGED.compareAndSet(false, true)) {
        Logger.warn(
            "Failed to query the model render types for "
                + state
                + ", using the registered block render layer",
            t);
      }
      return null;
    }
  }

  /**
   * Plant-like blocks whose solid fallback must become cutout. Leaves, huge mushroom caps (the DTP
   * {@code DynamicCapBlock} extends {@link HugeMushroomBlock}), DTP mushroom stems and fluids are
   * rejected; they have their own render paths. Only non-full-block plants match, so fully opaque
   * structural blocks are never forced onto the cutout layer.
   */
  private static boolean isPlantLike(BlockState state) {
    Block block = state.getBlock();
    if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) {
      return false;
    }
    if (block instanceof HugeMushroomBlock || block instanceof LiquidBlock) {
      return false;
    }
    if (isClassInChain(block, DTP_MUSHROOM_BRANCH_CLASS)
        || isClassInChain(block, DTP_DYNAMIC_CAP_CLASS)
        || isClassInChain(block, DTP_DYNAMIC_CAP_CENTER_CLASS)) {
      return false;
    }
    // BushBlock covers flowers, saplings, crops, stems, grass/ferns (including the tall variants),
    // seagrass and the waterlily; SugarCaneBlock, GrowingPlantBlock and VineBlock are not
    // BushBlock subclasses and are listed explicitly. The tags pick up addon plants that do not
    // extend a vanilla plant class, and the DT sapling class is not a SaplingBlock either.
    return block instanceof BushBlock
        || block instanceof FlowerBlock
        || block instanceof SaplingBlock
        || block instanceof CropBlock
        || block instanceof StemBlock
        || block instanceof SugarCaneBlock
        || block instanceof GrowingPlantBlock
        || block instanceof VineBlock
        || block instanceof WaterlilyBlock
        || isClassInChain(block, DT_DYNAMIC_SAPLING_CLASS)
        || state.is(BlockTags.FLOWERS)
        || state.is(BlockTags.SAPLINGS)
        || state.is(BlockTags.CROPS);
  }

  private static boolean isClassInChain(Block block, String className) {
    for (Class<?> cls = block.getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      if (cls.getName().equals(className)) {
        return true;
      }
    }
    return false;
  }
}
