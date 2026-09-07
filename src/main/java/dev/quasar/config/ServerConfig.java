package dev.quasar.config;

import dev.quasar.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server settings, read from {@code quasar.properties} and written back with defaults on first run.
 *
 * <p>Deliberately a flat properties file rather than YAML: no parser dependency, and every value
 * here is a scalar.
 */
public final class ServerConfig {

    private static final Path FILE = Path.of("quasar.properties");

    public final String host;
    public final int port;
    public final String motd;
    public final int maxPlayers;
    public final int viewDistance;
    public final int compressionThreshold;

    public final String levelName;
    public final long seed;
    public final String generator;
    public final int minY;
    public final int worldHeight;
    public final int seaLevel;
    public final int terrainAmplitude;

    /** Region tick worker threads. 0 means "one per available core". */
    public final int regionThreads;

    /** World generation threads. 0 means "half the cores, at least one". */
    public final int worldGenThreads;

    /** Seconds between engine summary lines in the log. 0 disables them. */
    public final int metricsIntervalSeconds;

    /**
     * Artificial CPU cost burned per region tick, in microseconds. 0 (the default) means none.
     *
     * <p>A benchmarking knob, not a gameplay one. This core implements no block behaviour, so a
     * region tick is nearly free and the scheduler never has to work — which makes it impossible to
     * tell real parallelism from a fast serial loop. Dialling in a per-tick cost simulates the
     * workload a real server's block, fluid and entity ticks would impose, and lets you compare
     * {@code engine.region-threads=1} against the full core count honestly.
     */
    public final int syntheticTickLoadMicros;

    public final boolean onlineMode;
    public final String logLevel;

    /** Persist edited chunks to disk. Off means every restart regenerates from the seed. */
    public final boolean saveEnabled;

    /** Seconds between background saves of edited chunks. 0 saves only on unload and shutdown. */
    public final int autosaveIntervalSeconds;

    private ServerConfig(Properties props) {
        this.host = props.getProperty("server.host", "0.0.0.0");
        this.port = parseInt(props, "server.port", 25565);
        this.motd = props.getProperty("server.motd", "§dQuasar §7— region-threaded core");
        this.maxPlayers = parseInt(props, "server.max-players", 100);
        this.viewDistance = clamp(parseInt(props, "server.view-distance", 8), 2, 32);
        this.compressionThreshold = parseInt(props, "server.compression-threshold", 256);
        this.onlineMode = Boolean.parseBoolean(props.getProperty("server.online-mode", "false"));

        this.levelName = props.getProperty("world.name", "world");
        this.seed = parseLong(props, "world.seed", 0L);
        this.generator = props.getProperty("world.generator", "noise");
        this.minY = parseInt(props, "world.min-y", -64);
        this.worldHeight = parseInt(props, "world.height", 384);
        this.seaLevel = parseInt(props, "world.sea-level", 64);
        this.terrainAmplitude = parseInt(props, "world.terrain-amplitude", 28);

        int cores = Runtime.getRuntime().availableProcessors();
        int configuredRegionThreads = parseInt(props, "engine.region-threads", 0);
        this.regionThreads = configuredRegionThreads > 0 ? configuredRegionThreads : Math.max(cores, 2);
        int configuredGenThreads = parseInt(props, "engine.worldgen-threads", 0);
        this.worldGenThreads = configuredGenThreads > 0 ? configuredGenThreads : Math.max(cores / 2, 1);
        this.metricsIntervalSeconds = parseInt(props, "engine.metrics-interval-seconds", 30);
        this.syntheticTickLoadMicros = Math.max(0, parseInt(props, "engine.synthetic-tick-load-micros", 0));

        this.logLevel = props.getProperty("log.level", "INFO");
        this.saveEnabled = Boolean.parseBoolean(props.getProperty("world.save-enabled", "true"));
        this.autosaveIntervalSeconds = parseInt(props, "world.autosave-interval-seconds", 120);
    }

    public static ServerConfig loadOrCreate() {
        Properties props = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                props.load(in);
            } catch (IOException e) {
                Log.warn("Could not read %s (%s); using defaults", FILE, e.getMessage());
            }
        }
        ServerConfig config = new ServerConfig(props);
        config.writeBack();
        return config;
    }

    /** Rewrites the file so every key, including ones the user never set, is visible and editable. */
    private void writeBack() {
        Properties out = new Properties();
        out.setProperty("server.host", host);
        out.setProperty("server.port", String.valueOf(port));
        out.setProperty("server.motd", motd);
        out.setProperty("server.max-players", String.valueOf(maxPlayers));
        out.setProperty("server.view-distance", String.valueOf(viewDistance));
        out.setProperty("server.compression-threshold", String.valueOf(compressionThreshold));
        out.setProperty("server.online-mode", String.valueOf(onlineMode));
        out.setProperty("world.name", levelName);
        out.setProperty("world.seed", String.valueOf(seed));
        out.setProperty("world.generator", generator);
        out.setProperty("world.min-y", String.valueOf(minY));
        out.setProperty("world.height", String.valueOf(worldHeight));
        out.setProperty("world.sea-level", String.valueOf(seaLevel));
        out.setProperty("world.terrain-amplitude", String.valueOf(terrainAmplitude));
        out.setProperty("engine.region-threads", String.valueOf(regionThreads));
        out.setProperty("engine.worldgen-threads", String.valueOf(worldGenThreads));
        out.setProperty("engine.metrics-interval-seconds", String.valueOf(metricsIntervalSeconds));
        out.setProperty("engine.synthetic-tick-load-micros", String.valueOf(syntheticTickLoadMicros));
        out.setProperty("log.level", logLevel);
        out.setProperty("world.save-enabled", String.valueOf(saveEnabled));
        out.setProperty("world.autosave-interval-seconds", String.valueOf(autosaveIntervalSeconds));

        try (OutputStream stream = Files.newOutputStream(FILE)) {
            out.store(stream, "Quasar server configuration");
        } catch (IOException e) {
            Log.warn("Could not write %s: %s", FILE, e.getMessage());
        }
    }

    private static int parseInt(Properties props, String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            Log.warn("Bad integer for %s, using %d", key, fallback);
            return fallback;
        }
    }

    private static long parseLong(Properties props, String key, long fallback) {
        try {
            return Long.parseLong(props.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            Log.warn("Bad long for %s, using %d", key, fallback);
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
