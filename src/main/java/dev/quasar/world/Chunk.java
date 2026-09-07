package dev.quasar.world;

import dev.quasar.nbt.Nbt;
import dev.quasar.net.ByteBufs;
import dev.quasar.world.block.Blocks;
import io.netty.buffer.ByteBuf;

/**
 * A 16×16 column of {@link ChunkSection}s.
 *
 * <p>Owned by exactly one {@link dev.quasar.engine.Region} at a time; see that class for the
 * threading rules. Nothing in here locks.
 */
public final class Chunk {

    private final int x;
    private final int z;
    private final int minY;
    private final ChunkSection[] sections;

    /** MOTION_BLOCKING heightmap, stored as height above {@link #minY}. */
    private final short[] heightmap = new short[256];

    /** Set when block data changed since the last save. */
    private boolean dirty;

    /**
     * Set when this chunk was loaded with blocks that could not be mapped, so what is in memory is
     * a degraded version of what is on disk. Such a chunk is never written back — overwriting
     * someone's world with placeholders because a lookup table was missing would be far worse than
     * declining to save.
     */
    private boolean saveBlocked;

    public Chunk(int x, int z, int minY, int height) {
        this.x = x;
        this.z = z;
        this.minY = minY;
        this.sections = new ChunkSection[height / ChunkSection.SIZE];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new ChunkSection(Blocks.AIR);
        }
    }

    public int x() { return x; }

    public int z() { return z; }

    public long key() { return ChunkPos.key(x, z); }

    public int sectionCount() { return sections.length; }

    public int minY() { return minY; }

    public int maxY() { return minY + sections.length * ChunkSection.SIZE - 1; }

    public boolean isDirty() { return dirty; }

    public void clearDirty() { dirty = false; }

    public boolean isSaveBlocked() { return saveBlocked; }

    public void blockSaving() { saveBlocked = true; }

    /** Packed MOTION_BLOCKING heightmap, in the layout both the protocol and Anvil expect. */
    public long[] packedHeightmap() { return packHeightmap(); }

    public ChunkSection section(int index) { return sections[index]; }

    /** @param localX 0-15, @param localZ 0-15, y in absolute world coordinates */
    public int getBlock(int localX, int y, int localZ) {
        int sectionIndex = (y - minY) >> 4;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return Blocks.AIR;
        }
        return sections[sectionIndex].get(localX, y & 15, localZ);
    }

    public void setBlock(int localX, int y, int localZ, int state) {
        int sectionIndex = (y - minY) >> 4;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return;
        }
        int previous = sections[sectionIndex].set(localX, y & 15, localZ, state);
        if (previous != state) {
            dirty = true;
            updateHeightmapAfterSet(localX, y, localZ, state);
        }
    }

    private void updateHeightmapAfterSet(int localX, int y, int localZ, int state) {
        int columnIndex = (localZ << 4) | localX;
        int relative = y - minY + 1;
        if (!Blocks.isAir(state)) {
            if (relative > heightmap[columnIndex]) {
                heightmap[columnIndex] = (short) relative;
            }
        } else if (heightmap[columnIndex] == relative) {
            // The block that defined this column's height was removed — walk down for the new one.
            int scan = y - 1;
            while (scan >= minY && Blocks.isAir(getBlock(localX, scan, localZ))) {
                scan--;
            }
            heightmap[columnIndex] = (short) (scan - minY + 1);
        }
    }

    /** Recomputes the whole heightmap. Called once after generation rather than per block. */
    public void recalculateHeightmap() {
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int scan = maxY();
                while (scan >= minY && Blocks.isAir(getBlock(localX, scan, localZ))) {
                    scan--;
                }
                heightmap[(localZ << 4) | localX] = (short) (scan - minY + 1);
            }
        }
    }

    /**
     * Heightmaps as the chunk-data packet wants them: a compound of packed long arrays, with bits
     * per entry wide enough for the world height and entries never straddling a long boundary.
     */
    public Nbt.NbtCompound heightmapNbt() {
        long[] packed = packHeightmap();
        return Nbt.compound()
                .put("MOTION_BLOCKING", new Nbt.NbtLongArray(packed))
                .put("WORLD_SURFACE", new Nbt.NbtLongArray(packed));
    }

    private long[] packHeightmap() {
        int worldHeight = sections.length * ChunkSection.SIZE;
        int bits = 32 - Integer.numberOfLeadingZeros(worldHeight); // ceil(log2(height + 1))
        int entriesPerLong = 64 / bits;
        int longCount = (256 + entriesPerLong - 1) / entriesPerLong;
        long[] out = new long[longCount];
        long mask = (1L << bits) - 1;

        for (int i = 0; i < 256; i++) {
            int longIndex = i / entriesPerLong;
            int slot = i % entriesPerLong;
            out[longIndex] |= (heightmap[i] & mask) << (slot * bits);
        }
        return out;
    }

    /** Writes the sections blob that sits inside the chunk-data packet's length-prefixed field. */
    public void writeSections(ByteBuf buf, int biomeId) {
        for (ChunkSection section : sections) {
            section.write(buf, biomeId);
        }
    }

    /**
     * Writes the light payload.
     *
     * <p>This server ships full-bright sky light everywhere rather than running a light engine:
     * a real propagating light engine is a large piece of work on its own, and shipping 0xFF keeps
     * the world visible instead of pitch black. Block light is sent as empty.
     */
    public void writeLight(ByteBuf buf) {
        // Light arrays cover the sections plus one below and one above the world.
        int lightSections = sections.length + 2;

        long[] skyMask = bitSet(lightSections, true);
        long[] blockMask = bitSet(lightSections, false);
        long[] emptySkyMask = bitSet(lightSections, false);
        long[] emptyBlockMask = bitSet(lightSections, true);

        writeLongArray(buf, skyMask);
        writeLongArray(buf, blockMask);
        writeLongArray(buf, emptySkyMask);
        writeLongArray(buf, emptyBlockMask);

        ByteBufs.writeVarInt(buf, lightSections); // sky light arrays
        byte[] full = new byte[2048];
        java.util.Arrays.fill(full, (byte) 0xFF);
        for (int i = 0; i < lightSections; i++) {
            ByteBufs.writeVarInt(buf, full.length);
            buf.writeBytes(full);
        }

        ByteBufs.writeVarInt(buf, 0); // no block light arrays
    }

    private static long[] bitSet(int bitCount, boolean allSet) {
        long[] words = new long[(bitCount + 63) / 64];
        if (allSet) {
            for (int i = 0; i < bitCount; i++) {
                words[i >> 6] |= 1L << (i & 63);
            }
        }
        return words;
    }

    private static void writeLongArray(ByteBuf buf, long[] values) {
        ByteBufs.writeVarInt(buf, values.length);
        for (long v : values) {
            buf.writeLong(v);
        }
    }
}
