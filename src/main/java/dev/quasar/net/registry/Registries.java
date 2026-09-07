package dev.quasar.net.registry;

import dev.quasar.nbt.Nbt;
import dev.quasar.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The datapack registries the server pushes to the client during configuration.
 *
 * <h2>Why this exists</h2>
 * Since 1.20.5 the client builds its dimension, biome and damage-type registries from what the
 * server sends rather than from its own jar, and it refuses to join if something it needs is
 * missing. This class is the minimum set that gets a vanilla client into the world.
 *
 * <h2>If a join fails with a registry error</h2>
 * This is the second most likely thing to need version-specific fixing, after
 * {@link dev.quasar.net.Protocol}. The authoritative content is whatever the vanilla server sends,
 * and you can dump it exactly: run {@code java -jar server.jar --reports} and read
 * {@code generated/reports/registries.json} plus the datapack files in the vanilla jar. Entry
 * <em>names</em> matter more than entry contents — a client will accept bland values but not a
 * missing key it expects to resolve.
 */
public final class Registries {

    /** One registry and its ordered entries. Order defines the numeric IDs the client will use. */
    public record Registry(String id, List<Entry> entries) {}

    public record Entry(String id, Nbt.NbtCompound data) {}

    /**
     * The single biome this server sends.
     *
     * <p>Referenced by name from other registries. Every biome ID mentioned anywhere in the data
     * below must exist in {@link #biomes()}, or the client aborts the join while binding
     * registries — so route all such references through this constant rather than typing a biome
     * name inline.
     */
    private static final String ONLY_BIOME = "minecraft:plains";

    private Registries() {}

    public static List<Registry> forWorld(World world) {
        List<Registry> out = new ArrayList<>();
        out.add(dimensionTypes(world));
        out.add(biomes());
        out.add(chatTypes());
        out.add(damageTypes());
        out.add(wolfVariants());
        out.add(paintingVariants());
        return out;
    }

    /** The dimension the player will actually be in; its min_y/height must match the world. */
    private static Registry dimensionTypes(World world) {
        Nbt.NbtCompound overworld = Nbt.compound()
                .putBoolean("has_skylight", true)
                .putBoolean("has_ceiling", false)
                .putBoolean("ultrawarm", false)
                .putBoolean("natural", true)
                .putDouble("coordinate_scale", 1.0)
                .putBoolean("bed_works", true)
                .putBoolean("respawn_anchor_works", false)
                .putInt("min_y", world.minY())
                .putInt("height", world.height())
                .putInt("logical_height", world.height())
                .putString("infiniburn", "#minecraft:infiniburn_overworld")
                .putString("effects", "minecraft:overworld")
                .putFloat("ambient_light", 0.0f)
                .putBoolean("piglin_safe", false)
                .putBoolean("has_raids", true)
                .putInt("monster_spawn_block_light_limit", 0)
                .putInt("monster_spawn_light_level", 0);

        return new Registry("minecraft:dimension_type", List.of(new Entry("minecraft:overworld", overworld)));
    }

    private static Registry biomes() {
        Nbt.NbtCompound plainsEffects = Nbt.compound()
                .putInt("fog_color", 0xC0D8FF)
                .putInt("water_color", 0x3F76E4)
                .putInt("water_fog_color", 0x050533)
                .putInt("sky_color", 0x78A7FF);

        Nbt.NbtCompound plains = Nbt.compound()
                .putBoolean("has_precipitation", true)
                .putFloat("temperature", 0.8f)
                .putFloat("downfall", 0.4f)
                .put("effects", plainsEffects);

        return new Registry("minecraft:worldgen/biome", List.of(new Entry(ONLY_BIOME, plains)));
    }

    /**
     * Chat type decoration. The client needs at least {@code minecraft:chat} to render player chat;
     * this server sends system messages, which do not consult this registry, but the entry is
     * required for the registry itself to validate.
     */
    private static Registry chatTypes() {
        Nbt.NbtCompound decoration = Nbt.compound()
                .putString("translation_key", "chat.type.text")
                .put("parameters", Nbt.NbtList.ofStrings("sender", "content"))
                .put("style", Nbt.compound());

        Nbt.NbtCompound chat = Nbt.compound()
                .put("chat", decoration)
                .put("narration", Nbt.compound()
                        .putString("translation_key", "chat.type.text.narrate")
                        .put("parameters", Nbt.NbtList.ofStrings("sender", "content"))
                        .put("style", Nbt.compound()));

        return new Registry("minecraft:chat_type", List.of(new Entry("minecraft:chat", chat)));
    }

    /**
     * Damage types. The client resolves death messages through this registry and expects the
     * vanilla key set, so all of them are listed even though this core never deals damage.
     */
    private static Registry damageTypes() {
        String[] names = {
            "in_fire", "campfire", "lightning_bolt", "on_fire", "lava", "hot_floor", "in_wall",
            "cramming", "drown", "starve", "cactus", "fall", "fly_into_wall", "ender_pearl",
            "out_of_world", "generic", "magic", "wither", "dragon_breath", "dry_out",
            "sweet_berry_bush", "freeze", "stalagmite", "falling_block", "falling_anvil",
            "falling_stalactite", "sting", "mob_attack", "mob_attack_no_aggro", "player_attack",
            "arrow", "trident", "mob_projectile", "spit", "unattributed_fireball", "fireball",
            "wind_charge", "thrown", "indirect_magic", "thorns", "explosion", "player_explosion",
            "sonic_boom", "bad_respawn_point", "outside_border", "generic_kill", "wither_skull",
            "mace_smash",
        };
        List<Entry> entries = new ArrayList<>(names.length);
        for (String name : names) {
            entries.add(new Entry("minecraft:" + name, Nbt.compound()
                    .putString("message_id", name)
                    .putFloat("exhaustion", 0.0f)
                    .putString("scaling", "when_caused_by_living_non_player")));
        }
        return new Registry("minecraft:damage_type", entries);
    }

    private static Registry wolfVariants() {
        String[] variants = {
            "pale", "spotted", "snowy", "black", "ashen", "rusty", "woods", "chestnut", "striped",
        };
        List<Entry> entries = new ArrayList<>(variants.length);
        for (String variant : variants) {
            String base = "minecraft:entity/wolf/wolf" + (variant.equals("pale") ? "" : "_" + variant);
            entries.add(new Entry("minecraft:" + variant, Nbt.compound()
                    .putString("wild_texture", base)
                    .putString("tame_texture", base + "_tame")
                    .putString("angry_texture", base + "_angry")
                    // Must name a biome this server actually sends. The client resolves
                    // cross-registry references when it processes Finish Configuration, and a key
                    // that was never sent fails the whole join with "Unbound values in registry".
                    // Referencing minecraft:taiga here — a biome we do not send — is exactly how
                    // this failed the first time against a real client.
                    .putString("biomes", ONLY_BIOME)));
        }
        return new Registry("minecraft:wolf_variant", entries);
    }

    private static Registry paintingVariants() {
        Nbt.NbtCompound kebab = Nbt.compound()
                .putString("asset_id", "minecraft:kebab")
                .putInt("height", 1)
                .putInt("width", 1);
        return new Registry("minecraft:painting_variant", List.of(new Entry("minecraft:kebab", kebab)));
    }
}
