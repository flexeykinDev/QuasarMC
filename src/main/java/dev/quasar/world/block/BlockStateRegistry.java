package dev.quasar.world.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quasar.util.Log;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps between this server's numeric block state IDs and Anvil's {@code (name, properties)} pairs.
 *
 * <h2>Two tiers</h2>
 * A built-in table covers every block this server can itself generate or place — enough to write
 * worlds vanilla and third-party tools can read, with no setup.
 *
 * <p>Reading an arbitrary vanilla world needs the <em>whole</em> registry, some 28,000 states, which
 * is Mojang's data and is not shipped here. Drop a {@code blocks.json} (from
 * {@code java -jar server.jar --reports}) next to the jar and the full table is loaded at startup.
 *
 * <h2>What happens without it</h2>
 * A chunk containing blocks this table cannot name is still loaded, with unknown blocks standing in
 * as stone — but the chunk is flagged so it is <em>never written back</em>. Degrading someone's
 * world to stone on disk because a lookup table was missing would be far worse than refusing to
 * save it.
 */
public final class BlockStateRegistry {

    /** What an unmappable block becomes in memory. Never written back to disk. */
    public static final int UNKNOWN_PLACEHOLDER = Blocks.STONE;

    public record State(int id, String name, Map<String, String> properties) {}

    private static final Int2ObjectOpenHashMap<State> BY_ID = new Int2ObjectOpenHashMap<>();
    private static final Object2IntOpenHashMap<String> BY_KEY = new Object2IntOpenHashMap<>();

    /** Block name to its default state ID — what an item of that name places. */
    private static final Object2IntOpenHashMap<String> DEFAULT_STATE = new Object2IntOpenHashMap<>();

    private static boolean fullTableLoaded;

    static {
        BY_KEY.defaultReturnValue(-1);
        DEFAULT_STATE.defaultReturnValue(-1);
        builtin(Blocks.AIR, "minecraft:air");
        builtin(Blocks.STONE, "minecraft:stone");
        builtin(Blocks.GRANITE, "minecraft:granite");
        builtin(Blocks.DIORITE, "minecraft:diorite");
        builtin(Blocks.ANDESITE, "minecraft:andesite");
        builtin(Blocks.GRASS_BLOCK, "minecraft:grass_block", "snowy", "false");
        builtin(Blocks.DIRT, "minecraft:dirt");
        builtin(Blocks.BEDROCK, "minecraft:bedrock");
        builtin(Blocks.WATER, "minecraft:water", "level", "0");
        builtin(Blocks.SAND, "minecraft:sand");
        // The hotbar kit's remaining blocks, so worlds built with them round-trip.
        builtin(14, "minecraft:cobblestone");
        builtin(15, "minecraft:oak_planks");
        builtin(137, "minecraft:oak_log", "axis", "y");
        builtin(562, "minecraft:glass");
    }

    private BlockStateRegistry() {}

    private static void builtin(int id, String name, String... propertyPairs) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i + 1 < propertyPairs.length; i += 2) {
            properties.put(propertyPairs[i], propertyPairs[i + 1]);
        }
        register(new State(id, name, properties));
        registerDefault(name, id);
    }

    private static void register(State state) {
        BY_ID.putIfAbsent(state.id(), state);
        BY_KEY.putIfAbsent(key(state.name(), state.properties()), state.id());
    }

    private static void registerDefault(String blockName, int stateId) {
        DEFAULT_STATE.putIfAbsent(blockName, stateId);
    }

    /**
     * The state an item of this block's name should place.
     *
     * <p>Always the block's <em>default</em> state. Placement here does not derive facing, axis,
     * half or waterlogging from how you clicked, so stairs land unrotated and logs upright. Getting
     * that right means reimplementing every block's placement logic, which is a far larger job than
     * the mapping this supports.
     *
     * @return the state ID, or -1 if no block carries that name
     */
    public static int defaultStateForBlock(String blockName) {
        return DEFAULT_STATE.getInt(blockName);
    }

    /** Canonical lookup key: properties sorted by name so ordering never affects identity. */
    private static String key(String name, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(properties).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.append(']').toString();
    }

    public static boolean hasFullTable() {
        return fullTableLoaded;
    }

    /** @return the state, or {@code null} when the ID is not in the table */
    public static State byId(int id) {
        return BY_ID.get(id);
    }

    /** @return the state ID, or -1 when this name/property combination is unknown */
    public static int idFor(String name, Map<String, String> properties) {
        return BY_KEY.getInt(key(name, properties));
    }

    /**
     * Loads Mojang's full block table if {@code blocks.json} is present in the working directory.
     * Built-in entries win, so the IDs this server generates with can never be shifted underneath
     * it by a report from a different version.
     */
    public static void loadFullTableIfPresent() {
        Path file = Path.of("blocks.json");
        if (!Files.isRegularFile(file)) {
            Log.info("No blocks.json found — Anvil support covers this server's own blocks only. "
                    + "Generate one with 'java -jar server.jar --reports' to read arbitrary worlds.");
            return;
        }
        long start = System.nanoTime();
        int states = 0;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> block : root.entrySet()) {
                JsonArray stateArray = block.getValue().getAsJsonObject().getAsJsonArray("states");
                if (stateArray == null) {
                    continue;
                }
                for (JsonElement element : stateArray) {
                    JsonObject stateObject = element.getAsJsonObject();
                    int id = stateObject.get("id").getAsInt();
                    Map<String, String> properties = new LinkedHashMap<>();
                    JsonObject props = stateObject.getAsJsonObject("properties");
                    if (props != null) {
                        for (Map.Entry<String, JsonElement> property : props.entrySet()) {
                            properties.put(property.getKey(), property.getValue().getAsString());
                        }
                    }
                    register(new State(id, block.getKey(), properties));
                    JsonElement isDefault = stateObject.get("default");
                    if (isDefault != null && isDefault.getAsBoolean()) {
                        registerDefault(block.getKey(), id);
                    }
                    states++;
                }
            }
            fullTableLoaded = true;
            Log.info("Loaded %d block states from blocks.json in %d ms",
                    states, (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not read blocks.json (%s); falling back to built-in blocks only",
                    e.toString());
        }
    }
}
