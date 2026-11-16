package me.cortex.voxy.client.core.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.DynamicTreesBranches;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

/**
 * Read-only diagnostic dump behind {@code /voxy dump <x> <y> <z> [radius] [level]}.
 *
 * <p>Emits one {@code [VoxyDump]}-prefixed line per field/cell so an external script can parse the
 * exact LOD state of a cell: the section/cell key, the Mapper block id and state, Dynamic Trees
 * classification, base model metadata and per-face face data, the variant key slots (raw and
 * canonical) and variant registry state, the section mask bits a mesher would install, the
 * branch-cap filter mode/drop mask/depth mode and a re-run of the per-face quad pipeline with the
 * same neighbour context and {@link RootVertFilterBakedModel} predicate, listing kept vs dropped
 * quads with plane/extents.
 *
 * <p>Inert by default: nothing runs unless the command is executed. Sections are only read through
 * {@link WorldEngine#acquireIfExists} (never force-loaded) and released before returning. No
 * variant request is queued; the registry is read, not mutated (canonical slot representatives are
 * stored exactly as normal meshing already does).
 */
public final class LodDump {
  public static final int MAX_RADIUS = 2;

  /** Hard line budget so a radius-2 dump cannot flood the log without bound. */
  public static final int MAX_LINES = 20000;

  /** Per-cell quad detail budget (summary counters are always emitted). */
  public static final int MAX_QUAD_LINES_PER_CELL = 256;

  private static final Direction[] FACE_ORDER = {
    Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
  };
  private static final String[] SLOT_NAMES = {
    "own", "down", "up", "north", "south", "west", "east"
  };
  private static final float CAP_MAX_Y = 16.0f;

  private LodDump() {}

  /**
   * Runs the dump. {@code blockX/Y/Z} are world block coordinates; the cell is resolved with {@code
   * floorDiv(coord, 1 << level)}. {@code radius} is measured in cells (0..{@link #MAX_RADIUS}).
   *
   * @return the number of lines emitted
   */
  public static int run(
      WorldEngine engine,
      ModelFactory factory,
      int blockX,
      int blockY,
      int blockZ,
      int radius,
      int level,
      boolean voxyActive,
      Consumer<String> sink) {
    Emitter out = new Emitter(sink);
    SectionCache sections = new SectionCache(engine, level);
    try {
      int lvl = level;
      out.line("header version=1");
      out.line(
          "flags branchCapFilter="
              + ModelFactory.branchCapFilterMode()
              + " branchDepthMode="
              + ModelFactory.branchDepthModeName()
              + " dtVariants="
              + ModelFactory.dtVariantsEnabled()
              + " vanillaTerrain="
              + VoxyClient.isVanillaTerrain()
              + " dtRootMultiplane="
              + factory.isRootMultiplaneEnabled());
      out.line(
          "backend id="
              + VoxyClient.getRenderBackendId()
              + " bakery="
              + factory.getBakeryMode()
              + " voxyActive="
              + voxyActive
              + " variantBakingSupported="
              + factory.supportsVariantModels());
      out.line(
          "counters modelIdCount="
              + factory.getModelIdCount()
              + " variantRegistry="
              + factory.getVariantRegistrySize()
              + " variantsCapped="
              + factory.areVariantsCapped()
              + " variantPending="
              + factory.getVariantPendingCount()
              + " variantLive="
              + factory.getVariantLiveCount()
              + " variantInflight="
              + factory.getVariantInflightCount()
              + " variantRequests="
              + factory.getVariantRequestCount()
              + " variantSuccesses="
              + factory.getVariantSuccessCount()
              + " variantFailures="
              + factory.getVariantFailureCount()
              + " dedupeBase="
              + factory.getVariantDedupeBaseCount()
              + " branchRequests="
              + factory.getBranchVariantRequestCount()
              + " branchSuccesses="
              + factory.getBranchVariantSuccessCount()
              + " rootRequests="
              + factory.getRootVariantRequestCount()
              + " rootSuccesses="
              + factory.getRootVariantSuccessCount());
      out.line(
          "request block=["
              + blockX
              + ","
              + blockY
              + ","
              + blockZ
              + "] level="
              + lvl
              + " radius="
              + radius
              + " cellSize="
              + (1 << lvl));
      out.line(
          "rendererRule=voxyActive&&!vanillaTerrain&&level==0=>voxy-lod0;"
              + "level>0&&voxyActive&&!vanillaTerrain=>voxy-lod<level>;else=>vanilla");

      int cellSize = 1 << lvl;
      int centerGx = Math.floorDiv(blockX, cellSize);
      int centerGy = Math.floorDiv(blockY, cellSize);
      int centerGz = Math.floorDiv(blockZ, cellSize);

      int cellIndex = 0;
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          for (int dx = -radius; dx <= radius; dx++) {
            int gx = centerGx + dx;
            int gy = centerGy + dy;
            int gz = centerGz + dz;
            dumpCell(out, sections, factory, gx, gy, gz, lvl, cellIndex++, voxyActive);
            if (out.truncated) {
              out.line("dumpTruncated lines=" + out.lines + " reason=MAX_LINES");
              return out.lines;
            }
          }
        }
      }
      out.line("footer cells=" + cellIndex + " lines=" + out.lines + " truncated=" + out.truncated);
      return out.lines;
    } finally {
      sections.close();
    }
  }

  private static void dumpCell(
      Emitter out,
      SectionCache sections,
      ModelFactory factory,
      int gx,
      int gy,
      int gz,
      int lvl,
      int cellIndex,
      boolean voxyActive) {
    int sx = Math.floorDiv(gx, 32);
    int sy = Math.floorDiv(gy, 32);
    int sz = Math.floorDiv(gz, 32);
    int lx = Math.floorMod(gx, 32);
    int ly = Math.floorMod(gy, 32);
    int lz = Math.floorMod(gz, 32);
    WorldSection section = sections.get(sx, sy, sz);
    String key =
        "cell index="
            + cellIndex
            + " cell=["
            + gx
            + ","
            + gy
            + ","
            + gz
            + "] section="
            + lvl
            + "@["
            + sx
            + ","
            + sy
            + ","
            + sz
            + "] local=["
            + lx
            + ","
            + ly
            + ","
            + lz
            + "]";

    String renderer = rendererName(voxyActive, lvl);
    out.line(
        key
            + " sectionPresent="
            + (section != null)
            + " renderer="
            + renderer
            + " backend="
            + VoxyClient.getRenderBackendId());
    if (section == null) {
      out.line(key + " blockId=-1 state=ABSENT_SECTION");
      out.line(key + " variant state=ABSENT_SECTION");
      return;
    }

    int blockId = readBlockId(section, lx, ly, lz);
    BlockState state =
        blockId == 0 ? Blocks.AIR.defaultBlockState() : factory.getBlockStateForId(blockId);
    ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    out.line(
        key
            + " blockId="
            + blockId
            + " registryId="
            + (registryId == null ? "unknown" : registryId)
            + " registryBlockId="
            + BuiltInRegistries.BLOCK.getId(state.getBlock())
            + " state="
            + state);
    out.line(
        key
            + " dt branch="
            + DynamicTreesBranches.isBranch(state)
            + " branchClass="
            + DynamicTreesBranches.isBranchBlockClass(state)
            + " thick="
            + DynamicTreesBranches.isThickBranch(state)
            + " mushroomBranch="
            + DynamicTreesBranches.isMushroomBranch(state)
            + " trunkShell="
            + DynamicTreesBranches.isTrunkShell(state)
            + " root="
            + DynamicTreesBranches.isSurfaceRoot(state)
            + " leaf="
            + isLeaf(state)
            + " radiusProperty="
            + DynamicTreesBranches.radiusOf(state)
            + " baseModelOverride="
            + baseModelOverride(state));
    int baseModelId = rawModelId(factory, blockId);
    out.line(
        key
            + " variantEligible="
            + (blockId > 0 && factory.isVariantEligibleBranchBlockId(blockId))
            + " variantPath="
            + (lvl == 0 ? "lod0" : "lod" + lvl + "-only-base")
            + " baseModelId="
            + (baseModelId < 0 ? "NOT_BAKED" : baseModelId));

    long baseMetadata = baseModelId >= 0 ? factory.dumpModelMetadata(baseModelId) : 0L;
    if (baseModelId >= 0) {
      dumpFaceLines(out, key, "base", baseModelId, baseMetadata, factory);
    } else {
      out.line(key + " base faces=NOT_BAKED");
    }

    int[] neighbourIds = new int[6];
    BlockState[] neighbourStates = new BlockState[6];
    boolean missingNeighbour = false;
    for (int i = 0; i < 6; i++) {
      Direction dir = FACE_ORDER[i];
      int nx = gx + dir.getStepX();
      int ny = gy + dir.getStepY();
      int nz = gz + dir.getStepZ();
      int nsx = Math.floorDiv(nx, 32);
      int nsy = Math.floorDiv(ny, 32);
      int nsz = Math.floorDiv(nz, 32);
      WorldSection neighbourSection = sections.get(nsx, nsy, nsz);
      int id =
          neighbourSection == null
              ? -1
              : readBlockId(
                  neighbourSection,
                  Math.floorMod(nx, 32),
                  Math.floorMod(ny, 32),
                  Math.floorMod(nz, 32));
      neighbourIds[i] = id;
      if (id < 0) {
        missingNeighbour = true;
        neighbourStates[i] = Blocks.AIR.defaultBlockState();
      } else {
        neighbourStates[i] =
            id == 0 ? Blocks.AIR.defaultBlockState() : factory.getBlockStateForId(id);
      }
    }
    out.line(
        key
            + " neighbours missing="
            + missingNeighbour
            + " ids=[down="
            + neighbourIds[0]
            + ",up="
            + neighbourIds[1]
            + ",north="
            + neighbourIds[2]
            + ",south="
            + neighbourIds[3]
            + ",west="
            + neighbourIds[4]
            + ",east="
            + neighbourIds[5]
            + "]");

    int[] slots = new int[7];
    slots[0] = blockId;
    System.arraycopy(neighbourIds, 0, slots, 1, 6);
    for (int i = 0; i < slots.length; i++) {
      int raw = slots[i];
      int canonical = raw < 0 ? raw : factory.dumpCanonicalVariantSlot(raw);
      out.line(
          key
              + " variantSlot name="
              + SLOT_NAMES[i]
              + " raw="
              + raw
              + " canonical="
              + canonical
              + " canonicalHex=0x"
              + Integer.toHexString(canonical)
              + " desc=\""
              + factory.dumpDescribeVariantSlot(canonical)
              + "\"");
    }

    Integer variantValue = null;
    String variantState;
    if (lvl != 0) {
      variantState = "NOT_APPLICABLE_LEVEL";
    } else if (!ModelFactory.dtVariantsEnabled()) {
      variantState = "DISABLED_BY_FLAG";
    } else if (!factory.supportsVariantModels()) {
      variantState = "UNSUPPORTED_LOADER";
    } else if (missingNeighbour) {
      variantState = "SKIPPED_MISSING_NEIGHBOUR";
    } else {
      variantValue =
          factory.dumpPeekVariantModelId(
              blockId,
              neighbourIds[0],
              neighbourIds[1],
              neighbourIds[2],
              neighbourIds[3],
              neighbourIds[4],
              neighbourIds[5]);
      if (variantValue == null) {
        variantState = "ABSENT";
      } else if (variantValue == ModelFactory.VARIANT_MODEL_NOT_READY) {
        variantState = "NOT_READY";
      } else if (variantValue == ModelFactory.VARIANT_MODEL_UNAVAILABLE) {
        variantState = "UNAVAILABLE";
      } else if (variantValue <= 0) {
        variantState = "INVALID(" + variantValue + ")";
      } else {
        variantState = "BAKED";
      }
    }
    int variantModelId = (variantValue != null && variantValue > 0) ? variantValue : -1;
    out.line(
        key
            + " variant state="
            + variantState
            + " modelId="
            + (variantModelId < 0 ? "-" : variantModelId)
            + " variantsCapped="
            + factory.areVariantsCapped()
            + " depthMode="
            + ModelFactory.branchDepthModeName());
    if (variantModelId >= 0) {
      long variantMetadata = factory.dumpModelMetadata(variantModelId);
      dumpFaceLines(out, key, "variant", variantModelId, variantMetadata, factory);
    }

    dumpMasks(out, key, baseMetadata, variantModelId, factory);

    int dropMask = ModelFactory.dumpDropCapSidesMask(state, neighbourStates);
    out.line(
        key
            + " capFilter mode="
            + ModelFactory.branchCapFilterMode()
            + " installed="
            + !"none".equals(ModelFactory.branchCapFilterMode())
            + " dropMask=0x"
            + Integer.toHexString(dropMask)
            + " dropSides="
            + dropSides(dropMask)
            + " maxY="
            + CAP_MAX_Y);

    dumpQuadPipeline(
        out, key, sections, factory, state, neighbourStates, gx, gy, gz, dropMask, lvl);
  }

  private static void dumpFaceLines(
      Emitter out, String key, String prefix, int modelId, long metadata, ModelFactory factory) {
    for (int face = 0; face < 6; face++) {
      int packedMetadata = (int) ((metadata >>> (8 * face)) & 0xFF);
      boolean exists = ModelQueries.faceExists(metadata, face);
      boolean occludes = ModelQueries.faceOccludes(metadata, face);
      boolean fullBlock = (packedMetadata & 0b10) != 0;
      boolean canBeOccluded = ModelQueries.faceCanBeOccluded(metadata, face);
      boolean selfLighting = ModelQueries.faceUsesSelfLighting(metadata, face);
      int faceData = factory.getFaceData(modelId, face);
      String data;
      if (faceData < 0) {
        data = "faceData=-1 bounds=- indent=- cutout=- discardOverride=- tint=- perPixelDepth=-";
      } else {
        int b0 = faceData & 0xF;
        int b1 = (faceData >>> 4) & 0xF;
        int b2 = (faceData >>> 8) & 0xF;
        int b3 = (faceData >>> 12) & 0xF;
        int indentRaw = (faceData >>> 16) & 0x3F;
        boolean cutout = (faceData & (1 << 22)) != 0;
        boolean discardOverride = (faceData & (1 << 23)) != 0;
        int tint = (faceData >>> 24) & 0x3;
        boolean perPixelDepth = (faceData & (1 << 26)) != 0;
        data =
            "faceData=0x"
                + Integer.toHexString(faceData)
                + " bounds=["
                + b0
                + ","
                + b1
                + ","
                + b2
                + ","
                + b3
                + "] indent="
                + format3(indentRaw / 64.0f)
                + " cutout="
                + cutout
                + " discardOverride="
                + discardOverride
                + " tint="
                + tintName(tint)
                + " perPixelDepth="
                + perPixelDepth;
      }
      out.line(
          key
              + " "
              + prefix
              + " face="
              + FACE_ORDER[face].getSerializedName()
              + " metadata=0x"
              + Integer.toHexString(packedMetadata)
              + " exists="
              + exists
              + " occludes="
              + occludes
              + " fullBlock="
              + fullBlock
              + " canBeOccluded="
              + canBeOccluded
              + " selfLighting="
              + selfLighting
              + " "
              + data);
    }
  }

  private static void dumpMasks(
      Emitter out, String key, long baseMetadata, int variantModelId, ModelFactory factory) {
    String base = maskString(baseMetadata);
    if (variantModelId < 0) {
      out.line(key + " masks base[" + base + "] variant[N/A] maskPatched=N/A");
      return;
    }
    long variantMetadata = factory.dumpModelMetadata(variantModelId);
    String variant = maskString(variantMetadata);
    boolean patched =
        ModelQueries.isFullyOpaque(baseMetadata) != ModelQueries.isFullyOpaque(variantMetadata)
            || ModelQueries.isFluid(baseMetadata) != ModelQueries.isFluid(variantMetadata)
            || ModelQueries.containsFluid(baseMetadata)
                != ModelQueries.containsFluid(variantMetadata);
    out.line(
        key
            + " masks base["
            + base
            + "] variant["
            + variant
            + "] maskPatched="
            + patched
            + " source=recomputedFromModelMetadata");
  }

  private static String maskString(long metadata) {
    boolean opaque = ModelQueries.isFullyOpaque(metadata);
    boolean fluid = ModelQueries.isFluid(metadata);
    boolean containsFluid = ModelQueries.containsFluid(metadata);
    boolean nonOpaque = !opaque && !fluid;
    boolean hasCutout =
        !ModelQueries.isTranslucent(metadata) && ModelQueries.needsAlphaDiscard(metadata);
    return "opaque="
        + opaque
        + ",nonOpaque="
        + nonOpaque
        + ",fluid="
        + (fluid || containsFluid)
        + ",pureFluid="
        + fluid
        + ",containsFluid="
        + containsFluid
        + ",hasCutout="
        + hasCutout;
  }

  private static void dumpQuadPipeline(
      Emitter out,
      String key,
      SectionCache sections,
      ModelFactory factory,
      BlockState state,
      BlockState[] neighbourStates,
      int gx,
      int gy,
      int gz,
      int dropMask,
      int lvl) {
    if (state.getRenderShape() == RenderShape.INVISIBLE) {
      out.line(key + " quadPipeline skipped=INVISIBLE_RENDER_SHAPE");
      return;
    }
    BakedModel model;
    try {
      model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
    } catch (Throwable t) {
      out.line(
          key
              + " quadPipeline error=MODEL_LOOKUP_FAILED class="
              + t.getClass().getName()
              + " message=\""
              + String.valueOf(t.getMessage())
              + "\"");
      return;
    }
    boolean filterInstalled = !"none".equals(ModelFactory.branchCapFilterMode());
    boolean rootCube = DynamicTreesBranches.isSurfaceRoot(state) && lvl == 0;
    BlockState[] cube = rootCube ? buildCube(sections, factory, gx, gy, gz, state) : null;
    RandomSource random = new SingleThreadedRandomSource(42L);
    int total = 0;
    int kept = 0;
    int dropped = 0;
    int errorCount = 0;
    List<String> details = new ArrayList<>();
    boolean detailsTruncated = false;

    Direction[] queryOrder = {
      Direction.DOWN,
      Direction.UP,
      Direction.NORTH,
      Direction.SOUTH,
      Direction.WEST,
      Direction.EAST,
      null
    };
    for (Direction face : queryOrder) {
      List<BakedQuad> quads;
      try {
        quads =
            rootCube
                ? ModelQuadsAccess.get().getQuadsCube(model, state, face, random, cube)
                : ModelQuadsAccess.get().getQuads(model, state, face, random, neighbourStates);
      } catch (Throwable t) {
        errorCount++;
        if (details.size() < MAX_QUAD_LINES_PER_CELL) {
          details.add(
              key
                  + " quad face="
                  + (face == null ? "all" : face.getSerializedName())
                  + " result=ERROR class="
                  + t.getClass().getName()
                  + " message=\""
                  + String.valueOf(t.getMessage())
                  + "\"");
        } else {
          detailsTruncated = true;
        }
        continue;
      }
      if (quads == null) {
        quads = List.of();
      }
      for (BakedQuad quad : quads) {
        total++;
        boolean withinHeight = RootVertFilterBakedModel.withinHeight(quad, CAP_MAX_Y);
        boolean boundaryCap =
            filterInstalled && RootVertFilterBakedModel.isBoundaryCap(quad, dropMask);
        boolean drop = !withinHeight || boundaryCap;
        if (drop) {
          dropped++;
        } else {
          kept++;
        }
        if (details.size() >= MAX_QUAD_LINES_PER_CELL) {
          detailsTruncated = true;
          continue;
        }
        details.add(quadLine(key, face, quad, withinHeight, boundaryCap, drop));
      }
    }

    out.line(
        key
            + " quadPipeline context="
            + (rootCube ? "cube3x3x3" : "neighbours6")
            + " model="
            + (model == null ? "null" : "blockModel")
            + " filterInstalled="
            + filterInstalled
            + " quads="
            + total
            + " kept="
            + kept
            + " dropped="
            + dropped
            + " errors="
            + errorCount
            + " detailsTruncated="
            + detailsTruncated
            + " detailCap="
            + MAX_QUAD_LINES_PER_CELL);
    for (String line : details) {
      out.line(line);
    }
  }

  private static String quadLine(
      String key,
      Direction face,
      BakedQuad quad,
      boolean withinHeight,
      boolean boundaryCap,
      boolean drop) {
    int[] vertices = quad.getVertices();
    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float minZ = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float maxZ = Float.NEGATIVE_INFINITY;
    int count = Math.min(4, vertices.length / 8);
    for (int i = 0; i < count; i++) {
      float x = Float.intBitsToFloat(vertices[i * 8]);
      float y = Float.intBitsToFloat(vertices[i * 8 + 1]);
      float z = Float.intBitsToFloat(vertices[i * 8 + 2]);
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
      minZ = Math.min(minZ, z);
      maxZ = Math.max(maxZ, z);
    }
    String axis;
    float plane;
    if (Math.abs(maxX - minX) < 1.0e-3f) {
      axis = "X";
      plane = minX;
    } else if (Math.abs(maxY - minY) < 1.0e-3f) {
      axis = "Y";
      plane = minY;
    } else if (Math.abs(maxZ - minZ) < 1.0e-3f) {
      axis = "Z";
      plane = minZ;
    } else {
      axis = "NONE";
      plane = Float.NaN;
    }
    String reason;
    String capSide;
    if (boundaryCap) {
      reason = "boundaryCap";
      capSide = capSide(minX, maxX, minY, maxY, minZ, maxZ);
    } else if (!withinHeight) {
      reason = "aboveMaxY";
      capSide = "-";
    } else {
      reason = "kept";
      capSide = "-";
    }
    return key
        + " quad face="
        + (face == null ? "all" : face.getSerializedName())
        + " result="
        + (drop ? "DROPPED" : "KEPT")
        + " reason="
        + reason
        + " capSide="
        + capSide
        + " axis="
        + axis
        + " plane="
        + format3(plane)
        + " extents=["
        + format3(maxX - minX)
        + ","
        + format3(maxY - minY)
        + ","
        + format3(maxZ - minZ)
        + "] withinHeight="
        + withinHeight;
  }

  private static String capSide(
      float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
    if (near(minX, maxX) && near(minX, 0.0f)) {
      return Direction.WEST.getSerializedName();
    }
    if (near(minX, maxX) && near(minX, 16.0f)) {
      return Direction.EAST.getSerializedName();
    }
    if (near(minY, maxY) && near(minY, 0.0f)) {
      return Direction.DOWN.getSerializedName();
    }
    if (near(minY, maxY) && near(minY, 16.0f)) {
      return Direction.UP.getSerializedName();
    }
    if (near(minZ, maxZ) && near(minZ, 0.0f)) {
      return Direction.NORTH.getSerializedName();
    }
    if (near(minZ, maxZ) && near(minZ, 16.0f)) {
      return Direction.SOUTH.getSerializedName();
    }
    return "-";
  }

  private static boolean near(float value, float target) {
    return Math.abs(value - target) < 1.0e-3f;
  }

  private static BlockState[] buildCube(
      SectionCache sections, ModelFactory factory, int gx, int gy, int gz, BlockState center) {
    // Same 3x3x3 cell context Dynamic Trees roots read from ModelData. Missing cells are air.
    BlockState[] cube = new BlockState[27];
    for (int dy = -1; dy <= 1; dy++) {
      for (int dz = -1; dz <= 1; dz++) {
        for (int dx = -1; dx <= 1; dx++) {
          int i = (dx + 1) + 3 * (dy + 1) + 9 * (dz + 1);
          if (dx == 0 && dy == 0 && dz == 0) {
            cube[i] = center;
            continue;
          }
          int nx = gx + dx;
          int ny = gy + dy;
          int nz = gz + dz;
          WorldSection section =
              sections.get(Math.floorDiv(nx, 32), Math.floorDiv(ny, 32), Math.floorDiv(nz, 32));
          int id =
              section == null
                  ? -1
                  : readBlockId(
                      section, Math.floorMod(nx, 32), Math.floorMod(ny, 32), Math.floorMod(nz, 32));
          cube[i] = id <= 0 ? Blocks.AIR.defaultBlockState() : factory.getBlockStateForId(id);
        }
      }
    }
    return cube;
  }

  private static int readBlockId(WorldSection section, int x, int y, int z) {
    long raw = section._unsafeGetRawDataArray()[WorldSection.getIndex(x, y, z)];
    return Mapper.getBlockId(raw);
  }

  private static int rawModelId(ModelFactory factory, int blockId) {
    if (blockId < 0 || blockId >= (1 << 20)) {
      return -1;
    }
    return factory._unsafeRawAccess()[blockId];
  }

  /**
   * Which synthetic model the base (neighbour-less) bake path installs for this block, matching
   * ModelFactory's overrides. The quad pipeline below always uses the registered block model, which
   * is the model a LOD0 variant bake uses; this field marks cells where the installed base model
   * differs from the pipeline model.
   */
  private static String baseModelOverride(BlockState state) {
    if (DynamicTreesBranches.isTrunkShell(state)) {
      return "TRUNK_SHELL_PRISM_PATCH";
    }
    if (DynamicTreesBranches.isMushroomBranch(state) || DynamicTreesBranches.isThickBranch(state)) {
      return "SOLID_BARK_CUBE";
    }
    if (DynamicTreesBranches.isBranch(state)) {
      int radius = DynamicTreesBranches.radiusOf(state);
      if (radius >= 1 && radius <= DynamicTreesBranches.MAX_CELL_RADIUS) {
        return "THIN_BARK_COLUMN";
      }
    }
    return "NONE";
  }

  private static boolean isLeaf(BlockState state) {
    if (state == null || state.isAir()) {
      return false;
    }
    if (state.is(BlockTags.LEAVES)) {
      return true;
    }
    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    return id != null && id.getPath().contains("leaves");
  }

  private static String tintName(int tint) {
    return switch (tint) {
      case 1 -> "partial";
      case 2 -> "full";
      default -> "none";
    };
  }

  private static String dropSides(int mask) {
    if (mask == 0) {
      return "-";
    }
    StringBuilder sb = new StringBuilder();
    for (Direction dir : FACE_ORDER) {
      if ((mask & (1 << dir.get3DDataValue())) == 0) {
        continue;
      }
      if (sb.length() != 0) {
        sb.append(',');
      }
      sb.append(dir.getSerializedName());
    }
    return sb.toString();
  }

  private static String rendererName(boolean voxyActive, int lvl) {
    if (!voxyActive || VoxyClient.disableSodiumChunkRender()) {
      return "vanilla";
    }
    return lvl == 0 ? "voxy-lod0" : "voxy-lod" + lvl;
  }

  private static String format3(float value) {
    if (Float.isNaN(value)) {
      return "NaN";
    }
    return String.format(Locale.ROOT, "%.3f", value);
  }

  /** Bounded {@code [VoxyDump]}-prefixed emitter. */
  private static final class Emitter {
    private final Consumer<String> sink;
    private int lines;
    private boolean truncated;

    private Emitter(Consumer<String> sink) {
      this.sink = sink;
    }

    private void line(String text) {
      if (this.lines >= MAX_LINES) {
        this.truncated = true;
        return;
      }
      this.lines++;
      this.sink.accept("[VoxyDump] " + text);
    }
  }

  /**
   * Read-only section cache for one LOD. Uses {@link WorldEngine#acquireIfExists} so a dump never
   * force-loads a section or touches storage; absent sections read as unavailable/air.
   */
  private static final class SectionCache {
    private final WorldEngine engine;
    private final int lvl;
    private final Map<Long, WorldSection> acquired = new HashMap<>();
    private final Map<Long, Boolean> attempted = new HashMap<>();

    private SectionCache(WorldEngine engine, int lvl) {
      this.engine = engine;
      this.lvl = lvl;
    }

    private WorldSection get(int x, int y, int z) {
      long key = WorldEngine.getWorldSectionId(this.lvl, x, y, z);
      WorldSection section = this.acquired.get(key);
      if (section != null) {
        return section;
      }
      if (this.attempted.containsKey(key)) {
        return null;
      }
      this.attempted.put(key, Boolean.TRUE);
      try {
        section = this.engine.acquireIfExists(this.lvl, x, y, z);
      } catch (Throwable t) {
        section = null;
      }
      if (section != null) {
        this.acquired.put(key, section);
      }
      return section;
    }

    private void close() {
      for (WorldSection section : this.acquired.values()) {
        try {
          section.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        } catch (Throwable ignored) {
          // A section must not become unreleasable because a diagnostic failed; swallow here.
        }
      }
      this.acquired.clear();
      this.attempted.clear();
    }
  }
}
