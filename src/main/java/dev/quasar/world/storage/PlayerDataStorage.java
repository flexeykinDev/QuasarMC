package dev.quasar.world.storage;

import dev.quasar.nbt.Nbt;
import dev.quasar.nbt.NbtIo;
import dev.quasar.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per-player state on disk: {@code <world>/playerdata/<uuid>.dat}, gzipped NBT.
 *
 * <p>Same location and encoding a vanilla server uses, so the files sit alongside a world without
 * looking foreign. Only a subset of vanilla's fields is written — position, rotation and this
 * server's hotbar — and anything else vanilla expects is simply absent, which it fills with
 * defaults.
 *
 * <p>Writes go through a temporary file and an atomic move. A player's position is rewritten every
 * time they leave and on every world save; a crash midway through a plain write would leave a
 * truncated file and lose the player entirely, whereas a failed move just leaves the previous one.
 */
public final class PlayerDataStorage {

    private final Path directory;
    private final ExecutorService writer;

    public PlayerDataStorage(Path worldDirectory) throws IOException {
        this.directory = worldDirectory.resolve("playerdata");
        Files.createDirectories(directory);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quasar-playerdata-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    private Path fileFor(UUID uuid) {
        return directory.resolve(uuid + ".dat");
    }

    /** @return the stored data, or {@code null} when this player has never been saved */
    public Nbt.NbtCompound load(UUID uuid) {
        Path file = fileFor(uuid);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return NbtIo.read(Files.readAllBytes(file), NbtIo.COMPRESSION_GZIP);
        } catch (IOException | RuntimeException e) {
            // A corrupt file costs that player their position, not their ability to join.
            Log.warn("Could not read player data for %s (%s); treating as a new player",
                    uuid, e.toString());
            return null;
        }
    }

    /**
     * Queues a write. Returns immediately; the tree must not be mutated afterwards.
     *
     * <p>Callers build the tree on the thread that owns the player, so only the finished tree
     * crosses to the IO thread.
     */
    public void saveAsync(UUID uuid, Nbt.NbtCompound data) {
        writer.execute(() -> {
            Path file = fileFor(uuid);
            Path temporary = directory.resolve(uuid + ".dat.tmp");
            try {
                Files.write(temporary, NbtIo.write(data, NbtIo.COMPRESSION_GZIP));
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Log.error("Could not save player data for " + uuid, e);
            }
        });
    }

    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.warn("Player data writes did not drain in time");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
