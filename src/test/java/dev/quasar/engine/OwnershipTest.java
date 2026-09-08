package dev.quasar.engine;

import dev.quasar.world.World;
import dev.quasar.world.gen.FlatChunkGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the ownership assertions can actually fail.
 *
 * <p>This matters more than it sounds. An assertion that has never fired is indistinguishable from
 * one that cannot fire, and this project has already been burnt twice by exactly that: a bot swarm
 * that ran green for hours against a fully submerged world, and a pick-block check that passed
 * without a single middle-click ever being handled. So every test here first demonstrates the
 * violation being caught, rather than only demonstrating that legal code is left alone.
 *
 * <p>The tests deliberately do the wrong thing on purpose from a foreign thread. That is the point:
 * they are the only place in the codebase where the single-writer rule is broken, and they exist so
 * that it can never be broken silently anywhere else.
 */
class OwnershipTest {

    private World world;
    private RegionManager manager;
    private boolean strictBefore;

    @BeforeEach
    void setUp() {
        strictBefore = Ownership.strict();
        Ownership.setStrict(true);
        world = new World("test", 0L, -64, 384, new FlatChunkGenerator(63), 1, null);
        manager = new RegionManager(world, 0L);
        world.setRegionManager(manager);
    }

    @AfterEach
    void tearDown() {
        Ownership.setStrict(strictBefore);
        Ownership.exitSafepoint();
    }

    // ------------------------------------------------------------------------------ safepoints

    @Test
    void structuralChangeOffSafepointIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Ownership.assertAtSafepoint("Region.addChunk"));

        // The message has to be good enough to act on without reading the engine's source, so it
        // is asserted rather than left to drift.
        assertTrue(error.getMessage().contains("Region.addChunk"), error.getMessage());
        assertTrue(error.getMessage().contains("safepoint"), error.getMessage());
        assertTrue(error.getMessage().contains("runAtSafepoint"), error.getMessage());
    }

    @Test
    void structuralChangeInsideSafepointIsAllowed() {
        Ownership.enterSafepoint();
        Ownership.assertAtSafepoint("Region.addChunk");
        Ownership.exitSafepoint();

        assertThrows(IllegalStateException.class, () -> Ownership.assertAtSafepoint("after exit"));
    }

    @Test
    void safepointMarkerIsPerThread() throws Exception {
        Ownership.enterSafepoint();

        // Another thread must not inherit the dispatcher's permission just because a safepoint is
        // running somewhere. If it did, every worker would be free to restructure during one.
        AtomicReference<Boolean> otherThreadSawSafepoint = new AtomicReference<>();
        Thread other = new Thread(() -> otherThreadSawSafepoint.set(Ownership.atSafepoint()));
        other.start();
        other.join();

        assertEquals(Boolean.FALSE, otherThreadSawSafepoint.get());
    }

    // ---------------------------------------------------------------------------- block access

    @Test
    void writingABlockOwnedByAnotherRegionIsRejected() throws Exception {
        Region region = claimChunk(0, 0);

        // Pretend the region is mid-tick on a thread that is not this one, which is exactly the
        // situation the invariant exists to protect: a tick in flight, someone else writing.
        AtomicReference<Throwable> caught = new AtomicReference<>();
        runAsRegionOwner(region, () -> {
            Thread intruder = new Thread(() -> {
                try {
                    world.setBlock(5, 70, 5, 1);
                } catch (Throwable t) {
                    caught.set(t);
                }
            }, "intruder");
            intruder.start();
            intruder.join();
        });

        Throwable error = caught.get();
        assertNotNull(error, "writing another region's block from a foreign thread was allowed");
        assertTrue(error instanceof IllegalStateException, String.valueOf(error));
        assertTrue(error.getMessage().contains("region #" + region.id()), error.getMessage());
        assertTrue(error.getMessage().contains("region.post"), error.getMessage());
    }

    @Test
    void readingABlockOwnedByAnotherRegionIsRejected() throws Exception {
        Region region = claimChunk(0, 0);

        // Reads are checked too. A torn read is not obviously destructive, but acting on a value
        // another thread is halfway through changing is how a "block reverted" bug starts.
        AtomicReference<Throwable> caught = new AtomicReference<>();
        runAsRegionOwner(region, () -> {
            Thread intruder = new Thread(() -> {
                try {
                    world.getBlock(5, 70, 5);
                } catch (Throwable t) {
                    caught.set(t);
                }
            }, "intruder");
            intruder.start();
            intruder.join();
        });

        assertNotNull(caught.get(), "reading another region's block from a foreign thread was allowed");
    }

    @Test
    void theOwningThreadMayWriteItsOwnBlocks() throws Exception {
        Region region = claimChunk(0, 0);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        runAsRegionOwner(region, () -> {
            try {
                world.setBlock(5, 70, 5, 1);
                world.getBlock(5, 70, 5);
            } catch (Throwable t) {
                caught.set(t);
            }
        });

        assertNull(caught.get(), String.valueOf(caught.get()));
    }

    @Test
    void anUnownedChunkIsFreeForAnyThread() {
        // Nothing owns chunk 40,40: it is generating, loading, or already unloaded. There is no
        // tick to race with, and world generation legitimately writes from its own pool.
        Ownership.checkBlockAccess(manager, "Writing a block", 640, 70, 640);
    }

    @Test
    void aSafepointMayTouchAnyRegionsBlocks() throws Exception {
        Region region = claimChunk(0, 0);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        runAsRegionOwner(region, () -> {
            Thread dispatcher = new Thread(() -> {
                Ownership.enterSafepoint();
                try {
                    world.setBlock(5, 70, 5, 1);
                } catch (Throwable t) {
                    caught.set(t);
                } finally {
                    Ownership.exitSafepoint();
                }
            }, "dispatcher");
            dispatcher.start();
            dispatcher.join();
        });

        assertNull(caught.get(), String.valueOf(caught.get()));
    }

    // ---------------------------------------------------------------------------- strict toggle

    @Test
    void strictModeOffSkipsTheExpensiveCheck() throws Exception {
        Region region = claimChunk(0, 0);
        Ownership.setStrict(false);

        // Production does not pay for the region lookup. That is a deliberate trade, so it is
        // pinned by a test: if someone makes these checks unconditional, this fails and they have
        // to decide knowingly rather than by accident.
        AtomicReference<Throwable> caught = new AtomicReference<>();
        runAsRegionOwner(region, () -> {
            Thread intruder = new Thread(() -> {
                try {
                    world.setBlock(5, 70, 5, 1);
                } catch (Throwable t) {
                    caught.set(t);
                }
            }, "intruder");
            intruder.start();
            intruder.join();
        });

        assertNull(caught.get(), "strict mode was off, so nothing should have been checked");
    }

    // --------------------------------------------------------------------------------- helpers

    /** Gives a region ownership of one chunk, the way a safepoint would. */
    private Region claimChunk(int chunkX, int chunkZ) {
        Ownership.enterSafepoint();
        try {
            manager.requestChunkAdd(chunkX, chunkZ);
            manager.applyPendingAtSafepoint();
        } finally {
            Ownership.exitSafepoint();
        }
        Region region = manager.regionForChunk(chunkX, chunkZ);
        assertNotNull(region, "chunk was not adopted by any region");
        return region;
    }

    /**
     * Runs {@code body} on a thread that the region believes is its tick thread.
     *
     * <p>Region.runTick is the only thing that normally sets the owner, and it also does a full
     * tick. This reaches in and sets it directly, because what is under test is the guard, not the
     * tick.
     */
    private void runAsRegionOwner(Region region, ThrowingRunnable body) throws Exception {
        Thread owner = new Thread(() -> {
            region.setOwnerForTesting(Thread.currentThread());
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                region.setOwnerForTesting(null);
            }
        }, "fake-region-owner");
        owner.start();
        owner.join();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
