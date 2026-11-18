package me.cortex.voxy.client.mixin.minecraft;

import java.util.Objects;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.DTRootGeometryCache;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IVoxyRenderSystemHolder {
  @Shadow private @Nullable ClientLevel level;
  @Unique private @Nullable WorldIdentifier identifier;
  @Unique private VoxyRenderSystem renderer;

  @Override
  public VoxyRenderSystem voxy$getRenderSystem() {
    return this.renderer;
  }

  // D1 nested-render guard. IP renders portals by calling LevelRenderer.renderLevel again with
  // Minecraft.levelRenderer swapped to the destination renderer, so every Voxy stage mixin fires a
  // second time inside that call. Count the nesting so VoxyRenderSystem can refuse all main-style
  // stages of a nested render that did not open a portal scope (bridge absent, failed or skipped).
  // order = 50: this must run before the Iris capture mixin (order 100) on the same HEAD, otherwise
  // a same-dimension nested render could overwrite the main view's captured parameters (F4).
  @Inject(method = "renderLevel", at = @At("HEAD"), order = 50)
  private void voxy$enterLevelRender(
      DeltaTracker tickCounter,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f positionMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    VoxyRenderSystem.enterLevelRender();
  }

  @Inject(method = "renderLevel", at = @At("RETURN"), order = 50)
  private void voxy$exitLevelRender(
      DeltaTracker tickCounter,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f positionMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    VoxyRenderSystem.exitLevelRender();
  }

  @Inject(
      method = "allChanged()V",
      at = @At("RETURN"),
      order = 900) // We want to inject before sodium
  private void voxy$reloadVoxyRenderer(CallbackInfo ci) {
    this.voxy$shutdownRenderer();
    if (this.level != null) {
      this.voxy$createRenderer();
    }
  }

  @Inject(method = "setLevel", at = @At("HEAD"))
  private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
    if (this.level != world) {
      this.voxy$shutdownRenderer();
    }
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void voxy$injectClose(CallbackInfo ci) {
    this.voxy$shutdownRenderer();
  }

  @Override
  public void voxy$shutdownRenderer() {
    if (this.renderer != null) {
      this.renderer.shutdown();
      this.renderer = null;
    }
    // Clear only after the mesher workers are stopped, otherwise a worker can publish a stale
    // section after the clear and the overlay would draw roots that no longer exist.
    DTRootGeometryCache.clear();
  }

  @Override
  public void voxy$setWorld(Level level) {
    WorldIdentifier identifier = level == null ? null : WorldIdentifier.of(level);
    if (Objects.equals(this.identifier, identifier)) return;
    this.voxy$shutdownRenderer();
    this.identifier = identifier;
  }

  @Override
  public void voxy$createRenderer() {
    if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
    if (!VoxyConfig.CONFIG.enabled) {
      Logger.info("Not creating renderer due to disabled");
      return;
    }
    if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
      Logger.info("Not creating renderer due to disabled rendering");
      return;
    }
    if (this.level == null) {
      Logger.error("Not creating renderer due to null world");
      return;
    }
    var instance = (VoxyClientInstance) VoxyCommon.getInstance();
    if (instance == null) {
      Logger.error("Not creating renderer due to null instance");
      return;
    }
    WorldEngine world = WorldIdentifier.ofEngine(this.level);
    if (world == null) {
      Logger.error("Null world selected");
      return;
    }
    try {
      // D1: remember which dimension this system renders (same level the WorldEngine came from),
      // so VoxyRenderSystem can tell the home system from IP's synthetic dimension renderers.
      this.renderer =
          new VoxyRenderSystem(world, instance.getServiceManager(), this.level.dimension());
    } catch (RuntimeException e) {
      if (IrisUtil.irisShaderPackEnabled()) {
        IrisUtil.disableIrisShaders();
      } else {
        throw e;
      }
    }
    instance.updateDedicatedThreads();
  }
}
