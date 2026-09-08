package dev.quasar.world.storage;

import dev.quasar.entity.Entity;
import dev.quasar.entity.ItemEntity;
import dev.quasar.item.ItemRegistry;
import dev.quasar.item.ItemStack;
import dev.quasar.nbt.Nbt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entity persistence, round-tripped.
 *
 * <p>The failure mode here is quiet in both directions: a bad write loses a player's items at the
 * moment a chunk unloads, and a bad read either loses them again or resurrects them twice. Neither
 * throws. So these assert the actual values that come back out.
 */
class EntityIoTest {

    @BeforeAll
    static void load() {
        ItemRegistry.loadIfPresent();
    }

    private static void needsItems() {
        Assumptions.assumeTrue(ItemRegistry.idForName("minecraft:stone") > 0,
                "needs registries.json for item names");
    }

    @Test
    void anItemStackSurvivesTheRoundTrip() {
        needsItems();
        int stone = ItemRegistry.idForName("minecraft:stone");
        ItemEntity item = new ItemEntity(null, ItemStack.of(stone, 42), 10.5, 65.0, -3.5, 7);
        item.setAge(1234);

        Nbt.NbtCompound root = EntityIo.toNbt(List.<Entity>of(item), 0, -1, 4189);
        assertNotNull(root, "a chunk with an item in it must produce something to write");

        List<ItemEntity> back = EntityIo.fromNbt(null, root);

        assertEquals(1, back.size());
        ItemEntity restored = back.get(0);
        assertEquals(stone, restored.stack().itemId());
        assertEquals(42, restored.stack().count(), "the count must survive, or items evaporate");
        assertEquals(10.5, restored.x(), 1e-9);
        assertEquals(65.0, restored.y(), 1e-9);
        assertEquals(-3.5, restored.z(), 1e-9);
        assertEquals(1234, restored.age(),
                "age must survive, or a stack saved near its despawn gets a fresh five minutes");
        assertEquals(7, restored.pickupDelay());
    }

    @Test
    void anEmptyChunkWritesNothing() {
        // Returning an empty compound instead would put a file on disk for every chunk that ever
        // unloads, which for a world someone has walked across is a great many files holding
        // nothing at all.
        assertNull(EntityIo.toNbt(List.of(), 0, 0, 4189));
    }

    @Test
    void theChunkPositionIsRecorded() {
        needsItems();
        ItemEntity item = new ItemEntity(null,
                ItemStack.of(ItemRegistry.idForName("minecraft:stone"), 1), 0, 64, 0, 0);

        Nbt.NbtCompound root = EntityIo.toNbt(List.<Entity>of(item), 3, -7, 4189);

        assertNotNull(root);
        assertTrue(root.get("Position") instanceof Nbt.NbtIntArray, "vanilla keys entity chunks by Position");
        int[] position = ((Nbt.NbtIntArray) root.get("Position")).value();
        assertEquals(3, position[0]);
        assertEquals(-7, position[1]);
    }

    @Test
    void unknownEntityTypesAreSkippedNotGuessed() {
        Nbt.NbtList entities = new Nbt.NbtList();
        entities.add(Nbt.compound().putString("id", "minecraft:zombie"));
        Nbt.NbtCompound root = Nbt.compound().put("Entities", entities);

        // A world written by vanilla will contain mobs this server does not model. Reading one as
        // an item would be worse than ignoring it.
        assertEquals(0, EntityIo.fromNbt(null, root).size());
    }

    @Test
    void aStackOfAnUnknownItemIsDropped() {
        needsItems();
        Nbt.NbtList pos = new Nbt.NbtList();
        pos.add(new Nbt.NbtDouble(0));
        pos.add(new Nbt.NbtDouble(64));
        pos.add(new Nbt.NbtDouble(0));

        Nbt.NbtList entities = new Nbt.NbtList();
        entities.add(Nbt.compound()
                .putString("id", "minecraft:item")
                .put("Pos", pos)
                .put("Item", Nbt.compound()
                        .putString("id", "minecraft:not_a_real_item")
                        .put("count", new Nbt.NbtInt(1))));
        Nbt.NbtCompound root = Nbt.compound().put("Entities", entities);

        assertEquals(0, EntityIo.fromNbt(null, root).size(),
                "an item this server cannot name must not come back as item 0");
    }
}
