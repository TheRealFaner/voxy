package me.cortex.voxy.client.core.rendering.backend.gl41metal;

import me.cortex.voxy.client.core.rendering.backend.gl41metal.jni.NativeBindings;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.jni.NativeContext;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.Platform;

public final class Gl41MetalSupport {
  private static volatile String cachedProbeReason;
  private static volatile boolean probeAttempted;
  private static volatile boolean earlyFailureLogged;

  private Gl41MetalSupport() {}

  public static String getUnsupportedReason() {
    if (Platform.get() != Platform.MACOSX) {
      return "macOS is required";
    }
    if (!NativeBindings.isLoaded()) {
      String reason =
          "native library unavailable: "
              + NativeBindings.loadFailure()
              + "; "
              + NativeBindings.runtimeDescription();
      logEarlyFailure(reason);
      return reason;
    }
    if (!NativeBindings.hasShaderCandidates()) {
      String reason =
          "shader library unavailable: "
              + NativeBindings.shaderFailure()
              + "; "
              + NativeBindings.runtimeDescription();
      logEarlyFailure(reason);
      return reason;
    }
    String nativeReason = NativeBindings.getUnsupportedReason();
    if (nativeReason != null && !nativeReason.isBlank()) {
      return nativeReason;
    }
    probeRuntimeOnce();
    String probeReason = cachedProbeReason;
    if (probeReason != null) {
      return probeReason;
    }
    return null;
  }

  private static synchronized void probeRuntimeOnce() {
    if (probeAttempted) {
      return;
    }
    probeAttempted = true;
    try (NativeContext probe = new NativeContext(2)) {
      Logger.info(
          "Voxy macOS runtime: "
              + NativeBindings.runtimeDescription()
              + ", device="
              + probe.deviceName());
    } catch (RuntimeException e) {
      String reason =
          "Metal context initialization failed on " + osDescription() + ": " + message(e);
      cachedProbeReason = reason;
      Logger.warn(reason, e);
    }
  }

  private static String osDescription() {
    return System.getProperty("os.name", "unknown")
        + " "
        + System.getProperty("os.version", "unknown")
        + " ("
        + System.getProperty("os.arch", "unknown")
        + ")";
  }

  private static void logEarlyFailure(String reason) {
    if (!earlyFailureLogged) {
      earlyFailureLogged = true;
      Logger.warn(reason);
    }
  }

  private static String message(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getName() : message;
  }
}
