package dev.quasar.world.storage;

import dev.quasar.nbt.Nbt;
import dev.quasar.nbt.NbtIo;
import dev.quasar.util.Log;
import dev.quasar.world.ChunkPos;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The world's chunk store: a directory of Anvil {@code .mca} files, plus the thread that writes
 * to them.
 *
 * <h2>Threading</h2>
 * Reads happen inline on world-gen threads, because a load is already off the tick path and the
 * caller would otherwise be blocking on generation anyway.
 *
 * <p>Writes are split deliberately. The caller builds the chunk's NBT at a safepoint, where nothing
 * can be mid-tick over the chunk; only that finished tree crosses to the IO thread, which does the
 * serialising, deflating and disk write. So a save costs a safepoint pause proportional to how much
 * was edited, and none proportional to disk speed.
 */
public final class RegionStorage implements Closeable {

    private final Path regionDirectory;
    private final Map<Long, AnvilRegionFile> openFiles = new ConcurrentHashMap<>();
    private final ExecutorService writer;

    private final AtomicLong chunksSaved = new AtomicLong();
    private final AtomicLong chunksLoaded = new AtomicLong();
    private final AtomicLong pendingWrites = new AtomicLong();

    public RegionStorage(Path worldDirectory) throws IOException {
        this(worldDirectory, "region");
    }

    /**
     * @param subdirectory which region tree this is. Vanilla keeps chunks under {@code region} and
     *                     entities under {@code entities}, both in the same file format, so one
     *                     implementation serves both.
     */
    public RegionStorage(Path worldDirectory, String subdirectory) throws IOException {
        this.regionDirectory = worldDirectory.resolve(subdirectory);
        Files.createDirectories(regionDirectory);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quasar-chunk-io");
            thread.setDaemon(true);
            return thread;
        });
        Log.info("World storage at %s (Anvil)", regionDirectory.toAbsolutePath());
    }

    public long chunksSaved() {
        return chunksSaved.get();
    }

    public long chunksLoaded() {
        return chunksLoaded.get();
    }

    public long pendingWrites() {
        return pendingWrites.get();
    }

    private static long regionKey(int chunkX, int chunkZ) {
        return ChunkPos.key(chunkX >> 5, chunkZ >> 5);
    }

    private Path pathFor(int chunkX, int chunkZ) {
        return regionDirectory.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
    }

    /**
     * @param createIfMissing whether to create the file when absent. Reads pass false: opening for
     *     read would otherwise leave an empty header-only file behind for every region a player
     *     merely passes through.
     * @return the region file, or {@code null} if absent and creation was not requested
     */
    private AnvilRegionFile regionFor(int chunkX, int chunkZ, boolean createIfMissing)
            throws IOException {
        long key = regionKey(chunkX, chunkZ);
        AnvilRegionFile existing = openFiles.get(key);
        if (existing != null) {
            return existing;
        }
        // computeIfAbsent cannot be used: opening a file throws, and a failed open must not leave
        // a null mapping behind.
        synchronized (openFiles) {
            AnvilRegionFile current = openFiles.get(key);
            if (current != null) {
                return current;
            }
            Path path = pathFor(chunkX, chunkZ);
            if (!createIfMissing && !Files.isRegularFile(path)) {
                return null;
            }
            AnvilRegionFile opened = new AnvilRegionFile(path);
            openFiles.put(key, opened);
            return opened;
        }
    }

    /** @return the chunk's NBT, or {@code null} when absent or unreadable */
    public Nbt.NbtCompound readChunk(int chunkX, int chunkZ) {
        try {
            AnvilRegionFile region = regionFor(chunkX, chunkZ, false);
            if (region == null) {
                return null;
            }
            AnvilRegionFile.Payload payload = region.read(chunkX & 31, chunkZ & 31);
            if (payload == null) {
                return null;
            }
            Nbt.NbtCompound root = NbtIo.read(payload.data(), payload.compression());
            chunksLoaded.incrementAndGet();
            return root;
        } catch (IOException | RuntimeException e) {
            // A corrupt chunk costs that chunk, not the server: it is regenerated instead.
            Log.warn("Could not read chunk %d,%d: %s", chunkX, chunkZ, e.toString());
            return null;
        }
    }

    /**
     * Queues a chunk for writing. Returns immediately; the NBT tree must not be mutated afterwards.
     */
    public void writeChunkAsync(int chunkX, int chunkZ, Nbt.NbtCompound root) {
        pendingWrites.incrementAndGet();
        writer.execute(() -> {
            try {
                byte[] compressed = NbtIo.write(root, NbtIo.COMPRESSION_ZLIB);
                regionFor(chunkX, chunkZ, true)
                        .write(chunkX & 31, chunkZ & 31, compressed, AnvilRegionFile.COMPRESSION_ZLIB);
                chunksSaved.incrementAndGet();
            } catch (IOException e) {
                Log.error("Could not save chunk " + chunkX + "," + chunkZ, e);
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
    }

    /** Blocks until queued writes have been applied and flushed to disk. */
    public void flush(long timeout, TimeUnit unit) {
        try {
            // A no-op queued behind the writes completes only once they have all run.
            writer.submit(() -> { }).get(timeout, unit);
        } catch (Exception e) {
            Log.warn("Timed out waiting for chunk writes to drain: %s", e.getMessage());
        }
        for (AnvilRegionFile region : openFiles.values()) {
            try {
                region.flush();
            } catch (IOException e) {
                Log.warn("Could not flush a region file: %s", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        flush(30, TimeUnit.SECONDS);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        for (AnvilRegionFile region : openFiles.values()) {
            try {
                region.close();
            } catch (IOException e) {
                Log.warn("Could not close a region file: %s", e.getMessage());
            }
        }
        openFiles.clear();
        Log.info("World storage closed — %d chunk(s) saved, %d loaded this session",
                chunksSaved.get(), chunksLoaded.get());
    }
}
