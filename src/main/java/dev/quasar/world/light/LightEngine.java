package dev.quasar.world.light;

import dev.quasar.world.Chunk;
import dev.quasar.world.ChunkPos;
import dev.quasar.world.World;
import dev.quasar.world.block.Blocks;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Propagating sky and block light.
 *
 * <h2>Why this needs no cross-region coordination</h2>
 *
 * <p>Light is capped at 15 and loses at least one level per block, so a change can influence blocks
 * at most 15 away. A section is 16 wide. Fifteen blocks from the very edge of a chunk therefore
 * lands in the <em>adjacent</em> chunk and cannot reach the one beyond it: the blast radius of any
 * light update is the 3x3 chunks around it.
 *
 * <p>The engine already guarantees that chunks within {@code LINK_RADIUS} (2) of each other belong
 * to the same region. 1 is less than 2, so every chunk a light update can touch is owned by the
 * region already running that update. Light needs no mailbox and no ownership transfer -- it falls
 * out of the same geometry that makes block editing lock-free, and
 * {@code LightEngineTest.lightCannotReachBeyondTheAdjacentChunk} pins the arithmetic so a future
 * change to either constant fails loudly rather than quietly introducing a race.
 *
 * <h2>Deferred work</h2>
 *
 * <p>Propagation is breadth-first over a queue owned by the calling region. Callers drain it with a
 * budget ({@link #processQueue}), so a cascade -- breaking the one block that lets daylight into a
 * long cave -- is spread across ticks instead of spiking one of them. That is the "predictable
 * latency" half of the design: a region's tick cost from light is bounded by the budget, never by
 * the size of the cascade.
 */
public final class LightEngine {

    /** Blocks visited per region tick before the rest is deferred to the next one. */
    public static final int DEFAULT_BUDGET = 8192;

    private final World world;

    /**
     * Pending propagation, as packed block positions.
     *
     * <p>Owned by whichever region is draining it. Every position in it is inside that region by
     * the argument above, so this needs no synchronisation.
     */
    private final LongArrayFIFOQueue increaseQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue decreaseQueue = new LongArrayFIFOQueue();

    /** Chunks whose light changed since the last flush, so only those are resent. */
    private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();

    private long blocksVisited;

    public LightEngine(World world) {
        this.world = world;
    }

    public long blocksVisited() {
        return blocksVisited;
    }

    public boolean hasWork() {
        return !increaseQueue.isEmpty() || !decreaseQueue.isEmpty();
    }

    /** Queues a removal. Two entries: the position, then the level being cleared. */
    private void enqueueDecrease(int x, int y, int z, int level, boolean sky) {
        if (level <= 0) {
            return;
        }
        decreaseQueue.enqueue(pack(x, y, z));
        decreaseQueue.enqueue(sky ? (level | SKY_FLAG_LEVEL) : level);
    }

    public int pendingCount() {
        return increaseQueue.size() + decreaseQueue.size() / 2;
    }

    // ------------------------------------------------------------------------ initial lighting

    /**
     * Lights a freshly generated or loaded chunk.
     *
     * <p>Runs on whichever thread produced the chunk, before it is published to a region -- so it
     * touches nothing anyone else can see, and deliberately does not spread into neighbours. Edges
     * are stitched afterwards by {@link #seedChunkEdges}, once the neighbours are known to be
     * loaded and owned by the same region.
     */
    public static void lightNewChunk(Chunk chunk) {
        LightStorage light = chunk.light();
        int minY = chunk.minY();
        int maxY = chunk.maxY();

        // Everything above the highest occluding block is open sky in every column. Filling those
        // sections as uniform 15 costs one byte each; walking them would write 15 into a quarter of
        // a million positions per chunk and materialise every array on the way, which is most of
        // the memory the lazy layers exist to avoid.
        int highest = chunk.highestOccludingY();
        int firstOpenSection = ((Math.max(highest, minY - 1) + 1 - minY) >> 4) + 1;
        for (int layer = Math.max(firstOpenSection + 1, 0); layer < light.sectionCount(); layer++) {
            light.fillSkySection(layer, LightStorage.MAX_LEVEL);
        }

        // Only the band from the world floor up to just above the terrain can vary, so that is the
        // only part walked per column.
        int bandTop = Math.min(maxY, ((firstOpenSection) << 4) + minY + 15);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                // Straight down from the sky at full strength until something stops it. Vertical
                // sky light does not dim, which is why a hole in the roof lights the floor below
                // just as brightly as open ground.
                int level = LightStorage.MAX_LEVEL;
                for (int y = bandTop; y >= minY; y--) {
                    int state = chunk.getBlock(localX, y, localZ);
                    if (LightProperties.blocksLight(state)) {
                        level = 0;
                    } else {
                        level = Math.max(0, level - LightProperties.opacity(state));
                    }
                    if (level == 0) {
                        // Everything below is dark until something re-lights it sideways, which the
                        // horizontal pass handles.
                        break;
                    }
                    light.setSky(localX, y, localZ, level);
                }
            }
        }
    }

    /**
     * Queues everything in a chunk that should spread light outwards.
     *
     * <p>Called once the chunk belongs to a region. The initial pass lights each column in
     * isolation; this is what makes light flow sideways into overhangs and caves, and across the
     * chunk border into neighbours.
     */
    public void seedChunk(Chunk chunk) {
        int minY = chunk.minY();
        int baseX = chunk.x() << 4;
        int baseZ = chunk.z() << 4;
        LightStorage light = chunk.light();

        // Only the band up to just above the terrain can hold a gradient: above it every column is
        // uniformly daylit, so there is nothing for light to flow into. Walking the full height
        // instead would be a quarter of a million positions per chunk, and enqueueing every lit one
        // of them would be tens of thousands of queue entries that immediately find nothing to do.
        int bandTop = Math.min(chunk.maxY(), chunk.highestOccludingY() + 1);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                boolean onBorder = localX == 0 || localX == 15 || localZ == 0 || localZ == 15;
                for (int y = minY; y <= bandTop; y++) {
                    int worldX = baseX + localX;
                    int worldZ = baseZ + localZ;
                    int state = chunk.getBlock(localX, y, localZ);

                    int emission = LightProperties.emission(state);
                    if (emission > 0) {
                        light.setBlock(localX, y, localZ, emission);
                        increaseQueue.enqueue(pack(worldX, y, worldZ));
                        continue;
                    }

                    int sky = light.sky(localX, y, localZ);
                    if (sky <= 0) {
                        continue;
                    }
                    // A border block always seeds: the neighbouring chunk may have loaded after
                    // this one, and its edge needs light flowing in from here.
                    if (onBorder || hasDarkerHorizontalNeighbour(chunk, localX, y, localZ, sky)) {
                        increaseQueue.enqueue(pack(worldX, y, worldZ));
                    }
                }
            }
        }
        dirtyChunks.add(ChunkPos.key(chunk.x(), chunk.z()));
    }

    /** True when light here could still flow sideways into something dimmer. */
    private static boolean hasDarkerHorizontalNeighbour(Chunk chunk, int localX, int y, int localZ,
                                                        int sky) {
        LightStorage light = chunk.light();
        return (localX > 0 && light.sky(localX - 1, y, localZ) < sky - 1)
                || (localX < 15 && light.sky(localX + 1, y, localZ) < sky - 1)
                || (localZ > 0 && light.sky(localX, y, localZ - 1) < sky - 1)
                || (localZ < 15 && light.sky(localX, y, localZ + 1) < sky - 1);
    }

    // --------------------------------------------------------------------------- block changes

    /**
     * Records that a block changed, and queues whatever relighting that implies.
     *
     * <p>Both directions are needed. Placing an opaque block removes light, which means clearing a
     * region of the light map and then letting the surviving sources flood back in; breaking one
     * adds light, which is a plain spread.
     */
    public void onBlockChanged(int x, int y, int z, int oldState, int newState) {
        Chunk chunk = world.chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return;
        }
        int localX = x & 15;
        int localZ = z & 15;
        LightStorage light = chunk.light();

        int oldEmission = LightProperties.emission(oldState);
        int newEmission = LightProperties.emission(newState);

        // Whatever was here is no longer authoritative: clear it and let the neighbours re-light
        // this spot. Doing it this way -- rather than trying to work out which sources contributed
        // -- is what makes removal correct without tracking provenance per block.
        if (LightProperties.blocksLight(newState) || oldEmission > newEmission) {
            enqueueDecrease(x, y, z, light.block(localX, y, localZ), false);
            light.setBlock(localX, y, localZ, 0);

            enqueueDecrease(x, y, z, light.sky(localX, y, localZ), true);
            light.setSky(localX, y, localZ, 0);
        }

        if (newEmission > 0) {
            light.setBlock(localX, y, localZ, newEmission);
            increaseQueue.enqueue(pack(x, y, z));
        }

        if (!LightProperties.blocksLight(newState)) {
            // Newly transparent: pull light in from every side, including straight down from the
            // sky, by re-queueing the neighbours as sources.
            for (int face = 0; face < 6; face++) {
                int nx = x + FACE_X[face];
                int ny = y + FACE_Y[face];
                int nz = z + FACE_Z[face];
                if (isLoaded(nx, nz)) {
                    increaseQueue.enqueue(pack(nx, ny, nz));
                }
            }
            recomputeSkyColumn(chunk, localX, localZ, x, z);
        }

        dirtyChunks.add(ChunkPos.key(x >> 4, z >> 4));
    }

    /**
     * Re-runs the vertical sky pass for one column.
     *
     * <p>Cheap compared with a full chunk, and necessary: breaking the block that was capping a
     * column has to let daylight fall all the way to the next obstruction, which no amount of
     * sideways spreading from the neighbours would produce.
     */
    private void recomputeSkyColumn(Chunk chunk, int localX, int localZ, int worldX, int worldZ) {
        LightStorage light = chunk.light();
        int level = LightStorage.MAX_LEVEL;
        for (int y = chunk.maxY(); y >= chunk.minY(); y--) {
            int state = chunk.getBlock(localX, y, localZ);
            if (LightProperties.blocksLight(state)) {
                level = 0;
            } else {
                level = Math.max(0, level - LightProperties.opacity(state));
            }
            int current = light.sky(localX, y, localZ);
            if (level > current) {
                light.setSky(localX, y, localZ, level);
                increaseQueue.enqueue(pack(worldX, y, worldZ));
            }
            if (level == 0 && current == 0) {
                // Below an obstruction with nothing to propagate; anything further down is either
                // already dark or will be re-lit sideways.
                break;
            }
        }
    }

    // ------------------------------------------------------------------------------ processing

    /**
     * Drains up to {@code budget} positions.
     *
     * @return true when work remains for the next tick
     */
    public boolean processQueue(int budget) {
        int visited = 0;

        // Removal first. Running a spread before the stale values are cleared lets the old light
        // immediately re-seed itself from a neighbour that has not been cleared yet, and the region
        // it was supposed to darken stays lit.
        while (decreaseQueue.size() >= 2 && visited < budget) {
            long packed = decreaseQueue.dequeueLong();
            int levelAndFlag = (int) decreaseQueue.dequeueLong();
            visited += propagateDecrease(packed, levelAndFlag);
        }
        while (!increaseQueue.isEmpty() && visited < budget) {
            long packed = increaseQueue.dequeueLong();
            visited += propagateIncrease(packed);
        }

        blocksVisited += visited;
        return hasWork();
    }

    private int propagateIncrease(long packed) {
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);

        Chunk chunk = world.chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return 1;
        }
        int sky = chunk.light().sky(x & 15, y, z & 15);
        int block = chunk.light().block(x & 15, y, z & 15);
        int visited = 1;

        for (int face = 0; face < 6; face++) {
            int nx = x + FACE_X[face];
            int ny = y + FACE_Y[face];
            int nz = z + FACE_Z[face];

            Chunk neighbour = world.chunkAt(nx >> 4, nz >> 4);
            if (neighbour == null || ny < neighbour.minY() || ny > neighbour.maxY()) {
                continue;
            }
            int state = neighbour.getBlock(nx & 15, ny, nz & 15);
            if (LightProperties.blocksLight(state)) {
                continue;
            }
            int cost = 1 + LightProperties.opacity(state);
            LightStorage neighbourLight = neighbour.light();
            boolean changed = false;

            // Sky light falling straight down keeps its level. Everything else costs a level, which
            // is what makes a shaft of daylight reach the floor while the sides fade out.
            int skyCost = (FACE_Y[face] == -1 && sky == LightStorage.MAX_LEVEL) ? 0 : cost;
            int newSky = sky - skyCost;
            if (newSky > neighbourLight.sky(nx & 15, ny, nz & 15)) {
                neighbourLight.setSky(nx & 15, ny, nz & 15, newSky);
                changed = true;
            }
            int newBlock = block - cost;
            if (newBlock > neighbourLight.block(nx & 15, ny, nz & 15)) {
                neighbourLight.setBlock(nx & 15, ny, nz & 15, newBlock);
                changed = true;
            }
            if (changed) {
                increaseQueue.enqueue(pack(nx, ny, nz));
                dirtyChunks.add(ChunkPos.key(nx >> 4, nz >> 4));
                visited++;
            }
        }
        return visited;
    }

    private int propagateDecrease(long packed, int levelAndFlag) {
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);
        boolean isSky = (levelAndFlag & SKY_FLAG_LEVEL) != 0;
        int level = levelAndFlag & 0x0F;
        if (level == 0) {
            return 1;
        }
        int visited = 1;

        for (int face = 0; face < 6; face++) {
            int nx = x + FACE_X[face];
            int ny = y + FACE_Y[face];
            int nz = z + FACE_Z[face];

            Chunk neighbour = world.chunkAt(nx >> 4, nz >> 4);
            if (neighbour == null || ny < neighbour.minY() || ny > neighbour.maxY()) {
                continue;
            }
            LightStorage neighbourLight = neighbour.light();
            int current = isSky
                    ? neighbourLight.sky(nx & 15, ny, nz & 15)
                    : neighbourLight.block(nx & 15, ny, nz & 15);
            if (current == 0) {
                continue;
            }

            if (current < level) {
                // This neighbour was lit by the value being removed, so it goes dark too and the
                // clearing continues outwards.
                if (isSky) {
                    neighbourLight.setSky(nx & 15, ny, nz & 15, 0);
                } else {
                    neighbourLight.setBlock(nx & 15, ny, nz & 15, 0);
                }
                enqueueDecrease(nx, ny, nz, current, isSky);
            } else {
                // Brighter than what is being removed, so it has its own source: it survives, and
                // becomes a seed for filling the hole back in.
                increaseQueue.enqueue(pack(nx, ny, nz));
            }
            dirtyChunks.add(ChunkPos.key(nx >> 4, nz >> 4));
            visited++;
        }
        return visited;
    }

    // ----------------------------------------------------------------------------- dirty chunks

    /** Chunks whose light changed since the last call; clears the set. */
    public long[] drainDirtyChunks() {
        if (dirtyChunks.isEmpty()) {
            return EMPTY;
        }
        long[] out = dirtyChunks.toLongArray();
        dirtyChunks.clear();
        return out;
    }

    private static final long[] EMPTY = new long[0];

    private boolean isLoaded(int x, int z) {
        return world.chunkAt(x >> 4, z >> 4) != null;
    }

    // --------------------------------------------------------------------------------- packing

    /** Marks a queued decrease as sky rather than block light; sits above the 4-bit level. */
    private static final int SKY_FLAG_LEVEL = 0x10;

    private static final int[] FACE_X = {0, 0, 0, 0, -1, 1};
    private static final int[] FACE_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] FACE_Z = {0, 0, -1, 1, 0, 0};

    /**
     * Packs a position into one long: 26 bits of X, 26 of Z, 12 of Y.
     *
     * <p>That is all 64 bits, which is why a removal's level travels as a second queue entry rather
     * than riding along in spare space -- there is none. Y as 12 signed bits covers -2048..2047,
     * comfortably more than the -64..319 a world uses.
     */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int unpackX(long packed) {
        return (int) (packed >> 38) << 6 >> 6;
    }

    static int unpackZ(long packed) {
        return (int) ((packed >> 12) & 0x3FFFFFF) << 6 >> 6;
    }

    static int unpackY(long packed) {
        return (int) (packed & 0xFFF) << 20 >> 20;
    }
}
