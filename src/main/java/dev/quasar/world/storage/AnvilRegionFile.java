package dev.quasar.world.storage;

import dev.quasar.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mojang's Anvil region file ({@code r.X.Z.mca}).
 *
 * <h2>Layout</h2>
 * <pre>
 * sector 0    location table: 1024 × (3-byte start sector, 1-byte sector count)
 * sector 1    timestamps:     1024 × 4-byte epoch seconds
 * sector 2+   chunk payloads: 4-byte length, 1-byte compression type, then data
 * </pre>
 * The length field counts the compression byte, so the payload is {@code length - 1} bytes. A
 * location entry of all zeroes means the chunk is not present.
 *
 * <p>This is the on-disk half of Anvil compatibility; {@link AnvilChunkCodec} handles the NBT that
 * lives inside these payloads.
 */
public final class AnvilRegionFile implements Closeable {

    public static final int CHUNKS_PER_AXIS = 32;

    /** Zlib. Vanilla writes this by default and reads any of gzip, zlib or uncompressed. */
    public static final byte COMPRESSION_ZLIB = 2;

    private static final int SECTOR_SIZE = 4096;
    private static final int ENTRY_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;
    private static final int HEADER_SECTORS = 2;

    private final Path path;
    private final RandomAccessFile file;
    private final ReentrantLock lock = new ReentrantLock();

    private final int[] startSector = new int[ENTRY_COUNT];
    private final int[] sectorCount = new int[ENTRY_COUNT];
    private final BitSet usedSectors = new BitSet();

    public AnvilRegionFile(Path path) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        for (int i = 0; i < HEADER_SECTORS; i++) {
            usedSectors.set(i);
        }
        if (file.length() < (long) HEADER_SECTORS * SECTOR_SIZE) {
            file.setLength((long) HEADER_SECTORS * SECTOR_SIZE);
            file.seek(0);
            file.write(new byte[HEADER_SECTORS * SECTOR_SIZE]);
        } else {
            readHeader();
        }
    }

    private void readHeader() throws IOException {
        file.seek(0);
        for (int i = 0; i < ENTRY_COUNT; i++) {
            int packed = file.readInt();
            startSector[i] = packed >>> 8;
            sectorCount[i] = packed & 0xFF;
            if (sectorCount[i] > 0) {
                for (int s = 0; s < sectorCount[i]; s++) {
                    usedSectors.set(startSector[i] + s);
                }
            }
        }
    }

    private void writeLocation(int index) throws IOException {
        file.seek((long) index * 4);
        file.writeInt((startSector[index] << 8) | (sectorCount[index] & 0xFF));
    }

    private void writeTimestamp(int index) throws IOException {
        file.seek(SECTOR_SIZE + (long) index * 4);
        file.writeInt((int) (System.currentTimeMillis() / 1000L));
    }

    private static int index(int localX, int localZ) {
        return localX + localZ * CHUNKS_PER_AXIS;
    }

    /**
     * Reads a chunk payload.
     *
     * @return the compressed NBT bytes and their compression type, or {@code null} if absent
     */
    public Payload read(int localX, int localZ) throws IOException {
        lock.lock();
        try {
            int index = index(localX, localZ);
            if (sectorCount[index] == 0) {
                return null;
            }
            file.seek((long) startSector[index] * SECTOR_SIZE);
            int length = file.readInt();
            if (length <= 1 || length > sectorCount[index] * SECTOR_SIZE) {
                Log.warn("Region %s chunk %d,%d declares length %d; treating as absent",
                        path, localX, localZ, length);
                return null;
            }
            byte compression = file.readByte();
            byte[] data = new byte[length - 1];
            file.readFully(data);
            return new Payload(data, compression);
        } finally {
            lock.unlock();
        }
    }

    public void write(int localX, int localZ, byte[] compressedNbt, byte compression)
            throws IOException {
        lock.lock();
        try {
            int index = index(localX, localZ);
            int totalBytes = compressedNbt.length + 5; // length field + compression byte
            int needed = (totalBytes + SECTOR_SIZE - 1) / SECTOR_SIZE;
            if (needed > 255) {
                // Anvil's sector count is one byte; oversized chunks need the external-file
                // mechanism, which this server has no way to produce.
                Log.warn("Chunk %d,%d in %s needs %d sectors, over Anvil's 255 limit; not saved",
                        localX, localZ, path, needed);
                return;
            }

            if (sectorCount[index] < needed) {
                free(index);
                startSector[index] = allocate(needed);
                sectorCount[index] = needed;
            }
            writeLocation(index);
            writeTimestamp(index);

            file.seek((long) startSector[index] * SECTOR_SIZE);
            file.writeInt(compressedNbt.length + 1);
            file.writeByte(compression);
            file.write(compressedNbt);

            long required = (long) (startSector[index] + sectorCount[index]) * SECTOR_SIZE;
            if (file.length() < required) {
                file.setLength(required);
            }
        } finally {
            lock.unlock();
        }
    }

    private void free(int index) {
        for (int s = 0; s < sectorCount[index]; s++) {
            usedSectors.clear(startSector[index] + s);
        }
        startSector[index] = 0;
        sectorCount[index] = 0;
    }

    private int allocate(int sectors) {
        int candidate = HEADER_SECTORS;
        while (true) {
            int free = usedSectors.nextClearBit(candidate);
            boolean fits = true;
            for (int s = 0; s < sectors; s++) {
                if (usedSectors.get(free + s)) {
                    fits = false;
                    candidate = free + s + 1;
                    break;
                }
            }
            if (fits) {
                for (int s = 0; s < sectors; s++) {
                    usedSectors.set(free + s);
                }
                return free;
            }
        }
    }

    public void flush() throws IOException {
        lock.lock();
        try {
            file.getFD().sync();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            file.close();
        } finally {
            lock.unlock();
        }
    }

    /** A chunk's stored bytes together with how they are compressed. */
    public record Payload(byte[] data, byte compression) {}
}
