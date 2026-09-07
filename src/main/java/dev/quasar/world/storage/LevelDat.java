package dev.quasar.world.storage;

import dev.quasar.nbt.Nbt;
import dev.quasar.nbt.NbtIo;
import dev.quasar.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a {@code level.dat} so the world directory is recognised as a world.
 *
 * <p>Without it the region files are still valid Anvil and readable by tools that take a raw
 * {@code .mca}, but Minecraft will not list the folder and world editors will not open it.
 *
 * <p>Only written when absent. Importing a vanilla world means adopting <em>its</em>
 * {@code level.dat}; overwriting one would discard the real generator settings, seed and player
 * data for a stub.
 *
 * <p>Note what this cannot fix: terrain here comes from this server's own noise generator, not
 * Mojang's. Chunks this server saved load back exactly, but anything vanilla generates beyond them
 * follows vanilla's generator and will not line up at the seams.
 */
public final class LevelDat {

    private LevelDat() {}

    public static void writeIfAbsent(Path worldDirectory, String levelName, long seed,
                                     int spawnX, int spawnY, int spawnZ) {
        Path file = worldDirectory.resolve("level.dat");
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(worldDirectory);
            Nbt.NbtCompound root = Nbt.compound().put("Data", data(levelName, seed, spawnX, spawnY, spawnZ));
            Files.write(file, NbtIo.write(root, NbtIo.COMPRESSION_GZIP));
            Log.info("Wrote %s", file.toAbsolutePath());
        } catch (IOException e) {
            Log.warn("Could not write level.dat: %s", e.getMessage());
        }
    }

    private static Nbt.NbtCompound data(String levelName, long seed,
                                        int spawnX, int spawnY, int spawnZ) {
        return Nbt.compound()
                .putInt("DataVersion", AnvilChunkCodec.DATA_VERSION)
                .putInt("version", 19133) // the Anvil storage version, unchanged for years
                .putString("LevelName", levelName)
                .putLong("LastPlayed", System.currentTimeMillis())
                .putInt("GameType", 1)   // creative, matching what the server puts players in
                .putBoolean("allowCommands", true)
                .putBoolean("hardcore", false)
                .putBoolean("initialized", true)
                .putByte("Difficulty", 2)
                .putBoolean("DifficultyLocked", false)
                .putInt("SpawnX", spawnX)
                .putInt("SpawnY", spawnY)
                .putInt("SpawnZ", spawnZ)
                .putFloat("SpawnAngle", 0.0f)
                .putLong("Time", 0L)
                .putLong("DayTime", 0L)
                .putInt("clearWeatherTime", 0)
                .putInt("rainTime", 100000)
                .putInt("thunderTime", 100000)
                .putBoolean("raining", false)
                .putBoolean("thundering", false)
                .putDouble("BorderCenterX", 0.0)
                .putDouble("BorderCenterZ", 0.0)
                .putDouble("BorderSize", 59999968.0)
                .putDouble("BorderSafeZone", 5.0)
                .putDouble("BorderWarningBlocks", 5.0)
                .putDouble("BorderWarningTime", 15.0)
                .putDouble("BorderDamagePerBlock", 0.2)
                .putDouble("BorderSizeLerpTarget", 59999968.0)
                .putLong("BorderSizeLerpTime", 0L)
                .put("Version", Nbt.compound()
                        .putInt("Id", AnvilChunkCodec.DATA_VERSION)
                        .putString("Name", dev.quasar.net.Protocol.VERSION_NAME)
                        .putString("Series", "main")
                        .putBoolean("Snapshot", false))
                .put("DataPacks", Nbt.compound()
                        .put("Enabled", Nbt.NbtList.ofStrings("vanilla"))
                        .put("Disabled", new Nbt.NbtList(Nbt.TAG_STRING)))
                .put("GameRules", Nbt.compound()
                        .putString("doDaylightCycle", "true")
                        .putString("doMobSpawning", "false")
                        .putString("doFireTick", "false")
                        .putString("keepInventory", "true"))
                .put("WorldGenSettings", worldGenSettings(seed));
    }

    private static Nbt.NbtCompound worldGenSettings(long seed) {
        return Nbt.compound()
                .putBoolean("bonus_chest", false)
                .putLong("seed", seed)
                .putBoolean("generate_features", true)
                .put("dimensions", Nbt.compound()
                        .put("minecraft:overworld", dimension(
                                "minecraft:overworld", "minecraft:overworld", "minecraft:plains"))
                        .put("minecraft:the_nether", dimension(
                                "minecraft:the_nether", "minecraft:nether", "minecraft:nether_wastes"))
                        .put("minecraft:the_end", dimension(
                                "minecraft:the_end", "minecraft:end", "minecraft:the_end")));
    }

    private static Nbt.NbtCompound dimension(String type, String settings, String biome) {
        return Nbt.compound()
                .putString("type", type)
                .put("generator", Nbt.compound()
                        .putString("type", "minecraft:noise")
                        .putString("settings", settings)
                        .put("biome_source", Nbt.compound()
                                .putString("type", "minecraft:fixed")
                                .putString("biome", biome)));
    }
}
