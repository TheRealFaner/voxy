package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.backend.RenderStage;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

  public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
    super(device, vertexType);
  }

  @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
  private void cancelThingie(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      boolean indexedRenderingEnabled,
      CallbackInfo ci) {
    if (renderPass.isTranslucent() && !IrisUtil.irisShaderPackEnabled()) {
      this.renderVoxyTranslucent(matrices, camera);
    }
    // Occlusion-debug path only: Voxy replaces Sodium's near chunks. The diagnostic
    // vanillaTerrain switch must never cancel Sodium's own chunk pass, otherwise both renderers
    // end up off for near chunks (transparent terrain).
    if (VoxyClient.disableSodiumChunkRender() && !VoxyClient.isVanillaTerrain()) {
      super.begin(renderPass);
      this.doRender(matrices, renderLists, renderPass, camera);
      super.end(renderPass);
      ci.cancel();
    }
  }

  @Inject(
      method = "render",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
              shift = At.Shift.BEFORE))
  private void injectRender(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      boolean indexedRenderingEnabled,
      CallbackInfo ci) {
    this.doRender(matrices, renderLists, renderPass, camera);
  }

  @Unique
  private void doRender(
      ChunkRenderMatrices matrices,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera) {
    var renderer =
        ((IVoxyRenderSystemHolder) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
    if (renderer == null) {
      return;
    }

    // vanillaTerrain: Sodium's own chunk pass already drew the near scene, so do not feed its
    // render lists into Voxy's LOD0 sync stages. The distant OPAQUE stage keeps running, so Voxy
    // still renders LOD beyond the vanilla render distance.
    boolean vanillaTerrain = VoxyClient.isVanillaTerrain();

    if (renderPass == DefaultTerrainRenderPasses.SOLID) {
      if (vanillaTerrain) {
        return;
      }
      renderer.beginVanillaRenderSectionSync();
      renderer.syncVanillaRenderSections(renderLists, renderPass.isTranslucent());
      renderer.storeFrame(
          renderer.runFrameStage(
              RenderStage.SODIUM_SOLID_SYNC,
              renderer.currentFrame(),
              matrices,
              camera.x,
              camera.y,
              camera.z,
              IrisUtil.IRIS_INSTALLED,
              IrisUtil.irisShaderPackEnabled()));
      return;
    }

    if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
      boolean shaderPackActive = IrisUtil.irisShaderPackEnabled();
      var frame = renderer.currentFrame();
      if (!vanillaTerrain) {
        renderer.syncVanillaRenderSections(renderLists, renderPass.isTranslucent());
        frame =
            renderer.runFrameStage(
                RenderStage.SODIUM_CUTOUT_SYNC,
                renderer.currentFrame(),
                matrices,
                camera.x,
                camera.y,
                camera.z,
                IrisUtil.IRIS_INSTALLED,
                shaderPackActive);
      }
      frame =
          renderer.runFrameStage(
              RenderStage.OPAQUE,
              frame,
              matrices,
              camera.x,
              camera.y,
              camera.z,
              IrisUtil.IRIS_INSTALLED,
              shaderPackActive);
      if (shaderPackActive) {
        renderer.storeFrame(frame);
      } else {
        renderer.clearFrame();
      }
    }
  }

  @Unique
  private void renderVoxyTranslucent(ChunkRenderMatrices matrices, CameraTransform camera) {
    var renderer =
        ((IVoxyRenderSystemHolder) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
    if (renderer == null) {
      return;
    }
    renderer.runFrameStage(
        RenderStage.TRANSLUCENT,
        renderer.currentFrame(),
        matrices,
        camera.x,
        camera.y,
        camera.z,
        IrisUtil.IRIS_INSTALLED,
        false);
  }
}
