package dev.quasar.item;

/**
 * An open crafting screen: a grid and the result it currently produces.
 *
 * <p>Slot 0 is the result, then the grid, then the player's inventory. That layout is the client's,
 * not a choice made here -- a crafting table window is 46 slots: result, nine grid cells, 27 main
 * inventory slots and nine hotbar slots.
 *
 * <p>Unlike a container this is backed by nothing. The grid exists only while the screen is open,
 * and closing it hands everything back to the player, which is why there is no persistence here at
 * all: a crafting table is furniture, not storage.
 */
public final class CraftingMenu {

    /** Result slot index, in the client's numbering. */
    public static final int RESULT_SLOT = 0;

    /** First grid slot. The grid runs from here for {@code width * height} slots. */
    public static final int FIRST_GRID_SLOT = 1;

    private final int windowId;
    private final int width;
    private final int height;
    private final ItemStack[] grid;

    /** The crafting table this belongs to, so the screen can close if it is broken. */
    private final int blockX;
    private final int blockY;
    private final int blockZ;

    private ItemStack result = ItemStack.EMPTY;
    private RecipeRegistry.Recipe recipe;

    public CraftingMenu(int windowId, int width, int height, int blockX, int blockY, int blockZ) {
        this.windowId = windowId;
        this.width = width;
        this.height = height;
        this.grid = new ItemStack[width * height];
        java.util.Arrays.fill(this.grid, ItemStack.EMPTY);
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
    }

    public int windowId() {
        return windowId;
    }

    public int width() {
        return width;
    }

    public int gridSize() {
        return grid.length;
    }

    /** Total window slots: result, grid, 27 inventory, 9 hotbar. */
    public int totalSlots() {
        return 1 + grid.length + 36;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    public ItemStack grid(int index) {
        return grid[index];
    }

    public void setGrid(int index, ItemStack stack) {
        grid[index] = stack == null ? ItemStack.EMPTY : stack;
    }

    public ItemStack result() {
        return result;
    }

    public RecipeRegistry.Recipe recipe() {
        return recipe;
    }

    /** True when a window slot index falls inside the grid. */
    public boolean isGridSlot(int slot) {
        return slot >= FIRST_GRID_SLOT && slot < FIRST_GRID_SLOT + grid.length;
    }

    public int gridIndexOf(int slot) {
        return slot - FIRST_GRID_SLOT;
    }

    /** Recomputes what the grid currently makes. Call after any change to it. */
    public void refreshResult() {
        int[] items = new int[grid.length];
        for (int i = 0; i < grid.length; i++) {
            items[i] = grid[i].isEmpty() ? 0 : grid[i].itemId();
        }
        recipe = RecipeRegistry.match(items, width);
        result = recipe == null
                ? ItemStack.EMPTY
                : ItemStack.of(recipe.resultItem(), recipe.resultCount());
    }

    /**
     * Consumes one of each ingredient, as taking the result does.
     *
     * <p>Every occupied cell loses exactly one item regardless of the recipe, which is what vanilla
     * does: a recipe never takes two from one cell. Deriving it from the grid rather than from the
     * matched recipe also means a shapeless match cannot consume the wrong cell.
     */
    public void consumeIngredients() {
        for (int i = 0; i < grid.length; i++) {
            if (!grid[i].isEmpty()) {
                grid[i] = grid[i].shrink(1);
            }
        }
        refreshResult();
    }

    /** Whether this menu belongs to the given block. */
    public boolean isAt(int x, int y, int z) {
        return blockX == x && blockY == y && blockZ == z;
    }
}
