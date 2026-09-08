package dev.quasar.world.blockentity;

import dev.quasar.nbt.Nbt;
import dev.quasar.world.block.BlockStateRegistry;

/**
 * Sign block entities and their text.
 *
 * <p>A sign's words live in its block entity, not in its block state, in the shape 1.20.4
 * introduced: separate {@code front_text} and {@code back_text} compounds, each holding four
 * {@code messages}. Each message is a JSON text component <em>as a string</em>, not a bare string,
 * which is the detail that turns a working sign into a blank one -- the client parses each entry
 * and silently renders nothing when it will not parse.
 */
public final class Signs {

    private Signs() {}

    public static final String BLOCK_ENTITY_ID = "minecraft:sign";
    public static final String HANGING_BLOCK_ENTITY_ID = "minecraft:hanging_sign";

    public static final int LINES = 4;

    /** Vanilla's limit, and worth enforcing: the client will not show more. */
    public static final int MAX_LINE_LENGTH = 384;

    /** Whether a block is any kind of sign. */
    public static boolean isSign(int blockState) {
        BlockStateRegistry.State state = BlockStateRegistry.byId(blockState);
        if (state == null) {
            return false;
        }
        String name = state.name();
        return name.endsWith("_sign") || name.endsWith("_hanging_sign");
    }

    public static boolean isHangingSign(int blockState) {
        BlockStateRegistry.State state = BlockStateRegistry.byId(blockState);
        return state != null && state.name().endsWith("_hanging_sign");
    }

    public static String blockEntityIdFor(int blockState) {
        return isHangingSign(blockState) ? HANGING_BLOCK_ENTITY_ID : BLOCK_ENTITY_ID;
    }

    /** A blank sign block entity at the given position. */
    public static Nbt.NbtCompound newBlockEntity(int blockState, int x, int y, int z) {
        return Nbt.compound()
                .putString("id", blockEntityIdFor(blockState))
                .putInt("x", x)
                .putInt("y", y)
                .putInt("z", z)
                .putByte("is_waxed", (byte) 0)
                .put("front_text", blankSide())
                .put("back_text", blankSide());
    }

    private static Nbt.NbtCompound blankSide() {
        Nbt.NbtList messages = new Nbt.NbtList();
        for (int i = 0; i < LINES; i++) {
            messages.add(new Nbt.NbtString(asComponent("")));
        }
        return Nbt.compound()
                .putByte("has_glowing_text", (byte) 0)
                .putString("color", "black")
                .put("messages", messages);
    }

    /**
     * Writes four lines onto one side of a sign.
     *
     * @param front which side was edited; a sign has two and the client says which it means
     */
    public static Nbt.NbtCompound withText(Nbt.NbtCompound entity, boolean front, String[] lines) {
        Nbt.NbtList messages = new Nbt.NbtList();
        for (int i = 0; i < LINES; i++) {
            String line = i < lines.length && lines[i] != null ? lines[i] : "";
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH);
            }
            messages.add(new Nbt.NbtString(asComponent(line)));
        }

        String key = front ? "front_text" : "back_text";
        Nbt.NbtCompound side = entity.get(key) instanceof Nbt.NbtCompound existing
                ? existing
                : blankSide();
        side.put("messages", messages);
        entity.put(key, side);
        return entity;
    }

    /**
     * Wraps a line as the JSON text component the client expects.
     *
     * <p>Escaped by hand rather than through a JSON library because this is the only JSON this
     * server writes, and a quote or a backslash typed onto a sign would otherwise produce a
     * malformed component -- which the client answers by rendering an empty line, giving no hint
     * that the text ever arrived.
     */
    private static String asComponent(String text) {
        StringBuilder out = new StringBuilder(text.length() + 12);
        out.append("{\"text\":\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append("\"}").toString();
    }
}
