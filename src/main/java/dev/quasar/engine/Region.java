package dev.quasar.engine;

import dev.quasar.entity.Entity;
import dev.quasar.util.Log;
import dev.quasar.world.ChunkPos;
import dev.quasar.world.World;
import dev.quasar.world.light.LightEngine;
import dev.quasar.world.physics.BlockPhysics;
import dev.quasar.world.redstone.RedstoneEngine;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An independently ticking slice of the world.
 *
 * <h2>The ownership rule</h2>
 * A region owns a set of chunks and every entity standing in them. While a region is ticking, its
 * worker thread is the <em>only</em> thread permitted to read or write that state. There are no
 * locks on block data or entity fields anywhere in this project; correctness comes entirely from
 * that single-writer invariant.
 *
 * <p>Two things preserve it:
 * <ul>
 *   <li>Chunks closer than {@link RegionManager#LINK_RADIUS} are always in the same region, so
 *       anything an entity can reach in one tick is state its own region owns.</li>
 *   <li>Region membership only ever changes at a global safepoint, when no region is ticking
 *       (see {@link RegionScheduler}).</li>
 * </ul>
 *
 * <p>Anything that has to touch a <em>different</em> region posts to that region's
 * {@link #mailbox}, which is drained by the owner at the top of its next tick.
 */
public final class Region {

    public enum State {
        /** Not running; eligible to be scheduled. */
        IDLE,
        /** Handed to the worker pool, not yet started. */
        SCHEDULED,
        /** A worker thread is inside {@link #runTick()}. */
        TICKING,
        /** Merged into another region or emptied; never scheduled again. */
        DEAD,
    }

    /** Nominal tick period. Every region targets this independently of every other region. */
    public static final long TICK_PERIOD_NANOS = 50_000_000L;

    /**
     * How far behind a region may fall before it stops trying to catch up. Without this a region
     * that stalls once tries to run the whole backlog at once and never recovers.
     */
    private static final long MAX_CATCHUP_NANOS = TICK_PERIOD_NANOS * 10;

    private record ScheduledTask(long dueTick, Runnable action) {}

    private final int id;
    private final World world;
    private final RegionManager manager;

    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private final List<Entity> entities = new ArrayList<>();
    private final Queue<Runnable> mailbox = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<ScheduledTask> timers =
            new PriorityQueue<>((a, b) -> Long.compare(a.dueTick, b.dueTick));

    /** Entities queued for removal during the current tick; applied once iteration finishes. */
    private final Queue<Entity> pendingRemoval = new ArrayDeque<>();

    private final TickMetrics metrics = new TickMetrics();
    final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    /** The random source is per-region so parallel ticking stays reproducible per region. */
    private final Random random;

    /** Scheduler bookkeeping — only ever touched by the dispatcher thread. */
    long nextTickNanos;

    /**
     * When this region was merged away, the region that took its contents.
     *
     * <p>Lets {@link RegionManager} follow a dead region forward to whoever owns its chunks now,
     * which matters when a region flagged for a split check gets absorbed in the same batch.
     */
    Region absorbedInto;

    private long tickCount;
    private volatile Thread owner;

    /**
     * The region the calling thread is currently ticking, or null.
     *
     * <p>Lets code deep in the world -- {@link dev.quasar.world.World#setBlock} in particular --
     * reach the region responsible for the work it is doing without threading a parameter through
     * every layer, and without a lookup by chunk position on a hot path.
     */
    private static final ThreadLocal<Region> CURRENT = new ThreadLocal<>();

    /** Light propagation queued by this region, drained under a budget at the end of its tick. */
    private final LightEngine lightEngine;

    /** Gravity and fluid flow queued by this region, drained under its own budget. */
    private final BlockPhysics physics;

    /** Vanilla's slow background block ticks: grass spreading and dying back. */
    private final dev.quasar.world.physics.RandomTicks randomTicks;

    /** Redstone, likewise per-region: its timing is measured in this region's ticks. */
    private final RedstoneEngine redstone;

    /** Chunks adopted at a safepoint, waiting to be seeded on this region's own thread. */
    private final Queue<Long> pendingLightSeeds = new ConcurrentLinkedQueue<>();

    Region(int id, World world, RegionManager manager, long seed) {
        this.id = id;
        this.world = world;
        this.manager = manager;
        this.random = new Random(seed ^ (id * 0x9E3779B97F4A7C15L));
        this.lightEngine = new LightEngine(world);
        this.physics = new BlockPhysics(world);
        this.randomTicks = new dev.quasar.world.physics.RandomTicks(world);
        this.redstone = new RedstoneEngine(world);
        this.nextTickNanos = System.nanoTime();
    }

    public int id() {
        return id;
    }

    /** The region being ticked on this thread, or null if this is not a region tick. */
    public static Region current() {
        return CURRENT.get();
    }

    public LightEngine lightEngine() {
        return lightEngine;
    }

    public BlockPhysics physics() {
        return physics;
    }

    public RedstoneEngine redstone() {
        return redstone;
    }


    /**
     * Tells everyone in this region who can see it that a block changed.
     *
     * <p>A plain loop for the same reason block edits are: every player who could witness the change
     * is an entity of this region already.
     */
    public void broadcastBlockUpdate(int x, int y, int z, int state) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        for (Entity entity : entities) {
            if (entity instanceof dev.quasar.entity.Player player
                    && !player.isRemoved()
                    && player.hasChunkLoaded(chunkX, chunkZ)) {
                player.sendBlockUpdate(x, y, z, state);
            }
        }
    }

    public World world() {
        return world;
    }

    /**
     * This region's random source.
     *
     * <p>Per-region so that parallel ticking stays reproducible, which only holds while a single
     * thread draws from it; sharing it across threads destroys both the reproducibility and the
     * generator's internal state.
     */
    public Random random() {
        assertOwned();
        return random;
    }

    public long tickCount() {
        assertOwnedOrSafepoint("Region.tickCount()");
        return tickCount;
    }

    public TickMetrics metrics() {
        return metrics;
    }

    public State state() {
        return state.get();
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int entityCount() {
        return entities.size();
    }

    /**
     * This region's entities.
     *
     * <p>The list is unmodifiable but <em>live</em>: it is a view, not a copy, so reading it from
     * another thread while this region ticks is a race even though nothing can be added through it.
     */
    public List<Entity> entities() {
        assertOwnedOrSafepoint("Region.entities()");
        return Collections.unmodifiableList(entities);
    }

    /**
     * Chunk keys owned by this region. Safe to read from the owning tick thread, or from any thread
     * at a safepoint; racy otherwise.
     */
    public LongOpenHashSet chunkKeys() {
        return chunks;
    }

    public boolean ownsChunk(int chunkX, int chunkZ) {
        return chunks.contains(ChunkPos.key(chunkX, chunkZ));
    }

    // ------------------------------------------------------------------ cross-thread entry points

    /**
     * Runs {@code task} on this region's tick thread, at the start of its next tick. This is the
     * only supported way to touch a region you do not own.
     */
    public void post(Runnable task) {
        mailbox.add(task);
    }

    /** Runs {@code task} after {@code delayTicks} of <em>this region's</em> ticks. */
    public void postDelayed(Runnable task, int delayTicks) {
        post(() -> timers.add(new ScheduledTask(tickCount + Math.max(delayTicks, 0), task)));
    }

    /** True when called from this region's tick thread — the guard used by {@link #assertOwned()}. */
    public boolean isOwnedByCurrentThread() {
        return owner == Thread.currentThread();
    }

    /**
     * Fails fast on an ownership violation. Cheap enough to leave in hot paths, and it turns a
     * silent data race into an immediate, obvious stack trace.
     */
    public void assertOwned() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Region " + id + " touched from " + Thread.currentThread().getName()
                            + " but is owned by " + (owner == null ? "nobody" : owner.getName()));
        }
    }

    /**
     * Sets the tick owner directly. Test-only.
     *
     * <p>{@link #runTick()} is normally the only thing that assigns ownership, and it runs a whole
     * tick to do it. The ownership tests need a thread the region believes is its owner without any
     * of the rest, so that what is under test is the guard alone.
     */
    void setOwnerForTesting(Thread thread) {
        this.owner = thread;
    }

    /**
     * Fails unless the caller either owns this region or is the dispatcher at a safepoint.
     *
     * <p>The safepoint half matters: housekeeping, metrics and the console read region state
     * between ticks, which is legal precisely because nothing is ticking then.
     */
    public void assertOwnedOrSafepoint(String what) {
        if (owner != Thread.currentThread() && !Ownership.atSafepoint() && owner != null) {
            throw new IllegalStateException("Ownership violation: " + what + " on region " + id
                    + " from thread '" + Thread.currentThread().getName() + "', which owns nothing;"
                    + " region " + id + " is being ticked by '" + owner.getName() + "'."
                    + " Post the work to it instead: region.post(() -> ...).");
        }
    }

    // ------------------------------------------------------------------------- structural changes

    /** Safepoint-only. Called by {@link RegionManager}. */
    void addChunkAtSafepoint(long chunkKey) {
        Ownership.assertAtSafepoint("Region.addChunk");
        chunks.add(chunkKey);
        // Seeding walks the whole chunk and must run on this region's thread, not the dispatcher's,
        // or a safepoint would carry the cost of lighting every chunk that just loaded.
        pendingLightSeeds.add(chunkKey);
    }

    /** Safepoint-only. Called by {@link RegionManager}. */
    void removeChunkAtSafepoint(long chunkKey) {
        Ownership.assertAtSafepoint("Region.removeChunk");
        chunks.remove(chunkKey);
    }

    /** Safepoint-only. Moves everything owned by {@code other} into this region. */
    void absorbAtSafepoint(Region other) {
        Ownership.assertAtSafepoint("Region.absorb");
        chunks.addAll(other.chunks);
        // Light work the dying region never got to is inherited rather than dropped, exactly like
        // its mailbox: losing it would leave those chunks permanently half-lit.
        pendingLightSeeds.addAll(other.pendingLightSeeds);
        other.pendingLightSeeds.clear();
        for (Entity entity : other.entities) {
            entity.setRegion(this);
            entities.add(entity);
        }
        other.chunks.clear();
        other.entities.clear();
        // The dying region may still hold work nobody has run yet; inherit it rather than drop it.
        Runnable pending;
        while ((pending = other.mailbox.poll()) != null) {
            mailbox.add(pending);
        }
        timers.addAll(other.timers);
        other.timers.clear();
        other.absorbedInto = this;
        other.state.set(State.DEAD);
    }

    /** Safepoint-only. */
    void addEntityAtSafepoint(Entity entity) {
        Ownership.assertAtSafepoint("Region.addEntity");
        entity.setRegion(this);
        entities.add(entity);
    }

    /** Safepoint-only. */
    void removeEntityAtSafepoint(Entity entity) {
        Ownership.assertAtSafepoint("Region.removeEntity");
        entities.remove(entity);
    }

    /** Callable from this region's tick thread; the entity leaves at the end of the tick. */
    public void removeEntity(Entity entity) {
        assertOwned();
        entity.markRemoved();
        pendingRemoval.add(entity);
    }

    // ------------------------------------------------------------------------------- the tick

    /**
     * Executes one tick. Called only by {@link RegionScheduler} on a worker thread, never
     * re-entrantly, and never concurrently with another tick of the same region.
     */
    void runTick() {
        long start = System.nanoTime();
        owner = Thread.currentThread();
        CURRENT.set(this);
        state.set(State.TICKING);
        try {
            drainMailbox();
            runDueTimers();
            world.tickRegion(this);
            tickEntities();
            applyPendingRemovals();
            tickLight();
        } catch (Throwable t) {
            // One region blowing up must not take the server down; log it and keep the others alive.
            Log.error("Region " + id + " threw during tick " + tickCount, t);
        } finally {
            tickCount++;
            owner = null;
            CURRENT.remove();
            long duration = System.nanoTime() - start;
            metrics.recordTick(start, duration);
            advanceDeadline(start + duration);
            state.set(State.IDLE);
        }
    }

    /**
     * Seeds newly adopted chunks and drains queued light propagation.
     *
     * <p>Budgeted rather than run to completion: breaking the one block that opens a cave to
     * daylight can cascade across thousands of positions, and paying for all of it inside a single
     * tick is exactly the latency spike a region-threaded engine is supposed to avoid. Work left
     * over simply continues next tick.
     */
    private void tickLight() {
        Long seed;
        while ((seed = pendingLightSeeds.poll()) != null) {
            dev.quasar.world.Chunk chunk =
                    world.chunkAt(ChunkPos.keyX(seed), ChunkPos.keyZ(seed));
            if (chunk != null) {
                // Normally generation has already done this on its own thread. Doing it here as a
                // fallback costs a tick's worth of work once, and is the difference between a
                // correctly lit chunk and a permanently black one.
                if (!chunk.isLit()) {
                    LightEngine.lightNewChunk(chunk);
                }
                lightEngine.seedChunk(chunk);
                seedPhysics(chunk);
            }
        }
        // Uses this region's own random source, so parallel regions stay independent and each
        // one's growth is reproducible from its seed.
        randomTicks.tick(this, random, tickCount);
        physics.process(this, tickCount, BlockPhysics.DEFAULT_BUDGET);
        redstone.process(this, tickCount, RedstoneEngine.DEFAULT_BUDGET);
        lightEngine.processQueue(LightEngine.DEFAULT_BUDGET);

        // Tell anyone watching. Every player who could see these chunks is an entity of this region
        // -- the same argument that makes block-change broadcasts a plain loop -- so this needs no
        // coordination either.
        long[] dirty = lightEngine.drainDirtyChunks();
        if (dirty.length == 0) {
            return;
        }
        for (Entity entity : entities) {
            if (!(entity instanceof dev.quasar.entity.Player player)) {
                continue;
            }
            for (long key : dirty) {
                dev.quasar.world.Chunk chunk =
                        world.chunkAt(ChunkPos.keyX(key), ChunkPos.keyZ(key));
                if (chunk != null) {
                    player.sendLightUpdate(chunk);
                }
            }
        }
    }

    /**
     * Wakes physics for a chunk this region has just adopted.
     *
     * <p>Only the fluid and gravity blocks matter, so this looks for those rather than queueing
     * every position: a chunk is nearly 100k blocks and almost none of them will ever move.
     */
    private void seedPhysics(dev.quasar.world.Chunk chunk) {
        int baseX = chunk.x() << 4;
        int baseZ = chunk.z() << 4;

        // Section by section, skipping any that is a single repeated state. Most of a generated
        // world is uniform stone or uniform air, and walking those block by block made adopting a
        // chunk cost 98k reads -- enough to drag a busy server off 20 TPS on its own.
        for (int index = 0; index < chunk.sectionCount(); index++) {
            dev.quasar.world.ChunkSection section = chunk.section(index);
            int single = section.singleState();
            int sectionMinY = chunk.minY() + (index << 4);

            if (single >= 0) {
                if (!dev.quasar.world.block.Blocks.isFluid(single)
                        && !dev.quasar.world.block.Blocks.fallsUnderGravity(single)
                        && !dev.quasar.world.redstone.RedstoneBlocks.isRelevant(single)) {
                    continue;
                }
            }
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int dy = 0; dy < 16; dy++) {
                        int y = sectionMinY + dy;
                        int state = chunk.getBlock(localX, y, localZ);
                        if (dev.quasar.world.block.Blocks.isFluid(state)
                                || dev.quasar.world.block.Blocks.fallsUnderGravity(state)) {
                            physics.enqueue(baseX + localX, y, baseZ + localZ);
                        }
                        // A chunk loaded from disk can hold a circuit mid-signal. Without waking
                        // it, a lamp saved lit stays lit with nothing driving it.
                        //
                        // One array read, not four name comparisons: this runs for every block of
                        // every non-uniform section of every chunk adopted, and the predicate
                        // version cost a full TPS on a world containing no redstone at all.
                        if (dev.quasar.world.redstone.RedstoneBlocks.isRelevant(state)) {
                            redstone.enqueue(baseX + localX, y, baseZ + localZ);
                        }
                    }
                }
            }
        }
    }

    private void drainMailbox() {
        Runnable task;
        while ((task = mailbox.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                Log.error("Mailbox task failed in region " + id, t);
            }
        }
    }

    private void runDueTimers() {
        while (!timers.isEmpty() && timers.peek().dueTick <= tickCount) {
            ScheduledTask task = timers.poll();
            try {
                task.action.run();
            } catch (Throwable t) {
                Log.error("Scheduled task failed in region " + id, t);
            }
        }
    }

    private void tickEntities() {
        // Indexed loop: entities may be appended during iteration (spawns), and those should wait
        // for the next tick rather than be ticked in the same one they were created in.
        int count = entities.size();
        for (int i = 0; i < count; i++) {
            Entity entity = entities.get(i);
            if (entity.isRemoved()) {
                continue;
            }
            try {
                entity.tick(this);
            } catch (Throwable t) {
                Log.error("Entity " + entity.entityId() + " threw in region " + id, t);
            }
        }
    }

    private void applyPendingRemovals() {
        Entity entity;
        while ((entity = pendingRemoval.poll()) != null) {
            entities.remove(entity);
            manager.onEntityRemoved(entity);
        }
    }

    /**
     * Picks the next deadline. If the tick overran badly we abandon the backlog instead of
     * spiralling — the region runs slow but stays responsive, and the drop is recorded so it shows
     * up in {@code /quasar regions} rather than being silently absorbed.
     */
    private void advanceDeadline(long now) {
        nextTickNanos += TICK_PERIOD_NANOS;
        long behind = now - nextTickNanos;
        if (behind > MAX_CATCHUP_NANOS) {
            long dropped = behind / TICK_PERIOD_NANOS;
            metrics.recordDroppedTicks(dropped);
            nextTickNanos = now + TICK_PERIOD_NANOS;
        }
    }

    @Override
    public String toString() {
        return "Region#" + id + "{chunks=" + chunks.size() + ", entities=" + entities.size()
                + ", tps=" + String.format("%.1f", metrics.tps()) + "}";
    }
}
