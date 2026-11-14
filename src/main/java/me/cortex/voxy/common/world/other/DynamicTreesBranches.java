package me.cortex.voxy.common.world.other;

import java.util.concurrent.atomic.AtomicBoolean;
import me.cortex.voxy.common.Logger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Dynamic Trees branch detection without a compile-time Dynamic Trees dependency.
 *
 * <p>Branch geometry is generated from neighbour state by {@code BranchBlock.getConnectionData}, so
 * only real branch blocks (Basic/Thick/Palm and addon subclasses) are valid variant candidates.
 * Roots extend {@code BranchBlock} but must be excluded. Among the {@code dynamictreesplus} addon
 * blocks, mushroom branches ({@code MushroomBranchBlock extends ThickBranchBlock}) are regular
 * ModelData branch models and are variant-eligible; cacti are not, because their custom {@code
 * CactusBranchBlockBakedModel} has a different contract, so the exclusion is narrowed to that class
 * instead of the whole namespace.
 */
public final class DynamicTreesBranches {
  public static final String BRANCH_CLASS = "com.dtteam.dynamictrees.block.branch.BranchBlock";
  public static final String ROOTS_CLASS = "com.dtteam.dynamictrees.block.branch.BasicRootsBlock";
  public static final String TRUNK_SHELL_CLASS =
      "com.dtteam.dynamictrees.block.branch.TrunkShellBlock";
  public static final String SURFACE_ROOT_CLASS =
      "com.dtteam.dynamictrees.block.branch.SurfaceRootBlock";

  /**
   * DynamicTreesPlus cactus branch. Verified in DynamicTreesPlus-neoforge-1.21.1-1.3.2: {@code
   * CactusBranchBlock extends com.dtteam.dynamictrees.block.branch.BranchBlock} but uses the custom
   * {@code CactusBranchBlockBakedModel}, so it stays excluded from the variant path.
   */
  public static final String CACTUS_BRANCH_CLASS =
      "com.dtteam.dynamictreesplus.block.CactusBranchBlock";

  public static final String MUSHROOM_BRANCH_CLASS =
      "com.dtteam.dynamictreesplus.block.mushroom.MushroomBranchBlock";

  private static final AtomicBoolean MUSHROOM_VARIANT_ELIGIBLE_LOGGED = new AtomicBoolean();

  /**
   * Largest radius whose baked model stays inside one block cell. Branches above this (Dynamic
   * Trees thick branches, radius 9..24) render their outer surface outside the cell, so Voxy's
   * per-block rasterizer would clip every face and produce an empty texture.
   */
  public static final int MAX_CELL_RADIUS = 8;

  private static final TagKey<Block> DYNAMICTREES_BRANCHES =
      TagKey.create(
          Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("dynamictrees", "branches"));

  private DynamicTreesBranches() {}

  public static boolean isBranch(BlockState state) {
    if (state == null || state.isAir()) {
      return false;
    }
    // Narrow DTP exclusion: cactus is a BranchBlock subclass but its custom baked model does not
    // follow the ModelData contract, so it must stay out of the variant/base model path.
    if (isCactusBranch(state)) {
      return false;
    }
    // The tag alone is enough: DTP mushroom branches are listed in dynamictrees:fungus_branches,
    // and dynamictrees:branches includes that tag (verified in DynamicTreesPlus 1.3.2 data).
    if (isBranchClass(state) || state.is(DYNAMICTREES_BRANCHES)) {
      noteMushroomVariantEligibility(state);
      return true;
    }
    return false;
  }

  /**
   * True for a Dynamic Trees branch whose baked model cannot fit one Voxy cell (radius &gt; 8).
   * {@code dynamictreesplus} blocks are not excluded wholesale: {@code CactusBranchBlock} is
   * excluded by exact class, while mushroom branches are allowed; the synthetic bark cube only
   * needs the model's particle sprite, not its ModelData contract.
   */
  public static boolean isThickBranch(BlockState state) {
    if (state == null || state.isAir() || radiusOf(state) <= MAX_CELL_RADIUS) {
      return false;
    }
    if (isCactusBranch(state)) {
      return false;
    }
    return isBranchClass(state) || state.is(DYNAMICTREES_BRANCHES);
  }

  /**
   * True when the block extends the DT {@code BranchBlock} class (roots and DTP cactus excluded).
   * Use {@link #isBranch} for the variant-eligible predicate.
   */
  public static boolean isBranchBlockClass(BlockState state) {
    return state != null && !state.isAir() && isBranchClass(state);
  }

  /**
   * True when a base bake that produced no quads may be replaced by the synthetic solid-bark cube
   * (see {@code ModelFactory.branchSolidBarkFallbackFor}). Covers branch blocks and tag members
   * (roots and DTP cactus are excluded by {@link #isBranchClass}); {@code trunk_shell} is excluded
   * because it already has its own shell fallback.
   */
  public static boolean isSolidBarkFallbackEligible(BlockState state) {
    if (state == null || state.isAir() || isTrunkShell(state)) {
      return false;
    }
    return isBranch(state);
  }

  private static boolean isBranchClass(BlockState state) {
    // Roots are BranchBlock subclasses and must not be treated as branches. DTP cactus is also a
    // BranchBlock subclass but has a custom baked model, so it is excluded by its exact class.
    for (Class<?> cls = state.getBlock().getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      String name = cls.getName();
      if (ROOTS_CLASS.equals(name) || CACTUS_BRANCH_CLASS.equals(name)) {
        return false;
      }
      if (BRANCH_CLASS.equals(name)) {
        return true;
      }
    }
    return false;
  }

  /** True when the block's class chain contains {@code className} (exact match). */
  private static boolean isClassInChain(BlockState state, String className) {
    for (Class<?> cls = state.getBlock().getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      if (className.equals(cls.getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCactusBranch(BlockState state) {
    return isClassInChain(state, CACTUS_BRANCH_CLASS);
  }

  /**
   * One-shot (per session) notice that DynamicTreesPlus mushroom branches now take the variant
   * path. Kept out of the hot per-state log: the atomic fast path returns before the class-chain
   * walk after the first call.
   */
  private static void noteMushroomVariantEligibility(BlockState state) {
    if (MUSHROOM_VARIANT_ELIGIBLE_LOGGED.get()) {
      return;
    }
    if (!isClassInChain(state, MUSHROOM_BRANCH_CLASS)) {
      return;
    }
    if (MUSHROOM_VARIANT_ELIGIBLE_LOGGED.compareAndSet(false, true)) {
      ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
      Logger.info(
          "DynamicTreesPlus mushroom branches are variant-eligible: "
              + id
              + " ("
              + MUSHROOM_BRANCH_CLASS
              + ")");
    }
  }

  /**
   * True when the block extends DT {@code BranchBlock}, roots included. DT's root model checks any
   * branch (roots are branches too) with radius {@code >= 8} and returns a MID connection of radius
   * 8 before running its HIGH/LOW tests, so the key mirror must include that case.
   */
  public static boolean isBranchLike(BlockState state) {
    if (state == null || state.isAir()) {
      return false;
    }
    for (Class<?> cls = state.getBlock().getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      if (BRANCH_CLASS.equals(cls.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads the DT {@code radius} block-state property without a compile-time dependency. Returns -1
   * when the block has no such property (non-DT blocks, cactus-style branches).
   */
  public static int radiusOf(BlockState state) {
    if (state == null) {
      return -1;
    }
    for (Property<?> property : state.getProperties()) {
      if (!"radius".equals(property.getName())) {
        continue;
      }
      Comparable<?> value = state.getValue(property);
      if (value instanceof Integer radius) {
        return radius;
      }
    }
    return -1;
  }

  /**
   * True for DT surface-root blocks. Their model reads a 3x3x3 neighbourhood (up state plus each
   * horizontal side at y-1/y/y+1), so they need the cube-context variant path.
   */
  public static boolean isSurfaceRoot(BlockState state) {
    if (state == null || state.isAir()) {
      return false;
    }
    for (Class<?> cls = state.getBlock().getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      if (SURFACE_ROOT_CLASS.equals(cls.getName())) {
        return true;
      }
    }
    return false;
  }

  /** Reads the surface root's {@code grounded} property (false when absent). */
  public static boolean isGrounded(BlockState state) {
    if (state == null) {
      return false;
    }
    for (Property<?> property : state.getProperties()) {
      if (!"grounded".equals(property.getName())) {
        continue;
      }
      Comparable<?> value = state.getValue(property);
      return value instanceof Boolean grounded && grounded;
    }
    return false;
  }

  private static final net.minecraft.world.level.BlockGetter EMPTY_GETTER =
      new net.minecraft.world.level.BlockGetter() {
        @Override
        public BlockState getBlockState(net.minecraft.core.BlockPos pos) {
          return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(
            net.minecraft.core.BlockPos pos) {
          return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState().getFluidState();
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
            net.minecraft.core.BlockPos pos) {
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
      };

  /** DT's HIGH-connection requirement: the same-level neighbour is a full collision block. */
  public static boolean isFullCollisionBlock(BlockState state) {
    return state != null
        && state.isCollisionShapeFullBlock(EMPTY_GETTER, net.minecraft.core.BlockPos.ZERO);
  }

  /**
   * DT's root connection test. Dynamic Trees compares block identity against {@code Blocks.AIR} and
   * {@code Blocks.WATER} only; cave/void air and waterlogged blocks do not count as air/water
   * there, and treating them as such mispredicted HIGH/LOW connections.
   */
  public static boolean isAirOrWater(BlockState state) {
    return state != null
        && (state.getBlock() == net.minecraft.world.level.block.Blocks.AIR
            || state.getBlock() == net.minecraft.world.level.block.Blocks.WATER);
  }

  /** True for the invisible {@code dynamictrees:trunk_shell} blocks around a thick trunk core. */
  public static boolean isTrunkShell(BlockState state) {
    if (state == null || state.isAir()) {
      return false;
    }
    for (Class<?> cls = state.getBlock().getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      if (TRUNK_SHELL_CLASS.equals(cls.getName())) {
        return true;
      }
    }
    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    return id != null && "trunk_shell".equals(id.getPath());
  }

  /**
   * Direction from a trunk shell to its core, from the {@code coredir} property. Returns {@code
   * {dx, dz}} or null when the state has no such property.
   */
  public static int @Nullable [] coreDir(BlockState state) {
    if (state == null) {
      return null;
    }
    for (Property<?> property : state.getProperties()) {
      if (!"coredir".equals(property.getName())
          || !(property instanceof EnumProperty<?> enumProperty)) {
        continue;
      }
      Comparable<?> value = state.getValue(property);
      if (!(value instanceof Enum<?> enumValue)) {
        return null;
      }
      String name = getName(enumProperty, enumValue);
      return switch (name) {
        case "n" -> new int[] {0, -1};
        case "nw" -> new int[] {-1, -1};
        case "w" -> new int[] {-1, 0};
        case "sw" -> new int[] {-1, 1};
        case "s" -> new int[] {0, 1};
        case "se" -> new int[] {1, 1};
        case "e" -> new int[] {1, 0};
        case "ne" -> new int[] {1, -1};
        default -> null;
      };
    }
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String getName(EnumProperty<?> property, Enum<?> value) {
    return ((EnumProperty) property).getName(value);
  }

  /** True when the branch model can be baked into a single cell (radius {@code <= 8}). */
  public static boolean isVariantSupported(BlockState state) {
    if (!isBranch(state)) {
      return false;
    }
    int radius = radiusOf(state);
    return radius < 0 || radius <= MAX_CELL_RADIUS;
  }
}
