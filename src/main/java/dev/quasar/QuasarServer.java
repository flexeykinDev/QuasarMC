package dev.quasar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.quasar.config.ServerConfig;
import dev.quasar.engine.RegionManager;
import dev.quasar.engine.RegionScheduler;
import dev.quasar.entity.ItemEntity;
import dev.quasar.entity.Player;
import dev.quasar.item.HotbarKit;
import dev.quasar.item.ItemStack;
import dev.quasar.item.ItemRegistry;
import dev.quasar.nbt.Nbt;
import dev.quasar.net.Connection;
import dev.quasar.net.NettyServer;
import dev.quasar.net.Protocol;
import dev.quasar.net.ProtocolState;
import dev.quasar.net.listener.PlayListener;
import dev.quasar.util.Log;
import dev.quasar.world.World;
import dev.quasar.world.gen.ChunkGenerator;
import dev.quasar.world.gen.FlatChunkGenerator;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.gen.NoiseChunkGenerator;
import dev.quasar.world.storage.LevelDat;
import dev.quasar.world.storage.PlayerDataStorage;
import dev.quasar.world.storage.RegionStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wires the engine, the world and the network together, and owns startup and shutdown order. */
public final class QuasarServer {

    private final ServerConfig config;
    private final World world;
    private final RegionManager regionManager;
    private final RegionScheduler scheduler;
    private final NettyServer network;

    private final ConcurrentHashMap<UUID, Player> players = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService housekeeping;
    private final boolean flatWorld;
    private final long startedAtMillis = System.currentTimeMillis();

    /** World spawn, resolved once at startup rather than per join. */
    private int[] spawn = {0, 64, 0};

    /** Per-player state on disk, or {@code null} when persistence is off. */
    private final PlayerDataStorage playerData;

    public QuasarServer(ServerConfig config) {
        this.config = config;
        this.flatWorld = config.generator.equalsIgnoreCase("flat");

        ChunkGenerator generator = flatWorld
                ? new FlatChunkGenerator(config.seaLevel)
                : new NoiseChunkGenerator(config.seed, config.seaLevel, config.terrainAmplitude);

        // Both tables are needed regardless of persistence: the block table also backs item
        // placement, and the item table is what makes the creative inventory work.
        BlockStateRegistry.loadFullTableIfPresent();
        ItemRegistry.loadIfPresent();

        RegionStorage storage = null;
        PlayerDataStorage players = null;
        if (config.saveEnabled) {
            try {
                storage = new RegionStorage(Path.of(config.levelName));
                players = new PlayerDataStorage(Path.of(config.levelName));
            } catch (IOException e) {
                // Running without persistence is a big enough behaviour change to be loud about,
                // but it is still better than refusing to start.
                Log.error("Could not open world storage; running without persistence", e);
            }
        } else {
            Log.warn("world.save-enabled is false — edits and player positions will not persist");
        }
        this.playerData = players;

        this.world = new World(config.levelName, config.seed, config.minY, config.worldHeight,
                generator, config.worldGenThreads, storage);
        this.regionManager = new RegionManager(world, config.seed);
        this.world.setRegionManager(regionManager);
        this.world.setSyntheticTickLoadMicros(config.syntheticTickLoadMicros);
        this.scheduler = new RegionScheduler(regionManager, config.regionThreads);
        this.network = new NettyServer(this);
        this.housekeeping = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quasar-housekeeping");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ServerConfig config() { return config; }

    public World world() { return world; }

    public RegionManager regionManager() { return regionManager; }

    public RegionScheduler scheduler() { return scheduler; }

    public boolean isFlatWorld() { return flatWorld; }

    public boolean isRunning() { return running.get(); }

    public long uptimeMillis() { return System.currentTimeMillis() - startedAtMillis; }

    public int playerCount() { return players.size(); }

    public Collection<Player> players() { return players.values(); }

    public Player playerByName(String name) {
        for (Player player : players.values()) {
            if (player.name().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------- lifecycle

    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (config.onlineMode) {
            throw new IllegalStateException(
                    "server.online-mode=true is not supported: this core implements no encryption "
                            + "or Mojang session verification. Set it to false, and do not expose "
                            + "this server to untrusted networks.");
        }

        Log.info("Quasar starting — Minecraft %s (protocol %d)", Protocol.VERSION_NAME, Protocol.VERSION);
        Log.info("World '%s': generator=%s seed=%d height=%d..%d",
                config.levelName, config.generator, config.seed, config.minY, world.maxY());

        spawn = findDrySpawn();
        Log.info("World spawn at %d, %d, %d", spawn[0], spawn[1], spawn[2]);
        if (config.saveEnabled) {
            LevelDat.writeIfAbsent(Path.of(config.levelName), config.levelName, config.seed,
                    spawn[0], spawn[1], spawn[2]);
        }

        scheduler.start();
        network.bind(config.host, config.port);

        housekeeping.scheduleAtFixedRate(this::housekeep, 1, 1, TimeUnit.SECONDS);
        if (config.metricsIntervalSeconds > 0) {
            housekeeping.scheduleAtFixedRate(this::logMetrics,
                    config.metricsIntervalSeconds, config.metricsIntervalSeconds, TimeUnit.SECONDS);
        }
        if (config.saveEnabled && config.autosaveIntervalSeconds > 0) {
            housekeeping.scheduleAtFixedRate(() -> saveWorld(false),
                    config.autosaveIntervalSeconds, config.autosaveIntervalSeconds, TimeUnit.SECONDS);
        }
        Log.info("Ready — %d region threads, %d world-gen threads",
                config.regionThreads, config.worldGenThreads);
    }

    private void housekeep() {
        try {
            scheduler.runAtSafepoint(world::applyUnloadsAtSafepoint);
        } catch (Throwable t) {
            Log.error("Housekeeping failed", t);
        }
    }

    /**
     * Saves every edited chunk.
     *
     * <p>Serialisation runs at a safepoint, where nothing can be mid-tick over a chunk; the disk
     * writes it queues happen on the storage IO thread afterwards, so the pause is proportional to
     * how much has been edited, not to disk speed.
     *
     * @param verbose whether to report even when nothing needed saving
     */
    public void saveWorld(boolean verbose) {
        saveWorld(verbose, false);
    }

    /**
     * Drops a stack into the world.
     *
     * <p>The entity joins its region at the next safepoint, so it appears within a tick or two
     * rather than instantly. Safe to call from a region thread.
     */
    public void spawnItem(ItemStack stack, double x, double y, double z, int pickupDelay) {
        if (stack.isEmpty()) {
            return;
        }
        regionManager.requestEntityAdd(new ItemEntity(this, stack, x, y, z, pickupDelay));
    }

    /** Snapshots a player to disk. Call from the thread that owns them. */
    public void savePlayer(Player player) {
        if (playerData != null) {
            playerData.saveAsync(player.uuid(), player.toNbt());
        }
    }

    public void saveWorld(boolean verbose, boolean includeUnedited) {
        try {
            scheduler.runAtSafepoint(() -> {
                // Players are saved here too: at a safepoint nothing is mid-tick, so their
                // positions are consistent without reaching into a region thread.
                for (Player player : players.values()) {
                    savePlayer(player);
                }
                int saved = world.saveAllAtSafepoint(includeUnedited);
                if (saved > 0 || verbose) {
                    Log.info("Saved %d %schunk(s)", saved, includeUnedited ? "" : "edited ");
                }
            }).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.error("World save failed", e);
        }
    }

    /** Periodic engine summary. Skipped while idle so an empty server does not spam its log. */
    private void logMetrics() {
        if (players.isEmpty() && regionManager.regionCount() == 0) {
            return;
        }
        Log.info("%s", engineSummary());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Log.info("Shutting down…");
        for (Player player : players.values()) {
            player.disconnect("Server closed");
        }
        housekeeping.shutdownNow();
        // Before the scheduler stops: saving needs a safepoint, and safepoints need a live
        // dispatcher. world.shutdown() then closes storage, which drains the queued writes.
        saveWorld(true);
        network.shutdown();
        scheduler.shutdown();
        world.shutdown();
        if (playerData != null) {
            playerData.close();
        }
        Log.info("Goodbye.");
    }

    // ---------------------------------------------------------------------------- player entry

    /** How far out to look for dry land before giving up, and the grid step used while looking. */
    private static final int SPAWN_SEARCH_RADIUS = 2048;
    private static final int SPAWN_SEARCH_STEP = 16;

    /**
     * Picks a spawn on dry land.
     *
     * <p>Origin is a poor default: with a noise generator the terrain at 0,0 is as likely as not to
     * sit below sea level, and spawning there drops the player thirty blocks under water, where
     * every attempted block placement targets water rather than air. Searching outward for a column
     * whose surface clears sea level costs nothing — {@code surfaceY} is pure noise evaluation and
     * generates no chunks.
     */
    private int[] findDrySpawn() {
        for (int radius = 0; radius <= SPAWN_SEARCH_RADIUS; radius += SPAWN_SEARCH_STEP) {
            for (int dx = -radius; dx <= radius; dx += SPAWN_SEARCH_STEP) {
                for (int dz = -radius; dz <= radius; dz += SPAWN_SEARCH_STEP) {
                    // Only the perimeter of each ring; the interior was covered by smaller radii.
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int surface = world.generator().surfaceY(dx, dz);
                    if (surface > config.seaLevel) {
                        return new int[] {dx, surface + 1, dz};
                    }
                }
            }
        }
        // Entirely submerged within the search area: stand on the water surface rather than fail.
        Log.warn("No dry land within %d blocks of origin; spawning at sea level", SPAWN_SEARCH_RADIUS);
        return new int[] {0, config.seaLevel + 1, 0};
    }

    /** Called from the network thread once the client acknowledges the end of configuration. */
    public void beginPlay(Connection connection, String username, UUID uuid, int viewDistance) {
        double spawnX = spawn[0] + 0.5;
        double spawnY = spawn[1];
        double spawnZ = spawn[2] + 0.5;

        Player player = new Player(this, connection, username, uuid, viewDistance, spawnX, spawnY, spawnZ);
        Player previous = players.putIfAbsent(uuid, player);
        if (previous != null) {
            player.disconnect("Already connected");
            return;
        }

        // A returning player picks up where they left off; world spawn is only the fallback.
        Nbt.NbtCompound saved = playerData == null ? null : playerData.load(uuid);
        if (saved != null) {
            player.loadFromNbt(saved);
            Log.info("%s returning to %.1f, %.1f, %.1f", username, player.x(), player.y(), player.z());
        }

        connection.setState(ProtocolState.PLAY);
        connection.setListener(new PlayListener(this, connection, player));

        player.sendJoinSequence();
        regionManager.requestEntityAdd(player);

        // Runs on the player's first region tick, by which point the client is in the world.
        // Placement ignoring what you are holding is surprising enough to be worth saying out loud
        // rather than leaving people to conclude the server is broken.
        player.submit(() -> {
            player.sendStarterKit();
            player.sendSystemMessage("§dQuasar §7— region-threaded core, " + Protocol.VERSION_NAME);
            if (ItemRegistry.isLoaded()) {
                player.sendSystemMessage(String.format(
                        "§7Creative inventory is live: §f%d§7 items, §f%d§7 of them placeable. "
                                + "Blocks are placed in their default state.",
                        ItemRegistry.itemCount(), ItemRegistry.placeableCount()));
            } else {
                player.sendSystemMessage("§7No item table loaded, so placement is limited to this "
                        + "fixed hotbar: §f" + HotbarKit.describe());
            }
        });

        Log.info("%s joined at %.1f, %.1f, %.1f (%d online)",
                username, player.x(), player.y(), player.z(), players.size());
        broadcast("§e" + username + " joined the game");
    }

    /**
     * Called from the network thread when a channel closes.
     *
     * <p>This only drops the player from the lookup map. It deliberately does <em>not</em> mark the
     * entity removed or release its chunk tickets: both are region-owned, and marking it removed
     * here would make the region skip its tick, so the cleanup queued for that tick would never
     * run and the player's chunks would stay loaded forever. {@link Player#tick} sees the closed
     * connection on its next tick and cleans up on the owning thread.
     */
    public void removePlayer(Player player) {
        if (players.remove(player.uuid()) == null) {
            return;
        }
        // The entity despawns on its own once the region drops it, but the tab-list entry has to be
        // withdrawn explicitly or the name lingers for everyone still online.
        for (Player other : players.values()) {
            other.sendPlayerInfoRemove(player.uuid());
        }
        Log.info("%s left (%d online)", player.name(), players.size());
        broadcast("§e" + player.name() + " left the game");
    }

    public void handleChat(Player sender, String message) {
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        Log.info("<%s> %s", sender.name(), trimmed);
        broadcast("§7<§f" + sender.name() + "§7> §r" + trimmed);
    }

    public void broadcast(String text) {
        for (Player player : players.values()) {
            player.sendSystemMessage(text);
        }
    }

    // ------------------------------------------------------------------------------- status

    /** The server-list ping payload. */
    public String statusJson(int clientProtocol) {
        JsonObject version = new JsonObject();
        version.addProperty("name", Protocol.VERSION_NAME);
        // Echoing the client's own protocol when it matches avoids a spurious "incompatible" badge
        // in the list; a mismatch is reported honestly so the player sees why they cannot join.
        version.addProperty("protocol", clientProtocol == Protocol.VERSION ? clientProtocol : Protocol.VERSION);

        JsonArray sample = new JsonArray();
        int shown = 0;
        for (Player player : players.values()) {
            if (shown++ >= 8) {
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.name());
            entry.addProperty("id", player.uuid().toString());
            sample.add(entry);
        }

        JsonObject playersJson = new JsonObject();
        playersJson.addProperty("max", config.maxPlayers);
        playersJson.addProperty("online", players.size());
        playersJson.add("sample", sample);

        JsonObject description = new JsonObject();
        description.addProperty("text", config.motd);

        JsonObject root = new JsonObject();
        root.add("version", version);
        root.add("players", playersJson);
        root.add("description", description);
        root.addProperty("enforcesSecureChat", false);
        return root.toString();
    }

    /** One-line engine summary, used by the console and by the status command. */
    public String engineSummary() {
        double worstMspt = 0;
        double slowestTps = 20;
        int trackedEntities = 0;
        for (var region : regionManager.regions()) {
            worstMspt = Math.max(worstMspt, region.metrics().averageMspt());
            slowestTps = Math.min(slowestTps, region.metrics().tps());
            trackedEntities += region.entityCount();
        }
        return String.format(Locale.ROOT,
                "regions=%d players=%d(%d ticking) chunks=%d worstMSPT=%.2f slowestTPS=%.1f "
                        + "parallel=%d peak=%d/%d threads=%d merges=%d splits=%d",
                regionManager.regionCount(), players.size(), trackedEntities, world.loadedChunkCount(),
                worstMspt, slowestTps,
                scheduler.activeTicks(), scheduler.peakConcurrency(), scheduler.parallelism(),
                scheduler.threadsUsed().size(), regionManager.merges(), regionManager.splits());
    }
}
