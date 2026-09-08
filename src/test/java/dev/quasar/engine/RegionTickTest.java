package dev.quasar.engine;

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
 * That a real region tick actually runs physics and light.
 *
 * <p>The other tests drive {@code BlockPhysics} and {@code LightEngine} directly, which proves the
 * algorithms but not that anything calls them. That gap is not hypothetical: the scripted-client
 * scenario written to cover it could never reliably aim at the block it needed, so three runs in a
 * row "passed" while testing nothing. This closes it deterministically instead, by running the tick
 * the scheduler runs and asserting on the result.
 */
class RegionTickTest {

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
        world = new World("tick-test", 0L, -64, 384, new FlatChunkGenerator(SURFACE), 1, null);
        manager = new RegionManager(world, 0L);
        world.setRegionManager(manager);

        Chunk chunk = new Chunk(0, 0, -64, 384);
        new FlatChunkGenerator(SURFACE).generate(chunk);
        world.putChunkForTesting(chunk);

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

    @Test
    void tickingARegionMakesUnsupportedSandFall() {
        int[] spawned = {-1};
        world.setFallingBlockSpawner((state, x, y, z) -> spawned[0] = state);

        // Ticking once first, so the chunk's own seeding is done and out of the way.
        region.runTick();

        // Region.current() has to be set during the tick for World.setBlock to find the physics
        // queue at all, so this edit is made from inside one -- exactly as a player's edit is.
        region.post(() -> {
            world.setBlock(8, SURFACE + 1, 8, Blocks.SAND);
            world.setBlock(8, SURFACE, 8, Blocks.AIR);
        });
        region.runTick();
        region.runTick();

        assertEquals(Blocks.SAND, spawned[0],
                "a plain region tick should have run gravity and started the sand falling");
        assertEquals(Blocks.AIR, world.getBlockRaw(8, SURFACE + 1, 8));
    }

    @Test
    void tickingARegionFlowsWater() {
        region.runTick();
        region.post(() -> {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    world.setBlock(x, SURFACE + 1, z, Blocks.AIR);
                }
            }
            world.setBlock(8, SURFACE + 1, 8, Blocks.fluidState(false, 0));
        });

        // Water moves every fifth tick, so a handful of ticks is needed before anything shows.
        for (int i = 0; i < 60; i++) {
            region.runTick();
        }

        assertTrue(Blocks.isWater(world.getBlockRaw(9, SURFACE + 1, 8)),
                "water should have spread during ordinary region ticks");
    }

    @Test
    void tickingARegionLightsItsChunk() {
        region.runTick();
        region.runTick();

        Chunk chunk = world.chunkAt(0, 0);
        assertEquals(15, chunk.light().sky(8, SURFACE + 1, 8),
                "the tick should have seeded and propagated sky light for the adopted chunk");
    }
}
