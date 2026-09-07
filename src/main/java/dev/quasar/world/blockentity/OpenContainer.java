package dev.quasar.world.blockentity;

import dev.quasar.item.ItemStack;

/**
 * A container screen a player currently has open.
 *
 * <p>Backed by one block, or two for a double chest. The slots of each block are laid end to end,
 * so a double chest presents 54 slots while each half keeps its own block entity on disk — which is
 * what lets vanilla read the two chests back independently.
 *
 * <p>Holds only the container's own slots. Window slots past those map straight onto the player's
 * real inventory, so a click cannot leave the two disagreeing.
 */
public final class OpenContainer {

    private final int windowId;
    private final Containers.Kind kind;

    /** One {x, y, z} per backing block, in the same order as the slots. */
    private final int[][] blocks;

    private final int slotsPerBlock;
    private final ItemStack[] items;

    public OpenContainer(int windowId, Containers.Kind kind, int[][] blocks, int slotsPerBlock,
                         ItemStack[] items) {
        this.windowId = windowId;
        this.kind = kind;
        this.blocks = blocks;
        this.slotsPerBlock = slotsPerBlock;
        this.items = items;
    }

    public int windowId() {
        return windowId;
    }

    public Containers.Kind kind() {
        return kind;
    }

    public int[][] blocks() {
        return blocks;
    }

    public int slotsPerBlock() {
        return slotsPerBlock;
    }

    public ItemStack[] items() {
        return items;
    }

    /** Slots belonging to the container itself, across every backing block. */
    public int containerSlots() {
        return items.length;
    }

    public ItemStack get(int slot) {
        return slot >= 0 && slot < items.length ? items[slot] : ItemStack.EMPTY;
    }

    public void set(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.length) {
            items[slot] = stack == null ? ItemStack.EMPTY : stack;
        }
    }

    /** The container's slots, then the player's 27 main slots and 9 hotbar. */
    public int totalSlots() {
        return containerSlots() + 36;
    }

    /** The slice of slots stored in one backing block. */
    public ItemStack[] sliceFor(int blockIndex) {
        ItemStack[] slice = new ItemStack[slotsPerBlock];
        System.arraycopy(items, blockIndex * slotsPerBlock, slice, 0, slotsPerBlock);
        return slice;
    }
}
