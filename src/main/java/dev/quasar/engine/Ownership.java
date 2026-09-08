package dev.quasar.engine;

import dev.quasar.util.Log;

/**
 * Enforces the single-writer rule that the whole engine rests on.
 *
 * <p>There is not one lock on block or entity state in this project. Correctness comes entirely
 * from an invariant: while a region ticks, its worker thread is the only thread permitted to touch
 * the chunks and entities that region owns. Everything else must go through
 * {@link Region#post(Runnable)}.
 *
 * <p>An invariant held up only by careful review is not an invariant, it is a habit. When it breaks
 * the symptom is not an exception, it is a torn read: a block that reverts, an inventory that
 * duplicates, a chunk that saves half-written. Those corrupt silently, surface far from the cause,
 * and are close to impossible to reproduce. This class turns them into an immediate stack trace
 * naming the thread, the region and the offending coordinate.
 *
 * <h2>Two tiers</h2>
 *
 * <p><b>Always on:</b> comparing a thread reference is free, so any check that only needs "am I the
 * owner of this region I already hold" runs unconditionally, even in production. See
 * {@link Region#assertOwned()}.
 *
 * <p><b>Strict mode:</b> checks that must first work out <em>which</em> region owns a coordinate
 * need a lookup under a lock, which is far too expensive for a hot path. Those are compiled in but
 * gated on {@link #strict()}, on by default with {@code --debug} and settable with
 * {@code engine.strict-ownership}. Development and CI run strict; production does not.
 *
 * <h2>Legitimate unowned access</h2>
 *
 * <p>Not every write comes from a region tick, and the legal exceptions are narrow:
 *
 * <ul>
 *   <li><b>Generation and loading.</b> A chunk being built on a world-gen thread has no owning
 *       region yet, so a lookup returns null and the write is allowed. Publication is what hands it
 *       to a region, and that happens at a safepoint.</li>
 *   <li><b>Safepoints.</b> No region is ticking, so the dispatcher may restructure freely. It marks
 *       itself with {@link #enterSafepoint()}.</li>
 *   <li><b>Startup and shutdown.</b> Before the scheduler runs and after it stops there are no
 *       region threads to conflict with.</li>
 * </ul>
 */
public final class Ownership {

    private Ownership() {}

    private static volatile boolean strict;

    /**
     * The dispatcher, while it is inside a safepoint.
     *
     * <p>Volatile rather than a ThreadLocal because it is read from every other thread: a worker
     * that somehow ran during a safepoint must see the marker to report it.
     */
    private static volatile Thread safepointThread;

    /** Counts violations rather than only throwing, so a run can report a total at the end. */
    private static volatile int violations;

    public static void setStrict(boolean value) {
        strict = value;
        Log.info("Ownership assertions: %s", value ? "strict (development)" : "cheap checks only");
    }

    public static boolean strict() {
        return strict;
    }

    public static int violationCount() {
        return violations;
    }

    // ------------------------------------------------------------------------------ safepoints

    /** Marks the calling thread as the safepoint owner. Paired with {@link #exitSafepoint()}. */
    public static void enterSafepoint() {
        safepointThread = Thread.currentThread();
    }

    public static void exitSafepoint() {
        safepointThread = null;
    }

    /** True when the caller is the dispatcher inside a safepoint, where no region is ticking. */
    public static boolean atSafepoint() {
        return safepointThread == Thread.currentThread();
    }

    /**
     * Fails unless the caller is inside a safepoint.
     *
     * <p>For structural changes — adding a chunk to a region, merging, retiring — which are only
     * safe when nothing is ticking. Doing one mid-tick can move a chunk out from under a region
     * that is halfway through reading it.
     */
    public static void assertAtSafepoint(String action) {
        if (!atSafepoint()) {
            throw violation(action + " is a structural change and may only run at a safepoint, but"
                    + " was called from " + describe(Thread.currentThread())
                    + ". Queue it with RegionScheduler.runAtSafepoint(...).");
        }
    }

    // ---------------------------------------------------------------------------- block access

    /**
     * Verifies the calling thread may touch the block at the given coordinate.
     *
     * <p>Strict mode only: this resolves the owning region from the chunk position, which takes a
     * lock and is far too costly to leave in a path that runs for every block read.
     */
    public static void checkBlockAccess(RegionManager manager, String action, int x, int y, int z) {
        if (!strict || manager == null || atSafepoint()) {
            return;
        }
        Region owner = manager.regionForChunk(x >> 4, z >> 4);
        if (owner == null) {
            // No region owns this chunk: it is still generating, loading, or already unloaded.
            // Nothing else can be ticking it, so there is no one to race with.
            return;
        }
        if (!owner.isOwnedByCurrentThread()) {
            throw violation(action + " at " + x + "," + y + "," + z + " (chunk " + (x >> 4) + ","
                    + (z >> 4) + ") is owned by region #" + owner.id() + ", but was called from "
                    + describe(Thread.currentThread()) + ". Post the work to that region instead:"
                    + " region.post(() -> ...).");
        }
    }

    // --------------------------------------------------------------------------------- helpers

    private static IllegalStateException violation(String message) {
        violations++;
        IllegalStateException error = new IllegalStateException("Ownership violation: " + message);
        // Logged as well as thrown. A violation inside a region tick is caught and logged by
        // Region.runTick so one bad region cannot kill the server, which would otherwise bury the
        // stack trace among ordinary tick errors.
        Log.error("Ownership violation", error);
        return error;
    }

    private static String describe(Thread thread) {
        String name = thread.getName();
        if (safepointThread != null) {
            return "thread '" + name + "' (a safepoint is in progress on '"
                    + safepointThread.getName() + "')";
        }
        return "thread '" + name + "'";
    }
}
