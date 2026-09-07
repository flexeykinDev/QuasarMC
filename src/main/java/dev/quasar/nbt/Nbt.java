package dev.quasar.nbt;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compact NBT tree model plus the network serialiser.
 *
 * <p>Everything lives in one file on purpose: NBT is a closed set of thirteen tag types, and
 * splitting it across thirteen files buys nothing.
 *
 * <p>Note the two write modes. Since 1.20.2 the protocol uses <em>network NBT</em>, where the root
 * compound is written as a bare type byte followed by its payload, with no root name. Disk NBT
 * still carries the (usually empty) root name. {@link #writeNetwork} does the former.
 */
public sealed interface Nbt {

    byte TAG_END = 0;
    byte TAG_BYTE = 1;
    byte TAG_SHORT = 2;
    byte TAG_INT = 3;
    byte TAG_LONG = 4;
    byte TAG_FLOAT = 5;
    byte TAG_DOUBLE = 6;
    byte TAG_BYTE_ARRAY = 7;
    byte TAG_STRING = 8;
    byte TAG_LIST = 9;
    byte TAG_COMPOUND = 10;
    byte TAG_INT_ARRAY = 11;
    byte TAG_LONG_ARRAY = 12;

    byte typeId();

    void writePayload(ByteBuf buf);

    record NbtByte(byte value) implements Nbt {
        public byte typeId() { return TAG_BYTE; }
        public void writePayload(ByteBuf buf) { buf.writeByte(value); }
    }

    record NbtShort(short value) implements Nbt {
        public byte typeId() { return TAG_SHORT; }
        public void writePayload(ByteBuf buf) { buf.writeShort(value); }
    }

    record NbtInt(int value) implements Nbt {
        public byte typeId() { return TAG_INT; }
        public void writePayload(ByteBuf buf) { buf.writeInt(value); }
    }

    record NbtLong(long value) implements Nbt {
        public byte typeId() { return TAG_LONG; }
        public void writePayload(ByteBuf buf) { buf.writeLong(value); }
    }

    record NbtFloat(float value) implements Nbt {
        public byte typeId() { return TAG_FLOAT; }
        public void writePayload(ByteBuf buf) { buf.writeFloat(value); }
    }

    record NbtDouble(double value) implements Nbt {
        public byte typeId() { return TAG_DOUBLE; }
        public void writePayload(ByteBuf buf) { buf.writeDouble(value); }
    }

    record NbtByteArray(byte[] value) implements Nbt {
        public byte typeId() { return TAG_BYTE_ARRAY; }
        public void writePayload(ByteBuf buf) {
            buf.writeInt(value.length);
            buf.writeBytes(value);
        }
    }

    record NbtIntArray(int[] value) implements Nbt {
        public byte typeId() { return TAG_INT_ARRAY; }
        public void writePayload(ByteBuf buf) {
            buf.writeInt(value.length);
            for (int v : value) {
                buf.writeInt(v);
            }
        }
    }

    record NbtLongArray(long[] value) implements Nbt {
        public byte typeId() { return TAG_LONG_ARRAY; }
        public void writePayload(ByteBuf buf) {
            buf.writeInt(value.length);
            for (long v : value) {
                buf.writeLong(v);
            }
        }
    }

    record NbtString(String value) implements Nbt {
        public byte typeId() { return TAG_STRING; }
        public void writePayload(ByteBuf buf) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0xFFFF) {
                throw new IllegalArgumentException("NBT string too long: " + bytes.length);
            }
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    /** A homogeneous list. Empty lists are written with element type END, as the format requires. */
    final class NbtList implements Nbt {
        private final List<Nbt> items = new ArrayList<>();
        private byte elementType = TAG_END;

        public NbtList() {}

        /**
         * Creates a list with its element type fixed up front, so an empty list read from disk
         * keeps the type it was written with instead of degrading to END.
         */
        public NbtList(byte elementType) {
            this.elementType = elementType;
        }

        public byte elementType() {
            return elementType;
        }

        public static NbtList of(Nbt... values) {
            NbtList list = new NbtList();
            for (Nbt v : values) {
                list.add(v);
            }
            return list;
        }

        public static NbtList ofStrings(String... values) {
            NbtList list = new NbtList();
            for (String v : values) {
                list.add(new NbtString(v));
            }
            return list;
        }

        public NbtList add(Nbt tag) {
            if (elementType == TAG_END) {
                elementType = tag.typeId();
            } else if (elementType != tag.typeId()) {
                throw new IllegalArgumentException(
                        "Heterogeneous NBT list: have " + elementType + ", got " + tag.typeId());
            }
            items.add(tag);
            return this;
        }

        public int size() { return items.size(); }

        public List<Nbt> items() { return items; }

        public byte typeId() { return TAG_LIST; }

        public void writePayload(ByteBuf buf) {
            buf.writeByte(elementType);
            buf.writeInt(items.size());
            for (Nbt item : items) {
                item.writePayload(buf);
            }
        }
    }

    /** Insertion-ordered so serialised output is stable and diffable. */
    final class NbtCompound implements Nbt {
        private final Map<String, Nbt> entries = new LinkedHashMap<>();

        public NbtCompound put(String key, Nbt value) {
            entries.put(key, value);
            return this;
        }

        public NbtCompound putByte(String key, int value) { return put(key, new NbtByte((byte) value)); }

        public NbtCompound putBoolean(String key, boolean value) { return putByte(key, value ? 1 : 0); }

        public NbtCompound putShort(String key, int value) { return put(key, new NbtShort((short) value)); }

        public NbtCompound putInt(String key, int value) { return put(key, new NbtInt(value)); }

        public NbtCompound putLong(String key, long value) { return put(key, new NbtLong(value)); }

        public NbtCompound putFloat(String key, float value) { return put(key, new NbtFloat(value)); }

        public NbtCompound putDouble(String key, double value) { return put(key, new NbtDouble(value)); }

        public NbtCompound putString(String key, String value) { return put(key, new NbtString(value)); }

        public Nbt get(String key) { return entries.get(key); }

        public boolean isEmpty() { return entries.isEmpty(); }

        public Map<String, Nbt> entries() { return entries; }

        public byte typeId() { return TAG_COMPOUND; }

        public void writePayload(ByteBuf buf) {
            for (Map.Entry<String, Nbt> e : entries.entrySet()) {
                Nbt value = e.getValue();
                buf.writeByte(value.typeId());
                new NbtString(e.getKey()).writePayload(buf);
                value.writePayload(buf);
            }
            buf.writeByte(TAG_END);
        }
    }

    /** Network NBT (1.20.2+): type byte, then payload, with no root name. */
    static void writeNetwork(ByteBuf buf, Nbt root) {
        buf.writeByte(root.typeId());
        root.writePayload(buf);
    }

    /** Disk/legacy NBT: type byte, root name, then payload. */
    static void writeNamed(ByteBuf buf, String name, Nbt root) {
        buf.writeByte(root.typeId());
        new NbtString(name).writePayload(buf);
        root.writePayload(buf);
    }

    static NbtCompound compound() {
        return new NbtCompound();
    }
}
