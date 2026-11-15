package me.cortex.voxy.client.core.model;

import static me.cortex.voxy.client.core.model.BakedModelPayload.FALLBACK_NONE;
import static me.cortex.voxy.client.core.model.BakedModelPayload.RENDER_LAYER_CUTOUT;
import static me.cortex.voxy.client.core.model.BakedModelPayload.RENDER_LAYER_FLUID;
import static me.cortex.voxy.client.core.model.BakedModelPayload.RENDER_LAYER_OPAQUE;
import static me.cortex.voxy.client.core.model.BakedModelPayload.RENDER_LAYER_TRANSLUCENT;
import static me.cortex.voxy.client.core.model.ModelStore.MODEL_SIZE;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D;
import static org.lwjgl.opengl.GL11.*;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.bakery.Gl41OffscreenModelTextureBakery;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.other.DynamicTreesBranches;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

// Manages the storage and updating of model states, textures and colours

// Also has a fast long[] based metadata lookup for when the terrain mesher needs to look up the
// face occlusion data

// TODO: support more than 65535 states, what should actually happen is a blockstate is registered,
// the model data is generated, then compared
// to all other models already loaded, if it is a duplicate, create a mapping from the id to the
// already loaded id, this will help with meshing aswell
// as leaves and such will be able to be merged

// TODO: NOTE!!! is it worth even uploading as a 16x16 texture, since automatic lod selection...
// doing 8x8 textures might be perfectly ok!!!
// this _quarters_ the memory requirements for the texture atlas!!! WHICH IS HUGE saving
public class ModelFactory {
  public static final int MODEL_TEXTURE_SIZE = 16;
  public static final int LAYERS = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE);

  /** Model ids are packed into 16 bits of the per-quad data and index a 256x256 model atlas. */
  public static final int MAX_MODEL_ID_COUNT = 1 << 16;

  // Dynamic Trees LOD0 variants are capped so a world with many species/connections cannot exhaust
  // the 16-bit model id space used by every other block. Once capped, meshing keeps the base model.
  // Radius keys collapse most branch contexts to one key per (state, connection radii) shape, which
  // covers large forests within this cap.
  private static final int MAX_VARIANT_MODEL_COUNT = 1 << 15; // 32768 distinct variants
  private static final int BASE_MODEL_ID_RESERVE = 1 << 13; // 8192 ids kept for normal blocks

  /** Returned by {@link #getVariantModelId} while the variant bake is queued/running. */
  public static final int VARIANT_MODEL_NOT_READY = -1;

  /** Returned by {@link #getVariantModelId} when the caller must fall back to the base model. */
  public static final int VARIANT_MODEL_UNAVAILABLE = -2;

  /**
   * Per-position model key for Dynamic Trees branches: the six neighbour block state ids in {@link
   * Direction#get3DDataValue()} order (DOWN, UP, NORTH, SOUTH, WEST, EAST).
   */
  private record VariantKey(
      int blockId, int down, int up, int north, int south, int west, int east) {}

  /** A thick-trunk shell cell: core block state id plus the cell offset (x/z in -1..1). */
  /** {@code capBits} bit0 = UP end cap, bit1 = DOWN end cap. */
  private record ShellModelKey(int coreBlockId, int dx, int dz, int capBits) {}

  /**
   * Fail-safe solid bark base cube for a {@code trunk_shell} cell whose prism piece cannot be
   * resolved for a data/model-absence reason. {@code shellBlockId} is the shell state's own block
   * id (its model particle icon is the first sprite source and keys the one-shot oak warning);
   * {@code barkSourceBlockId} is the resolved thick core or a nearby Dynamic Trees branch state (0
   * = no source, use the deterministic oak bark fallback).
   */
  private record ShellSolidBaseKey(int shellBlockId, int barkSourceBlockId) {}

  /**
   * A Dynamic Trees surface-root variant. {@code sideRadii} packs the four connection radii into
   * 4-bit fields (DT radii reach 8; 3 bits collapsed 8 into 7) and {@code sideLevels} the four DT
   * connection levels (2 bits each, 0=none, 1=MID, 2=LOW, 3=HIGH); the remaining fields describe an
   * adjacent thick-trunk core whose per-cell prism bark must be composited back. The exact DT root
   * model is baked from a captured 3x3x3 state cube stored per key.
   */
  private record RootCrownKey(
      int blockId, int sideRadii, int sideLevels, int coreBlockId, int coreDx, int coreDz) {}

  // TODO: replace the fluid BlockState with a client model id integer of the fluidState, requires
  // looking up
  // the fluid state in the mipper
  private record ModelEntry(
      ColourDepthTextureData down,
      ColourDepthTextureData up,
      ColourDepthTextureData north,
      ColourDepthTextureData south,
      ColourDepthTextureData west,
      ColourDepthTextureData east,
      int fluidBlockStateId,
      int tintingColour,
      int renderLayer,
      int fallbackReason,
      int customBlockStateId) {
    public ModelEntry(
        ColourDepthTextureData[] textures,
        int fluidBlockStateId,
        int tintingColour,
        int renderLayer,
        int fallbackReason,
        int customBlockStateId) {
      this(
          textures[0],
          textures[1],
          textures[2],
          textures[3],
          textures[4],
          textures[5],
          fluidBlockStateId,
          tintingColour,
          renderLayer,
          fallbackReason,
          customBlockStateId);
    }
  }

  private final Biome DEFAULT_BIOME =
      Minecraft.getInstance()
          .level
          .registryAccess()
          .lookupOrThrow(Registries.BIOME)
          .getOrThrow(Biomes.PLAINS)
          .value();

  public final SoftwareModelTextureBakery bakery2;
  private final Gl41OffscreenModelTextureBakery gl41OffscreenBakery;
  private final long bakeScratchBuffer =
      MemoryUtil.nmemAlloc(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 8 * 6);

  // Model data might also contain a constant colour if the colour resolver produces a constant
  // colour, this saves space in the
  // section buffer reverse indexing

  // model data also contains if a face should be randomly rotated,flipped etc to get rid of moire
  // effect
  // this would be done in the fragment shader

  // The Meta-cache contains critical information needed for meshing, colour provider bit, per-face
  // = is empty, has alpha, is solid, full width, full height
  // alpha means that some pixels have alpha values and belong in the translucent rendering layer,
  // is empty means that the face is air/shouldent be rendered as there is nothing there
  // is solid means that every pixel is fully opaque
  // full width, height, is if the blockmodel dimentions occupy a full block, e.g. comparator, some
  // faces do some dont and some only in a specific axis

  // FIXME: the issue is e.g. leaves are translucent but the alpha value is used to colour the
  // leaves, so a block can have alpha but still be only made up of transparent or opaque pixels
  // will need to find a way to send this info to the shader via the material, if it is in the
  // opaque phase render as transparent with blending shiz

  // TODO: ADD an occlusion mask that can be queried (16x16 pixels takes up 4 longs) this mask shows
  // what pixels are exactly occluded at the edge of the block
  // so that full block occlusion can work nicely

  // TODO: what might work maybe, is that all the transparent pixels should be set to the average of
  // the other pixels
  // that way the block is always "fully occluding" (if the block model doesnt cover the entire
  // thing), maybe
  // this has some issues with quad merging
  // TODO: ACTUALLY, full out all the transparent pixels that are _within_ the bounding box of the
  // model
  // this will mean that when quad merging and rendering, the transparent pixels of the block where
  // there shouldent be
  // might still work???

  // this has an issue with scaffolding i believe tho, so maybe make it a probability to render???
  // idk
  private final long[] metadataCache;
  private final int[] fluidStateLUT;

  // Provides a map from id -> model id as multiple ids might have the same internal model id
  private final int[] idMappings;
  private final Object2IntOpenHashMap<ModelEntry> modelTexture2id = new Object2IntOpenHashMap<>();

  // Contains the set of all block ids that are currently inflight/being baked
  // this is required due to "async" nature of gpu feedback
  private final IntOpenHashSet blockStatesInFlight = new IntOpenHashSet();
  private final ReentrantLock blockStatesInFlightLock = new ReentrantLock();

  private final List<Biome> biomes = new ArrayList<>();
  private final List<Pair<Integer, BlockState>> modelsRequiringBiomeColours = new ArrayList<>();

  private static final ObjectSet<BlockState> LOGGED_SELF_CULLING_WARNING =
      new ObjectOpenHashSet<>();

  private final Mapper mapper;
  private final ModelStore storage;
  private final ModelOutputSink outputSink;

  private final ConcurrentLinkedDeque<BlockBake> bakeQueue = new ConcurrentLinkedDeque<>();

  private final ConcurrentLinkedDeque<ResultUploader> uploadResults = new ConcurrentLinkedDeque<>();

  // Dynamic Trees LOD0 variants. The map is the single source of truth for a variant's state:
  // absent => never requested, VARIANT_MODEL_NOT_READY => queued/in flight,
  // VARIANT_MODEL_UNAVAILABLE
  // => permanent fallback to the base model, any other value => baked model id.
  private final ConcurrentHashMap<VariantKey, Integer> variantModelIds = new ConcurrentHashMap<>();
  private final ConcurrentLinkedDeque<VariantKey> variantBakeQueue = new ConcurrentLinkedDeque<>();
  private final AtomicInteger variantInflightCount = new AtomicInteger();
  private final AtomicInteger variantActiveCount = new AtomicInteger();
  private final AtomicInteger variantRequestCount = new AtomicInteger();
  private final AtomicInteger variantFailureCount = new AtomicInteger();
  private final AtomicInteger variantSuccessCount = new AtomicInteger();
  private final AtomicInteger branchVariantRequestCount = new AtomicInteger();
  private final AtomicInteger branchVariantSuccessCount = new AtomicInteger();
  private final AtomicInteger rootVariantRequestCount = new AtomicInteger();
  private final AtomicInteger rootVariantSuccessCount = new AtomicInteger();
  private final AtomicInteger variantDedupeBaseCount = new AtomicInteger();
  private final AtomicInteger variantNoPixelCount = new AtomicInteger();
  private final AtomicInteger variantNoQuadCount = new AtomicInteger();
  private final AtomicInteger variantExceptionCount = new AtomicInteger();
  private final AtomicBoolean variantBakeActiveLogged = new AtomicBoolean();
  private final AtomicBoolean variantSummaryLogged = new AtomicBoolean();
  // Bounded retries for transient variant bake exceptions (key -> attempts).
  private final ConcurrentHashMap<VariantKey, Integer> variantAttempts = new ConcurrentHashMap<>();
  // Upload-loop failure logging is rate limited so a broken atlas cannot spam the log.
  private final AtomicInteger uploadFailureLogCount = new AtomicInteger();
  private final AtomicInteger shellFailureLogCount = new AtomicInteger();

  // Async-sink (gl41metal) publications are held until MaterialStore.drainUploads wrote the atlas
  // tiles; publishing earlier lets sections mesh against unwritten slots (black/white cells).
  // Holds ModelPublishUpload entries and multi-plane RootPlanePublish owners.
  private final ConcurrentLinkedDeque<ResultUploader> pendingSinkVariantPublishes =
      new ConcurrentLinkedDeque<>();

  private volatile boolean variantsCapped = false;

  // Cached per-block-id Dynamic Trees branch classification (0 unknown, 1 branch, 2 not a branch).
  // Confined to the 20-bit Mapper block id space, same bound as idMappings.
  private final byte[] dynamicTreesBranchCache = new byte[1 << 20];
  private final byte[] thickBranchCache = new byte[1 << 20];
  private final byte[] trunkShellCache = new byte[1 << 20];
  private final byte[] anyBranchCache = new byte[1 << 20];

  // Synthetic full-bark-cube models for Dynamic Trees thick branch states (radius > 8) and
  // DynamicTreesPlus mushroom stems (on the neighbour-less base path), see thickBranchModelFor and
  // mushroomStemModelFor. A failed sprite lookup is not retained, so it is retried on a later call.
  private final ConcurrentHashMap<BlockState, Optional<BakedModel>> thickBranchSolidModels =
      new ConcurrentHashMap<>();

  // Per-shell-cell models of the thick-trunk prism, keyed by core state + cell offset. Baked
  // together with the core's solid cube and patched into trunk_shell cells at LOD0 meshing time.
  private final ConcurrentHashMap<ShellModelKey, Integer> shellModelIds = new ConcurrentHashMap<>();

  // Fail-safe solid bark cubes patched into trunk_shell cells whose prism cannot be resolved
  // because of data/model absence. The cube is the cell's only model in that case (never an
  // overlay), so it cannot z-fight with a successfully patched prism. Derived models are baked
  // lazily on the bakery thread like branch/root variants.
  private final ConcurrentHashMap<ShellSolidBaseKey, Integer> shellSolidBaseModelIds =
      new ConcurrentHashMap<>();
  private final ConcurrentLinkedDeque<ShellSolidBaseKey> shellSolidBaseBakeQueue =
      new ConcurrentLinkedDeque<>();
  private final AtomicInteger shellSolidBaseFailureCount = new AtomicInteger();
  // One-shot warning per shell block id when the bark sprite had to fall back to oak.
  private final Set<Integer> shellSolidBaseOakWarned = ConcurrentHashMap.newKeySet();

  // Dynamic Trees surface-root variants (3x3x3 neighbour context). Patched into LOD0 sections.
  private final ConcurrentHashMap<RootCrownKey, Integer> rootVariantModelIds =
      new ConcurrentHashMap<>();
  // Stage 1 option A scaffolding: base root model id -> plane model ids. Populated only when
  // DT_ROOT_MULTIPLANE is on; for now each key maps to a single plane (its own model id).
  private final ConcurrentHashMap<Integer, int[]> rootPlaneModelIds = new ConcurrentHashMap<>();
  // Key -> most recently published base model id. failRootVariant only receives the key, so this
  // lets it drop the corresponding plane registry entry. Also flag-gated.
  private final ConcurrentHashMap<RootCrownKey, Integer> rootBaseModelIds =
      new ConcurrentHashMap<>();
  // Representative 3x3x3 state cube per root key, captured on the first request. The exact DT
  // root model recomputes RootConnections from it; any context with the same key is geometrically
  // identical.
  private final ConcurrentHashMap<RootCrownKey, BlockState[]> rootCubes = new ConcurrentHashMap<>();
  private final ConcurrentLinkedDeque<RootCrownKey> rootVariantBakeQueue =
      new ConcurrentLinkedDeque<>();
  private final AtomicInteger rootVariantInflight = new AtomicInteger();
  private final AtomicInteger rootVariantActive = new AtomicInteger();
  private static final int MAX_ROOT_VARIANT_MODEL_COUNT = 1 << 14;
  private final AtomicBoolean rootVariantCapLogged = new AtomicBoolean();
  private final AtomicBoolean rootMultiplaneCapLogged = new AtomicBoolean();
  private final AtomicBoolean rootVariantActiveLogged = new AtomicBoolean();
  private static final AtomicBoolean ROOT_FACE_DIAG = new AtomicBoolean();
  // Stage 2a multi-plane root diagnostics: number of bakes that took the band branch and the
  // total planes they produced. Flag-gated: stays at 0 when DT_ROOT_MULTIPLANE is off.
  private final AtomicInteger rootMultiplaneBakes = new AtomicInteger();
  private final AtomicInteger rootMultiplanePlanes = new AtomicInteger();
  private final AtomicBoolean rootMultiplaneLogged = new AtomicBoolean();

  /**
   * gl46 only: encode the per-pixel indentation of root faces into the model-texture alpha and flag
   * the face, so the terrain shader can displace those fragments. Other models and the GL41 backend
   * keep the old single-indentation path. Disable for A/B with {@code
   * -Dvoxy.dtRootPerPixelDepth=false}.
   */
  private static final boolean PER_PIXEL_ROOT_DEPTH =
      !"false".equalsIgnoreCase(System.getProperty("voxy.dtRootPerPixelDepth", "true"));

  /**
   * Root model source. Default: Dynamic Trees' exact SurfaceRootBlockBakedModel, which only works
   * because the per-pixel depth map keeps its multiple planes apart. {@code
   * -Dvoxy.dtRootModel=synthetic} switches back to the single-level plus pad for A/B and rollback.
   */
  private static final boolean SYNTHETIC_ROOT_PAD =
      "synthetic".equalsIgnoreCase(System.getProperty("voxy.dtRootModel", "exact"));

  /**
   * Stage 1 scaffolding for option A root impostors: a root face may later bake several depth-band
   * planes, each exported as its own ordinary model id. This stage only maintains an inert id
   * registry (always exactly one plane per published key); Stage 2 will populate the N plane ids.
   * Default off; with the flag off the registry is never populated and output is unchanged.
   */
  private static final boolean DT_ROOT_MULTIPLANE =
      "true".equalsIgnoreCase(System.getProperty("voxy.dtRootMultiplane", "false"));

  private static final int DT_ROOT_MAX_PLANES =
      Math.max(1, Math.min(3, Integer.getInteger("voxy.dtRootMaxPlanes", 3)));

  /**
   * Boundary-cap filtering for Dynamic Trees branch variants. {@code all} (the default) restores
   * the stable-candidate behaviour and drops every small boundary-plane cap; {@code per-side} drops
   * a cap only toward a connected branch neighbour whose radius is at least the self radius; {@code
   * none} keeps every cap (the old {@code -Dvoxy.dtBranchCapFilter=false}).
   *
   * <p>Select a mode with {@code -Dvoxy.dtBranchCapFilter=all|per-side|none}. For compatibility
   * {@code true} maps to {@code per-side} and {@code false} to {@code none}; an unknown value warns
   * once and falls back to {@code all}.
   */
  private enum BranchCapFilterMode {
    ALL("all"),
    PER_SIDE("per-side"),
    NONE("none");

    private final String id;

    BranchCapFilterMode(String id) {
      this.id = id;
    }
  }

  private static final BranchCapFilterMode BRANCH_CAP_FILTER_MODE = parseBranchCapFilterMode();
  private static final AtomicBoolean BRANCH_CAP_FILTER_LOG = new AtomicBoolean();

  private static BranchCapFilterMode parseBranchCapFilterMode() {
    String raw = System.getProperty("voxy.dtBranchCapFilter");
    if (raw == null) {
      return BranchCapFilterMode.ALL;
    }
    return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "all" -> BranchCapFilterMode.ALL;
      case "per-side", "per_side" -> BranchCapFilterMode.PER_SIDE;
      case "none" -> BranchCapFilterMode.NONE;
      case "true" -> BranchCapFilterMode.PER_SIDE;
      case "false" -> BranchCapFilterMode.NONE;
      default -> {
        // Static initialisation runs once per JVM, so this warning is one-shot by construction.
        Logger.warn(
            "Unknown -Dvoxy.dtBranchCapFilter value '"
                + raw
                + "' (expected all|per-side|none), using all");
        yield BranchCapFilterMode.ALL;
      }
    };
  }

  /** Cap filter mode id for logs: {@code all}, {@code per-side} or {@code none}. */
  public static String branchCapFilterMode() {
    return BRANCH_CAP_FILTER_MODE.id;
  }

  private static void logBranchCapFilterMode() {
    if (BRANCH_CAP_FILTER_LOG.compareAndSet(false, true)) {
      Logger.info("DT branch cap filter mode=" + BRANCH_CAP_FILTER_MODE.id);
    }
  }

  /**
   * Branch variants use MAX face depth: at radius tapers/junctions AVG smears the mixed planes and
   * opens see-through wedges between adjacent cells. MAX is computed once at bake time and is
   * static (no per-pixel depth). Roll back with {@code -Dvoxy.dtBranchDepthMode=avg} (or {@code
   * min}).
   */
  private static final int BRANCH_DEPTH_MODE = branchDepthMode();

  private static int branchDepthMode() {
    String mode =
        System.getProperty("voxy.dtBranchDepthMode", "max").toLowerCase(java.util.Locale.ROOT);
    return switch (mode) {
      case "avg" -> TextureUtils.DEPTH_MODE_AVG;
      case "min" -> TextureUtils.DEPTH_MODE_MIN;
      default -> TextureUtils.DEPTH_MODE_MAX;
    };
  }

  private final ConcurrentHashMap<RootCrownKey, Integer> rootVariantAttempts =
      new ConcurrentHashMap<>();
  private final byte[] surfaceRootCache = new byte[1 << 20];

  // Non-capping model id high-water mark; read by worker threads for the variant id reserve check.
  private final AtomicInteger modelIdCount = new AtomicInteger();

  // Fabric (and any loader without per-position ModelData) cannot change geometry from neighbours;
  // variant bakes would only waste ids/CPU and dedupe back to the base model.
  private final boolean variantBakingSupported = ModelQuadsAccess.get().supportsNeighbourContext();

  private Object2IntMap<BlockState> customBlockStateIdMapping;

  // TODO: NOTE!!! is it worth even uploading as a 16x16 texture, since automatic lod selection...
  // doing 8x8 textures might be perfectly ok!!!
  // this _quarters_ the memory requirements for the texture atlas!!! WHICH IS HUGE saving
  public ModelFactory(Mapper mapper, ModelStore storage) {
    this(mapper, storage, null);
  }

  public ModelFactory(Mapper mapper, ModelStore storage, ModelOutputSink outputSink) {
    logBranchCapFilterMode();
    this.mapper = mapper;
    this.storage = storage;
    this.outputSink = outputSink;
    if (storage != null) {
      // gl46 path: software rasterizer bakery (this repo's baseline behaviour)
      this.bakery2 = new SoftwareModelTextureBakery();
      this.bakery2.setupTexture();
      this.gl41OffscreenBakery = null;
    } else if (outputSink != null) {
      // gl41metal sink path: GL offscreen bakery (reference implementation)
      this.bakery2 = null;
      this.gl41OffscreenBakery = new Gl41OffscreenModelTextureBakery();
    } else {
      throw new IllegalStateException(
          "ModelFactory requires either a ModelStore or a ModelOutputSink");
    }

    this.metadataCache = new long[1 << 16];
    this.fluidStateLUT = new int[1 << 16];
    this.idMappings = new int[1 << 20]; // Max of 1 million blockstates mapping to 65k model states
    Arrays.fill(this.idMappings, -1);
    Arrays.fill(this.fluidStateLUT, -1);

    this.modelTexture2id.defaultReturnValue(-1);
    this.addEntry(0); // Add air as the first entry
  }

  public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
    this.customBlockStateIdMapping = mapping;
  }

  private int getCustomBlockStateId(BlockState blockState) {
    if (this.customBlockStateIdMapping != null
        && this.customBlockStateIdMapping.containsKey(blockState)) {
      return this.customBlockStateIdMapping.getInt(blockState);
    }
    return 0;
  }

  private static final record BlockBake(int blockId, BlockState state) {}

  public boolean addEntry(int blockId) {
    if (this.gl41OffscreenBakery != null) {
      return this.addGl41OffscreenBakedEntry(blockId);
    }

    if (this.idMappings[blockId] != -1) {
      return false;
    }
    // We are (probably) going to be baking the block id
    // check that it is currently not inflight, if it is, return as its already being baked
    // else add it to the flight as it is going to be baked
    this.blockStatesInFlightLock.lock();
    if (!this.blockStatesInFlight.add(blockId)) {
      this.blockStatesInFlightLock.unlock();
      // Block baking is already in-flight
      return false;
    }
    this.blockStatesInFlightLock.unlock();

    VarHandle.loadLoadFence();

    // We need to get it twice cause of threading
    if (this.idMappings[blockId] != -1) {
      return false;
    }

    var blockState = this.mapper.getBlockStateFromBlockId(blockId);

    if (blockState.getBlock() instanceof StairBlock sb) {
      if (sb.baseState.hasProperty(BlockStateProperties.WATERLOGGED)) {
        blockState =
            sb.baseState.setValue(
                BlockStateProperties.WATERLOGGED,
                blockState.getValue(BlockStateProperties.WATERLOGGED));
      } else {
        blockState = sb.baseState;
      }
    }

    // Before we enqueue the baking of this blockstate, we must check if it has a fluid state
    // associated with it
    // if it does, we must ensure that it is (effectivly) baked BEFORE we bake this blockstate
    boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
    if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
      // Insert into the fluid LUT
      var fluidState = blockState.getFluidState().createLegacyBlock();

      int fluidStateId = this.mapper.getIdForBlockState(fluidState);

      if (this.idMappings[fluidStateId] == -1) {
        // Dont have to check for inflight as that is done recursively :p

        // This is a hack but does work :tm: due to how the download stream is setup
        // it should enforce that the fluid state is processed before our blockstate
        addEntry(fluidStateId);
      }
    }
    this.bakeQueue.add(new BlockBake(blockId, blockState));
    return true;
  }

  private boolean addGl41OffscreenBakedEntry(int blockId) {
    if (this.idMappings[blockId] != -1) {
      return false;
    }

    this.blockStatesInFlightLock.lock();
    if (!this.blockStatesInFlight.add(blockId)) {
      this.blockStatesInFlightLock.unlock();
      return false;
    }
    this.blockStatesInFlightLock.unlock();

    VarHandle.loadLoadFence();

    if (this.idMappings[blockId] != -1) {
      this.blockStatesInFlightLock.lock();
      this.blockStatesInFlight.remove(blockId);
      this.blockStatesInFlightLock.unlock();
      return false;
    }

    var blockState = this.mapper.getBlockStateFromBlockId(blockId);
    boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
    if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
      var fluidState = blockState.getFluidState().createLegacyBlock();
      int fluidStateId = this.mapper.getIdForBlockState(fluidState);
      if (this.idMappings[fluidStateId] == -1) {
        addEntry(fluidStateId);
      }
    }

    BakedModel thickBranchModel = this.thickBranchModelFor(blockState);
    // Mushroom stems need the solid bark cube on the neighbour-less base bake: their real model
    // bakes no usable geometry without ModelData. Chosen once, passed to the single bake call.
    BakedModel baseModel = this.mushroomStemModelFor(blockState);
    if (baseModel == null) {
      baseModel = thickBranchModel;
    }
    var result = this.gl41OffscreenBakery.bake(blockState, null, baseModel);
    var upload =
        this.processTextureBakeResult(
            blockId,
            blockState,
            result.faces(),
            result.shaded(),
            result.darkenedTextures(),
            null,
            result.fallbackReason());
    if (upload != null) {
      this.uploadResults.add(upload);
    }
    if (thickBranchModel != null) {
      this.bakeThickShells(blockId, blockState);
    }
    return true;
  }

  private ColourDepthTextureData[] readSoftwareBakeTextures() {
    ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];
    long ptr = this.bakeScratchBuffer;
    final int FACE_SIZE = MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
    for (int face = 0; face < 6; face++) {
      long faceDataPtr = ptr + (FACE_SIZE * 4) * face * 2;
      int[] colour = new int[FACE_SIZE];
      int[] depth = new int[FACE_SIZE];

      // Copy out colour
      for (int i = 0; i < FACE_SIZE; i++) {
        //// De-interpolate results
        // colour[i] = MemoryUtil.memGetInt(faceDataPtr + (i * 4 * 2));
        // depth[i] = MemoryUtil.memGetInt(faceDataPtr + (i * 4 * 2) + 4);

        long value = MemoryUtil.memGetLong(faceDataPtr + i * 8);
        colour[i] = (int) value;
        depth[i] = (int) (value >>> 32);
      }
      textureData[face] =
          new ColourDepthTextureData(colour, depth, MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);
    }
    return textureData;
  }

  private static RenderType layerFromSoftwareFlags(BlockState state, int flags) {
    RenderType layer = RenderType.solid();
    if ((flags & 4) != 0) {
      layer = RenderType.translucent();
    } else if ((flags & 8) != 0) {
      layer = RenderType.cutout();
    }
    if (state.is(BlockTags.LEAVES)) {
      layer = RenderType.solid();
    }
    return layer;
  }

  private boolean processModelResult() {
    var bake = this.bakeQueue.poll();
    if (bake == null) return false;

    BakedModel thickBranchModel = this.thickBranchModelFor(bake.state);
    // Same upfront override as the GL41 path: mushroom stems bake no usable geometry without
    // ModelData, so substitute the solid bark cube in this single bake instead of re-baking.
    BakedModel baseModel = this.mushroomStemModelFor(bake.state);
    if (baseModel == null) {
      baseModel = thickBranchModel;
    }
    int flags =
        baseModel == null
            ? this.bakery2.renderToOutput(bake.state, this.bakeScratchBuffer)
            : this.bakery2.renderToOutput(bake.state, this.bakeScratchBuffer, null, baseModel);

    var bakeResult =
        this.processTextureBakeResult(
            bake.blockId,
            bake.state,
            this.readSoftwareBakeTextures(),
            (flags & 1) != 0,
            (flags & 2) != 0,
            layerFromSoftwareFlags(bake.state, flags),
            FALLBACK_NONE);
    if (bakeResult != null) {
      this.uploadResults.add(bakeResult);
    }
    if (thickBranchModel != null) {
      this.bakeThickShells(bake.blockId, bake.state);
    }
    return !this.bakeQueue.isEmpty();
  }

  // Software bakery path: the model factory processor thread drains variant bakes alongside base
  // bakes. The sink (GL41) path must run variants on the render thread instead, see
  // processVariantBakeOnRenderThread.
  private boolean processVariantResult() {
    if (this.gl41OffscreenBakery != null) {
      return false;
    }
    VariantKey key = this.variantBakeQueue.poll();
    if (key != null) {
      this.bakeVariantSoftware(key);
      return true;
    }
    RootCrownKey rootKey = this.rootVariantBakeQueue.poll();
    if (rootKey != null) {
      this.bakeRootVariantSoftware(rootKey);
      return true;
    }
    ShellSolidBaseKey solidBaseKey = this.shellSolidBaseBakeQueue.poll();
    if (solidBaseKey != null) {
      this.bakeShellSolidBaseSoftware(solidBaseKey);
      return true;
    }
    return false;
  }

  /** Sink (gl41metal) path: called from ModelBakerySubsystem.tick on the render thread. */
  public boolean processVariantBakeOnRenderThread() {
    if (this.bakery2 != null) {
      return false;
    }
    VariantKey key = this.variantBakeQueue.poll();
    if (key != null) {
      this.bakeVariantGl41(key);
      return true;
    }
    RootCrownKey rootKey = this.rootVariantBakeQueue.poll();
    if (rootKey != null) {
      this.bakeRootVariantGl41(rootKey);
      return true;
    }
    ShellSolidBaseKey solidBaseKey = this.shellSolidBaseBakeQueue.poll();
    if (solidBaseKey != null) {
      this.bakeShellSolidBaseGl41(solidBaseKey);
      return true;
    }
    return false;
  }

  private BlockState[] resolveVariantNeighbours(VariantKey key) {
    BlockState[] neighbours = new BlockState[6];
    neighbours[0] = this.mapper.getBlockStateFromBlockId(key.down());
    neighbours[1] = this.mapper.getBlockStateFromBlockId(key.up());
    neighbours[2] = this.mapper.getBlockStateFromBlockId(key.north());
    neighbours[3] = this.mapper.getBlockStateFromBlockId(key.south());
    neighbours[4] = this.mapper.getBlockStateFromBlockId(key.west());
    neighbours[5] = this.mapper.getBlockStateFromBlockId(key.east());
    return neighbours;
  }

  /**
   * Boundary-cap side mask for a branch variant bake. Bit {@code i} ({@code i =} {@link
   * Direction#get3DDataValue()}, matching the {@code neighbours} array order) is set when that
   * side's cap may be dropped. In {@code all} mode every side is enabled and {@link
   * RootVertFilterBakedModel} removes only the small ({@code < 7.9} model unit) caps lying exactly
   * on a cell boundary plane, like the stable candidate; large boundary faces stay. In {@code
   * per-side} mode a side is set only when its neighbour resolves to a connected Dynamic Trees
   * branch that actually covers the boundary plane, so the quad on that boundary plane is an
   * internal face Voxy would drag into a visible fin. Air and unrelated blocks keep their caps, so
   * exposed trunk tops and sides stay closed. {@code none} keeps every cap and skips installing the
   * filter entirely.
   *
   * <p>In per-side mode only real branches ({@link DynamicTreesBranches#isBranch}, roots excluded)
   * whose radius is at least the self radius may drop a cap: roots and thinner neighbours don't
   * reliably cover the boundary plane, and dropping the cap there would leave an open end, so the
   * cap is kept.
   */
  private static int computeDropCapSidesMask(BlockState self, BlockState[] neighbours) {
    if (BRANCH_CAP_FILTER_MODE == BranchCapFilterMode.ALL) {
      // Stable-candidate behaviour: enable every side. The filter itself still keeps large
      // boundary faces and anything above maxY, so this cannot remove arbitrary geometry.
      return RootVertFilterBakedModel.ALL_SIDES_MASK;
    }
    if (BRANCH_CAP_FILTER_MODE == BranchCapFilterMode.NONE) {
      return 0;
    }
    int selfRadius = DynamicTreesBranches.radiusOf(self);
    if (selfRadius < 0) {
      // No radius property: the neighbour coverage cannot be proven, keep every cap.
      return 0;
    }
    int requiredRadius = Math.max(1, selfRadius);
    int mask = 0;
    for (int i = 0; i < 6; i++) {
      BlockState neighbour = neighbours[i];
      if (!DynamicTreesBranches.isBranch(neighbour)) {
        continue;
      }
      int neighbourRadius = DynamicTreesBranches.radiusOf(neighbour);
      if (neighbourRadius < 0 || neighbourRadius < requiredRadius) {
        continue;
      }
      mask |= 1 << i;
    }
    return mask;
  }

  private void bakeVariantSoftware(VariantKey key) {
    try {
      BlockState state = this.mapper.getBlockStateFromBlockId(key.blockId());
      BlockState[] neighbours = this.resolveVariantNeighbours(key);
      int dropCapSidesMask = computeDropCapSidesMask(state, neighbours);
      BakedModel branchModel = null;
      if (BRANCH_CAP_FILTER_MODE != BranchCapFilterMode.NONE) {
        try {
          BakedModel raw =
              Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
          if (raw != null) {
            branchModel = new RootVertFilterBakedModel(raw, 16.0f, dropCapSidesMask);
          }
        } catch (Throwable t) {
          branchModel = null;
        }
      }
      if (BRANCH_CAP_FILTER_MODE != BranchCapFilterMode.NONE && branchModel == null) {
        this.failVariant(key, "missing the branch model for the boundary-cap filter");
        return;
      }
      int flags =
          this.bakery2.renderToOutput(state, this.bakeScratchBuffer, neighbours, branchModel);
      RenderType layer = layerFromSoftwareFlags(state, flags);
      ColourDepthTextureData[] textureData = this.readSoftwareBakeTextures();
      int checkMode =
          layer == RenderType.solid()
              ? TextureUtils.WRITE_CHECK_STENCIL
              : TextureUtils.WRITE_CHECK_ALPHA;
      int writtenPixels = 0;
      for (ColourDepthTextureData face : textureData) {
        writtenPixels += TextureUtils.getWrittenPixelCount(face, checkMode);
      }
      if (writtenPixels == 0) {
        // Never install an empty variant model: the branch would disappear instead of falling back
        // to its base geometry.
        this.variantNoPixelCount.incrementAndGet();
        this.failVariant(
            key,
            "variant bake produced no pixels state="
                + state
                + " neighbours="
                + java.util.Arrays.toString(neighbours));
        return;
      }
      var upload =
          this.processTextureBakeResult(
              key.blockId(),
              state,
              textureData,
              (flags & 1) != 0,
              (flags & 2) != 0,
              layer,
              FALLBACK_NONE,
              key,
              null,
              null,
              null,
              null,
              null);
      if (upload != null) {
        this.uploadResults.add(upload);
      }
    } catch (Throwable t) {
      this.variantExceptionCount.incrementAndGet();
      this.failVariantTransient(key, t);
    } finally {
      this.variantInflightCount.decrementAndGet();
    }
  }

  private void bakeVariantGl41(VariantKey key) {
    try {
      BlockState state = this.mapper.getBlockStateFromBlockId(key.blockId());
      BlockState[] neighbours = this.resolveVariantNeighbours(key);
      int dropCapSidesMask = computeDropCapSidesMask(state, neighbours);
      BakedModel branchModel = null;
      if (BRANCH_CAP_FILTER_MODE != BranchCapFilterMode.NONE) {
        try {
          BakedModel raw =
              Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
          if (raw != null) {
            branchModel = new RootVertFilterBakedModel(raw, 16.0f, dropCapSidesMask);
          }
        } catch (Throwable t) {
          branchModel = null;
        }
      }
      if (BRANCH_CAP_FILTER_MODE != BranchCapFilterMode.NONE && branchModel == null) {
        this.failVariant(key, "missing the branch model for the boundary-cap filter");
        return;
      }
      var result = this.gl41OffscreenBakery.bake(state, neighbours, branchModel);
      if (result.fallbackReason() != FALLBACK_NONE) {
        this.variantNoQuadCount.incrementAndGet();
        this.failVariant(
            key, "variant bake failed with fallback reason " + result.fallbackReason());
        return;
      }
      // Resolve against the model that was actually baked (the cap-filter wrapper). Resolving
      // against the shaper's raw model could classify the entry cutout while the bake was solid.
      RenderType layer = PlantRenderLayerResolver.resolve(state, branchModel);
      int checkMode =
          layer == RenderType.solid()
              ? TextureUtils.WRITE_CHECK_STENCIL
              : TextureUtils.WRITE_CHECK_ALPHA;
      int writtenPixels = 0;
      for (ColourDepthTextureData face : result.faces()) {
        writtenPixels += TextureUtils.getWrittenPixelCount(face, checkMode);
      }
      if (writtenPixels == 0) {
        this.variantNoPixelCount.incrementAndGet();
        this.failVariant(
            key,
            "variant bake produced no pixels state="
                + state
                + " neighbours="
                + java.util.Arrays.toString(neighbours));
        return;
      }
      var upload =
          this.processTextureBakeResult(
              key.blockId(),
              state,
              result.faces(),
              result.shaded(),
              result.darkenedTextures(),
              layer,
              result.fallbackReason(),
              key,
              null,
              null,
              null,
              null,
              null);
      if (upload != null) {
        this.uploadResults.add(upload);
      }
    } catch (Throwable t) {
      this.variantExceptionCount.incrementAndGet();
      this.failVariantTransient(key, t);
    } finally {
      this.variantInflightCount.decrementAndGet();
    }
  }

  private void bakeRootVariantSoftware(RootCrownKey key) {
    try {
      if (!DTRootGeometryCache.ROOTS_ENABLED) {
        // Fail-safe: no root bake may run while the master switch is off (the finally still
        // releases the inflight slot if a stale key was queued before the switch was read).
        return;
      }
      BlockState state = this.mapper.getBlockStateFromBlockId(key.blockId());
      BlockState[] cube = this.rootCubes.get(key);
      BakedModel model = this.buildRootExactModel(key, state, cube);
      if (model == null || cube == null) {
        this.failRootVariant(key, "missing the surface-root model or 3x3x3 context");
        return;
      }
      // Always pass the built model as the override. The non-composite branch used to omit it,
      // so the bakery re-fetched the raw DT model and never applied the RootVertFilterBakedModel
      // wrapper (HIGH verts and boundary sleeve caps stayed in the bake and MIN turned them into
      // posts/plates). This is the wiring roots12 was supposed to have.
      int flags = this.bakery2.renderToOutputCube(state, this.bakeScratchBuffer, cube, model);
      RenderType layer = layerFromSoftwareFlags(state, flags);
      ColourDepthTextureData[] textureData = this.readSoftwareBakeTextures();
      int checkMode =
          layer == RenderType.solid()
              ? TextureUtils.WRITE_CHECK_STENCIL
              : TextureUtils.WRITE_CHECK_ALPHA;
      // gl46 per-pixel depth: the alpha channel carries the per-pixel indentation for root
      // faces. Must happen before processTextureBakeResult because the ModelEntry hash covers
      // the colour arrays. The GL41 bakery keeps the old flat path (see the faceData bit gate).
      if (PER_PIXEL_ROOT_DEPTH && layer != RenderType.translucent()) {
        TextureUtils.encodeRootPixelDepth(textureData, checkMode);
      }
      // Stage 2a: one plane per depth band. Depth is unaffected by the alpha encoding, so the
      // bands can be computed after it; maxBands also feeds the diagnostic log.
      int[] bandsPerFace = new int[textureData.length];
      int maxBands = 1;
      for (int f = 0; f < textureData.length; f++) {
        bandsPerFace[f] =
            TextureUtils.computeDepthBands(textureData[f], checkMode, DT_ROOT_MAX_PLANES).length;
        maxBands = Math.max(maxBands, bandsPerFace[f]);
      }
      boolean multiplane =
          DT_ROOT_MULTIPLANE
              && this.gl41OffscreenBakery == null
              && PER_PIXEL_ROOT_DEPTH
              && layer != RenderType.translucent()
              && maxBands >= 2;
      if (ROOT_FACE_DIAG.compareAndSet(false, true)) {
        StringBuilder sb = new StringBuilder("DT root variant face pixels");
        for (int f = 0; f < textureData.length; f++) {
          sb.append(' ')
              .append(f)
              .append('=')
              .append(TextureUtils.getWrittenPixelCount(textureData[f], checkMode));
        }
        sb.append(" bands=").append(java.util.Arrays.toString(bandsPerFace));
        sb.append(" planes=").append(maxBands);
        sb.append(" multiplane=").append(multiplane);
        Logger.info(sb.append(" key=").append(key).toString());
      }
      int writtenPixels = 0;
      for (ColourDepthTextureData face : textureData) {
        writtenPixels += TextureUtils.getWrittenPixelCount(face, checkMode);
      }
      if (writtenPixels == 0) {
        this.variantNoPixelCount.incrementAndGet();
        this.failRootVariant(key, "surface root variant bake produced no pixels");
        return;
      }
      if (multiplane) {
        var planes = TextureUtils.splitDepthBandPlanes(textureData, checkMode, DT_ROOT_MAX_PLANES);
        if (planes.length >= 2) {
          this.rootMultiplaneBakes.incrementAndGet();
          RootPlanePublish pending = new RootPlanePublish(key, new int[planes.length]);
          RootPlaneBakeContext ctx = new RootPlaneBakeContext(key);
          for (int p = 0; p < planes.length; p++) {
            var upload =
                this.processTextureBakeResult(
                    key.blockId(),
                    state,
                    planes[p],
                    (flags & 1) != 0,
                    (flags & 2) != 0,
                    layer,
                    FALLBACK_NONE,
                    null,
                    null,
                    key,
                    ctx,
                    pending,
                    null);
            if (ctx.failed) {
              this.failRootVariant(key, ctx.failure);
              return;
            }
            if (upload != null) {
              this.uploadResults.add(upload);
            }
          }
          if (ctx.planeCount != planes.length) {
            this.failRootVariant(key, "surface root plane bake produced an incomplete plane set");
            return;
          }
          System.arraycopy(ctx.planeIds, 0, pending.planeIds, 0, ctx.planeCount);
          // Queued after every plane upload: processUploads publishes the whole set atomically,
          // and only after the FIFO drained all of its planes.
          this.uploadResults.add(pending);
          this.rootMultiplanePlanes.addAndGet(planes.length);
          return;
        }
      }
      var upload =
          this.processTextureBakeResult(
              key.blockId(),
              state,
              textureData,
              (flags & 1) != 0,
              (flags & 2) != 0,
              layer,
              FALLBACK_NONE,
              null,
              null,
              key,
              null,
              null,
              null);
      if (upload != null) {
        this.uploadResults.add(upload);
      }
    } catch (Throwable t) {
      this.variantExceptionCount.incrementAndGet();
      this.failRootVariantTransient(key, t);
    } finally {
      this.rootVariantInflight.decrementAndGet();
    }
  }

  private void bakeRootVariantGl41(RootCrownKey key) {
    try {
      if (!DTRootGeometryCache.ROOTS_ENABLED) {
        // Fail-safe: no root bake may run while the master switch is off (the finally still
        // releases the inflight slot if a stale key was queued before the switch was read).
        return;
      }
      BlockState state = this.mapper.getBlockStateFromBlockId(key.blockId());
      BlockState[] cube = this.rootCubes.get(key);
      BakedModel model = this.buildRootExactModel(key, state, cube);
      if (model == null || cube == null) {
        this.failRootVariant(key, "missing the surface-root model or 3x3x3 context");
        return;
      }
      // Same wiring fix as the software path: always bake the model returned by
      // buildRootExactModel so the RootVertFilterBakedModel is actually applied.
      var result = this.gl41OffscreenBakery.bakeCube(state, cube, model);
      if (result.fallbackReason() != FALLBACK_NONE) {
        this.variantNoQuadCount.incrementAndGet();
        this.failRootVariant(
            key, "surface root variant failed with fallback reason " + result.fallbackReason());
        return;
      }
      // Resolve against the model that was actually baked (buildRootExactModel's wrapper), not
      // the shaper's raw model, so the classification matches the baked alpha-discard data.
      RenderType layer = PlantRenderLayerResolver.resolve(state, model);
      // TODO(Stage 3): add the depth-band plane split for the GL41 offscreen bakery. Stage 2a
      // keeps the GL41 root path single-plane; only the software (gl46) bakery emits planes.
      int checkMode =
          layer == RenderType.solid()
              ? TextureUtils.WRITE_CHECK_STENCIL
              : TextureUtils.WRITE_CHECK_ALPHA;
      if (ROOT_FACE_DIAG.compareAndSet(false, true)) {
        StringBuilder sb = new StringBuilder("DT root variant face pixels");
        for (int f = 0; f < result.faces().length; f++) {
          sb.append(' ')
              .append(f)
              .append('=')
              .append(TextureUtils.getWrittenPixelCount(result.faces()[f], checkMode));
        }
        Logger.info(sb.append(" key=").append(key).toString());
      }
      int writtenPixels = 0;
      for (ColourDepthTextureData face : result.faces()) {
        writtenPixels += TextureUtils.getWrittenPixelCount(face, checkMode);
      }
      if (writtenPixels == 0) {
        this.variantNoPixelCount.incrementAndGet();
        this.failRootVariant(key, "surface root variant bake produced no pixels");
        return;
      }
      var upload =
          this.processTextureBakeResult(
              key.blockId(),
              state,
              result.faces(),
              result.shaded(),
              result.darkenedTextures(),
              layer,
              result.fallbackReason(),
              null,
              null,
              key,
              null,
              null,
              null);
      if (upload != null) {
        this.uploadResults.add(upload);
      }
    } catch (Throwable t) {
      this.variantExceptionCount.incrementAndGet();
      this.failRootVariantTransient(key, t);
    } finally {
      this.rootVariantInflight.decrementAndGet();
    }
  }

  /**
   * Builds the model used for a surface-root variant: the exact Dynamic Trees {@code
   * SurfaceRootBlockBakedModel} driven by the captured 3x3x3 state cube. Root cells that replaced a
   * {@code trunk_shell} are composited with the trunk's per-cell prism bark so the trunk wall has
   * no notch.
   */
  private @Nullable BakedModel buildRootExactModel(
      RootCrownKey key, BlockState rootState, BlockState[] cube) {
    BakedModel rootModel;
    try {
      rootModel =
          Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(rootState);
    } catch (Throwable t) {
      Logger.warn("Could not resolve the model for surface root " + rootState, t);
      return null;
    }
    if (rootModel == null || cube == null) {
      return null;
    }
    BakedModel bakedRoot = rootModel;
    int rootRadius = DynamicTreesBranches.radiusOf(rootState);
    boolean grounded = DynamicTreesBranches.isGrounded(rootState);
    if (!SYNTHETIC_ROOT_PAD) {
      // Exact DT model. The per-pixel depth map (gl46) keeps its multiple planes apart, so the
      // real core, sleeves, boundary caps and DOWN faces can be baked. The filter only removes
      // the part of the HIGH vert columns above the cell top: quads with a vertex above y=16
      // are dropped, while the in-cell vert piece (max y=16) and all other geometry stay. Applied
      // to ungrounded roots too, which can keep the same out-of-cell HIGH vert geometry.
      bakedRoot = new RootVertFilterBakedModel(rootModel, 16.0f, false);
    } else if (grounded && rootRadius > 0) {
      TextureAtlasSprite sprite = rootModel.getParticleIcon();
      if (sprite != null) {
        int north = key.sideRadii() & 15;
        int east = (key.sideRadii() >> 4) & 15;
        int south = (key.sideRadii() >> 8) & 15;
        int west = (key.sideRadii() >> 12) & 15;
        bakedRoot = new SyntheticRootPadBakedModel(sprite, rootRadius, north, east, south, west);
      }
    }
    if (key.coreBlockId() == 0) {
      return bakedRoot;
    }
    BlockState coreState = this.mapper.getBlockStateFromBlockId(key.coreBlockId());
    BakedModel solid = this.thickBranchModelFor(coreState);
    if (solid instanceof SolidBarkBakedModel bark) {
      int coreRadius = DynamicTreesBranches.radiusOf(coreState);
      if (coreRadius > DynamicTreesBranches.MAX_CELL_RADIUS) {
        EnumSet<Direction> hiddenRootSides = EnumSet.noneOf(Direction.class);
        if (SYNTHETIC_ROOT_PAD) {
          // Only the single-level pad needs its trunk-facing quads hidden; the exact model under
          // the per-pixel depth map keeps the root and the wall at their own depths.
          if (key.coreDx() > 0) {
            hiddenRootSides.add(Direction.EAST);
          } else if (key.coreDx() < 0) {
            hiddenRootSides.add(Direction.WEST);
          }
          if (key.coreDz() > 0) {
            hiddenRootSides.add(Direction.SOUTH);
          } else if (key.coreDz() < 0) {
            hiddenRootSides.add(Direction.NORTH);
          }
        }
        return new CompositeRootShellBakedModel(
            bakedRoot,
            new ThickTrunkShellBakedModel(
                bark.sprite(), coreRadius, key.coreDx(), key.coreDz(), false, false),
            hiddenRootSides);
      }
    }
    return bakedRoot;
  }

  private void failRootVariant(RootCrownKey key, Object cause) {
    Integer previous = this.rootVariantModelIds.put(key, VARIANT_MODEL_UNAVAILABLE);
    if (previous != null && previous == VARIANT_MODEL_NOT_READY) {
      this.rootVariantActive.decrementAndGet();
    }
    // A terminally failed key never bakes; drop its captured cube and retry counter. Failed keys
    // are not bounded by the variant cap, so keeping them would leak a BlockState[27] per context.
    this.rootCubes.remove(key);
    this.rootVariantAttempts.remove(key);
    // Stage 1 plane registry cleanup: drop the mapping and the plane entry for the id this key
    // published earlier, if any (a key that never published leaves both maps untouched).
    Integer baseModelId = this.rootBaseModelIds.remove(key);
    if (baseModelId != null) {
      this.rootPlaneModelIds.remove(baseModelId);
    }
    if (this.variantFailureCount.incrementAndGet() <= 32) {
      Logger.warn("Failed to bake Dynamic Trees surface root variant " + key, cause);
    }
  }

  /** Mirrors the branch transient retry: up to 3 attempts before the key becomes permanent. */
  private void failRootVariantTransient(RootCrownKey key, Throwable cause) {
    int attempt = this.rootVariantAttempts.merge(key, 1, Integer::sum);
    if (attempt < 3) {
      if (this.rootVariantModelIds.remove(key, VARIANT_MODEL_NOT_READY)) {
        this.rootVariantActive.decrementAndGet();
      }
      if (this.variantFailureCount.incrementAndGet() <= 32) {
        Logger.warn(
            "Surface root variant attempt " + attempt + " failed for " + key + ", will retry",
            cause);
      }
    } else {
      this.failRootVariant(key, cause);
    }
  }

  private void logVariantActive(VariantKey key, int modelId) {
    if (this.variantBakeActiveLogged.compareAndSet(false, true)) {
      Logger.info(
          "Dynamic Trees LOD0 branch variants active, first variant "
              + key
              + " -> model id "
              + modelId);
    }
  }

  /**
   * Marks a variant as permanently unavailable. Deterministic failures (empty bake, no quads) use
   * this; transient exceptions go through {@link #failVariantTransient}.
   */
  private void failVariant(VariantKey key, Object cause) {
    this.markVariantUnavailable(key);
    if (this.variantFailureCount.incrementAndGet() <= 32) {
      Logger.warn(
          "Failed to bake Dynamic Trees branch variant " + key + ", using the base model", cause);
    }
  }

  /**
   * Retries a variant a few times before giving up: exception failures are frequently transient
   * (allocation hiccups, model-manager races), and a permanent -2 for the whole session would
   * silently degrade that branch position. The variant key is removed while retries remain, so the
   * next section meshing attempt re-requests it.
   */
  private void failVariantTransient(VariantKey key, Throwable cause) {
    int attempt = this.variantAttempts.merge(key, 1, Integer::sum);
    if (attempt < 3) {
      if (this.variantModelIds.remove(key, VARIANT_MODEL_NOT_READY)) {
        this.variantActiveCount.decrementAndGet();
      }
      if (this.variantFailureCount.incrementAndGet() <= 32) {
        Logger.warn(
            "Variant bake attempt "
                + attempt
                + " failed for "
                + key
                + ", will retry (base model meanwhile)",
            cause);
      }
    } else {
      this.failVariant(key, cause);
    }
  }

  /** Sets -2 and keeps the active-variant counter accurate (only NOT_READY entries count). */
  private void markVariantUnavailable(VariantKey key) {
    Integer previous = this.variantModelIds.put(key, VARIANT_MODEL_UNAVAILABLE);
    if (previous != null && previous == VARIANT_MODEL_NOT_READY) {
      this.variantActiveCount.decrementAndGet();
    }
  }

  private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();

  public void addBiome(Mapper.BiomeEntry biome) {
    this.biomeQueue.add(biome);
  }

  public void processAllThings() {
    var biomeEntry = this.biomeQueue.poll();
    while (biomeEntry != null) {
      var biomeRegistry =
          Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME);
      var mcbiomeEntry = biomeRegistry.getOptional(ResourceLocation.parse(biomeEntry.biome));
      if (!mcbiomeEntry.isPresent()) {
        Logger.error("Could not find biome: " + biomeEntry.biome + " using default");
      }
      var res = this.addBiome0(biomeEntry.id, mcbiomeEntry.orElse(DEFAULT_BIOME));
      if (res != null) {
        this.uploadResults.add(res);
      }
      biomeEntry = this.biomeQueue.poll();
    }

    // Alternate base and variant bakes so a large base-model backlog cannot starve LOD0 variants.
    while (true) {
      boolean processedBase = this.processModelResult();
      boolean processedVariant = this.processVariantResult();
      if (!processedBase && !processedVariant) {
        break;
      }
    }
  }

  public void processUploads() {
    List<ResultUploader> readyPublishes = new ArrayList<>();
    var upload = this.uploadResults.poll();
    if (upload != null) {
      if (this.storage != null) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
      }
      do {
        try {
          if (this.storage != null) {
            upload.upload(this.storage);
          } else {
            upload.upload(this.outputSink);
          }
        } catch (Throwable t) {
          // The bake already inserted its ModelEntry->id mapping, so a retry would dedupe to an
          // entry whose upload never happened. Fail terminal instead.
          if (upload instanceof ModelBakeResultUpload result) {
            if (result.owner != null) {
              // A multi-plane root plane failed to upload: never publish a partial plane set. The
              // owner publication is still queued behind the planes and fails the whole key.
              result.owner.uploadFailed = true;
            }
            if (result.variantKey != null) {
              this.failVariant(result.variantKey, t);
            } else if (result.shellModelKey != null) {
              this.shellModelIds.put(result.shellModelKey, VARIANT_MODEL_UNAVAILABLE);
            } else if (result.shellSolidBaseKey != null) {
              this.shellSolidBaseModelIds.put(result.shellSolidBaseKey, VARIANT_MODEL_UNAVAILABLE);
            } else if (result.rootVariantKey != null) {
              this.failRootVariant(result.rootVariantKey, t);
            }
          }
          if (this.uploadFailureLogCount.incrementAndGet() <= 8) {
            Logger.warn("Model texture upload failed", t);
          }
          upload.free();
          upload = this.uploadResults.poll();
          continue;
        }
        if (upload instanceof ModelBakeResultUpload result) {
          // Plane bakes carry an owner instead of a per-key publication: the owner entry that
          // follows them in the FIFO queue publishes the whole plane set at once.
          if (result.owner == null) {
            if (result.variantKey != null) {
              readyPublishes.add(
                  new ModelPublishUpload(
                      result.variantKey, null, null, null, result.variantModelId, false));
            } else if (result.shellModelKey != null) {
              readyPublishes.add(
                  new ModelPublishUpload(
                      null, result.shellModelKey, null, null, result.shellModelId, false));
            } else if (result.shellSolidBaseKey != null) {
              readyPublishes.add(
                  new ModelPublishUpload(
                      null,
                      null,
                      null,
                      result.shellSolidBaseKey,
                      result.shellSolidBaseModelId,
                      false));
            } else if (result.rootVariantKey != null) {
              readyPublishes.add(
                  new ModelPublishUpload(
                      null, null, result.rootVariantKey, null, result.rootVariantModelId, false));
            }
          }
        } else if (upload instanceof RootPlanePublish publish) {
          // The owner's plane uploads were drained earlier in this FIFO batch.
          readyPublishes.add(publish);
        } else if (upload instanceof ModelPublishUpload publish) {
          // The deduplicated model's upload was drained earlier in this FIFO queue.
          readyPublishes.add(publish);
        }
        upload.free();
        upload = this.uploadResults.poll();
      } while (upload != null);
      if (this.storage != null) {
        UploadStream.INSTANCE.commit();
      }
    }
    // Publish only after the synchronous batch is committed (and, for async sinks, only once the
    // sink reports its own queue drained).
    if (this.outputSink != null) {
      this.pendingSinkVariantPublishes.addAll(readyPublishes);
    } else {
      for (ResultUploader publish : readyPublishes) {
        this.publishModel(publish);
      }
    }
    this.publishSinkVariantsIfDrained();
    this.maybeLogVariantSummary();
  }

  private void publishModel(ResultUploader publish) {
    if (publish instanceof RootPlanePublish rootPlanePublish) {
      this.publishRootPlanes(rootPlanePublish);
      return;
    }
    if (!(publish instanceof ModelPublishUpload modelPublish)) {
      return;
    }
    if (modelPublish.variantKey != null) {
      this.variantModelIds.put(modelPublish.variantKey, modelPublish.modelId);
      // A dedupe to an existing (non-air) model still resolves the branch position correctly.
      this.branchVariantSuccessCount.incrementAndGet();
      if (modelPublish.dedupedToBase) {
        this.variantDedupeBaseCount.incrementAndGet();
      } else {
        this.variantSuccessCount.incrementAndGet();
        this.logVariantActive(modelPublish.variantKey, modelPublish.modelId);
      }
    } else if (modelPublish.shellKey != null) {
      this.shellModelIds.put(modelPublish.shellKey, modelPublish.modelId);
    } else if (modelPublish.shellSolidBaseKey != null) {
      this.shellSolidBaseModelIds.put(modelPublish.shellSolidBaseKey, modelPublish.modelId);
    } else if (modelPublish.rootKey != null) {
      this.rootVariantModelIds.put(modelPublish.rootKey, modelPublish.modelId);
      this.rootVariantSuccessCount.incrementAndGet();
      this.variantSuccessCount.incrementAndGet();
      if (DT_ROOT_MULTIPLANE) {
        // Stage 1: exactly one plane per key. Stage 2 will register the per-face band ids here.
        this.rootBaseModelIds.put(modelPublish.rootKey, modelPublish.modelId);
        this.rootPlaneModelIds.put(modelPublish.modelId, new int[] {modelPublish.modelId});
      }
    }
  }

  /**
   * Publishes a multi-plane root once every one of its plane uploads drained. The base model id
   * (and the {@code IdNotYetComputedException} retry resolution) stays {@link
   * #VARIANT_MODEL_NOT_READY} until this point, so meshing can never observe a partial plane set.
   */
  private void publishRootPlanes(RootPlanePublish publish) {
    if (publish.uploadFailed) {
      this.failRootVariant(publish.rootKey, "plane upload failed");
      return;
    }
    int base = publish.planeIds[0];
    // The variant id is the publish barrier: all plane registry writes must happen first, so a
    // meshing thread that observes the variant id also observes the plane list.
    this.rootBaseModelIds.put(publish.rootKey, base);
    this.rootPlaneModelIds.put(base, publish.planeIds);
    this.rootVariantModelIds.put(publish.rootKey, base);
    this.rootVariantSuccessCount.incrementAndGet();
    this.variantSuccessCount.incrementAndGet();
    if (this.rootMultiplaneLogged.compareAndSet(false, true)) {
      Logger.info(
          "Dynamic Trees multi-plane root active, first root "
              + publish.rootKey
              + " planes "
              + java.util.Arrays.toString(publish.planeIds)
              + " (bakes="
              + this.rootMultiplaneBakes.get()
              + ", planes="
              + this.rootMultiplanePlanes.get()
              + ")");
    }
  }

  /** Async sink backends only expose completion through their pending queue. */
  private void publishSinkVariantsIfDrained() {
    if (this.outputSink == null || this.outputSink.hasPendingUploads()) {
      return;
    }
    ResultUploader publish;
    while ((publish = this.pendingSinkVariantPublishes.poll()) != null) {
      this.publishModel(publish);
    }
  }

  /** One-shot settle summary; runs on the render thread after the upload queue drained. */
  private void maybeLogVariantSummary() {
    if (this.variantSummaryLogged.get()
        || this.variantRequestCount.get() == 0
        || !this.variantBakeQueue.isEmpty()
        || !this.rootVariantBakeQueue.isEmpty()
        || !this.shellSolidBaseBakeQueue.isEmpty()
        || this.variantInflightCount.get() != 0
        || this.rootVariantInflight.get() != 0
        || !this.uploadResults.isEmpty()
        || !this.pendingSinkVariantPublishes.isEmpty()) {
      return;
    }
    this.variantSummaryLogged.set(true);
    Logger.info(
        "Dynamic Trees LOD0 variants settled: "
            + this.variantRequestCount.get()
            + " requested, "
            + this.variantSuccessCount.get()
            + " baked, "
            + this.variantDedupeBaseCount.get()
            + " identical to base, "
            + this.variantFailureCount.get()
            + " failed ("
            + this.variantNoPixelCount.get()
            + " no pixels, "
            + this.variantNoQuadCount.get()
            + " no quads, "
            + this.variantExceptionCount.get()
            + " exceptions)");
  }

  private interface ResultUploader {
    void upload(ModelStore store);

    void upload(ModelOutputSink sink);

    void free();
  }

  /**
   * Bake-scope state for the Stage 2a multi-plane root path. One instance spans all plane bakes of
   * a single root key: {@code planeIds} collects each plane's model id in plane order, while {@code
   * failed}/{@code failure} abort the whole key (from the bake loop, once) instead of publishing a
   * partial plane set.
   */
  private static final class RootPlaneBakeContext {
    final RootCrownKey key;
    final int[] planeIds = new int[DT_ROOT_MAX_PLANES];
    int planeCount;
    boolean failed;
    Object failure;

    RootPlaneBakeContext(RootCrownKey key) {
      this.key = key;
    }
  }

  /**
   * Publication-only queue entry for a multi-plane root. Each plane bake uploads its own atlas slot
   * but publishes nothing; this owner is enqueued after every plane, so when it drains the whole
   * plane set is in the atlas and can be published atomically for the key.
   */
  private static final class RootPlanePublish implements ResultUploader {
    final RootCrownKey rootKey;
    final int[] planeIds;
    volatile boolean uploadFailed;

    RootPlanePublish(RootCrownKey rootKey, int[] planeIds) {
      this.rootKey = rootKey;
      this.planeIds = planeIds;
    }

    @Override
    public void upload(ModelStore store) {}

    @Override
    public void upload(ModelOutputSink sink) {}

    @Override
    public void free() {}
  }

  /**
   * Publication-only queue entry for a model that deduplicated to an existing model id. It rides
   * the same FIFO upload queue so the duplicate's atlas upload (enqueued earlier) completes first.
   */
  private static final class ModelPublishUpload implements ResultUploader {
    final @Nullable VariantKey variantKey;
    final @Nullable ShellModelKey shellKey;
    final @Nullable RootCrownKey rootKey;
    final @Nullable ShellSolidBaseKey shellSolidBaseKey;
    final int modelId;
    final boolean dedupedToBase;

    ModelPublishUpload(
        @Nullable VariantKey variantKey,
        @Nullable ShellModelKey shellKey,
        @Nullable RootCrownKey rootKey,
        @Nullable ShellSolidBaseKey shellSolidBaseKey,
        int modelId,
        boolean dedupedToBase) {
      this.variantKey = variantKey;
      this.shellKey = shellKey;
      this.rootKey = rootKey;
      this.shellSolidBaseKey = shellSolidBaseKey;
      this.modelId = modelId;
      this.dedupedToBase = dedupedToBase;
    }

    @Override
    public void upload(ModelStore store) {}

    @Override
    public void upload(ModelOutputSink sink) {}

    @Override
    public void free() {}
  }

  private static final class ModelBakeResultUpload implements ResultUploader {
    private final MemoryBuffer model = new MemoryBuffer(MODEL_SIZE).zero();
    private final MemoryBuffer texture =
        new MemoryBuffer((2L * 3 * computeSizeWithMips(MODEL_TEXTURE_SIZE)) * 4);

    public int modelId = -1;
    public int renderLayer = RENDER_LAYER_OPAQUE;
    public int fallbackReason = FALLBACK_NONE;
    public String sourceDescription = "";

    public int biomeUploadIndex = -1;
    public @Nullable MemoryBuffer biomeUpload;

    // Non-null for a Dynamic Trees variant. The variant id is published to variantModelIds only
    // after the atlas upload below, so section meshing can never reference an unwritten slot (which
    // would bake the section against an empty/white texture).
    public @Nullable VariantKey variantKey;
    public int variantModelId = -1;

    // Same deferral for a thick-trunk shell cell / surface-root variant model.
    public @Nullable ShellModelKey shellModelKey;
    public int shellModelId = -1;
    // Fail-safe solid bark base cube for a trunk_shell cell (same deferred publication).
    public @Nullable ShellSolidBaseKey shellSolidBaseKey;
    public int shellSolidBaseModelId = -1;
    public @Nullable RootCrownKey rootVariantKey;
    public int rootVariantModelId = -1;
    // Multi-plane roots: non-null on every plane upload. Planes never publish themselves; the
    // RootPlanePublish owner queued after them installs the whole plane set. A failed plane upload
    // sets owner.uploadFailed so publication fails the key instead of installing a partial set.
    public @Nullable RootPlanePublish owner;

    public void upload(ModelStore store) { // Uploads and resets for reuse
      this.upload(store.modelBuffer, store.modelColourBuffer, store.textures);
    }

    public void upload(ModelOutputSink sink) {
      sink.uploadModel(
          new BakedModelPayload(
              this.modelId,
              this.model,
              this.texture,
              this.biomeUploadIndex,
              this.biomeUpload,
              this.renderLayer,
              this.fallbackReason,
              this.sourceDescription));
      this.modelId = -1;
      this.renderLayer = RENDER_LAYER_OPAQUE;
      this.fallbackReason = FALLBACK_NONE;
      this.sourceDescription = "";
    }

    public void upload(
        GlBuffer modelBuffer,
        GlBuffer colourBuffer,
        GlTexture atlas) { // Uploads and resets for reuse
      this.model.cpyTo(
          UploadStream.INSTANCE.upload(modelBuffer, (long) this.modelId * MODEL_SIZE, MODEL_SIZE));
      if (this.biomeUploadIndex != -1) {
        this.biomeUpload.cpyTo(
            UploadStream.INSTANCE.upload(
                colourBuffer, this.biomeUploadIndex * 4L, this.biomeUpload.size));
        this.biomeUploadIndex = -1;
        this.biomeUpload.free();
        this.biomeUpload = null;
      }

      int X = (this.modelId & 0xFF) * MODEL_TEXTURE_SIZE * 3;
      int Y = ((this.modelId >> 8) & 0xFF) * MODEL_TEXTURE_SIZE * 2;

      long cAddr = this.texture.address;
      for (int lvl = 0; lvl < LAYERS; lvl++) {
        nglTextureSubImage2D(
            atlas.id,
            lvl,
            X >> lvl,
            Y >> lvl,
            (MODEL_TEXTURE_SIZE * 3) >> lvl,
            (MODEL_TEXTURE_SIZE * 2) >> lvl,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            cAddr);
        cAddr += (MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 3 * 2 * 4) >> (lvl << 1);
      }

      this.modelId = -1;
    }

    public void free() {
      this.model.free();
      this.texture.free();
      if (this.biomeUpload != null) {
        this.biomeUpload.free();
      }
    }
  }

  // layer == null means "resolve from the model/registered block render types through
  // PlantRenderLayerResolver" (sink/gl41metal path); the gl46 path passes the layer derived from
  // the software bakery.
  private ModelBakeResultUpload processTextureBakeResult(
      int blockId,
      BlockState blockState,
      ColourDepthTextureData[] textureData,
      boolean isShaded,
      boolean darkenedTinting,
      RenderType layer,
      int fallbackReason) {
    return this.processTextureBakeResult(
        blockId,
        blockState,
        textureData,
        isShaded,
        darkenedTinting,
        layer,
        fallbackReason,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  // variantKey/shellKey/rootKey/shellSolidBaseKey == null is the normal per-block bake. A non-null
  // key installs the result into the corresponding registry instead of idMappings[blockId], so
  // base ids are never overwritten.
  private ModelBakeResultUpload processTextureBakeResult(
      int blockId,
      BlockState blockState,
      ColourDepthTextureData[] textureData,
      boolean isShaded,
      boolean darkenedTinting,
      RenderType layer,
      int fallbackReason,
      @Nullable VariantKey variantKey) {
    return this.processTextureBakeResult(
        blockId,
        blockState,
        textureData,
        isShaded,
        darkenedTinting,
        layer,
        fallbackReason,
        variantKey,
        null,
        null,
        null,
        null,
        null);
  }

  // planeContext is non-null only for Stage 2a multi-plane root plane bakes; owner is the pending
  // RootPlanePublish those planes belong to (set on the returned upload so processUploads does not
  // publish an individual plane). shellSolidBaseKey is the trunk_shell fail-safe bark cube.
  private ModelBakeResultUpload processTextureBakeResult(
      int blockId,
      BlockState blockState,
      ColourDepthTextureData[] textureData,
      boolean isShaded,
      boolean darkenedTinting,
      RenderType layer,
      int fallbackReason,
      @Nullable VariantKey variantKey,
      @Nullable ShellModelKey shellKey,
      @Nullable RootCrownKey rootKey,
      @Nullable RootPlaneBakeContext planeContext,
      @Nullable RootPlanePublish owner,
      @Nullable ShellSolidBaseKey shellSolidBaseKey) {
    if (variantKey == null && shellKey == null && rootKey == null && shellSolidBaseKey == null) {
      if (this.idMappings[blockId] != -1) {
        // This should be impossible to reach as it means that multiple bakes for the same blockId
        // happened and where inflight at the same time!
        throw new IllegalStateException(
            "Block id already added: " + blockId + " for state: " + blockState);
      }

      this.blockStatesInFlightLock.lock();
      if (!this.blockStatesInFlight.contains(blockId)) {
        this.blockStatesInFlightLock.unlock();
        throw new IllegalStateException(
            "processing a texture bake result but the block state was not in flight!!");
      }
      this.blockStatesInFlightLock.unlock();
    } else if (variantKey != null
        && !Integer.valueOf(VARIANT_MODEL_NOT_READY).equals(this.variantModelIds.get(variantKey))) {
      throw new IllegalStateException(
          "processing a variant texture bake result but the variant was not in flight!! "
              + variantKey);
    }

    // TODO: add thing for `blockState.hasEmissiveLighting()` and `blockState.getLuminance()`

    boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
    int modelId = -1;

    int clientFluidStateId = -1;

    if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
      // Insert into the fluid LUT
      var fluidState = blockState.getFluidState().createLegacyBlock();

      int fluidStateId = this.mapper.getIdForBlockState(fluidState);

      clientFluidStateId = this.idMappings[fluidStateId];
      if (clientFluidStateId == -1) {
        throw new IllegalStateException(
            "Block has a fluid state but fluid state is not already baked!!!");
      }
    }

    if (layer == null) {
      // Sink (gl41metal) path: no layer came from the bake, resolve it the same way as the
      // bake paths so the model entry classification matches the baked alpha-discard data.
      layer = PlantRenderLayerResolver.resolve(blockState);
    }
    int renderLayer = classifyRenderLayer(blockState, layer, isFluid);

    var colourProvider = getColourProvider(blockState.getBlock());

    boolean isBiomeColourDependent = false;
    int constantTint = -1;
    if (colourProvider != null) {
      try {
        isBiomeColourDependent = isBiomeDependentColour(colourProvider, blockState);
        if (!isBiomeColourDependent) {
          constantTint =
              captureColourConstant(colourProvider, blockState, DEFAULT_BIOME) | 0xFF000000;
        }
      } catch (Throwable t) {
        Logger.warn(
            "Colour provider for "
                + blockState
                + " threw while being probed, treating the block as untinted",
            t);
        colourProvider = null;
        isBiomeColourDependent = false;
        constantTint = -1;
      }
    }
    int customBlockStateId = this.getCustomBlockStateId(blockState);

    ModelEntry entry;
    { // Deduplicate same entries
      // The extra dedupe key fields are neutralised on the gl46 path (outputSink == null) so the
      // gl46 dedupe behaviour stays identical to the pre-migration implementation.
      int dedupeRenderLayer = this.outputSink == null ? RENDER_LAYER_OPAQUE : renderLayer;
      int dedupeFallbackReason = this.outputSink == null ? FALLBACK_NONE : fallbackReason;
      int dedupeCustomBlockStateId = this.outputSink == null ? 0 : customBlockStateId;
      entry =
          new ModelEntry(
              textureData,
              clientFluidStateId,
              constantTint,
              dedupeRenderLayer,
              dedupeFallbackReason,
              dedupeCustomBlockStateId);
      int possibleDuplicate = this.modelTexture2id.getInt(entry);
      if (possibleDuplicate != -1) { // Duplicate found
        modelId = possibleDuplicate;
        if (variantKey != null) {
          if (possibleDuplicate == 0) {
            // Model id 0 is the empty/air model; a variant must never resolve to it or the branch
            // would disappear.
            this.failVariant(variantKey, "variant deduplicated to the empty/air model");
            return null;
          }
          // Publish only after the duplicate's own upload has been drained. The upload queue is
          // FIFO and model bakes are single-threaded per backend, so enqueuing this marker after
          // the duplicate's uploader guarantees the atlas slot is written first.
          this.uploadResults.add(
              new ModelPublishUpload(
                  variantKey,
                  null,
                  null,
                  null,
                  possibleDuplicate,
                  possibleDuplicate == this.idMappings[blockId]));
          return null;
        }
        if (shellKey != null) {
          if (possibleDuplicate == 0) {
            this.shellModelIds.put(shellKey, VARIANT_MODEL_UNAVAILABLE);
            return null;
          }
          this.uploadResults.add(
              new ModelPublishUpload(null, shellKey, null, null, possibleDuplicate, false));
          return null;
        }
        if (shellSolidBaseKey != null) {
          if (possibleDuplicate == 0) {
            // The fail-safe cube must never collapse into the empty/air model.
            this.shellSolidBaseModelIds.put(shellSolidBaseKey, VARIANT_MODEL_UNAVAILABLE);
            return null;
          }
          this.uploadResults.add(
              new ModelPublishUpload(
                  null, null, null, shellSolidBaseKey, possibleDuplicate, false));
          return null;
        }
        if (rootKey != null) {
          if (possibleDuplicate == 0) {
            if (planeContext != null) {
              // Fail the whole multi-plane key once, from the bake loop.
              planeContext.failed = true;
              planeContext.failure = "surface root plane deduplicated to the empty/air model";
            } else {
              this.failRootVariant(rootKey, "surface root deduplicated to the empty/air model");
            }
            return null;
          }
          if (planeContext != null) {
            // Multi-plane plane: collect the duplicate's model id for the owner publication. Its
            // atlas upload was drained earlier (FIFO), and the owner is queued after every plane,
            // so no per-plane marker is needed.
            planeContext.planeIds[planeContext.planeCount++] = possibleDuplicate;
            return null;
          }
          this.uploadResults.add(
              new ModelPublishUpload(null, null, rootKey, null, possibleDuplicate, false));
          return null;
        }
        this.idMappings[blockId] = possibleDuplicate;
        // Remove from flight
        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.remove(blockId)) {
          this.blockStatesInFlightLock.unlock();
          throw new IllegalStateException();
        }
        this.blockStatesInFlightLock.unlock();
        return null;
      } else { // Not a duplicate so create a new entry
        modelId = this.modelTexture2id.size();
        if ((variantKey != null || shellKey != null || rootKey != null || shellSolidBaseKey != null)
            && modelId >= MAX_MODEL_ID_COUNT) {
          // Never exceed the 16-bit id space. The derived model falls back to the base model/air.
          this.capVariants("16-bit model id space exhausted");
          if (variantKey != null) {
            this.markVariantUnavailable(variantKey);
          } else if (shellKey != null) {
            this.shellModelIds.put(shellKey, VARIANT_MODEL_UNAVAILABLE);
          } else if (shellSolidBaseKey != null) {
            this.shellSolidBaseModelIds.put(shellSolidBaseKey, VARIANT_MODEL_UNAVAILABLE);
          } else if (planeContext != null) {
            // Fail the multi-plane key once from the bake loop; do not fail it per plane here.
            planeContext.failed = true;
            planeContext.failure = "16-bit model id space exhausted";
          } else {
            this.failRootVariant(rootKey, "16-bit model id space exhausted");
          }
          return null;
        }
        // NOTE: we set the mapping at the very end so that race conditions with this and
        // getMetadata dont occur
        // this.idMappings[blockId] = modelId;
        this.modelTexture2id.put(entry, modelId);
        this.modelIdCount.lazySet(modelId + 1);
      }
    }

    if (isFluid) {
      this.fluidStateLUT[modelId] = modelId;
    } else if (clientFluidStateId != -1) {
      this.fluidStateLUT[modelId] = clientFluidStateId;
    }

    int checkMode =
        layer == RenderType.solid()
            ? TextureUtils.WRITE_CHECK_STENCIL
            : TextureUtils.WRITE_CHECK_ALPHA;

    ModelBakeResultUpload uploadResult = new ModelBakeResultUpload();
    uploadResult.modelId = modelId;
    uploadResult.renderLayer = renderLayer;
    uploadResult.fallbackReason = fallbackReason;
    uploadResult.sourceDescription = blockState.toString();
    long uploadPtr = uploadResult.model.address;

    // TODO: implement;
    // TODO: if it has a constant colour instead... idk why (apparently for things like spruce
    // leaves)?? but premultiply the texture data by the constant colour

    // If it contains fluid but isnt a fluid
    if ((!isFluid) && (!blockState.getFluidState().isEmpty()) && clientFluidStateId != -1) {

      // Or it with the fluid state biome dependency
      isBiomeColourDependent |=
          ModelQueries.isBiomeColoured(this.getModelMetadataFromClientId(clientFluidStateId));
    }

    // TODO: special case stuff like vines and glow lichen, where it can be represented by a single
    // double sided quad
    // since that would help alot with perf of lots of vines, can be done by having one of the faces
    // just not exist and the other be in no occlusion mode

    // Non-solid layers (translucent fluids, cutout) take the MIN per-face depth so the visible
    // surface stays flush/forward; AVG recesses these faces into the voxel and opens see-through
    // gaps at grazing angles. Lava renders on the SOLID layer, so key off isFluid too: EVERY
    // fluid (water and lava) takes MIN and FLUID_BRIDGE_WALLS side walls stay flush at height
    // steps.
    var depths =
        computeModelDepth(
            textureData,
            checkMode,
            // Roots still expose several planes per view at junctions (sleeve end at the cell
            // boundary plus perpendicular sleeve fronts deeper in). AVG recesses the whole face
            // and creates plates/gaps; MIN keeps the frontmost plane. Branch variants use MAX:
            // AVG smears the two planes at radius tapers/junctions and opens see-through wedges
            // between adjacent cells. MAX is computed at bake time and stays static (no per-pixel
            // depth). Roll back with -Dvoxy.dtBranchDepthMode=avg.
            (isFluid || layer != RenderType.solid() || rootKey != null)
                ? TextureUtils.DEPTH_MODE_MIN
                : (variantKey != null ? BRANCH_DEPTH_MODE : TextureUtils.DEPTH_MODE_AVG),
            this.gl41OffscreenBakery != null);

    // TODO: THIS, note this can be tested for in 2 ways, re render the model with quad culling
    // disabled and see if the result
    // is the same, (if yes then needs double sided quads)
    // another way to test it is if e.g. up and down havent got anything rendered but the sides do
    // (e.g. all plants etc)
    boolean needsDoubleSidedQuads =
        (depths[0] < -0.1 && depths[1] < -0.1)
            || (depths[2] < -0.1 && depths[3] < -0.1)
            || (depths[4] < -0.1 && depths[5] < -0.1);

    boolean cullsSame = false;

    {
      // TODO: Could also move this into the RenderDataFactory and do it on the actual blockstates
      // instead of a guestimation
      boolean allTrue = true;
      boolean allFalse = true;
      // Guestimation test for if the block culls itself
      for (var dir : Direction.values()) {
        if (blockState.skipRendering(blockState, dir)) {
          allFalse = false;
        } else {
          allTrue = false;
        }
      }

      if (allFalse == allTrue) { // If only some sides where self culled then abort
        cullsSame = false;
        // if (LOGGED_SELF_CULLING_WARNING.add(blockState))
        //    Logger.info("Warning! blockstate: " + blockState + " only culled against its self some
        // of the time");
      }

      if (allTrue) {
        cullsSame = true;
      }
    }

    // Each face gets 1 byte, with the top 2 bytes being for whatever
    long metadata = 0;
    metadata |= isBiomeColourDependent ? 1 : 0;
    metadata |= layer == RenderType.translucent() ? 2 : 0;
    metadata |= needsDoubleSidedQuads ? 4 : 0;
    metadata |=
        ((!isFluid) && !blockState.getFluidState().isEmpty())
            ? 8
            : 0; // Has a fluid state accosiacted with it and is not itself a fluid
    metadata |= isFluid ? 16 : 0; // Is a fluid

    metadata |= cullsSame ? 32 : 0;

    boolean fullyOpaque = true;
    boolean anyFaceNeedsDiscard = false;
    // Root crowns and thick-trunk shell pieces are synthetic impostors with irregular footprints;
    // their occlusion/discard flags must not be derived from the normal full-block heuristics.
    final boolean synthetic = rootKey != null || shellKey != null;
    // Stage 2a multi-plane plane bakes (planeContext != null) carry out-of-band pixels as alpha 0
    // while the shared depth/stencil channel still reports them as written, so the writeCount
    // heuristic below cannot see them.
    final boolean planeSlice = planeContext != null && rootKey != null;

    // TODO: FIXME faces that have the same "alignment depth" e.g. (sizes[0]+sizes[1])~=1 can be
    // merged into a double faced single quad

    // TODO: add a bunch of control config options for overriding/setting options of metadata for
    // each face of each type
    for (int face = 5;
        face != -1;
        face--) { // In reverse order to make indexing into the metadata long easier
      long faceUploadPtr = uploadPtr + 4L * face; // Each face gets 4 bytes worth of data
      metadata <<= 8;
      float offset = depths[face];
      if (offset < -0.1) { // Face is empty, so ignore
        metadata |= 0xFF; // Mark the face as non-existent
        // Set to -1 as safepoint
        MemoryUtil.memPutInt(faceUploadPtr, -1);

        fullyOpaque = false;
        continue;
      }
      var faceSize = TextureUtils.computeBounds(textureData[face], checkMode);
      int writeCount = TextureUtils.getWrittenPixelCount(textureData[face], checkMode);

      boolean faceCoversFullBlock =
          faceSize[0] == 0
              && faceSize[2] == 0
              && faceSize[1] == (MODEL_TEXTURE_SIZE - 1)
              && faceSize[3] == (MODEL_TEXTURE_SIZE - 1);

      // TODO: use faceSize and the depths to compute if mesh can be correctly rendered

      metadata |= faceCoversFullBlock ? 2 : 0;

      // TODO: add alot of config options for the following
      boolean occludesFace = true;
      occludesFace &= layer != RenderType.translucent(); // If its translucent, it doesnt occlude

      // TODO: make this an option, basicly if the face is really close, it occludes otherwise it
      // doesnt
      occludesFace &=
          offset
              < 0.1; // If the face is rendered far away from the other face, then it doesnt occlude

      if (occludesFace) {
        // A face may occlude only when it is genuinely flush and covers the entire cell face.
        // The old 90%-coverage heuristic marked partial blocks such as dirt_path (15/16 high,
        // side coverage 93.75%) as occluding; the neighbouring full block's side face was then
        // culled and the 1/16 slit showed the sky as a thin blue line along every path edge.
        occludesFace &= offset <= 0.0f && writeCount == MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
      }
      metadata |= occludesFace ? 1 : 0;
      fullyOpaque &= occludesFace;

      boolean canBeOccluded = true;
      // TODO: make this an option on how far/close
      canBeOccluded &=
          offset < 0.3; // If the face is rendered far away from the other face, then it cant be
      // occluded

      metadata |= canBeOccluded ? 4 : 0;

      if (rootKey != null) {
        // Root impostors are closed synthetic prisms. Their faces must survive neighbour culling
        // even when they sit at a depth the mesher would treat as occludable; otherwise only the
        // top face is emitted and the root is not wrapped. Adjacent root cells may also differ in
        // radius, so a shared face is still a visible step.
        occludesFace = false;
        canBeOccluded = false;
        metadata &= ~1;
        metadata &= ~4;
        fullyOpaque = false;
      }

      // Face uses its own lighting if its not flat against the adjacent block & isnt traslucent
      metadata |= (offset > 0.01 || layer == RenderType.translucent()) ? 0b1000 : 0;

      if (MODEL_TEXTURE_SIZE - 1 != 15) {
        // Scale face size from 0->this.modelTextureSize-1 to 0->15
        for (int i = 0; i < 4; i++) {
          faceSize[i] = Math.round((((float) faceSize[i]) / (MODEL_TEXTURE_SIZE - 1)) * 15);
        }
      }

      int faceModelData = 0;
      faceModelData |= faceSize[0] | (faceSize[1] << 4) | (faceSize[2] << 8) | (faceSize[3] << 12);
      // Change the scale from 0->1 (ends inclusive)
      // this is cursed also warning stuff at 63 (i.e half a pixel from the end will be clamped to
      // the end)
      int enc = Math.round(offset * 64);
      faceModelData |= Math.min(enc, 62) << 16;
      if (PER_PIXEL_ROOT_DEPTH && rootKey != null && this.gl41OffscreenBakery == null) {
        // gl46 only: the model-texture alpha of this face carries the per-pixel indentation.
        faceModelData |= 1 << 26;
      }
      // Still have 11 bits free

      // Stuff like fences are solid, however they have extra side piece that mean it needs to have
      // discard on
      int area = (faceSize[1] - faceSize[0] + 1) * (faceSize[3] - faceSize[2] + 1);
      boolean needsAlphaDiscard =
          ((float) writeCount) / area
              < 0.9; // If the amount of area covered by written pixels is less than a threashold,
      // disable discard as its not needed

      needsAlphaDiscard |= layer != RenderType.solid();
      needsAlphaDiscard &=
          layer != RenderType.translucent(); // Translucent doesnt have alpha discard
      if (synthetic) {
        // Synthetic root/shell impostors have irregular silhouettes (plus-shaped top, L-shaped
        // side). The bounds-relative 0.9 test above misses junctions whose bounding box is nearly
        // full, so their empty corners were solidified into opaque bark plates. Any synthetic face
        // with a single unwritten pixel must discard.
        needsAlphaDiscard |= writeCount < MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
      }
      if (planeSlice) {
        // splitDepthBandPlanes stores every out-of-band pixel as alpha 0 but shares the source
        // depth/stencil array, so writeCount still equals the full-face count and the heuristic
        // above can miss planes whose band leaves transparent holes. Discard is mandatory for
        // every plane face; the single-plane/flag-off path is unaffected (planeSlice == false).
        needsAlphaDiscard = true;
      }
      faceModelData |= needsAlphaDiscard ? 1 << 22 : 0;
      anyFaceNeedsDiscard |= needsAlphaDiscard;

      faceModelData |=
          ((!faceCoversFullBlock) && layer != RenderType.translucent())
              ? 1 << 23
              : 0; // Alpha discard override, translucency doesnt have alpha discard

      // Bits 24,25 are tint metadata
      // A provider returning -1 means "no tint". Marking those faces as tinted makes the shader
      // multiply the texture by -1 (mapped to zero) => solid black leaves (Dynamic Trees species
      // whose primitive leaves have no vanilla BlockColors entry, e.g. cherry/azalea/fir).
      if (colourProvider != null
          && (isBiomeColourDependent || entry.tintingColour != -1)) { // We have a real tint
        int tintState = TextureUtils.computeFaceTint(textureData[face], checkMode);
        if (tintState == 2) { // Partial tint
          faceModelData |= 1 << 24;
        } else if (tintState == 3) { // Full tint
          faceModelData |= 2 << 24;
        }
      }

      MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
    }

    metadata |= fullyOpaque ? (1L << (48 + 6)) : 0;
    metadata |= anyFaceNeedsDiscard ? (1L << (48 + 7)) : 0;

    boolean canBeCorrectlyRendered =
        true; // This represents if a model can be correctly (perfectly) represented
    // i.e. no gaps

    this.metadataCache[modelId] = metadata;

    uploadPtr += 4 * 6;
    // Have 40 bytes free for remaining model data
    // todo: put in like the render layer type ig? along with colour resolver info
    int modelFlags = 0;
    modelFlags |= colourProvider != null ? 1 : 0;
    modelFlags |=
        isBiomeColourDependent
            ? 2
            : 0; // Basicly whether to use the next int as a colour or as a base index/id into a
    // colour buffer for biome dependent colours
    modelFlags |= layer == RenderType.translucent() ? 4 : 0; // Is translucent

    // TODO: THIS
    modelFlags |= isShaded ? 8 : 0; // model has AO and shade

    // modelFlags |= blockRenderLayer == RenderLayer.getSolid()?0:1;// should discard alpha
    MemoryUtil.memPutInt(uploadPtr, modelFlags);
    uploadPtr += 4;

    // Temporary override to always be non biome specific
    if (colourProvider == null) {
      MemoryUtil.memPutInt(
          uploadPtr, -1); // Set the default to nothing so that its faster on the gpu
    } else if (!isBiomeColourDependent) {
      MemoryUtil.memPutInt(uploadPtr, entry.tintingColour);
    } else {
      // Register the model even when no biome is known yet: it is flagged as biome-dependent
      // regardless, and addBiome0 can only fill its colours if it is in this list. Otherwise the
      // model permanently reads LUT slot 0 (zeroed buffer) => black leaves.
      int biomeIndex = this.modelsRequiringBiomeColours.size() * this.biomes.size();
      MemoryUtil.memPutInt(uploadPtr, biomeIndex);
      this.modelsRequiringBiomeColours.add(new Pair<>(modelId, blockState));

      if (!this.biomes.isEmpty()) {
        uploadResult.biomeUploadIndex = biomeIndex;
        long clrUploadPtr =
            (uploadResult.biomeUpload = new MemoryBuffer(4L * this.biomes.size())).address;
        for (var biome : this.biomes) {
          int colour =
              biome == null
                  ? -1
                  : captureColourConstant(colourProvider, blockState, biome) | 0xFF000000;
          MemoryUtil.memPutInt(clrUploadPtr, colour);
          clrUploadPtr += 4;
        }
      }
    }
    uploadPtr += 4;

    // have 32 bytes of free space after here

    // install the custom mapping id if it exists
    MemoryUtil.memPutInt(uploadPtr, customBlockStateId);
    uploadPtr += 4;

    // Note: if the layer isSolid then need to fill all the points in the texture where alpha == 0
    // with the average colour
    // of the surrounding blocks but only within the computed face size bounds

    // TODO callback to inject extra data into the model data

    MipGen.putTextures(darkenedTinting, textureData, uploadResult.texture);

    // glGenerateTextureMipmap(this.textures.id);

    // Set the mapping at the very end
    if (variantKey != null) {
      // Publish after the GPU upload, not here: a worker that reads this id would otherwise patch
      // the section against an atlas slot that has not been written yet.
      uploadResult.variantKey = variantKey;
      uploadResult.variantModelId = modelId;
    } else if (shellKey != null) {
      uploadResult.shellModelKey = shellKey;
      uploadResult.shellModelId = modelId;
    } else if (shellSolidBaseKey != null) {
      uploadResult.shellSolidBaseKey = shellSolidBaseKey;
      uploadResult.shellSolidBaseModelId = modelId;
    } else if (rootKey != null) {
      if (planeContext != null) {
        // Multi-plane plane: record the plane id in plane order and defer publication to the
        // owner. rootVariantModelIds stays NOT_READY until the owner is published.
        planeContext.planeIds[planeContext.planeCount++] = modelId;
        uploadResult.owner = owner;
      } else {
        uploadResult.rootVariantKey = rootKey;
        uploadResult.rootVariantModelId = modelId;
      }
    } else {
      this.idMappings[blockId] = modelId;

      this.blockStatesInFlightLock.lock();
      if (!this.blockStatesInFlight.remove(blockId)) {
        this.blockStatesInFlightLock.unlock();
        throw new IllegalStateException(
            "processing a texture bake result but the block state was not in flight!!");
      }
      this.blockStatesInFlightLock.unlock();
    }

    return uploadResult;
  }

  private static int classifyRenderLayer(
      BlockState state, RenderType blockRenderLayer, boolean isFluid) {
    if (isFluid || state.getBlock() instanceof LiquidBlock) {
      return RENDER_LAYER_FLUID;
    }
    if (blockRenderLayer == RenderType.translucent()) {
      return RENDER_LAYER_TRANSLUCENT;
    }
    if (blockRenderLayer == RenderType.cutout()
        || blockRenderLayer == RenderType.cutoutMipped()
        || blockRenderLayer == RenderType.tripwire()) {
      return RENDER_LAYER_CUTOUT;
    }
    return RENDER_LAYER_OPAQUE;
  }

  private static final class BiomeUploadResult implements ResultUploader {
    private final MemoryBuffer biomeColourBuffer;
    private final MemoryBuffer modelBiomeIndexPairs;

    private BiomeUploadResult(int biomes, int models) {
      this.biomeColourBuffer = new MemoryBuffer(biomes * models * 4);
      this.modelBiomeIndexPairs = new MemoryBuffer(models * 8);
    }

    public void upload(ModelStore store) {
      this.upload(store.modelBuffer, store.modelColourBuffer);
    }

    public void upload(ModelOutputSink sink) {
      sink.uploadBiomeData(
          new BiomeModelPayload(this.biomeColourBuffer, this.modelBiomeIndexPairs));
    }

    public void upload(GlBuffer modelBuffer, GlBuffer modelColourBuffer) {
      this.biomeColourBuffer.cpyTo(
          UploadStream.INSTANCE.upload(modelColourBuffer, 0, this.biomeColourBuffer.size));

      // TODO: optimize this to like a compute scatter update or something
      long ptr = this.modelBiomeIndexPairs.address;
      for (long offset = 0; offset < this.modelBiomeIndexPairs.size; offset += 8) {
        long v = MemoryUtil.memGetLong(ptr);
        ptr += 8;
        MemoryUtil.memPutInt(
            UploadStream.INSTANCE.upload(
                modelBuffer, (MODEL_SIZE * (v & ((1L << 32) - 1))) + 4 * 6 + 4, 4),
            (int) (v >>> 32));
      }

      this.biomeColourBuffer.free();
      this.modelBiomeIndexPairs.free();
    }

    public void free() {
      if (!this.biomeColourBuffer.isFreed()) {
        this.biomeColourBuffer.free();
        this.modelBiomeIndexPairs.free();
      }
    }
  }

  private BiomeUploadResult addBiome0(int id, Biome biome) {
    if (biome == null) {
      throw new IllegalStateException("Null biome");
    }
    for (int i = this.biomes.size(); i <= id; i++) {
      this.biomes.add(null);
    }
    var oldBiome = this.biomes.set(id, biome);

    if (oldBiome != null && oldBiome != biome) {
      throw new IllegalStateException("Biome was put in an id that was not null");
    }
    if (oldBiome == biome) {
      Logger.error("Biome added was a duplicate: " + id);
      return null;
    }

    if (this.modelsRequiringBiomeColours.isEmpty()) return null;

    var result = new BiomeUploadResult(this.biomes.size(), this.modelsRequiringBiomeColours.size());

    int i = 0;
    long modelUpPtr = result.modelBiomeIndexPairs.address;
    for (var entry : this.modelsRequiringBiomeColours) {
      var colourProvider = getColourProvider(entry.right().getBlock());
      if (colourProvider == null) {
        throw new IllegalStateException();
      }
      // Populate the list of biomes for the model state
      int biomeIndex = (i++) * this.biomes.size();
      MemoryUtil.memPutLong(
          modelUpPtr,
          Integer.toUnsignedLong(entry.left()) | (Integer.toUnsignedLong(biomeIndex) << 32));
      modelUpPtr += 8;
      long clrUploadPtr = result.biomeColourBuffer.address + biomeIndex * 4L;
      // The shader indexes this LUT by raw biome id, and the biome list is sparse (null for ids
      // not seen yet). Skipping nulls without advancing the pointer compacts the table and leaves
      // its tail unwritten (black), so every id keeps its own slot.
      for (var biomeE : this.biomes) {
        int colour =
            biomeE == null
                ? -1
                : captureColourConstant(colourProvider, entry.right(), biomeE) | 0xFF000000;
        MemoryUtil.memPutInt(clrUploadPtr, colour);
        clrUploadPtr += 4;
      }
    }

    return result;
  }

  private static BlockColor getColourProvider(Block block) {
    BlockState defaultState = block.defaultBlockState();
    var blockColors = Minecraft.getInstance().getBlockColors();
    if (block instanceof LiquidBlock) {
      return (state, world, pos, tintIndex) -> blockColors.getColor(state, world, pos, tintIndex);
    }
    int color;
    try {
      color = blockColors.getColor(defaultState, null, BlockPos.ZERO, 0);
    } catch (Exception e) {
      return null;
    }
    if (color != 0 && color != -1) {
      return (state, world, pos, tintIndex) -> blockColors.getColor(state, world, pos, tintIndex);
    }
    return null;
  }

  // TODO: add a method to detect biome dependent colours (can do by detecting if getColor is ever
  // called)
  // if it is, need to add it to a list and mark it as biome colour dependent or something then the
  // shader
  // will either use the uint as an index or a direct colour multiplier
  private static int captureColourConstant(
      BlockColor colorProvider, BlockState state, Biome biome) {
    var getter =
        new BlockAndTintGetter() {

          @Override
          public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
          }

          @Override
          public LevelLightEngine getLightEngine() {
            return null;
          }

          @Override
          public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return colorResolver.getColor(biome, 0, 0);
          }

          @Nullable
          @Override
          public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
          }

          @Override
          public BlockState getBlockState(BlockPos pos) {
            return state;
          }

          @Override
          public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
          }

          @Override
          public int getHeight() {
            return 0;
          }

          @Override
          public int getMinBuildHeight() {
            return 0;
          }

          @Override
          public float getShade(Direction direction, boolean bl) {
            return Minecraft.getInstance().level.getShade(direction, bl);
          }
        };
    int c = colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
    if (c != -1) return c;
    return colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
  }

  private static boolean isBiomeDependentColour(BlockColor colorProvider, BlockState state) {
    boolean[] biomeDependent = new boolean[1];
    var getter =
        new BlockAndTintGetter() {
          @Override
          public float getShade(Direction direction, boolean shaded) {
            return 0;
          }

          @Override
          public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
          }

          @Override
          public LevelLightEngine getLightEngine() {
            return null;
          }

          @Override
          public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            biomeDependent[0] = true;
            return 0;
          }

          @Nullable
          @Override
          public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
          }

          @Override
          public BlockState getBlockState(BlockPos pos) {
            return state;
          }

          @Override
          public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
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
    colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
    colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
    return biomeDependent[0];
  }

  // halfDepthRange: true when the textures came from the GL offscreen bakery (gl41metal path),
  // whose captured depth spans [0, 0.5] and must be doubled; false for the software bakery (gl46).
  private static float[] computeModelDepth(
      ColourDepthTextureData[] textures, int checkMode, int computeMode, boolean halfDepthRange) {
    float[] res = new float[6];
    for (var dir : Direction.values()) {
      var data = textures[dir.get3DDataValue()];
      float fd =
          TextureUtils.computeDepth(
              data,
              computeMode,
              checkMode,
              halfDepthRange); // Compute the min float depth, smaller means closer to the camera,
      // range 0-1
      // int depth = Math.round(fd * MODEL_TEXTURE_SIZE);
      // If fd is -1, it means that there was nothing rendered on that face and it should be
      // discarded
      if (fd < -0.1) {
        res[dir.ordinal()] = -1;
      } else {
        res[dir.ordinal()] = fd; // ((float) depth)/MODEL_TEXTURE_SIZE;
      }
    }
    return res;
  }

  public int[] _unsafeRawAccess() {
    return this.idMappings;
  }

  public int getModelId(int blockId) {
    int map = this.idMappings[blockId];
    if (map == -1) {
      throw new IdNotYetComputedException(blockId, true);
    }
    return map;
  }

  public boolean hasModelForBlockId(int blockId) {
    return this.idMappings[blockId] != -1;
  }

  /**
   * Classifies a Mapper block id as a Dynamic Trees branch whose model fits one Voxy cell. Results
   * are cached per block id; the underlying check is loader-agnostic (class hierarchy + optional
   * {@code dynamictrees:branches} tag) and safe to call from meshing worker threads.
   *
   * <p>Thick branches (radius &gt; 8) are excluded: their baked model renders the trunk surface
   * outside the block cell, so the per-cell rasterizer clips every face and the bake is empty.
   */
  public boolean isVariantEligibleBranchBlockId(int blockId) {
    if (blockId <= 0 || blockId >= this.dynamicTreesBranchCache.length) {
      return false;
    }
    byte cached = this.dynamicTreesBranchCache[blockId];
    if (cached != 0) {
      return cached == 1;
    }
    boolean result =
        DynamicTreesBranches.isVariantSupported(this.mapper.getBlockStateFromBlockId(blockId));
    this.dynamicTreesBranchCache[blockId] = result ? (byte) 1 : (byte) 2;
    return result;
  }

  /** Cached per-block-id check: is this a thick (radius &gt; 8) Dynamic Trees branch? */
  public boolean isThickBranchBlockId(int blockId) {
    if (blockId <= 0 || blockId >= this.thickBranchCache.length) {
      return false;
    }
    byte cached = this.thickBranchCache[blockId];
    if (cached != 0) {
      return cached == 1;
    }
    boolean result =
        DynamicTreesBranches.isThickBranch(this.mapper.getBlockStateFromBlockId(blockId));
    this.thickBranchCache[blockId] = result ? (byte) 1 : (byte) 2;
    return result;
  }

  /** Cached per-block-id check: any Dynamic Trees branch, thick branches included. */
  public boolean isAnyBranchBlockId(int blockId) {
    if (blockId <= 0 || blockId >= this.anyBranchCache.length) {
      return false;
    }
    byte cached = this.anyBranchCache[blockId];
    if (cached != 0) {
      return cached == 1;
    }
    boolean result = DynamicTreesBranches.isBranch(this.mapper.getBlockStateFromBlockId(blockId));
    this.anyBranchCache[blockId] = result ? (byte) 1 : (byte) 2;
    return result;
  }

  /** Resolves a Mapper block id to its BlockState (used by LOD0 shell patching). */
  public BlockState getBlockStateForId(int blockId) {
    return this.mapper.getBlockStateFromBlockId(blockId);
  }

  /** Cached per-block-id check for {@code dynamictrees:trunk_shell}. */
  public boolean isTrunkShellBlockId(int blockId) {
    if (blockId <= 0 || blockId >= this.trunkShellCache.length) {
      return false;
    }
    byte cached = this.trunkShellCache[blockId];
    if (cached != 0) {
      return cached == 1;
    }
    boolean result =
        DynamicTreesBranches.isTrunkShell(this.mapper.getBlockStateFromBlockId(blockId));
    this.trunkShellCache[blockId] = result ? (byte) 1 : (byte) 2;
    return result;
  }

  /**
   * Model id of the thick-trunk prism piece inside the cell at {@code (dx, dz)} from the core.
   * Returns {@link #VARIANT_MODEL_NOT_READY} while the bake is queued, or {@link
   * #VARIANT_MODEL_UNAVAILABLE} when the piece does not exist / failed to bake.
   */
  public int getShellModelId(int coreBlockId, int dx, int dz, int capBits) {
    Integer id = this.shellModelIds.get(new ShellModelKey(coreBlockId, dx, dz, capBits));
    return id == null ? VARIANT_MODEL_UNAVAILABLE : id;
  }

  /**
   * Model id of the fail-safe solid bark cube patched into a {@code trunk_shell} cell whose prism
   * piece cannot be resolved because of <em>data/model</em> absence (unknown core, missing
   * neighbour slice, shell bake unavailable). Cells whose core is known to be non-thick are
   * DT-invalid and must stay empty; the caller makes that distinction.
   *
   * <p>The sprite is resolved on the bakery thread in this order: the shell state's own model
   * particle icon, the resolved core's / nearby branch state's bark sprite, then the deterministic
   * oak bark side sprite (one-shot WARN per shell block id). The bake is queued lazily, so the
   * first caller gets {@link #VARIANT_MODEL_NOT_READY} and must retry the section.
   *
   * @param shellBlockId the {@code trunk_shell} block id that will carry the cube
   * @param barkSourceBlockId block id of the resolved core or a nearby Dynamic Trees branch; 0 when
   *     neither is available (the oak fallback is used)
   * @return a baked model id, {@link #VARIANT_MODEL_NOT_READY} while queued, or {@link
   *     #VARIANT_MODEL_UNAVAILABLE} if every sprite/bake attempt failed (caller leaves the cell
   *     empty rather than crashing)
   */
  public int getShellSolidBaseModelId(int shellBlockId, int barkSourceBlockId) {
    ShellSolidBaseKey key = new ShellSolidBaseKey(shellBlockId, barkSourceBlockId);
    Integer id = this.shellSolidBaseModelIds.get(key);
    if (id != null) {
      return id;
    }
    if (this.shellSolidBaseModelIds.putIfAbsent(key, VARIANT_MODEL_NOT_READY) == null) {
      this.shellSolidBaseBakeQueue.add(key);
    }
    return VARIANT_MODEL_NOT_READY;
  }

  /** Cached per-block-id check for Dynamic Trees surface roots. */
  public boolean isSurfaceRootBlockId(int blockId) {
    if (blockId <= 0 || blockId >= this.surfaceRootCache.length) {
      return false;
    }
    byte cached = this.surfaceRootCache[blockId];
    if (cached != 0) {
      return cached == 1;
    }
    boolean result =
        DynamicTreesBranches.isSurfaceRoot(this.mapper.getBlockStateFromBlockId(blockId));
    this.surfaceRootCache[blockId] = result ? (byte) 1 : (byte) 2;
    return result;
  }

  /**
   * Root variant key limit. With multi-plane roots each key may consume up to {@link
   * #DT_ROOT_MAX_PLANES} model ids, so cap roots by the id budget left after the branch variant
   * reserve and the base model reserve. Flag-off this is exactly {@link
   * #MAX_ROOT_VARIANT_MODEL_COUNT}, so behaviour is unchanged without multi-plane.
   */
  private static int rootVariantLimit() {
    if (!DT_ROOT_MULTIPLANE) {
      return MAX_ROOT_VARIANT_MODEL_COUNT;
    }
    int branchReserve = MAX_VARIANT_MODEL_COUNT; // keep the branch budget intact
    int available = MAX_MODEL_ID_COUNT - BASE_MODEL_ID_RESERVE - branchReserve;
    return Math.min(MAX_ROOT_VARIANT_MODEL_COUNT, Math.max(1, available / DT_ROOT_MAX_PLANES));
  }

  /**
   * Looks up the synthetic crown model id for a surface root. {@code flags} bit 0..3 = connected to
   * north/east/south/west. Returns {@link #VARIANT_MODEL_NOT_READY} while queued, {@link
   * #VARIANT_MODEL_UNAVAILABLE} when the base model must be used.
   */
  public int getRootVariantModelId(
      int blockId,
      int sideRadii,
      int sideLevels,
      int coreBlockId,
      int coreDx,
      int coreDz,
      BlockState[] cube) {
    if (!DTRootGeometryCache.ROOTS_ENABLED) {
      // Master switch off: fail-safe, never queue a RootCrownKey bake. The LOD0 resolver
      // suppresses root cells without requesting a variant.
      return VARIANT_MODEL_UNAVAILABLE;
    }
    if (!this.variantBakingSupported || cube == null) {
      // The exact DT model can only be baked from a captured context cube. A null cube would
      // poison the key (the first caller wins the stored cube) and every later request would
      // reuse that failure, so reject it up front.
      return VARIANT_MODEL_UNAVAILABLE;
    }
    RootCrownKey key =
        new RootCrownKey(blockId, sideRadii, sideLevels, coreBlockId, coreDx, coreDz);
    Integer existing = this.rootVariantModelIds.get(key);
    if (existing != null) {
      return existing;
    }
    // A multi-plane root may consume DT_ROOT_MAX_PLANES ids at once, so reserve room for all of
    // them up front. With the flag off this is exactly the original single-id check.
    int requiredIds = DT_ROOT_MULTIPLANE ? DT_ROOT_MAX_PLANES : 1;
    if (this.variantsCapped
        || this.modelIdCount.get() + requiredIds > MAX_MODEL_ID_COUNT - BASE_MODEL_ID_RESERVE) {
      return VARIANT_MODEL_UNAVAILABLE;
    }
    int rootLimit = rootVariantLimit();
    if (this.rootVariantActive.get() >= rootLimit) {
      if (DT_ROOT_MULTIPLANE) {
        if (this.rootMultiplaneCapLogged.compareAndSet(false, true)) {
          Logger.warn(
              "Dynamic Trees multi-plane surface-root variants capped at "
                  + rootLimit
                  + " distinct contexts by the id budget (roots up to "
                  + (rootLimit * DT_ROOT_MAX_PLANES)
                  + " ids + branch reserve "
                  + MAX_VARIANT_MODEL_COUNT
                  + " + base reserve "
                  + BASE_MODEL_ID_RESERVE
                  + " = "
                  + (rootLimit * DT_ROOT_MAX_PLANES
                      + MAX_VARIANT_MODEL_COUNT
                      + BASE_MODEL_ID_RESERVE)
                  + " of "
                  + MAX_MODEL_ID_COUNT
                  + "), modelIdCount="
                  + this.modelIdCount.get()
                  + "; further roots keep the base model");
        }
      } else if (this.rootVariantCapLogged.compareAndSet(false, true)) {
        Logger.warn(
            "Dynamic Trees surface-root variants capped at "
                + rootLimit
                + " distinct contexts; further roots keep the base model");
      }
      return VARIANT_MODEL_UNAVAILABLE;
    }
    existing = this.rootVariantModelIds.putIfAbsent(key, VARIANT_MODEL_NOT_READY);
    if (existing != null) {
      return existing;
    }
    // Replace rather than putIfAbsent: after a transient bake failure the key is removed but the
    // old cube would survive, and the retry must bake the context seen now, not a stale one.
    this.rootCubes.put(key, cube);
    this.rootVariantActive.incrementAndGet();
    this.variantRequestCount.incrementAndGet();
    this.rootVariantRequestCount.incrementAndGet();
    this.rootVariantInflight.incrementAndGet();
    this.rootVariantBakeQueue.add(key);
    if (this.rootVariantActiveLogged.compareAndSet(false, true)) {
      Logger.info(
          "Dynamic Trees surface-root variants active, first request "
              + key
              + " state "
              + this.mapper.getBlockStateFromBlockId(blockId));
    }
    return VARIANT_MODEL_NOT_READY;
  }

  /**
   * Stage 1 option A accessor: ordinary model ids making up the given base root model. Falls back
   * to a single-element array containing the base id itself, so callers always get a valid plane
   * list even when the flag is off or nothing was published.
   */
  public int[] getRootPlaneModelIds(int baseModelId) {
    int[] planes = this.rootPlaneModelIds.get(baseModelId);
    // Defensive copy: Stage 3 callers must never be able to mutate the registered plane list.
    return planes != null ? planes.clone() : new int[] {baseModelId};
  }

  /** Upper bound on depth bands per root face (1..3), used by later multi-plane stages. */
  public int getRootMaxPlanes() {
    return DT_ROOT_MAX_PLANES;
  }

  /** True when the option A multi-plane root scaffolding (and its registry) is active. */
  public boolean isRootMultiplaneEnabled() {
    return DT_ROOT_MULTIPLANE;
  }

  /**
   * Dynamic Trees thick branches (radius &gt; 8) paint their bark in the eight neighbouring block
   * cells (a 3x3x1 prism); a per-cell rasterizer cannot capture that, and the branch's own cell
   * contains at most a thin cap. Substitute a full bark cube so the trunk column stays solid at
   * every LOD. Returns null for every other block.
   */
  private @Nullable BakedModel thickBranchModelFor(BlockState state) {
    if (!DynamicTreesBranches.isThickBranch(state)) {
      return null;
    }
    Optional<BakedModel> cached = this.thickBranchSolidModels.get(state);
    if (cached == null || cached.isEmpty()) {
      // Never cache an empty Optional permanently: the particle-sprite lookup can fail
      // transiently during a resource reload, and computeIfAbsent used to make the trunk prism
      // and every composite root shell disappear for the rest of the session.
      cached = this.createThickBranchModel(state);
      if (cached.isPresent()) {
        this.thickBranchSolidModels.put(state, cached);
      }
    }
    return cached.orElse(null);
  }

  /**
   * Solid-bark base model for DynamicTreesPlus mushroom stems. Their real model derives all
   * geometry from neighbour-supplied ModelData; on the neighbour-less base-bake path (ModelData
   * .EMPTY) it produces degenerate quads and dedupes to the air model, which hides stems at LOD1
   * and fails the LOD0 variant gate ({@code modelId != 0}). A full cube using the model's bark
   * particle sprite ({@code minecraft:block/mushroom_stem} for {@code mushroom_branch}) keeps the
   * base id visible. Neighbour-aware variant bakes keep using the real dynamic model.
   *
   * <p>Returns null for every other block.
   */
  private @Nullable BakedModel mushroomStemModelFor(BlockState state) {
    if (!DynamicTreesBranches.isMushroomBranch(state)) {
      return null;
    }
    Optional<BakedModel> cached = this.thickBranchSolidModels.get(state);
    if (cached == null || cached.isEmpty()) {
      // Same policy as thickBranchModelFor: never cache an empty Optional permanently, the
      // particle-sprite lookup can fail transiently during a resource reload.
      cached = this.createThickBranchModel(state);
      if (cached.isPresent()) {
        this.thickBranchSolidModels.put(state, cached);
      }
    }
    return cached.orElse(null);
  }

  private Optional<BakedModel> createThickBranchModel(BlockState thickState) {
    try {
      TextureAtlasSprite sprite =
          Minecraft.getInstance()
              .getModelManager()
              .getBlockModelShaper()
              .getBlockModel(thickState)
              .getParticleIcon();
      if (sprite == null) {
        Logger.warn("No particle sprite for Dynamic Trees thick branch " + thickState);
        return Optional.empty();
      }
      return Optional.of(new SolidBarkBakedModel(sprite));
    } catch (Throwable t) {
      Logger.warn("Could not build the bark cube for Dynamic Trees thick branch " + thickState, t);
      return Optional.empty();
    }
  }

  /**
   * Bakes the eight prism pieces that surround a thick trunk core. Their models are patched into
   * {@code trunk_shell} cells at LOD0 meshing time so the trunk keeps DT's real width (2x2/3x3)
   * instead of a single block. Runs right after the core's solid-cube bake.
   */
  private void bakeThickShells(int coreBlockId, BlockState coreState) {
    Optional<BakedModel> solid = this.thickBranchSolidModels.get(coreState);
    if (solid == null || solid.isEmpty()) {
      solid = this.createThickBranchModel(coreState);
      if (solid.isPresent()) {
        this.thickBranchSolidModels.put(coreState, solid);
      }
    }
    if (solid.isEmpty() || !(solid.get() instanceof SolidBarkBakedModel bark)) {
      return;
    }
    int radius = DynamicTreesBranches.radiusOf(coreState);
    if (radius <= DynamicTreesBranches.MAX_CELL_RADIUS) {
      return;
    }
    if (this.variantsCapped
        || this.modelIdCount.get() >= MAX_MODEL_ID_COUNT - BASE_MODEL_ID_RESERVE) {
      // Shell models are derived variants and must respect the same id-space guard as branches
      // and roots; 4 cap variants per cell multiply their consumption.
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) {
            continue;
          }
          for (int capBits = 0; capBits < 4; capBits++) {
            this.shellModelIds.putIfAbsent(
                new ShellModelKey(coreBlockId, dx, dz, capBits), VARIANT_MODEL_UNAVAILABLE);
          }
        }
      }
      return;
    }
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        for (int capBits = 0; capBits < 4; capBits++) {
          ShellModelKey key = new ShellModelKey(coreBlockId, dx, dz, capBits);
          if (this.shellModelIds.putIfAbsent(key, VARIANT_MODEL_NOT_READY) != null) {
            continue;
          }
          try {
            BakedModel shellModel =
                new ThickTrunkShellBakedModel(
                    bark.sprite(), radius, dx, dz, (capBits & 1) != 0, (capBits & 2) != 0);
            ColourDepthTextureData[] textureData;
            boolean shaded;
            boolean darkened;
            if (this.bakery2 != null) {
              int flags =
                  this.bakery2.renderToOutput(coreState, this.bakeScratchBuffer, null, shellModel);
              textureData = this.readSoftwareBakeTextures();
              shaded = (flags & 1) != 0;
              darkened = (flags & 2) != 0;
            } else {
              var result = this.gl41OffscreenBakery.bake(coreState, null, shellModel);
              if (result.fallbackReason() != FALLBACK_NONE) {
                this.shellModelIds.put(key, VARIANT_MODEL_UNAVAILABLE);
                continue;
              }
              textureData = result.faces();
              shaded = result.shaded();
              darkened = result.darkenedTextures();
            }
            int writtenPixels = 0;
            for (ColourDepthTextureData face : textureData) {
              writtenPixels +=
                  TextureUtils.getWrittenPixelCount(face, TextureUtils.WRITE_CHECK_STENCIL);
            }
            if (writtenPixels == 0) {
              // Degenerate sliver (tiny radius in a far cell): leave the cell air rather than
              // installing an invisible model.
              this.shellModelIds.put(key, VARIANT_MODEL_UNAVAILABLE);
              continue;
            }
            var upload =
                this.processTextureBakeResult(
                    coreBlockId,
                    coreState,
                    textureData,
                    shaded,
                    darkened,
                    RenderType.solid(),
                    FALLBACK_NONE,
                    null,
                    key,
                    null,
                    null,
                    null,
                    null);
            if (upload != null) {
              this.uploadResults.add(upload);
            }
          } catch (Throwable t) {
            this.shellModelIds.put(key, VARIANT_MODEL_UNAVAILABLE);
            if (this.shellFailureLogCount.incrementAndGet() <= 8) {
              Logger.warn("Failed to bake thick trunk shell " + key, t);
            }
          }
        }
      }
    }
  }

  /**
   * Bakes one fail-safe solid bark cube for a {@code trunk_shell} cell with the software bakery.
   * Runs on the model factory processor thread, like the other Dynamic Trees variants.
   */
  private void bakeShellSolidBaseSoftware(ShellSolidBaseKey key) {
    try {
      BlockState shellState = this.mapper.getBlockStateFromBlockId(key.shellBlockId());
      TextureAtlasSprite sprite = this.resolveShellSolidBaseSprite(key, shellState);
      int flags =
          this.bakery2.renderToOutput(
              shellState, this.bakeScratchBuffer, null, new SolidBarkBakedModel(sprite));
      ColourDepthTextureData[] textureData = this.readSoftwareBakeTextures();
      int writtenPixels = 0;
      for (ColourDepthTextureData face : textureData) {
        writtenPixels += TextureUtils.getWrittenPixelCount(face, TextureUtils.WRITE_CHECK_STENCIL);
      }
      if (writtenPixels == 0) {
        this.failShellSolidBase(key, "bake produced no pixels");
        return;
      }
      var upload =
          this.processTextureBakeResult(
              key.shellBlockId(),
              shellState,
              textureData,
              (flags & 1) != 0,
              (flags & 2) != 0,
              RenderType.solid(),
              FALLBACK_NONE,
              null,
              null,
              null,
              null,
              null,
              key);
      if (upload != null) {
        this.uploadResults.add(upload);
      }
    } catch (Throwable t) {
      this.failShellSolidBase(key, t);
    }
  }

  /** GL41 sink variant of {@link #bakeShellSolidBaseSoftware}; runs on the render thread. */
  private void bakeShellSolidBaseGl41(ShellSolidBaseKey key) {
    try {
      BlockState shellState = this.mapper.getBlockStateFromBlockId(key.shellBlockId());
      TextureAtlasSprite sprite = this.resolveShellSolidBaseSprite(key, shellState);
      var result = this.gl41OffscreenBakery.bake(shellState, null, new SolidBarkBakedModel(sprite));
      if (result.fallbackReason() != FALLBACK_NONE) {
        this.failShellSolidBase(key, "bake failed with fallback reason " + result.fallbackReason());
        return;
      }
      int writtenPixels = 0;
      for (ColourDepthTextureData face : result.faces()) {
        writtenPixels += TextureUtils.getWrittenPixelCount(face, TextureUtils.WRITE_CHECK_STENCIL);
      }
      if (writtenPixels == 0) {
        this.failShellSolidBase(key, "bake produced no pixels");
        return;
      }
      var upload =
          this.processTextureBakeResult(
              key.shellBlockId(),
              shellState,
              result.faces(),
              result.shaded(),
              result.darkenedTextures(),
              RenderType.solid(),
              FALLBACK_NONE,
              null,
              null,
              null,
              null,
              null,
              key);
      if (upload != null) {
        this.uploadResults.add(upload);
      }
    } catch (Throwable t) {
      this.failShellSolidBase(key, t);
    }
  }

  private void failShellSolidBase(ShellSolidBaseKey key, Object cause) {
    this.shellSolidBaseModelIds.put(key, VARIANT_MODEL_UNAVAILABLE);
    if (this.shellSolidBaseFailureCount.incrementAndGet() <= 8) {
      Logger.warn("Failed to bake the fail-safe trunk_shell bark cube " + key, cause);
    }
  }

  /**
   * Resolves the fail-safe cube sprite on the bakery thread: the shell state's own model particle
   * icon, then the resolved core's / nearby branch state's bark sprite, then the deterministic oak
   * bark side sprite (one-shot WARN per shell block id). The missing-texture sprite is used only as
   * an absolute last resort, so the cube always has geometry and the caller never falls back to the
   * empty model.
   */
  private TextureAtlasSprite resolveShellSolidBaseSprite(
      ShellSolidBaseKey key, BlockState shellState) {
    TextureAtlasSprite sprite = barkSpriteOrNull(shellState);
    if (sprite != null) {
      return sprite;
    }
    if (key.barkSourceBlockId() > 0) {
      sprite = barkSpriteOrNull(this.mapper.getBlockStateFromBlockId(key.barkSourceBlockId()));
      if (sprite != null) {
        return sprite;
      }
    }
    sprite = barkSpriteOrNull(Blocks.OAK_LOG.defaultBlockState());
    if (sprite != null) {
      if (this.shellSolidBaseOakWarned.add(key.shellBlockId())) {
        Logger.warn(
            "No bark sprite for Dynamic Trees trunk_shell block id "
                + key.shellBlockId()
                + " ("
                + shellState
                + "), using the deterministic oak bark fallback");
      }
      return sprite;
    }
    if (this.shellSolidBaseOakWarned.add(key.shellBlockId())) {
      Logger.warn(
          "No bark or oak sprite available for Dynamic Trees trunk_shell block id "
              + key.shellBlockId()
              + ", using the missing-texture cube");
    }
    return Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
  }

  /** The state's model particle icon, or null when it is absent or the missing-texture sprite. */
  private static @Nullable TextureAtlasSprite barkSpriteOrNull(@Nullable BlockState state) {
    if (state == null) {
      return null;
    }
    try {
      BakedModel model =
          Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
      if (model == null) {
        return null;
      }
      TextureAtlasSprite sprite = model.getParticleIcon();
      if (sprite == null
          || MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) {
        return null;
      }
      return sprite;
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * Looks up the per-position model id for a Dynamic Trees branch. The six ints are the neighbour
   * block state ids in Direction.get3DDataValue order (DOWN, UP, NORTH, SOUTH, WEST, EAST).
   *
   * @return the variant model id, {@link #VARIANT_MODEL_NOT_READY} if a bake was requested and the
   *     caller must retry, or {@link #VARIANT_MODEL_UNAVAILABLE} if the base model must be used
   */
  public int getVariantModelId(
      int blockId, int down, int up, int north, int south, int west, int east) {
    if (!this.variantBakingSupported) {
      return VARIANT_MODEL_UNAVAILABLE;
    }
    VariantKey key = new VariantKey(blockId, down, up, north, south, west, east);
    // Existing entries stay usable even after the cap fired: disabling only new requests avoids
    // silently reverting already-baked geometry to the base model on remesh.
    Integer existing = this.variantModelIds.get(key);
    if (existing != null) {
      return existing;
    }
    if (this.variantsCapped || !this.canRequestVariantModel()) {
      return VARIANT_MODEL_UNAVAILABLE;
    }
    existing = this.variantModelIds.putIfAbsent(key, VARIANT_MODEL_NOT_READY);
    if (existing != null) {
      return existing;
    }
    this.variantActiveCount.incrementAndGet();
    this.variantRequestCount.incrementAndGet();
    this.branchVariantRequestCount.incrementAndGet();
    this.variantInflightCount.incrementAndGet();
    this.variantBakeQueue.add(key);
    return VARIANT_MODEL_NOT_READY;
  }

  private boolean canRequestVariantModel() {
    if (this.modelIdCount.get() >= MAX_MODEL_ID_COUNT - BASE_MODEL_ID_RESERVE) {
      this.capVariants("16-bit model id space is nearly full");
      return false;
    }
    // Failed (-2) entries do not consume a slot; only live/in-flight variants count toward the
    // soft cap, otherwise a burst of failures would disable the feature for the session.
    if (this.variantActiveCount.get() >= MAX_VARIANT_MODEL_COUNT) {
      this.capVariants("variant model cap (" + MAX_VARIANT_MODEL_COUNT + ") reached");
      return false;
    }
    return true;
  }

  private synchronized void capVariants(String reason) {
    if (!this.variantsCapped) {
      this.variantsCapped = true;
      Logger.warn(
          "Dynamic Trees LOD0 branch variants disabled, falling back to base models: " + reason);
    }
  }

  /** True when the active loader can bake neighbour-dependent block models. */
  public boolean supportsVariantModels() {
    return this.variantBakingSupported;
  }

  /** True while variant bake requests are queued for the render-thread/sink bakery. */
  public boolean hasQueuedVariantBakes() {
    return !this.variantBakeQueue.isEmpty()
        || !this.rootVariantBakeQueue.isEmpty()
        || !this.shellSolidBaseBakeQueue.isEmpty();
  }

  public int getVariantInflightCount() {
    return this.variantInflightCount.get() + this.rootVariantInflight.get();
  }

  public int getVariantSuccessCount() {
    return this.variantSuccessCount.get();
  }

  public int getVariantFailureCount() {
    return this.variantFailureCount.get();
  }

  public int getVariantRequestCount() {
    return this.variantRequestCount.get();
  }

  public int getVariantDedupeBaseCount() {
    return this.variantDedupeBaseCount.get();
  }

  public int getBranchVariantRequestCount() {
    return this.branchVariantRequestCount.get();
  }

  public int getBranchVariantSuccessCount() {
    return this.branchVariantSuccessCount.get();
  }

  public int getRootVariantRequestCount() {
    return this.rootVariantRequestCount.get();
  }

  public int getRootVariantSuccessCount() {
    return this.rootVariantSuccessCount.get();
  }

  /** Live branch + root variant entries currently holding a model id slot. */
  public int getVariantActiveCount() {
    return this.variantActiveCount.get() + this.rootVariantActive.get();
  }

  /** High-water mark of the shared model id space (base, variant and root models). */
  public int getModelIdCount() {
    return this.modelIdCount.get();
  }

  public int getFluidClientStateId(int clientBlockStateId) {
    int map = this.fluidStateLUT[clientBlockStateId];
    if (map == -1) {
      throw new IdNotYetComputedException(clientBlockStateId, false);
    }
    return map;
  }

  public final long getModelMetadataFromClientId(int clientId) {
    return this.metadataCache[clientId];
  }

  public void free() {
    if (this.bakery2 != null) {
      this.bakery2.free();
    }
    if (this.gl41OffscreenBakery != null) {
      this.gl41OffscreenBakery.close();
    }
    MemoryUtil.nmemFree(this.bakeScratchBuffer);
    while (!this.uploadResults.isEmpty()) {
      this.uploadResults.poll().free();
    }
  }

  public int getBakedCount() {
    return this.modelTexture2id.size();
  }

  public int getInflightCount() {
    // TODO replace all of this with an atomic?
    int size = this.blockStatesInFlight.size();
    size += this.uploadResults.size();
    size += this.biomeQueue.size();
    size += this.bakeQueue.size();
    size += this.variantInflightCount.get();
    size += this.rootVariantInflight.get();
    return size;
  }

  public String getBakeryMode() {
    if (this.gl41OffscreenBakery != null) {
      return this.gl41OffscreenBakery.modeName();
    }
    if (this.bakery2 != null) {
      return "software";
    }
    return "none";
  }

  private static int computeSizeWithMips(int size) {
    int total = 0;
    for (; size != 0; size >>= 1) total += size * size;
    return total;
  }
}
