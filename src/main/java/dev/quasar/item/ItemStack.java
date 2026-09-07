package dev.quasar.item;

/**
 * A quantity of one item.
 *
 * <p>No component data. This server does not model enchantments, damage, custom names or any of the
 * other component types, and pretending to would mean silently dropping them on the first click.
 * Stacks moved through a container here keep their identity and count and nothing else.
 */
public record ItemStack(int itemId, int count) {

    public static final ItemStack EMPTY = new ItemStack(0, 0);

    /** Vanilla's default cap. Items with smaller caps are not distinguished. */
    public static final int MAX_STACK = 64;

    public static ItemStack of(int itemId, int count) {
        return itemId <= 0 || count <= 0 ? EMPTY : new ItemStack(itemId, count);
    }

    public boolean isEmpty() {
        return itemId <= 0 || count <= 0;
    }

    /** Whether two stacks can merge, which here is just "same item". */
    public boolean stacksWith(ItemStack other) {
        return !isEmpty() && !other.isEmpty() && itemId == other.itemId;
    }

    public ItemStack withCount(int newCount) {
        return of(itemId, newCount);
    }

    public ItemStack grow(int amount) {
        return withCount(count + amount);
    }

    public ItemStack shrink(int amount) {
        return withCount(count - amount);
    }

    public int spaceLeft() {
        return isEmpty() ? MAX_STACK : MAX_STACK - count;
    }
}
