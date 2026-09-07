package dev.quasar.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Reader/writer helpers for the Minecraft wire types that Netty does not provide. */
public final class ByteBufs {

    /** A VarInt is at most five bytes; anything longer is a malformed or hostile packet. */
    private static final int MAX_VARINT_BYTES = 5;

    private ByteBufs() {}

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = buf.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position > 35) {
                throw new DecoderException("VarInt is too big");
            }
        } while ((current & 0x80) != 0);
        return value;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static int varIntSize(int value) {
        for (int i = 1; i < MAX_VARINT_BYTES; i++) {
            if ((value & -1 << i * 7) == 0) {
                return i;
            }
        }
        return MAX_VARINT_BYTES;
    }

    public static long readVarLong(ByteBuf buf) {
        long value = 0;
        int position = 0;
        byte current;
        do {
            current = buf.readByte();
            value |= (long) (current & 0x7F) << position;
            position += 7;
            if (position > 70) {
                throw new DecoderException("VarLong is too big");
            }
        } while ((current & 0x80) != 0);
        return value;
    }

    public static void writeVarLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    public static String readString(ByteBuf buf, int maxLength) {
        int length = readVarInt(buf);
        if (length < 0 || length > maxLength * 4) {
            throw new DecoderException("String length " + length + " exceeds limit " + maxLength * 4);
        }
        if (buf.readableBytes() < length) {
            throw new DecoderException("Truncated string: want " + length + ", have " + buf.readableBytes());
        }
        String s = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.skipBytes(length);
        if (s.length() > maxLength) {
            throw new DecoderException("String of " + s.length() + " chars exceeds limit " + maxLength);
        }
        return s;
    }

    public static String readString(ByteBuf buf) {
        return readString(buf, 32767);
    }

    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    public static void writeUuid(ByteBuf buf, UUID value) {
        buf.writeLong(value.getMostSignificantBits());
        buf.writeLong(value.getLeastSignificantBits());
    }

    public static byte[] readByteArray(ByteBuf buf, int maxLength) {
        int length = readVarInt(buf);
        if (length < 0 || length > maxLength) {
            throw new DecoderException("Byte array length " + length + " exceeds limit " + maxLength);
        }
        byte[] out = new byte[length];
        buf.readBytes(out);
        return out;
    }

    public static void writeByteArray(ByteBuf buf, byte[] value) {
        writeVarInt(buf, value.length);
        buf.writeBytes(value);
    }

    /** Optional prefix: a boolean followed by the value when present. */
    public static void writeOptionalString(ByteBuf buf, String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            writeString(buf, value);
        }
    }

    /**
     * Block position, packed as 26-bit X, 26-bit Z, 12-bit Y (Y in the low bits).
     */
    public static void writeBlockPos(ByteBuf buf, int x, int y, int z) {
        buf.writeLong(((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF));
    }

    public static int blockPosX(long packed) {
        return (int) (packed >> 38);
    }

    public static int blockPosY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int blockPosZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    /**
     * Writes an item stack in the 1.20.5+ "Slot" format.
     *
     * <p>An empty stack is a single zero count with nothing after it. A present stack is followed
     * by the item ID and two component counts — added and removed — both written as zero here,
     * meaning "exactly the item's default components". Writing components properly needs a full
     * component codec, which this server has no use for.
     */
    public static void writeItemStack(ByteBuf buf, int itemId, int count) {
        if (count <= 0) {
            writeVarInt(buf, 0);
            return;
        }
        writeVarInt(buf, count);
        writeVarInt(buf, itemId);
        writeVarInt(buf, 0); // components to add
        writeVarInt(buf, 0); // components to remove
    }

    public static void writeEmptyItemStack(ByteBuf buf) {
        writeVarInt(buf, 0);
    }

    /** Angle as a 1/256-of-a-turn byte. */
    public static void writeAngle(ByteBuf buf, float degrees) {
        buf.writeByte((byte) (int) (degrees * 256.0F / 360.0F));
    }
}
