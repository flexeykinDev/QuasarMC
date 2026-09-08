package dev.quasar.world.block;

import java.util.Set;

/**
 * Whether a block can survive where it is being placed.
 *
 * <p>Vanilla asks every block {@code canSurvive} before placing it, which is why a torch cannot be
 * hung in mid-air and a sign cannot float. Without the check anything goes anywhere, and the result
 * is a world full of blocks the client draws but vanilla would immediately break -- so an exported
 * world visibly falls apart the moment it is opened elsewhere.
 *
 * <h2>Deliberately narrow</h2>
 *
 * <p>Only the rule that catches almost every case is implemented: some blocks need something solid
 * directly beneath them, and wall-mounted variants need something solid behind them. Vanilla has
 * per-block shape rules far beyond this -- a torch can sit on a fence post, a sign can hang from a
 * chain -- so this is a floor on correctness, not a match. Anything unlisted is allowed, because a
 * false refusal is worse than a permissive one: it stops a player building at all.
 */
public final class BlockSupport {

    private BlockSupport() {}

    /** Blocks needing something solid directly below. */
    private static final Set<String> NEEDS_FLOOR = Set.of(
            "torch", "soul_torch", "redstone_torch",
            "lever", "redstone_wire", "repeater", "comparator",
            "rail", "powered_rail", "detector_rail", "activator_rail",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower",
            "sunflower", "lilac", "rose_bush", "peony",
            "wheat", "carrots", "potatoes", "beetroots", "sugar_cane",
            "cake", "flower_pot", "snow", "turtle_egg");

    private static final String[] NEEDS_FLOOR_SUFFIXES = {
            "_sapling", "_pressure_plate", "_sign", "_banner", "_door", "_bed", "_candle"
    };

    /** Wall-mounted blocks, which need something solid behind them instead. */
    private static final Set<String> WALL_MOUNTED = Set.of(
            "wall_torch", "soul_wall_torch", "redstone_wall_torch", "ladder");

    private static final String[] WALL_MOUNTED_SUFFIXES = {
            "_wall_sign", "_wall_banner", "_wall_hanging_sign"
    };

    /** What a placement needs underneath or behind it. */
    public enum Requirement {
        /** Nothing; it can go anywhere. */
        NONE,
        /** Something solid directly below. */
        FLOOR,
        /** Something solid behind, in the direction it faces away from. */
        WALL
    }

    public static Requirement requirementOf(int state) {
        BlockStateRegistry.State current = BlockStateRegistry.byId(state);
        if (current == null) {
            return Requirement.NONE;
        }
        String name = current.name();
        int colon = name.indexOf(':');
        String plain = colon < 0 ? name : name.substring(colon + 1);

        if (WALL_MOUNTED.contains(plain) || endsWithAny(plain, WALL_MOUNTED_SUFFIXES)) {
            return Requirement.WALL;
        }
        // Checked after the wall variants, since "oak_wall_sign" also ends with "_sign".
        if (NEEDS_FLOOR.contains(plain) || endsWithAny(plain, NEEDS_FLOOR_SUFFIXES)) {
            return Requirement.FLOOR;
        }
        return Requirement.NONE;
    }

    private static boolean endsWithAny(String name, String[] suffixes) {
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The direction a wall-mounted block needs support from, as an offset, or null.
     *
     * <p>A wall torch faces away from its wall, so the support is opposite its {@code facing}.
     */
    public static int[] wallSupportOffset(int state) {
        BlockStateRegistry.State current = BlockStateRegistry.byId(state);
        if (current == null) {
            return null;
        }
        String facing = current.properties().get("facing");
        if (facing == null) {
            return null;
        }
        return switch (facing) {
            case "north" -> new int[] {0, 0, 1};
            case "south" -> new int[] {0, 0, -1};
            case "west" -> new int[] {1, 0, 0};
            case "east" -> new int[] {-1, 0, 0};
            default -> null;
        };
    }

    /** Whether a block is solid enough to hold something else up. */
    public static boolean canSupport(int state) {
        return !Blocks.isReplaceable(state) && !BlockCollision.isPassable(state);
    }
}
