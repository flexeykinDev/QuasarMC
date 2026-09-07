package dev.quasar.world.blockentity;

import dev.quasar.item.ItemStack;

/**
 * A container screen a player currently has open.
 *
 * <p>Holds the container's own slots. The player's inventory is not copied in: window slots past
 * the container map straight onto the player's real inventory, so a click cannot leave the two
 * disagreeing.
 */
public final class OpenContainer {

    private final int windowId;
    private final Containers.Kind kind;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final ItemStack[] items;

    public OpenContainer(int windowId, Containers.Kind kind, int blockX, int blockY, int blockZ,
                         ItemStack[] items) {
        this.windowId = windowId;
        this.kind = kind;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.items = items;
    }

    public int windowId() {
        return windowId;
    }

    public Containers.Kind kind() {
        return kind;
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

    public ItemStack[] items() {
        return items;
    }

    public ItemStack get(int slot) {
        return slot >= 0 && slot < items.length ? items[slot] : ItemStack.EMPTY;
    }

    public void set(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.length) {
            items[slot] = stack == null ? ItemStack.EMPTY : stack;
        }
    }

    /** Total slots in the window: the container, then the player's 27 main slots and 9 hotbar. */
    public int totalSlots() {
        return kind.slots() + 36;
    }
}
