package dev.quasar.world.physics;

import dev.quasar.engine.Ownership;
import dev.quasar.engine.Region;
import dev.quasar.engine.RegionManager;
import dev.quasar.world.Chunk;
import dev.quasar.world.World;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;
import dev.quasar.world.gen.FlatChunkGenerator;
import dev.quasar.world.light.LightProperties;
import dev.quasar.world.redstone.RedstoneBlocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gravity and fluid flow.
 *
 * <p>Asserts positions and levels rather than "it ran". Physics that does nothing at all leaves a
 * perfectly valid world behind -- just a static one -- so a test that only checks for the absence of
 * an exception would pass against an engine that was never wired up.
 */
class BlockPhysicsTest {

    private static final int SURFACE = 63;

    private World world;
    private RegionManager manager;
    private Region region;

    @BeforeAll
    static void buildTables() {
        BlockStateRegistry.loadFullTableIfPresent();
        LightProperties.build();
        RedstoneBlocks.build();
    }

    @BeforeEach
    void setUp() {
        Ownership.setStrict(false);
        world = new World("physics-test", 0L, -64, 384, new FlatChunkGenerator(SURFACE), 1, null);
        manager = new RegionManager(world, 0L);
        world.setRegionManager(manager);

        generate(0, 0);
        Ownership.enterSafepoint();
        try {
            manager.requestChunkAdd(0, 0);
            manager.applyPendingAtSafepointForTesting();
        } finally {
            Ownership.exitSafepoint();
        }
        region = manager.regionForChunk(0, 0);
        assertNotNull(region);
    }

    // -------------------------------------------------------------------------------- gravity

    @Test
    void unsupportedSandBecomesAFallingBlock() {
        // A column of air under a sand block, as if the block beneath had just been mined.
        world.setBlock(8, SURFACE + 1, 8, Blocks.SAND);
        world.setBlock(8, SURFACE, 8, Blocks.AIR);

        int[] spawned = new int[1];
        world.setFallingBlockSpawner((state, x, y, z) -> spawned[0] = state);

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(8, SURFACE, 8);
        physics.process(region, 0, 4096);

        assertEquals(Blocks.SAND, spawned[0], "sand with air beneath should have become an entity");
        assertEquals(Blocks.AIR, world.getBlockRaw(8, SURFACE + 1, 8),
                "and the original block should be gone");
    }

    @Test
    void supportedSandDoesNotMove() {
        world.setBlock(8, SURFACE + 1, 8, Blocks.SAND);

        int[] spawned = {-1};
        world.setFallingBlockSpawner((state, x, y, z) -> spawned[0] = state);

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(8, SURFACE + 1, 8);
        physics.process(region, 0, 4096);

        assertEquals(-1, spawned[0], "sand resting on solid ground must not fall");
        assertEquals(Blocks.SAND, world.getBlockRaw(8, SURFACE + 1, 8));
    }

    @Test
    void sandDoesNotFallIntoAnUnloadedChunk() {
        // Chunk 40,40 was never generated. Reading it as air would let physics act on a chunk
        // nobody owns, and the write would then be silently dropped.
        int x = 640;
        int z = 640;
        assertEquals(Blocks.STONE, world.getBlockRaw(x, SURFACE, z),
                "an unloaded chunk must read as solid, not as air");
    }

    // --------------------------------------------------------------------------------- fluids

    @Test
    void waterSpreadsSidewaysAndThins() {
        clearAbove();
        world.setBlock(8, SURFACE + 1, 8, Blocks.fluidState(false, 0)); // a source

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(8, SURFACE + 1, 8);
        drain(physics);

        int beside = world.getBlockRaw(9, SURFACE + 1, 8);
        assertTrue(Blocks.isWater(beside), "water should have spread one block sideways");
        assertEquals(1, Blocks.fluidLevel(beside), "the first step away from a source is level 1");

        int further = world.getBlockRaw(10, SURFACE + 1, 8);
        assertEquals(2, Blocks.fluidLevel(further), "and it keeps thinning with distance");
    }

    @Test
    void waterStopsAfterSevenBlocks() {
        clearAbove();
        // Sourced at x=4 so that both the seventh and eighth block stay inside this chunk. Putting
        // it at x=8 pushed the eighth block into chunk 1, which is not loaded and therefore reads
        // as stone -- the test then compared air with stone and failed for the wrong reason.
        world.setBlock(4, SURFACE + 1, 8, Blocks.fluidState(false, 0));

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(4, SURFACE + 1, 8);
        drain(physics);

        // Vanilla water reaches seven blocks from a source and no further.
        assertTrue(Blocks.isWater(world.getBlockRaw(11, SURFACE + 1, 8)),
                "seven blocks from the source should still be wet");
        assertEquals(Blocks.AIR, world.getBlockRaw(12, SURFACE + 1, 8),
                "eight blocks away must stay dry");
    }

    @Test
    void waterFallsBeforeItSpreads() {
        clearAbove();
        // A hole in the floor directly under the source.
        world.setBlock(8, SURFACE, 8, Blocks.AIR);
        world.setBlock(8, SURFACE - 1, 8, Blocks.AIR);
        world.setBlock(8, SURFACE + 1, 8, Blocks.fluidState(false, 0));

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(8, SURFACE + 1, 8);
        drain(physics);

        assertTrue(Blocks.isWater(world.getBlockRaw(8, SURFACE - 1, 8)),
                "water should have fallen to the bottom of the hole");
    }

    @Test
    void removingTheSourceDrainsTheFlow() {
        clearAbove();
        world.setBlock(8, SURFACE + 1, 8, Blocks.fluidState(false, 0));

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(8, SURFACE + 1, 8);
        drain(physics);
        assertTrue(Blocks.isWater(world.getBlockRaw(10, SURFACE + 1, 8)), "precondition: it flowed");

        // Take the source away. Every dependent level should fail to justify itself and vanish --
        // which is the whole reason levels are derived from neighbours rather than pushed outwards.
        world.setBlock(8, SURFACE + 1, 8, Blocks.AIR);
        physics.onBlockChanged(8, SURFACE + 1, 8);
        drain(physics);

        assertEquals(Blocks.AIR, world.getBlockRaw(10, SURFACE + 1, 8),
                "flowing water must drain when its source is gone, not linger");
    }

    @Test
    void lavaSpreadsLessFarThanWater() {
        clearAbove();
        world.setBlock(8, SURFACE + 1, 8, Blocks.fluidState(true, 0));

        BlockPhysics physics = region.physics();
        physics.onBlockChanged(8, SURFACE + 1, 8);
        drain(physics);

        assertTrue(Blocks.isLava(world.getBlockRaw(11, SURFACE + 1, 8)),
                "lava reaches three blocks");
        assertEquals(Blocks.AIR, world.getBlockRaw(12, SURFACE + 1, 8),
                "but not four, unlike water");
    }

    // --------------------------------------------------------------------------------- helpers

    /** Runs enough ticks for any flow to settle, stepping the tick counter so both rates fire. */
    private void drain(BlockPhysics physics) {
        for (long tick = 0; tick < 2000 && physics.hasWork(); tick++) {
            physics.process(region, tick * BlockPhysics.LAVA_PERIOD, 65536);
        }
    }

    /** Empties the two layers above the surface, so flow is not fighting the terrain. */
    private void clearAbove() {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.setBlock(x, SURFACE + 1, z, Blocks.AIR);
                world.setBlock(x, SURFACE + 2, z, Blocks.AIR);
            }
        }
    }

    private void generate(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(chunkX, chunkZ, -64, 384);
        new FlatChunkGenerator(SURFACE).generate(chunk);
        world.putChunkForTesting(chunk);
    }
}
