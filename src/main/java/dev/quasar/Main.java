package dev.quasar;

import dev.quasar.config.ServerConfig;
import java.nio.file.Path;
import dev.quasar.item.RecipeExtractor;
import dev.quasar.engine.Region;
import dev.quasar.entity.Player;
import dev.quasar.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Entry point and console. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        // Handled before anything else starts: this is a data-extraction tool, not a server run.
        List<String> arguments = List.of(args);
        int extract = arguments.indexOf("--extract-recipes");
        if (extract >= 0) {
            if (extract + 1 >= arguments.size()) {
                Log.error("--extract-recipes needs the path to a Minecraft client or server jar");
                System.exit(2);
                return;
            }
            RecipeExtractor.run(arguments.get(extract + 1), Path.of("recipes.json"));
            return;
        }

        ServerConfig config = ServerConfig.loadOrCreate();
        try {
            Log.setLevel(Log.Level.valueOf(config.logLevel.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            Log.warn("Unknown log level '%s', staying at INFO", config.logLevel);
        }
        // --debug beats the config file, so diagnosing a failed join needs no file editing.
        if (List.of(args).contains("--debug")) {
            Log.setLevel(Log.Level.DEBUG);
            Log.info("Debug logging enabled (--debug)");
        }

        printBanner(config);

        QuasarServer server = new QuasarServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "quasar-shutdown"));

        try {
            server.start();
        } catch (Exception e) {
            Log.error("Startup failed: " + e.getMessage(), e);
            server.stop();
            System.exit(1);
            return;
        }

        runConsole(server);
        server.stop();
    }

    private static void printBanner(ServerConfig config) {
        Log.info("  ___                          ");
        Log.info(" / _ \\ _   _  __ _ ___  __ _ _ __ ");
        Log.info("| | | | | | |/ _` / __|/ _` | '__|");
        Log.info("| |_| | |_| | (_| \\__ \\ (_| | |   ");
        Log.info(" \\__\\_\\\\__,_|\\__,_|___/\\__,_|_|   ");
        Log.info("region-threaded Minecraft core · %d cores available",
                Runtime.getRuntime().availableProcessors());
        Log.info("JVM %s (%s)", System.getProperty("java.version"), System.getProperty("java.vm.name"));
        Log.info("max heap: %d MB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        Log.info("");
        if (config.onlineMode) {
            Log.warn("online-mode is set but unsupported; startup will fail");
        }
    }

    private static void runConsole(QuasarServer server) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        Log.info("Type 'help' for commands.");
        try {
            String line;
            while (server.isRunning() && (line = reader.readLine()) != null) {
                // Strip a byte-order mark: piping commands in from a tool that writes UTF-8 with a
                // BOM otherwise turns the first command into an unrecognised one, with nothing
                // visible in the log to explain why.
                String command = line.replace("﻿", "").trim();
                if (command.isEmpty()) {
                    continue;
                }
                if (!dispatch(server, command)) {
                    return; // 'stop'
                }
            }
        } catch (IOException e) {
            Log.debug("Console closed: %s", e.getMessage());
        }
        // stdin closed (service, nohup, piped input): keep serving rather than exiting.
        if (server.isRunning()) {
            Log.info("Console input closed; running headless. Send SIGTERM to stop.");
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return false when the server should shut down */
    private static boolean dispatch(QuasarServer server, String command) {
        String[] parts = command.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";

        switch (name) {
            case "help" -> {
                Log.info("help              this message");
                Log.info("status            one-line engine summary");
                Log.info("regions           per-region tick timings");
                Log.info("players           connected players and their region");
                Log.info("world             chunk, world-gen and persistence counters");
                Log.info("save              write every edited chunk to disk now");
                Log.info("saveall           write every loaded chunk, edited or not (world export)");
                Log.info("light <x> <y> <z> block and sky light at a position");
            Log.info("mem               heap usage, and a GC hint");
                Log.info("say <message>     broadcast a chat message");
                Log.info("stop              shut down");
            }
            case "status" -> Log.info("%s", server.engineSummary());
            case "regions" -> printRegions(server);
            case "players" -> printPlayers(server);
            case "world" -> {
                Log.info("loaded=%d generated=%d generating=%d",
                        server.world().loadedChunkCount(),
                        server.world().chunksGenerated(),
                        server.world().generationsInFlight());
                var storage = server.world().storage();
                if (storage == null) {
                    Log.info("persistence: disabled — edits are lost on unload");
                } else {
                    Log.info("persistence: %d chunk(s) saved, %d loaded, %d write(s) pending",
                            storage.chunksSaved(), storage.chunksLoaded(), storage.pendingWrites());
                }
            }
            case "save" -> server.saveWorld(true);
            case "saveall" -> server.saveWorld(true, true);
            case "light" -> {
                // Answers "is the room dark because the light is wrong, or because it never
                // reached the client?" -- a question no amount of reading the code settles.
                // The dispatcher splits into name and remainder only, so the coordinates arrive
                // as one string and are split again here.
                String[] coordinates = parts.length > 1 ? parts[1].trim().split("\s+") : new String[0];
                if (coordinates.length < 3) {
                    Log.info("usage: light <x> <y> <z>");
                    break;
                }
                try {
                    int x = Integer.parseInt(coordinates[0]);
                    int y = Integer.parseInt(coordinates[1]);
                    int z = Integer.parseInt(coordinates[2]);
                    var chunk = server.world().chunkAt(x >> 4, z >> 4);
                    if (chunk == null) {
                        Log.info("chunk %d,%d is not loaded", x >> 4, z >> 4);
                        break;
                    }
                    Log.info("%d,%d,%d block=%d sky=%d state=%d (chunk %d,%d)", x, y, z,
                            chunk.light().block(x & 15, y, z & 15),
                            chunk.light().sky(x & 15, y, z & 15),
                            chunk.getBlock(x & 15, y, z & 15), x >> 4, z >> 4);
                } catch (NumberFormatException e) {
                    Log.info("usage: light <x> <y> <z>");
                }
            }
            case "mem" -> {
                Runtime runtime = Runtime.getRuntime();
                long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                Log.info("heap: %d MB used / %d MB committed / %d MB max",
                        used, runtime.totalMemory() / (1024 * 1024), runtime.maxMemory() / (1024 * 1024));
            }
            case "say" -> {
                if (rest.isEmpty()) {
                    Log.info("usage: say <message>");
                } else {
                    server.broadcast("§d[Server] §r" + rest);
                    Log.info("[Server] %s", rest);
                }
            }
            case "stop" -> {
                return false;
            }
            default -> Log.info("Unknown command '%s'. Try 'help'.", name);
        }
        return true;
    }

    private static void printRegions(QuasarServer server) {
        List<Region> regions = server.regionManager().regions().stream()
                .sorted(Comparator.comparingDouble((Region r) -> r.metrics().averageMspt()).reversed())
                .toList();
        if (regions.isEmpty()) {
            Log.info("No regions — nothing is loaded yet.");
            return;
        }
        Log.info("%-8s %8s %8s %8s %8s %7s %8s %8s",
                "region", "chunks", "entities", "avgMSPT", "p95MSPT", "tps", "ticks", "dropped");
        for (Region region : regions) {
            Log.info("#%-7d %8d %8d %8.2f %8.2f %7.1f %8d %8d",
                    region.id(), region.chunkCount(), region.entityCount(),
                    region.metrics().averageMspt(), region.metrics().p95Mspt(),
                    region.metrics().tps(), region.tickCount(), region.metrics().droppedTicks());
        }
        Log.info("%d region(s) across %d worker threads · %d merges, %d splits",
                regions.size(), server.scheduler().parallelism(),
                server.regionManager().merges(), server.regionManager().splits());
        Log.info("ticking now: %d · peak simultaneous: %d · worker threads used: %s",
                server.scheduler().activeTicks(), server.scheduler().peakConcurrency(),
                server.scheduler().threadsUsed());
    }

    private static void printPlayers(QuasarServer server) {
        if (server.playerCount() == 0) {
            Log.info("No players connected.");
            return;
        }
        Log.info("%-18s %-26s %8s", "name", "position", "region");
        for (Player player : server.players()) {
            Region region = player.region();
            Log.info("%-18s %-26s %8s",
                    player.name(),
                    String.format(Locale.ROOT, "%.1f, %.1f, %.1f", player.x(), player.y(), player.z()),
                    region == null ? "-" : "#" + region.id());
        }
    }
}
