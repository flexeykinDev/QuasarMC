package dev.quasar.engine;

import dev.quasar.entity.Entity;
import dev.quasar.util.Log;
import dev.quasar.world.ChunkPos;
import dev.quasar.world.World;
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

    Region(int id, World world, RegionManager manager, long seed) {
        this.id = id;
        this.world = world;
        this.manager = manager;
        this.random = new Random(seed ^ (id * 0x9E3779B97F4A7C15L));
        this.nextTickNanos = System.nanoTime();
    }

    public int id() {
        return id;
    }

    public World world() {
        return world;
    }

    public Random random() {
        return random;
    }

    public long tickCount() {
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

    public List<Entity> entities() {
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

    // ------------------------------------------------------------------------- structural changes

    /** Safepoint-only. Called by {@link RegionManager}. */
    void addChunkAtSafepoint(long chunkKey) {
        chunks.add(chunkKey);
    }

    /** Safepoint-only. Called by {@link RegionManager}. */
    void removeChunkAtSafepoint(long chunkKey) {
        chunks.remove(chunkKey);
    }

    /** Safepoint-only. Moves everything owned by {@code other} into this region. */
    void absorbAtSafepoint(Region other) {
        chunks.addAll(other.chunks);
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
        entity.setRegion(this);
        entities.add(entity);
    }

    /** Safepoint-only. */
    void removeEntityAtSafepoint(Entity entity) {
        entities.remove(entity);
    }

    /** Callable from this region's tick thread; the entity leaves at the end of the tick. */
    public void removeEntity(Entity entity) {
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
        state.set(State.TICKING);
        try {
            drainMailbox();
            runDueTimers();
            world.tickRegion(this);
            tickEntities();
            applyPendingRemovals();
        } catch (Throwable t) {
            // One region blowing up must not take the server down; log it and keep the others alive.
            Log.error("Region " + id + " threw during tick " + tickCount, t);
        } finally {
            tickCount++;
            owner = null;
            long duration = System.nanoTime() - start;
            metrics.recordTick(start, duration);
            advanceDeadline(start + duration);
            state.set(State.IDLE);
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
