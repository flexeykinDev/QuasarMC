package dev.quasar.world.storage;

import dev.quasar.QuasarServer;
import dev.quasar.entity.Entity;
import dev.quasar.entity.ItemEntity;
import dev.quasar.item.ItemRegistry;
import dev.quasar.item.ItemStack;
import dev.quasar.nbt.Nbt;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes entities in vanilla's own layout.
 *
 * <p>Since 1.17 entities do not live in the chunk file: they have their own region files under
 * {@code entities/}, keyed by the same chunk coordinates. Writing them into the chunk instead would
 * work for this server and quietly lose them for anything else that opened the world, which is the
 * opposite of what Anvil support is for here.
 *
 * <p>Only item entities are persisted. A falling block exists for well under a second and is a
 * transitional state rather than a thing a world contains; the alternative -- writing them out and
 * resuming mid-fall -- adds a format for something no player would notice.
 */
public final class EntityIo {

    private EntityIo() {}

    private static final String ITEM_ID = "minecraft:item";

    /** Encodes the entities of one chunk, or null when there is nothing worth writing. */
    public static Nbt.NbtCompound toNbt(List<Entity> entities, int chunkX, int chunkZ,
                                        int dataVersion) {
        Nbt.NbtList list = new Nbt.NbtList();
        for (Entity entity : entities) {
            if (entity instanceof ItemEntity item && !item.stack().isEmpty()) {
                list.add(itemToNbt(item));
            }
        }
        if (list.size() == 0) {
            return null;
        }
        return Nbt.compound()
                .put("DataVersion", new Nbt.NbtInt(dataVersion))
                .put("Position", new Nbt.NbtIntArray(new int[] {chunkX, chunkZ}))
                .put("Entities", list);
    }

    private static Nbt.NbtCompound itemToNbt(ItemEntity item) {
        Nbt.NbtList pos = new Nbt.NbtList();
        pos.add(new Nbt.NbtDouble(item.x()));
        pos.add(new Nbt.NbtDouble(item.y()));
        pos.add(new Nbt.NbtDouble(item.z()));

        Nbt.NbtList motion = new Nbt.NbtList();
        motion.add(new Nbt.NbtDouble(0));
        motion.add(new Nbt.NbtDouble(0));
        motion.add(new Nbt.NbtDouble(0));

        Nbt.NbtList rotation = new Nbt.NbtList();
        rotation.add(new Nbt.NbtFloat(0));
        rotation.add(new Nbt.NbtFloat(0));

        // The item itself uses the modern component form: a namespaced id and a count, rather than
        // the numeric id and damage value that a pre-1.13 world would carry.
        Nbt.NbtCompound stack = Nbt.compound()
                .putString("id", ItemRegistry.nameFor(item.stack().itemId()))
                .put("count", new Nbt.NbtInt(item.stack().count()));

        return Nbt.compound()
                .putString("id", ITEM_ID)
                .put("Pos", pos)
                .put("Motion", motion)
                .put("Rotation", rotation)
                .put("Age", new Nbt.NbtShort((short) Math.min(item.age(), Short.MAX_VALUE)))
                .put("PickupDelay",
                        new Nbt.NbtShort((short) Math.min(item.pickupDelay(), Short.MAX_VALUE)))
                .put("Health", new Nbt.NbtShort((short) 5))
                .put("Item", stack);
    }

    /** Rebuilds the item entities of one chunk. Unknown entity types are skipped, not guessed. */
    public static List<ItemEntity> fromNbt(QuasarServer server, Nbt.NbtCompound root) {
        List<ItemEntity> out = new ArrayList<>();
        if (root == null || !(root.get("Entities") instanceof Nbt.NbtList list)) {
            return out;
        }
        for (Nbt tag : list.items()) {
            if (!(tag instanceof Nbt.NbtCompound entity)) {
                continue;
            }
            if (!(entity.get("id") instanceof Nbt.NbtString id) || !ITEM_ID.equals(id.value())) {
                // Anything this server does not model -- a mob written by vanilla, say -- is left
                // alone rather than half-loaded into something it is not.
                continue;
            }
            ItemEntity item = itemFromNbt(server, entity);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private static ItemEntity itemFromNbt(QuasarServer server, Nbt.NbtCompound entity) {
        if (!(entity.get("Pos") instanceof Nbt.NbtList pos) || pos.size() < 3) {
            return null;
        }
        if (!(entity.get("Item") instanceof Nbt.NbtCompound stackTag)
                || !(stackTag.get("id") instanceof Nbt.NbtString name)) {
            return null;
        }
        int itemId = ItemRegistry.idForName(name.value());
        if (itemId <= 0) {
            return null;
        }
        int count = stackTag.get("count") instanceof Nbt.NbtInt c ? c.value() : 1;
        if (count <= 0) {
            return null;
        }

        double x = doubleAt(pos, 0);
        double y = doubleAt(pos, 1);
        double z = doubleAt(pos, 2);
        int pickupDelay = entity.get("PickupDelay") instanceof Nbt.NbtShort d ? d.value() : 0;

        ItemEntity item = new ItemEntity(server, ItemStack.of(itemId, count), x, y, z, pickupDelay);
        // Age is restored so a stack that has already waited four minutes does not get another
        // five: an item saved near its despawn should still despawn on time.
        if (entity.get("Age") instanceof Nbt.NbtShort age) {
            item.setAge(Math.max(0, age.value()));
        }
        return item;
    }

    private static double doubleAt(Nbt.NbtList list, int index) {
        return list.items().get(index) instanceof Nbt.NbtDouble d ? d.value() : 0;
    }
}
