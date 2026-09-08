package dev.quasar.item;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crafting grid itself: what it produces, and what it consumes.
 *
 * <p>Consumption is the half that quietly duplicates or destroys items when it is wrong, and
 * neither shows up as an error -- a player just ends up with more or fewer materials than they
 * should. So these assert exact counts rather than "it crafted something".
 */
class CraftingMenuTest {

    @BeforeAll
    static void load() {
        ItemRegistry.loadIfPresent();
        RecipeRegistry.loadIfPresent();
    }

    private static void needsData() {
        Assumptions.assumeTrue(RecipeRegistry.available(), "needs recipes.json");
    }

    private static CraftingMenu table() {
        return new CraftingMenu(1, 3, 3, 0, 0, 0);
    }

    @Test
    void slotLayoutMatchesTheClientWindow() {
        CraftingMenu menu = table();
        // Result, nine grid cells, 27 inventory, 9 hotbar. If this is wrong every click lands in
        // the wrong place, so it is pinned rather than assumed.
        assertEquals(46, menu.totalSlots());
        assertTrue(menu.isGridSlot(1) && menu.isGridSlot(9), "slots 1-9 are the grid");
        assertEquals(false, menu.isGridSlot(0), "slot 0 is the result, not the grid");
        assertEquals(false, menu.isGridSlot(10), "slot 10 is the first inventory slot");
    }

    @Test
    void aFilledGridProducesItsRecipe() {
        needsData();
        CraftingMenu menu = table();
        int planks = ItemRegistry.idForName("minecraft:oak_planks");
        menu.setGrid(0, ItemStack.of(planks, 3));
        menu.setGrid(3, ItemStack.of(planks, 3));
        menu.refreshResult();

        assertEquals(ItemRegistry.idForName("minecraft:stick"), menu.result().itemId());
        assertEquals(4, menu.result().count());
    }

    @Test
    void craftingTakesExactlyOneFromEachCell() {
        needsData();
        CraftingMenu menu = table();
        int planks = ItemRegistry.idForName("minecraft:oak_planks");
        menu.setGrid(0, ItemStack.of(planks, 5));
        menu.setGrid(3, ItemStack.of(planks, 5));
        menu.refreshResult();

        menu.consumeIngredients();

        // One per occupied cell, never more, regardless of what the recipe asked for.
        assertEquals(4, menu.grid(0).count());
        assertEquals(4, menu.grid(3).count());
        assertEquals(0, menu.grid(1).count(), "untouched cells stay empty");
    }

    @Test
    void theResultUpdatesAsTheGridEmpties() {
        needsData();
        CraftingMenu menu = table();
        int planks = ItemRegistry.idForName("minecraft:oak_planks");
        menu.setGrid(0, ItemStack.of(planks, 1));
        menu.setGrid(3, ItemStack.of(planks, 1));
        menu.refreshResult();
        assertTrue(!menu.result().isEmpty(), "precondition: the grid crafts something");

        menu.consumeIngredients();

        assertTrue(menu.result().isEmpty(),
                "with the last ingredients used the result must clear, or the player keeps taking"
                        + " free items from an empty grid");
        assertTrue(menu.grid(0).isEmpty());
    }

    @Test
    void consumingTwiceCannotOutrunTheGrid() {
        needsData();
        CraftingMenu menu = table();
        int planks = ItemRegistry.idForName("minecraft:oak_planks");
        menu.setGrid(0, ItemStack.of(planks, 2));
        menu.setGrid(3, ItemStack.of(planks, 2));
        menu.refreshResult();

        // Two crafts is exactly what the grid holds; a third must produce nothing. Shift-click
        // crafting loops on this, and a loop that keeps going past the last ingredient is how a
        // grid hands out free items.
        menu.consumeIngredients();
        menu.consumeIngredients();

        assertTrue(menu.result().isEmpty(), "the grid is spent and must stop producing");
        assertTrue(menu.grid(0).isEmpty());
        assertTrue(menu.grid(3).isEmpty());
    }

    @Test
    void anEmptyGridProducesNothing() {
        needsData();
        CraftingMenu menu = table();
        menu.refreshResult();
        assertTrue(menu.result().isEmpty());
    }
}
