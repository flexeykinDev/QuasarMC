package dev.quasar.world;

import dev.quasar.nbt.Nbt;
import dev.quasar.net.ByteBufs;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;
import dev.quasar.world.light.LightStorage;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

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

    /** Sky and block light. Lazily sized inside {@link LightStorage}; see there for why. */
    private final LightStorage light;

    /**
     * Block entities, keyed by position within the chunk.
     *
     * <p>Compounds are stored exactly as they appear on disk, {@code id} and coordinates included.
     * Storing them verbatim is what lets a chunk carrying block entities this server does not model
     * — spawners, signs, beehives — be loaded, held and written back unchanged instead of being
     * dropped.
     */
    private final Int2ObjectOpenHashMap<Nbt.NbtCompound> blockEntities = new Int2ObjectOpenHashMap<>();

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
        this.light = new LightStorage(height / ChunkSection.SIZE, minY);
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

    public LightStorage light() { return light; }

    /**
     * Whether the initial sky-light column pass has run.
     *
     * <p>Generation does it on the world-gen thread, where it is free. A chunk that reaches a region
     * without passing through there would otherwise stay black forever, because seeding only
     * *spreads* light and has nothing to spread.
     */
    public boolean isLit() { return lit; }

    public void markLit() { this.lit = true; }

    private boolean lit;

    /**
     * Y of the highest block in this chunk that light cannot pass through, or {@link #minY()} - 1
     * when the chunk is empty.
     *
     * <p>Everything above this is open sky in every column, so the light engine can fill those
     * sections uniformly instead of walking them.
     */
    public int highestOccludingY() {
        int highest = minY - 1;
        for (int i = 0; i < 256; i++) {
            int top = heightmap[i] + minY - 1;
            if (top > highest) {
                highest = top;
            }
        }
        return highest;
    }

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
            discardBlockEntityIfBlockChanged(localX, y, localZ, previous, state);
        }
    }

    /**
     * Drops a block entity when the block itself is replaced.
     *
     * <p>Compares block <em>names</em>, not states: a chest gaining a connection or a barrel being
     * opened changes state without becoming a different block, and dropping its contents for that
     * would be a data-loss bug.
     */
    private void discardBlockEntityIfBlockChanged(int localX, int y, int localZ,
                                                  int previous, int state) {
        int key = blockEntityKey(localX, y, localZ);
        if (!blockEntities.containsKey(key)) {
            return;
        }
        BlockStateRegistry.State before = BlockStateRegistry.byId(previous);
        BlockStateRegistry.State after = BlockStateRegistry.byId(state);
        String beforeName = before == null ? null : before.name();
        String afterName = after == null ? null : after.name();
        if (beforeName == null || !beforeName.equals(afterName)) {
            blockEntities.remove(key);
        }
    }

    private int blockEntityKey(int localX, int y, int localZ) {
        return ((y - minY) << 8) | (localZ << 4) | localX;
    }

    /** @return the block entity at this position, or {@code null} */
    public Nbt.NbtCompound blockEntity(int localX, int y, int localZ) {
        return blockEntities.get(blockEntityKey(localX, y, localZ));
    }

    public void setBlockEntity(int localX, int y, int localZ, Nbt.NbtCompound data) {
        blockEntities.put(blockEntityKey(localX, y, localZ), data);
        dirty = true;
    }

    public void removeBlockEntity(int localX, int y, int localZ) {
        if (blockEntities.remove(blockEntityKey(localX, y, localZ)) != null) {
            dirty = true;
        }
    }

    /** Every block entity in this chunk, for serialisation. */
    public Iterable<Nbt.NbtCompound> blockEntities() {
        return blockEntities.values();
    }

    public int blockEntityCount() {
        return blockEntities.size();
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
     * Writes the light payload: four masks, then the arrays for the sections a mask marked present.
     *
     * <p>A section is announced as present or empty, never both. The protocol has an "all zero"
     * mask but no "all fifteen" one, so a uniformly daylit section still has to be sent as 2048
     * real bytes -- only fully dark sections get to be a single mask bit, which is most of them
     * underground.
     */
    public void writeLight(ByteBuf buf) {
        // Light arrays cover the sections plus one below and one above the world.
        int lightSections = sections.length + 2;

        long[] skyMask = new long[(lightSections + 63) / 64];
        long[] blockMask = new long[(lightSections + 63) / 64];
        long[] emptySkyMask = new long[(lightSections + 63) / 64];
        long[] emptyBlockMask = new long[(lightSections + 63) / 64];

        for (int i = 0; i < lightSections; i++) {
            if (light.isSkySectionEmpty(i)) {
                emptySkyMask[i >> 6] |= 1L << (i & 63);
            } else {
                skyMask[i >> 6] |= 1L << (i & 63);
            }
            if (light.isBlockSectionEmpty(i)) {
                emptyBlockMask[i >> 6] |= 1L << (i & 63);
            } else {
                blockMask[i >> 6] |= 1L << (i & 63);
            }
        }

        writeLongArray(buf, skyMask);
        writeLongArray(buf, blockMask);
        writeLongArray(buf, emptySkyMask);
        writeLongArray(buf, emptyBlockMask);

        writeLightArrays(buf, lightSections, skyMask, true);
        writeLightArrays(buf, lightSections, blockMask, false);
    }

    private void writeLightArrays(ByteBuf buf, int lightSections, long[] mask, boolean sky) {
        int present = 0;
        for (int i = 0; i < lightSections; i++) {
            if ((mask[i >> 6] & (1L << (i & 63))) != 0) {
                present++;
            }
        }
        ByteBufs.writeVarInt(buf, present);
        for (int i = 0; i < lightSections; i++) {
            if ((mask[i >> 6] & (1L << (i & 63))) == 0) {
                continue;
            }
            byte[] data = sky ? light.skyBytes(i) : light.blockBytes(i);
            ByteBufs.writeVarInt(buf, data.length);
            buf.writeBytes(data);
        }
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
