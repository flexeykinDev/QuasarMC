package dev.quasar.world.gen;

import dev.quasar.world.Chunk;
import dev.quasar.world.block.Blocks;

/**
 * Rolling terrain from summed octaves of value noise.
 *
 * <p>Not a replica of vanilla worldgen — it is a deliberately self-contained heightmap generator
 * that produces varied terrain and costs a realistic few milliseconds per chunk, which is what
 * makes it useful for exercising the parallel generation pipeline.
 *
 * <p>Fully deterministic and stateless per call, so N threads generating N chunks never interact.
 */
public final class NoiseChunkGenerator implements ChunkGenerator {

    private static final int OCTAVES = 5;
    private static final double BASE_FREQUENCY = 1.0 / 220.0;
    private static final double LACUNARITY = 2.0;
    private static final double PERSISTENCE = 0.5;

    private final long seed;
    private final int seaLevel;
    private final int amplitude;

    public NoiseChunkGenerator(long seed, int seaLevel, int amplitude) {
        this.seed = seed;
        this.seaLevel = seaLevel;
        this.amplitude = amplitude;
    }

    @Override
    public void generate(Chunk chunk) {
        int minY = chunk.minY();
        int baseX = chunk.x() << 4;
        int baseZ = chunk.z() << 4;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int height = surfaceY(baseX + localX, baseZ + localZ);

                chunk.setBlock(localX, minY, localZ, Blocks.BEDROCK);
                for (int y = minY + 1; y <= height - 4; y++) {
                    chunk.setBlock(localX, y, localZ, Blocks.STONE);
                }
                for (int y = Math.max(minY + 1, height - 3); y < height; y++) {
                    chunk.setBlock(localX, y, localZ, Blocks.DIRT);
                }
                chunk.setBlock(localX, height, localZ, height < seaLevel ? Blocks.SAND : Blocks.GRASS_BLOCK);

                for (int y = height + 1; y <= seaLevel; y++) {
                    chunk.setBlock(localX, y, localZ, Blocks.WATER);
                }
            }
        }
        chunk.recalculateHeightmap();
    }

    @Override
    public int surfaceY(int blockX, int blockZ) {
        double total = 0;
        double frequency = BASE_FREQUENCY;
        double weight = 1.0;
        double normalisation = 0;

        for (int octave = 0; octave < OCTAVES; octave++) {
            total += valueNoise(blockX * frequency, blockZ * frequency, octave) * weight;
            normalisation += weight;
            frequency *= LACUNARITY;
            weight *= PERSISTENCE;
        }
        return seaLevel + (int) Math.round(total / normalisation * amplitude);
    }

    /** Bilinearly interpolated value noise with a smoothstep fade. */
    private double valueNoise(double x, double z, int octave) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = fade(x - x0);
        double fz = fade(z - z0);

        double n00 = hashToUnit(x0, z0, octave);
        double n10 = hashToUnit(x0 + 1, z0, octave);
        double n01 = hashToUnit(x0, z0 + 1, octave);
        double n11 = hashToUnit(x0 + 1, z0 + 1, octave);

        double top = n00 + fx * (n10 - n00);
        double bottom = n01 + fx * (n11 - n01);
        return top + fz * (bottom - top);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * Deterministic hash of a lattice point to [-1, 1).
     *
     * <p>The shift must be unsigned. An arithmetic {@code >>} yields [-1, 1) before the final
     * {@code - 1.0}, which lands the result in [-2, 0) — noise that is never positive, so every
     * column generates below sea level and the entire world comes out submerged.
     */
    private double hashToUnit(int x, int z, int octave) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) octave * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        // >>> 11 gives [0, 2^53); / 2^52 gives [0, 2); - 1.0 gives [-1, 1).
        return (h >>> 11) / (double) (1L << 52) - 1.0;
    }

    @Override
    public int biomeId() {
        return 0;
    }
}
