package dev.quasar.world.redstone;

import dev.quasar.world.block.BlockStateRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Reading and editing the redstone blocks' state properties.
 *
 * <p>Every property name and value here was read out of Mojang's generated block report rather than
 * remembered: {@code redstone_wire} carries {@code power} 0-15, {@code lever} and {@code repeater}
 * carry {@code powered}, torches and lamps carry {@code lit}, and a repeater also has {@code delay}
 * and {@code facing}. Getting one of those strings wrong produces a state lookup that silently
 * fails and falls back to the default state, which looks like a logic bug a long way from the cause.
 *
 * <p>This needs the full block table from {@code blocks.json}. The built-in table covers only the
 * blocks this server generates with, and none of them are redstone, so redstone is simply inert
 * without it -- see {@link #available()}.
 */
public final class RedstoneBlocks {

    private RedstoneBlocks() {}

    public static final int MAX_POWER = 15;

    private static final String WIRE = "minecraft:redstone_wire";
    private static final String LEVER = "minecraft:lever";
    private static final String TORCH = "minecraft:redstone_torch";
    private static final String WALL_TORCH = "minecraft:redstone_wall_torch";
    private static final String REPEATER = "minecraft:repeater";
    private static final String LAMP = "minecraft:redstone_lamp";
    private static final String BLOCK = "minecraft:redstone_block";

    /** True when the full block table is loaded, so redstone states can be resolved at all. */
    public static boolean available() {
        return BlockStateRegistry.hasFullTable();
    }

    /**
     * Per-state flag for "this block has anything to do with redstone".
     *
     * <p>Built once and indexed by state ID. Every predicate here otherwise costs a map lookup and
     * a string comparison, and the engine asks about a block's neighbours on every change -- in a
     * world with no redstone in it at all, that overhead alone was enough to pull a six-bot server
     * from 20.0 TPS to 19.0.
     */
    private static volatile boolean[] relevantByState;

    public static synchronized void build() {
        int max = BlockStateRegistry.highestStateId();
        boolean[] relevant = new boolean[max + 1];
        for (int id = 0; id <= max; id++) {
            String name = nameOf(id);
            relevant[id] = WIRE.equals(name) || LEVER.equals(name) || TORCH.equals(name)
                    || WALL_TORCH.equals(name) || REPEATER.equals(name) || LAMP.equals(name)
                    || BLOCK.equals(name);
        }
        relevantByState = relevant;
    }

    /** Whether this state participates in redstone at all. One array read. */
    public static boolean isRelevant(int state) {
        boolean[] table = relevantByState;
        if (table == null || state < 0 || state >= table.length) {
            return false;
        }
        return table[state];
    }

    private static String nameOf(int state) {
        BlockStateRegistry.State s = BlockStateRegistry.byId(state);
        return s == null ? "" : s.name();
    }

    private static Map<String, String> propsOf(int state) {
        BlockStateRegistry.State s = BlockStateRegistry.byId(state);
        return s == null ? Map.of() : s.properties();
    }

    /**
     * Returns the same block with one property changed, or the original state if that state does
     * not exist.
     *
     * <p>Falling back to the original rather than to the block's default matters: a default repeater
     * has delay 1 and faces north, so a failed lookup would silently rotate and retime the player's
     * circuit instead of leaving it alone.
     */
    private static int with(int state, String property, String value) {
        BlockStateRegistry.State s = BlockStateRegistry.byId(state);
        if (s == null) {
            return state;
        }
        Map<String, String> properties = new HashMap<>(s.properties());
        properties.put(property, value);
        int found = BlockStateRegistry.idFor(s.name(), properties);
        return found >= 0 ? found : state;
    }

    // ------------------------------------------------------------------------------------ dust

    public static boolean isDust(int state) {
        return WIRE.equals(nameOf(state));
    }

    public static int dustPower(int state) {
        String power = propsOf(state).get("power");
        if (power == null) {
            return 0;
        }
        try {
            return Integer.parseInt(power);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int withDustPower(int state, int power) {
        return with(state, "power", String.valueOf(Math.max(0, Math.min(MAX_POWER, power))));
    }

    // --------------------------------------------------------------------------------- sources

    public static boolean isLever(int state) {
        return LEVER.equals(nameOf(state));
    }

    public static boolean isRedstoneBlock(int state) {
        return BLOCK.equals(nameOf(state));
    }

    public static boolean isPoweredProperty(int state) {
        return "true".equals(propsOf(state).get("powered"));
    }

    public static int togglePowered(int state) {
        return with(state, "powered", isPoweredProperty(state) ? "false" : "true");
    }

    // --------------------------------------------------------------------------------- torches

    public static boolean isTorch(int state) {
        String name = nameOf(state);
        return TORCH.equals(name) || WALL_TORCH.equals(name);
    }

    public static boolean isLit(int state) {
        return "true".equals(propsOf(state).get("lit"));
    }

    public static int withLit(int state, boolean lit) {
        return with(state, "lit", lit ? "true" : "false");
    }

    /**
     * The direction a wall torch hangs from, as a face index, or -1 for a floor torch.
     *
     * <p>A torch is powered by the block it is attached to, and that block is the one direction it
     * does <em>not</em> power in turn -- so knowing which one it is is what stops a torch feeding
     * itself.
     */
    public static int torchAttachmentFace(int state) {
        if (TORCH.equals(nameOf(state))) {
            return FACE_DOWN;
        }
        String facing = propsOf(state).get("facing");
        if (facing == null) {
            return FACE_DOWN;
        }
        // A wall torch faces away from its block, so the attachment is the opposite direction.
        return opposite(faceForName(facing));
    }

    // -------------------------------------------------------------------------------- repeater

    public static boolean isRepeater(int state) {
        return REPEATER.equals(nameOf(state));
    }

    public static int repeaterDelayTicks(int state) {
        String delay = propsOf(state).get("delay");
        if (delay == null) {
            return 2;
        }
        try {
            // The property counts redstone ticks; one redstone tick is two game ticks.
            return Integer.parseInt(delay) * 2;
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** The face a repeater outputs into. Its input is the opposite side. */
    public static int repeaterFacingFace(int state) {
        String facing = propsOf(state).get("facing");
        return facing == null ? FACE_NORTH : faceForName(facing);
    }

    public static int cycleRepeaterDelay(int state) {
        String delay = propsOf(state).get("delay");
        int current = 1;
        if (delay != null) {
            try {
                current = Integer.parseInt(delay);
            } catch (NumberFormatException ignored) {
                current = 1;
            }
        }
        return with(state, "delay", String.valueOf(current >= 4 ? 1 : current + 1));
    }

    // ------------------------------------------------------------------------------------ lamp

    public static boolean isLamp(int state) {
        return LAMP.equals(nameOf(state));
    }

    // ----------------------------------------------------------------------------------- faces

    public static final int FACE_DOWN = 0;
    public static final int FACE_UP = 1;
    public static final int FACE_NORTH = 2;
    public static final int FACE_SOUTH = 3;
    public static final int FACE_WEST = 4;
    public static final int FACE_EAST = 5;

    public static final int[] FACE_X = {0, 0, 0, 0, -1, 1};
    public static final int[] FACE_Y = {-1, 1, 0, 0, 0, 0};
    public static final int[] FACE_Z = {0, 0, -1, 1, 0, 0};

    public static int opposite(int face) {
        return switch (face) {
            case FACE_DOWN -> FACE_UP;
            case FACE_UP -> FACE_DOWN;
            case FACE_NORTH -> FACE_SOUTH;
            case FACE_SOUTH -> FACE_NORTH;
            case FACE_WEST -> FACE_EAST;
            default -> FACE_WEST;
        };
    }

    private static int faceForName(String name) {
        return switch (name) {
            case "north" -> FACE_NORTH;
            case "south" -> FACE_SOUTH;
            case "west" -> FACE_WEST;
            case "east" -> FACE_EAST;
            case "up" -> FACE_UP;
            default -> FACE_DOWN;
        };
    }
}
