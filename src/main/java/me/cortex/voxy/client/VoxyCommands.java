package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.model.LodDump;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

public class VoxyCommands {

  public static LiteralArgumentBuilder<CommandSourceStack> register() {
    var imports =
        LiteralArgumentBuilder.<CommandSourceStack>literal("import")
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("world")
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "world_name", StringArgumentType.string())
                            .suggests(VoxyCommands::importWorldSuggester)
                            .executes(VoxyCommands::importWorld)))
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("bobby")
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "world_name", StringArgumentType.string())
                            .suggests(VoxyCommands::importBobbySuggester)
                            .executes(VoxyCommands::importBobby)))
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("raw")
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "path", StringArgumentType.string())
                            .executes(VoxyCommands::importRaw)))
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("zip")
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "zipPath", StringArgumentType.string())
                            .executes(VoxyCommands::importZip)
                            .then(
                                RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                        "innerPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importZip))))
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("current")
                    .executes(VoxyCommands::importCurrentWorldIn))
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                    .executes(VoxyCommands::cancelImport));

    if (DHImporter.HasRequiredLibraries) {
      imports =
          imports.then(
              LiteralArgumentBuilder.<CommandSourceStack>literal("distant_horizons")
                  .then(
                      RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                              "sqlDbPath", StringArgumentType.string())
                          .executes(VoxyCommands::importDistantHorizons)));
    }

    var debug =
        LiteralArgumentBuilder.<CommandSourceStack>literal("debug")
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("verifyTLNChildMask")
                    .executes(ctx -> verifyTLNs(ctx, false))
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument(
                                "attemptRepair", BoolArgumentType.bool())
                            .executes(
                                ctx ->
                                    verifyTLNs(
                                        ctx, BoolArgumentType.getBool(ctx, "attemptRepair")))));

    var dump =
        LiteralArgumentBuilder.<CommandSourceStack>literal("dump")
            .then(
                RequiredArgumentBuilder.<CommandSourceStack, Integer>argument(
                        "x", IntegerArgumentType.integer())
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, Integer>argument(
                                "y", IntegerArgumentType.integer())
                            .then(
                                RequiredArgumentBuilder.<CommandSourceStack, Integer>argument(
                                        "z", IntegerArgumentType.integer())
                                    .executes(VoxyCommands::dump)
                                    .then(
                                        RequiredArgumentBuilder
                                            .<CommandSourceStack, Integer>argument(
                                                "radius",
                                                IntegerArgumentType.integer(0, LodDump.MAX_RADIUS))
                                            .executes(VoxyCommands::dump)
                                            .then(
                                                RequiredArgumentBuilder
                                                    .<CommandSourceStack, Integer>argument(
                                                        "level",
                                                        IntegerArgumentType.integer(
                                                            0, WorldEngine.MAX_LOD_LAYER))
                                                    .executes(VoxyCommands::dump))))));

    var vanillaTerrain =
        LiteralArgumentBuilder.<CommandSourceStack>literal("vanillaTerrain")
            .executes(VoxyCommands::vanillaTerrainStatus)
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("on")
                    .executes(ctx -> setVanillaTerrain(ctx, true)))
            .then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("off")
                    .executes(ctx -> setVanillaTerrain(ctx, false)));

    return LiteralArgumentBuilder.<CommandSourceStack>literal(
            "voxy") // .requires((ctx)-> VoxyCommon.getInstance() != null)
        .then(
            LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                .executes(VoxyCommands::reloadInstance))
        .then(dump)
        .then(vanillaTerrain)
        .then(imports)
        .then(debug);
  }

  /** Chat budget for dump lines; the complete output always goes to latest.log. */
  private static final int DUMP_CHAT_LINE_LIMIT = 200;

  private static int dump(CommandContext<CommandSourceStack> ctx) {
    int x = IntegerArgumentType.getInteger(ctx, "x");
    int y = IntegerArgumentType.getInteger(ctx, "y");
    int z = IntegerArgumentType.getInteger(ctx, "z");
    int radius = optionalInt(ctx, "radius", 0);
    int level = optionalInt(ctx, "level", 0);
    if (radius < 0 || radius > LodDump.MAX_RADIUS) {
      sendErrorToPlayer(Component.literal("voxy dump: radius must be 0.." + LodDump.MAX_RADIUS));
      return 0;
    }
    if (level < 0 || level > WorldEngine.MAX_LOD_LAYER) {
      sendErrorToPlayer(
          Component.literal("voxy dump: level must be 0.." + WorldEngine.MAX_LOD_LAYER));
      return 0;
    }
    var renderSystem = IVoxyRenderSystemHolder.getNullable();
    if (renderSystem == null) {
      sendErrorToPlayer(
          Component.literal("voxy dump: no active Voxy render system (Voxy disabled?)"));
      return 1;
    }
    var engine = renderSystem.getEngine();
    if (engine == null || !engine.isLive()) {
      sendErrorToPlayer(Component.literal("voxy dump: world engine is not live"));
      return 1;
    }
    var factory = renderSystem.getModelFactory();
    if (factory == null) {
      sendErrorToPlayer(
          Component.literal("voxy dump: active backend does not expose a ModelFactory"));
      return 1;
    }

    if (radius > 1) {
      sendErrorToPlayer(
          Component.literal(
              "[VoxyDump] radius>1 can synchronously load stored sections and hitch; prefer"
                  + " radius<=1 near the player"));
    }

    int[] chatLines = {0};
    int lines =
        LodDump.run(
            engine,
            factory,
            x,
            y,
            z,
            radius,
            level,
            true,
            line -> {
              Logger.info(line);
              var player = Minecraft.getInstance().player;
              if (player == null) {
                return;
              }
              if (chatLines[0] < DUMP_CHAT_LINE_LIMIT) {
                player.displayClientMessage(Component.literal(line), false);
                chatLines[0]++;
              } else if (chatLines[0] == DUMP_CHAT_LINE_LIMIT) {
                chatLines[0]++;
                player.displayClientMessage(
                    Component.literal(
                        "[VoxyDump] chat display truncated at "
                            + DUMP_CHAT_LINE_LIMIT
                            + " lines; the full dump is in latest.log"),
                    false);
              }
            });
    Logger.info("voxy dump complete: lines=" + lines);
    return 0;
  }

  private static int optionalInt(
      CommandContext<CommandSourceStack> ctx, String name, int fallback) {
    try {
      return IntegerArgumentType.getInteger(ctx, name);
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  private static int setVanillaTerrain(CommandContext<CommandSourceStack> ctx, boolean enabled) {
    VoxyClient.setVanillaTerrain(enabled);
    Logger.info(
        "voxy vanillaTerrain set to "
            + enabled
            + " (disableSodiumChunkRender="
            + VoxyClient.disableSodiumChunkRender()
            + ") at runtime");
    sendErrorToPlayer(
        Component.literal(
            "Voxy vanillaTerrain="
                + VoxyClient.isVanillaTerrain()
                + " (disableSodiumChunkRender="
                + VoxyClient.disableSodiumChunkRender()
                + ")"));
    return 0;
  }

  private static int vanillaTerrainStatus(CommandContext<CommandSourceStack> ctx) {
    sendErrorToPlayer(
        Component.literal(
            "Voxy vanillaTerrain="
                + VoxyClient.isVanillaTerrain()
                + " (disableSodiumChunkRender="
                + VoxyClient.disableSodiumChunkRender()
                + "); toggle with /voxy vanillaTerrain on|off"));
    return 0;
  }

  private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
    var instance = (VoxyClientInstance) VoxyCommon.getInstance();
    if (instance == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }
    var wr = Minecraft.getInstance().levelRenderer;
    if (wr != null) {
      ((IVoxyRenderSystemHolder) wr).voxy$shutdownRenderer();
    }

    VoxyCommon.shutdownInstance();
    System.gc();
    VoxyCommon.createInstance();

    var r = Minecraft.getInstance().levelRenderer;
    if (r != null) r.allChanged();
    return 0;
  }

  private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
    var instance = VoxyCommon.getInstance();
    if (instance == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }
    if (Minecraft.getInstance().level == null) {
      throw new IllegalStateException("How you even do this");
    }
    DebugUtils.verifyAllTopLevelNodes(
        WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
    return 0;
  }

  private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
    var instance = (VoxyClientInstance) VoxyCommon.getInstance();
    if (instance == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }
    var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
    if (!dbFile.exists()) {
      return 1;
    }
    if (dbFile.isDirectory()) {
      dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
      if (!dbFile.exists()) {
        return 1;
      }
    }

    File dbFile_ = dbFile;
    var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
    if (engine == null) return 1;
    return instance
            .getImportManager()
            .makeAndRunIfNone(
                engine,
                () ->
                    new DHImporter(
                        dbFile_,
                        engine,
                        Minecraft.getInstance().level,
                        instance.getServiceManager(),
                        instance.savingServiceRateLimiter))
        ? 0
        : 1;
  }

  private static boolean fileBasedImporter(File directory) {
    var instance = (VoxyClientInstance) VoxyCommon.getInstance();
    if (instance == null) {
      return false;
    }

    var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
    if (engine == null) return false;
    return instance
        .getImportManager()
        .makeAndRunIfNone(
            engine,
            () -> {
              var importer =
                  new WorldImporter(
                      engine,
                      Minecraft.getInstance().level,
                      instance.getServiceManager(),
                      instance.savingServiceRateLimiter);
              importer.importRegionDirectoryAsync(directory);
              return importer;
            });
  }

  private static int importRaw(CommandContext<CommandSourceStack> ctx) {
    if (VoxyCommon.getInstance() == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }

    return fileBasedImporter(new File(ctx.getArgument("path", String.class))) ? 0 : 1;
  }

  private static int importBobby(CommandContext<CommandSourceStack> ctx) {
    if (VoxyCommon.getInstance() == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }

    var file =
        new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
    return fileBasedImporter(file) ? 0 : 1;
  }

  private static CompletableFuture<Suggestions> importWorldSuggester(
      CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
    return fileDirectorySuggester(
        Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
  }

  private static CompletableFuture<Suggestions> importBobbySuggester(
      CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
    return fileDirectorySuggester(
        Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
  }

  private static CompletableFuture<Suggestions> fileDirectorySuggester(
      Path dir, SuggestionsBuilder sb) {
    var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
    if (str.startsWith("\"")) {
      str = str.substring(1);
    }
    if (str.endsWith("\"")) {
      str = str.substring(0, str.length() - 1);
    }
    var remaining = str;
    if (str.contains("/")) {
      int idx = str.lastIndexOf('/');
      remaining = str.substring(idx + 1);
      try {
        dir = dir.resolve(str.substring(0, idx));
      } catch (Exception e) {
        return Suggestions.empty();
      }
      str = str.substring(0, idx + 1);
    } else {
      str = "";
    }

    try {
      var worlds = Files.list(dir).toList();
      for (var world : worlds) {
        if (!world.toFile().isDirectory()) {
          continue;
        }
        var wn = world.getFileName().toString();
        if (wn.equals(remaining)) {
          continue;
        }
        if (SharedSuggestionProvider.matchesSubStr(remaining, wn)
            || SharedSuggestionProvider.matchesSubStr(remaining, '"' + wn)) {
          wn = str + wn + "/";
          sb.suggest(StringArgumentType.escapeIfRequired(wn));
        }
      }
    } catch (IOException e) {
    }

    return sb.buildFuture();
  }

  private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
    if (VoxyCommon.getInstance() == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }

    var localServer = Minecraft.getInstance().getSingleplayerServer();
    if (localServer == null) {
      sendErrorToPlayer(Component.translatable("You must be in single player to use this command"));
      return 1;
    }
    var regionPath =
        DimensionType.getStorageFolder(
                Minecraft.getInstance().level.dimension(),
                localServer.getWorldPath(LevelResource.ROOT))
            .resolve("region");
    if ((!regionPath.toFile().exists()) || !regionPath.toFile().isDirectory()) {
      sendErrorToPlayer(Component.translatable("Cannot find region folder for current dimension"));
      return 1;
    }
    return fileBasedImporter(regionPath.toFile()) ? 0 : 1;
  }

  private static int importWorld(CommandContext<CommandSourceStack> ctx) {
    if (VoxyCommon.getInstance() == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }

    var name = ctx.getArgument("world_name", String.class);
    var file = new File("saves").toPath().resolve(name);
    name = name.toLowerCase(Locale.ROOT);
    if (name.endsWith("/")) {
      name = name.substring(0, name.length() - 1);
    }
    if (file.resolve("level.dat").toFile().exists()) {
      var dimFile =
          DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
              .resolve("region")
              .toFile();
      if (!dimFile.isDirectory()) return 1;
      return fileBasedImporter(dimFile) ? 0 : 1;
      // We are in a world directory, so import the current dimension we are in
      /*
      for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
          var id = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(dim));
          var dimPath = DimensionType.getStorageFolder(id, file);
          dimPath = dimPath.resolve("region");
          var dimFile = dimPath.toFile();
          if (dimFile.isDirectory()) {//exists and is a directory
              if (!fileBasedImporter(dimFile)) {
                  Logger.error("Failed to import dimension: " + id);
              }
          }
      }*/
    } else {
      if (!(name.endsWith("region"))) {
        file = file.resolve("region");
      }
      return fileBasedImporter(file.toFile()) ? 0 : 1;
    }
  }

  private static int importZip(CommandContext<CommandSourceStack> ctx) {
    var zip = new File(ctx.getArgument("zipPath", String.class));
    var innerDir = "region/";
    try {
      innerDir = ctx.getArgument("innerPath", String.class);
    } catch (Exception e) {
    }

    var instance = (VoxyClientInstance) VoxyCommon.getInstance();
    if (instance == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }
    String finalInnerDir = innerDir;

    var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
    if (engine != null) {
      return instance
              .getImportManager()
              .makeAndRunIfNone(
                  engine,
                  () -> {
                    var importer =
                        new WorldImporter(
                            engine,
                            Minecraft.getInstance().level,
                            instance.getServiceManager(),
                            instance.savingServiceRateLimiter);
                    importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                    return importer;
                  })
          ? 0
          : 1;
    }
    return 1;
  }

  private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
    var instance = (VoxyClientInstance) VoxyCommon.getInstance();
    if (instance == null) {
      sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
      return 1;
    }
    var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
    if (world != null) {
      return instance.getImportManager().cancelImport(world) ? 0 : 1;
    }
    return 1;
  }

  // The command tree is registered against loader-specific source types via an erased cast,
  // so errors are reported directly to the client player instead of through the source.
  private static void sendErrorToPlayer(Component comp) {
    var player = Minecraft.getInstance().player;
    if (player != null) player.displayClientMessage(comp, false);
  }
}
