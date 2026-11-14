package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfigScreenFactory;
import me.cortex.voxy.client.core.model.DTRootGeometryCache;
import me.cortex.voxy.client.core.model.DTRootOverlayRenderer;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "voxy", dist = Dist.CLIENT)
public class NeoForgeVoxyClient extends VoxyClient {
  private static final java.util.concurrent.atomic.AtomicBoolean OVERLAY_EVENT_LOGGED =
      new java.util.concurrent.atomic.AtomicBoolean();

  public NeoForgeVoxyClient(IEventBus modBus, ModContainer container) {
    // The loader is always marked active; DTRootGeometryCache.ROOTS_ENABLED
    // (voxy.dtRootsEnabled) is the master switch that makes isActive() false and turns the
    // overlay hook below into a no-op.
    DTRootGeometryCache.setActive(true);
    NeoForge.EVENT_BUS.addListener(
        (RegisterClientCommandsEvent evt) -> {
          if (VoxyCommon.isAvailable()) {
            evt.getDispatcher().register(VoxyCommands.register());
          }
        });

    NeoForge.EVENT_BUS.addListener(
        (RenderLevelStageEvent event) -> {
          if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (OVERLAY_EVENT_LOGGED.compareAndSet(false, true)) {
              me.cortex.voxy.common.Logger.info(
                  "DT root overlay stage reached (AFTER_TRANSLUCENT_BLOCKS)");
            }
            // No-op when the DT roots master switch is off: the renderer early-returns before
            // any work (the one-shot DISABLED message is logged on DTRootGeometryCache load).
            DTRootOverlayRenderer.render(event.getPoseStack(), event.getCamera());
          }
        });

    container.registerExtensionPoint(
        IConfigScreenFactory.class,
        (mc, parent) -> {
          var screen = VoxyConfigScreenFactory.create(parent);
          return screen != null ? screen : parent;
        });
  }
}
