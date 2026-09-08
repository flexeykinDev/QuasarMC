package dev.quasar.world.blockentity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quasar.util.Log;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Numeric IDs for block entity types, from {@code registries.json}.
 *
 * <p>The chunk packet identifies each block entity by a registry ID, not by its name, so a chest
 * cannot be sent without this table. Without it, block entities are simply omitted -- which is
 * visible, because a chest's own block model is empty: the whole thing is drawn by a block-entity
 * renderer, and a chest with no block entity is an invisible hole with a working inventory behind
 * it.
 */
public final class BlockEntityTypes {

    private BlockEntityTypes() {}

    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static volatile boolean loaded;

    public static boolean available() {
        return loaded;
    }

    /** @return the registry ID, or -1 when unknown */
    public static int idFor(String name) {
        Integer id = BY_NAME.get(name);
        return id == null ? -1 : id;
    }

    public static synchronized void loadIfPresent() {
        Path file = Path.of("registries.json");
        if (!Files.isRegularFile(file)) {
            Log.info("No registries.json found — block entities will not be sent, so chests and "
                    + "signs render as empty space until one is generated.");
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject registry = root.getAsJsonObject("minecraft:block_entity_type");
            if (registry == null) {
                Log.warn("registries.json has no minecraft:block_entity_type registry");
                return;
            }
            JsonObject entries = registry.getAsJsonObject("entries");
            for (Map.Entry<String, com.google.gson.JsonElement> entry : entries.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                BY_NAME.put(entry.getKey(), value.get("protocol_id").getAsInt());
            }
            loaded = true;
            Log.info("Loaded %d block entity types from registries.json", BY_NAME.size());
        } catch (Exception e) {
            Log.warn("Could not read block entity types from registries.json: %s", e.getMessage());
        }
    }
}
