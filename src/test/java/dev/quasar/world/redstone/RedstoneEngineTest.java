package dev.quasar.world.redstone;

import dev.quasar.engine.Ownership;
import dev.quasar.engine.Region;
import dev.quasar.engine.RegionManager;
import dev.quasar.world.Chunk;
import dev.quasar.world.World;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;
import dev.quasar.world.gen.FlatChunkGenerator;
import dev.quasar.world.light.LightProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redstone signal propagation.
 *
 * <p>Every test asserts a power level or a lit flag, never just that the engine ran. Redstone that
 * does nothing leaves a valid world behind, so "no exception" proves nothing whatsoever here.
 *
 * <p>Skipped when {@code blocks.json} is absent: redstone states cannot be resolved from the
 * built-in block table, which covers only the blocks this server generates with. Skipping loudly is
 * better than passing vacuously.
 */
class RedstoneEngineTest {

    private static final int SURFACE = 63;
    private static final int WIRE_Y = SURFACE + 1;

    private World world;
    private Region region;

    @BeforeAll
    static void buildTables() {
        BlockStateRegistry.loadFullTableIfPresent();
        LightProperties.build();
        RedstoneBlocks.build();
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(RedstoneBlocks.available(),
                "needs blocks.json for the full state table");

        Ownership.setStrict(false);
        world = new World("redstone-test", 0L, -64, 384, new FlatChunkGenerator(SURFACE), 1, null);
        RegionManager manager = new RegionManager(world, 0L);
        world.setRegionManager(manager);

        // Two chunks, so a wire can run past x=15 and actually cross a chunk boundary. One chunk
        // silently truncates the test: writes beyond it are dropped and reads answer stone, so a
        // "wire ran out" assertion passes for the wrong reason.
        for (int cx = 0; cx <= 1; cx++) {
            Chunk chunk = new Chunk(cx, 0, -64, 384);
            new FlatChunkGenerator(SURFACE).generate(chunk);
            world.putChunkForTesting(chunk);
        }

        Ownership.enterSafepoint();
        try {
            manager.requestChunkAdd(0, 0);
            manager.requestChunkAdd(1, 0);
            manager.applyPendingAtSafepointForTesting();
        } finally {
            Ownership.exitSafepoint();
        }
        region = manager.regionForChunk(0, 0);
        assertNotNull(region);
    }

    // ------------------------------------------------------------------------------------ dust

    @Test
    void dustLosesOneLevelPerBlock() {
        layWire(2, 12);
        int source = state("minecraft:redstone_block", Map.of());
        place(1, WIRE_Y, 8, source);

        drive();

        assertEquals(15, power(2), "the block adjacent to a source is at full strength");
        assertEquals(14, power(3), "and each step costs exactly one level");
        assertEquals(13, power(4));
    }

    /** Also crosses the chunk boundary at x=16, which is the region-safety claim in practice. */
    @Test
    void dustRunsOutAfterFifteenBlocks() {
        layWire(2, 20);
        place(1, WIRE_Y, 8, state("minecraft:redstone_block", Map.of()));

        drive();

        assertEquals(1, power(16), "fifteen blocks from the source is the last lit one");
        assertEquals(0, power(17), "and the wire is dead beyond that");
    }

    @Test
    void breakingTheSourceDrainsTheWire() {
        layWire(2, 12);
        place(1, WIRE_Y, 8, state("minecraft:redstone_block", Map.of()));
        drive();
        assertTrue(power(4) > 0, "precondition: the wire was powered");

        place(1, WIRE_Y, 8, Blocks.AIR);
        drive();

        assertEquals(0, power(4), "wire must fall back to zero when its source is gone");
    }

    // ---------------------------------------------------------------------------------- lever

    @Test
    void aLeverPowersAndUnpowersTheWire() {
        layWire(3, 12);
        // A lever on the floor beside the wire's first block.
        int leverOff = state("minecraft:lever",
                Map.of("face", "floor", "facing", "north", "powered", "false"));
        place(2, WIRE_Y, 8, leverOff);
        drive();
        assertEquals(0, power(3), "an unflipped lever powers nothing");

        place(2, WIRE_Y, 8, RedstoneBlocks.togglePowered(leverOff));
        drive();

        assertEquals(15, power(3), "flipping the lever should power the wire beside it");
    }

    // ----------------------------------------------------------------------------------- lamp

    @Test
    void aLampLightsWhenTheWireReachesIt() {
        layWire(2, 6);
        int lamp = state("minecraft:redstone_lamp", Map.of("lit", "false"));
        place(7, WIRE_Y, 8, lamp);
        place(1, WIRE_Y, 8, state("minecraft:redstone_block", Map.of()));

        drive();

        assertTrue(RedstoneBlocks.isLit(world.getBlockRaw(7, WIRE_Y, 8)),
                "a lamp at the end of a powered wire should be lit");
    }

    // ---------------------------------------------------------------------------------- torch

    @Test
    void aTorchInvertsItsInput() {
        // A torch on the side of a block, with the block driven by a lever.
        int block = Blocks.STONE;
        place(5, WIRE_Y, 8, block);
        int torch = state("minecraft:redstone_wall_torch", Map.of("facing", "east", "lit", "true"));
        place(6, WIRE_Y, 8, torch);

        drive();
        assertTrue(RedstoneBlocks.isLit(world.getBlockRaw(6, WIRE_Y, 8)),
                "an unpowered block leaves its torch burning");

        // Power the block the torch hangs on; the torch must go out.
        place(4, WIRE_Y, 8, state("minecraft:redstone_block", Map.of()));
        drive();

        assertEquals(false, RedstoneBlocks.isLit(world.getBlockRaw(6, WIRE_Y, 8)),
                "powering the block a torch hangs on must switch the torch off");
    }

    // -------------------------------------------------------------------------------- geometry

    /**
     * The claim the design rests on, pinned like the light engine's.
     *
     * <p>Redstone needs no owner for a "network" because nothing is derived from more than one
     * block's neighbours at a time. What makes that safe is that a neighbour is one block away and
     * therefore at most one chunk away, which is inside the region by construction.
     */
    @Test
    void everyDerivationIsPurelyLocal() {
        assertTrue(1 <= RegionManager.LINK_RADIUS,
                "a redstone update reads one block in every direction, which is at most one chunk;"
                        + " LINK_RADIUS must guarantee that chunk shares the region");
    }

    // --------------------------------------------------------------------------------- helpers

    /** Lays redstone dust along x, at z=8, on top of the flat world's surface. */
    private void layWire(int fromX, int count) {
        int dust = state("minecraft:redstone_wire",
                Map.of("east", "side", "north", "side", "power", "0", "south", "side",
                        "west", "side"));
        for (int x = fromX; x < fromX + count; x++) {
            place(x, WIRE_Y, 8, dust);
        }
    }

    /**
     * Sets a block and wakes redstone for it.
     *
     * <p>On the server {@code World.setBlock} does the waking itself, but only when called from
     * inside a region tick -- it finds the queue through {@code Region.current()}. A test writing
     * blocks directly gets no such thing, so it has to say so explicitly. The first version of this
     * test did not, and every assertion that used a redstone block read zero power from an engine
     * that had simply never been asked to do anything.
     */
    private void place(int x, int y, int z, int state) {
        world.setBlock(x, y, z, state);
        region.redstone().onBlockChanged(x, y, z);
    }

    private int power(int x) {
        return RedstoneBlocks.dustPower(world.getBlockRaw(x, WIRE_Y, 8));
    }

    private int state(String name, Map<String, String> properties) {
        int id = properties.isEmpty()
                ? BlockStateRegistry.defaultStateForBlock(name)
                : BlockStateRegistry.idFor(name, properties);
        assertTrue(id > 0, "could not resolve " + name + " " + properties);
        return id;
    }

    /** Runs enough region ticks for delays to fire and the signal to settle. */
    private void drive() {
        for (int tick = 0; tick < 40; tick++) {
            region.redstone().process(region, tick, 65536);
        }
    }
}
