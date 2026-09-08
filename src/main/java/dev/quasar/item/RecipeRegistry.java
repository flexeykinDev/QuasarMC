package dev.quasar.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quasar.util.Log;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Crafting recipes, and matching a grid against them.
 *
 * <p>Loaded from {@code recipes.json}, which {@link RecipeExtractor} pulls out of a Minecraft jar.
 * Without that file there is no crafting -- the recipes are not something to write from memory,
 * and a partial hand-made table would be worse than none, because the missing half looks like a
 * bug rather than an absence.
 *
 * <p>Ingredients are stored as item IDs, resolved once at load through {@link ItemRegistry}. A
 * recipe naming an item this server does not know is dropped rather than half-loaded.
 */
public final class RecipeRegistry {

    private RecipeRegistry() {}

    /**
     * One recipe, with every ingredient already reduced to the set of item IDs that satisfies it.
     *
     * @param slots   for a shaped recipe, {@code width * height} entries in row-major order, an
     *                empty set meaning "this cell must be empty"; for a shapeless one, the
     *                ingredients in no particular order
     */
    public record Recipe(String name, boolean shaped, int width, int height,
                         List<IntOpenHashSet> slots, int resultItem, int resultCount) {}

    private static final List<Recipe> SHAPED = new ArrayList<>();
    private static final List<Recipe> SHAPELESS = new ArrayList<>();
    private static volatile boolean loaded;

    public static boolean available() {
        return loaded;
    }

    public static int count() {
        return SHAPED.size() + SHAPELESS.size();
    }

    public static synchronized void loadIfPresent() {
        Path file = Path.of("recipes.json");
        if (!Files.isRegularFile(file)) {
            Log.info("No recipes.json found — crafting is disabled. Generate one with: "
                    + "java -jar quasar-all.jar --extract-recipes <minecraft.jar>");
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray root = JsonParser.parseReader(reader).getAsJsonArray();
            int dropped = 0;
            for (JsonElement element : root) {
                JsonObject json = element.getAsJsonObject();
                Recipe recipe = parse(json);
                if (recipe == null) {
                    dropped++;
                    continue;
                }
                (recipe.shaped() ? SHAPED : SHAPELESS).add(recipe);
            }
            loaded = count() > 0;
            Log.info("Loaded %d crafting recipes from recipes.json (%d shaped, %d shapeless, "
                    + "%d dropped for unknown items)", count(), SHAPED.size(), SHAPELESS.size(),
                    dropped);
        } catch (Exception e) {
            Log.warn("Could not read recipes.json: %s", e.getMessage());
        }
    }

    private static Recipe parse(JsonObject json) {
        int resultItem = ItemRegistry.idForName(json.get("result").getAsString());
        if (resultItem <= 0) {
            return null;
        }
        List<IntOpenHashSet> slots = new ArrayList<>();
        for (JsonElement slot : json.getAsJsonArray("slots")) {
            IntOpenHashSet options = new IntOpenHashSet();
            for (JsonElement option : slot.getAsJsonArray()) {
                int id = ItemRegistry.idForName(option.getAsString());
                if (id > 0) {
                    options.add(id);
                }
            }
            // An ingredient none of whose options resolve would silently match nothing, so the
            // whole recipe is dropped instead of becoming quietly uncraftable.
            if (options.isEmpty() && slot.getAsJsonArray().size() > 0) {
                return null;
            }
            slots.add(options);
        }
        boolean shaped = json.get("shaped").getAsBoolean();
        return new Recipe(json.get("result").getAsString(), shaped,
                shaped ? json.get("width").getAsInt() : 0,
                shaped ? json.get("height").getAsInt() : 0,
                slots, resultItem, json.get("count").getAsInt());
    }

    /**
     * Finds what a grid produces, or null.
     *
     * @param grid  row-major item IDs, 0 for empty
     * @param width the grid's width; 2 for the inventory's little grid, 3 for a crafting table
     */
    public static Recipe match(int[] grid, int width) {
        int height = grid.length / width;
        Recipe shapeless = matchShapeless(grid);
        if (shapeless != null) {
            return shapeless;
        }
        return matchShaped(grid, width, height);
    }

    private static Recipe matchShapeless(int[] grid) {
        List<Integer> present = new ArrayList<>();
        for (int item : grid) {
            if (item > 0) {
                present.add(item);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        for (Recipe recipe : SHAPELESS) {
            if (recipe.slots().size() != present.size()) {
                continue;
            }
            // Greedy assignment: each ingredient claims one item and that item is used up. Correct
            // here because ingredients are sets of interchangeable items, so no ingredient can be
            // starved by an earlier one taking the only item it could have used.
            boolean[] used = new boolean[present.size()];
            boolean ok = true;
            for (IntOpenHashSet ingredient : recipe.slots()) {
                int found = -1;
                for (int i = 0; i < present.size(); i++) {
                    if (!used[i] && ingredient.contains(present.get(i).intValue())) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    ok = false;
                    break;
                }
                used[found] = true;
            }
            if (ok) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * Matches a shaped recipe, allowing it to sit anywhere in the grid and to be mirrored.
     *
     * <p>Both matter: vanilla lets you put a 2x2 pattern in any corner of a crafting table, and
     * matches mirrored patterns so a right-handed and left-handed arrangement both work.
     */
    private static Recipe matchShaped(int[] grid, int width, int height) {
        int[] bounds = usedBounds(grid, width, height);
        if (bounds == null) {
            return null;
        }
        int usedWidth = bounds[2] - bounds[0] + 1;
        int usedHeight = bounds[3] - bounds[1] + 1;

        for (Recipe recipe : SHAPED) {
            if (recipe.width() != usedWidth || recipe.height() != usedHeight) {
                continue;
            }
            if (matchesAt(grid, width, bounds[0], bounds[1], recipe, false)
                    || matchesAt(grid, width, bounds[0], bounds[1], recipe, true)) {
                return recipe;
            }
        }
        return null;
    }

    /** The smallest rectangle containing every non-empty cell, or null when the grid is empty. */
    private static int[] usedBounds(int[] grid, int width, int height) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (grid[y * width + x] > 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new int[] {minX, minY, maxX, maxY};
    }

    private static boolean matchesAt(int[] grid, int width, int offsetX, int offsetY,
                                     Recipe recipe, boolean mirrored) {
        for (int y = 0; y < recipe.height(); y++) {
            for (int x = 0; x < recipe.width(); x++) {
                int recipeX = mirrored ? recipe.width() - 1 - x : x;
                IntOpenHashSet ingredient = recipe.slots().get(y * recipe.width() + recipeX);
                int item = grid[(offsetY + y) * width + (offsetX + x)];

                if (ingredient.isEmpty()) {
                    if (item > 0) {
                        return false;
                    }
                } else if (item <= 0 || !ingredient.contains(item)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Clears the tables. Test-only. */
    static synchronized void resetForTesting() {
        SHAPED.clear();
        SHAPELESS.clear();
        loaded = false;
    }

    /** Adds a recipe directly. Test-only, so matching can be tested without the data file. */
    static synchronized void addForTesting(Recipe recipe) {
        (recipe.shaped() ? SHAPED : SHAPELESS).add(recipe);
        loaded = true;
    }

    static IntOpenHashSet options(int... items) {
        IntOpenHashSet set = new IntOpenHashSet();
        Arrays.stream(items).forEach(set::add);
        return set;
    }
}
