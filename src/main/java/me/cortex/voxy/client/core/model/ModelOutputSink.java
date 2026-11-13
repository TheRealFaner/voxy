package me.cortex.voxy.client.core.model;

public interface ModelOutputSink extends AutoCloseable {
  void uploadModel(BakedModelPayload payload);

  default void uploadBiomeData(BiomeModelPayload payload) {}

  /**
   * True while uploaded payloads are still queued inside the sink (async backends such as
   * gl41metal). Synchronous sinks return false.
   */
  default boolean hasPendingUploads() {
    return false;
  }

  @Override
  default void close() {}
}
