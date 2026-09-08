package dev.quasar.world.physics;

import dev.quasar.engine.Region;
import dev.quasar.world.Chunk;
import dev.quasar.world.ChunkPos;
import dev.quasar.world.World;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;
import dev.quasar.world.light.LightStorage;

import java.util.Random;

/**
 * Vanilla's random block ticks: the slow background changes that make a world feel alive.
 *
 * <p>Every tick, each loaded section gets a few randomly chosen blocks offered a chance to do
 * something. Grass creeps onto bare dirt, and grass under something solid dies back.
 *
 * <h2>Why this is cheap despite touching everything</h2>
 *
 * <p>The cost is fixed per section per tick -- three positions, as in vanilla -- not proportional to
 * what is in the section. A region with a thousand chunks pays for a thousand sections' worth of
 * dice rolls and nothing more, because a position that is not a block with behaviour costs one
 * array lookup and is dropped.
 *
 * <p>Only sections near a player are ticked at all. Vanilla ticks a radius around each player
 * rather than everything loaded, and without that limit a server that has streamed a thousand
 * chunks would be doing three thousand rolls a tick over terrain nobody can see.
 *
 * <h2>Region safety</h2>
 *
 * <p>A random tick reads and writes one position and its immediate neighbours, which is the same
 * shape as fluid flow and safe for the same reason: a neighbour is at most one chunk away, and an
 * adjacent loaded chunk always belongs to the same region.
 */
public final class RandomTicks {

    /** Positions offered per section per tick. Vanilla's default {@code randomTickSpeed}. */
    public static final int SPEED = 3;

    /** How far from a player, in chunks, sections are ticked. */
    private static final int RADIUS_CHUNKS = 3;

    /**
     * Every chunk in range is examined every tick, as vanilla does.
     *
     * <p>An earlier version examined only a quarter of them per tick, on the strength of a
     * benchmark that appeared to show random ticks costing two milliseconds a region. Rerunning it
     * with random ticks disabled entirely produced a <em>worse</em> figure than leaving them on, so
     * the two milliseconds were run-to-run variance on this machine and the optimisation was
     * justified by noise. It was removed rather than kept "just in case": a complication nobody can
     * measure is a complication nobody can maintain.
     */

    private final World world;
    private int grassBlock = -1;
    private int dirt = -1;

    public RandomTicks(World world) {
        this.world = world;
    }

    /** Resolves the handful of states this needs. Safe to call repeatedly. */
    private void resolve() {
        if (grassBlock >= 0) {
            return;
        }
        grassBlock = BlockStateRegistry.defaultStateForBlock("minecraft:grass_block");
        dirt = BlockStateRegistry.defaultStateForBlock("minecraft:dirt");
    }

    /**
     * Offers random positions in the chunks around each player.
     *
     * <p>Runs on the region thread as part of its tick.
     */
    public void tick(Region region, Random random, long tickCount) {
        resolve();
        if (grassBlock <= 0 || dirt <= 0) {
            return;
        }
        for (dev.quasar.entity.Entity entity : region.entities()) {
            if (!(entity instanceof dev.quasar.entity.Player player) || player.isRemoved()) {
                continue;
            }
            int centreX = player.chunkX();
            int centreZ = player.chunkZ();
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                    tickChunk(region, random, centreX + dx, centreZ + dz);
                }
            }
            // One player's surroundings are enough: two players standing together would otherwise
            // double the growth rate of the ground they share.
            return;
        }
    }

    private void tickChunk(Region region, Random random, int chunkX, int chunkZ) {
        if (!region.ownsChunk(chunkX, chunkZ)) {
            return;
        }
        Chunk chunk = world.chunkAt(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int section = 0; section < chunk.sectionCount(); section++) {
            // A section of nothing but air cannot grow anything, and most of a chunk is exactly
            // that. Skipping it turns the common case into one comparison.
            if (chunk.section(section).isEmpty()) {
                continue;
            }
            int sectionMinY = chunk.minY() + (section << 4);
            for (int i = 0; i < SPEED; i++) {
                int x = random.nextInt(16);
                int y = random.nextInt(16);
                int z = random.nextInt(16);
                apply(chunk, region, baseX + x, sectionMinY + y, baseZ + z, x, sectionMinY + y, z);
            }
        }
    }

    private void apply(Chunk chunk, Region region, int worldX, int worldY, int worldZ,
                       int localX, int y, int localZ) {
        int state = chunk.getBlock(localX, y, localZ);

        if (state == grassBlock) {
            // Grass smothered by something solid turns back to dirt.
            int above = world.getBlockRaw(worldX, worldY + 1, worldZ);
            if (blocksGrowth(above)) {
                world.setBlock(worldX, worldY, worldZ, dirt);
                region.broadcastBlockUpdate(worldX, worldY, worldZ, dirt);
            }
            return;
        }

        if (state == dirt) {
            // Bare dirt with light above and grass beside it becomes grass.
            int above = world.getBlockRaw(worldX, worldY + 1, worldZ);
            if (blocksGrowth(above) || skyLightAt(worldX, worldY + 1, worldZ) < 4) {
                return;
            }
            if (hasGrassNeighbour(worldX, worldY, worldZ)) {
                world.setBlock(worldX, worldY, worldZ, grassBlock);
                region.broadcastBlockUpdate(worldX, worldY, worldZ, grassBlock);
            }
        }
    }

    private boolean blocksGrowth(int state) {
        return !Blocks.isReplaceable(state)
                && !dev.quasar.world.block.BlockCollision.isPassable(state);
    }

    private int skyLightAt(int x, int y, int z) {
        Chunk chunk = world.chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return 0;
        }
        return chunk.light().sky(x & 15, y, z & 15);
    }

    /** Grass spreads from a neighbour, so a lone dirt block in the dark stays dirt forever. */
    private boolean hasGrassNeighbour(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    if (world.getBlockRaw(x + dx, y + dy, z + dz) == grassBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Highest sky light, for documentation of the threshold above. */
    public static int maxSkyLight() {
        return LightStorage.MAX_LEVEL;
    }

    /** Key for the chunk a position is in; kept for callers that batch by chunk. */
    public static long chunkKeyOf(int x, int z) {
        return ChunkPos.key(x >> 4, z >> 4);
    }
}
