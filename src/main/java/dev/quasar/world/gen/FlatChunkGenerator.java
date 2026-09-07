package dev.quasar.world.gen;

import dev.quasar.world.Chunk;
import dev.quasar.world.block.Blocks;

/**
 * A superflat generator. Cheap on purpose: use it when measuring the tick engine, so generation
 * cost does not drown out the numbers you are actually trying to read.
 */
public final class FlatChunkGenerator implements ChunkGenerator {

    private final int surfaceY;

    public FlatChunkGenerator(int surfaceY) {
        this.surfaceY = surfaceY;
    }

    @Override
    public void generate(Chunk chunk) {
        int minY = chunk.minY();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                chunk.setBlock(localX, minY, localZ, Blocks.BEDROCK);
                for (int y = minY + 1; y < surfaceY; y++) {
                    chunk.setBlock(localX, y, localZ, Blocks.STONE);
                }
                chunk.setBlock(localX, surfaceY, localZ, Blocks.GRASS_BLOCK);
            }
        }
        chunk.recalculateHeightmap();
    }

    @Override
    public int surfaceY(int blockX, int blockZ) {
        // The topmost solid block, matching the grass layer generate() writes. It previously
        // returned one higher, disagreeing with the noise generator about what this means.
        return surfaceY;
    }
}
