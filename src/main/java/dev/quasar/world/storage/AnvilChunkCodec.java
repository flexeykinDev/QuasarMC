package dev.quasar.world.storage;

import dev.quasar.nbt.Nbt;
import dev.quasar.util.Log;
import dev.quasar.util.MathUtil;
import dev.quasar.world.Chunk;
import dev.quasar.world.ChunkSection;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link Chunk} to and from Anvil's chunk NBT.
 *
 * <h2>The packing rule</h2>
 * Block indices are packed into longs <em>without straddling</em> boundaries: since 1.16 each long
 * holds {@code 64 / bitsPerEntry} entries and the leftover high bits are simply unused. Bits per
 * entry is {@code max(4, ceil(log2(paletteSize)))} — the floor of 4 is not optional, and a section
 * with a two-entry palette still uses four bits.
 *
 * <p>A single-entry palette omits the data array entirely, which is both how vanilla writes uniform
 * sections and why an air-only chunk costs almost nothing.
 */
public final class AnvilChunkCodec {

    /** 1.21.4. Read from {@code version.json} in the client jar ({@code world_version}). */
    public static final int DATA_VERSION = 4189;

    private static final String BIOME = "minecraft:plains";

    private static boolean warnedAboutUnmappable;
    private static boolean warnedAboutDroppedData;

    private AnvilChunkCodec() {}

    // ------------------------------------------------------------------------------- writing

    public static Nbt.NbtCompound toNbt(Chunk chunk) {
        Nbt.NbtList sections = new Nbt.NbtList();
        int bottomSectionY = chunk.minY() >> 4;
        for (int i = 0; i < chunk.sectionCount(); i++) {
            sections.add(sectionToNbt(chunk.section(i), bottomSectionY + i));
        }

        long[] heightmap = chunk.packedHeightmap();
        Nbt.NbtCompound heightmaps = Nbt.compound()
                .put("MOTION_BLOCKING", new Nbt.NbtLongArray(heightmap))
                .put("WORLD_SURFACE", new Nbt.NbtLongArray(heightmap));

        return Nbt.compound()
                .putInt("DataVersion", DATA_VERSION)
                .putInt("xPos", chunk.x())
                .putInt("zPos", chunk.z())
                .putInt("yPos", bottomSectionY)
                .putString("Status", "minecraft:full")
                .putLong("LastUpdate", 0L)
                .putLong("InhabitedTime", 0L)
                .put("sections", sections)
                .put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND))
                .put("Heightmaps", heightmaps)
                .put("block_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND))
                .put("fluid_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND))
                .put("PostProcessing", new Nbt.NbtList(Nbt.TAG_LIST))
                .put("structures", Nbt.compound()
                        .put("References", Nbt.compound())
                        .put("starts", Nbt.compound()));
    }

    private static Nbt.NbtCompound sectionToNbt(ChunkSection section, int sectionY) {
        // Distinct states, in first-seen order, and the per-block index into that palette.
        Map<Integer, Integer> paletteIndex = new LinkedHashMap<>();
        int[] indices = new int[ChunkSection.VOLUME];
        int cursor = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int state = section.get(x, y, z);
                    Integer existing = paletteIndex.get(state);
                    if (existing == null) {
                        existing = paletteIndex.size();
                        paletteIndex.put(state, existing);
                    }
                    indices[cursor++] = existing;
                }
            }
        }

        Nbt.NbtList palette = new Nbt.NbtList(Nbt.TAG_COMPOUND);
        for (int state : paletteIndex.keySet()) {
            palette.add(paletteEntry(state));
        }

        Nbt.NbtCompound blockStates = Nbt.compound().put("palette", palette);
        if (paletteIndex.size() > 1) {
            blockStates.put("data", new Nbt.NbtLongArray(pack(indices, paletteIndex.size())));
        }

        return Nbt.compound()
                .putByte("Y", sectionY)
                .put("block_states", blockStates)
                .put("biomes", Nbt.compound()
                        .put("palette", Nbt.NbtList.ofStrings(BIOME)));
    }

    private static Nbt.NbtCompound paletteEntry(int state) {
        BlockStateRegistry.State known = BlockStateRegistry.byId(state);
        if (known == null) {
            if (!warnedAboutUnmappable) {
                warnedAboutUnmappable = true;
                Log.warn("Block state %d has no name in the block table; writing air. "
                        + "Supply blocks.json to widen the table.", state);
            }
            return Nbt.compound().putString("Name", "minecraft:air");
        }
        Nbt.NbtCompound entry = Nbt.compound().putString("Name", known.name());
        if (!known.properties().isEmpty()) {
            Nbt.NbtCompound properties = Nbt.compound();
            known.properties().forEach(properties::putString);
            entry.put("Properties", properties);
        }
        return entry;
    }

    /** Packs palette indices, {@code 64 / bits} per long, never straddling a boundary. */
    private static long[] pack(int[] indices, int paletteSize) {
        int bits = Math.max(4, MathUtil.ceilLog2(paletteSize));
        int entriesPerLong = 64 / bits;
        long[] out = new long[(indices.length + entriesPerLong - 1) / entriesPerLong];
        for (int i = 0; i < indices.length; i++) {
            int longIndex = i / entriesPerLong;
            int slot = i % entriesPerLong;
            out[longIndex] |= (long) indices[i] << (slot * bits);
        }
        return out;
    }

    // ------------------------------------------------------------------------------- reading

    /**
     * Rebuilds a chunk from Anvil NBT.
     *
     * @return the chunk, or {@code null} if it does not describe this position at this world height
     */
    public static Chunk fromNbt(Nbt.NbtCompound root, int expectedX, int expectedZ,
                                int minY, int height) {
        Integer x = intValue(root.get("xPos"));
        Integer z = intValue(root.get("zPos"));
        if (x == null || z == null || x != expectedX || z != expectedZ) {
            return null;
        }

        Chunk chunk = new Chunk(expectedX, expectedZ, minY, height);
        int bottomSectionY = minY >> 4;
        boolean lossy = false;

        if (root.get("sections") instanceof Nbt.NbtList sections) {
            for (Nbt element : sections.items()) {
                if (element instanceof Nbt.NbtCompound section) {
                    lossy |= readSection(chunk, section, bottomSectionY);
                }
            }
        }

        // Anything this server cannot represent must not be silently destroyed by a later rewrite.
        if (hasEntries(root.get("block_entities"))
                || hasEntries(root.get("block_ticks"))
                || hasEntries(root.get("fluid_ticks"))) {
            lossy = true;
            if (!warnedAboutDroppedData) {
                warnedAboutDroppedData = true;
                Log.warn("Chunk %d,%d carries block entities or scheduled ticks, which this server "
                        + "cannot represent; it is loaded read-only and will not be overwritten.",
                        expectedX, expectedZ);
            }
        }

        chunk.recalculateHeightmap();
        chunk.clearDirty();
        if (lossy) {
            chunk.blockSaving();
        }
        return chunk;
    }

    /** @return true if anything in this section could not be mapped */
    private static boolean readSection(Chunk chunk, Nbt.NbtCompound section, int bottomSectionY) {
        Integer sectionY = intValue(section.get("Y"));
        if (sectionY == null) {
            return false;
        }
        int index = sectionY - bottomSectionY;
        if (index < 0 || index >= chunk.sectionCount()) {
            // Light-only sections sit one above and one below the world; they hold no blocks.
            return false;
        }
        if (!(section.get("block_states") instanceof Nbt.NbtCompound blockStates)) {
            return false;
        }
        if (!(blockStates.get("palette") instanceof Nbt.NbtList palette) || palette.size() == 0) {
            return false;
        }

        boolean lossy = false;
        int[] states = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            states[i] = resolve(palette.items().get(i));
            if (states[i] < 0) {
                states[i] = BlockStateRegistry.UNKNOWN_PLACEHOLDER;
                lossy = true;
            }
        }

        ChunkSection target = chunk.section(index);
        if (!(blockStates.get("data") instanceof Nbt.NbtLongArray packed)) {
            // Single-valued palette: the whole section is one block.
            fill(target, states[0]);
            return lossy;
        }

        int bits = Math.max(4, MathUtil.ceilLog2(states.length));
        int entriesPerLong = 64 / bits;
        long mask = (1L << bits) - 1;
        long[] data = packed.value();

        for (int i = 0; i < ChunkSection.VOLUME; i++) {
            int longIndex = i / entriesPerLong;
            if (longIndex >= data.length) {
                break;
            }
            int slot = i % entriesPerLong;
            int paletteIdx = (int) ((data[longIndex] >>> (slot * bits)) & mask);
            int state = paletteIdx < states.length ? states[paletteIdx] : Blocks.AIR;
            // Index order is y, then z, then x.
            int y = i >> 8;
            int z = (i >> 4) & 15;
            int x = i & 15;
            target.set(x, y, z, state);
        }
        return lossy;
    }

    private static void fill(ChunkSection section, int state) {
        if (Blocks.isAir(state)) {
            return; // sections start as air
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.set(x, y, z, state);
                }
            }
        }
    }

    /** @return the state ID, or -1 when the palette entry names a block we cannot map */
    private static int resolve(Nbt entry) {
        if (!(entry instanceof Nbt.NbtCompound compound)
                || !(compound.get("Name") instanceof Nbt.NbtString name)) {
            return -1;
        }
        Map<String, String> properties = new LinkedHashMap<>();
        if (compound.get("Properties") instanceof Nbt.NbtCompound props) {
            for (Map.Entry<String, Nbt> property : props.entries().entrySet()) {
                if (property.getValue() instanceof Nbt.NbtString value) {
                    properties.put(property.getKey(), value.value());
                }
            }
        }
        return BlockStateRegistry.idFor(name.value(), properties);
    }

    private static boolean hasEntries(Nbt tag) {
        return tag instanceof Nbt.NbtList list && list.size() > 0;
    }

    private static Integer intValue(Nbt tag) {
        if (tag instanceof Nbt.NbtInt value) {
            return value.value();
        }
        if (tag instanceof Nbt.NbtByte value) {
            return (int) value.value();
        }
        if (tag instanceof Nbt.NbtShort value) {
            return (int) value.value();
        }
        return null;
    }

    /** Exposed for diagnostics: the palette names a chunk would be written with. */
    public static List<String> describePalette(Chunk chunk, int sectionIndex) {
        List<String> names = new ArrayList<>();
        Nbt.NbtCompound section = sectionToNbt(chunk.section(sectionIndex), 0);
        if (section.get("block_states") instanceof Nbt.NbtCompound states
                && states.get("palette") instanceof Nbt.NbtList palette) {
            for (Nbt entry : palette.items()) {
                if (entry instanceof Nbt.NbtCompound compound
                        && compound.get("Name") instanceof Nbt.NbtString name) {
                    names.add(name.value());
                }
            }
        }
        return names;
    }
}
