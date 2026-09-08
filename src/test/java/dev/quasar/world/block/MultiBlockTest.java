package dev.quasar.world.block;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Two-block structures: doors, beds and tall plants.
 *
 * <p>Placing one half of a door leaves the client drawing a floating bottom panel, and breaking one
 * half leaves the other standing in mid-air. Neither throws, and both are obvious on sight, so the
 * tests here are about the pairing arithmetic rather than about anything failing loudly.
 */
class MultiBlockTest {

    @BeforeAll
    static void load() {
        BlockStateRegistry.loadFullTableIfPresent();
    }

    private static void needsTable() {
        Assumptions.assumeTrue(BlockStateRegistry.hasFullTable(), "needs blocks.json");
    }

    private static int state(String name, Map<String, String> properties) {
        return BlockStateRegistry.idFor(name, properties);
    }

    @Test
    void aDoorsLowerHalfPairsWithTheBlockAbove() {
        needsTable();
        int lower = state("minecraft:oak_door", Map.of(
                "facing", "north", "half", "lower", "hinge", "left",
                "open", "false", "powered", "false"));

        MultiBlock.Partner partner = MultiBlock.partnerOf(lower, true);

        assertNotNull(partner, "a door is two blocks");
        assertEquals(0, partner.dx());
        assertEquals(1, partner.dy(), "the other half is directly above");
        assertEquals(0, partner.dz());
        assertEquals("upper", BlockStateRegistry.byId(partner.state()).properties().get("half"));
    }

    @Test
    void aDoorsUpperHalfPairsDownward() {
        needsTable();
        int upper = state("minecraft:oak_door", Map.of(
                "facing", "north", "half", "upper", "hinge", "left",
                "open", "false", "powered", "false"));

        MultiBlock.Partner partner = MultiBlock.partnerOf(upper, false);

        assertNotNull(partner);
        assertEquals(-1, partner.dy(), "breaking the top must reach down for the bottom");
    }

    @Test
    void aSlabIsNotTwoBlocks() {
        needsTable();
        // A slab carries a property called "half" too, but it means top/bottom, not lower/upper.
        // Keying on the property name alone would make every slab place a second copy of itself.
        int slab = state("minecraft:stone_slab", Map.of("type", "bottom", "waterlogged", "false"));
        assertNull(MultiBlock.partnerOf(slab, true), "a slab stands alone");

        int stairs = BlockStateRegistry.defaultStateForBlock("minecraft:oak_stairs");
        assertNull(MultiBlock.partnerOf(stairs, true), "so do stairs");
    }

    @Test
    void aBedRunsAlongItsFacing() {
        needsTable();
        int foot = state("minecraft:red_bed", Map.of(
                "facing", "east", "occupied", "false", "part", "foot"));

        MultiBlock.Partner partner = MultiBlock.partnerOf(foot, true);

        assertNotNull(partner, "a bed is two blocks");
        assertEquals(1, partner.dx(), "facing east puts the head one block east");
        assertEquals(0, partner.dy(), "a bed lies flat");
        assertEquals(0, partner.dz());
        assertEquals("head", BlockStateRegistry.byId(partner.state()).properties().get("part"));
    }

    @Test
    void anOrdinaryBlockHasNoPartner() {
        needsTable();
        assertNull(MultiBlock.partnerOf(Blocks.STONE, true));
    }
}
