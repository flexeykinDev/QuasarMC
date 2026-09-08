package dev.quasar.world.block;

import java.util.Set;

/**
 * Which blocks a falling block passes straight through.
 *
 * <p>Separate from the light engine's transparency list, which answers a different question: glass
 * lets light through but still stops sand, and redstone dust stops neither. Conflating the two puts
 * sand through a window.
 *
 * <p>Vanilla decides this from collision shapes, which do not exist here, so this is a curated set
 * of names built into a per-state table once at startup. Anything unlisted stops a falling block,
 * which is the safe direction to be wrong in: a block that lands early is visible and harmless,
 * while one that falls through the world is gone.
 */
public final class BlockCollision {

    private BlockCollision() {}

    /** Blocks with no collision box in vanilla, which a falling block destroys on its way past. */
    private static final Set<String> NO_COLLISION = Set.of(
            "air", "cave_air", "void_air",
            "water", "lava", "bubble_column",
            "redstone_wire", "redstone_torch", "redstone_wall_torch",
            "torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "lever", "tripwire", "tripwire_hook", "rail", "powered_rail",
            "detector_rail", "activator_rail",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower",
            "sunflower", "lilac", "rose_bush", "peony",
            "wheat", "carrots", "potatoes", "beetroots", "sugar_cane",
            "vine", "glow_lichen", "seagrass", "tall_seagrass", "kelp", "kelp_plant",
            "structure_void", "light", "sculk_vein");

    private static final String[] NO_COLLISION_SUFFIXES = {
            "_sapling", "_button", "_pressure_plate", "_torch"
    };

    private static volatile boolean[] passable;

    public static synchronized void build() {
        int max = BlockStateRegistry.highestStateId();
        boolean[] table = new boolean[max + 1];
        for (int id = 0; id <= max; id++) {
            BlockStateRegistry.State state = BlockStateRegistry.byId(id);
            if (state == null) {
                continue;
            }
            String name = state.name();
            int colon = name.indexOf(':');
            String plain = colon < 0 ? name : name.substring(colon + 1);
            table[id] = matches(plain);
        }
        table[Blocks.AIR] = true;
        passable = table;
    }

    private static boolean matches(String plain) {
        if (NO_COLLISION.contains(plain)) {
            return true;
        }
        for (String suffix : NO_COLLISION_SUFFIXES) {
            if (plain.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a falling block passes through this state rather than resting on it. */
    public static boolean isPassable(int state) {
        boolean[] table = passable;
        if (table == null) {
            // Before the table is built -- tests, or a server with no block report -- fall back to
            // the blocks this server knows natively.
            return Blocks.isReplaceable(state);
        }
        if (state < 0 || state >= table.length) {
            return false;
        }
        return table[state];
    }
}
