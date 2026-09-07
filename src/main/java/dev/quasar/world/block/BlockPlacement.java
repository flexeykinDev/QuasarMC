package dev.quasar.world.block;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives a block's placed state from how it was clicked.
 *
 * <p>Placing the default state always gives upright logs, unrotated stairs and bottom slabs. Real
 * placement reads the clicked face, where on that face the cursor landed, and which way the player
 * was looking, then adjusts the block's state properties to match.
 *
 * <h2>How a property is chosen</h2>
 * The block's default state says which properties it <em>has</em>; only those are touched. The
 * candidate state is then looked up by name and properties, and if that combination does not exist
 * the default is used instead. That fallback is what keeps this safe across the whole block set
 * without a per-block table: {@code type} means top/bottom on a slab but single/left/right on a
 * chest, and asking for a chest of type "bottom" simply fails the lookup and leaves the chest
 * alone.
 */
public final class BlockPlacement {

    /** Protocol face numbering, as sent in {@code use_item_on}. */
    public static final int FACE_DOWN = 0;
    public static final int FACE_UP = 1;
    public static final int FACE_NORTH = 2;
    public static final int FACE_SOUTH = 3;
    public static final int FACE_WEST = 4;
    public static final int FACE_EAST = 5;

    /** Indexed by the yaw quadrant: yaw 0 looks south, and it turns clockwise from there. */
    private static final String[] YAW_DIRECTIONS = {"south", "west", "north", "east"};

    private BlockPlacement() {}

    /**
     * @param defaultState the block's default state, as the item maps to it
     * @param face         which face of the existing block was clicked
     * @param cursorY      where on that face the cursor was, 0 at the bottom and 1 at the top
     * @param playerYaw    the placing player's yaw
     * @param intoWater    whether the position being filled currently holds water
     * @return the state to place, falling back to {@code defaultState} whenever the adjusted
     *         combination does not exist
     */
    public static int stateFor(int defaultState, int face, float cursorY, float playerYaw,
                               boolean intoWater) {
        BlockStateRegistry.State state = BlockStateRegistry.byId(defaultState);
        if (state == null || state.properties().isEmpty()) {
            return defaultState;
        }

        Map<String, String> properties = new LinkedHashMap<>(state.properties());
        boolean changed = false;

        // Logs, pillars, chains: the axis runs perpendicular to the face you clicked.
        if (properties.containsKey("axis")) {
            properties.put("axis", axisForFace(face));
            changed = true;
        }

        // Stairs and trapdoors sit in the top or bottom half of their block.
        if ("bottom".equals(properties.get("half")) || "top".equals(properties.get("half"))) {
            properties.put("half", verticalHalf(face, cursorY));
            changed = true;
        }

        // Slabs use "type" for the same idea. Guarded on the default actually being a slab value,
        // so chests and pistons — which also have "type" — are left alone.
        if ("bottom".equals(properties.get("type")) || "top".equals(properties.get("type"))) {
            properties.put("type", verticalHalf(face, cursorY));
            changed = true;
        }

        if (properties.containsKey("facing")) {
            properties.put("facing", facingFor(state.name(), playerYaw));
            changed = true;
        }

        // Placing into a water source leaves the water behind, as vanilla does.
        if (intoWater && properties.containsKey("waterlogged")) {
            properties.put("waterlogged", "true");
            changed = true;
        }

        if (!changed) {
            return defaultState;
        }
        int resolved = BlockStateRegistry.idFor(state.name(), properties);
        return resolved >= 0 ? resolved : defaultState;
    }

    private static String axisForFace(int face) {
        return switch (face) {
            case FACE_NORTH, FACE_SOUTH -> "z";
            case FACE_WEST, FACE_EAST -> "x";
            default -> "y";
        };
    }

    /**
     * Top or bottom half. Clicking the top of a block places into the bottom half of the space
     * above; clicking the underside places into the top half. On a side face it depends on whether
     * the cursor was above or below the midpoint.
     */
    private static String verticalHalf(int face, float cursorY) {
        if (face == FACE_UP) {
            return "bottom";
        }
        if (face == FACE_DOWN) {
            return "top";
        }
        return cursorY > 0.5f ? "top" : "bottom";
    }

    /**
     * Horizontal facing from the player's yaw.
     *
     * <p>Stairs face the way the player looks, so you walk up them going forwards. Almost
     * everything else — furnaces, chests, observers — faces back towards the player instead, so
     * its front is the side you can see. Blocks whose facing is decided by the clicked face rather
     * than the player, such as wall torches and buttons, are not handled: the lookup rejects the
     * bad combination and they keep their default state.
     */
    private static String facingFor(String blockName, float playerYaw) {
        String looking = YAW_DIRECTIONS[(int) Math.floor(playerYaw / 90.0 + 0.5) & 3];
        return blockName.endsWith("_stairs") ? looking : opposite(looking);
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "south" -> "north";
            case "west" -> "east";
            default -> "west";
        };
    }
}
