package me.cortex.voxy.client.core.model;

/**
 * Static holder for the loader-specific {@link ModelQuads}. {@code ModelQuadsImpl} is a same-FQN
 * dual implementation provided by each loader source set (src/fabric, src/neoforge), mirroring
 * {@code PlatformAccess}.
 */
public final class ModelQuadsAccess {
  private static final ModelQuads MODEL_QUADS = new ModelQuadsImpl();

  private ModelQuadsAccess() {}

  public static ModelQuads get() {
    return MODEL_QUADS;
  }
}
