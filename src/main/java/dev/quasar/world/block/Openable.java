package dev.quasar.world.block;

import java.util.HashMap;
import java.util.Map;

/**
 * Blocks that swing when you right-click them: doors, trapdoors and fence gates.
 *
 * <p>They all carry an {@code open} property, so the toggle is the same for each. What differs is
 * that a door is two blocks and both halves hold the flag -- swinging only the clicked half leaves
 * the other shut, and the client draws a door folded in half.
 *
 * <p>Iron doors and iron trapdoors are deliberately excluded. In vanilla they do not respond to a
 * hand at all and open only under redstone power, and a server that opened them by hand would be
 * quietly wrong in a way that breaks the one thing they are used for.
 */
public final class Openable {

    private Openable() {}

    /** Whether right-clicking this block should swing it. */
    public static boolean isOpenable(int state) {
        BlockStateRegistry.State current = BlockStateRegistry.byId(state);
        if (current == null || !current.properties().containsKey("open")) {
            return false;
        }
        String name = current.name();
        if (name.equals("minecraft:iron_door") || name.equals("minecraft:iron_trapdoor")) {
            return false;
        }
        return name.endsWith("_door") || name.endsWith("_trapdoor") || name.endsWith("_fence_gate");
    }

    /** The same block with its {@code open} flag flipped, or the original if that state is absent. */
    public static int toggleOpen(int state) {
        BlockStateRegistry.State current = BlockStateRegistry.byId(state);
        if (current == null) {
            return state;
        }
        String open = current.properties().get("open");
        if (open == null) {
            return state;
        }
        Map<String, String> properties = new HashMap<>(current.properties());
        properties.put("open", "true".equals(open) ? "false" : "true");
        int found = BlockStateRegistry.idFor(current.name(), properties);
        return found >= 0 ? found : state;
    }

    public static boolean isOpen(int state) {
        BlockStateRegistry.State current = BlockStateRegistry.byId(state);
        return current != null && "true".equals(current.properties().get("open"));
    }
}
