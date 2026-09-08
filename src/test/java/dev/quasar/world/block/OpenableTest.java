package dev.quasar.world.block;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doors, trapdoors and fence gates.
 *
 * <p>A door that will not open is not a subtle bug, but the reasons one might fail are: the
 * {@code open} property lives on both halves, and iron doors must not respond to a hand at all.
 */
class OpenableTest {

    @BeforeAll
    static void load() {
        BlockStateRegistry.loadFullTableIfPresent();
    }

    private static void needsTable() {
        Assumptions.assumeTrue(BlockStateRegistry.hasFullTable(), "needs blocks.json");
    }

    @Test
    void aWoodenDoorSwings() {
        needsTable();
        int shut = BlockStateRegistry.idFor("minecraft:oak_door", Map.of(
                "facing", "north", "half", "lower", "hinge", "left",
                "open", "false", "powered", "false"));

        assertTrue(Openable.isOpenable(shut));
        int open = Openable.toggleOpen(shut);
        assertTrue(Openable.isOpen(open), "right-clicking a door should open it");
        assertFalse(Openable.isOpen(Openable.toggleOpen(open)), "and again should shut it");
    }

    @Test
    void trapdoorsAndFenceGatesSwingToo() {
        needsTable();
        for (String name : new String[] {"minecraft:oak_trapdoor", "minecraft:oak_fence_gate"}) {
            int state = BlockStateRegistry.defaultStateForBlock(name);
            assertTrue(Openable.isOpenable(state), name + " should open by hand");
            assertEquals(true, Openable.isOpen(Openable.toggleOpen(state)));
        }
    }

    @Test
    void ironDoorsIgnoreAHand() {
        needsTable();
        // Vanilla opens these only under redstone power. A server that opened them by hand would
        // break the single thing they exist for.
        for (String name : new String[] {"minecraft:iron_door", "minecraft:iron_trapdoor"}) {
            int state = BlockStateRegistry.defaultStateForBlock(name);
            assertFalse(Openable.isOpenable(state), name + " must not open by hand");
        }
    }

    @Test
    void aPlainBlockDoesNotSwing() {
        needsTable();
        assertFalse(Openable.isOpenable(Blocks.STONE));
        assertEquals(Blocks.STONE, Openable.toggleOpen(Blocks.STONE),
                "a block with no open property must come back unchanged, not as some default");
    }
}
