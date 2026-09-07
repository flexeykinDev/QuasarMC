package dev.quasar.world.blockentity;

import dev.quasar.item.ItemRegistry;
import dev.quasar.item.ItemStack;
import dev.quasar.nbt.Nbt;

/**
 * Reads and writes a container's {@code Items} list in block-entity NBT.
 *
 * <p>Item NBT stores an item by <em>name</em> and a slot index, unlike the protocol which uses
 * numeric IDs, so both directions go through {@link ItemRegistry}. Slots are sparse: empty ones are
 * simply absent from the list rather than written as empty entries.
 *
 * <p>Uses the 1.20.5+ shape — {@code id} plus a {@code count} int. Older worlds wrote a {@code Count}
 * byte, and are not read here.
 */
public final class ContainerIo {

    private ContainerIo() {}

    public static ItemStack[] readItems(Nbt.NbtCompound blockEntity, int slots) {
        ItemStack[] items = new ItemStack[slots];
        java.util.Arrays.fill(items, ItemStack.EMPTY);
        if (!(blockEntity.get("Items") instanceof Nbt.NbtList list)) {
            return items;
        }
        for (Nbt element : list.items()) {
            if (!(element instanceof Nbt.NbtCompound entry)) {
                continue;
            }
            if (!(entry.get("Slot") instanceof Nbt.NbtByte slot)
                    || !(entry.get("id") instanceof Nbt.NbtString id)) {
                continue;
            }
            int index = slot.value() & 0xFF;
            if (index >= slots) {
                continue;
            }
            int itemId = ItemRegistry.idForName(id.value());
            if (itemId < 0) {
                // An item this table does not know. Leaving the slot empty in memory would delete
                // it on the next write, so the whole container is better left alone; the caller
                // decides, and this signals it by leaving the slot empty and returning null.
                continue;
            }
            int count = entry.get("count") instanceof Nbt.NbtInt c ? c.value() : 1;
            items[index] = ItemStack.of(itemId, count);
        }
        return items;
    }

    /** Replaces the {@code Items} list, writing only the slots that hold something. */
    public static void writeItems(Nbt.NbtCompound blockEntity, ItemStack[] items) {
        Nbt.NbtList list = new Nbt.NbtList(Nbt.TAG_COMPOUND);
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack stack = items[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = ItemRegistry.nameFor(stack.itemId());
            if (name == null) {
                continue;
            }
            list.add(Nbt.compound()
                    .putByte("Slot", slot)
                    .putString("id", name)
                    .putInt("count", stack.count()));
        }
        blockEntity.put("Items", list);
    }

    /** A fresh, empty block entity for a container at these coordinates. */
    public static Nbt.NbtCompound newBlockEntity(Containers.Kind kind, int x, int y, int z) {
        Nbt.NbtCompound entity = Nbt.compound()
                .putString("id", kind.blockEntityId())
                .putInt("x", x)
                .putInt("y", y)
                .putInt("z", z);
        entity.put("Items", new Nbt.NbtList(Nbt.TAG_COMPOUND));
        return entity;
    }
}
