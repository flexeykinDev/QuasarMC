package dev.quasar.world.blockentity;

import java.util.Map;

/**
 * Which blocks hold items, and the screen they open.
 *
 * <p>A curated table rather than derived data: nothing in the generated reports says which blocks
 * have a block entity, let alone which of those are containers or how many slots they have.
 *
 * <p>Deliberately limited to blocks that open a plain slot grid. Furnaces, brewing stands and
 * enchanting tables have their own screens with fuel, progress and result slots whose behaviour is
 * not just "move items around", and a half-implemented furnace would be worse than none.
 */
public final class Containers {

    /**
     * @param blockEntityId the {@code id} written into the block entity's NBT
     * @param slots         how many item slots the container itself has
     * @param menuType      registry ID of the screen to open
     * @param title         window title shown to the player
     */
    public record Kind(String blockEntityId, int slots, int menuType, String title) {}

    private static final int MENU_GENERIC_9X3 = 2;
    private static final int MENU_GENERIC_9X6 = 5;
    private static final int MENU_GENERIC_3X3 = 6;
    private static final int MENU_HOPPER = 16;
    private static final int MENU_SHULKER_BOX = 20;

    private static final Map<String, Kind> BY_BLOCK = Map.of(
            "minecraft:chest", new Kind("minecraft:chest", 27, MENU_GENERIC_9X3, "Chest"),
            "minecraft:trapped_chest",
                    new Kind("minecraft:trapped_chest", 27, MENU_GENERIC_9X3, "Trapped Chest"),
            "minecraft:barrel", new Kind("minecraft:barrel", 27, MENU_GENERIC_9X3, "Barrel"),
            "minecraft:hopper", new Kind("minecraft:hopper", 5, MENU_HOPPER, "Hopper"),
            "minecraft:dispenser", new Kind("minecraft:dispenser", 9, MENU_GENERIC_3X3, "Dispenser"),
            "minecraft:dropper", new Kind("minecraft:dropper", 9, MENU_GENERIC_3X3, "Dropper"));

    private Containers() {}

    /** @return what this block opens, or {@code null} when it is not a container */
    public static Kind forBlock(String blockName) {
        Kind exact = BY_BLOCK.get(blockName);
        if (exact != null) {
            return exact;
        }
        // Every dyed shulker box is a separate block but one block entity type.
        if (blockName.endsWith("shulker_box")) {
            return new Kind("minecraft:shulker_box", 27, MENU_SHULKER_BOX, "Shulker Box");
        }
        return null;
    }

    public static boolean isContainer(String blockName) {
        return forBlock(blockName) != null;
    }

    /** The 54-slot screen a pair of chests presents between them. */
    public static Kind largeChest(Kind singleChest) {
        return new Kind(singleChest.blockEntityId(), singleChest.slots() * 2,
                MENU_GENERIC_9X6, "Large " + singleChest.title());
    }
}
