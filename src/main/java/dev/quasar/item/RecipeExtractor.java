package dev.quasar.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quasar.util.Log;

import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pulls crafting recipes out of a Minecraft jar into a single {@code recipes.json}.
 *
 * <p>Recipes are the one piece of game data Mojang's {@code --reports} does not emit: they live as
 * individual files in the vanilla data pack inside the jar, roughly a thousand of them. So this
 * follows the same rule as every other version-specific table in this project -- read it from
 * Mojang's own data, never write it from memory.
 *
 * <p>Item tags are resolved here rather than at runtime. A recipe says {@code #minecraft:planks},
 * tags nest ({@code #minecraft:logs} contains {@code #minecraft:logs_that_burn}), and flattening
 * that once at extraction keeps the server's matcher dealing only in concrete item names.
 *
 * <pre>
 * java -jar quasar-all.jar --extract-recipes path/to/minecraft-1.21.4-client.jar
 * </pre>
 */
public final class RecipeExtractor {

    private RecipeExtractor() {}

    private static final String RECIPE_PREFIX = "data/minecraft/recipe/";
    private static final String ITEM_TAG_PREFIX = "data/minecraft/tags/item/";

    /** Only the two types a crafting grid can produce; smelting and stonecutting are not crafting. */
    private static final Set<String> WANTED_TYPES =
            Set.of("minecraft:crafting_shaped", "minecraft:crafting_shapeless");

    public static void run(String jarPath, Path output) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath)) {
            Map<String, JsonObject> rawTags = new HashMap<>();
            List<JsonObject> recipes = new ArrayList<>();

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".json")) {
                    continue;
                }
                if (name.startsWith(ITEM_TAG_PREFIX)) {
                    String tag = "minecraft:"
                            + name.substring(ITEM_TAG_PREFIX.length(), name.length() - 5);
                    rawTags.put(tag, read(jar, entry));
                } else if (name.startsWith(RECIPE_PREFIX)) {
                    recipes.add(read(jar, entry));
                }
            }

            Map<String, List<String>> tags = new HashMap<>();
            for (String tag : rawTags.keySet()) {
                tags.put(tag, new ArrayList<>(resolveTag(tag, rawTags, new HashSet<>())));
            }

            JsonArray out = new JsonArray();
            int skipped = 0;
            for (JsonObject recipe : recipes) {
                JsonElement type = recipe.get("type");
                if (type == null || !WANTED_TYPES.contains(type.getAsString())) {
                    skipped++;
                    continue;
                }
                JsonObject converted = convert(recipe, tags);
                if (converted != null) {
                    out.add(converted);
                }
            }

            Gson gson = new GsonBuilder().create();
            try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                gson.toJson(out, writer);
            }
            Log.info("Wrote %d crafting recipes to %s (%d non-crafting recipes skipped, "
                            + "%d item tags resolved)",
                    out.size(), output.toAbsolutePath(), skipped, tags.size());
        }
    }

    private static JsonObject read(ZipFile jar, ZipEntry entry) throws Exception {
        try (InputStreamReader reader =
                     new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /**
     * Flattens a tag to concrete item names.
     *
     * <p>{@code visiting} guards against a cycle in the tag graph. Mojang's data has none, but a
     * stack overflow while reading a data file is a miserable way to find that out.
     */
    private static Set<String> resolveTag(String tag, Map<String, JsonObject> rawTags,
                                          Set<String> visiting) {
        Set<String> resolved = new LinkedHashSet<>();
        if (!visiting.add(tag)) {
            return resolved;
        }
        JsonObject definition = rawTags.get(tag);
        if (definition == null || !definition.has("values")) {
            return resolved;
        }
        for (JsonElement value : definition.getAsJsonArray("values")) {
            String entry = value.isJsonObject()
                    ? value.getAsJsonObject().get("id").getAsString()
                    : value.getAsString();
            if (entry.startsWith("#")) {
                resolved.addAll(resolveTag(entry.substring(1), rawTags, visiting));
            } else {
                resolved.add(entry);
            }
        }
        visiting.remove(tag);
        return resolved;
    }

    /**
     * Rewrites one recipe into the flat shape the server reads.
     *
     * <p>Both kinds become a list of "slots", each slot being the set of item names that satisfy
     * it. A shaped recipe keeps its grid width and height; a shapeless one has neither.
     */
    private static JsonObject convert(JsonObject recipe, Map<String, List<String>> tags) {
        JsonObject result = recipe.getAsJsonObject("result");
        if (result == null || !result.has("id")) {
            return null;
        }

        JsonObject out = new JsonObject();
        out.addProperty("result", result.get("id").getAsString());
        out.addProperty("count", result.has("count") ? result.get("count").getAsInt() : 1);

        String type = recipe.get("type").getAsString();
        if (type.equals("minecraft:crafting_shaped")) {
            JsonArray pattern = recipe.getAsJsonArray("pattern");
            JsonObject key = recipe.getAsJsonObject("key");
            int height = pattern.size();
            int width = 0;
            for (JsonElement row : pattern) {
                width = Math.max(width, row.getAsString().length());
            }

            JsonArray slots = new JsonArray();
            for (int row = 0; row < height; row++) {
                String line = pattern.get(row).getAsString();
                for (int column = 0; column < width; column++) {
                    char symbol = column < line.length() ? line.charAt(column) : ' ';
                    slots.add(symbol == ' ' ? new JsonArray() : options(key.get(String.valueOf(symbol)), tags));
                }
            }
            out.addProperty("shaped", true);
            out.addProperty("width", width);
            out.addProperty("height", height);
            out.add("slots", slots);
        } else {
            JsonArray slots = new JsonArray();
            for (JsonElement ingredient : recipe.getAsJsonArray("ingredients")) {
                slots.add(options(ingredient, tags));
            }
            out.addProperty("shaped", false);
            out.add("slots", slots);
        }
        return out;
    }

    /** Every item name that satisfies one ingredient: a plain name, a tag, or a list of either. */
    private static JsonArray options(JsonElement ingredient, Map<String, List<String>> tags) {
        JsonArray out = new JsonArray();
        if (ingredient == null) {
            return out;
        }
        if (ingredient.isJsonArray()) {
            for (JsonElement each : ingredient.getAsJsonArray()) {
                for (JsonElement resolved : options(each, tags)) {
                    out.add(resolved);
                }
            }
            return out;
        }
        String value = ingredient.isJsonObject()
                ? ingredient.getAsJsonObject().get("item").getAsString()
                : ingredient.getAsString();
        if (value.startsWith("#")) {
            for (String item : tags.getOrDefault(value.substring(1), List.of())) {
                out.add(item);
            }
        } else {
            out.add(value);
        }
        return out;
    }
}
