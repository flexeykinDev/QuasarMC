package dev.quasar.world.block;

import java.util.HashMap;
import java.util.Map;

/**
 * Blocks that occupy two positions: doors, beds and tall plants.
 *
 * <p>Placing only one of them leaves a half door -- the client renders the bottom and nothing above
 * it -- and breaking only one leaves the orphan half standing. Both are immediately visible, and
 * both happened here before this existed.
 *
 * <h2>Telling the two kinds of {@code half} apart</h2>
 *
 * <p>A door and a slab both carry a property called {@code half}, and they mean different things:
 * a door is {@code lower}/{@code upper} and really is two blocks, while a slab is
 * {@code top}/{@code bottom} and is one. Keying on the property name alone would make every slab
 * try to place a second copy of itself.
 */
public final class MultiBlock {

    private MultiBlock() {}

    /** The second position of a two-block structure, and the state that belongs there. */
    public record Partner(int dx, int dy, int dz, int state) {}

    /**
     * The partner of this block, or null when it stands alone.
     *
     * @param placing true when the block is being placed, false when it is being broken; the
     *                offset is the same either way, so this only affects which state is wanted
     */
    public static Partner partnerOf(int state, boolean placing) {
        BlockStateRegistry.State current = BlockStateRegistry.byId(state);
        if (current == null) {
            return null;
        }
        Map<String, String> properties = current.properties();

        String half = properties.get("half");
        if ("lower".equals(half)) {
            int upper = with(current, "half", "upper");
            return upper < 0 ? null : new Partner(0, 1, 0, upper);
        }
        if ("upper".equals(half)) {
            int lower = with(current, "half", "lower");
            return lower < 0 ? null : new Partner(0, -1, 0, lower);
        }

        String part = properties.get("part");
        if (part != null) {
            // A bed runs along its facing: the foot is placed and the head goes one block further
            // in that direction.
            int[] offset = offsetFor(properties.get("facing"));
            if (offset == null) {
                return null;
            }
            if ("foot".equals(part)) {
                int head = with(current, "part", "head");
                return head < 0 ? null : new Partner(offset[0], 0, offset[1], head);
            }
            if ("head".equals(part)) {
                int foot = with(current, "part", "foot");
                return foot < 0 ? null : new Partner(-offset[0], 0, -offset[1], foot);
            }
        }
        return null;
    }

    private static int with(BlockStateRegistry.State state, String property, String value) {
        Map<String, String> properties = new HashMap<>(state.properties());
        properties.put(property, value);
        return BlockStateRegistry.idFor(state.name(), properties);
    }

    private static int[] offsetFor(String facing) {
        if (facing == null) {
            return null;
        }
        return switch (facing) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "west" -> new int[] {-1, 0};
            case "east" -> new int[] {1, 0};
            default -> null;
        };
    }
}
