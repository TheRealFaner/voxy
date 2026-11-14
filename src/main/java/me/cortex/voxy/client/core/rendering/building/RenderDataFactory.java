package me.cortex.voxy.client.core.rendering.building;

import java.util.Arrays;
import me.cortex.voxy.client.core.model.DTRootGeometryCache;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyFlags;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.DynamicTreesBranches;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

public class RenderDataFactory {
  private static final boolean BUILD_OCCUPANCY_SET = false;

  private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
  private static final boolean DISABLE_CULL_SAME_OCCLUDES =
      false; // TODO: FIX TRANSLUCENTS (e.g. stained glass) breaking on chunk boarders with this set
  // to false (it might be something else????)

  private static final boolean VERIFY_MESHING = VoxyFlags.isVerificationFlagOn("verifyMeshing");

  // DIVERGENCE FROM voxy-fabric: bridge walls for horizontally-adjacent fluids of different surface
  // heights. The shared fluid<->fluid boundary face is normally culled (XOR of two set fluid bits),
  // which is correct for equal-height water but leaves a see-through gap in the [shorterH, tallerH]
  // band where two flowing-water columns of different heights meet. Fabric ships with this gap
  // (only
  // visible up close; voxy renders distant terrain where it is sub-pixel). When enabled, we re-add
  // that boundary face on the HORIZONTAL passes only (X- and Z-normal). Every fluid voxel bakes
  // full-height side walls (upper neighbour always reports fluid), and the Metal translucent raster
  // is double-sided (MTLCullModeNone), so a single re-added face fills the gap from both views. The
  // trade-off is a ~1/8-block "lip" above the taller surface at the step, consistent with the
  // existing water-air "cup" rim. Y-normal (top/bottom) faces are never bridged: that would insert
  // a
  // horizontal sheet inside a flowing-water column. Toggle with -Dvoxy.fluidBridgeWalls=false.
  private static final boolean FLUID_BRIDGE_WALLS =
      Boolean.parseBoolean(System.getProperty("voxy.fluidBridgeWalls", "true"));

  // TODO: MAKE a render cache that caches each WorldSection directional face generation, cause then
  // can just pull that directly
  // instead of needing to regen the entire thing

  // Ok so the idea for fluid rendering is to make it use a seperate mesher and use a different code
  // path for it
  // since fluid states are explicitly overlays over the base block
  // can do funny stuff like double rendering

  private final WorldEngine world;
  private final ModelFactory modelMan;

  // private final long[] sectionData = new long[32*32*32*2];
  private final long[] sectionData = new long[32 * 32 * 32 * 2];
  private final long[] neighboringFaces = new long[32 * 32 * 6];
  // private final int[] neighboringOpaqueMasks = new int[32*6];

  // LOD0 Dynamic Trees branch blocks, recorded during prepareSectionData and resolved to a
  // per-position variant model id after acquireNeighborData has filled neighboringFaces.
  private final int[] variantBlockIndices = new int[32 * 32 * 32];
  private int variantBlockCount = 0;
  // LOD0 thick-trunk shell cells (dynamictrees:trunk_shell next to a thick branch core).
  private final int[] shellBlockIndices = new int[32 * 32 * 32];
  private int shellBlockCount = 0;
  // LOD0 Dynamic Trees surface roots (3x3x3 neighbour context).
  private final int[] rootBlockIndices = new int[32 * 32 * 32];
  private int rootBlockCount = 0;
  // Stage 2b-1: depth-band plane model ids per successfully patched root cell (same indexing as
  // rootBlockIndices). Only populated when the multi-plane flag is on; cleared at the start of
  // every mesh so entries never leak between sections.
  private final int[][] rootCellPlanes = new int[32 * 32 * 32][];
  // Trunk-facing root sides per patched root cell. Mirrors
  // CompositeRootShellBakedModel.hiddenRootSides for the extra plane model ids, which the composite
  // does not filter: their quads would protrude through / leave a gap at the thick-trunk shell
  // wall. 6-bit per face: bit0 DOWN, bit1 UP, bit2 NORTH, bit3 SOUTH, bit4 WEST, bit5 EAST.
  private final int[] rootCellHiddenSides = new int[32 * 32 * 32];
  // True-geometry overlay: exact root cells captured for the vanilla render pass.
  private final java.util.List<DTRootGeometryCache.RootBlock> overlayRoots =
      new java.util.ArrayList<>();

  // One-shot LOD0 diagnostics: prove from the log whether root/shell variants are actually
  // applied in a section and which fallback dominates.
  private int rootPatchedCount = 0;
  private int rootUnavailableCount = 0;
  private int rootMaskRejectedCount = 0;
  private int rootPlaneQuadCount = 0;
  private int rootPlaneBoundarySkipped = 0;
  private int rootPlaneHiddenSkipped = 0;
  private int shellPatchedCount = 0;
  private int shellUnavailableCount = 0;
  // Branch cells whose section masks were patched to match a neighbour-aware variant (one-shot
  // diag only).
  private int branchMaskPatchedCount = 0;
  // Per-section shell skip reasons (one-shot diag only).
  private int shellSkippedCornerBothOutside = 0;
  private int shellSkippedMskBitMissing = 0;
  private int shellSkippedCoreNotThick = 0;
  private int shellSkippedCoreDirNull = 0;
  // Render-distance seam fallback: the LOD0 core reads as air because its chunk lies beyond the
  // vanilla render distance, so the core state is read from the LOD1 mip section instead
  // (one-shot diag only).
  private int shellLod1FallbackCount = 0;
  private int rootLod1FallbackCount = 0;
  private static final java.util.concurrent.atomic.AtomicInteger SECTION_DIAG_LOGS =
      new java.util.concurrent.atomic.AtomicInteger();

  private final int[] opaqueMasks = new int[32 * 32];
  private final int[] nonOpaqueMasks = new int[32 * 32];
  private final int[] fluidMasks =
      new int[32 * 32]; // Used to separately mesh fluids, allowing for fluid + blockstate

  // TODO: emit directly to memory buffer instead of long arrays

  // Each axis gets a max quad count of 2^16 (65536 quads) since that is the max the basic geometry
  // manager can handle
  private final MemoryBuffer quadBuffer =
      new MemoryBuffer(8 * (8 * (1 << 16))); // 6 faces + dual direction + translucents
  private final long quadBufferPtr = this.quadBuffer.address;
  private final int[] quadCounters = new int[8];

  private int minX;
  private int minY;
  private int minZ;
  private int maxX;
  private int maxY;
  private int maxZ;

  private int quadCount = 0;
  private boolean hasCutout = false;

  private final OccupancySet occupancy;

  // Wont work for double sided quads
  private final class Mesher extends ScanMesher2D {
    public int auxiliaryPosition = 0;
    public boolean doAuxiliaryFaceOffset = true;
    public int axis = 0; // Y,Z,X

    // Note x, z are in top right
    @Override
    protected void emitQuad(int x, int z, int length, int width, long data) {
      if (VERIFY_MESHING) {
        if (length < 1 || length > 16) {
          throw new IllegalStateException("length out of bounds: " + length);
        }
        if (width < 1 || width > 16) {
          throw new IllegalStateException("width out of bounds: " + width);
        }
        if (x < 0 || x > 31) {
          throw new IllegalStateException("x out of bounds: " + x);
        }
        if (z < 0 || z > 31) {
          throw new IllegalStateException("z out of bounds: " + z);
        }
        if (x - (length - 1) < 0 || z - (width - 1) < 0) {
          throw new IllegalStateException(
              "dim out of bounds: " + (x - (length - 1)) + ", " + (z - (width - 1)));
        }
      }

      RenderDataFactory.this.quadCount++;

      x -= length - 1;
      z -= width - 1;

      if (this.axis == 2) {
        // Need to swizzle the data if on x axis
        int tmp = x;
        x = z;
        z = tmp;

        tmp = length;
        length = width;
        width = tmp;
      }

      // Lower 26 bits can be auxiliary data since that is where quad position information goes;
      int auxData = (int) (data & ((1 << 26) - 1));
      data &= ~((1L << 26) - 1);

      int axisSide = auxData & 1;
      int type = (auxData >> 1) & 3; // Translucent, double side, directional

      if (VERIFY_MESHING) {
        if (type == 3) {
          throw new IllegalStateException();
        }
      }

      // Shift up if is negative axis
      int auxPos = this.auxiliaryPosition;
      auxPos += 1 - (this.doAuxiliaryFaceOffset ? axisSide : 1); // Shift

      if (VERIFY_MESHING) {
        if (auxPos > 31) {
          throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
        }
      }

      final int axis = this.axis;
      int face = (axis << 1) | axisSide;

      int encodedPosition = face;
      encodedPosition |= ((width - 1) << 7) | ((length - 1) << 3);
      encodedPosition |= x << (axis == 2 ? 16 : 21);
      encodedPosition |= z << (axis == 1 ? 16 : 11);
      int shiftAmount = axis == 0 ? 16 : (axis == 1 ? 11 : 21);
      // shiftAmount += ;
      encodedPosition |= auxPos << (shiftAmount);

      long quad = data | Integer.toUnsignedLong(encodedPosition);

      int bufferIdx = type + (type == 2 ? face : 0); // Translucent, double side, directional
      long bufferOffset =
          (RenderDataFactory.this.quadCounters[bufferIdx]++) * 8L + bufferIdx * 8L * (1 << 16);
      MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);

      // Update AABB bounds
      if (axis == 0) { // Y
        RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
        RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos);

        RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
        RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

        RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
        RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
      } else if (axis == 1) { // Z
        RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
        RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos);

        RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
        RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

        RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
        RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
      } else { // X
        RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
        RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos);

        RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
        RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);

        RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
        RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
      }
    }
  }

  private final Mesher blockMesher = new Mesher();
  private final Mesher seondaryblockMesher = new Mesher(); // Used for dual non-opaque geometry
  // Scratch mesher for isolated per-plane root quads. Used only via emitQuad (length/width 1), so
  // its scanline state is never touched and it must not be finished/reset like the mesh loops.
  private final Mesher rootPlaneMesher = new Mesher();

  public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
    this(world, modelManager, emitMeshlets, false);
  }

  public RenderDataFactory(
      WorldEngine world,
      ModelFactory modelManager,
      boolean emitMeshlets,
      boolean generateOccupancy) {
    this.world = world;
    this.modelMan = modelManager;
    if (generateOccupancy && BUILD_OCCUPANCY_SET) {
      this.occupancy = new OccupancySet();
    } else {
      this.occupancy = null;
    }
  }

  private static long getQuadTyping(long metadata) { // 2 bits
    return 0b111L
        & (0b000_000_010_100L
            >> (ModelQueries._isTranslucent(metadata) * 6
                + ModelQueries._isDoubleSided(metadata) * 3));
  }

  private static long packPartialQuadData(int modelId, long state, long metadata) {
    // This uses hardcoded data to shuffle things
    long lightAndBiome = (state & ((0x1FFL << 47) | (0xFFL << 56))) >>> 1;
    lightAndBiome &=
        ~(ModelQueries._notIsBiomeColoured(metadata)
            * (0x1FFL << 46)); // 46 not 47 because is already shifted by 1 THIS WASTED 4 HOURS ;-;
    // aaaaaAAAAAA
    lightAndBiome &=
        ~(ModelQueries._isFullyOpaque(metadata)
            * (0xFFL << 55)); // If its fully opaque it always uses neighbor light?

    long quadData = lightAndBiome;
    quadData |= Integer.toUnsignedLong(modelId) << 26;
    quadData |= getQuadTyping(metadata); // Returns the typing already shifted by 1
    return quadData;
  }

  private int prepareSectionData(final long[] rawSectionData, boolean lod0) {
    final var sectionData = this.sectionData;
    final var rawModelIds = this.modelMan._unsafeRawAccess();
    final long airMetadata = this.modelMan.getModelMetadataFromClientId(0);
    final boolean collectVariants = lod0 && this.modelMan.supportsVariantModels();
    // Root cells must be resolved (and suppressed) at LOD0 even where variant baking is
    // unsupported, so that the master switch can keep every DT root out of the Voxy mesh. When
    // the switch is on this adds nothing: variants-capable loaders already collect them above.
    final boolean collectRoots = collectVariants || (lod0 && !DTRootGeometryCache.ROOTS_ENABLED);
    long opaque = 0;
    long notEmpty = 0;
    long pureFluid = 0;
    long partialFluid = 0;

    int neighborAcquireMskAndFlags = 0; // -+x, -+z, -+y
    int i = 0;
    for (int q = 0; q < 512; q++) {
      for (int j = 0; j < 64; i++, j++) {
        long block = rawSectionData[i]; // Get the block mapping
        if (Mapper.isAir(block)) { // If it is air, just emit lighting
          sectionData[i * 2] = (block & (0xFFL << 56)) >>> 1;
          sectionData[i * 2 + 1] = 0;
        } else {
          int blockId = Mapper.getBlockId(block);
          int modelId = rawModelIds[blockId];
          if (modelId == -1) { // Failed, so just return error
            return blockId | (1 << 31);
          }
          if (collectVariants
              && modelId != 0
              && this.modelMan.isVariantEligibleBranchBlockId(blockId)) {
            this.variantBlockIndices[this.variantBlockCount++] = i;
          }
          boolean trunkShellForLod0 = false;
          if (collectVariants && this.modelMan.isTrunkShellBlockId(blockId)) {
            // Shell states bake to the empty/air model, so they are collected by block id and
            // marked non-empty below so the mesher traverses the cell after it is patched.
            this.shellBlockIndices[this.shellBlockCount++] = i;
            trunkShellForLod0 = true;
          }
          boolean rootForLod0 = false;
          if (collectRoots && this.modelMan.isSurfaceRootBlockId(blockId)) {
            this.rootBlockIndices[this.rootBlockCount++] = i;
            rootForLod0 = true;
          }
          if (modelId == 0) { // modelId == 0, its basicly air so set it as air
            sectionData[i * 2] = (block & (0xFFL << 56)) >>> 1;
            boolean mayGetVariant = trunkShellForLod0 || rootForLod0;
            // Use the air model's real metadata for candidates: metadata 0 makes faceExists()
            // true for every face and would emit phantom model-0 quads if the variant never
            // resolves.
            sectionData[i * 2 + 1] = mayGetVariant ? airMetadata : 0;
            if (mayGetVariant) {
              // Non-opaque candidate: the variant resolver may install geometry here (root base
              // models can dedupe to air, e.g. grounded=false DT 1.7.x roots).
              notEmpty |= 1L << j;
            }
          } else {
            // TODO: cache the results of this, then link it to `block` do same optimization as
            // SaveLoadSystem3

            long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

            sectionData[i * 2] = packPartialQuadData(modelId, block, modelMetadata);
            sectionData[i * 2 + 1] = modelMetadata;

            notEmpty |= 1L << j;
            opaque |= ModelQueries._isFullyOpaque(modelMetadata) << j;
            pureFluid |= ModelQueries._isFluid(modelMetadata) << j;
            partialFluid |= ModelQueries._containsFluid(modelMetadata) << j;
            this.hasCutout |=
                !ModelQueries.isTranslucent(modelMetadata)
                    && ModelQueries.needsAlphaDiscard(modelMetadata);
          }
        }
      }
      if (notEmpty != 0) {
        long nonOpaque = (notEmpty ^ opaque) & ~pureFluid;
        long fluid = pureFluid | partialFluid;
        this.opaqueMasks[(i >> 5) - 2] = (int) opaque;
        this.opaqueMasks[(i >> 5) - 1] = (int) (opaque >>> 32);
        this.nonOpaqueMasks[(i >> 5) - 2] = (int) nonOpaque;
        this.nonOpaqueMasks[(i >> 5) - 1] = (int) (nonOpaque >>> 32);
        this.fluidMasks[(i >> 5) - 2] = (int) fluid;
        this.fluidMasks[(i >> 5) - 1] = (int) (fluid >>> 32);

        neighborAcquireMskAndFlags |= getNeighborMsk(notEmpty, i);
        neighborAcquireMskAndFlags |= opaque != 0 ? (1 << 6) : 0;

        opaque = 0;
        notEmpty = 0;
        pureFluid = 0;
        partialFluid = 0;
      }
    }
    return neighborAcquireMskAndFlags;
  }

  private static int getNeighborMsk(long notEmpty, int i) {
    int neighborMsk = 0;
    // Explicit full-face form of the +/-x neighbour test. One 64-cell group spans the full x
    // range twice (j=0..31 is the x=0..31 row at z, j=32..63 the same row at z+1), so masking
    // both row copies covers every x=0 and x=31 cell. This is the same mask the previous folded
    // form produced; the mask only decides whether one face slice per direction is acquired, so
    // full-face coverage costs nothing extra.
    if ((notEmpty & 0x0000_0001_0000_0001L) != 0) neighborMsk |= 0b000001; // -x, x=0 row
    if ((notEmpty & 0x8000_0000_8000_0000L) != 0) neighborMsk |= 0b000010; // +x, x=31 row

    int packedEmpty = (int) ((notEmpty >>> 32) | notEmpty);
    // notEmpty = (notEmpty != 0)?1:0;
    neighborMsk += ((((i - 1) >> 10) == 0) ? 0b100 : 0) * (packedEmpty != 0 ? 1 : 0); // -y
    neighborMsk += ((((i - 1) >> 10) == 31) ? 0b1000 : 0) * (packedEmpty != 0 ? 1 : 0); // +y
    neighborMsk +=
        (((((i - 33) >> 5) & 0x1F) == 0) ? 0b10000 : 0) * (((int) notEmpty) != 0 ? 1 : 0); // -z
    neighborMsk +=
        (((((i - 1) >> 5) & 0x1F) == 31) ? 0b100000 : 0) * ((notEmpty >>> 32) != 0 ? 1 : 0); // +z
    return neighborMsk;
  }

  private void acquireNeighborData(WorldSection section, int msk) {
    // TODO: fixme!!! its probably more efficent to just access the raw section array on demand
    // instead of copying it
    if ((msk & 1) != 0) { // -x
      var sec = this.world.acquire(section.lvl, section.x - 1, section.y, section.z);
      // Note this is not thread safe! (but eh, fk it)
      var raw = sec._unsafeGetRawDataArray();
      for (int i = 0; i < 32 * 32; i++) {
        this.neighboringFaces[i] = raw[(i << 5) + 31]; // pull the +x faces from the section
      }
      sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }
    if ((msk & 2) != 0) { // +x
      var sec = this.world.acquire(section.lvl, section.x + 1, section.y, section.z);
      // Note this is not thread safe! (but eh, fk it)
      var raw = sec._unsafeGetRawDataArray();
      for (int i = 0; i < 32 * 32; i++) {
        this.neighboringFaces[i + 32 * 32] = raw[(i << 5)]; // pull the -x faces from the section
      }
      sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }

    if ((msk & 4) != 0) { // -y
      var sec = this.world.acquire(section.lvl, section.x, section.y - 1, section.z);
      // Note this is not thread safe! (but eh, fk it)
      var raw = sec._unsafeGetRawDataArray();
      for (int i = 0; i < 32 * 32; i++) {
        this.neighboringFaces[i + 32 * 32 * 2] =
            raw[i | (0x1F << 10)]; // pull the +y faces from the section
      }
      sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }
    if ((msk & 8) != 0) { // +y
      var sec = this.world.acquire(section.lvl, section.x, section.y + 1, section.z);
      // Note this is not thread safe! (but eh, fk it)
      var raw = sec._unsafeGetRawDataArray();
      for (int i = 0; i < 32 * 32; i++) {
        this.neighboringFaces[i + 32 * 32 * 3] = raw[i]; // pull the -y faces from the section
      }
      sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }

    if ((msk & 16) != 0) { // -z
      var sec = this.world.acquire(section.lvl, section.x, section.y, section.z - 1);
      // Note this is not thread safe! (but eh, fk it)
      var raw = sec._unsafeGetRawDataArray();
      for (int i = 0; i < 32 * 32; i++) {
        this.neighboringFaces[i + 32 * 32 * 4] =
            raw[
                Integer.expand(i, 0b11111_00000_11111)
                    | (0x1F << 5)]; // pull the +z faces from the section
      }
      sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }
    if ((msk & 32) != 0) { // +z
      var sec = this.world.acquire(section.lvl, section.x, section.y, section.z + 1);
      // Note this is not thread safe! (but eh, fk it)
      var raw = sec._unsafeGetRawDataArray();
      for (int i = 0; i < 32 * 32; i++) {
        this.neighboringFaces[i + 32 * 32 * 5] =
            raw[Integer.expand(i, 0b11111_00000_11111)]; // pull the -z faces from the section
      }
      sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }
  }

  // Sentinel for "the face slice needed to resolve this neighbour was not acquired". Air is a
  // valid block id (0), so a distinct value is required.
  private static final int NEIGHBOR_UNAVAILABLE = -1;

  /**
   * Reads a neighbour block id from the section or from the boundary face slices filled by {@link
   * #acquireNeighborData}. Raw section layout is {@code x + 32*z + 1024*y}; the six
   * neighboringFaces slices use their own 2D packing, matching acquireNeighborData.
   */
  private int getNeighborBlockId(
      long[] rawSectionData, int idx, Direction direction, int neighborMsk) {
    int x = idx & 31;
    int z = (idx >> 5) & 31;
    int y = idx >> 10;
    switch (direction) {
      case DOWN: // -y
        if (y > 0) {
          return Mapper.getBlockId(rawSectionData[idx - 32 * 32]);
        }
        if ((neighborMsk & 4) == 0) return NEIGHBOR_UNAVAILABLE;
        return Mapper.getBlockId(this.neighboringFaces[2 * 32 * 32 + x + (z << 5)]);
      case UP: // +y
        if (y < 31) {
          return Mapper.getBlockId(rawSectionData[idx + 32 * 32]);
        }
        if ((neighborMsk & 8) == 0) return NEIGHBOR_UNAVAILABLE;
        return Mapper.getBlockId(this.neighboringFaces[3 * 32 * 32 + x + (z << 5)]);
      case NORTH: // -z
        if (z > 0) {
          return Mapper.getBlockId(rawSectionData[idx - 32]);
        }
        if ((neighborMsk & 16) == 0) return NEIGHBOR_UNAVAILABLE;
        return Mapper.getBlockId(this.neighboringFaces[4 * 32 * 32 + x + (y << 5)]);
      case SOUTH: // +z
        if (z < 31) {
          return Mapper.getBlockId(rawSectionData[idx + 32]);
        }
        if ((neighborMsk & 32) == 0) return NEIGHBOR_UNAVAILABLE;
        return Mapper.getBlockId(this.neighboringFaces[5 * 32 * 32 + x + (y << 5)]);
      case WEST: // -x
        if (x > 0) {
          return Mapper.getBlockId(rawSectionData[idx - 1]);
        }
        if ((neighborMsk & 1) == 0) return NEIGHBOR_UNAVAILABLE;
        return Mapper.getBlockId(this.neighboringFaces[y * 32 + z]);
      case EAST: // +x
        if (x < 31) {
          return Mapper.getBlockId(rawSectionData[idx + 1]);
        }
        if ((neighborMsk & 2) == 0) return NEIGHBOR_UNAVAILABLE;
        return Mapper.getBlockId(this.neighboringFaces[32 * 32 + y * 32 + z]);
      default:
        return NEIGHBOR_UNAVAILABLE;
    }
  }

  /**
   * Replaces the base model id of every recorded Dynamic Trees branch block with its per-position
   * variant model id. Must run after {@link #acquireNeighborData} (boundary neighbours) and before
   * face generation. The section masks were built from the base model, so every installed variant
   * patches the cell's opaque/non-opaque/fluid bits to match its own metadata.
   */
  private void resolveBranchVariants(long[] rawSectionData, int neighborMsk) {
    final var rawModelIds = this.modelMan._unsafeRawAccess();
    // Collect/request every not-ready variant in this pass and only then abort the section once.
    // Throwing on the first miss forced one full section rebuild per distinct neighbour
    // combination (prepareSectionData + neighbour acquisition), which serializes badly in forests.
    boolean anyNotReady = false;
    int notReadyBlockId = 0;
    for (int i = 0; i < this.variantBlockCount; i++) {
      int idx = this.variantBlockIndices[i];
      int down = this.getNeighborBlockId(rawSectionData, idx, Direction.DOWN, neighborMsk);
      int up = this.getNeighborBlockId(rawSectionData, idx, Direction.UP, neighborMsk);
      int north = this.getNeighborBlockId(rawSectionData, idx, Direction.NORTH, neighborMsk);
      int south = this.getNeighborBlockId(rawSectionData, idx, Direction.SOUTH, neighborMsk);
      int west = this.getNeighborBlockId(rawSectionData, idx, Direction.WEST, neighborMsk);
      int east = this.getNeighborBlockId(rawSectionData, idx, Direction.EAST, neighborMsk);
      if (down == NEIGHBOR_UNAVAILABLE
          || up == NEIGHBOR_UNAVAILABLE
          || north == NEIGHBOR_UNAVAILABLE
          || south == NEIGHBOR_UNAVAILABLE
          || west == NEIGHBOR_UNAVAILABLE
          || east == NEIGHBOR_UNAVAILABLE) {
        // A boundary slice was genuinely not acquired; keep the base model rather than guessing.
        // Missing sections are AIR-filled, so this does not fire at normal LOD seams.
        continue;
      }
      final int blockId = Mapper.getBlockId(rawSectionData[idx]);
      int variantModelId =
          this.modelMan.getVariantModelId(blockId, down, up, north, south, west, east);
      if (variantModelId == ModelFactory.VARIANT_MODEL_NOT_READY) {
        // Lazy bake requested by getVariantModelId. Keep requesting the remaining variants so a
        // single retry can satisfy the whole section, then let the existing retry mechanism
        // re-run this section once the bakes land.
        anyNotReady = true;
        if (notReadyBlockId == 0) {
          notReadyBlockId = blockId;
        }
        continue;
      }
      if (variantModelId <= 0) {
        continue; // VARIANT_MODEL_UNAVAILABLE, or the empty/air model: keep the base model.
      }

      long block = rawSectionData[idx];
      long metadata = this.modelMan.getModelMetadataFromClientId(variantModelId);
      // The masks were built from the base model. A variant can legitimately flip opaque/non-opaque
      // and fluid bits (capped ends, different faces), so patch this cell's bits to match the
      // installed model instead of rejecting a valid variant and leaving the capped base bake in
      // place: that rejection is what opened the vertical slit at thin-trunk section borders.
      long baseMetadata = this.modelMan.getModelMetadataFromClientId(rawModelIds[blockId]);
      int maskIndex = idx >> 5;
      int bit = 1 << (idx & 31);
      this.opaqueMasks[maskIndex] &= ~bit;
      this.nonOpaqueMasks[maskIndex] &= ~bit;
      this.fluidMasks[maskIndex] &= ~bit;
      boolean fullyOpaque = ModelQueries._isFullyOpaque(metadata) != 0;
      boolean pureFluid = ModelQueries._isFluid(metadata) != 0;
      boolean containsFluid = ModelQueries._containsFluid(metadata) != 0;
      if (fullyOpaque) {
        this.opaqueMasks[maskIndex] |= bit;
      }
      if (!fullyOpaque && !pureFluid) {
        this.nonOpaqueMasks[maskIndex] |= bit;
      }
      if (pureFluid || containsFluid) {
        this.fluidMasks[maskIndex] |= bit;
      }
      this.hasCutout |=
          !ModelQueries.isTranslucent(metadata) && ModelQueries.needsAlphaDiscard(metadata);
      if (ModelQueries._isFullyOpaque(baseMetadata) != ModelQueries._isFullyOpaque(metadata)
          || ModelQueries._isFluid(baseMetadata) != ModelQueries._isFluid(metadata)
          || ModelQueries._containsFluid(baseMetadata) != ModelQueries._containsFluid(metadata)) {
        this.branchMaskPatchedCount++;
        if (VARIANT_MASK_PATCH_LOG.compareAndSet(false, true)) {
          Logger.warn(
              "Dynamic Trees variant masks patched: block "
                  + blockId
                  + " (base="
                  + baseMetadata
                  + ", variant="
                  + metadata
                  + ")");
        }
      }
      this.sectionData[idx * 2] = packPartialQuadData(variantModelId, block, metadata);
      this.sectionData[idx * 2 + 1] = metadata;
    }
    if (anyNotReady) {
      throw new IdNotYetComputedException(notReadyBlockId, false);
    }
  }

  /**
   * Replaces the (empty) model of each {@code trunk_shell} cell adjacent to a thick Dynamic Trees
   * trunk with the pre-baked prism piece for that cell. This restores the trunk's real 2x2/3x3
   * width at LOD0; a plain bark cube only covered the core cell.
   */
  private void resolveTrunkShells(long[] rawSectionData, int neighborMsk, WorldSection section) {
    boolean anyNotReady = false;
    int notReadyBlockId = 0;
    for (int i = 0; i < this.shellBlockCount; i++) {
      int idx = this.shellBlockIndices[i];
      int shellBlockId = Mapper.getBlockId(rawSectionData[idx]);
      int[] coreDir = DynamicTreesBranches.coreDir(this.modelMan.getBlockStateForId(shellBlockId));
      if (coreDir == null) {
        this.shellSkippedCoreDirNull++;
        continue;
      }
      int cdx = coreDir[0];
      int cdz = coreDir[1];
      int x = idx & 31;
      int z = (idx >> 5) & 31;
      int y = idx >> 10;
      int coreX = x + cdx;
      int coreZ = z + cdz;
      int coreBlockId;
      final boolean outX = coreX < 0 || coreX > 31;
      final boolean outZ = coreZ < 0 || coreZ > 31;
      if (!outX && !outZ) {
        coreBlockId = Mapper.getBlockId(rawSectionData[coreX + 32 * coreZ + 1024 * y]);
      } else if (outX && outZ) {
        // Both core offsets point outside: only possible at a section corner, where no single
        // face slice holds the core. Read it from the diagonally adjacent section instead of
        // adding another slice; if that section is not resident, keep the cell empty.
        coreBlockId = this.readDiagonalCoreId(section, x, y, z, cdx, cdz);
        if (coreBlockId == DIAGONAL_CELL_UNAVAILABLE) {
          this.shellSkippedCornerBothOutside++;
          continue;
        }
      } else if (outX) {
        // Diagonal or cardinal core with x outside: the ±x face slice spans the whole y/z face,
        // so index it at the core's z (in range even when the shell cell's z differs).
        if (cdx < 0) {
          if ((neighborMsk & 1) == 0) {
            this.shellSkippedMskBitMissing++;
            continue;
          }
          coreBlockId = Mapper.getBlockId(this.neighboringFaces[y * 32 + coreZ]);
        } else {
          if ((neighborMsk & 2) == 0) {
            this.shellSkippedMskBitMissing++;
            continue;
          }
          coreBlockId = Mapper.getBlockId(this.neighboringFaces[32 * 32 + y * 32 + coreZ]);
        }
      } else {
        // Diagonal or cardinal core with z outside: the ±z face slice spans the whole x/y face,
        // so index it at the core's x (in range even when the shell cell's x differs).
        if (cdz < 0) {
          if ((neighborMsk & 16) == 0) {
            this.shellSkippedMskBitMissing++;
            continue;
          }
          coreBlockId = Mapper.getBlockId(this.neighboringFaces[4 * 32 * 32 + coreX + (y << 5)]);
        } else {
          if ((neighborMsk & 32) == 0) {
            this.shellSkippedMskBitMissing++;
            continue;
          }
          coreBlockId = Mapper.getBlockId(this.neighboringFaces[5 * 32 * 32 + coreX + (y << 5)]);
        }
      }
      boolean lod1Fallback = false;
      if (coreBlockId <= 0) {
        // The LOD0 core cell is air/unavailable: the core's chunk can lie beyond the vanilla
        // render distance while VSS still holds LOD1 data there. Read the core state at the exact
        // world position from the LOD1 mip section so the ring keeps its trunk wall.
        int worldX = (section.x << 5) + x + cdx;
        int worldY = (section.y << 5) + y;
        int worldZ = (section.z << 5) + z + cdz;
        int lod1CoreId = this.readBlockIdAtLevel(section.lvl + 1, worldX, worldY, worldZ);
        if (lod1CoreId > 0 && this.modelMan.isThickBranchBlockId(lod1CoreId)) {
          coreBlockId = lod1CoreId;
          lod1Fallback = true;
          this.shellLod1FallbackCount++;
        }
      }
      if (coreBlockId <= 0 || !this.modelMan.isThickBranchBlockId(coreBlockId)) {
        this.shellSkippedCoreNotThick++;
        continue;
      }
      int capBits = 0;
      BlockState coreState = this.modelMan.getBlockStateForId(coreBlockId);
      int coreRadius = DynamicTreesBranches.radiusOf(coreState);
      int coreUp = this.readRootNeighbourId(rawSectionData, x, y, z, cdx, 1, cdz, neighborMsk);
      if (coreUp < 0
          || !(this.modelMan.isThickBranchBlockId(coreUp)
              && DynamicTreesBranches.radiusOf(this.modelMan.getBlockStateForId(coreUp))
                  >= coreRadius)) {
        // Vanilla emits the trunk's top cap where the connection above is thinner (or absent).
        capBits |= 1;
      }
      int coreDown = this.readRootNeighbourId(rawSectionData, x, y, z, cdx, -1, cdz, neighborMsk);
      if (coreDown < 0) {
        // Context outside this section: keep the conservative cap rather than risk a hole.
        capBits |= 2;
      } else {
        BlockState downState = this.stateForIdSafe(coreDown);
        int connDown;
        if (this.modelMan.isThickBranchBlockId(coreDown)) {
          // DT compares the raw connection radius with the core radius, so a thinner thick trunk
          // below still gets a bottom bark cap (the old "any thick below" test skipped it and
          // left an annular hole at downward radius steps).
          connDown = DynamicTreesBranches.radiusOf(downState);
        } else if (DynamicTreesBranches.isAirOrWater(downState)
            || this.modelMan.isSurfaceRootBlockId(coreDown)) {
          connDown = 0;
        } else {
          // Soil and other tree parts report a connection radius of at least 1 (rooty soil: 8).
          connDown = 8;
        }
        boolean capDown = connDown < coreRadius;
        if (capDown && connDown < 1) {
          // DT's DOWN ring query is empty, so for a twig-sized connection it draws no bottom face
          // unless horizontal branches exist around the core.
          capDown = this.coreHasHorizontalBranch(rawSectionData, x, y, z, cdx, cdz, neighborMsk);
        }
        if (capDown) {
          capBits |= 2;
        }
      }
      // The fallback core was read from the LOD1 mip, where the LOD0 up/down context around it is
      // unavailable/unreliable: cap both ends conservatively instead of trusting those reads.
      int shellModelId =
          this.modelMan.getShellModelId(coreBlockId, -cdx, -cdz, lod1Fallback ? 3 : capBits);
      if (shellModelId == ModelFactory.VARIANT_MODEL_NOT_READY) {
        anyNotReady = true;
        if (notReadyBlockId == 0) {
          notReadyBlockId = coreBlockId;
        }
        continue;
      }
      if (shellModelId <= 0) {
        if (shellModelId == ModelFactory.VARIANT_MODEL_UNAVAILABLE) {
          this.shellUnavailableCount++;
        }
        continue;
      }
      long block = rawSectionData[idx];
      long metadata = this.modelMan.getModelMetadataFromClientId(shellModelId);
      this.sectionData[idx * 2] = packPartialQuadData(shellModelId, block, metadata);
      this.sectionData[idx * 2 + 1] = metadata;
      this.shellPatchedCount++;
    }
    if (anyNotReady) {
      throw new IdNotYetComputedException(notReadyBlockId, false);
    }
  }

  private static final int DIAGONAL_CELL_UNAVAILABLE = -1;

  /**
   * Resolves a thick-trunk core that lies diagonally across a section corner by reading the
   * matching cell of the diagonally adjacent section. The six face slices each hold one axis, so
   * they cannot cover a position outside both x and z; this direct lookup avoids adding another
   * slice. Returns {@link #DIAGONAL_CELL_UNAVAILABLE} when the adjacent section has no resident
   * data (absent from the tracker and from storage); the caller then keeps the shell cell empty.
   */
  private int readDiagonalCoreId(WorldSection section, int x, int y, int z, int cdx, int cdz) {
    WorldSection diagonal =
        this.world.acquireIfExists(section.lvl, section.x + cdx, section.y, section.z + cdz);
    if (diagonal == null) {
      return DIAGONAL_CELL_UNAVAILABLE;
    }
    try {
      int localX = (x + cdx) & 31;
      int localZ = (z + cdz) & 31;
      return Mapper.getBlockId(
          diagonal._unsafeGetRawDataArray()[localX + 32 * localZ + 1024 * y]);
    } finally {
      diagonal.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }
  }

  /**
   * Reads the block id at world coords from a Voxy mip level; -1 when the section is absent.
   *
   * <p>Render-distance seam fallback: beyond the vanilla render distance the LOD0 section is air,
   * but the Voxel Shape Storage still holds the coarser LOD1 data there, so the thick-trunk core
   * can be recovered at its exact position instead of losing the trunk wall.
   */
  private int readBlockIdAtLevel(int lvl, int worldX, int worldY, int worldZ) {
    int bx = worldX >> lvl;
    int by = worldY >> lvl;
    int bz = worldZ >> lvl;
    int sx = bx >> 5;
    int sy = by >> 5;
    int sz = bz >> 5;
    int lx = bx & 31;
    int ly = by & 31;
    int lz = bz & 31;
    WorldSection section = this.world.acquireIfExists(lvl, sx, sy, sz);
    if (section == null) {
      return -1;
    }
    try {
      return Mapper.getBlockId(section._unsafeGetRawDataArray()[lx + 32 * lz + 1024 * ly]);
    } finally {
      section.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }
  }

  // Horizontal neighbours of a surface root, in the same order as the LOD0 core search in
  // resolveSurfaceRoots (cardinals, then diagonals). The LOD1 fallback reads at cell + offset and
  // stores the negated offset, because coreDx/coreDz is the cell's offset from the core.
  private static final int[][] ROOT_CORE_OFFSETS = {
    {0, -1}, {0, 1}, {-1, 0}, {1, 0}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}
  };

  /**
   * DT's {@code branchesAround} flag for the thick-trunk cap rules: true when any horizontal
   * neighbour of the core is a tree branch. Unknown context counts as true, which draws the cap
   * (the safer direction: an occluded extra face instead of a hole).
   */
  private boolean coreHasHorizontalBranch(
      long[] raw, int x, int y, int z, int coreDx, int coreDz, int neighborMsk) {
    int cx = x + coreDx;
    int cz = z + coreDz;
    if (cx < 0 || cx > 31 || cz < 0 || cz > 31) {
      // Core outside this section: its neighbours are beyond the single face slice we hold.
      return true;
    }
    int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    for (int[] offset : offsets) {
      int id =
          this.readRootNeighbourId(
              raw, x, y, z, coreDx + offset[0], 0, coreDz + offset[1], neighborMsk);
      if (id < 0) {
        return true;
      }
      if (DynamicTreesBranches.isBranch(this.stateForIdSafe(id))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Replaces surface-root models with their 3x3x3-context variants. DT roots read the block above
   * and each horizontal side at y-1/y/y+1; the plain per-state bake only knows a lone core box,
   * which is why roots looked like loose cubes.
   *
   * <p>Roots on a section edge read the outside states from the already-acquired neighbour face
   * slices (a ±x/±z slice holds the whole y/z or x/y face, so all 13 states are available).
   * Positions outside two axes (section corners, y borders) are treated as air, which matches DT's
   * "no connection" result; the exact root model is still baked from the captured cube.
   */
  private void resolveSurfaceRoots(long[] rawSectionData, int neighborMsk, WorldSection section) {
    boolean anyNotReady = false;
    int notReadyBlockId = 0;
    for (int i = 0; i < this.rootBlockCount; i++) {
      int idx = this.rootBlockIndices[i];
      int x = idx & 31;
      int z = (idx >> 5) & 31;
      int y = idx >> 10;
      int blockId = Mapper.getBlockId(rawSectionData[idx]);
      int up = this.readRootNeighbourId(rawSectionData, x, y, z, 0, 1, 0, neighborMsk);
      int northDown = this.readRootNeighbourId(rawSectionData, x, y, z, 0, -1, -1, neighborMsk);
      int north = this.readRootNeighbourId(rawSectionData, x, y, z, 0, 0, -1, neighborMsk);
      int northUp = this.readRootNeighbourId(rawSectionData, x, y, z, 0, 1, -1, neighborMsk);
      int eastDown = this.readRootNeighbourId(rawSectionData, x, y, z, 1, -1, 0, neighborMsk);
      int east = this.readRootNeighbourId(rawSectionData, x, y, z, 1, 0, 0, neighborMsk);
      int eastUp = this.readRootNeighbourId(rawSectionData, x, y, z, 1, 1, 0, neighborMsk);
      int southDown = this.readRootNeighbourId(rawSectionData, x, y, z, 0, -1, 1, neighborMsk);
      int south = this.readRootNeighbourId(rawSectionData, x, y, z, 0, 0, 1, neighborMsk);
      int southUp = this.readRootNeighbourId(rawSectionData, x, y, z, 0, 1, 1, neighborMsk);
      int westDown = this.readRootNeighbourId(rawSectionData, x, y, z, -1, -1, 0, neighborMsk);
      int west = this.readRootNeighbourId(rawSectionData, x, y, z, -1, 0, 0, neighborMsk);
      int westUp = this.readRootNeighbourId(rawSectionData, x, y, z, -1, 1, 0, neighborMsk);
      // Unavailable context cells (section corners, y borders) are treated as air: DT's
      // RootConnections sees no connection there, and the exact root model still bakes.

      // A root cell can replace a trunk_shell of a thick trunk. Find the adjacent core so the bake
      // can composite the trunk's per-cell prism bark back into this cell.
      int coreBlockId = 0;
      int coreDx = 0;
      int coreDz = 0;
      // Seam gate: remember whether any already-read LOD0 neighbour is a trunk_shell, so the LOD1
      // root probes below run only in a thick-trunk context (thin trees have no shell cells).
      // No extra reads: the cardinal ids are read above and the diagonals in their branch below.
      boolean adjacentTrunkShell =
          this.modelMan.isTrunkShellBlockId(north)
              || this.modelMan.isTrunkShellBlockId(south)
              || this.modelMan.isTrunkShellBlockId(west)
              || this.modelMan.isTrunkShellBlockId(east);
      if (this.modelMan.isThickBranchBlockId(north)) {
        coreBlockId = north;
        coreDz = 1;
      } else if (this.modelMan.isThickBranchBlockId(south)) {
        coreBlockId = south;
        coreDz = -1;
      } else if (this.modelMan.isThickBranchBlockId(west)) {
        coreBlockId = west;
        coreDx = 1;
      } else if (this.modelMan.isThickBranchBlockId(east)) {
        coreBlockId = east;
        coreDx = -1;
      } else {
        int northWest = this.readRootNeighbourId(rawSectionData, x, y, z, -1, 0, -1, neighborMsk);
        int northEast = this.readRootNeighbourId(rawSectionData, x, y, z, 1, 0, -1, neighborMsk);
        int southWest = this.readRootNeighbourId(rawSectionData, x, y, z, -1, 0, 1, neighborMsk);
        int southEast = this.readRootNeighbourId(rawSectionData, x, y, z, 1, 0, 1, neighborMsk);
        adjacentTrunkShell =
            adjacentTrunkShell
                || this.modelMan.isTrunkShellBlockId(northWest)
                || this.modelMan.isTrunkShellBlockId(northEast)
                || this.modelMan.isTrunkShellBlockId(southWest)
                || this.modelMan.isTrunkShellBlockId(southEast);
        if (this.modelMan.isThickBranchBlockId(northWest)) {
          coreBlockId = northWest;
          coreDx = 1;
          coreDz = 1;
        } else if (this.modelMan.isThickBranchBlockId(northEast)) {
          coreBlockId = northEast;
          coreDx = -1;
          coreDz = 1;
        } else if (this.modelMan.isThickBranchBlockId(southWest)) {
          coreBlockId = southWest;
          coreDx = 1;
          coreDz = -1;
        } else if (this.modelMan.isThickBranchBlockId(southEast)) {
          coreBlockId = southEast;
          coreDx = -1;
          coreDz = -1;
        }
      }

      boolean upAir = DynamicTreesBranches.isAirOrWater(this.stateForIdSafe(up));
      int selfRadius = DynamicTreesBranches.radiusOf(this.modelMan.getBlockStateForId(blockId));
      if (selfRadius <= 0) {
        selfRadius = 1;
      } else if (selfRadius > 8) {
        selfRadius = 8;
      }
      int northC = this.rootSideConnection(north, northUp, northDown, upAir, selfRadius);
      int eastC = this.rootSideConnection(east, eastUp, eastDown, upAir, selfRadius);
      int southC = this.rootSideConnection(south, southUp, southDown, upAir, selfRadius);
      int westC = this.rootSideConnection(west, westUp, westDown, upAir, selfRadius);
      // Radii are packed in 4-bit fields: DT's connection radius reaches 8, and the old 3-bit
      // packing collapsed radius 8 into 7, merging distinct DT contexts under one cache key.
      int sideRadii =
          (northC & 15) | ((eastC & 15) << 4) | ((southC & 15) << 8) | ((westC & 15) << 12);
      int sideLevels =
          ((northC >> 4) & 3)
              | (((eastC >> 4) & 3) << 2)
              | (((southC >> 4) & 3) << 4)
              | (((westC >> 4) & 3) << 6);
      // The 3x3x3 cube is consumed only by the overlay and root-variant paths. With the master
      // switch off neither runs, so the capture is skipped entirely.
      BlockState[] cube =
          DTRootGeometryCache.ROOTS_ENABLED
              ? this.buildRootCube(
                  blockId, up, northDown, north, northUp, eastDown, east, eastUp, southDown, south,
                  southUp, westDown, west, westUp)
              : null;
      if (!DTRootGeometryCache.ROOTS_ENABLED || DTRootGeometryCache.isActive()) {
        // Master switch off, or overlay active: suppress the root impostor from the LOD0 mesh so
        // Voxy draws no root geometry here. Root cells that replaced a trunk_shell still need the
        // thick-trunk prism wall, which the overlay does not draw; keep the pre-baked shell-only
        // impostor for those cells so the wall remains. Without an adjacent thick-trunk core the
        // cell stays air/base (no root geometry).
        int suppressionModelId = 0;
        if (coreBlockId != 0) {
          int shellId = this.modelMan.getShellModelId(coreBlockId, coreDx, coreDz, 0);
          if (shellId == ModelFactory.VARIANT_MODEL_NOT_READY) {
            // Do not air the cell while the shell bake is in flight: retry the whole section so
            // the wall does not stay missing until an unrelated re-mesh.
            anyNotReady = true;
            if (notReadyBlockId == 0) {
              notReadyBlockId = coreBlockId;
            }
            continue;
          }
          if (shellId > 0) {
            suppressionModelId = shellId;
          }
        }
        if (DTRootGeometryCache.isActive()) {
          // Overlay on: draw the exact DT model with the vanilla renderer. With the master switch
          // off isActive() is false, so nothing is collected and the root stays suppressed (air
          // or shell-only) in the impostor mesh.
          BlockState rootState = this.modelMan.getBlockStateForId(blockId);
          this.overlayRoots.add(
              new DTRootGeometryCache.RootBlock(
                  (section.x << 5) + x,
                  (section.y << 5) + y,
                  (section.z << 5) + z,
                  rootState,
                  cube,
                  Mapper.getLightId(rawSectionData[idx])));
        }
        long suppressionMetadata =
            this.modelMan.getModelMetadataFromClientId(Math.max(suppressionModelId, 0));
        this.sectionData[idx * 2] =
            packPartialQuadData(suppressionModelId, rawSectionData[idx], suppressionMetadata);
        this.sectionData[idx * 2 + 1] = suppressionMetadata;
        // The section masks were computed in prepareSectionData from the BASE root model; the
        // suppressed cell must not keep that model's opaque/non-opaque bit, or neighbours cull
        // their faces against the cell and holes appear around roots such as the radius-8 pad.
        int maskIndex = idx >> 5;
        int bit = 1 << (idx & 31);
        this.opaqueMasks[maskIndex] &= ~bit;
        this.nonOpaqueMasks[maskIndex] &= ~bit;
        if (suppressionModelId > 0) {
          if (ModelQueries._isFullyOpaque(suppressionMetadata) != 0) {
            this.opaqueMasks[maskIndex] |= bit;
          } else {
            this.nonOpaqueMasks[maskIndex] |= bit;
          }
        }
        // fluidMasks stay untouched: waterlogged water is still meshed by the fluid pass.
        this.rootPatchedCount++;
        continue;
      }
      // Seam fallback (non-overlay path only): when the trunk core's chunk lies beyond the vanilla
      // render distance, LOD0 reports the core as air and the root loses the trunk-facing wall.
      // Read the core at the exact position from the LOD1 mip section instead. Same y only: the
      // y-1 root flare under a trunk has no core beside it and must stay wall-less. Gated on a
      // LOD0 trunk_shell neighbour: thin trees have no shell cells, so their root cells skip the
      // (potentially storage-loading) LOD1 probes entirely.
      if (coreBlockId == 0 && adjacentTrunkShell) {
        int wx = (section.x << 5) + x;
        int wy = (section.y << 5) + y;
        int wz = (section.z << 5) + z;
        for (int[] offset : ROOT_CORE_OFFSETS) {
          int candidateId =
              this.readBlockIdAtLevel(section.lvl + 1, wx + offset[0], wy, wz + offset[1]);
          if (candidateId > 0 && this.modelMan.isThickBranchBlockId(candidateId)) {
            coreBlockId = candidateId;
            // Stored convention: the cell's offset from the core (core -> cell), as in the LOD0
            // search above and as getShellModelId/buildRootExactModel expect.
            coreDx = -offset[0];
            coreDz = -offset[1];
            this.rootLod1FallbackCount++;
            break;
          }
        }
      }
      int rootModelId =
          this.modelMan.getRootVariantModelId(
              blockId, sideRadii, sideLevels, coreBlockId, coreDx, coreDz, cube);
      if (rootModelId == ModelFactory.VARIANT_MODEL_NOT_READY) {
        anyNotReady = true;
        if (notReadyBlockId == 0) {
          notReadyBlockId = blockId;
        }
        continue;
      }
      if (rootModelId <= 0) {
        if (rootModelId == ModelFactory.VARIANT_MODEL_UNAVAILABLE) {
          this.rootUnavailableCount++;
        }
        continue;
      }
      long metadata = this.modelMan.getModelMetadataFromClientId(rootModelId);
      // The section's opaque/fluid masks were built from the base model metadata, so a root
      // variant that flips those bits must fall back to the base model (same invariant as the
      // branch path). Per-face layout bits may legitimately differ.
      int baseModelId = this.modelMan._unsafeRawAccess()[blockId];
      long baseMetadata = this.modelMan.getModelMetadataFromClientId(baseModelId);
      if (ModelQueries._isFullyOpaque(baseMetadata) != ModelQueries._isFullyOpaque(metadata)
          || ModelQueries._isFluid(baseMetadata) != ModelQueries._isFluid(metadata)
          || ModelQueries._containsFluid(baseMetadata) != ModelQueries._containsFluid(metadata)) {
        this.rootMaskRejectedCount++;
        if (ROOT_MASK_REJECT_LOG.compareAndSet(false, true)) {
          Logger.warn(
              "Dynamic Trees root variant skipped: opaque/fluid mask mismatch for block "
                  + blockId
                  + " (base="
                  + baseMetadata
                  + ", variant="
                  + metadata
                  + ")");
        }
        continue;
      }
      long block = rawSectionData[idx];
      this.sectionData[idx * 2] = packPartialQuadData(rootModelId, block, metadata);
      this.sectionData[idx * 2 + 1] = metadata;
      // Stage 2b-1: remember the extra depth-band planes so the mesher can emit them. Only
      // successfully patched variants reach this point; overlay suppression returns above.
      if (this.modelMan.isRootMultiplaneEnabled()) {
        int[] planes = this.modelMan.getRootPlaneModelIds(rootModelId);
        if (planes.length > 1) {
          this.rootCellPlanes[idx] = planes;
        }
        // Mirrors ModelFactory.buildRootExactModel's hiddenRootSides: the extra plane ids are not
        // filtered by the composite root model, so record which sides face an adjacent thick trunk
        // core and skip their plane quads later.
        int hiddenMask = 0;
        if (coreBlockId != 0) {
          if (coreDx > 0) {
            hiddenMask |= 1 << 5; // EAST
          } else if (coreDx < 0) {
            hiddenMask |= 1 << 4; // WEST
          }
          if (coreDz > 0) {
            hiddenMask |= 1 << 3; // SOUTH
          } else if (coreDz < 0) {
            hiddenMask |= 1 << 2; // NORTH
          }
        }
        this.rootCellHiddenSides[idx] = hiddenMask;
      }
      this.rootPatchedCount++;
    }
    if (anyNotReady) {
      throw new IdNotYetComputedException(notReadyBlockId, false);
    }
  }

  private static final int ROOT_NEIGHBOUR_UNAVAILABLE = -1;

  /**
   * Returns the connection index for one root side: bits 0-3 = radius (0..8), bits 4-5 = DT level
   * (1 MID, 2 LOW, 3 HIGH), 0 = no connection. Mirrors DT's {@code getSideConnectionRadius}.
   *
   * <p>DT checks any {@code BranchBlock} (roots included) with radius {@code >= 8} first and
   * returns a MID connection of radius 8; only afterwards it evaluates HIGH/LOW. Mirroring that
   * order is what keeps the cache key aligned with the geometry DT bakes from the captured cube.
   */
  private int rootSideConnection(int same, int up, int down, boolean upAir, int selfRadius) {
    BlockState sameState = this.stateForIdSafe(same);
    // DT connects any BranchBlock (roots included) with radius >= 8 as a MID radius-8 connection
    // before it looks at HIGH/LOW, so this check must stay first.
    if (DynamicTreesBranches.isBranchLike(sameState)
        && DynamicTreesBranches.radiusOf(sameState) >= 8) {
      return Math.min(8, selfRadius) | (1 << 4);
    }
    // DT picks the level from the same-level neighbour: HIGH when the cell above this root is
    // air/water and the neighbour is a full collision block, LOW when the neighbour is air/water,
    // MID otherwise.
    boolean goUp = upAir && DynamicTreesBranches.isFullCollisionBlock(sameState);
    boolean goDown = DynamicTreesBranches.isAirOrWater(sameState);
    int level = goUp ? 3 : goDown ? 2 : 1;
    // HIGH/LOW read the cell one above/below the same-level neighbour; MID reads the neighbour
    // itself. DT connects only when the cell at that level is a SurfaceRootBlock.
    int shifted = level == 3 ? up : level == 2 ? down : same;
    if (!this.modelMan.isSurfaceRootBlockId(shifted)) {
      return 0;
    }
    int radius = DynamicTreesBranches.radiusOf(this.stateForIdSafe(shifted));
    if (radius <= 0) {
      return 0;
    }
    // DT's baked root model clamps every connection radius to the root's own radius (1..8), so a
    // smaller key space is exact rather than lossy.
    radius = Math.min(radius, selfRadius);
    return radius | (level << 4);
  }

  private BlockState stateForIdSafe(int blockId) {
    if (blockId <= 0) {
      return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }
    BlockState state = this.modelMan.getBlockStateForId(blockId);
    return state == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : state;
  }

  private static int rootCubeIndex(int x, int y, int z) {
    return (x + 1) + 3 * (y + 1) + 9 * (z + 1);
  }

  /** Builds the 27-state context cube the exact DT root model reads its ModelData from. */
  private BlockState[] buildRootCube(
      int blockId,
      int up,
      int northDown,
      int north,
      int northUp,
      int eastDown,
      int east,
      int eastUp,
      int southDown,
      int south,
      int southUp,
      int westDown,
      int west,
      int westUp) {
    BlockState[] cube = new BlockState[27];
    Arrays.fill(cube, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    cube[13] = this.modelMan.getBlockStateForId(blockId);
    cube[rootCubeIndex(0, 1, 0)] = this.stateForIdSafe(up);
    cube[rootCubeIndex(0, -1, -1)] = this.stateForIdSafe(northDown);
    cube[rootCubeIndex(0, 0, -1)] = this.stateForIdSafe(north);
    cube[rootCubeIndex(0, 1, -1)] = this.stateForIdSafe(northUp);
    cube[rootCubeIndex(1, -1, 0)] = this.stateForIdSafe(eastDown);
    cube[rootCubeIndex(1, 0, 0)] = this.stateForIdSafe(east);
    cube[rootCubeIndex(1, 1, 0)] = this.stateForIdSafe(eastUp);
    cube[rootCubeIndex(0, -1, 1)] = this.stateForIdSafe(southDown);
    cube[rootCubeIndex(0, 0, 1)] = this.stateForIdSafe(south);
    cube[rootCubeIndex(0, 1, 1)] = this.stateForIdSafe(southUp);
    cube[rootCubeIndex(-1, -1, 0)] = this.stateForIdSafe(westDown);
    cube[rootCubeIndex(-1, 0, 0)] = this.stateForIdSafe(west);
    cube[rootCubeIndex(-1, 1, 0)] = this.stateForIdSafe(westUp);
    return cube;
  }

  /**
   * Reads one cell of a root's 3x3x3 context, using the neighbour face slices when the position is
   * one axis outside the section. A ±x/±z slice contains the complete perpendicular face, so edge
   * roots resolve fully; positions outside two axes (section corners, y borders) are unavailable.
   */
  private int readRootNeighbourId(
      long[] raw, int x, int y, int z, int dx, int dy, int dz, int neighborMsk) {
    final int area = 32 * 32;
    int px = x + dx;
    int py = y + dy;
    int pz = z + dz;
    int outside = 0;
    if (px < 0 || px > 31) outside++;
    if (py < 0 || py > 31) outside++;
    if (pz < 0 || pz > 31) outside++;
    if (outside == 0) {
      return Mapper.getBlockId(raw[px + 32 * pz + 1024 * py]);
    }
    if (outside > 1) {
      return ROOT_NEIGHBOUR_UNAVAILABLE;
    }
    if (py < 0) {
      if ((neighborMsk & 4) == 0) return ROOT_NEIGHBOUR_UNAVAILABLE;
      return Mapper.getBlockId(this.neighboringFaces[2 * area + px + (pz << 5)]);
    }
    if (py > 31) {
      if ((neighborMsk & 8) == 0) return ROOT_NEIGHBOUR_UNAVAILABLE;
      return Mapper.getBlockId(this.neighboringFaces[3 * area + px + (pz << 5)]);
    }
    if (px < 0) {
      if ((neighborMsk & 1) == 0) return ROOT_NEIGHBOUR_UNAVAILABLE;
      return Mapper.getBlockId(this.neighboringFaces[py * 32 + pz]);
    }
    if (px > 31) {
      if ((neighborMsk & 2) == 0) return ROOT_NEIGHBOUR_UNAVAILABLE;
      return Mapper.getBlockId(this.neighboringFaces[area + py * 32 + pz]);
    }
    if (pz < 0) {
      if ((neighborMsk & 16) == 0) return ROOT_NEIGHBOUR_UNAVAILABLE;
      return Mapper.getBlockId(this.neighboringFaces[4 * area + px + (py << 5)]);
    }
    if ((neighborMsk & 32) == 0) return ROOT_NEIGHBOUR_UNAVAILABLE;
    return Mapper.getBlockId(this.neighboringFaces[5 * area + px + (py << 5)]);
  }

  private static final java.util.concurrent.atomic.AtomicBoolean VARIANT_MASK_PATCH_LOG =
      new java.util.concurrent.atomic.AtomicBoolean();

  private static final java.util.concurrent.atomic.AtomicBoolean ROOT_MASK_REJECT_LOG =
      new java.util.concurrent.atomic.AtomicBoolean();

  private static final java.util.concurrent.atomic.AtomicBoolean ROOT_PLANE_CAPACITY_LOG =
      new java.util.concurrent.atomic.AtomicBoolean();

  private static final java.util.concurrent.atomic.AtomicBoolean ROOT_PLANE_DIAG_LOG =
      new java.util.concurrent.atomic.AtomicBoolean();

  private static final long LM = (0xFFL << 55);

  private static boolean shouldMeshNonOpaqueBlockFace(
      int face, long quad, long meta, long neighborQuad, long neighborMeta) {
    if (((quad ^ neighborQuad) & (0xFFFFL << 26)) == 0
        && (DISABLE_CULL_SAME_OCCLUDES
            || (ModelQueries.cullsSame(meta) || ModelQueries.faceOccludes(meta, face))))
      return false; // This is a hack, if the neigbor and this are the same, dont mesh the face//
    // TODO: FIXME
    if (!ModelQueries.faceExists(meta, face)) return false; // Dont mesh if no face
    if (ModelQueries.faceCanBeOccluded(meta, face)) // TODO: maybe enable this
    if (ModelQueries.faceOccludes(neighborMeta, face ^ 1)) return false;
    return true;
  }

  private static void meshNonOpaqueFace(
      int face, long quad, long meta, long neighborQuad, long neighborMeta, Mesher mesher) {
    if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
      mesher.putNext(
          (long) (face & 1)
              | (quad & ~LM)
              | ((ModelQueries.faceUsesSelfLighting(meta, face) ? quad : neighborQuad) & LM));
    } else {
      mesher.skip(1);
    }
  }

  private void generateYZOpaqueInnerGeometry(int axis) {
    for (int layer = 0; layer < 31; layer++) {
      this.blockMesher.auxiliaryPosition = layer;
      int cSkip = 0;
      for (int other = 0; other < 32; other++) {
        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
        int skipAmount = axis == 0 ? 32 : 1;

        int current = this.opaqueMasks[pidx];
        int next = this.opaqueMasks[pidx + skipAmount];

        int msk = current ^ next;
        if (msk == 0) {
          cSkip += 32;
          continue;
        }

        this.blockMesher.skip(cSkip);
        cSkip = 0;

        int faceForwardMsk = msk & current;
        int cIdx = -1;
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk); // Is also the x-axis index
          int delta = index - cIdx - 1;
          cIdx = index; // index--;
          if (delta != 0) this.blockMesher.skip(delta);
          msk &= ~Integer.lowestOneBit(msk);

          int facingForward = ((faceForwardMsk >> index) & 1);

          {
            int idx = index + (pidx * 32);
            int shift = skipAmount * 32 * 2;

            // Flip data with respect to facing direction
            int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
            int iB = idx * 2 + (facingForward == 1 ? shift : 0);

            long selfModel = this.sectionData[iA];
            long nextModel = this.sectionData[iB];

            // Check if next culls this face
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
              long neighbor = this.sectionData[iB + 1];
              boolean culls = false;
              culls |=
                  ((selfModel ^ nextModel) & (0xFFFFL << 26)) == 0
                      && ModelQueries.cullsSame(neighbor);
              culls |= ModelQueries.faceOccludes(neighbor, (axis << 1) | (1 - facingForward));
              if (culls) {
                this.blockMesher.skip(1);
                continue;
              }
            }

            this.blockMesher.putNext(
                ((long) facingForward)
                    | // Facing
                    (selfModel & ~LM)
                    | (nextModel & LM) // Apply lighting
                );
          }
        }

        this.blockMesher.endRow();
      }
      this.blockMesher.finish();
    }
  }

  private void generateYZOpaqueOuterGeometry(int axis) {
    this.blockMesher.doAuxiliaryFaceOffset = false;
    // Hacky generate section side faces (without check neighbor section)
    for (int side = 0; side < 2; side++) { // -, +
      int layer = side == 0 ? 0 : 31;
      this.blockMesher.auxiliaryPosition = layer;
      int cSkips = 0;
      for (int other = 0; other < 32; other++) {
        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
        int msk = this.opaqueMasks[pidx];
        if (msk == 0) {
          cSkips += 32;
          continue;
        }

        this.blockMesher.skip(cSkips);
        cSkips = 0;

        int cIdx = -1;
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk); // Is also the x-axis index
          int delta = index - cIdx - 1;
          cIdx = index; // index--;
          if (delta != 0) this.blockMesher.skip(delta);
          msk &= ~Integer.lowestOneBit(msk);

          {
            int idx = index + (pidx * 32);

            int neighborIdx = ((axis + 1) * 32 * 32 * 2) + (side) * 32 * 32;
            long neighborId = this.neighboringFaces[neighborIdx + (other * 32) + index];
            long A = this.sectionData[idx * 2];

            int nib = Mapper.getBlockId(neighborId);
            if (nib != 0) { // Not air
              int cid = this.modelMan.getModelId(nib);
              long meta = this.modelMan.getModelMetadataFromClientId(cid);
              if (ModelQueries.isFullyOpaque(meta)) { // Dont mesh this face
                this.blockMesher.skip(1);
                continue;
              }

              // This very funnily causes issues when not combined with meshing non full opaque
              // geometry
              // TODO:FIXME, when non opaque geometry is added
              if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                boolean culls = false;
                culls |= cid == ((A >> 26) & 0xFFFF) && ModelQueries.cullsSame(meta);
                culls |= ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side));
                if (culls) {
                  this.blockMesher.skip(1);
                  continue;
                }
              }
            }

            this.blockMesher.putNext(
                ((side == 0) ? 0L : 1L) | (A & ~LM) | ((neighborId & (0xFFL << 56)) >>> 1));
          }
        }
        this.blockMesher.endRow();
      }

      this.blockMesher.finish();
    }
    this.blockMesher.doAuxiliaryFaceOffset = true;
  }

  // True when the two fluid voxels (given by their sectionData base indices, already *2) resolve to
  // different fluid client-state models, i.e. different baked surface heights/types. Used to decide
  // whether a culled fluid<->fluid boundary needs a bridge wall. Only call on bits already known to
  // be fluid on both sides.
  private boolean fluidModelsDiffer(int aData, int bData) {
    int aModel = (int) ((this.sectionData[aData] >> 26) & 0xFFFF);
    int bModel = (int) ((this.sectionData[bData] >> 26) & 0xFFFF);
    return this.modelMan.getFluidClientStateId(aModel)
        != this.modelMan.getFluidClientStateId(bModel);
  }

  // Bridge bits for a YZ boundary between slice pidx and pidx+skipAmount: both sides fluid and the
  // two fluid models differ. See FLUID_BRIDGE_WALLS. Opaque fluids (e.g. lava source blocks) must
  // NOT be excluded: a full-height opaque source next to a shorter flowing block still needs a
  // bridge wall because the normal XOR culls the boundary (both sides set) but the heights differ.
  private int computeFluidBridgeMaskYZ(int pidx, int skipAmount) {
    int both = this.fluidMasks[pidx] & this.fluidMasks[pidx + skipAmount];
    int bridge = 0;
    while (both != 0) {
      int index = Integer.numberOfTrailingZeros(both);
      both &= both - 1;
      int idx = index + (pidx * 32);
      if (this.fluidModelsDiffer(idx * 2, (idx + skipAmount * 32) * 2)) {
        bridge |= 1 << index;
      }
    }
    return bridge;
  }

  // Bridge bits for the X pass at row (y,z): bit index set when x=index and x=index+1 are both
  // fluid and their fluid models differ. See FLUID_BRIDGE_WALLS. Opaque fluids are included (same
  // reasoning as computeFluidBridgeMaskYZ).
  private int computeFluidBridgeMaskX(int y, int z) {
    int fMsk = this.fluidMasks[y * 32 + z];
    int both = fMsk & (fMsk >>> 1);
    int bridge = 0;
    while (both != 0) {
      int index = Integer.numberOfTrailingZeros(both);
      both &= both - 1;
      int idx = index + (z * 32) + (y * 32 * 32);
      if (this.fluidModelsDiffer(idx * 2, (idx + 1) * 2)) {
        bridge |= 1 << index;
      }
    }
    return bridge;
  }

  private void generateYZFluidInnerGeometry(int axis) {
    for (int layer = 0; layer < 31; layer++) {
      this.blockMesher.auxiliaryPosition = layer;
      int cSkip = 0;
      for (int other = 0; other < 32; other++) {
        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
        int skipAmount = axis == 0 ? 32 : 1;

        // TODO: this needs to take into account opaqueMasks to not mesh any faces with it set
        int current = this.fluidMasks[pidx];
        int next = this.fluidMasks[pidx + skipAmount];

        int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
        msk &= current | next;
        // axis 0 is the Y (top/bottom) normal; axis 1 is the Z (horizontal side) normal. Only
        // bridge the horizontal pass so a flowing-water step does not leave a see-through side
        // gap. The re-added boundary face emits the current voxel's full-height side wall
        // (double-sided in the Metal translucent raster), filling the [shorterH, tallerH] band.
        int bridgeMsk = 0;
        if (FLUID_BRIDGE_WALLS && axis == 1) {
          bridgeMsk = this.computeFluidBridgeMaskYZ(pidx, skipAmount);
          msk |= bridgeMsk;
        }
        if (msk == 0) {
          cSkip += 32;
          continue;
        }

        this.blockMesher.skip(cSkip);
        cSkip = 0;

        int faceForwardMsk = msk & current;
        int cIdx = -1;
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk); // Is also the x-axis index
          int delta = index - cIdx - 1;
          cIdx = index; // index--;
          if (delta != 0) this.blockMesher.skip(delta);
          msk &= ~Integer.lowestOneBit(msk);

          int facingForward = ((faceForwardMsk >> index) & 1);

          {
            int idx = index + (pidx * 32);

            int a = idx * 2;
            int b = (idx + skipAmount * 32) * 2;

            // Flip data with respect to facing direction
            int ai = facingForward == 1 ? a : b;
            int bi = facingForward == 1 ? b : a;

            // Bridge wall faces must skip the occlusion check: the two fluid models have different
            // surface heights but both bake full-height side walls (bakery leaves the upper
            // neighbour as fluid), so per-face occlusion is true on both sides even though the
            // visual gap exists.
            if (CHECK_NEIGHBOR_FACE_OCCLUSION
                && ((bridgeMsk >> index) & 1) == 0) { // TODO:SELF OCCLUSION
              if (ModelQueries.faceOccludes(
                  this.sectionData[bi + 1], (axis << 1) | (1 - facingForward))) {
                this.blockMesher.skip(1);
                continue;
              }
            }

            long A = this.sectionData[ai];
            long Am = this.sectionData[ai + 1];
            // If it isnt a fluid but contains one,
            if (ModelQueries.containsFluid(Am)) {
              int modelId = (int) ((A >> 26) & 0xFFFF);
              A &= ~(0xFFFFL << 26);
              int fluidId = this.modelMan.getFluidClientStateId(modelId);
              A |= Integer.toUnsignedLong(fluidId) << 26;
              Am = this.modelMan.getModelMetadataFromClientId(fluidId);

              // Update quad typing info
              A &= ~0b110L;
              A |= getQuadTyping(Am);
            }

            long lighter = this.sectionData[bi];
            // if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check
            // this is right
            //    lighter = this.sectionData[bi];
            // }

            this.blockMesher.putNext(
                ((long) facingForward)
                    | // Facing
                    (A & ~LM)
                    | (lighter & LM) // Apply lighting
                );
          }
        }

        this.blockMesher.endRow();
      }
      this.blockMesher.finish();

      // Second pass: backward-facing bridge wall faces. The first pass always emits bridge walls
      // with facingForward=1 (because faceForwardMsk = msk & current, and both sides are fluid).
      // This second pass emits the same bridge walls with facingForward=0 so the face is visible
      // from the opposite viewing direction.
      if (FLUID_BRIDGE_WALLS && axis == 1) {
        this.blockMesher.auxiliaryPosition = layer;
        cSkip = 0;
        for (int other = 0; other < 32; other++) {
          int pidx = other * 32 + layer;
          int skipAmount = 1;
          int backBridgeMsk = this.computeFluidBridgeMaskYZ(pidx, skipAmount);
          if (backBridgeMsk == 0) {
            cSkip += 32;
            continue;
          }
          this.blockMesher.skip(cSkip);
          cSkip = 0;
          int cIdx2 = -1;
          while (backBridgeMsk != 0) {
            int index = Integer.numberOfTrailingZeros(backBridgeMsk);
            int delta = index - cIdx2 - 1;
            cIdx2 = index;
            if (delta != 0) this.blockMesher.skip(delta);
            backBridgeMsk &= ~Integer.lowestOneBit(backBridgeMsk);

            int idx = index + (pidx * 32);
            int a = idx * 2;
            int b = (idx + skipAmount * 32) * 2;

            long A = this.sectionData[b];
            long Am = this.sectionData[b + 1];
            if (ModelQueries.containsFluid(Am)) {
              int modelId = (int) ((A >> 26) & 0xFFFF);
              A &= ~(0xFFFFL << 26);
              int fluidId = this.modelMan.getFluidClientStateId(modelId);
              A |= Integer.toUnsignedLong(fluidId) << 26;
              Am = this.modelMan.getModelMetadataFromClientId(fluidId);
              A &= ~0b110L;
              A |= getQuadTyping(Am);
            }
            long lighter = this.sectionData[a];
            this.blockMesher.putNext((A & ~LM) | (lighter & LM));
          }
          this.blockMesher.endRow();
        }
        this.blockMesher.finish();
      }
    }
  }

  private void generateYZFluidOuterGeometry(int axis) {
    this.blockMesher.doAuxiliaryFaceOffset = false;
    // Hacky generate section side faces (without check neighbor section)
    for (int side = 0; side < 2; side++) { // -, +
      int layer = side == 0 ? 0 : 31;
      this.blockMesher.auxiliaryPosition = layer;
      int cSkips = 0;
      for (int other = 0; other < 32; other++) {
        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
        int msk = this.fluidMasks[pidx];
        if (msk == 0) {
          cSkips += 32;
          continue;
        }

        this.blockMesher.skip(cSkips);
        cSkips = 0;

        int cIdx = -1;
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk); // Is also the x-axis index
          int delta = index - cIdx - 1;
          cIdx = index; // index--;
          if (delta != 0) this.blockMesher.skip(delta);
          msk &= ~Integer.lowestOneBit(msk);

          {
            int idx = index + (pidx * 32);

            int neighborIdx = ((axis + 1) * 32 * 32 * 2) + (side) * 32 * 32;
            long neighborId = this.neighboringFaces[neighborIdx + (other * 32) + index];

            long A = this.sectionData[idx * 2];
            long B = this.sectionData[idx * 2 + 1];

            if (ModelQueries.containsFluid(B)) {
              int modelId = (int) ((A >> 26) & 0xFFFF);
              A &= ~(0xFFFFL << 26);
              int fluidId = this.modelMan.getFluidClientStateId(modelId);
              A |= Integer.toUnsignedLong(fluidId) << 26;
              B = this.modelMan.getModelMetadataFromClientId(fluidId);

              // We need to update the typing info for A
              A &= ~0b110L;
              A |= getQuadTyping(B);
            }

            // Check and test if can cull W.R.T neighbor
            if (Mapper.getBlockId(neighborId) != 0) { // Not air
              int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
              long meta = this.modelMan.getModelMetadataFromClientId(modelId);
              if (ModelQueries.containsFluid(meta)) {
                modelId = this.modelMan.getFluidClientStateId(modelId);
              }
              if (ModelQueries.cullsSame(B)) {
                if (modelId == ((A >> 26) & 0xFFFF)) {
                  this.blockMesher.skip(1);
                  continue;
                }
              }

              if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                  this.blockMesher.skip(1);
                  continue;
                }
              }
            }

            this.blockMesher.putNext(
                (side == 0 ? 0L : 1L) | (A & ~LM) | ((neighborId & (0xFFL << 56)) >>> 1));
          }
        }
        this.blockMesher.endRow();
      }
      this.blockMesher.finish();
    }
    this.blockMesher.doAuxiliaryFaceOffset = true;
  }

  private void generateYZNonOpaqueInnerGeometry(int axis) {
    // Note: think is ok to just reuse.. blockMesher
    this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
    this.blockMesher.axis = axis;
    this.seondaryblockMesher.axis = axis;
    for (int layer = 1; layer < 31; layer++) { // (should be 1->31, then have outer face mesher)
      this.blockMesher.auxiliaryPosition = layer;
      this.seondaryblockMesher.auxiliaryPosition = layer;
      int cSkip = 0;
      for (int other = 0; other < 32; other++) { // TODO: need to do the faces that border sections
        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
        int skipAmount = axis == 0 ? 32 * 32 : 32;

        int msk = this.nonOpaqueMasks[pidx];

        if (msk == 0) {
          cSkip += 32;
          continue;
        }

        this.blockMesher.skip(cSkip);
        this.seondaryblockMesher.skip(cSkip);
        cSkip = 0;

        int cIdx = -1;
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk); // Is also the x-axis index
          int delta = index - cIdx - 1;
          cIdx = index; // index--;
          if (delta != 0) {
            this.blockMesher.skip(delta);
            this.seondaryblockMesher.skip(delta);
          }
          msk &= ~Integer.lowestOneBit(msk);

          {
            int idx = index + (pidx * 32);

            long A = this.sectionData[idx * 2];
            long B = this.sectionData[idx * 2 + 1];

            meshNonOpaqueFace(
                (axis << 1) | 0,
                A,
                B,
                this.sectionData[(idx - skipAmount) * 2],
                this.sectionData[(idx - skipAmount) * 2 + 1],
                this.seondaryblockMesher); // -
            meshNonOpaqueFace(
                (axis << 1) | 1,
                A,
                B,
                this.sectionData[(idx + skipAmount) * 2],
                this.sectionData[(idx + skipAmount) * 2 + 1],
                this.blockMesher); // +
          }
        }
        this.blockMesher.endRow();
        this.seondaryblockMesher.endRow();
      }
      this.blockMesher.finish();
      this.seondaryblockMesher.finish();
    }
  }

  private void generateYZNonOpaqueOuterGeometry(int axis) {
    // Note: think is ok to just reuse.. blockMesher
    this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
    this.blockMesher.axis = axis;
    this.seondaryblockMesher.axis = axis;
    for (int side = 0; side < 2; side++) { // -, +
      int layer = side == 0 ? 0 : 31;
      int skipAmount = (axis == 0 ? 32 * 32 : 32) * (1 - (side * 2));
      this.blockMesher.auxiliaryPosition = layer;
      this.seondaryblockMesher.auxiliaryPosition = layer;
      int cSkips = 0;
      for (int other = 0; other < 32; other++) {
        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
        int msk = this.nonOpaqueMasks[pidx];
        if (msk == 0) {
          cSkips += 32;
          continue;
        }

        this.blockMesher.skip(cSkips);
        this.seondaryblockMesher.skip(cSkips);
        cSkips = 0;

        int cIdx = -1;
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk); // Is also the x-axis index
          int delta = index - cIdx - 1;
          cIdx = index; // index--;
          if (delta != 0) {
            this.blockMesher.skip(delta);
            this.seondaryblockMesher.skip(delta);
          }
          msk &= ~Integer.lowestOneBit(msk);

          {
            int idx = index + (pidx * 32);

            int neighborIdx = ((axis + 1) * 32 * 32 * 2) + (side) * 32 * 32;
            long neighborId = this.neighboringFaces[neighborIdx + (other * 32) + index];

            long A = this.sectionData[idx * 2];
            long B = this.sectionData[idx * 2 + 1];

            boolean fail = false;
            // Check and test if can cull W.R.T neighbor
            if (Mapper.getBlockId(neighborId) != 0) { // Not air
              int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));

              if (ModelQueries.cullsSame(B)
                  && modelId
                      == ((A >> 26)
                          & 0xFFFF)) { // TODO: FIXME, this technically isnt correct as need to
                // check self occulsion, thinks?
                // TODO: check self occlsuion in the if statment
                fail = true;
              } else {
                long meta = this.modelMan.getModelMetadataFromClientId(modelId);

                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                  fail = true;
                }
              }
            }

            long nA = this.sectionData[(idx + skipAmount) * 2];
            long nB = this.sectionData[(idx + skipAmount) * 2 + 1];
            boolean failB = false;
            // TODO: check self occlusion

            if (ModelQueries.cullsSame(nB)
                && (nA & (0xFFFFL << 26))
                    == (A
                        & (0xFFFFL
                            << 26))) { // TODO: FIXME, this technically isnt correct as need to
              // check self occulsion, thinks?
              // TODO: check self occlsuion in the if statment
              failB = true;
            } else {
              if (ModelQueries.faceOccludes(nB, (axis << 1) | (side))) {
                failB = true;
              }
            }

            // TODO: LIGHTING
            if (ModelQueries.faceExists(B, (axis << 1) | 1)
                && ((side == 1 && !fail) || (side == 0 && !failB))) {
              this.blockMesher.putNext(
                  (long) (false ? 0L : 1L)
                      | A
                      | 0 // ((ModelQueries.faceUsesSelfLighting(B, (axis<<1)|1)?A:) & (0xFFL <<
                  // 55))
                  );
            } else {
              this.blockMesher.skip(1);
            }

            if (ModelQueries.faceExists(B, (axis << 1) | 0)
                && ((side == 0 && !fail) || (side == 1 && !failB))) {
              this.seondaryblockMesher.putNext(
                  (long) (true ? 0L : 1L) | A | 0 // (((0xFFL) & 0xFF) << 55)
                  );
            } else {
              this.seondaryblockMesher.skip(1);
            }
          }
        }
        this.blockMesher.endRow();
        this.seondaryblockMesher.endRow();
      }
      this.blockMesher.finish();
      this.seondaryblockMesher.finish();
    }
  }

  private void generateYZFaces() {
    for (int axis = 0; axis < 2; axis++) { // Y then Z
      this.blockMesher.axis = axis;

      this.generateYZOpaqueInnerGeometry(axis);
      this.generateYZOpaqueOuterGeometry(axis);

      this.generateYZFluidInnerGeometry(axis);
      this.generateYZFluidOuterGeometry(axis);
      if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
        this.generateYZNonOpaqueInnerGeometry(axis);
        this.generateYZNonOpaqueOuterGeometry(axis);
      }
    }
  }

  private final Mesher[] xAxisMeshers = new Mesher[32];
  private final Mesher[] secondaryXAxisMeshers = new Mesher[32];

  {
    for (int i = 0; i < 32; i++) {
      var mesher = new Mesher();
      mesher.auxiliaryPosition = i;
      mesher.axis = 2; // X axis
      this.xAxisMeshers[i] = mesher;
    }
    if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
      for (int i = 0; i < 32; i++) {
        var mesher = new Mesher();
        mesher.auxiliaryPosition = i;
        mesher.axis = 2; // X axis
        mesher.doAuxiliaryFaceOffset = false;
        this.secondaryXAxisMeshers[i] = mesher;
      }
    }
  }

  private static final long X_I_MSK = 0x4210842108421L;

  private void generateXOpaqueInnerGeometry() {
    for (int y = 0; y < 32; y++) {
      long sumA = 0;
      long sumB = 0;
      long sumC = 0;
      int partialHasCount = -1;
      int msk = 0;
      for (int z = 0; z < 32; z++) {
        int lMsk = this.opaqueMasks[y * 32 + z];
        msk = (lMsk ^ (lMsk >>> 1));
        // TODO: fixme? doesnt this generate extra geometry??
        msk &= -1 >>> 1; // Remove top bit as we dont actually know/have the data for that slice

        // Always increment cause can do funny trick (i.e. -1 on skip amount)
        sumA += X_I_MSK;
        sumB += X_I_MSK;
        sumC += X_I_MSK;

        partialHasCount &= ~msk;

        if (z == 30 && partialHasCount != 0) { // Hackfix for incremental count overflow issue
          int cmsk = partialHasCount;
          while (cmsk != 0) {
            int index = Integer.numberOfTrailingZeros(cmsk);
            cmsk &= ~Integer.lowestOneBit(cmsk);
            // TODO: fixme! check this is correct or if should be 30
            this.xAxisMeshers[index].skip(31);
          }
          // Clear the sum
          sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK) * 0x1F);
          sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, X_I_MSK) * 0x1F);
          sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, X_I_MSK) * 0x1F);
        }

        if (msk == 0) {
          continue;
        }

        /*
        {//Dont need this as can just increment everything then -1 in mask
            //Compute and increment skips for indexes
            long imsk = Integer.toUnsignedLong(~msk);// we only want to increment where there isnt a face
            sumA += Long.expand(imsk, X_I_MSK);
            sumB += Long.expand(imsk>>11, X_I_MSK);
            sumC += Long.expand(imsk>>22, X_I_MSK);
        }*/

        int faceForwardMsk = msk & lMsk;
        int iter = msk;
        while (iter != 0) {
          int index = Integer.numberOfTrailingZeros(iter);
          iter &= ~Integer.lowestOneBit(iter);

          var mesher = this.xAxisMeshers[index];

          int skipCount; // Compute the skip count
          { // TODO: Branch-less
            // Compute skip and clear
            if (index < 11) {
              skipCount = (int) (sumA >> (index * 5));
              sumA &= ~(0x1FL << (index * 5));
            } else if (index < 22) {
              skipCount = (int) (sumB >> ((index - 11) * 5));
              sumB &= ~(0x1FL << ((index - 11) * 5));
            } else {
              skipCount = (int) (sumC >> ((index - 22) * 5));
              sumC &= ~(0x1FL << ((index - 22) * 5));
            }
            skipCount &= 0x1F;
            skipCount--;
          }

          if (skipCount != 0) {
            mesher.skip(skipCount);
          }

          int facingForward = ((faceForwardMsk >> index) & 1);
          {
            int idx = index + (z * 32) + (y * 32 * 32);
            // TODO: swap this out for something not getting the next entry

            // Flip data with respect to facing direction
            int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
            int iB = idx * 2 + (facingForward == 1 ? 2 : 0);

            // Check if next culls this face
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
              // TODO: check self occlsuion
              if (ModelQueries.faceOccludes(
                  this.sectionData[iB + 1], (2 << 1) | (1 - facingForward))) {
                mesher.skip(1);
                continue;
              }
            }

            long selfModel = this.sectionData[iA];
            long nextModel = this.sectionData[iB];

            // Example thing thats just wrong but as example
            mesher.putNext(
                ((long) facingForward)
                    | // Facing
                    (selfModel & ~LM)
                    | (nextModel & LM));
          }
        }
      }

      // Need to skip the remaining entries in the skip array
      {
        msk = ~msk; // Invert the mask as we only need to set stuff that isnt 0
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk);
          msk &= ~Integer.lowestOneBit(msk);
          int skipCount;
          if (index < 11) {
            skipCount = (int) (sumA >> (index * 5));
          } else if (index < 22) {
            skipCount = (int) (sumB >> ((index - 11) * 5));
          } else {
            skipCount = (int) (sumC >> ((index - 22) * 5));
          }
          skipCount &= 0x1F;

          if (skipCount != 0) {
            this.xAxisMeshers[index].skip(skipCount);
          }
        }
      }
    }
  }

  private void generateXOuterOpaqueGeometry() {
    // Generate the side faces, hackily, using 0 and 31 mesher

    var ma = this.xAxisMeshers[0];
    var mb = this.xAxisMeshers[31];
    ma.finish();
    mb.finish();
    ma.doAuxiliaryFaceOffset = false;
    mb.doAuxiliaryFaceOffset = false;

    for (int y = 0; y < 32; y++) {
      int skipA = 0;
      int skipB = 0;
      for (int z = 0; z < 32; z++) {
        int i = y * 32 + z;
        int msk = this.opaqueMasks[i];
        if ((msk & 1) != 0) { // -x
          long neighborId = this.neighboringFaces[i];
          boolean oki = true;
          if (Mapper.getBlockId(neighborId) != 0) { // Not air
            long meta =
                this.modelMan.getModelMetadataFromClientId(
                    this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
            if (ModelQueries.isFullyOpaque(meta)) {
              oki = false;
            } else if (CHECK_NEIGHBOR_FACE_OCCLUSION
                && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 1))) {
              // TODO check self occlsion
              oki = false;
            }
          }
          if (oki) {
            ma.skip(skipA);
            skipA = 0;
            long A = this.sectionData[(i << 5) * 2];
            ma.putNext(0L | (A & ~LM) | ((neighborId & (0xFFL << 56)) >>> 1));
          } else {
            skipA++;
          }
        } else {
          skipA++;
        }

        if ((msk & (1 << 31)) != 0) { // +x
          long neighborId = this.neighboringFaces[i + 32 * 32];
          boolean oki = true;
          if (Mapper.getBlockId(neighborId) != 0) { // Not air
            long meta =
                this.modelMan.getModelMetadataFromClientId(
                    this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
            if (ModelQueries.isFullyOpaque(meta)) {
              oki = false;
            } else if (CHECK_NEIGHBOR_FACE_OCCLUSION
                && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 0))) {
              // TODO check self occlsion
              oki = false;
            }
          }
          if (oki) {
            mb.skip(skipB);
            skipB = 0;
            long A = this.sectionData[(i * 32 + 31) * 2];
            mb.putNext(1L | (A & ~LM) | ((neighborId & (0xFFL << 56)) >>> 1));
          } else {
            skipB++;
          }
        } else {
          skipB++;
        }
      }
      ma.skip(skipA);
      mb.skip(skipB);
    }

    ma.finish();
    mb.finish();
    ma.doAuxiliaryFaceOffset = true;
    mb.doAuxiliaryFaceOffset = true;
  }

  private void generateXInnerFluidGeometry() {
    for (int y = 0; y < 32; y++) {
      long sumA = 0;
      long sumB = 0;
      long sumC = 0;
      int partialHasCount = -1;
      int msk = 0;
      for (int z = 0; z < 32; z++) {
        int oMsk = this.opaqueMasks[y * 32 + z];
        int fMsk = this.fluidMasks[y * 32 + z];
        int lMsk = oMsk | fMsk;
        msk = (lMsk ^ (lMsk >>> 1));
        // TODO: fixme? doesnt this generate extra geometry??
        msk &= -1 >>> 1; // Remove top bit as we dont actually know/have the data for that slice

        // Dont generate geometry for opaque faces
        msk &= fMsk | (fMsk >> 1);

        // Re-add culled fluid<->fluid X boundaries where the two fluid models differ (different
        // surface heights) so the step does not leave a see-through side gap. Must be folded into
        // msk before the sum/partialHasCount bookkeeping below so the skip counters stay
        // consistent.
        int bridgeMskX = 0;
        if (FLUID_BRIDGE_WALLS) {
          bridgeMskX = this.computeFluidBridgeMaskX(y, z);
          msk |= bridgeMskX;
        }

        // Always increment cause can do funny trick (i.e. -1 on skip amount)
        sumA += X_I_MSK;
        sumB += X_I_MSK;
        sumC += X_I_MSK;

        partialHasCount &= ~msk;

        if (z == 30 && partialHasCount != 0) { // Hackfix for incremental count overflow issue
          int cmsk = partialHasCount;
          while (cmsk != 0) {
            int index = Integer.numberOfTrailingZeros(cmsk);
            cmsk &= ~Integer.lowestOneBit(cmsk);
            // TODO: fixme! check this is correct or if should be 30
            this.xAxisMeshers[index].skip(31);
          }
          // Clear the sum
          sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK) * 0x1F);
          sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, X_I_MSK) * 0x1F);
          sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, X_I_MSK) * 0x1F);
        }

        if (msk == 0) {
          continue;
        }

        int faceForwardMsk = msk & lMsk;
        int iter = msk;
        while (iter != 0) {
          int index = Integer.numberOfTrailingZeros(iter);
          iter &= ~Integer.lowestOneBit(iter);

          var mesher = this.xAxisMeshers[index];

          int skipCount; // Compute the skip count
          { // TODO: Branch-less
            // Compute skip and clear
            if (index < 11) {
              skipCount = (int) (sumA >> (index * 5));
              sumA &= ~(0x1FL << (index * 5));
            } else if (index < 22) {
              skipCount = (int) (sumB >> ((index - 11) * 5));
              sumB &= ~(0x1FL << ((index - 11) * 5));
            } else {
              skipCount = (int) (sumC >> ((index - 22) * 5));
              sumC &= ~(0x1FL << ((index - 22) * 5));
            }
            skipCount &= 0x1F;
            skipCount--;
          }

          if (skipCount != 0) {
            mesher.skip(skipCount);
          }

          int facingForward = ((faceForwardMsk >> index) & 1);
          {
            int idx = index + (z * 32) + (y * 32 * 32);

            // The facingForward thing is to get next entry automajicly
            int ai = (idx + (1 - facingForward)) * 2;
            int bi = (idx + facingForward) * 2;

            // Bridge wall faces skip occlusion: both sides bake full-height walls but the actual
            // fluid surface heights differ, so the gap is real despite per-face occlusion.
            if (CHECK_NEIGHBOR_FACE_OCCLUSION && ((bridgeMskX >> index) & 1) == 0) {
              if (ModelQueries.faceOccludes(
                  this.sectionData[bi + 1], (2 << 1) | (1 - facingForward))) {
                // TODO check self occlsion
                mesher.skip(1);
                continue;
              }
            }

            long A = this.sectionData[ai];
            long Am = this.sectionData[ai + 1];

            // TODO: check if must cull against next entries face
            if (ModelQueries.containsFluid(Am)) {
              int modelId = (int) ((A >> 26) & 0xFFFF);
              A &= ~(0xFFFFL << 26);
              int fluidId = this.modelMan.getFluidClientStateId(modelId);
              A |= Integer.toUnsignedLong(fluidId) << 26;
              Am = this.modelMan.getModelMetadataFromClientId(fluidId);

              // Update quad typing info to be the fluid type
              A &= ~0b110L;
              A |= getQuadTyping(Am);
            }

            long lighter = this.sectionData[bi];
            // if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check
            // this is right
            //    lighter = this.sectionData[bi];
            // }

            // Example thing thats just wrong but as example
            mesher.putNext(
                ((long) facingForward)
                    | // Facing
                    (A & ~LM)
                    | (lighter & LM) // Lighting
                );
          }
        }
      }

      // Need to skip the remaining entries in the skip array
      {
        msk = ~msk; // Invert the mask as we only need to set stuff that isnt 0
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk);
          msk &= ~Integer.lowestOneBit(msk);
          int skipCount;
          if (index < 11) {
            skipCount = (int) (sumA >> (index * 5));
          } else if (index < 22) {
            skipCount = (int) (sumB >> ((index - 11) * 5));
          } else {
            skipCount = (int) (sumC >> ((index - 22) * 5));
          }
          skipCount &= 0x1F;

          if (skipCount != 0) {
            this.xAxisMeshers[index].skip(skipCount);
          }
        }
      }
    }
  }

  // Second pass for X-axis bridge walls with the opposite facing direction. The main X pass always
  // emits bridge walls with facingForward determined by faceForwardMsk = msk & lMsk, which for
  // fluid-fluid boundaries is always 1 (toward +X). This pass emits the opposite direction so
  // bridge walls are visible from both sides.
  private void generateXBackwardBridgeWalls() {
    int[] meshLastPos = new int[32];
    java.util.Arrays.fill(meshLastPos, -1);
    int pos = 0;
    for (int y = 0; y < 32; y++) {
      for (int z = 0; z < 32; z++, pos++) {
        int bridgeMskX = this.computeFluidBridgeMaskX(y, z);
        if (bridgeMskX == 0) continue;

        int fMsk = this.fluidMasks[y * 32 + z];
        int oMsk = this.opaqueMasks[y * 32 + z];
        int lMsk = oMsk | fMsk;

        int iter = bridgeMskX;
        while (iter != 0) {
          int x = Integer.numberOfTrailingZeros(iter);
          iter &= iter - 1;

          int skip = pos - meshLastPos[x] - 1;
          if (skip > 0) this.xAxisMeshers[x].skip(skip);
          meshLastPos[x] = pos;

          int origFacingForward = ((lMsk >> x) & 1);
          int facingForward = 1 - origFacingForward;

          int idx = x + (z * 32) + (y * 32 * 32);
          int ai = (idx + (1 - facingForward)) * 2;
          int bi = (idx + facingForward) * 2;

          long A = this.sectionData[ai];
          long Am = this.sectionData[ai + 1];
          if (ModelQueries.containsFluid(Am)) {
            int modelId = (int) ((A >> 26) & 0xFFFF);
            A &= ~(0xFFFFL << 26);
            int fluidId = this.modelMan.getFluidClientStateId(modelId);
            A |= Integer.toUnsignedLong(fluidId) << 26;
            Am = this.modelMan.getModelMetadataFromClientId(fluidId);
            A &= ~0b110L;
            A |= getQuadTyping(Am);
          }
          long lighter = this.sectionData[bi];
          this.xAxisMeshers[x].putNext(((long) facingForward) | (A & ~LM) | (lighter & LM));
        }
      }
    }
  }

  private void generateXOuterFluidGeometry() {
    // Generate the side faces, hackily, using 0 and 31 mesher

    var ma = this.xAxisMeshers[0];
    var mb = this.xAxisMeshers[31];
    ma.finish();
    mb.finish();
    ma.doAuxiliaryFaceOffset = false;
    mb.doAuxiliaryFaceOffset = false;

    for (int y = 0; y < 32; y++) {
      int skipA = 0;
      int skipB = 0;
      for (int z = 0; z < 32; z++) {
        int i = y * 32 + z;
        int msk = this.fluidMasks[i];
        if ((msk & 1) != 0) { // -x
          long neighborId = this.neighboringFaces[i];
          boolean oki = true;

          int sidx = (i << 5) * 2;
          long A = this.sectionData[sidx];
          long Am = this.sectionData[sidx + 1];

          if (ModelQueries.containsFluid(Am)) {
            int modelId = (int) ((A >> 26) & 0xFFFF);
            A &= ~(0xFFFFL << 26);
            int fluidId = this.modelMan.getFluidClientStateId(modelId);
            A |= Integer.toUnsignedLong(fluidId) << 26;
            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

            // Update quad typing info to be the fluid type
            A &= ~0b110L;
            A |= getQuadTyping(Am);
          }

          if (Mapper.getBlockId(neighborId) != 0) { // Not air

            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
            if (ModelQueries.isFullyOpaque(meta)) {
              oki = false;
            }

            // Check neighbor face
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
              if (ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 0))) {
                oki = false;
              }
            }

            if (ModelQueries.containsFluid(meta)) {
              modelId = this.modelMan.getFluidClientStateId(modelId);
            }

            if (ModelQueries.cullsSame(Am)) {
              if (modelId == ((A >> 26) & 0xFFFF)) {
                oki = false;
              }
            }
          }

          if (oki) {
            ma.skip(skipA);
            skipA = 0;

            // TODO: LIGHTING
            long lightData = ((neighborId & (0xFFL << 56)) >>> 1); // A;
            // if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check
            // this is right
            //    lighter = this.sectionData[bi];
            // }

            ma.putNext(0L | (A & ~LM) | lightData);
          } else {
            skipA++;
          }
        } else {
          skipA++;
        }

        if ((msk & (1 << 31)) != 0) { // +x
          long neighborId = this.neighboringFaces[i + 32 * 32];
          boolean oki = true;

          int sidx = (i * 32 + 31) * 2;
          long A = this.sectionData[sidx];
          long Am = this.sectionData[sidx + 1];

          // TODO: check if must cull against next entries face
          if (ModelQueries.containsFluid(Am)) {
            int modelId = (int) ((A >> 26) & 0xFFFF);
            A &= ~(0xFFFFL << 26);
            int fluidId = this.modelMan.getFluidClientStateId(modelId);
            A |= Integer.toUnsignedLong(fluidId) << 26;
            Am = this.modelMan.getModelMetadataFromClientId(fluidId);
          }

          if (Mapper.getBlockId(neighborId) != 0) { // Not air
            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
            if (ModelQueries.isFullyOpaque(meta)) {
              oki = false;
            }

            // Check neighbor face
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
              if (ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 1))) {
                oki = false;
              }
            }

            if (ModelQueries.containsFluid(meta)) {
              modelId = this.modelMan.getFluidClientStateId(modelId);
            }

            if (ModelQueries.cullsSame(Am)) {
              if (modelId == ((A >> 26) & 0xFFFF)) {
                oki = false;
              }
            }
          }

          if (oki) {
            mb.skip(skipB);
            skipB = 0;

            // TODO: LIGHTING
            long lightData = ((neighborId & (0xFFL << 56)) >>> 1); // A;
            // if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check
            // this is right
            //    lighter = this.sectionData[bi];
            // }

            mb.putNext(1L | (A & ~LM) | lightData);
          } else {
            skipB++;
          }
        } else {
          skipB++;
        }
      }
      ma.skip(skipA);
      mb.skip(skipB);
    }

    ma.finish();
    mb.finish();
    ma.doAuxiliaryFaceOffset = true;
    mb.doAuxiliaryFaceOffset = true;
  }

  private void generateXNonOpaqueInnerGeometry() {
    for (int y = 0; y < 32; y++) {
      long sumA = 0;
      long sumB = 0;
      long sumC = 0;
      int partialHasCount = -1;
      int msk = 0;
      for (int z = 0; z < 32; z++) {
        msk = this.nonOpaqueMasks[y * 32 + z] & (~0x80000001); // Dont mesh the outer layer

        // Always increment cause can do funny trick (i.e. -1 on skip amount)
        sumA += X_I_MSK;
        sumB += X_I_MSK;
        sumC += X_I_MSK;

        partialHasCount &= ~msk;

        if (z == 30 && partialHasCount != 0) { // Hackfix for incremental count overflow issue
          int cmsk = partialHasCount;
          while (cmsk != 0) {
            int index = Integer.numberOfTrailingZeros(cmsk);
            cmsk &= ~Integer.lowestOneBit(cmsk);

            this.xAxisMeshers[index].skip(31);
            this.secondaryXAxisMeshers[index].skip(31);
          }
          // Clear the sum
          sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK) * 0x1F);
          sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, X_I_MSK) * 0x1F);
          sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, X_I_MSK) * 0x1F);
        }

        if (msk == 0) {
          continue;
        }

        int iter = msk;
        while (iter != 0) {
          int index = Integer.numberOfTrailingZeros(iter);
          iter &= ~Integer.lowestOneBit(iter);

          int skipCount; // Compute the skip count
          { // TODO: Branch-less
            // Compute skip and clear
            if (index < 11) {
              skipCount = (int) (sumA >> (index * 5));
              sumA &= ~(0x1FL << (index * 5));
            } else if (index < 22) {
              skipCount = (int) (sumB >> ((index - 11) * 5));
              sumB &= ~(0x1FL << ((index - 11) * 5));
            } else {
              skipCount = (int) (sumC >> ((index - 22) * 5));
              sumC &= ~(0x1FL << ((index - 22) * 5));
            }
            skipCount &= 0x1F;
            skipCount--;
          }

          var mesherA = this.xAxisMeshers[index];
          var mesherB = this.secondaryXAxisMeshers[index];
          if (skipCount != 0) {
            mesherA.skip(skipCount);
            mesherB.skip(skipCount);
          }

          {
            int idx = index + (z * 32) + (y * 32 * 32);

            long A = this.sectionData[idx * 2];
            long Am = this.sectionData[idx * 2 + 1];

            // Check and generate the mesh for both + and - faces
            meshNonOpaqueFace(
                2 << 1,
                A,
                Am,
                this.sectionData[(idx - 1) * 2],
                this.sectionData[(idx - 1) * 2 + 1],
                mesherB); // -
            meshNonOpaqueFace(
                (2 << 1) | 1,
                A,
                Am,
                this.sectionData[(idx + 1) * 2],
                this.sectionData[(idx + 1) * 2 + 1],
                mesherA); // +
          }
        }
      }

      // Need to skip the remaining entries in the skip array
      {
        msk = ~msk; // Invert the mask as we only need to set stuff that isnt 0
        while (msk != 0) {
          int index = Integer.numberOfTrailingZeros(msk);
          msk &= ~Integer.lowestOneBit(msk);
          int skipCount;
          if (index < 11) {
            skipCount = (int) (sumA >> (index * 5));
          } else if (index < 22) {
            skipCount = (int) (sumB >> ((index - 11) * 5));
          } else {
            skipCount = (int) (sumC >> ((index - 22) * 5));
          }
          skipCount &= 0x1F;

          if (skipCount != 0) {
            this.xAxisMeshers[index].skip(skipCount);
            this.secondaryXAxisMeshers[index].skip(skipCount);
          }
        }
      }
    }
  }

  private static void dualMeshNonOpaqueOuterX(
      int side,
      long quad,
      long meta,
      int neighborAId,
      int neighborLight,
      long neighborAMeta,
      long neighborBQuad,
      long neighborBMeta,
      Mesher ma,
      Mesher mb) {
    // side == 0 if is on 0 side and 1 if on 31 side

    // TODO: Check (neighborAId!=0) && works oki
    if ((neighborAId == 0 && ModelQueries.faceExists(meta, ((2 << 1) | 0) ^ side))
        || (neighborAId != 0
            && shouldMeshNonOpaqueBlockFace(
                ((2 << 1) | 0) ^ side, quad, meta, ((long) neighborAId) << 26, neighborAMeta))) {
      ma.putNext(
          ((long) side)
              | (quad & ~LM)
              | (ModelQueries.faceUsesSelfLighting(meta, ((2 << 1) | 0) ^ side)
                  ? quad
                  : (((long) neighborLight) << 55)));
    } else {
      ma.skip(1);
    }

    if (shouldMeshNonOpaqueBlockFace(
        ((2 << 1) | 1) ^ side, quad, meta, neighborBQuad, neighborBMeta)) {
      mb.putNext(
          ((long) (side ^ 1))
              | (quad & ~LM)
              | ((ModelQueries.faceUsesSelfLighting(meta, ((2 << 1) | 1) ^ side)
                      ? quad
                      : neighborBQuad)
                  & (0xFFL << 55)));
    } else {
      mb.skip(1);
    }
  }

  private void generateXNonOpaqueOuterGeometry() {
    var npx = this.xAxisMeshers[0];
    npx.finish();
    var nnx = this.secondaryXAxisMeshers[0];
    nnx.finish();
    var ppx = this.xAxisMeshers[31];
    ppx.finish();
    var pnx = this.secondaryXAxisMeshers[31];
    pnx.finish();

    for (int y = 0; y < 32; y++) {
      int skipA = 0;
      int skipB = 0;
      for (int z = 0; z < 32; z++) {
        int i = y * 32 + z;
        int msk = this.nonOpaqueMasks[i];
        if ((msk & 1) != 0) { // -x
          long neighborId = this.neighboringFaces[i];
          // TODO also check self occlusion

          int sidx = (i << 5) * 2;
          long A = this.sectionData[sidx];
          long Am = this.sectionData[sidx + 1];

          int modelId = 0;
          long nM = 0;
          if (Mapper.getBlockId(neighborId) != 0) { // Not air
            modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
            nM = this.modelMan.getModelMetadataFromClientId(modelId);
          }

          nnx.skip(skipA);
          npx.skip(skipA);
          skipA = 0;

          dualMeshNonOpaqueOuterX(
              0,
              A,
              Am,
              modelId,
              Mapper.getLightId(neighborId),
              nM,
              this.sectionData[sidx + 2],
              this.sectionData[sidx + 3],
              nnx,
              npx);
        } else {
          skipA++;
        }

        if ((msk & (1 << 31)) != 0) { // +x
          long neighborId = this.neighboringFaces[i + 32 * 32];
          // TODO also check self occlusion

          int sidx = (i * 32 + 31) * 2;
          long A = this.sectionData[sidx];
          long Am = this.sectionData[sidx + 1];

          int modelId = 0;
          long nM = 0;
          if (Mapper.getBlockId(neighborId) != 0) { // Not air
            modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
            nM = this.modelMan.getModelMetadataFromClientId(modelId);
          }

          pnx.skip(skipB);
          ppx.skip(skipB);
          skipB = 0;

          dualMeshNonOpaqueOuterX(
              1,
              A,
              Am,
              modelId,
              Mapper.getLightId(neighborId),
              nM,
              this.sectionData[sidx - 2],
              this.sectionData[sidx - 1],
              ppx,
              pnx);
        } else {
          skipB++;
        }
      }
      nnx.skip(skipA);
      npx.skip(skipA);
      pnx.skip(skipB);
      ppx.skip(skipB);
    }
  }

  private void generateXFaces() {
    this.generateXOpaqueInnerGeometry();
    this.generateXOuterOpaqueGeometry();

    for (var mesher : this.xAxisMeshers) {
      mesher.finish();
    }

    this.generateXInnerFluidGeometry();
    this.generateXOuterFluidGeometry();

    for (var mesher : this.xAxisMeshers) {
      mesher.finish();
    }

    if (FLUID_BRIDGE_WALLS) {
      this.generateXBackwardBridgeWalls();
      for (var mesher : this.xAxisMeshers) {
        mesher.finish();
      }
    }

    if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
      this.generateXNonOpaqueInnerGeometry();
      this.generateXNonOpaqueOuterGeometry();

      for (var mesher : this.xAxisMeshers) {
        mesher.finish();
      }
      for (var mesher : this.secondaryXAxisMeshers) {
        mesher.finish();
      }
    }
  }

  /**
   * Stage 2b-1: emits the extra depth-band planes (1..N-1) of multi-plane Dynamic Trees roots.
   *
   * <p>Plane 0 is the base root model, already emitted by the normal non-opaque passes. Extra
   * planes are emitted only on INNER cells (coordinate 1..30 on every axis): section-boundary cells
   * need the cross-section neighbour decision that Stage 2b-2 adds, and inner cells also guarantee
   * that every neighbour index is in range. Runs after the face passes so its quads still count
   * into quadCount/AABB before occupancy and BuiltSection finalization.
   */
  private void generateRootPlaneGeometry(long[] rawSectionData) {
    if (!this.modelMan.isRootMultiplaneEnabled()
        || this.rootBlockCount == 0
        || !CHECK_NEIGHBOR_FACE_OCCLUSION) {
      return;
    }
    for (int i = 0; i < this.rootBlockCount; i++) {
      int idx = this.rootBlockIndices[i];
      int[] planes = this.rootCellPlanes[idx];
      if (planes == null || planes.length < 2) {
        continue;
      }
      int x = idx & 31;
      int z = (idx >> 5) & 31;
      int y = idx >> 10;
      if (x == 0 || x == 31 || y == 0 || y == 31 || z == 0 || z == 31) {
        this.rootPlaneBoundarySkipped++;
        continue;
      }
      long selfQuad = this.sectionData[idx * 2];
      long selfMeta = this.sectionData[idx * 2 + 1];
      // Planes outer, faces inner: model metadata is fetched once per plane and reused for all six
      // face tests.
      for (int p = 1; p < planes.length; p++) {
        // Identical depth bands dedupe to the same model id; emitting a coplanar duplicate would
        // only double overdraw, so skip consecutive repeats.
        if (p > 1 && planes[p] == planes[p - 1]) {
          continue;
        }
        long pmeta = this.modelMan.getModelMetadataFromClientId(planes[p]);
        for (int face = 0; face < 6; face++) {
          // Trunk-facing sides are hidden inside the composite root model (plane 0); the extra
          // plane ids have no such filtering, so skip them here as well.
          if ((this.rootCellHiddenSides[idx] & (1 << face)) != 0) {
            this.rootPlaneHiddenSkipped++;
            continue;
          }
          int nIdx =
              switch (face) {
                case 0 -> idx - 1024; // DOWN
                case 1 -> idx + 1024; // UP
                case 2 -> idx - 32; // NORTH
                case 3 -> idx + 32; // SOUTH
                case 4 -> idx - 1; // WEST
                default -> idx + 1; // EAST
              };
          long neighborQuad = this.sectionData[nIdx * 2];
          if (!shouldMeshNonOpaqueBlockFace(
              face, selfQuad, selfMeta, neighborQuad, this.sectionData[nIdx * 2 + 1])) {
            continue;
          }
          if (!ModelQueries.faceExists(pmeta, face)) {
            continue;
          }
          // Same bucket emitQuad derives; type 2 is the six directional face buffers.
          int type = ((int) (getQuadTyping(pmeta) >> 1)) & 3;
          int bucket = type + (type == 2 ? face : 0);
          if (this.quadCounters[bucket] + 1 >= (1 << 16)) {
            if (ROOT_PLANE_CAPACITY_LOG.compareAndSet(false, true)) {
              Logger.warn(
                  "Dynamic Trees root plane quads dropped: geometry bucket "
                      + bucket
                      + " is at capacity ("
                      + (1 << 16)
                      + " quads)");
            }
            continue;
          }
          long planeQuad = packPartialQuadData(planes[p], rawSectionData[idx], pmeta);
          // Mirrors meshNonOpaqueFace: recessed planes use their own light only when the face
          // self-lights, otherwise the neighbour's light.
          long lighter = ModelQueries.faceUsesSelfLighting(pmeta, face) ? planeQuad : neighborQuad;
          long data = (planeQuad & ~LM) | (lighter & LM) | (face & 1);
          this.emitRootPlaneQuad(face, x, y, z, data);
          this.rootPlaneQuadCount++;
        }
      }
    }
    if ((this.rootPlaneQuadCount != 0
            || this.rootPlaneBoundarySkipped != 0
            || this.rootPlaneHiddenSkipped != 0)
        && ROOT_PLANE_DIAG_LOG.compareAndSet(false, true)) {
      Logger.info(
          "DT root multi-plane mesher: extraQuads="
              + this.rootPlaneQuadCount
              + " boundaryCellsSkipped="
              + this.rootPlaneBoundarySkipped
              + " hiddenFacesSkipped="
              + this.rootPlaneHiddenSkipped);
    }
  }

  /**
   * Emits one isolated quad through the scratch mesher using the same swizzle as the scanline
   * loops: axis 0 (Y) takes (x,z), axis 1 (Z) takes (x,y), axis 2 (X) takes (z,y). emitQuad writes
   * the owning block's coordinate as the auxiliary position, so the face offset is enabled only
   * for positive faces (side 1), matching the {@code face & 1} bit the mesh loops putNext.
   */
  private void emitRootPlaneQuad(int face, int x, int y, int z, long data) {
    int axis = face >> 1;
    int side = face & 1;
    this.rootPlaneMesher.axis = axis;
    this.rootPlaneMesher.doAuxiliaryFaceOffset = side == 1;
    if (axis == 0) {
      this.rootPlaneMesher.auxiliaryPosition = y;
      this.rootPlaneMesher.emitQuad(x, z, 1, 1, data);
    } else if (axis == 1) {
      this.rootPlaneMesher.auxiliaryPosition = z;
      this.rootPlaneMesher.emitQuad(x, y, 1, 1, data);
    } else {
      this.rootPlaneMesher.auxiliaryPosition = x;
      this.rootPlaneMesher.emitQuad(z, y, 1, 1, data);
    }
  }

  // Build the occupancy set (used for AO) from the set of fully opaque blocks (atm, this can change
  // in the future if needed to a special occupancy bitset)
  private final void buildOccupancy() {
    // We basicly want to record all the points where we go from air to solid or solid to air (this
    // is to just get better compression)
    for (int i = 0; i < 32 * 32; i++) {
      int occ = 0;
      int msk = this.opaqueMasks[i];
      // x
      occ |= msk ^ (msk >> 1);
      occ |= msk ^ (msk << 1);
      // y
      occ |= i < 32 * 31 ? msk ^ this.opaqueMasks[i + 32] : 0;
      occ |= 31 < i ? msk ^ this.opaqueMasks[i - 32] : 0;
      // z
      occ |= (i & 31) < 31 ? msk ^ this.opaqueMasks[i + 1] : 0;
      occ |= 0 < (i & 31) ? msk ^ this.opaqueMasks[i - 1] : 0;

      // We now have our occlusion mask, fill in our occupancy set
      for (; occ != 0; occ &= ~Integer.lowestOneBit(occ)) {
        this.occupancy.set(i * 32 + Integer.numberOfTrailingZeros(occ));
      }
    }
  }

  // section is already acquired and gets released by the parent
  public BuiltSection generateMesh(WorldSection section) {
    // TODO: FIXME: because of the exceptions that are thrown when aquiring modelId
    // this can result in the state of all block meshes and well _everything_ from being incorrect
    // THE EXCEPTION THAT THIS THROWS CAUSES MAJOR ISSUES

    // Copy section data to end of array so that can mutate array while reading safely
    // section.copyDataTo(this.sectionData, 32*32*32);

    // We must reset _everything_ that could have changed as we dont exactly know the state due to
    // how the model id exception
    // throwing system works
    this.quadCount = 0;
    this.hasCutout = false;

    { // Reset all the block meshes
      this.blockMesher.reset();
      this.blockMesher.doAuxiliaryFaceOffset = true;
      this.seondaryblockMesher.reset();
      this.seondaryblockMesher.doAuxiliaryFaceOffset = true;
      for (var mesher : this.xAxisMeshers) {
        mesher.reset();
        mesher.doAuxiliaryFaceOffset = true;
      }
      if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
        for (var mesher : this.secondaryXAxisMeshers) {
          mesher.reset();
          mesher.doAuxiliaryFaceOffset = false;
        }
      }
    }
    if (this.occupancy != null) {
      this.occupancy.reset();
    }

    this.minX = Integer.MAX_VALUE;
    this.minY = Integer.MAX_VALUE;
    this.minZ = Integer.MAX_VALUE;
    this.maxX = Integer.MIN_VALUE;
    this.maxY = Integer.MIN_VALUE;
    this.maxZ = Integer.MIN_VALUE;

    Arrays.fill(this.quadCounters, 0);
    Arrays.fill(this.opaqueMasks, 0);
    Arrays.fill(this.nonOpaqueMasks, 0);
    Arrays.fill(this.fluidMasks, 0);
    this.variantBlockCount = 0;
    this.shellBlockCount = 0;
    // Drop references to the previous mesh's plane arrays before rootBlockIndices is reused.
    for (int i = 0; i < this.rootBlockCount; i++) {
      int rootIdx = this.rootBlockIndices[i];
      this.rootCellPlanes[rootIdx] = null;
      this.rootCellHiddenSides[rootIdx] = 0;
    }
    this.rootBlockCount = 0;
    this.overlayRoots.clear();
    this.rootPatchedCount = 0;
    this.rootUnavailableCount = 0;
    this.rootMaskRejectedCount = 0;
    this.rootPlaneQuadCount = 0;
    this.rootPlaneBoundarySkipped = 0;
    this.rootPlaneHiddenSkipped = 0;
    this.shellPatchedCount = 0;
    this.shellUnavailableCount = 0;
    this.branchMaskPatchedCount = 0;
    this.shellSkippedCornerBothOutside = 0;
    this.shellSkippedMskBitMissing = 0;
    this.shellSkippedCoreNotThick = 0;
    this.shellSkippedCoreDirNull = 0;
    this.shellLod1FallbackCount = 0;
    this.rootLod1FallbackCount = 0;

    // Prepare everything. Per-position Dynamic Trees variants only exist at LOD0; mips and other
    // levels keep using the plain per-BlockState model ids.
    boolean lod0 = section.lvl == 0;
    int neighborMskAndFlags = this.prepareSectionData(section._unsafeGetRawDataArray(), lod0);
    if ((neighborMskAndFlags & (1 << 31)) != 0) { // We failed to get everything so throw exception
      throw new IdNotYetComputedException(neighborMskAndFlags & ((1 << 20) - 1), true);
    }
    int neighborMsk = neighborMskAndFlags & 0b11_11_11;
    int flags = neighborMskAndFlags >>> 6;
    if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
      this.acquireNeighborData(section, neighborMsk);
    }

    try {
      if (lod0 && this.variantBlockCount != 0 && CHECK_NEIGHBOR_FACE_OCCLUSION) {
        this.resolveBranchVariants(section._unsafeGetRawDataArray(), neighborMsk);
      }
      if (lod0 && this.shellBlockCount != 0) {
        this.resolveTrunkShells(section._unsafeGetRawDataArray(), neighborMsk, section);
      }
      if (lod0 && this.rootBlockCount != 0) {
        this.resolveSurfaceRoots(section._unsafeGetRawDataArray(), neighborMsk, section);
      }
      if (lod0 && DTRootGeometryCache.isActive()) {
        // Publish (or clear) the overlay entry on every LOD0 mesh: an empty list marks the section
        // as root-free and drops stale roots from an earlier mesh. After resolveSurfaceRoots, so a
        // thrown IdNotYetComputedException does not publish a half-resolved list.
        DTRootGeometryCache.putSection(
            SectionPos.asLong(section.x, section.y, section.z), this.overlayRoots);
      }
      if (lod0
          && (this.rootBlockCount != 0
              || this.shellBlockCount != 0
              || this.variantBlockCount != 0)
          && SECTION_DIAG_LOGS.getAndIncrement() < 24) {
        Logger.info(
            "DT LOD0 section diag @ "
                + section.x
                + ","
                + section.y
                + ","
                + section.z
                + " rootsEnabled="
                + DTRootGeometryCache.ROOTS_ENABLED
                + " roots="
                + this.rootBlockCount
                + " patched="
                + this.rootPatchedCount
                + " unavailable="
                + this.rootUnavailableCount
                + " maskRejected="
                + this.rootMaskRejectedCount
                + " shells="
                + this.shellBlockCount
                + " patched="
                + this.shellPatchedCount
                + " unavailable="
                + this.shellUnavailableCount
                + " shellSkips=corner:"
                + this.shellSkippedCornerBothOutside
                + ",msk:"
                + this.shellSkippedMskBitMissing
                + ",notThick:"
                + this.shellSkippedCoreNotThick
                + ",dirNull:"
                + this.shellSkippedCoreDirNull
                + " lod1Fallback=shell:"
                + this.shellLod1FallbackCount
                + ",root:"
                + this.rootLod1FallbackCount
                + " branches="
                + this.variantBlockCount
                + " maskPatched="
                + this.branchMaskPatchedCount);
      }
      this.generateYZFaces();
      this.generateXFaces();
      this.generateRootPlaneGeometry(section._unsafeGetRawDataArray());
    } catch (IdNotYetComputedException e) {
      e.auxBitMsk = neighborMsk;
      e.auxData = this.neighboringFaces;
      throw e;
    }

    // We only care if we have quads
    if (this.occupancy != null
        && section.lvl == 0 /*only generate occupancy for lowest lod level*/
        && this.quadCount != 0
        && (flags & 1) != 0) {
      this.buildOccupancy();
    }

    // TODO:NOTE! when doing face culling of translucent blocks,
    // if the connecting type of the translucent block is the same AND the face is full, discard it
    // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc
    if (this.quadCount == 0) {
      return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
    }

    if (this.quadCount >= 1 << 16) {
      Logger.warn(
          "Large quad count for section "
              + WorldEngine.pprintPos(section.key)
              + " is "
              + this.quadCount);
    }

    if (this.minX < 0
        || this.minY < 0
        || this.minZ < 0
        || 32 < this.maxX
        || 32 < this.maxY
        || 32 < this.maxZ) {
      throw new IllegalStateException();
    }

    int[] offsets = new int[8];
    var buff = new MemoryBuffer(this.quadCount * 8L);
    long ptr = buff.address;
    int coff = 0;
    for (int buffer = 0; buffer < 8; buffer++) { // translucent, double sided quads, 6 faces
      offsets[buffer] = coff;
      int size = this.quadCounters[buffer];
      UnsafeUtil.memcpy(
          this.quadBufferPtr + (buffer * (8 * (1 << 16))), ptr + coff * 8L, (size * 8L));
      coff += size;
    }

    int aabb = 0;
    aabb |= this.minX;
    aabb |= this.minY << 5;
    aabb |= this.minZ << 10;
    aabb |= (this.maxX - this.minX - 1) << 15;
    aabb |= (this.maxY - this.minY - 1) << 20;
    aabb |= (this.maxZ - this.minZ - 1) << 25;
    aabb |= this.hasCutout ? (1 << 30) : 0;

    MemoryBuffer occupancy = null;
    if (this.occupancy != null && !this.occupancy.isEmpty()) {
      occupancy = new MemoryBuffer(this.occupancy.writeSize());
      this.occupancy.write(occupancy.address, false);
    }

    return new BuiltSection(
        section.key, section.getNonEmptyChildren(), aabb, buff, offsets, occupancy);
  }

  public void free() {
    this.quadBuffer.free();
  }
}
