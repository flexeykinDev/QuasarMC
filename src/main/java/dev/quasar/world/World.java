package dev.quasar.world;

import dev.quasar.engine.Region;
import dev.quasar.engine.Ownership;
import dev.quasar.world.light.LightEngine;
import dev.quasar.engine.RegionManager;
import dev.quasar.util.Log;
import dev.quasar.world.block.Blocks;
import dev.quasar.nbt.Nbt;
import dev.quasar.world.gen.ChunkGenerator;
import dev.quasar.world.storage.AnvilChunkCodec;
import dev.quasar.world.storage.RegionStorage;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dimension: its chunk store, its generator, and the ticket bookkeeping that decides what stays
 * loaded.
 *
 * <h2>Chunk lifecycle</h2>
 * <ol>
 *   <li>Something takes a ticket ({@link #addTicket}) — normally a player's view distance.</li>
 *   <li>On 0→1 the chunk is generated on the world-gen pool, off any tick thread.</li>
 *   <li>When generation finishes the chunk is published and a region is asked to adopt it; the
 *       adoption lands at the next safepoint.</li>
 *   <li>On 1→0 the chunk is queued for unload and actually dropped at a safepoint, so no region can
 *       be mid-tick over it.</li>
 * </ol>
 */
public final class World {

    private final String name;
    private final long seed;
    private final int minY;
    private final int height;
    private final ChunkGenerator generator;

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final Long2IntOpenHashMap tickets = new Long2IntOpenHashMap();
    private final LongOpenHashSet generating = new LongOpenHashSet();
    private final LongOpenHashSet pendingUnload = new LongOpenHashSet();

    private final ForkJoinPool worldGenPool;
    private final AtomicLong chunksGenerated = new AtomicLong();
    private final AtomicInteger generationsInFlight = new AtomicInteger();

    /** Benchmarking knob; see {@link #tickRegion}. */
    private long syntheticTickLoadNanos;

    /** Chunk store, or {@code null} when persistence is switched off. */
    private final RegionStorage storage;

    private RegionManager regionManager;

    public World(String name, long seed, int minY, int height, ChunkGenerator generator,
                 int genThreads, RegionStorage storage) {
        this.storage = storage;
        this.name = name;
        this.seed = seed;
        this.minY = minY;
        this.height = height;
        this.generator = generator;
        this.tickets.defaultReturnValue(0);

        AtomicInteger counter = new AtomicInteger();
        this.worldGenPool = new ForkJoinPool(
                genThreads,
                pool -> {
                    ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    thread.setName("quasar-worldgen-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                (thread, error) -> Log.error("World-gen thread " + thread.getName() + " failed", error),
                true);
    }

    public void setRegionManager(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    public void setSyntheticTickLoadMicros(int micros) {
        this.syntheticTickLoadNanos = micros * 1000L;
    }

    public String name() { return name; }

    public long seed() { return seed; }

    public int minY() { return minY; }

    public int height() { return height; }

    public int maxY() { return minY + height - 1; }

    public ChunkGenerator generator() { return generator; }

    public int loadedChunkCount() { return chunks.size(); }

    public long chunksGenerated() { return chunksGenerated.get(); }

    public int generationsInFlight() { return generationsInFlight.get(); }

    // -------------------------------------------------------------------------------- tickets

    /** Takes a load ticket. Safe from any thread. */
    public void addTicket(int chunkX, int chunkZ) {
        long key = ChunkPos.key(chunkX, chunkZ);
        boolean shouldGenerate = false;
        synchronized (tickets) {
            int count = tickets.addTo(key, 1);
            pendingUnload.remove(key);
            if (count == 0 && !chunks.containsKey(key) && generating.add(key)) {
                shouldGenerate = true;
            }
        }
        if (shouldGenerate) {
            submitGeneration(chunkX, chunkZ, key);
        }
    }

    /** Releases a load ticket. Safe from any thread. */
    public void removeTicket(int chunkX, int chunkZ) {
        long key = ChunkPos.key(chunkX, chunkZ);
        synchronized (tickets) {
            int remaining = tickets.addTo(key, -1) - 1;
            if (remaining <= 0) {
                tickets.remove(key);
                if (chunks.containsKey(key)) {
                    pendingUnload.add(key);
                }
            }
        }
    }

    private void submitGeneration(int chunkX, int chunkZ, long key) {
        generationsInFlight.incrementAndGet();
        worldGenPool.execute(() -> {
            try {
                // Saved data wins over the generator: a chunk someone has edited must come back as
                // they left it, not as the noise function would rebuild it.
                Chunk chunk = loadFromDisk(chunkX, chunkZ);
                if (chunk == null) {
                    chunk = new Chunk(chunkX, chunkZ, minY, height);
                    generator.generate(chunk);
                    chunk.clearDirty();
                }

                // Light it before publishing. At this point the chunk belongs to nobody but this
                // thread, so the column pass costs no one else anything; the sideways spread that
                // needs neighbours happens later, on the owning region's thread.
                LightEngine.lightNewChunk(chunk);

                // Publish before asking for adoption, so the region finds it at the safepoint.
                chunks.put(key, chunk);
                chunksGenerated.incrementAndGet();

                // The ticket that triggered this generation may already be gone — a player can
                // easily cross a chunk boundary in less time than a chunk takes to generate. If
                // nobody still wants it, queue it for unload instead of handing it to a region;
                // without this check such chunks stay resident with no ticket and never leave.
                boolean stillWanted;
                synchronized (tickets) {
                    generating.remove(key);
                    stillWanted = tickets.get(key) > 0;
                    if (!stillWanted) {
                        pendingUnload.add(key);
                    }
                }
                if (stillWanted && regionManager != null) {
                    regionManager.requestChunkAdd(chunkX, chunkZ);
                }
            } catch (Throwable t) {
                synchronized (tickets) {
                    generating.remove(key);
                }
                Log.error("Failed to generate chunk " + chunkX + ", " + chunkZ, t);
            } finally {
                generationsInFlight.decrementAndGet();
            }
        });
    }

    private Chunk loadFromDisk(int chunkX, int chunkZ) {
        if (storage == null) {
            return null;
        }
        Nbt.NbtCompound root = storage.readChunk(chunkX, chunkZ);
        if (root == null) {
            return null;
        }
        return AnvilChunkCodec.fromNbt(root, chunkX, chunkZ, minY, height);
    }

    /**
     * Serialises a chunk and queues it for writing, if it has unsaved changes.
     *
     * <p>Safepoint-only. Serialisation reads the whole chunk, so it must happen while no region can
     * be ticking over it; only the resulting byte array is handed to the IO thread.
     */
    private void saveIfDirty(Chunk chunk) {
        if (storage == null || !chunk.isDirty()) {
            return;
        }
        if (chunk.isSaveBlocked()) {
            // Loaded with blocks or block entities this server cannot represent. What is in memory
            // is a degraded copy, so writing it back would destroy the real thing on disk.
            return;
        }
        Nbt.NbtCompound root = AnvilChunkCodec.toNbt(chunk);
        chunk.clearDirty();
        storage.writeChunkAsync(chunk.x(), chunk.z(), root);
    }

    /**
     * Writes loaded chunks. Safepoint-only.
     *
     * @param includeUnedited also write chunks that exactly match what the generator would produce.
     *     Normally pointless — regenerating them is free and deterministic — but it is what turns a
     *     world into something another program can open and see terrain in, rather than a sparse
     *     scattering of the chunks somebody happened to edit.
     * @return the number of chunks queued for writing
     */
    public int saveAllAtSafepoint(boolean includeUnedited) {
        if (storage == null) {
            return 0;
        }
        int saved = 0;
        for (Chunk chunk : chunks.values()) {
            if (chunk.isSaveBlocked()) {
                continue;
            }
            if (chunk.isDirty()) {
                saveIfDirty(chunk);
                saved++;
            } else if (includeUnedited) {
                storage.writeChunkAsync(chunk.x(), chunk.z(), AnvilChunkCodec.toNbt(chunk));
                saved++;
            }
        }
        return saved;
    }

    /**
     * Drops chunks whose last ticket went away. Safepoint-only: a region may be holding this chunk
     * and must be given the chance to release it first.
     */
    public int applyUnloadsAtSafepoint() {
        long[] keys;
        synchronized (tickets) {
            if (pendingUnload.isEmpty()) {
                return 0;
            }
            keys = pendingUnload.toLongArray();
            pendingUnload.clear();
        }
        int unloaded = 0;
        for (long key : keys) {
            synchronized (tickets) {
                if (tickets.get(key) > 0) {
                    continue; // re-ticketed while we were queued
                }
            }
            Chunk chunk = chunks.get(key);
            if (chunk != null) {
                // Save before dropping the reference, or the edit is gone.
                saveIfDirty(chunk);
                chunks.remove(key);
                unloaded++;
                if (regionManager != null) {
                    regionManager.requestChunkRemove(ChunkPos.keyX(key), ChunkPos.keyZ(key));
                }
            }
        }
        if (unloaded > 0) {
            Log.debug("Unloaded %d chunk(s); %d still loaded", unloaded, chunks.size());
        }
        return unloaded;
    }

    // ---------------------------------------------------------------------------- block access

    /**
     * A light engine not bound to any region. Test-only.
     *
     * <p>In the server the queue belongs to whichever region is ticking; a test has no region, and
     * driving propagation directly is the point.
     */
    public LightEngine lightEngineForTesting() {
        if (testLightEngine == null) {
            testLightEngine = new LightEngine(this);
        }
        return testLightEngine;
    }

    private LightEngine testLightEngine;

    /** Inserts a chunk without generating it. Test-only. */
    public void putChunkForTesting(Chunk chunk) {
        chunks.put(ChunkPos.key(chunk.x(), chunk.z()), chunk);
    }

    public Chunk chunkAt(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.key(chunkX, chunkZ));
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return chunks.containsKey(ChunkPos.key(chunkX, chunkZ));
    }

    /**
     * Reads a block. Only valid for chunks owned by the calling region — see
     * {@link Region#assertOwned()}.
     */
    public int getBlock(int x, int y, int z) {
        Ownership.checkBlockAccess(regionManager, "Reading a block", x, y, z);
        Chunk chunk = chunkAt(x >> 4, z >> 4);
        return chunk == null ? Blocks.AIR : chunk.getBlock(x & 15, y, z & 15);
    }

    /** Writes a block. Same ownership rule as {@link #getBlock}. */
    public boolean setBlock(int x, int y, int z, int state) {
        Ownership.checkBlockAccess(regionManager, "Writing a block", x, y, z);
        Chunk chunk = chunkAt(x >> 4, z >> 4);
        if (chunk == null || y < minY || y > maxY()) {
            return false;
        }
        int previous = chunk.getBlock(x & 15, y, z & 15);
        chunk.setBlock(x & 15, y, z & 15, state);
        if (previous != state) {
            // Queued on the *calling region's* engine, not propagated here. The caller is mid-edit
            // on a region thread and a single break can cascade for thousands of blocks, so the
            // region drains it later under a budget.
            //
            // The queue belongs to the region rather than the world because two regions ticking in
            // parallel would otherwise be pushing and popping the same queue -- which is precisely
            // the shared mutable state this engine exists to not have.
            Region current = Region.current();
            if (current != null) {
                current.lightEngine().onBlockChanged(x, y, z, previous, state);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------- ticking

    /**
     * Per-region world work, run once per region tick on that region's thread.
     *
     * <p>Vanilla's random block ticks, fluid ticks and block-entity ticks would hang off here.
     * They are deliberately absent: this core implements the threading model and the protocol, not
     * the full block behaviour set, and pretending otherwise in a stub would be worse than a note.
     *
     * <p>The only thing here is the optional synthetic load used to benchmark the scheduler; see
     * {@code engine.synthetic-tick-load-micros}.
     */
    public void tickRegion(Region region) {
        if (syntheticTickLoadNanos > 0) {
            burnCpu(syntheticTickLoadNanos);
        }
    }

    /**
     * Busy-spins for the given duration.
     *
     * <p>Spinning rather than sleeping is the point: a sleeping thread would release its core and
     * make a serial scheduler look just as good as a parallel one, which would defeat the
     * measurement entirely.
     */
    private static void burnCpu(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long sink = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 64; i++) {
                sink += i * 31L;
            }
        }
        if (sink == Long.MIN_VALUE) {
            // Never true; exists only so the loop above cannot be optimised away.
            Log.trace("unreachable");
        }
    }

    public RegionStorage storage() {
        return storage;
    }

    public void shutdown() {
        worldGenPool.shutdown();
        try {
            if (!worldGenPool.awaitTermination(5, TimeUnit.SECONDS)) {
                worldGenPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worldGenPool.shutdownNow();
        }
        if (storage != null) {
            storage.close();
        }
    }
}
