package me.cortex.voxy.client.core.rendering.backend.gl41metal.jni;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import me.cortex.voxy.common.Logger;

public final class NativeBindings {
  private static final String RESOURCE_PATH = "/natives/macos/libvoxy_gl41metal.dylib";
  private static final String SHADER_RESOURCE_DIR = "/natives/macos/gl41metal/";
  private static final String DEV_PATH = "build/gl41metal-native/libvoxy_gl41metal.dylib";
  private static final String DEV_SHADER_DIR = "build/gl41metal-shaders/";
  private static final String BUILD_INFO_RESOURCE_PATH =
      "/natives/macos/gl41metal/build-info.properties";
  private static final String SHADER_CANDIDATE_PROPERTY = "voxy.gl41metal.shaderCandidate";
  private static final String PRIMARY_SHADER_NAME = "voxy_gl41metal.metallib";
  private static final String SECONDARY_SHADER_NAME = "voxy_gl41metal-current.metallib";
  private static final boolean LOADED;
  private static final String LOAD_FAILURE;
  private static final String NATIVE_SOURCE;
  private static final List<ShaderCandidate> SHADER_CANDIDATES;
  private static final String SHADER_FAILURE;
  private static final Properties BUILD_INFO;

  static {
    String failure = null;
    boolean loaded = false;
    String nativeSource = "unavailable";
    try {
      nativeSource = loadLibrary();
      loaded = true;
    } catch (Throwable e) {
      failure = message(e);
    }
    LOADED = loaded;
    LOAD_FAILURE = failure;
    NATIVE_SOURCE = nativeSource;

    List<ShaderCandidate> candidates = List.of();
    String shaderFailure = null;
    try {
      candidates = resolveShaderCandidates();
    } catch (Throwable e) {
      shaderFailure = message(e);
    }
    SHADER_CANDIDATES = List.copyOf(candidates);
    SHADER_FAILURE = shaderFailure;

    BUILD_INFO = readBuildInfo();

    if (!LOADED) {
      Logger.warn("Failed to load Voxy GL41Metal native library: " + LOAD_FAILURE);
    }
    if (!hasShaderCandidates()) {
      Logger.warn("GL41Metal shader library unavailable: " + shaderFailure());
    } else {
      Logger.info("GL41Metal shader library candidates: " + shaderCandidatesDescription());
    }
  }

  private NativeBindings() {}

  public static boolean isLoaded() {
    return LOADED;
  }

  public static String loadFailure() {
    return LOAD_FAILURE == null ? "none" : LOAD_FAILURE;
  }

  public static boolean hasShaderCandidates() {
    return hasUsableCandidate(SHADER_CANDIDATES);
  }

  public static String shaderCandidatesDescription() {
    if (SHADER_CANDIDATES.isEmpty()) {
      return "none";
    }
    List<String> descriptions = new ArrayList<>();
    for (ShaderCandidate candidate : SHADER_CANDIDATES) {
      descriptions.add(candidate.description());
    }
    return String.join(", ", descriptions);
  }

  public static String shaderFailure() {
    return SHADER_FAILURE == null ? "none" : SHADER_FAILURE;
  }

  public static String runtimeDescription() {
    return "os="
        + System.getProperty("os.name", "unknown")
        + " "
        + System.getProperty("os.version", "unknown")
        + " ("
        + System.getProperty("os.arch", "unknown")
        + "), native="
        + NATIVE_SOURCE
        + ", shaders="
        + shaderCandidatesDescription()
        + ", build-info="
        + buildInfoDescription();
  }

  private static String loadLibrary() throws IOException {
    try (InputStream stream = NativeBindings.class.getResourceAsStream(RESOURCE_PATH)) {
      if (stream != null) {
        Path extracted = Files.createTempFile("voxy_gl41metal", ".dylib");
        Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING);
        extracted.toFile().deleteOnExit();
        System.load(extracted.toAbsolutePath().toString());
        Logger.info("Loaded Voxy GL41Metal native library from bundled resource");
        return "bundled";
      }
    }

    Path devLibrary = Path.of(DEV_PATH);
    if (Files.exists(devLibrary)) {
      System.load(devLibrary.toAbsolutePath().toString());
      Logger.info("Loaded Voxy GL41Metal native library from " + devLibrary);
      return "dev:" + devLibrary;
    }

    throw new IOException("missing " + RESOURCE_PATH + " and " + DEV_PATH);
  }

  private static List<ShaderCandidate> resolveShaderCandidates() throws IOException {
    String override = System.getProperty(SHADER_CANDIDATE_PROPERTY, "").trim();
    if (!override.isEmpty()) {
      Logger.info(
          "GL41Metal shader candidate forced by -D"
              + SHADER_CANDIDATE_PROPERTY
              + "='"
              + override
              + "'");
    }
    if (override.equalsIgnoreCase("none") || override.equalsIgnoreCase("missing")) {
      throw new IOException(
          "shader library candidates disabled by -D" + SHADER_CANDIDATE_PROPERTY + "=" + override);
    }

    List<ShaderCandidate> candidates = new ArrayList<>();
    addResolvedCandidate(candidates, PRIMARY_SHADER_NAME);
    addResolvedCandidate(candidates, SECONDARY_SHADER_NAME);

    if (!override.isEmpty()) {
      String needle = override.toLowerCase(Locale.ROOT);
      List<ShaderCandidate> filtered = new ArrayList<>();
      for (ShaderCandidate candidate : candidates) {
        if (candidate.name().toLowerCase(Locale.ROOT).contains(needle)) {
          filtered.add(candidate);
        }
      }
      if (filtered.isEmpty()) {
        throw new IOException(
            "-D"
                + SHADER_CANDIDATE_PROPERTY
                + "="
                + override
                + " matched no GL41Metal shader library candidate; available: "
                + candidateNames(candidates)
                + "; expected paths: "
                + expectedCandidatePaths());
      }
      candidates = filtered;
    }

    if (!hasUsableCandidate(candidates)) {
      StringBuilder message =
          new StringBuilder("missing GL41Metal shader library; expected one of: ")
              .append(expectedCandidatePaths());
      for (ShaderCandidate candidate : candidates) {
        message.append("; ").append(candidate.name()).append(": ").append(candidate.error());
      }
      throw new IOException(message.toString());
    }
    return candidates;
  }

  private static void addResolvedCandidate(List<ShaderCandidate> candidates, String name) {
    String resourcePath = SHADER_RESOURCE_DIR + name;
    String devPath = DEV_SHADER_DIR + name;
    try (InputStream stream = NativeBindings.class.getResourceAsStream(resourcePath)) {
      if (stream != null) {
        Path extracted = Files.createTempFile("voxy_gl41metal", ".metallib");
        Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING);
        extracted.toFile().deleteOnExit();
        candidates.add(new ShaderCandidate(name, extracted.toAbsolutePath(), null));
        return;
      }
    } catch (IOException e) {
      candidates.add(
          new ShaderCandidate(name, null, "bundled resource extraction failed: " + message(e)));
      return;
    }

    Path devLibrary = Path.of(devPath);
    if (Files.exists(devLibrary)) {
      candidates.add(new ShaderCandidate(name, devLibrary.toAbsolutePath(), null));
    }
  }

  private static Properties readBuildInfo() {
    try {
      Properties properties = new Properties();
      try (InputStream stream =
          NativeBindings.class.getResourceAsStream(BUILD_INFO_RESOURCE_PATH)) {
        if (stream != null) {
          properties.load(stream);
        }
      }
      return properties;
    } catch (Throwable e) {
      Logger.warn("Failed to read " + BUILD_INFO_RESOURCE_PATH + ": " + message(e));
      return new Properties();
    }
  }

  private static String buildInfoDescription() {
    if (BUILD_INFO.isEmpty()) {
      return "unavailable";
    }
    List<String> parts = new ArrayList<>();
    appendBuildInfo(parts, "msl_std");
    appendBuildInfo(parts, "macos_min");
    appendBuildInfo(parts, "xcode_version");
    return parts.isEmpty() ? "present" : String.join(", ", parts);
  }

  private static void appendBuildInfo(List<String> parts, String key) {
    String value = BUILD_INFO.getProperty(key);
    if (value != null && !value.isBlank()) {
      parts.add(key + "=" + value);
    }
  }

  private static boolean hasUsableCandidate(List<ShaderCandidate> candidates) {
    for (ShaderCandidate candidate : candidates) {
      if (candidate.usable()) {
        return true;
      }
    }
    return false;
  }

  private static String candidateNames(List<ShaderCandidate> candidates) {
    if (candidates.isEmpty()) {
      return "none";
    }
    List<String> names = new ArrayList<>();
    for (ShaderCandidate candidate : candidates) {
      names.add(candidate.name());
    }
    return String.join(", ", names);
  }

  private static String expectedCandidatePaths() {
    return SHADER_RESOURCE_DIR
        + PRIMARY_SHADER_NAME
        + ", "
        + DEV_SHADER_DIR
        + PRIMARY_SHADER_NAME
        + ", "
        + SHADER_RESOURCE_DIR
        + SECONDARY_SHADER_NAME
        + ", "
        + DEV_SHADER_DIR
        + SECONDARY_SHADER_NAME;
  }

  private static String message(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getName() : message;
  }

  private record ShaderCandidate(String name, Path path, String error) {
    private boolean usable() {
      return this.path != null;
    }

    private String description() {
      if (this.path == null) {
        return this.name + " (error: " + this.error + ")";
      }
      return this.name + " (" + this.path + ")";
    }
  }

  public static native String getUnsupportedReason();

  public static native long createContext(int slotCount, String shaderLibraryPath);

  public static long createContext(int slotCount) {
    if (!hasShaderCandidates()) {
      throw new IllegalStateException(
          "GL41Metal shader library is unavailable: "
              + shaderFailure()
              + "; expected paths: "
              + expectedCandidatePaths());
    }
    List<String> failures = new ArrayList<>();
    for (ShaderCandidate candidate : SHADER_CANDIDATES) {
      if (!candidate.usable()) {
        failures.add(candidate.name() + ": " + candidate.error());
        continue;
      }
      try {
        long handle = createContext(slotCount, candidate.path().toString());
        if (handle != 0L) {
          Logger.info("GL41Metal shader library candidate '" + candidate.name() + "' loaded");
          return handle;
        }
        String failure = "native createContext returned 0";
        failures.add(candidate.name() + ": " + failure);
        Logger.warn(
            "GL41Metal shader library candidate '" + candidate.name() + "' failed: " + failure);
      } catch (RuntimeException e) {
        String failure = message(e);
        failures.add(candidate.name() + ": " + failure);
        Logger.warn(
            "GL41Metal shader library candidate '" + candidate.name() + "' failed: " + failure, e);
      }
    }
    throw new IllegalStateException(
        "GL41Metal failed to create a Metal context with any shader library candidate: "
            + String.join("; ", failures)
            + "; expected paths: "
            + expectedCandidatePaths());
  }

  public static native void destroyContext(long handle);

  public static native String getDeviceName(long handle);

  public static native int acquireFreeSlot(long handle);

  public static native void submitTraversal(
      long handle,
      int slot,
      long frameId,
      double cameraX,
      double cameraY,
      double cameraZ,
      long traversalMvpAddress,
      long drawMvpAddress,
      float subDivisionSize,
      float earthRadius,
      float nearExclusionRadius,
      float renderDistanceSquared,
      int viewportWidth,
      int viewportHeight);

  // countersAddress points at two uint64 values:
  // [0]=overflow range count,
  // [1]=work items whose traversal-time section snapshot no longer matches current metadata.
  public static native int buildOpaqueRanges(
      long handle,
      int slot,
      long countsAddress,
      long baseVerticesAddress,
      int capacity,
      long countersAddress);

  // countersAddress points at one uint64 value: [0]=overflow range count.
  public static native int buildTranslucentRanges(
      long handle,
      int slot,
      long countsAddress,
      long baseVerticesAddress,
      int capacity,
      long countersAddress);

  public static native int waitCurrent(long handle, int currentSlot, int timeoutMs);

  // Whether traversal emitted any translucent work for this slot.
  public static native boolean isSlotTranslucentValid(long handle, int slot);

  public static native void discardCurrentSlot(long handle, int slot);

  public static native void releaseSampledSlot(long handle, int slot);

  public static native void createTerrainResources(
      long handle,
      int maxSections,
      int maxNodes,
      int maxTraversalQueue,
      int maxTraversalRequests,
      int maxWorklistItems,
      int maxOpaqueRangeCommands);

  public static native void clearTerrainResources(long handle);

  public static native void removeSection(long handle, int sectionId);

  public static native void uploadNode(long handle, int nodeId, long nodeAddress);

  public static native void uploadSectionMetadata(long handle, int sectionId, long metadataAddress);

  public static native void addTopNode(long handle, int nodeId);

  public static native void removeTopNode(long handle, int nodeId);

  public static native long[] pollTraversalRequests(long handle);

  public static native void clearTraversalWorklist(long handle);

  public static native long[] getTerrainStats(long handle);
}
