package me.cortex.voxy.client.core.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * True-geometry overlay for Dynamic Trees surface roots.
 *
 * <p>Voxy's LOD0 impostor cannot represent DT's multi-plane root model, so root cells are removed
 * from the impostor stream ({@code DTRootGeometryCache} collection) and drawn here with the vanilla
 * block renderer instead: the exact SurfaceRootBlockBakedModel quads, the vanilla atlas, lightmap,
 * tint and AO. The 3x3x3 connection cube captured during meshing is fed back through {@link
 * ModelData}, so cross-section connections match Dynamic Trees exactly. Roots are skipped where
 * vanilla renders them: the chunk must be loaded AND inside the vanilla render distance (the client
 * stores more chunks than it renders).
 */
public final class DTRootOverlayRenderer {
  private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
      new java.util.concurrent.atomic.AtomicBoolean();
  private static long lastLogNanos;

  private DTRootOverlayRenderer() {}

  public static void render(PoseStack poseStack, Camera camera) {
    if (!DTRootGeometryCache.ROOTS_ENABLED) {
      // Master switch off: the loader hook may still fire, but it must do no work at all. The
      // one-shot DISABLED message is logged by DTRootGeometryCache on first class load.
      return;
    }
    if (!DTRootGeometryCache.isActive() || DTRootGeometryCache.isEmpty()) {
      return;
    }
    ClientLevel level = Minecraft.getInstance().level;
    if (level == null) {
      return;
    }
    if (STARTED.compareAndSet(false, true)) {
      me.cortex.voxy.common.Logger.info("DT root overlay renderer active");
    }
    Vec3 cam = camera.getPosition();
    int rd = Minecraft.getInstance().options.getEffectiveRenderDistance();
    int camChunkX = SectionPos.blockToSectionCoord((int) Math.floor(cam.x));
    int camChunkZ = SectionPos.blockToSectionCoord((int) Math.floor(cam.z));
    // Voxy's LOD0 range in blocks. The old hardcoded 512 was a Euclidean sphere and culled
    // sections that the LOD0 renderer still draws at large render distances.
    int lod0Distance = Math.max(32, (int) (VoxyConfig.CONFIG.sectionRenderDistance * 32));
    BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
    MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    boolean any = false;
    int cachedRoots = 0;
    int drawn = 0;
    int skippedLoaded = 0;
    for (Map.Entry<Long, List<DTRootGeometryCache.RootBlock>> entry :
        DTRootGeometryCache.sections().entrySet()) {
      int sx = SectionPos.x(entry.getKey());
      int sy = SectionPos.y(entry.getKey());
      int sz = SectionPos.z(entry.getKey());
      double dx = (sx << 5) + 16 - cam.x;
      double dy = (sy << 5) + 16 - cam.y;
      double dz = (sz << 5) + 16 - cam.z;
      // Chebyshev test in blocks against the LOD0 range, with a generous vertical bound.
      if (Math.max(Math.abs(dx), Math.abs(dz)) > lod0Distance || Math.abs(dy) > lod0Distance) {
        continue;
      }
      cachedRoots += entry.getValue().size();
      for (DTRootGeometryCache.RootBlock root : entry.getValue()) {
        int cx = root.x() >> 4;
        int cz = root.z() >> 4;
        // The client stores viewDistance+3 chunks but vanilla renders only viewDistance, so a
        // loaded-chunk-only test skipped a ~3-chunk ring that vanilla does not draw.
        boolean vanillaVisible =
            level.getChunkSource().hasChunk(cx, cz)
                && Math.max(Math.abs(cx - camChunkX), Math.abs(cz - camChunkZ)) <= rd;
        if (vanillaVisible) {
          skippedLoaded++;
          continue; // vanilla already renders roots in loaded chunks within the render distance
        }
        poseStack.pushPose();
        poseStack.translate(root.x() - cam.x, root.y() - cam.y, root.z() - cam.z);
        CubeGetter getter = new CubeGetter(root);
        BakedModel model = dispatcher.getBlockModel(root.state());
        ModelData data = model.getModelData(getter, BlockPos.ZERO, root.state(), ModelData.EMPTY);
        RenderType renderType =
            net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(root.state());
        VertexConsumer consumer = buffers.getBuffer(renderType);
        dispatcher
            .getModelRenderer()
            .tesselateBlock(
                getter,
                model,
                root.state(),
                BlockPos.ZERO,
                poseStack,
                consumer,
                true,
                RandomSource.create(),
                root.state().getSeed(BlockPos.ZERO),
                OverlayTexture.NO_OVERLAY,
                data,
                renderType);
        poseStack.popPose();
        drawn++;
        any = true;
      }
    }
    long now = System.nanoTime();
    if (cachedRoots > 0 && now - lastLogNanos > 5_000_000_000L) {
      lastLogNanos = now;
      me.cortex.voxy.common.Logger.info(
          "DT root overlay: rootsEnabled="
              + DTRootGeometryCache.ROOTS_ENABLED
              + " sections="
              + DTRootGeometryCache.sections().size()
              + " roots="
              + cachedRoots
              + " drawn="
              + drawn
              + " skippedLoaded="
              + skippedLoaded);
    }
    if (any) {
      buffers.endBatch();
    }
  }

  /** A synthetic level backed by the captured 3x3x3 cube, used only for ModelData/AO/light. */
  private static final class CubeGetter implements BlockAndTintGetter {
    private final DTRootGeometryCache.RootBlock root;

    private CubeGetter(DTRootGeometryCache.RootBlock root) {
      this.root = root;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      int dx = pos.getX();
      int dy = pos.getY();
      int dz = pos.getZ();
      if (dx == 0 && dy == 0 && dz == 0) {
        return this.root.state();
      }
      if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
        return Blocks.AIR.defaultBlockState();
      }
      BlockState state = this.root.cube()[(dx + 1) + 3 * (dy + 1) + 9 * (dz + 1)];
      return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return this.getBlockState(pos).getFluidState();
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
        return level.getShade(direction, shaded);
      }
      if (!shaded) {
        return 1.0f;
      }
      return switch (direction) {
        case DOWN -> 0.5f;
        case UP -> 1.0f;
        case NORTH, SOUTH -> 0.8f;
        case WEST, EAST -> 0.6f;
      };
    }

    @Override
    public LevelLightEngine getLightEngine() {
      return null;
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
      return type == LightLayer.BLOCK ? (this.root.light() >> 4) & 0xF : this.root.light() & 0xF;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
      return 0xFFFFFFFF;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
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
  }
}
