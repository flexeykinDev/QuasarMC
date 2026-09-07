package dev.quasar.world.block;

import dev.quasar.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Global-palette block state IDs.
 *
 * <p>Like {@link dev.quasar.net.Protocol}, these are version-specific: the global palette is just
 * the flattened index of every block state in registry order, so inserting one block upstream
 * shifts everything after it.
 *
 * <p>The values below are the <em>default</em> state IDs for 1.21.4, taken from
 * {@code generated/reports/blocks.json} produced by {@code java -jar server.jar --reports}. Blocks
 * with several states list a range there; the one flagged {@code "default": true} is what belongs
 * here.
 *
 * <p>Override the rest in {@code blocks.properties} next to the jar:
 * <pre>
 * bedrock = 79
 * grass_block = 9
 * </pre>
 * The values can be read out of the client jar's generated {@code blocks.json} report
 * ({@code java -jar server.jar --reports}), which lists every state with its ID.
 */
public final class Blocks {

    private static final Properties OVERRIDES = load();

    /** Always index 0 of the global palette. Safe across every modern version. */
    public static final int AIR = 0;

    /** Always index 1: the first block after air, and it has no block-state properties. */
    public static final int STONE = id("stone", 1);

    public static final int GRANITE = id("granite", 2);
    public static final int DIORITE = id("diorite", 4);
    public static final int ANDESITE = id("andesite", 6);

    /** Two states (snowy true/false); the value below is the snowy=false state. */
    public static final int GRASS_BLOCK = id("grass_block", 9);

    public static final int DIRT = id("dirt", 10);
    public static final int BEDROCK = id("bedrock", 85);

    /** Water has 16 states (levels 0-15); this is the default, i.e. a full source block. */
    public static final int WATER = id("water", 86);

    public static final int SAND = id("sand", 118);

    /**
     * Water occupies a run of states — the source block plus fifteen flowing levels. Placement
     * needs the whole range, not just the default, to recognise water it should replace.
     */
    public static final int WATER_STATE_MIN = id("water.min", 86);
    public static final int WATER_STATE_MAX = id("water.max", 101);

    private Blocks() {}

    public static boolean isAir(int stateId) {
        return stateId == AIR;
    }

    public static boolean isWater(int stateId) {
        return stateId >= WATER_STATE_MIN && stateId <= WATER_STATE_MAX;
    }

    /**
     * Whether a block can be built over.
     *
     * <p>Air and water both count, matching vanilla: you can place a block into water and it simply
     * displaces it. Restricting placement to air alone means that anywhere below sea level — which
     * is most of a noise-generated world's spawn area — every placement is silently refused.
     */
    public static boolean isReplaceable(int stateId) {
        return isAir(stateId) || isWater(stateId);
    }

    private static int id(String key, int fallback) {
        String raw = OVERRIDES.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.decode(raw.trim());
            Log.info("Block state override: %s = %d (default %d)", key, parsed, fallback);
            return parsed;
        } catch (NumberFormatException e) {
            Log.warn("Ignoring unparseable block override %s=%s", key, raw);
            return fallback;
        }
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = Path.of("blocks.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                Log.warn("Could not read blocks.properties: %s", e.getMessage());
            }
        }
        return props;
    }
}
