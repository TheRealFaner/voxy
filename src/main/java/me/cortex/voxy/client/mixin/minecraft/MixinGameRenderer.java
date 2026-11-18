package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
    value = {GameRenderer.class},
    priority = 1100)
public class MixinGameRenderer {
  // D1 guard safety net: the host frame begins outside any LevelRenderer.renderLevel call, so a
  // leaked nesting count (an exception swallowed between a nested render's HEAD and RETURN) heals
  // here instead of suppressing Voxy for the rest of the session. IP's portal renders call
  // renderLevel directly, not render(), so they do not reach this hook.
  @Inject(method = "render", at = @At("HEAD"))
  private void voxy$resetLevelRenderGuard(
      DeltaTracker deltaTracker, boolean renderWorldIn, CallbackInfo ci) {
    VoxyRenderSystem.resetLevelRenderDepth();
  }

  @WrapMethod(method = "getDepthFar()F")
  public float getDepthFar(Operation<Float> original) {
    if (VoxyConfig.CONFIG.isRenderingEnabled()) {
      return Math.max(original.call(), VoxyConfig.CONFIG.sectionRenderDistance * 32F * 4F);
    }
    return original.call();
  }
}
