package dev.quasar.world.block;

/**
 * Read-only block lookup.
 *
 * <p>Connection logic needs nothing from the world but the ability to ask what is at a position, so
 * it takes this rather than a {@link dev.quasar.world.World}. That keeps the rules testable against
 * a handful of blocks in a map, with no chunk store, generator or thread pools involved.
 */
@FunctionalInterface
public interface BlockAccess {
    int getBlock(int x, int y, int z);
}
