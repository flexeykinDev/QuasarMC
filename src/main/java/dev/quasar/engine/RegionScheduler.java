package dev.quasar.engine;

import dev.quasar.util.Log;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Drives every region's tick loop, and provides the global safepoint the rest of the engine relies
 * on.
 *
 * <h2>No global tick</h2>
 * There is no "server tick" here. Each region carries its own deadline and is dispatched to the
 * worker pool when that deadline passes, so N independent regions genuinely run at once and a
 * region full of hoppers slows down nobody but itself. A single dispatcher thread owns the
 * scheduling decision; workers only execute.
 *
 * <h2>Safepoints</h2>
 * Region membership cannot change while a region is ticking. When structural work is queued the
 * dispatcher stops <em>starting</em> ticks and waits for the in-flight ones to finish. That bounds
 * the pause by the slowest single tick rather than by anything unbounded, and it gives the rest of
 * the server a stop-the-world primitive ({@link #runAtSafepoint}) for saving, shutdown and
 * anything else that needs a consistent view.
 */
public final class RegionScheduler {

    /**
     * Floor on how often safepoints may run. Chunk loads and unloads are near-continuous while
     * players move, and taking a safepoint per change would serialise the whole server; batching
     * them costs a little latency on chunk visibility and buys back the parallelism.
     */
    private static final long MIN_SAFEPOINT_INTERVAL_NANOS = 25_000_000L; // 25 ms

    /** Upper bound on dispatcher sleep, so a newly created region is never left waiting long. */
    private static final long MAX_PARK_NANOS = 2_000_000L; // 2 ms

    private record SafepointTask(Runnable action, CompletableFuture<Void> completion) {}

    private final RegionManager manager;
    private final ForkJoinPool workers;
    private final Thread dispatcher;
    private final Queue<SafepointTask> safepointQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private volatile long safepointCount;

    /**
     * Cumulative nanoseconds spent inside safepoints.
     *
     * <p>The one number that says whether the region model is actually buying parallelism: time in
     * a safepoint is time when nothing ticks at all, so it is the server's serial fraction. A count
     * of safepoints does not answer that -- a thousand cheap ones are fine and one long one is not.
     */
    private final java.util.concurrent.atomic.AtomicLong safepointNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile long dispatchCount;
    private long lastSafepointNanos;

    /**
     * Live count of regions currently inside a tick, and the high-water mark.
     *
     * <p>This is the number that answers "is it actually running in parallel". A peak of 1 means
     * every region is being ticked one after another and the threading has bought nothing —
     * usually because everything merged into a single region.
     */
    private final AtomicInteger activeTicks = new AtomicInteger();
    private final AtomicInteger peakConcurrency = new AtomicInteger();

    /** Worker threads that have actually run a tick, for the same reason. */
    private final Set<String> threadsUsed = ConcurrentHashMap.newKeySet();

    public RegionScheduler(RegionManager manager, int parallelism) {
        this.manager = manager;
        AtomicInteger counter = new AtomicInteger();
        this.workers = new ForkJoinPool(
                parallelism,
                pool -> {
                    ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    thread.setName("quasar-region-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                (thread, error) -> Log.error("Uncaught error on " + thread.getName(), error),
                /* asyncMode = */ true);
        this.dispatcher = new Thread(this::dispatchLoop, "quasar-dispatcher");
        this.dispatcher.setDaemon(true);
    }

    public void start() {
        running = true;
        lastSafepointNanos = System.nanoTime();
        dispatcher.start();
        Log.info("Region scheduler started with %d worker threads", workers.getParallelism());
    }

    public int parallelism() {
        return workers.getParallelism();
    }

    public long safepointCount() {
        return safepointCount;
    }

    /** Cumulative time spent stopped in safepoints, in nanoseconds. */
    public long safepointNanos() {
        return safepointNanos.get();
    }

    public long dispatchCount() {
        return dispatchCount;
    }

    /**
     * Runs {@code action} with no region ticking, and returns a future completed once it has run.
     * The action executes on the dispatcher thread — keep it short.
     */
    public CompletableFuture<Void> runAtSafepoint(Runnable action) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!running) {
            // Nothing is ticking, so running inline is trivially safe.
            try {
                action.run();
                completion.complete(null);
            } catch (Throwable t) {
                completion.completeExceptionally(t);
            }
            return completion;
        }
        safepointQueue.add(new SafepointTask(action, completion));
        LockSupport.unpark(dispatcher);
        return completion;
    }

    // ------------------------------------------------------------------------------ dispatcher

    private void dispatchLoop() {
        while (running) {
            try {
                long now = System.nanoTime();
                boolean wantSafepoint = !safepointQueue.isEmpty()
                        || (manager.hasPendingWork() && now - lastSafepointNanos >= MIN_SAFEPOINT_INTERVAL_NANOS);

                if (wantSafepoint) {
                    // Stop starting ticks and let the in-flight ones drain.
                    if (allRegionsQuiescent()) {
                        enterSafepoint();
                        lastSafepointNanos = System.nanoTime();
                    } else {
                        LockSupport.parkNanos(50_000L); // 50 µs; a tick finishing will beat this anyway
                    }
                    continue;
                }

                long parkUntil = dispatchDueRegions(now);
                long sleep = Math.min(MAX_PARK_NANOS, Math.max(parkUntil - System.nanoTime(), 0));
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                }
            } catch (Throwable t) {
                Log.error("Dispatcher loop error", t);
                LockSupport.parkNanos(1_000_000L);
            }
        }
    }

    /**
     * Hands every region whose deadline has passed to the worker pool.
     *
     * @return the earliest future deadline, used to size the dispatcher's sleep
     */
    private long dispatchDueRegions(long now) {
        List<Region> regions = manager.regions();
        long earliest = now + MAX_PARK_NANOS;
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            if (region.state() == Region.State.DEAD) {
                continue;
            }
            if (region.nextTickNanos <= now) {
                if (region.state.compareAndSet(Region.State.IDLE, Region.State.SCHEDULED)) {
                    dispatchCount++;
                    workers.execute(() -> runInstrumented(region));
                }
                // Still behind but busy — check again promptly.
                earliest = Math.min(earliest, now + 100_000L);
            } else {
                earliest = Math.min(earliest, region.nextTickNanos);
            }
        }
        return earliest;
    }

    private void runInstrumented(Region region) {
        threadsUsed.add(Thread.currentThread().getName());
        int active = activeTicks.incrementAndGet();
        peakConcurrency.accumulateAndGet(active, Math::max);
        try {
            region.runTick();
        } finally {
            activeTicks.decrementAndGet();
        }
    }

    /** Regions ticking right now. */
    public int activeTicks() {
        return activeTicks.get();
    }

    /** Highest number of regions observed ticking simultaneously since startup. */
    public int peakConcurrency() {
        return peakConcurrency.get();
    }

    /** Names of worker threads that have run at least one tick, sorted. */
    public Set<String> threadsUsed() {
        return new TreeSet<>(threadsUsed);
    }

    private boolean allRegionsQuiescent() {
        List<Region> regions = manager.regions();
        for (int i = 0; i < regions.size(); i++) {
            Region.State state = regions.get(i).state();
            if (state == Region.State.SCHEDULED || state == Region.State.TICKING) {
                return false;
            }
        }
        return true;
    }

    private void enterSafepoint() {
        safepointCount++;
        long start = System.nanoTime();

        // Marks this thread as the safepoint owner for the duration. No region is ticking, so
        // structural changes are legal here and nowhere else; Ownership uses the marker both to
        // permit them and to name the culprit when something else tries.
        Ownership.enterSafepoint();
        int applied;
        try {
            applied = manager.applyPendingAtSafepoint();

            SafepointTask task;
            while ((task = safepointQueue.poll()) != null) {
                try {
                    task.action.run();
                    task.completion.complete(null);
                } catch (Throwable t) {
                    Log.error("Safepoint task failed", t);
                    task.completion.completeExceptionally(t);
                }
            }
        } finally {
            Ownership.exitSafepoint();
        }

        long durationNanos = System.nanoTime() - start;
        safepointNanos.addAndGet(durationNanos);
        if (durationNanos > 20_000_000L) {
            Log.warn("Long safepoint: %.1f ms for %d structural change(s)", durationNanos / 1e6, applied);
        }
    }

    // -------------------------------------------------------------------------------- shutdown

    public void shutdown() {
        running = false;
        LockSupport.unpark(dispatcher);
        try {
            dispatcher.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.warn("Worker pool did not drain in time; forcing shutdown");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        Log.info("Region scheduler stopped after %d dispatches and %d safepoints", dispatchCount, safepointCount);
    }
}
