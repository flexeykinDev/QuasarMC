package dev.quasar.world.block;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recomputes the state properties a block derives from its neighbours.
 *
 * <p>Placement state comes from how you clicked; connection state comes from what is next to the
 * block afterwards, and has to be recomputed on both sides whenever anything changes. Without it a
 * fence line is a row of unconnected posts and stairs never corner.
 *
 * <p>Handles three families:
 * <ul>
 *   <li>fences, glass panes and iron bars: boolean {@code north/south/east/west}</li>
 *   <li>walls: {@code none/low/tall} per side, plus {@code up}</li>
 *   <li>stairs: {@code shape}, from the stairs in front and behind</li>
 * </ul>
 *
 * <p>Takes a {@link BlockAccess} rather than a world so the rules can be tested against a handful
 * of blocks in a map, with no chunk store or thread pools involved.
 */
public final class BlockConnections {

    private static final String[] SIDES = {"north", "south", "west", "east"};

    private BlockConnections() {}

    /** Horizontal offset of a named direction, as {x, z}. */
    private static int[] offset(String direction) {
        return switch (direction) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "west" -> new int[] {-1, 0};
            default -> new int[] {1, 0};
        };
    }

    private static String counterClockWise(String direction) {
        return switch (direction) {
            case "north" -> "west";
            case "west" -> "south";
            case "south" -> "east";
            default -> "north";
        };
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "south" -> "north";
            case "west" -> "east";
            default -> "west";
        };
    }

    /**
     * @return the state this position should hold given its neighbours, or {@code state} unchanged
     *         when the block has no connection properties
     */
    public static int updatedState(BlockAccess blocks, int x, int y, int z, int state) {
        BlockStateRegistry.State self = BlockStateRegistry.byId(state);
        if (self == null || self.properties().isEmpty()) {
            return state;
        }
        Map<String, String> properties = self.properties();

        if (properties.containsKey("shape") && self.name().endsWith("_stairs")) {
            return withStairShape(blocks, x, y, z, self);
        }
        if (isSideConnectable(properties)) {
            return withSideConnections(blocks, x, y, z, self);
        }
        return state;
    }

    /** True when north/south/east/west look like connection properties rather than something else. */
    private static boolean isSideConnectable(Map<String, String> properties) {
        for (String side : SIDES) {
            String value = properties.get(side);
            if (value == null) {
                return false;
            }
            if (!value.equals("true") && !value.equals("false")
                    && !value.equals("none") && !value.equals("low") && !value.equals("tall")) {
                return false;
            }
        }
        return true;
    }

    private static int withSideConnections(BlockAccess blocks, int x, int y, int z,
                                           BlockStateRegistry.State self) {
        // Walls use none/low/tall where fences and panes use true/false.
        boolean wall = !"true".equals(self.properties().get("north"))
                && !"false".equals(self.properties().get("north"));

        Map<String, String> properties = new LinkedHashMap<>(self.properties());
        for (String side : SIDES) {
            int[] delta = offset(side);
            int neighbour = blocks.getBlock(x + delta[0], y, z + delta[1]);
            boolean connects = connects(self.name(), neighbour);
            properties.put(side, wall ? (connects ? "low" : "none") : String.valueOf(connects));
        }
        // A wall post is raised unless it is a straight run through.
        if (wall && properties.containsKey("up")) {
            boolean northSouth = !"none".equals(properties.get("north"))
                    && !"none".equals(properties.get("south"));
            boolean eastWest = !"none".equals(properties.get("east"))
                    && !"none".equals(properties.get("west"));
            boolean straightThrough = (northSouth && !eastWest) || (eastWest && !northSouth);
            properties.put("up", String.valueOf(!straightThrough));
        }

        int resolved = BlockStateRegistry.idFor(self.name(), properties);
        return resolved >= 0 ? resolved : self.id();
    }

    /**
     * Whether a block of {@code selfName} joins to {@code neighbourState}.
     *
     * <p>Same-family joins are exact. Joining to ordinary solid blocks is an approximation: the
     * server has no block shape or collision data, so solidity is inferred from the name in
     * {@link #isSolid}. That gets fences meeting terrain and buildings right, and will be wrong at
     * the edges for unusual blocks.
     */
    private static boolean connects(String selfName, int neighbourState) {
        BlockStateRegistry.State neighbour = BlockStateRegistry.byId(neighbourState);
        if (neighbour == null || Blocks.isAir(neighbourState)) {
            return false;
        }
        String name = neighbour.name();

        boolean selfFence = selfName.endsWith("_fence");
        boolean selfPane = selfName.endsWith("_pane") || selfName.equals("minecraft:iron_bars");
        boolean selfWall = selfName.endsWith("_wall");

        if (selfFence) {
            // Wooden and nether-brick fences do not join each other in vanilla; matching the exact
            // material keeps that true without a per-material table.
            return name.equals(selfName) || name.endsWith("_fence_gate") || isSolid(name);
        }
        if (selfPane) {
            return name.endsWith("_pane") || name.equals("minecraft:iron_bars") || isSolid(name);
        }
        if (selfWall) {
            return name.endsWith("_wall") || name.endsWith("_fence_gate") || isSolid(name);
        }
        return isSolid(name);
    }

    /**
     * Approximate solidity by name.
     *
     * <p>Neither the block report nor the protocol carries collision or shape data, so this is a
     * curated exclusion list rather than a fact. Everything unmatched is treated as solid, which is
     * right far more often than not; the failure mode is a fence connecting to something decorative
     * it should have ignored.
     */
    private static boolean isSolid(String name) {
        String id = name.startsWith("minecraft:") ? name.substring(10) : name;
        if (id.equals("air") || id.equals("cave_air") || id.equals("void_air")
                || id.equals("water") || id.equals("lava") || id.equals("barrier")
                || id.equals("light") || id.equals("structure_void")) {
            return false;
        }
        return !(id.endsWith("_torch") || id.equals("torch")
                || id.endsWith("_button") || id.endsWith("_pressure_plate")
                || id.endsWith("_sign") || id.endsWith("_banner") || id.endsWith("_carpet")
                || id.endsWith("_sapling") || id.endsWith("_rail") || id.equals("rail")
                || id.endsWith("_candle") || id.equals("candle")
                || id.endsWith("_fence_gate") || id.endsWith("_door")
                || id.equals("lever") || id.equals("tripwire") || id.equals("redstone_wire")
                || id.equals("ladder") || id.equals("vine") || id.equals("snow")
                || id.equals("cobweb") || id.equals("scaffolding")
                || id.equals("grass") || id.equals("short_grass") || id.equals("tall_grass")
                || id.equals("fern") || id.equals("large_fern") || id.equals("dead_bush")
                || id.equals("seagrass") || id.equals("kelp") || id.equals("kelp_plant")
                || id.equals("dandelion") || id.equals("poppy") || id.equals("torchflower")
                || id.endsWith("_tulip") || id.endsWith("_orchid") || id.equals("allium")
                || id.equals("azure_bluet") || id.equals("oxeye_daisy") || id.equals("cornflower")
                || id.equals("lily_of_the_valley") || id.equals("wither_rose")
                || id.equals("sunflower") || id.equals("lilac") || id.equals("rose_bush")
                || id.equals("peony") || id.equals("sugar_cane") || id.equals("bamboo"));
    }

    /**
     * Stair corner shape, following vanilla: a stair turns where the stairs in front of or behind
     * it face a perpendicular direction and sit in the same half.
     */
    private static int withStairShape(BlockAccess blocks, int x, int y, int z,
                                      BlockStateRegistry.State self) {
        String facing = self.properties().get("facing");
        String half = self.properties().get("half");
        if (facing == null || half == null) {
            return self.id();
        }

        String shape = "straight";

        // Behind: produces an outer corner.
        int[] back = offset(facing);
        BlockStateRegistry.State behind = stairsAt(blocks, x + back[0], y, z + back[1], half);
        if (behind != null) {
            String backFacing = behind.properties().get("facing");
            if (perpendicular(backFacing, facing)
                    && !sameStairs(blocks, x, y, z, facing, half, opposite(backFacing))) {
                shape = backFacing.equals(counterClockWise(facing)) ? "outer_left" : "outer_right";
            }
        }

        // In front: produces an inner corner. Vanilla checks this second, so it wins.
        if (shape.equals("straight")) {
            int[] front = offset(opposite(facing));
            BlockStateRegistry.State ahead = stairsAt(blocks, x + front[0], y, z + front[1], half);
            if (ahead != null) {
                String frontFacing = ahead.properties().get("facing");
                if (perpendicular(frontFacing, facing)
                        && !sameStairs(blocks, x, y, z, facing, half, frontFacing)) {
                    shape = frontFacing.equals(counterClockWise(facing)) ? "inner_left" : "inner_right";
                }
            }
        }

        Map<String, String> properties = new LinkedHashMap<>(self.properties());
        properties.put("shape", shape);
        int resolved = BlockStateRegistry.idFor(self.name(), properties);
        return resolved >= 0 ? resolved : self.id();
    }

    private static BlockStateRegistry.State stairsAt(BlockAccess blocks, int x, int y, int z,
                                                     String half) {
        BlockStateRegistry.State state = BlockStateRegistry.byId(blocks.getBlock(x, y, z));
        if (state == null || !state.name().endsWith("_stairs")) {
            return null;
        }
        return half.equals(state.properties().get("half")) ? state : null;
    }

    /**
     * Vanilla's {@code canTakeShape}: a corner is suppressed when the neighbour on that side is
     * itself a stair with the same facing and half, so a straight run does not turn.
     */
    private static boolean sameStairs(BlockAccess blocks, int x, int y, int z,
                                      String facing, String half, String direction) {
        int[] delta = offset(direction);
        BlockStateRegistry.State neighbour = stairsAt(blocks, x + delta[0], y, z + delta[1], half);
        return neighbour != null && facing.equals(neighbour.properties().get("facing"));
    }

    private static boolean perpendicular(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        boolean aZ = a.equals("north") || a.equals("south");
        boolean bZ = b.equals("north") || b.equals("south");
        return aZ != bZ;
    }
}
