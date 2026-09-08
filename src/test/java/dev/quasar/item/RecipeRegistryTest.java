package dev.quasar.item;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recipe matching, against the real extracted data wherever possible.
 *
 * <p>Matching is the part that silently does nothing when it is wrong: an unmatched grid is
 * indistinguishable from an empty one, so a broken matcher looks exactly like a player who has not
 * worked out the recipe yet. Every test here therefore names a specific recipe and asserts the item
 * and count that come out.
 */
class RecipeRegistryTest {

    @BeforeAll
    static void load() {
        ItemRegistry.loadIfPresent();
        RecipeRegistry.loadIfPresent();
    }

    private static void needsData() {
        Assumptions.assumeTrue(RecipeRegistry.available(),
                "needs recipes.json (--extract-recipes) and registries.json");
    }

    private static int item(String name) {
        return ItemRegistry.idForName(name);
    }

    // ------------------------------------------------------------------------------- the data

    @Test
    void theRecipeTableLoads() {
        needsData();
        assertTrue(RecipeRegistry.count() > 800,
                "expected the full vanilla crafting set, got " + RecipeRegistry.count());
    }

    // ----------------------------------------------------------------------------- shaped

    @Test
    void planksMakeSticks() {
        needsData();
        int planks = item("minecraft:oak_planks");
        // Vertical pair in the left column of a 2x2 grid.
        int[] grid = {planks, 0, planks, 0};

        RecipeRegistry.Recipe recipe = RecipeRegistry.match(grid, 2);

        assertNotNull(recipe, "two planks stacked should make sticks");
        assertEquals(item("minecraft:stick"), recipe.resultItem());
        assertEquals(4, recipe.resultCount(), "a stick recipe yields four");
    }

    @Test
    void aShapedRecipeMatchesAnywhereInTheGrid() {
        needsData();
        int planks = item("minecraft:oak_planks");
        // The same pair, moved to the right column: vanilla does not care where in the grid a
        // pattern sits, only about its shape.
        int[] grid = {0, planks, 0, planks};

        RecipeRegistry.Recipe recipe = RecipeRegistry.match(grid, 2);
        assertNotNull(recipe, "the pattern should match wherever it is placed");
        assertEquals(item("minecraft:stick"), recipe.resultItem());
    }

    @Test
    void aTwoByTwoPatternFitsInsideACraftingTable() {
        needsData();
        int planks = item("minecraft:oak_planks");
        // A crafting table, made in the bottom-right corner of a 3x3 grid.
        int[] grid = {
                0, 0, 0,
                0, planks, planks,
                0, planks, planks};

        RecipeRegistry.Recipe recipe = RecipeRegistry.match(grid, 3);
        assertNotNull(recipe, "a 2x2 pattern should match in a corner of a 3x3 grid");
        assertEquals(item("minecraft:crafting_table"), recipe.resultItem());
    }

    @Test
    void aGapInThePatternMustStayEmpty() {
        needsData();
        int planks = item("minecraft:oak_planks");
        // A chest is a ring of planks with a hole in the middle. Filling the hole must not craft.
        int[] full = {
                planks, planks, planks,
                planks, planks, planks,
                planks, planks, planks};

        RecipeRegistry.Recipe recipe = RecipeRegistry.match(full, 3);
        if (recipe != null) {
            assertEquals(false, "minecraft:chest".equals(recipe.name()),
                    "a solid block of planks is not a chest -- the centre gap is part of the shape");
        }
    }

    @Test
    void anIngredientTagAcceptsAnyMember() {
        needsData();
        // The stick recipe takes #minecraft:planks, so spruce must work exactly as oak does.
        int spruce = item("minecraft:spruce_planks");
        int[] grid = {spruce, 0, spruce, 0};

        RecipeRegistry.Recipe recipe = RecipeRegistry.match(grid, 2);
        assertNotNull(recipe, "any plank should satisfy the #planks tag");
        assertEquals(item("minecraft:stick"), recipe.resultItem());
    }

    // -------------------------------------------------------------------------- non-matches

    @Test
    void anEmptyGridMakesNothing() {
        needsData();
        assertNull(RecipeRegistry.match(new int[] {0, 0, 0, 0}, 2));
    }

    @Test
    void anUnrelatedArrangementMakesNothing() {
        needsData();
        // Bedrock and grass blocks appear in no recipe at all -- checked against recipes.json,
        // not assumed. A matcher that is too loose is worse than one that is too strict, because
        // it hands out free items.
        //
        // This test was wrong twice before it was right, and both times the matcher was correct:
        // a single stone is the shapeless stone_button recipe, and two stone in a row is a stone
        // pressure plate. Picking a "surely nothing" arrangement out of the air does not work,
        // because vanilla has nearly a thousand recipes and most simple shapes are one of them.
        int bedrock = item("minecraft:bedrock");
        int grass = item("minecraft:grass_block");

        assertNull(RecipeRegistry.match(new int[] {bedrock, 0, 0, 0}, 2));
        assertNull(RecipeRegistry.match(new int[] {bedrock, bedrock, 0, 0}, 2));
        assertNull(RecipeRegistry.match(new int[] {grass, bedrock, 0, 0}, 2));
    }

    // ------------------------------------------------------------------------------ shapeless

    @Test
    void aShapelessRecipeIgnoresArrangement() {
        needsData();
        int planks = item("minecraft:oak_planks");
        // One plank alone is the shapeless oak button recipe... whichever cell it sits in.
        RecipeRegistry.Recipe first = RecipeRegistry.match(new int[] {planks, 0, 0, 0}, 2);
        RecipeRegistry.Recipe last = RecipeRegistry.match(new int[] {0, 0, 0, planks}, 2);

        assertNotNull(first, "a single plank should craft something");
        assertEquals(first.resultItem(), last.resultItem(),
                "position must not change a shapeless result");
    }
}
