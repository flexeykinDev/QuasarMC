package dev.quasar.world.light;

import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * How much light a block emits, and whether it stops light passing through.
 *
 * <h2>An honest approximation</h2>
 *
 * <p>Vanilla decides occlusion from a block's collision shape: a slab blocks light from below but
 * not from the side, a fence barely blocks it at all. This server has no shapes, so it makes a
 * cruder call -- a curated set of block names lets light through and everything else stops it.
 *
 * <p>That is wrong in visible ways, and it is worth being precise about which. Slabs and stairs are
 * treated as solid, so a staircase lit from above is darker here than in vanilla -- deliberately, as
 * the alternative is daylight flooding through every slab roof. Blocks that plainly are not cubes,
 * including chests and fences, do let light past. It is right about the thing that matters most,
 * which is that caves are dark, overhangs cast shade, and torches light a room.

 * <p>Getting a chest wrong here is not subtle: a chest is drawn by a block-entity renderer using the
 * light value at its own position, so listing it as opaque zeroes that value and the chest renders
 * almost black in full daylight.
 *
 * <p>Emission is a curated table too. Mojang's generated block report carries every block state and
 * its properties but <em>not</em> luminance, so there is nothing to read it from; the alternative
 * to a hand-written table is guessing, and a guessed torch is worse than a listed one.
 */
public final class LightProperties {

    private LightProperties() {}

    /** Block names light passes through. Anything absent is treated as fully opaque. */
    private static final Set<String> TRANSPARENT = Set.of(
            "air", "cave_air", "void_air",
            "water", "bubble_column",
            "glass", "tinted_glass", "glass_pane",
            "barrier", "light", "structure_void",
            "torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "redstone_torch", "redstone_wall_torch",
            "lantern", "soul_lantern", "end_rod", "chain",
            "ladder", "rail", "powered_rail", "detector_rail", "activator_rail",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "sugar_cane", "vine", "glow_lichen", "cobweb",
            "lever", "tripwire", "tripwire_hook", "redstone_wire",
            "sunflower", "lilac", "rose_bush", "peony", "seagrass", "tall_seagrass",
            "kelp", "kelp_plant", "torchflower", "pitcher_plant",
            // Blocks that are not full cubes and so do not occlude in vanilla. Chests matter more
            // than the rest: a chest is drawn by a block-entity renderer using the light value at
            // its own position, so treating it as opaque zeroes that value and the chest renders
            // almost black in broad daylight.
            "chest", "trapped_chest", "ender_chest", "hopper", "enchanting_table",
            "brewing_stand", "cauldron", "water_cauldron", "lava_cauldron", "anvil",
            "chipped_anvil", "damaged_anvil", "grindstone", "stonecutter", "lectern",
            "bell", "conduit", "beacon", "end_portal_frame", "flower_pot", "decorated_pot",
            "composter", "scaffolding", "iron_bars", "lightning_rod");

    /** Prefixes and suffixes that stand in for whole families, so the list above stays readable. */
    private static final String[] TRANSPARENT_SUFFIXES = {
            "_glass", "_glass_pane", "_sapling", "_button", "_pressure_plate",
            "_candle", "_torch", "_carpet", "_banner", "_sign", "_hanging_sign",
            "_bed", "_fence", "_fence_gate", "_wall", "_bars", "_pane", "_head", "_skull",
            "_trapdoor", "_door", "_flower_pot"
            // Deliberately NOT slabs, stairs or shulker boxes. Slabs and stairs are what people
            // roof and floor with, and treating them as non-occluding floods the inside of any
            // such building with daylight -- a far more visible wrong than the slight over-darkness
            // of treating them as solid. A shulker box is a full cube and occludes properly.
    };

    /** Vanilla luminance for the blocks that actually light a room. */
    private static final Map<String, Integer> EMISSION = buildEmission();

    private static Map<String, Integer> buildEmission() {
        Map<String, Integer> map = new HashMap<>();
        map.put("beacon", 15);
        map.put("conduit", 15);
        map.put("end_gateway", 15);
        map.put("end_portal", 15);
        map.put("glowstone", 15);
        map.put("jack_o_lantern", 15);
        map.put("lava", 15);
        map.put("lantern", 15);
        map.put("sea_lantern", 15);
        map.put("shroomlight", 15);
        map.put("froglight", 15);
        map.put("ochre_froglight", 15);
        map.put("verdant_froglight", 15);
        map.put("pearlescent_froglight", 15);
        map.put("campfire", 15);
        map.put("torch", 14);
        map.put("wall_torch", 14);
        map.put("end_rod", 14);
        map.put("crying_obsidian", 10);
        map.put("soul_torch", 10);
        map.put("soul_wall_torch", 10);
        map.put("soul_lantern", 10);
        map.put("soul_campfire", 10);
        map.put("soul_fire", 10);
        map.put("fire", 15);
        map.put("glow_lichen", 7);
        map.put("enchanting_table", 7);
        map.put("ender_chest", 7);
        map.put("redstone_torch", 7);
        map.put("redstone_wall_torch", 7);
        map.put("amethyst_cluster", 5);
        map.put("large_amethyst_bud", 4);
        map.put("medium_amethyst_bud", 2);
        map.put("small_amethyst_bud", 1);
        map.put("magma_block", 3);
        map.put("smoker", 13);
        map.put("furnace", 13);
        map.put("blast_furnace", 13);
        map.put("brewing_stand", 1);
        map.put("brown_mushroom", 1);
        map.put("dragon_egg", 1);
        map.put("sculk_catalyst", 6);
        map.put("copper_bulb", 15);
        return map;
    }

    /**
     * Per-state lookup tables.
     *
     * <p>Built once and indexed by state ID, because the alternative is a map lookup and a string
     * comparison for every neighbour of every block a light update visits -- and a single sky-light
     * fill for one chunk visits tens of thousands.
     */
    private static volatile byte[] emissionByState;
    private static volatile boolean[] opaqueByState;

    /**
     * Builds the tables from whatever block table is available.
     *
     * <p>Called once at startup, after {@link BlockStateRegistry#loadFullTableIfPresent()}, so that
     * a server with {@code blocks.json} gets all 27k states and one without still gets its built-in
     * handful right.
     */
    public static synchronized void build() {
        int max = BlockStateRegistry.highestStateId();
        byte[] emission = new byte[max + 1];
        boolean[] opaque = new boolean[max + 1];

        for (int id = 0; id <= max; id++) {
            BlockStateRegistry.State state = BlockStateRegistry.byId(id);
            String name = state == null ? null : stripNamespace(state.name());
            if (name == null) {
                // Unknown state: assume it is a normal solid block. Assuming transparency instead
                // would punch light through anything this server does not have a name for.
                opaque[id] = !Blocks.isAir(id);
                continue;
            }
            opaque[id] = !isTransparentName(name);
            Integer emits = EMISSION.get(name);
            if (emits != null) {
                emission[id] = emits.byteValue();
            }
        }

        // Water is a range of states and must let light through whatever the level property says,
        // or every ocean floor goes black.
        for (int id = Blocks.WATER_STATE_MIN; id <= Blocks.WATER_STATE_MAX && id <= max; id++) {
            opaque[id] = false;
        }
        opaque[Blocks.AIR] = false;

        emissionByState = emission;
        opaqueByState = opaque;
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static boolean isTransparentName(String name) {
        if (TRANSPARENT.contains(name)) {
            return true;
        }
        for (String suffix : TRANSPARENT_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** How much light this state emits, 0 to 15. */
    public static int emission(int stateId) {
        byte[] table = emissionByState;
        if (table == null || stateId < 0 || stateId >= table.length) {
            return 0;
        }
        return table[stateId];
    }

    /** Whether this state stops light. */
    public static boolean blocksLight(int stateId) {
        boolean[] table = opaqueByState;
        if (table == null) {
            return !Blocks.isAir(stateId) && !Blocks.isWater(stateId);
        }
        if (stateId < 0 || stateId >= table.length) {
            return true;
        }
        return table[stateId];
    }

    /**
     * How much a block dims light passing through it, beyond the one level every step costs.
     *
     * <p>Water is the only one that matters here: vanilla dims it by an extra level per block, which
     * is why the sea floor goes dark a dozen blocks down rather than staying lit to the bottom.
     */
    public static int opacity(int stateId) {
        return Blocks.isWater(stateId) ? 1 : 0;
    }
}
