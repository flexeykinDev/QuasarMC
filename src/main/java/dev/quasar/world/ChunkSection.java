package dev.quasar.world;

import dev.quasar.net.ByteBufs;
import dev.quasar.world.block.Blocks;
import io.netty.buffer.ByteBuf;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A 16×16×16 cube of block states.
 *
 * <h2>Storage</h2>
 * A section starts <em>uniform</em>: one state, no backing array, sixteen bytes of overhead. Most
 * sections in a generated world are pure air or pure stone and stay that way forever, so the array
 * is only materialised on the first write that actually differs. A freshly generated 384-block-tall
 * world is roughly two orders of magnitude cheaper to hold in memory this way.
 *
 * <p>No synchronisation: a section belongs to a chunk, which belongs to exactly one region, which
 * is ticked by one thread at a time.
 */
public final class ChunkSection {

    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    /** Bits per entry for the direct (unpalettised) format. Fits every state ID in modern versions. */
    private static final int DIRECT_BITS = 15;

    private short uniformState;
    private short[] states;
    private int nonAirCount;

    public ChunkSection(int fillState) {
        this.uniformState = (short) fillState;
        this.nonAirCount = Blocks.isAir(fillState) ? 0 : VOLUME;
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public boolean isUniform() {
        return states == null;
    }

    public boolean isEmpty() {
        return nonAirCount == 0;
    }

    public int nonAirCount() {
        return nonAirCount;
    }

    /**
     * The single state filling a uniform section, or -1 when it varies.
     *
     * <p>Lets a caller skip 4096 blocks with one comparison. The physics scan needs this: without
     * it, adopting a chunk walks every block of every section, and most sections of a generated
     * world are solid stone or empty air with nothing that could ever move.
     */
    public int singleState() {
        return states == null ? uniformState : -1;
    }

    public int get(int x, int y, int z) {
        return states == null ? uniformState & 0xFFFF : states[index(x, y, z)] & 0xFFFF;
    }

    /** @return the previous state at that position */
    public int set(int x, int y, int z, int state) {
        int previous;
        if (states == null) {
            previous = uniformState & 0xFFFF;
            if (previous == state) {
                return previous;
            }
            materialise();
        } else {
            previous = states[index(x, y, z)] & 0xFFFF;
            if (previous == state) {
                return previous;
            }
        }
        states[index(x, y, z)] = (short) state;
        if (Blocks.isAir(previous) && !Blocks.isAir(state)) {
            nonAirCount++;
        } else if (!Blocks.isAir(previous) && Blocks.isAir(state)) {
            nonAirCount--;
        }
        return previous;
    }

    /**
     * Writes this section to the save format.
     *
     * <p>A uniform section costs three bytes rather than eight kilobytes, which is what keeps saved
     * regions small: most sections of a generated world are pure air or pure stone.
     */
    public void save(DataOutputStream out) throws IOException {
        if (states == null) {
            out.writeByte(0);
            out.writeShort(uniformState);
        } else {
            out.writeByte(1);
            for (short state : states) {
                out.writeShort(state);
            }
        }
    }

    /** Reads a section previously written by {@link #save}. */
    public void load(DataInputStream in) throws IOException {
        int kind = in.readByte();
        if (kind == 0) {
            uniformState = in.readShort();
            states = null;
        } else if (kind == 1) {
            short[] loaded = new short[VOLUME];
            for (int i = 0; i < VOLUME; i++) {
                loaded[i] = in.readShort();
            }
            states = loaded;
        } else {
            throw new IOException("Unknown chunk section kind " + kind);
        }
        recountNonAir();
    }

    /**
     * Recomputes the non-air census after a bulk load.
     *
     * <p>Derived rather than stored: it must agree with the block data exactly — the client uses it
     * to decide whether a section is empty — and a value read from disk could disagree with the
     * blocks beside it if either were ever written inconsistently.
     */
    private void recountNonAir() {
        if (states == null) {
            nonAirCount = Blocks.isAir(uniformState & 0xFFFF) ? 0 : VOLUME;
            return;
        }
        int count = 0;
        for (short state : states) {
            if (!Blocks.isAir(state & 0xFFFF)) {
                count++;
            }
        }
        nonAirCount = count;
    }

    private void materialise() {
        short[] expanded = new short[VOLUME];
        if (uniformState != 0) {
            java.util.Arrays.fill(expanded, uniformState);
        }
        states = expanded;
    }

    /**
     * Writes this section in the chunk-data wire format: block count, then the block-state paletted
     * container, then the biome paletted container.
     *
     * <p>Uniform sections take the single-valued path (bits-per-entry 0), which is both the cheapest
     * to produce and the cheapest for the client to read.
     */
    public void write(ByteBuf buf, int biomeId) {
        buf.writeShort(nonAirCount);

        if (states == null) {
            writeSingleValued(buf, uniformState & 0xFFFF);
        } else {
            writeDirect(buf);
        }

        // Biomes: one value for the whole section. Same container format, 64 entries rather than 4096.
        writeSingleValued(buf, biomeId);
    }

    private static void writeSingleValued(ByteBuf buf, int value) {
        buf.writeByte(0);                 // bits per entry = 0 -> single-valued palette
        ByteBufs.writeVarInt(buf, value); // the one value
        ByteBufs.writeVarInt(buf, 0);     // empty data array
    }

    private void writeDirect(ByteBuf buf) {
        buf.writeByte(DIRECT_BITS);
        int entriesPerLong = 64 / DIRECT_BITS; // 4; the remaining bits of each long stay unused
        int longCount = (VOLUME + entriesPerLong - 1) / entriesPerLong;
        ByteBufs.writeVarInt(buf, longCount);

        int index = 0;
        for (int i = 0; i < longCount; i++) {
            long packed = 0;
            for (int slot = 0; slot < entriesPerLong && index < VOLUME; slot++, index++) {
                packed |= (long) (states[index] & 0x7FFF) << (slot * DIRECT_BITS);
            }
            buf.writeLong(packed);
        }
    }
}
