package dev.quasar.item;

import dev.quasar.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The nine blocks a player is given on join, as matched item/block pairs.
 *
 * <h2>Why the server hands out items at all</h2>
 * A client with an empty hand does not send {@code use_item_on} — right-clicking is simply inert,
 * so no amount of server-side cleverness makes placing work. The server has no inventory system, so
 * nothing would ever fill that hand. Sending a fixed hotbar on join is what makes placement
 * reachable at all.
 *
 * <p>Pairing matters as much as the contents. Each entry ties an <em>item</em> to the
 * <em>block state</em> that item places. Because the server puts item N in hotbar slot N and places
 * block N when slot N is used, the client's optimistic prediction and the server's write agree, so
 * placement neither flickers nor rolls back. A mismatched table would reintroduce exactly the
 * desync this pairing exists to avoid.
 *
 * <p>Both ID spaces are version-specific and both came from Mojang's generated reports:
 * {@code registries.json} under {@code minecraft:item} for item IDs, and {@code blocks.json} for
 * the default block state. Override either in {@code hotbar.properties} next to the jar, one line
 * per entry, as {@code <name> = <itemId>:<blockState>}:
 * <pre>
 * stone = 1:1
 * glass = 195:562
 * </pre>
 */
public final class HotbarKit {

    /** One hotbar entry: the item the player holds, and the block it places. */
    public record Entry(String name, int itemId, int blockState) {}

    private static final Properties OVERRIDES = load();

    /**
     * Nine visually distinct, fully solid blocks. Deliberately no water or other non-placeable
     * item — a bucket is used through {@code use_item}, not {@code use_item_on}, and would silently
     * do nothing.
     */
    public static final List<Entry> ENTRIES = List.of(
            entry("stone", 1, 1),
            entry("grass_block", 27, 9),
            entry("dirt", 28, 10),
            entry("sand", 59, 118),
            entry("cobblestone", 35, 14),
            entry("oak_planks", 36, 15),
            entry("oak_log", 134, 137),
            entry("glass", 195, 562),
            entry("bedrock", 58, 85));

    /** Player inventory slot index of the first hotbar slot. Slots 36-44 are the hotbar. */
    public static final int FIRST_HOTBAR_SLOT = 36;

    /** Total slots in the player inventory container: output, grid, armour, main, hotbar, offhand. */
    public static final int INVENTORY_SLOTS = 46;

    private HotbarKit() {}

    public static int size() {
        return ENTRIES.size();
    }

    /** The block a hotbar slot places; out-of-range slots fall back to the first entry. */
    public static int blockForSlot(int slot) {
        if (slot < 0 || slot >= ENTRIES.size()) {
            return ENTRIES.get(0).blockState();
        }
        return ENTRIES.get(slot).blockState();
    }

    private static Entry entry(String name, int defaultItemId, int defaultBlockState) {
        String raw = OVERRIDES.getProperty(name);
        if (raw == null) {
            return new Entry(name, defaultItemId, defaultBlockState);
        }
        String[] parts = raw.split(":", 2);
        try {
            int itemId = Integer.decode(parts[0].trim());
            int blockState = parts.length > 1 ? Integer.decode(parts[1].trim()) : defaultBlockState;
            Log.info("Hotbar override: %s = %d:%d", name, itemId, blockState);
            return new Entry(name, itemId, blockState);
        } catch (NumberFormatException e) {
            Log.warn("Ignoring unparseable hotbar override %s=%s", name, raw);
            return new Entry(name, defaultItemId, defaultBlockState);
        }
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = Path.of("hotbar.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                Log.warn("Could not read hotbar.properties: %s", e.getMessage());
            }
        }
        return props;
    }

    /** Human-readable slot list, used in the join message. */
    public static String describe() {
        List<String> names = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES) {
            names.add(entry.name());
        }
        return String.join(", ", names);
    }
}
