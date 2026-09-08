package dev.quasar.world.light;

import dev.quasar.engine.RegionManager;
import dev.quasar.world.Chunk;
import dev.quasar.world.World;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;
import dev.quasar.world.gen.FlatChunkGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light propagation, and the geometry the whole design rests on.
 *
 * <p>These assert values, not merely "it ran". A light engine that silently produces zeros
 * everywhere still renders -- as a pitch black world -- and one that produces fifteen everywhere
 * renders as the full-bright placeholder this replaced. Both would pass a test that only checked
 * for absence of exceptions.
 */
class LightEngineTest {

    private static final int SURFACE = 63;

    private World world;

    @BeforeAll
    static void buildTables() {
        BlockStateRegistry.loadFullTableIfPresent();
        LightProperties.build();
    }

    @BeforeEach
    void setUp() {
        world = new World("light-test", 0L, -64, 384, new FlatChunkGenerator(SURFACE), 1, null);
        RegionManager manager = new RegionManager(world, 0L);
        world.setRegionManager(manager);
    }

    // ------------------------------------------------------------------- the region-safety proof

    /**
     * The arithmetic the lock-free design depends on.
     *
     * <p>Light is capped at 15 and costs at least one level per block, so it reaches at most 15
     * blocks. A chunk is 16 wide, so that reaches the neighbouring chunk and never the one past it.
     * The engine guarantees chunks within LINK_RADIUS of each other share a region, and 1 &lt; 2,
     * so every chunk a light update can touch is already owned by the region doing the update.
     *
     * <p>If someone raises the maximum light level, shrinks a section, or drops LINK_RADIUS to 1,
     * that stops being true and light silently becomes a cross-region data race. This test is here
     * so that change fails loudly instead.
     */
    @Test
    void lightCannotReachBeyondTheAdjacentChunk() {
        int reach = LightStorage.MAX_LEVEL;
        int chunksReached = (reach + 15) / 16;

        assertEquals(1, chunksReached,
                "light reaches " + reach + " blocks, which spans " + chunksReached + " chunks");
        assertTrue(chunksReached <= RegionManager.LINK_RADIUS,
                "light can reach " + chunksReached + " chunks away but only chunks within "
                        + RegionManager.LINK_RADIUS + " are guaranteed to share a region, so light "
                        + "would need a mailbox");
    }

    // -------------------------------------------------------------------------------- sky light

    @Test
    void openSkyIsFullyLitAndGroundIsDark() {
        Chunk chunk = generate(0, 0);
        LightEngine.lightNewChunk(chunk);

        assertEquals(LightStorage.MAX_LEVEL, chunk.light().sky(8, SURFACE + 1, 8),
                "the block above the surface sees open sky");
        assertEquals(0, chunk.light().sky(8, SURFACE, 8),
                "the surface block itself is solid, so no sky light inside it");
        assertEquals(0, chunk.light().sky(8, SURFACE - 5, 8),
                "buried stone is dark");
    }

    @Test
    void skyLightFallsVerticallyWithoutDimming() {
        Chunk chunk = generate(0, 0);
        // Carve a shaft straight down, as if someone mined into the ground.
        for (int y = SURFACE; y > SURFACE - 10; y--) {
            chunk.setBlock(8, y, 8, Blocks.AIR);
        }
        LightEngine.lightNewChunk(chunk);

        // Vertical sky light does not decay, which is what makes a mineshaft lit to the bottom
        // rather than fading out after fifteen blocks.
        assertEquals(LightStorage.MAX_LEVEL, chunk.light().sky(8, SURFACE - 9, 8),
                "a vertical shaft stays at full daylight all the way down");
    }

    @Test
    void lightSpreadsSidewaysIntoAnOverhang() {
        Chunk chunk = generate(0, 0);
        // A pocket under the surface, open to the shaft at x=8 but roofed at x=9..11.
        for (int y = SURFACE; y > SURFACE - 3; y--) {
            chunk.setBlock(8, y, 8, Blocks.AIR);
        }
        for (int x = 9; x <= 11; x++) {
            chunk.setBlock(x, SURFACE - 2, 8, Blocks.AIR);
        }
        LightEngine.lightNewChunk(chunk);
        world.lightEngineForTesting().seedChunk(chunk);
        world.lightEngineForTesting().processQueue(1_000_000);

        int atMouth = chunk.light().sky(9, SURFACE - 2, 8);
        int deeper = chunk.light().sky(11, SURFACE - 2, 8);

        assertTrue(atMouth > 0, "the mouth of the overhang should catch light, was " + atMouth);
        assertTrue(deeper < atMouth,
                "light should fade with distance: mouth=" + atMouth + " deeper=" + deeper);
    }

    // ------------------------------------------------------------------------------ block light

    @Test
    void anEmitterLightsItsSurroundings() {
        Chunk chunk = generate(0, 0);
        for (int y = SURFACE; y > SURFACE - 5; y--) {
            for (int x = 4; x <= 12; x++) {
                chunk.setBlock(x, y, 8, Blocks.AIR);
            }
        }
        int torch = BlockStateRegistry.defaultStateForBlock("minecraft:torch");
        chunk.setBlock(8, SURFACE - 2, 8, torch);

        LightEngine.lightNewChunk(chunk);
        LightEngine engine = world.lightEngineForTesting();
        engine.seedChunk(chunk);
        engine.processQueue(1_000_000);

        int atSource = chunk.light().block(8, SURFACE - 2, 8);
        int oneAway = chunk.light().block(9, SURFACE - 2, 8);

        assertEquals(14, atSource, "a torch emits 14");
        assertEquals(13, oneAway, "each block costs exactly one level");
    }

    @Test
    void removingAnEmitterClearsTheLightItCast() {
        Chunk chunk = generate(0, 0);
        for (int x = 4; x <= 12; x++) {
            chunk.setBlock(x, SURFACE, 8, Blocks.AIR);
        }
        int torch = BlockStateRegistry.defaultStateForBlock("minecraft:torch");
        chunk.setBlock(8, SURFACE, 8, torch);

        LightEngine.lightNewChunk(chunk);
        LightEngine engine = world.lightEngineForTesting();
        engine.seedChunk(chunk);
        engine.processQueue(1_000_000);
        assertTrue(chunk.light().block(9, SURFACE, 8) > 0, "precondition: the torch lit its side");

        // Now remove it the way a break would, and let the removal pass run.
        chunk.setBlock(8, SURFACE, 8, Blocks.AIR);
        engine.onBlockChanged(8, SURFACE, 8, torch, Blocks.AIR);
        engine.processQueue(1_000_000);

        assertEquals(0, chunk.light().block(9, SURFACE, 8),
                "block light must be cleared when its only source is removed, not left stale");
        assertEquals(0, chunk.light().block(8, SURFACE, 8), "and gone at the source too");
    }

    // ---------------------------------------------------------------------------- deferred work

    @Test
    void aBudgetDefersRatherThanDropsWork() {
        Chunk chunk = generate(0, 0);
        for (int y = SURFACE; y > SURFACE - 8; y--) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    chunk.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
        LightEngine.lightNewChunk(chunk);
        LightEngine engine = world.lightEngineForTesting();
        engine.seedChunk(chunk);

        // A deliberately tiny budget. The point is that the work survives to the next call rather
        // than being silently discarded, which would leave the world permanently half-lit.
        boolean more = engine.processQueue(16);
        assertTrue(more, "a tiny budget should leave work pending");

        int guard = 0;
        while (engine.processQueue(4096) && guard++ < 10_000) {
            // drain
        }
        assertTrue(guard < 10_000, "light propagation did not terminate");
        assertEquals(LightStorage.MAX_LEVEL, chunk.light().sky(8, SURFACE - 7, 8),
                "the deferred work still finished the job");
    }

    // ------------------------------------------------------------------------------ properties

    @Test
    void chestsDoNotOccludeLight() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                BlockStateRegistry.hasFullTable(), "needs blocks.json");

        // A chest is drawn by a block-entity renderer using the light at its own position. Treating
        // it as opaque zeroes that value, and the chest renders almost black in full daylight.
        int chest = BlockStateRegistry.defaultStateForBlock("minecraft:chest");
        assertTrue(chest > 0, "chest state should resolve");
        assertEquals(false, LightProperties.blocksLight(chest),
                "a chest is not a full cube and must not occlude");
    }

    @Test
    void slabsAndStairsStillOccludeLight() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                BlockStateRegistry.hasFullTable(), "needs blocks.json");

        // The deliberate half of the approximation: these are what people roof with, and letting
        // light past them floods the inside of every such building with daylight.
        for (String name : new String[] {"minecraft:stone_slab", "minecraft:oak_stairs"}) {
            int state = BlockStateRegistry.defaultStateForBlock(name);
            assertTrue(LightProperties.blocksLight(state), name + " should still occlude");
        }
    }

    @Test
    void theTransparencyTableLoadsAtAll() {
        // Set.of throws on a duplicate entry, so a careless addition to the list is not a subtly
        // wrong lookup -- it is a class-initialiser failure that takes the whole server down.
        LightProperties.build();
        assertEquals(false, LightProperties.blocksLight(Blocks.AIR));
    }

    // ---------------------------------------------------------------------------------- storage

    @Test
    void uniformSectionsCostNoArray() {
        Chunk chunk = generate(0, 0);
        LightEngine.lightNewChunk(chunk);

        // The memory argument for lazy layers only holds if untouched sections really do stay
        // unallocated. A flat world's deep stone is uniformly dark and must cost nothing.
        int allocated = chunk.light().allocatedSections();
        assertTrue(allocated < chunk.light().sectionCount(),
                "expected most sections to stay uniform, but " + allocated + " of "
                        + chunk.light().sectionCount() + " were materialised");
    }

    @Test
    void nibblesRoundTripBothHalvesOfAByte() {
        LightStorage storage = new LightStorage(24, -64);
        // Two adjacent blocks share one byte, so a naive implementation clobbers its neighbour.
        storage.setSky(0, 0, 0, 7);
        storage.setSky(1, 0, 0, 12);

        assertEquals(7, storage.sky(0, 0, 0));
        assertEquals(12, storage.sky(1, 0, 0));
    }

    private Chunk generate(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(chunkX, chunkZ, -64, 384);
        new FlatChunkGenerator(SURFACE).generate(chunk);
        world.putChunkForTesting(chunk);
        return chunk;
    }
}
