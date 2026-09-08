package dev.quasar.world.physics;

import dev.quasar.engine.Region;
import dev.quasar.entity.FallingBlockEntity;
import dev.quasar.world.World;
import dev.quasar.world.block.Blocks;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Gravity and fluid flow: the part of the world that keeps moving after you stop touching it.
 *
 * <h2>Region safety</h2>
 *
 * <p>One update step moves a block or a fluid level exactly one block, so it only ever reads and
 * writes the six neighbours of a position. A neighbour is at most one chunk away, and any loaded
 * chunk one away is in the same region -- regions are the connected components of "within
 * LINK_RADIUS (2) chunks", so two loaded chunks that are adjacent cannot be in different ones.
 *
 * <p>A <em>cascade</em> is unbounded, unlike light: water can run for hundreds of blocks. That is
 * fine, because it advances one step per update and each step is re-queued. The frontier can never
 * jump into a region it does not belong to; it can only reach chunks that are loaded and connected,
 * which is the same region by construction. Where the world runs out of loaded chunks the flow
 * simply stops, which is also what vanilla does at the edge of loaded terrain.
 *
 * <p>Queue entries can nevertheless go stale, when a region splits between queueing and processing
 * and a chunk changes hands. Rather than trying to re-home the queue, every position is checked
 * against the owning region before it is touched and silently dropped if it moved -- the new owner
 * re-seeds its own chunks when it adopts them.
 *
 * <h2>Rates</h2>
 *
 * <p>Fluids do not update every tick. Water moves every 5 ticks and lava every 30, matching vanilla,
 * which is what makes a stream visibly creep rather than appear instantly. Gravity is checked every
 * tick, because a falling block should give way the moment its support is gone.
 */
public final class BlockPhysics {

    /** Positions examined per region tick before the rest defers, as in the light engine. */
    public static final int DEFAULT_BUDGET = 2048;

    /** Vanilla's overworld flow rates, in ticks. */
    public static final int WATER_PERIOD = 5;
    public static final int LAVA_PERIOD = 30;

    /** How far a fluid spreads horizontally from its source before running out. */
    private static final int WATER_RANGE = 7;
    private static final int LAVA_RANGE = 3;

    /** The {@code level} value marking fluid that is falling rather than spreading. */
    private static final int FALLING = 8;

    private final World world;

    private final LongArrayFIFOQueue gravityQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue waterQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue lavaQueue = new LongArrayFIFOQueue();

    /**
     * Positions already queued, one set per queue.
     *
     * <p>Deduplication matters: without it a pool of water re-queues its own neighbours on every
     * level change and the queue grows faster than the budget drains it.
     *
     * <p>Per queue rather than shared, because a single set would let an air block adjacent to both
     * water and lava sit on only one of them -- and whichever it landed on first would decide, at
     * random, which fluid was allowed to flow there.
     */
    private final LongOpenHashSet gravityQueued = new LongOpenHashSet();
    private final LongOpenHashSet waterQueued = new LongOpenHashSet();
    private final LongOpenHashSet lavaQueued = new LongOpenHashSet();

    private long blocksVisited;

    /**
     * The region draining this queue, for the duration of {@link #process}.
     *
     * <p>Physics changes blocks with nobody watching from a player action, so it has to announce
     * them itself; the region is what knows who can see the change.
     */
    private Region current;

    public BlockPhysics(World world) {
        this.world = world;
    }

    public long blocksVisited() {
        return blocksVisited;
    }

    public int pendingCount() {
        return gravityQueue.size() + waterQueue.size() + lavaQueue.size();
    }

    private LongOpenHashSet queuedSetFor(LongArrayFIFOQueue queue) {
        if (queue == gravityQueue) {
            return gravityQueued;
        }
        return queue == waterQueue ? waterQueued : lavaQueued;
    }

    public boolean hasWork() {
        return pendingCount() > 0;
    }

    // ------------------------------------------------------------------------------- queueing

    /**
     * Queues the neighbourhood of a block that just changed.
     *
     * <p>This is the neighbour-update model vanilla uses: a change does not compute its own
     * consequences, it just tells the six adjacent positions to re-examine themselves. Sand above
     * discovers it is unsupported; water beside discovers it has somewhere to go.
     */
    public void onBlockChanged(int x, int y, int z) {
        enqueue(x, y, z);
        for (int face = 0; face < 6; face++) {
            enqueue(x + FACE_X[face], y + FACE_Y[face], z + FACE_Z[face]);
        }
    }

    /** Queues one position for re-examination, routed by what is actually there. */
    public void enqueue(int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return;
        }
        long key = pack(x, y, z);
        int state = world.getBlockRaw(x, y, z);

        if (Blocks.fallsUnderGravity(state) && gravityQueued.add(key)) {
            gravityQueue.enqueue(key);
        }

        // A position goes on a fluid's queue either because that fluid is already there, or because
        // it is empty and that fluid is adjacent and might move in. Deciding by the *neighbour's*
        // type is what makes lava spread at all: an air block beside lava was previously queued as
        // water, so the water pass looked at it, found no water, and did nothing -- while the lava
        // pass never saw it.
        boolean waterHere = Blocks.isWater(state);
        boolean lavaHere = Blocks.isLava(state);
        boolean empty = !waterHere && !lavaHere && Blocks.isReplaceable(state);

        if ((waterHere || (empty && hasAdjacentFluid(x, y, z, false))) && waterQueued.add(key)) {
            waterQueue.enqueue(key);
        }
        if ((lavaHere || (empty && hasAdjacentFluid(x, y, z, true))) && lavaQueued.add(key)) {
            lavaQueue.enqueue(key);
        }
    }

    /** Whether a fluid of the given kind sits above or beside this position. */
    private boolean hasAdjacentFluid(int x, int y, int z, boolean lava) {
        int above = world.getBlockRaw(x, y + 1, z);
        if (lava ? Blocks.isLava(above) : Blocks.isWater(above)) {
            return true;
        }
        for (int face = 2; face < 6; face++) {
            int neighbour = world.getBlockRaw(x + FACE_X[face], y, z + FACE_Z[face]);
            if (lava ? Blocks.isLava(neighbour) : Blocks.isWater(neighbour)) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------- processing

    /**
     * Runs one region tick's worth of physics.
     *
     * @return true when work remains
     */
    public boolean process(Region region, long tickCount, int budget) {
        int visited = 0;
        current = region;
        try {
        visited += drain(region, gravityQueue, budget - visited, this::applyGravity);
        if (tickCount % WATER_PERIOD == 0) {
            visited += drain(region, waterQueue, budget - visited, p -> applyFluid(p, false));
        }
        if (tickCount % LAVA_PERIOD == 0) {
            visited += drain(region, lavaQueue, budget - visited, p -> applyFluid(p, true));
        }
        } finally {
            current = null;
        }
        blocksVisited += visited;
        return hasWork();
    }

    private void setAndBroadcast(int x, int y, int z, int state) {
        world.setBlock(x, y, z, state);
        if (current != null) {
            current.broadcastBlockUpdate(x, y, z, state);
        }
    }

    private interface PositionAction {
        void apply(long packed);
    }

    private int drain(Region region, LongArrayFIFOQueue queue, int budget, PositionAction action) {
        int visited = 0;
        LongOpenHashSet seen = queuedSetFor(queue);
        while (!queue.isEmpty() && visited < budget) {
            long packed = queue.dequeueLong();
            seen.remove(packed);
            visited++;

            // A region split between queueing and now can have moved this chunk to another owner.
            // Touching it would be exactly the cross-region write the engine forbids, so it is
            // dropped: whoever owns the chunk now re-seeds it when it adopts it.
            if (!region.ownsChunk(unpackX(packed) >> 4, unpackZ(packed) >> 4)) {
                continue;
            }
            action.apply(packed);
        }
        return visited;
    }

    // -------------------------------------------------------------------------------- gravity

    private void applyGravity(long packed) {
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);

        int state = world.getBlockRaw(x, y, z);
        if (!Blocks.fallsUnderGravity(state) || y <= world.minY()) {
            return;
        }
        int below = world.getBlockRaw(x, y - 1, z);
        if (!Blocks.isReplaceable(below)) {
            return;
        }

        // Becomes an entity rather than teleporting down a block: the client animates the fall, and
        // a stack of sand collapsing one block per tick looks like a bug rather than gravity.
        setAndBroadcast(x, y, z, Blocks.AIR);
        world.spawnFallingBlock(state, x + 0.5, y, z + 0.5);
        dev.quasar.util.Log.debug("block %d at %d,%d,%d lost its support and is falling",
                state, x, y, z);
    }

    // --------------------------------------------------------------------------------- fluids

    /**
     * Recomputes one position's fluid state from its neighbours.
     *
     * <p>Deriving the value rather than pushing it means a fluid never has to remember where it came
     * from: break the source and every dependent level simply fails to justify itself on its next
     * update and disappears, which is why draining works without tracking provenance.
     */
    private void applyFluid(long packed, boolean lava) {
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);

        int state = world.getBlockRaw(x, y, z);
        int range = lava ? LAVA_RANGE : WATER_RANGE;

        boolean isThisFluid = lava ? Blocks.isLava(state) : Blocks.isWater(state);
        if (!isThisFluid && !Blocks.isReplaceable(state)) {
            return;
        }
        // A source never changes on its own. Only breaking it does, and that arrives as a block
        // change like any other.
        if (isThisFluid && Blocks.fluidLevel(state) == 0) {
            spreadFrom(x, y, z, lava, 0);
            return;
        }

        int target = deriveLevel(x, y, z, lava, range);

        if (target < 0) {
            // Nothing justifies fluid here any more.
            if (isThisFluid) {
                setAndBroadcast(x, y, z, Blocks.AIR);
                onBlockChanged(x, y, z);
            }
            return;
        }
        int desired = Blocks.fluidState(lava, target);
        if (desired != state) {
            setAndBroadcast(x, y, z, desired);
            onBlockChanged(x, y, z);
        }
        spreadFrom(x, y, z, lava, target);
    }

    /**
     * The level this position should hold, or -1 for none.
     *
     * <p>Fluid falling from directly above always wins and arrives at full strength, which is what
     * makes a waterfall reach the bottom undiminished before spreading out.
     */
    private int deriveLevel(int x, int y, int z, boolean lava, int range) {
        int above = world.getBlockRaw(x, y + 1, z);
        boolean fedFromAbove = lava ? Blocks.isLava(above) : Blocks.isWater(above);
        if (fedFromAbove) {
            return FALLING;
        }

        int best = -1;
        for (int face = 2; face < 6; face++) {
            int nx = x + FACE_X[face];
            int nz = z + FACE_Z[face];
            int neighbour = world.getBlockRaw(nx, y, nz);
            boolean sameFluid = lava ? Blocks.isLava(neighbour) : Blocks.isWater(neighbour);
            if (!sameFluid) {
                continue;
            }
            int level = Blocks.fluidLevel(neighbour);
            // A falling column counts as a source for the block beside its base, which is how a
            // waterfall spreads outwards when it lands.
            int effective = level >= FALLING ? 0 : level;
            if (effective >= range) {
                continue;
            }
            int candidate = effective + 1;
            if (best < 0 || candidate < best) {
                best = candidate;
            }
        }
        return best;
    }

    /** Queues the places this fluid could move into next. */
    private void spreadFrom(int x, int y, int z, boolean lava, int level) {
        int belowState = world.getBlockRaw(x, y - 1, z);
        if (Blocks.isReplaceable(belowState) && y - 1 >= world.minY()) {
            // Down first and unconditionally: fluid always prefers to fall, and only spreads
            // sideways when it cannot.
            enqueue(x, y - 1, z);
            return;
        }
        int range = lava ? LAVA_RANGE : WATER_RANGE;
        int effective = level >= FALLING ? 0 : level;
        if (effective >= range) {
            return;
        }
        for (int face = 2; face < 6; face++) {
            enqueue(x + FACE_X[face], y, z + FACE_Z[face]);
        }
    }

    // -------------------------------------------------------------------------------- packing

    private static final int[] FACE_X = {0, 0, 0, 0, -1, 1};
    private static final int[] FACE_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] FACE_Z = {0, 0, -1, 1, 0, 0};

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
