package dev.quasar.world.redstone;

import dev.quasar.engine.Region;
import dev.quasar.world.World;
import dev.quasar.world.block.Blocks;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic redstone: dust, levers, torches, repeaters and lamps.
 *
 * <h2>Which region owns a redstone component</h2>
 *
 * <p>The roadmap called this the hard question, and it deserves the direct answer: <b>no component
 * needs an owner, because no component is ever an object.</b> There is no circuit graph, no network
 * registry, nothing spanning chunks that would have to belong to somebody. Every value is derived
 * from the six neighbours of a single block, which is the same shape as fluid flow -- and a
 * neighbour is at most one chunk away, so any loaded chunk it can reach is in the same region by
 * construction.
 *
 * <p>A circuit is unbounded in length, as a repeater chain can run for thousands of blocks, and that
 * is fine for the same reason a river is: it advances one block per update and re-queues. The signal
 * cannot outrun the loaded, connected chunks that make up its region.
 *
 * <p>The alternative -- modelling a "network" object spanning the wire and handing it an owner --
 * would have created exactly the cross-region shared mutable state this engine exists not to have.
 * Keeping the derivation local is what makes redstone free of coordination rather than the hardest
 * thing to coordinate.
 *
 * <h2>Timing</h2>
 *
 * <p>Dust is instant, as in vanilla: a wire carries at most 15 blocks and the whole recomputation is
 * bounded, so it finishes inside the tick that caused it rather than visibly charging up across
 * several. Torches and repeaters are what introduce delay, and they schedule their flip for a future
 * tick of the owning region -- so a clock's rate is measured in that region's ticks, not in wall
 * time, and stays correct even when a region is running behind.
 */
public final class RedstoneEngine {

    /** Positions examined per region tick before the rest defers. */
    public static final int DEFAULT_BUDGET = 4096;

    /** Vanilla's torch delay: one redstone tick. */
    private static final int TORCH_DELAY_TICKS = 2;

    private record Scheduled(long dueTick, long position, int state) {}

    private final World world;
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();

    /**
     * Pending torch and repeater flips.
     *
     * <p>A list rather than a priority queue: a handful of entries is the normal case, and scanning
     * it is cheaper than maintaining heap order for something that is usually empty.
     */
    private final List<Scheduled> scheduled = new ArrayList<>();

    private long blocksVisited;

    public RedstoneEngine(World world) {
        this.world = world;
    }

    public long blocksVisited() {
        return blocksVisited;
    }

    public boolean hasWork() {
        return !queue.isEmpty() || !scheduled.isEmpty();
    }

    public int pendingCount() {
        return queue.size() + scheduled.size();
    }

    // -------------------------------------------------------------------------------- queueing

    /**
     * Queues a block and everything that could read power from it.
     *
     * <p>Two blocks in every direction, not one. Power conducts <em>through</em> a solid block: a
     * source beside a block makes that block powered, and a torch on its far side reads that -- so
     * the torch is two positions from the change that switched it off. Waking only direct
     * neighbours leaves it burning with a powered block behind it, which is a wrong answer that
     * never resolves rather than one that resolves late.
     *
     * <p>Two is still local by the standard this engine is held to: two blocks cannot leave the
     * 3x3 chunks around the change, so it is inside the region either way.
     */
    public void onBlockChanged(int x, int y, int z) {
        if (!RedstoneBlocks.available() || !nearAnyRedstone(x, y, z)) {
            return;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
                        enqueue(x + dx, y + dy, z + dz);
                    }
                }
            }
        }
    }

    /**
     * Cheap rejection for the overwhelmingly common case: a block change with no redstone anywhere
     * near it.
     *
     * <p>Seven array reads instead of queueing twenty-five positions and updating each. Most worlds
     * contain no redstone at all, and they should not pay for it.
     */
    private boolean nearAnyRedstone(int x, int y, int z) {
        if (RedstoneBlocks.isRelevant(world.getBlockRaw(x, y, z))) {
            return true;
        }
        for (int face = 0; face < 6; face++) {
            int neighbour = world.getBlockRaw(x + RedstoneBlocks.FACE_X[face],
                    y + RedstoneBlocks.FACE_Y[face], z + RedstoneBlocks.FACE_Z[face]);
            if (RedstoneBlocks.isRelevant(neighbour)) {
                return true;
            }
        }
        return false;
    }

    public void enqueue(int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return;
        }
        long key = pack(x, y, z);
        if (queued.add(key)) {
            queue.enqueue(key);
        }
    }

    // ------------------------------------------------------------------------------ processing

    public boolean process(Region region, long tickCount, int budget) {
        if (!RedstoneBlocks.available()) {
            return false;
        }
        int visited = 0;
        current = region;
        try {
            runDue(region, tickCount);

            while (!queue.isEmpty() && visited < budget) {
                long packed = queue.dequeueLong();
                queued.remove(packed);
                visited++;

                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);
                // A region split can move a chunk between queueing and now; touching it would be
                // the cross-region write the engine forbids.
                if (!region.ownsChunk(x >> 4, z >> 4)) {
                    continue;
                }
                update(x, y, z, tickCount);
            }
        } finally {
            current = null;
        }
        blocksVisited += visited;
        return hasWork();
    }

    private Region current;

    private void runDue(Region region, long tickCount) {
        if (scheduled.isEmpty()) {
            return;
        }
        for (int i = scheduled.size() - 1; i >= 0; i--) {
            Scheduled entry = scheduled.get(i);
            if (entry.dueTick > tickCount) {
                continue;
            }
            scheduled.remove(i);
            int x = unpackX(entry.position);
            int y = unpackY(entry.position);
            int z = unpackZ(entry.position);
            if (!region.ownsChunk(x >> 4, z >> 4)) {
                continue;
            }
            int existing = world.getBlockRaw(x, y, z);
            // The block may have been broken or replaced while the flip was pending; applying the
            // old state would resurrect a torch someone just mined.
            if (sameBlock(existing, entry.state) && existing != entry.state) {
                setAndBroadcast(x, y, z, entry.state);
                onBlockChanged(x, y, z);
            }
        }
    }

    private static boolean sameBlock(int a, int b) {
        var stateA = dev.quasar.world.block.BlockStateRegistry.byId(a);
        var stateB = dev.quasar.world.block.BlockStateRegistry.byId(b);
        return stateA != null && stateB != null && stateA.name().equals(stateB.name());
    }

    private void update(int x, int y, int z, long tickCount) {
        int state = world.getBlockRaw(x, y, z);
        if (!RedstoneBlocks.isRelevant(state)) {
            return;
        }

        if (RedstoneBlocks.isDust(state)) {
            updateDust(x, y, z, state);
        } else if (RedstoneBlocks.isTorch(state)) {
            updateTorch(x, y, z, state, tickCount);
        } else if (RedstoneBlocks.isRepeater(state)) {
            updateRepeater(x, y, z, state, tickCount);
        } else if (RedstoneBlocks.isLamp(state)) {
            updateLamp(x, y, z, state);
        }
    }

    // ------------------------------------------------------------------------------------ dust

    private void updateDust(int x, int y, int z, int state) {
        int target = 0;

        for (int face = 0; face < 6; face++) {
            int nx = x + RedstoneBlocks.FACE_X[face];
            int ny = y + RedstoneBlocks.FACE_Y[face];
            int nz = z + RedstoneBlocks.FACE_Z[face];
            int neighbour = world.getBlockRaw(nx, ny, nz);

            // A direct source hands over its full strength.
            target = Math.max(target, emittedPower(neighbour, nx, ny, nz, face));

            // A solid block with a source pushing into it passes that on to dust beside it, which
            // is what makes a torch under a block light wire on the far side.
            if (RedstoneBlocks.FACE_Y[face] == 0 && isSolid(neighbour)
                    && isStronglyPowered(nx, ny, nz)) {
                target = Math.max(target, RedstoneBlocks.MAX_POWER);
            }
        }

        // Wire to wire, losing a level each step, including one step up or down.
        for (int face = 2; face < 6; face++) {
            int nx = x + RedstoneBlocks.FACE_X[face];
            int nz = z + RedstoneBlocks.FACE_Z[face];
            for (int dy = -1; dy <= 1; dy++) {
                if (dy != 0 && !dustConnectsVertically(x, y, z, nx, y + dy, nz, dy)) {
                    continue;
                }
                int neighbour = world.getBlockRaw(nx, y + dy, nz);
                if (RedstoneBlocks.isDust(neighbour)) {
                    target = Math.max(target, RedstoneBlocks.dustPower(neighbour) - 1);
                }
            }
        }

        target = Math.max(0, Math.min(RedstoneBlocks.MAX_POWER, target));

        int desired = RedstoneBlocks.withDustPower(state, target);
        desired = RedstoneBlocks.withDustConnections(desired, dustConnections(x, y, z));

        if (desired != state) {
            setAndBroadcast(x, y, z, desired);
            // Only a power change can affect anything else. A connection change is purely how the
            // wire draws itself, so re-waking the neighbourhood for one would loop: every wire
            // would keep re-queueing its neighbours to redraw them.
            if (target != RedstoneBlocks.dustPower(state)) {
                onBlockChanged(x, y, z);
            }
        }
    }

    /**
     * Which way a wire visually joins on each of its four sides.
     *
     * <p>Vanilla's shape rules, and they are what turn a line of dust into a line rather than a row
     * of dots: a wire joins to anything redstone beside it, climbs to a wire one block up when
     * nothing solid caps it, and drops to a wire one block down when nothing roofs that one.
     */
    private String[] dustConnections(int x, int y, int z) {
        String[] sides = {"none", "none", "none", "none", "none", "none"};
        int connections = 0;
        boolean cappedAbove = isSolid(world.getBlockRaw(x, y + 1, z));

        for (int face = 2; face < 6; face++) {
            int nx = x + RedstoneBlocks.FACE_X[face];
            int nz = z + RedstoneBlocks.FACE_Z[face];

            int beside = world.getBlockRaw(nx, y, nz);
            if (RedstoneBlocks.connectsToDust(beside, face)) {
                sides[face] = "side";
            } else if (!cappedAbove && RedstoneBlocks.isDust(world.getBlockRaw(nx, y + 1, nz))) {
                sides[face] = "up";
            } else if (!isSolid(beside)
                    && RedstoneBlocks.isDust(world.getBlockRaw(nx, y - 1, nz))) {
                sides[face] = "side";
            }
            if (!"none".equals(sides[face])) {
                connections++;
            }
        }

        // A wire with exactly one connection still draws as a straight line in vanilla, extending
        // through to the opposite side. Leaving it as a single stub looks like a broken wire.
        if (connections == 1) {
            for (int face = 2; face < 6; face++) {
                if (!"none".equals(sides[face])) {
                    int back = RedstoneBlocks.opposite(face);
                    if ("none".equals(sides[back])) {
                        sides[back] = "side";
                    }
                    break;
                }
            }
        }
        return sides;
    }

    /**
     * Whether dust steps up or down to a diagonal neighbour.
     *
     * <p>Vanilla's rule: wire climbs to a block above if nothing solid caps the column it is
     * climbing past, and drops to a block below if nothing solid roofs the lower one.
     */
    private boolean dustConnectsVertically(int x, int y, int z, int nx, int ny, int nz, int dy) {
        if (dy > 0) {
            return !isSolid(world.getBlockRaw(x, y + 1, z));
        }
        return !isSolid(world.getBlockRaw(nx, ny + 1, nz));
    }

    // -------------------------------------------------------------------------- torch and lamp

    private void updateTorch(int x, int y, int z, int state, long tickCount) {
        int attachment = RedstoneBlocks.torchAttachmentFace(state);
        int ax = x + RedstoneBlocks.FACE_X[attachment];
        int ay = y + RedstoneBlocks.FACE_Y[attachment];
        int az = z + RedstoneBlocks.FACE_Z[attachment];

        // A torch is an inverter: it burns unless the block holding it is powered.
        boolean shouldBeLit = !isStronglyPowered(ax, ay, az) && !hasPoweredDust(ax, ay, az);
        if (shouldBeLit != RedstoneBlocks.isLit(state)) {
            schedule(tickCount + TORCH_DELAY_TICKS, x, y, z, RedstoneBlocks.withLit(state, shouldBeLit));
        }
    }

    private void updateLamp(int x, int y, int z, int state) {
        boolean lit = receivesAnyPower(x, y, z);
        if (lit != RedstoneBlocks.isLit(state)) {
            setAndBroadcast(x, y, z, RedstoneBlocks.withLit(state, lit));
        }
    }

    private void updateRepeater(int x, int y, int z, int state, long tickCount) {
        int inputFace = RedstoneBlocks.opposite(RedstoneBlocks.repeaterFacingFace(state));
        int ix = x + RedstoneBlocks.FACE_X[inputFace];
        int iy = y + RedstoneBlocks.FACE_Y[inputFace];
        int iz = z + RedstoneBlocks.FACE_Z[inputFace];

        int input = world.getBlockRaw(ix, iy, iz);
        boolean powered = emittedPower(input, ix, iy, iz, inputFace) > 0
                || (isSolid(input) && isStronglyPowered(ix, iy, iz));

        if (powered != RedstoneBlocks.isPoweredProperty(state)) {
            schedule(tickCount + RedstoneBlocks.repeaterDelayTicks(state), x, y, z,
                    RedstoneBlocks.togglePowered(state));
        }
    }

    private void schedule(long dueTick, int x, int y, int z, int state) {
        long position = pack(x, y, z);
        for (Scheduled entry : scheduled) {
            // Already pending for the same result; re-scheduling would reset a repeater's delay
            // every time anything nearby twitched, and the circuit would never fire.
            if (entry.position == position && entry.state == state) {
                return;
            }
        }
        scheduled.add(new Scheduled(dueTick, position, state));
    }

    // ----------------------------------------------------------------------------- power model

    /** How much power a block emits toward the neighbour it was reached from. */
    private int emittedPower(int state, int x, int y, int z, int fromFace) {
        if (RedstoneBlocks.isRedstoneBlock(state)) {
            return RedstoneBlocks.MAX_POWER;
        }
        if (RedstoneBlocks.isLever(state) && RedstoneBlocks.isPoweredProperty(state)) {
            return RedstoneBlocks.MAX_POWER;
        }
        if (RedstoneBlocks.isTorch(state) && RedstoneBlocks.isLit(state)) {
            // A torch powers everything except the block it hangs on, which is what stops it
            // feeding its own input and latching on forever.
            return RedstoneBlocks.torchAttachmentFace(state) == RedstoneBlocks.opposite(fromFace)
                    ? 0 : RedstoneBlocks.MAX_POWER;
        }
        if (RedstoneBlocks.isRepeater(state) && RedstoneBlocks.isPoweredProperty(state)) {
            // Strictly one-directional: only the block it points at sees anything.
            return RedstoneBlocks.repeaterFacingFace(state) == RedstoneBlocks.opposite(fromFace)
                    ? RedstoneBlocks.MAX_POWER : 0;
        }
        return 0;
    }

    /** Whether a source is driving this block hard enough to pass power on through it. */
    private boolean isStronglyPowered(int x, int y, int z) {
        for (int face = 0; face < 6; face++) {
            int nx = x + RedstoneBlocks.FACE_X[face];
            int ny = y + RedstoneBlocks.FACE_Y[face];
            int nz = z + RedstoneBlocks.FACE_Z[face];
            int neighbour = world.getBlockRaw(nx, ny, nz);
            if (emittedPower(neighbour, nx, ny, nz, face) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPoweredDust(int x, int y, int z) {
        // Dust lying on top of a block powers it; dust beside it does not.
        int above = world.getBlockRaw(x, y + 1, z);
        return RedstoneBlocks.isDust(above) && RedstoneBlocks.dustPower(above) > 0;
    }

    /** Whether anything at all is powering this position, which is what a lamp responds to. */
    private boolean receivesAnyPower(int x, int y, int z) {
        for (int face = 0; face < 6; face++) {
            int nx = x + RedstoneBlocks.FACE_X[face];
            int ny = y + RedstoneBlocks.FACE_Y[face];
            int nz = z + RedstoneBlocks.FACE_Z[face];
            int neighbour = world.getBlockRaw(nx, ny, nz);
            if (emittedPower(neighbour, nx, ny, nz, face) > 0) {
                return true;
            }
            if (RedstoneBlocks.isDust(neighbour) && RedstoneBlocks.dustPower(neighbour) > 0) {
                return true;
            }
            if (isSolid(neighbour) && isStronglyPowered(nx, ny, nz)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSolid(int state) {
        return !Blocks.isReplaceable(state) && !RedstoneBlocks.isDust(state);
    }

    private void setAndBroadcast(int x, int y, int z, int state) {
        world.setBlock(x, y, z, state);
        if (current != null) {
            current.broadcastBlockUpdate(x, y, z, state);
        }
    }

    // --------------------------------------------------------------------------------- packing

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
