package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.world.WorldEngine;

public interface DataImporter {
  interface ICompletionCallback {
    void onCompletion(int chunks);
  }

  interface IUpdateCallback {
    void onUpdate(int finished, int outOf);
  }

  void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback);

  WorldEngine getEngine();

  void shutdown();

  boolean isRunning();
}
