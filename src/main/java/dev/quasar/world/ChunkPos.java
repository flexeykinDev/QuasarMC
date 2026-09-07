package dev.quasar.world;

/**
 * A chunk coordinate, plus the packing used everywhere a chunk is used as a map key.
 *
 * <p>Hot paths use the packed {@code long} form directly (see {@link #key}) and never allocate one
 * of these records; the record exists for readable APIs and logging.
 */
public record ChunkPos(int x, int z) {

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public static ChunkPos fromKey(long key) {
        return new ChunkPos(keyX(key), keyZ(key));
    }

    public static ChunkPos ofBlock(double blockX, double blockZ) {
        return new ChunkPos((int) Math.floor(blockX) >> 4, (int) Math.floor(blockZ) >> 4);
    }

    public long key() {
        return key(x, z);
    }

    public int minBlockX() { return x << 4; }

    public int minBlockZ() { return z << 4; }

    @Override
    public String toString() {
        return "[" + x + ", " + z + "]";
    }
}
