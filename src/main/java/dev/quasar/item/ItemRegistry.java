package dev.quasar.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quasar.util.Log;
import dev.quasar.world.block.BlockStateRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps every item the client can hand us to the block it places.
 *
 * <h2>Why the server needs no item list of its own</h2>
 * The creative inventory is built client-side: the client already knows every item and shows them
 * all without the server sending anything. What the server has to do is <em>listen</em> — when a
 * player picks something, the client reports it in {@code set_creative_mode_slot}, and from then on
 * the server only needs to answer one question: which block does this item ID place?
 *
 * <p>Almost always, the one whose name matches. {@code minecraft:oak_stairs} the item places
 * {@code minecraft:oak_stairs} the block. A handful of items disagree with their block's name and
 * are listed in {@link #NAME_OVERRIDES}. Items with no block at all — swords, food — simply place
 * nothing.
 *
 * <h2>Where the data comes from</h2>
 * Item IDs live in {@code registries.json} and block states in {@code blocks.json}, both from
 * {@code java -jar server.jar --reports}. They are Mojang's data, so they are loaded at runtime from
 * the working directory rather than shipped. Without them the server falls back to
 * {@link HotbarKit}'s nine blocks and says so at startup.
 */
public final class ItemRegistry {

    /** Items whose name differs from the block they place. */
    private static final Map<String, String> NAME_OVERRIDES = Map.of(
            "minecraft:redstone", "minecraft:redstone_wire",
            "minecraft:string", "minecraft:tripwire",
            "minecraft:wheat_seeds", "minecraft:wheat",
            "minecraft:beetroot_seeds", "minecraft:beetroots",
            "minecraft:melon_seeds", "minecraft:melon_stem",
            "minecraft:pumpkin_seeds", "minecraft:pumpkin_stem",
            "minecraft:carrot", "minecraft:carrots",
            "minecraft:potato", "minecraft:potatoes",
            "minecraft:cocoa_beans", "minecraft:cocoa",
            "minecraft:kelp", "minecraft:kelp_plant");

    private static final Int2ObjectOpenHashMap<String> NAME_BY_ID = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap BLOCK_BY_ITEM = new Int2IntOpenHashMap();

    /**
     * Block name to the item that yields it, for pick-block.
     *
     * <p>Keyed by name rather than by state, because picking has to work on any state of the block:
     * middle-clicking east-facing stairs should hand you the stairs item, not fail because the
     * mapping only knew the default state.
     */
    private static final Map<String, Integer> ITEM_BY_BLOCK_NAME = new HashMap<>();

    /** Item name to numeric ID. Needed because item NBT stores names, while the protocol uses IDs. */
    private static final Map<String, Integer> ID_BY_NAME = new HashMap<>();

    private static boolean loaded;
    private static int placeableCount;

    static {
        BLOCK_BY_ITEM.defaultReturnValue(-1);
    }

    private ItemRegistry() {}

    public static boolean isLoaded() {
        return loaded;
    }

    public static int placeableCount() {
        return placeableCount;
    }

    public static int itemCount() {
        return NAME_BY_ID.size();
    }

    public static String nameFor(int itemId) {
        return NAME_BY_ID.get(itemId);
    }

    /** @return the block state this item places, or -1 if it places nothing */
    public static int blockStateForItem(int itemId) {
        return BLOCK_BY_ITEM.get(itemId);
    }

    /** @return the numeric ID for an item name, or -1 when unknown */
    public static int idForName(String itemName) {
        return ID_BY_NAME.getOrDefault(itemName, -1);
    }

    /** @return the item that yields this block, or -1 when nothing does */
    public static int itemForBlockName(String blockName) {
        return ITEM_BY_BLOCK_NAME.getOrDefault(blockName, -1);
    }

    /**
     * Builds the item table from {@code registries.json}, resolving each item to a block through
     * {@link BlockStateRegistry}. Call after the block table has been loaded, or every item will
     * resolve to nothing.
     */
    public static void loadIfPresent() {
        Path file = Path.of("registries.json");
        if (!Files.isRegularFile(file)) {
            Log.info("No registries.json found — placement is limited to the built-in hotbar kit. "
                    + "Generate one with 'java -jar server.jar --reports' for the full creative "
                    + "inventory.");
            return;
        }
        long start = System.nanoTime();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject items = root.getAsJsonObject("minecraft:item");
            if (items == null) {
                Log.warn("registries.json has no minecraft:item registry");
                return;
            }
            JsonObject entries = items.getAsJsonObject("entries");
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                String itemName = entry.getKey();
                int itemId = entry.getValue().getAsJsonObject().get("protocol_id").getAsInt();
                NAME_BY_ID.put(itemId, itemName);
                ID_BY_NAME.put(itemName, itemId);

                String blockName = NAME_OVERRIDES.getOrDefault(itemName, itemName);
                int state = BlockStateRegistry.defaultStateForBlock(blockName);
                // Strictly greater than zero, not merely present. Air is item 0 and block state 0,
                // and an empty inventory slot is also item 0 -- so accepting state 0 made an empty
                // hand "place air", which silently deleted any replaceable block it was aimed at.
                if (state > 0) {
                    BLOCK_BY_ITEM.put(itemId, state);
                    ITEM_BY_BLOCK_NAME.putIfAbsent(blockName, itemId);
                    placeableCount++;
                }
            }
            loaded = true;
            Log.info("Loaded %d items (%d placeable) from registries.json in %d ms",
                    NAME_BY_ID.size(), placeableCount, (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not read registries.json (%s); falling back to the hotbar kit",
                    e.toString());
        }
    }
}
