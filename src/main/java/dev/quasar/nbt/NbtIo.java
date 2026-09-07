package dev.quasar.nbt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Disk NBT: named root compounds, optionally compressed.
 *
 * <p>Distinct from the network form in {@link Nbt#writeNetwork}, which drops the root name. Anvil
 * and {@code level.dat} both use the named form.
 *
 * <p>Reading is new here — until Anvil support the server only ever produced NBT, never consumed
 * it. The reader is deliberately defensive: chunk files are the one input this server parses that
 * it did not write itself, and a malformed one must cost that chunk rather than the process.
 */
public final class NbtIo {

    public static final int COMPRESSION_GZIP = 1;
    public static final int COMPRESSION_ZLIB = 2;
    public static final int COMPRESSION_NONE = 3;

    /** Guards against a hand-crafted file nesting deeply enough to blow the stack. */
    private static final int MAX_DEPTH = 512;

    private NbtIo() {}

    public static byte[] write(Nbt.NbtCompound root, int compression) throws IOException {
        ByteBuf buf = Unpooled.buffer(4096);
        byte[] raw;
        try {
            Nbt.writeNamed(buf, "", root);
            raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
        } finally {
            buf.release();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 64);
        switch (compression) {
            case COMPRESSION_NONE -> out.write(raw);
            case COMPRESSION_GZIP -> {
                try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                    gzip.write(raw);
                }
            }
            case COMPRESSION_ZLIB -> {
                try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
                    deflate.write(raw);
                }
            }
            default -> throw new IOException("Unknown NBT compression " + compression);
        }
        return out.toByteArray();
    }

    public static Nbt.NbtCompound read(byte[] data, int compression) throws IOException {
        byte[] raw = switch (compression) {
            case COMPRESSION_NONE -> data;
            case COMPRESSION_GZIP -> readFully(new GZIPInputStream(new java.io.ByteArrayInputStream(data)));
            case COMPRESSION_ZLIB -> readFully(new InflaterInputStream(new java.io.ByteArrayInputStream(data)));
            default -> throw new IOException("Unknown NBT compression " + compression);
        };

        ByteBuf buf = Unpooled.wrappedBuffer(raw);
        try {
            byte type = buf.readByte();
            if (type != Nbt.TAG_COMPOUND) {
                throw new IOException("NBT root is type " + type + ", expected a compound");
            }
            readUtf(buf); // root name, conventionally empty
            Nbt payload = readPayload(buf, Nbt.TAG_COMPOUND, 0);
            return (Nbt.NbtCompound) payload;
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Truncated NBT", e);
        } finally {
            buf.release();
        }
    }

    private static byte[] readFully(java.io.InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static String readUtf(ByteBuf buf) {
        int length = buf.readUnsignedShort();
        String value = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.skipBytes(length);
        return value;
    }

    private static Nbt readPayload(ByteBuf buf, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        }
        switch (type) {
            case Nbt.TAG_BYTE:
                return new Nbt.NbtByte(buf.readByte());
            case Nbt.TAG_SHORT:
                return new Nbt.NbtShort(buf.readShort());
            case Nbt.TAG_INT:
                return new Nbt.NbtInt(buf.readInt());
            case Nbt.TAG_LONG:
                return new Nbt.NbtLong(buf.readLong());
            case Nbt.TAG_FLOAT:
                return new Nbt.NbtFloat(buf.readFloat());
            case Nbt.TAG_DOUBLE:
                return new Nbt.NbtDouble(buf.readDouble());
            case Nbt.TAG_STRING:
                return new Nbt.NbtString(readUtf(buf));
            case Nbt.TAG_BYTE_ARRAY: {
                int length = checkLength(buf, buf.readInt(), 1);
                byte[] values = new byte[length];
                buf.readBytes(values);
                return new Nbt.NbtByteArray(values);
            }
            case Nbt.TAG_INT_ARRAY: {
                int length = checkLength(buf, buf.readInt(), 4);
                int[] values = new int[length];
                for (int i = 0; i < length; i++) {
                    values[i] = buf.readInt();
                }
                return new Nbt.NbtIntArray(values);
            }
            case Nbt.TAG_LONG_ARRAY: {
                int length = checkLength(buf, buf.readInt(), 8);
                long[] values = new long[length];
                for (int i = 0; i < length; i++) {
                    values[i] = buf.readLong();
                }
                return new Nbt.NbtLongArray(values);
            }
            case Nbt.TAG_LIST: {
                byte elementType = buf.readByte();
                int length = buf.readInt();
                Nbt.NbtList list = new Nbt.NbtList(elementType);
                if (length < 0) {
                    throw new IOException("Negative NBT list length " + length);
                }
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(buf, elementType, depth + 1));
                }
                return list;
            }
            case Nbt.TAG_COMPOUND: {
                Nbt.NbtCompound compound = Nbt.compound();
                while (true) {
                    byte entryType = buf.readByte();
                    if (entryType == Nbt.TAG_END) {
                        return compound;
                    }
                    String name = readUtf(buf);
                    compound.put(name, readPayload(buf, entryType, depth + 1));
                }
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    /**
     * Rejects an array length the remaining buffer cannot possibly satisfy, so a corrupt length
     * field fails immediately instead of trying to allocate gigabytes.
     */
    private static int checkLength(ByteBuf buf, int length, int bytesPerElement) throws IOException {
        if (length < 0 || (long) length * bytesPerElement > buf.readableBytes()) {
            throw new IOException("NBT array length " + length + " exceeds remaining "
                    + buf.readableBytes() + " bytes");
        }
        return length;
    }
}
