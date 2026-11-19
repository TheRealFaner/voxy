package me.cortex.voxy.client.core;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public interface IVoxyRenderSystemHolder {
  VoxyRenderSystem voxy$getRenderSystem();

  void voxy$shutdownRenderer();

  void voxy$createRenderer();

  void voxy$setWorld(Level level);

  // The ClientLevel this LevelRenderer renders. IP swaps Minecraft.levelRenderer (and with it the
  // rendered level) to the destination dimension for nested portal renders, so this identifies the
  // world that is actually being drawn right now.
  @Nullable
  Level voxy$getLevel();

  static VoxyRenderSystem getNullable() {
    var lr = getNullableHolder();
    if (lr == null) return null;
    return lr.voxy$getRenderSystem();
  }

  static IVoxyRenderSystemHolder getNullableHolder() {
    return (IVoxyRenderSystemHolder) Minecraft.getInstance().levelRenderer;
  }
}
