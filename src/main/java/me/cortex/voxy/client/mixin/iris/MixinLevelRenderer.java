package me.cortex.voxy.client.mixin.iris;

import static org.lwjgl.opengl.GL11C.glViewport;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
  @Shadow @Final private Minecraft minecraft;

  @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
  private void voxy$injectIrisCompat(
      DeltaTracker tickCounter,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f positionMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    if (IrisUtil.irisShaderPackEnabled()) {
      var renderer = ((IVoxyRenderSystemHolder) this).voxy$getRenderSystem();
      if (renderer != null) {
        // Fixthe fucking viewport dims, fuck iris
        glViewport(
            0,
            0,
            Minecraft.getInstance().getMainRenderTarget().width,
            Minecraft.getInstance().getMainRenderTarget().height);

        // D3/F4: capture into the renderer this LevelRenderer owns, and skip the nested portal
        // render entirely. The scope already nulls the field at begin, so the main view's params
        // can never be replaced by the portal camera's. D1: a nested renderLevel without a scope
        // is not a portal pass at all (bridge absent/failed); it must not poison the main view's
        // captured parameters either.
        if (!renderer.isPortalViewActive() && !VoxyRenderSystem.isNestedLevelRender()) {
          var pos = camera.getPosition();
          renderer.captureViewportParameters(
              new IrisUtil.CapturedViewportParameters(
                  new ChunkRenderMatrices(projectionMatrix, positionMatrix), pos.x, pos.y, pos.z));
        }
      }
    }
  }
}
