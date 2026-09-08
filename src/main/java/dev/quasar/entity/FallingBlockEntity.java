package dev.quasar.entity;

import dev.quasar.QuasarServer;
import dev.quasar.engine.Region;
import dev.quasar.world.block.Blocks;

import java.util.UUID;

/**
 * A block in mid-air: sand or gravel that lost its support.
 *
 * <p>Vanilla turns the block into an entity rather than moving it down a block per tick, and so
 * does this. The client animates a smooth fall; a block teleporting downwards in one-block steps
 * reads as a rendering fault rather than as gravity.
 *
 * <p>Like {@link ItemEntity} it never moves horizontally, so it stays in the chunk it started in
 * and can never need state another region owns.
 */
public final class FallingBlockEntity extends Entity {

    private static final double GRAVITY = 0.04;
    private static final double TERMINAL_VELOCITY = -3.0;

    /** How far below to look for ground; small enough not to tunnel through a block at speed. */
    private static final double GROUND_PROBE = 0.06;

    /**
     * Ticks before a falling block gives up and drops itself back into the world.
     *
     * <p>Vanilla despawns after 600. Without a cap, a block falling into the void -- or one whose
     * landing check somehow never fires -- would tick forever.
     */
    private static final int MAX_AGE = 600;

    private final QuasarServer server;
    private final int blockState;
    private double motionY;
    private int age;

    public FallingBlockEntity(QuasarServer server, int blockState, double x, double y, double z) {
        super(UUID.randomUUID(), x, y, z);
        this.server = server;
        this.blockState = blockState;
    }

    /** The state to restore on landing; also the entity's spawn data field. */
    public int blockState() {
        return blockState;
    }

    @Override
    public void tick(Region region) {
        if (++age > MAX_AGE) {
            land(region, (int) Math.floor(y));
            return;
        }

        int belowY = (int) Math.floor(y - GROUND_PROBE);
        if (belowY < server.world().minY()) {
            // Fell out of the world. Vanishes rather than landing, matching vanilla.
            region.removeEntity(this);
            return;
        }

        int below = server.world().getBlock((int) Math.floor(x), belowY, (int) Math.floor(z));
        if (dev.quasar.world.block.BlockCollision.isPassable(below)) {
            motionY = Math.max(motionY - GRAVITY, TERMINAL_VELOCITY);
            y += motionY;
            return;
        }
        land(region, belowY + 1);
    }

    /**
     * Turns back into a block.
     *
     * <p>If the destination is not free -- someone built into the column mid-fall -- the block is
     * dropped as an item instead of overwriting what is there. Vanilla does the same, and silently
     * deleting a player's block would be worse than a stray item.
     */
    private void land(Region region, int landingY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        int existing = server.world().getBlock(blockX, landingY, blockZ);
        if (dev.quasar.world.block.BlockCollision.isPassable(existing)) {
            server.world().setBlock(blockX, landingY, blockZ, blockState);
            region.broadcastBlockUpdate(blockX, landingY, blockZ, blockState);
        } else {
            dev.quasar.world.block.BlockStateRegistry.State state =
                    dev.quasar.world.block.BlockStateRegistry.byId(blockState);
            int itemId = state == null
                    ? 0 : dev.quasar.item.ItemRegistry.itemForBlockName(state.name());
            if (itemId > 0) {
                server.spawnItem(dev.quasar.item.ItemStack.of(itemId, 1),
                        x, landingY + 0.5, z, ItemEntity.DEFAULT_PICKUP_DELAY);
            }
        }
        region.removeEntity(this);
    }

    @Override
    public String toString() {
        return "FallingBlockEntity[state " + blockState
                + " at " + String.format("%.1f, %.1f, %.1f", x, y, z) + "]";
    }
}
