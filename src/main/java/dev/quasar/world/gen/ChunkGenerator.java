package dev.quasar.world.gen;

import dev.quasar.world.Chunk;

/**
 * Fills a freshly created chunk with terrain.
 *
 * <p>Implementations run on the world-gen pool, on many threads at once, against chunks nobody else
 * can see yet. They must therefore be pure with respect to shared state: no reaching into
 * neighbouring chunks, no shared mutable scratch buffers.
 */
public interface ChunkGenerator {

    void generate(Chunk chunk);

    /** Biome ID written for every section of chunks from this generator. */
    default int biomeId() {
        return 0;
    }

    /**
     * Y of the topmost solid block in a column — the block you would stand <em>on</em>, not the
     * space you stand in. Callers add one to get a standing position.
     *
     * <p>Must agree with what {@link #generate} actually writes, and must be cheap: spawn search
     * calls it thousands of times without generating any chunks.
     */
    default int surfaceY(int blockX, int blockZ) {
        return 64;
    }
}
