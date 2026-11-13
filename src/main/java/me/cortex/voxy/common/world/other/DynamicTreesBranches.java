package me.cortex.voxy.common.world.other;

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
 * Roots extend {@code BranchBlock} but must be excluded. {@code dynamictreesplus} cacti and
 * mushroom stems are excluded by default because their baked models use a different ModelData
 * contract.
 */
public final class DynamicTreesBranches {
  public static final String BRANCH_CLASS = "com.dtteam.dynamictrees.block.branch.BranchBlock";
  public static final String ROOTS_CLASS = "com.dtteam.dynamictrees.block.branch.BasicRootsBlock";
  public static final String TRUNK_SHELL_CLASS =
      "com.dtteam.dynamictrees.block.branch.TrunkShellBlock";
  public static final String SURFACE_ROOT_CLASS =
      "com.dtteam.dynamictrees.block.branch.SurfaceRootBlock";
  public static final String EXCLUDED_NAMESPACE = "dynamictreesplus";

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
    Block block = state.getBlock();
    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
    if (id != null && EXCLUDED_NAMESPACE.equals(id.getNamespace())) {
      return false;
    }
    return isBranchClass(state) || state.is(DYNAMICTREES_BRANCHES);
  }

  /**
   * True for a Dynamic Trees branch whose baked model cannot fit one Voxy cell (radius &gt; 8).
   * Unlike {@link #isBranch} this ignores the {@code dynamictreesplus} namespace exclusion: the
   * synthetic bark cube only needs the model's particle sprite, not its ModelData contract.
   */
  public static boolean isThickBranch(BlockState state) {
    if (state == null || state.isAir() || radiusOf(state) <= MAX_CELL_RADIUS) {
      return false;
    }
    return isBranchClass(state) || state.is(DYNAMICTREES_BRANCHES);
  }

  /**
   * True when the block extends the DT {@code BranchBlock} class (roots excluded, namespace not
   * filtered). Use {@link #isBranch} for the variant-eligible predicate.
   */
  public static boolean isBranchBlockClass(BlockState state) {
    return state != null && !state.isAir() && isBranchClass(state);
  }

  private static boolean isBranchClass(BlockState state) {
    // Roots are BranchBlock subclasses and must not be treated as branches.
    for (Class<?> cls = state.getBlock().getClass();
        cls != null && cls != Object.class;
        cls = cls.getSuperclass()) {
      String name = cls.getName();
      if (ROOTS_CLASS.equals(name)) {
        return false;
      }
      if (BRANCH_CLASS.equals(name)) {
        return true;
      }
    }
    return false;
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
