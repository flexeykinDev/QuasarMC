package dev.quasar.entity;

import dev.quasar.QuasarServer;
import dev.quasar.engine.Region;
import dev.quasar.item.ItemStack;
import dev.quasar.util.Log;
import dev.quasar.world.block.Blocks;

import java.util.UUID;

/**
 * A stack of items lying in the world.
 *
 * <p>Everything here runs on the owning region's thread, and an item never changes its horizontal
 * position — it only falls — so it stays in the chunk it was dropped into and can never need state
 * another region owns.
 *
 * <p>No horizontal motion is a deliberate simplification. Vanilla throws items in an arc and lets
 * them bounce and slide, which needs real collision shapes; those do not exist here, and a
 * half-simulated one that clipped through walls would be worse than a stack that drops straight
 * down.
 */
public final class ItemEntity extends Entity {

    /** Five minutes at 20 ticks per second, matching vanilla. */
    public static final int DESPAWN_TICKS = 6000;

    /** Ticks before a freshly dropped stack can be picked up, so a drop is not instantly regained. */
    public static final int DEFAULT_PICKUP_DELAY = 20;

    private static final double GRAVITY = 0.04;
    private static final double TERMINAL_VELOCITY = -3.0;

    /** How far below the item to look for ground. Small enough not to skip a block at speed. */
    private static final double GROUND_PROBE = 0.06;

    /** How close a player must be to collect. Generous, since items do not move towards players. */
    private static final double PICKUP_RANGE_SQUARED = 1.6 * 1.6;

    /** Stacks within this distance of each other combine, so a broken container is not a carpet. */
    private static final double MERGE_RANGE_SQUARED = 1.0;

    private final QuasarServer server;
    private ItemStack stack;
    private int age;
    private int pickupDelay;
    private double motionY;

    /** Set when the stack changes, so trackers know to resend metadata. */
    private boolean stackDirty;

    public ItemEntity(QuasarServer server, ItemStack stack, double x, double y, double z,
                      int pickupDelay) {
        super(UUID.randomUUID(), x, y, z);
        this.server = server;
        this.stack = stack;
        this.pickupDelay = pickupDelay;
    }

    public ItemStack stack() {
        return stack;
    }

    public int age() {
        return age;
    }

    public int pickupDelay() {
        return pickupDelay;
    }

    public boolean consumeStackDirty() {
        boolean was = stackDirty;
        stackDirty = false;
        return was;
    }

    @Override
    public void tick(Region region) {
        if (++age > DESPAWN_TICKS) {
            region.removeEntity(this);
            return;
        }
        if (pickupDelay > 0) {
            pickupDelay--;
        }
        fall();
        mergeNearby(region);
        tryPickup(region);
    }

    /** Falls straight down until something solid is underneath. */
    private void fall() {
        if (y <= server.world().minY()) {
            motionY = 0;
            return;
        }
        int groundY = (int) Math.floor(y - GROUND_PROBE);
        int below = server.world().getBlock((int) Math.floor(x), groundY, (int) Math.floor(z));
        if (Blocks.isAir(below) || Blocks.isWater(below)) {
            motionY = Math.max(motionY - GRAVITY, TERMINAL_VELOCITY);
            y = Math.max(y + motionY, server.world().minY());
        } else if (motionY != 0) {
            // Landed: rest on the *top face* of the block underneath. Rounding to floor(y) instead
            // buries the item almost a full block deep whenever it lands mid-block, which puts it
            // out of pickup range and leaves it stuck there.
            motionY = 0;
            y = groundY + 1.0;
        }
    }

    /**
     * Absorbs nearby stacks of the same item.
     *
     * <p>Without this, emptying a container leaves dozens of separate entities in one spot, each
     * costing a spawn packet and a tick for every player watching.
     */
    private void mergeNearby(Region region) {
        if (stack.isEmpty() || stack.spaceLeft() <= 0) {
            return;
        }
        for (Entity other : region.entities()) {
            if (other == this || other.isRemoved() || !(other instanceof ItemEntity item)) {
                continue;
            }
            if (!item.stack.stacksWith(stack) || distanceSquared(other) > MERGE_RANGE_SQUARED) {
                continue;
            }
            int moved = Math.min(item.stack.count(), stack.spaceLeft());
            if (moved <= 0) {
                continue;
            }
            stack = stack.grow(moved);
            stackDirty = true;
            item.stack = item.stack.shrink(moved);
            item.stackDirty = true;
            if (item.stack.isEmpty()) {
                region.removeEntity(item);
            }
            if (stack.spaceLeft() <= 0) {
                return;
            }
        }
    }

    private void tryPickup(Region region) {
        if (pickupDelay > 0 || stack.isEmpty()) {
            return;
        }
        for (Entity other : region.entities()) {
            if (other.isRemoved() || !(other instanceof Player player)) {
                continue;
            }
            // Players are tracked at their feet, so allow for the body above that.
            double dx = player.x() - x;
            double dy = player.y() + 0.9 - y;
            double dz = player.z() - z;
            if (dx * dx + dy * dy + dz * dz > PICKUP_RANGE_SQUARED) {
                continue;
            }
            int taken = player.collect(this, stack);
            if (taken <= 0) {
                continue;
            }
            Log.debug("%s picked up %d x item %d", player.name(), taken, stack.itemId());
            stack = stack.shrink(taken);
            stackDirty = true;
            if (stack.isEmpty()) {
                region.removeEntity(this);
                return;
            }
        }
    }

    private double distanceSquared(Entity other) {
        double dx = other.x() - x;
        double dy = other.y() - y;
        double dz = other.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return "ItemEntity[" + stack.count() + " x " + stack.itemId()
                + " at " + String.format("%.1f, %.1f, %.1f", x, y, z) + "]";
    }
}
