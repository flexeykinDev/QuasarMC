package dev.quasar.net;

import dev.quasar.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Packet identifiers for the targeted protocol version.
 *
 * <h2>Provenance</h2>
 * Every value below was extracted from Mojang's own packet report for 1.21.4 — run
 * {@code java -jar server.jar --reports} and read {@code generated/reports/packets.json}, which
 * lists each packet with its phase, direction and protocol ID. Do that rather than guessing if you
 * retarget a version: three of these were wrong when hand-written, and the symptom is a client that
 * disconnects while decoding an unrelated packet whose ID happened to collide.
 *
 * <p>Every value can also be overridden at runtime without recompiling: drop a
 * {@code protocol.properties} next to the jar with lines like
 * <pre>
 * play.clientbound.login = 0x2C
 * play.clientbound.chunk_data = 0x27
 * </pre>
 * Keys are the constant names lowercased with dots, as listed in {@link #KEY_NAMES}.
 *
 * <p>Authoritative reference: <a href="https://minecraft.wiki/w/Java_Edition_protocol">
 * minecraft.wiki/w/Java_Edition_protocol</a> — pick the page revision matching {@link #VERSION_NAME}.
 */
public final class Protocol {

    /** Protocol number sent in the handshake. 769 == Minecraft 1.21.4. */
    public static final int VERSION = Integer.getInteger("quasar.protocol", 769);

    public static final String VERSION_NAME = System.getProperty("quasar.versionName", "1.21.4");

    private static final Properties OVERRIDES = loadOverrides();

    // ---------------------------------------------------------------- handshake / status / login

    public static final int HANDSHAKE_SERVERBOUND_INTENTION = id("handshake.serverbound.intention", 0x00);

    public static final int STATUS_CLIENTBOUND_RESPONSE = id("status.clientbound.response", 0x00);
    public static final int STATUS_CLIENTBOUND_PONG = id("status.clientbound.pong", 0x01);
    public static final int STATUS_SERVERBOUND_REQUEST = id("status.serverbound.request", 0x00);
    public static final int STATUS_SERVERBOUND_PING = id("status.serverbound.ping", 0x01);

    public static final int LOGIN_CLIENTBOUND_DISCONNECT = id("login.clientbound.disconnect", 0x00);
    public static final int LOGIN_CLIENTBOUND_ENCRYPTION_REQUEST = id("login.clientbound.encryption_request", 0x01);
    public static final int LOGIN_CLIENTBOUND_SUCCESS = id("login.clientbound.success", 0x02);
    public static final int LOGIN_CLIENTBOUND_SET_COMPRESSION = id("login.clientbound.set_compression", 0x03);

    public static final int LOGIN_SERVERBOUND_START = id("login.serverbound.start", 0x00);
    public static final int LOGIN_SERVERBOUND_ENCRYPTION_RESPONSE = id("login.serverbound.encryption_response", 0x01);
    public static final int LOGIN_SERVERBOUND_PLUGIN_RESPONSE = id("login.serverbound.plugin_response", 0x02);
    public static final int LOGIN_SERVERBOUND_ACKNOWLEDGED = id("login.serverbound.acknowledged", 0x03);

    // ------------------------------------------------------------------------------ configuration

    public static final int CONFIG_CLIENTBOUND_PLUGIN_MESSAGE = id("config.clientbound.plugin_message", 0x01);
    public static final int CONFIG_CLIENTBOUND_DISCONNECT = id("config.clientbound.disconnect", 0x02);
    public static final int CONFIG_CLIENTBOUND_FINISH = id("config.clientbound.finish", 0x03);
    public static final int CONFIG_CLIENTBOUND_KEEP_ALIVE = id("config.clientbound.keep_alive", 0x04);
    public static final int CONFIG_CLIENTBOUND_REGISTRY_DATA = id("config.clientbound.registry_data", 0x07);
    public static final int CONFIG_CLIENTBOUND_UPDATE_TAGS = id("config.clientbound.update_tags", 0x0D);
    public static final int CONFIG_CLIENTBOUND_KNOWN_PACKS = id("config.clientbound.known_packs", 0x0E);

    public static final int CONFIG_SERVERBOUND_CLIENT_INFORMATION = id("config.serverbound.client_information", 0x00);
    public static final int CONFIG_SERVERBOUND_PLUGIN_MESSAGE = id("config.serverbound.plugin_message", 0x02);
    public static final int CONFIG_SERVERBOUND_FINISH_ACK = id("config.serverbound.finish_ack", 0x03);
    public static final int CONFIG_SERVERBOUND_KEEP_ALIVE = id("config.serverbound.keep_alive", 0x04);
    public static final int CONFIG_SERVERBOUND_KNOWN_PACKS = id("config.serverbound.known_packs", 0x07);

    // -------------------------------------------------------------------------------------- play

    public static final int PLAY_CLIENTBOUND_DISCONNECT = id("play.clientbound.disconnect", 0x1D);
    public static final int PLAY_CLIENTBOUND_GAME_EVENT = id("play.clientbound.game_event", 0x23);
    public static final int PLAY_CLIENTBOUND_KEEP_ALIVE = id("play.clientbound.keep_alive", 0x27);
    /** {@code level_chunk_with_light} in Mojang's registry. */
    public static final int PLAY_CLIENTBOUND_CHUNK_DATA = id("play.clientbound.chunk_data", 0x28);

    /**
     * {@code light_update}, for light that changes after a chunk has already been sent.
     *
     * <p>0x2B, read from Mojang's generated packet report rather than counted by hand -- the
     * set_default_spawn_position collision proved how a guessed ID surfaces: as a decode failure
     * inside some unrelated packet the server never sent.
     */
    public static final int PLAY_CLIENTBOUND_LIGHT_UPDATE = id("play.clientbound.light_update", 0x2B);
    public static final int PLAY_CLIENTBOUND_LOGIN = id("play.clientbound.login", 0x2C);
    public static final int PLAY_CLIENTBOUND_PLAYER_POSITION = id("play.clientbound.player_position", 0x42);
    public static final int PLAY_CLIENTBOUND_SET_CENTER_CHUNK = id("play.clientbound.set_center_chunk", 0x58);
    /** 0x5A is {@code set_cursor_item} — getting this one wrong is what broke the first real join. */
    public static final int PLAY_CLIENTBOUND_SET_DEFAULT_SPAWN = id("play.clientbound.set_default_spawn", 0x5B);
    public static final int PLAY_CLIENTBOUND_SYSTEM_CHAT = id("play.clientbound.system_chat", 0x73);
    public static final int PLAY_CLIENTBOUND_BLOCK_UPDATE = id("play.clientbound.block_update", 0x09);

    /**
     * Acknowledges a block change by sequence number. Without it the client reverts its optimistic
     * local prediction and the block visibly snaps back.
     */
    public static final int PLAY_CLIENTBOUND_BLOCK_CHANGED_ACK = id("play.clientbound.block_changed_ack", 0x05);

    /** Sets the whole of a container's contents; window 0 is the player's own inventory. */
    public static final int PLAY_CLIENTBOUND_CONTAINER_SET_CONTENT =
            id("play.clientbound.container_set_content", 0x13);

    /** Sets one slot, which is how a picked block reaches the hotbar. */
    public static final int PLAY_CLIENTBOUND_CONTAINER_SET_SLOT =
            id("play.clientbound.container_set_slot", 0x15);

    public static final int PLAY_CLIENTBOUND_OPEN_SCREEN = id("play.clientbound.open_screen", 0x35);

    public static final int PLAY_SERVERBOUND_CONTAINER_CLICK =
            id("play.serverbound.container_click", 0x10);
    public static final int PLAY_SERVERBOUND_CONTAINER_CLOSE =
            id("play.serverbound.container_close", 0x11);

    // Entity tracking: what makes other players visible.
    public static final int PLAY_CLIENTBOUND_ADD_ENTITY = id("play.clientbound.add_entity", 0x01);
    public static final int PLAY_CLIENTBOUND_REMOVE_ENTITIES = id("play.clientbound.remove_entities", 0x47);
    public static final int PLAY_CLIENTBOUND_MOVE_ENTITY_POS_ROT =
            id("play.clientbound.move_entity_pos_rot", 0x30);
    public static final int PLAY_CLIENTBOUND_ROTATE_HEAD = id("play.clientbound.rotate_head", 0x4D);
    public static final int PLAY_CLIENTBOUND_PLAYER_INFO_UPDATE =
            id("play.clientbound.player_info_update", 0x40);
    public static final int PLAY_CLIENTBOUND_PLAYER_INFO_REMOVE =
            id("play.clientbound.player_info_remove", 0x3F);

    public static final int PLAY_CLIENTBOUND_SET_ENTITY_DATA =
            id("play.clientbound.set_entity_data", 0x5D);
    public static final int PLAY_CLIENTBOUND_TAKE_ITEM_ENTITY =
            id("play.clientbound.take_item_entity", 0x76);

    /** Registry ID of {@code minecraft:player}, needed by Add Entity. */
    public static final int ENTITY_TYPE_PLAYER = Integer.getInteger("quasar.playerEntityType", 147);

    /** Registry ID of {@code minecraft:item}. */
    public static final int ENTITY_TYPE_ITEM = Integer.getInteger("quasar.itemEntityType", 68);

    /**
     * Entity metadata slot holding an item entity's stack, and the serializer that encodes it.
     *
     * <p>A dropped stack is invisible without this: Add Entity carries no item, so the client has
     * nothing to render until the metadata arrives. Unlike packet and block IDs these are not in
     * any generated report — they come from the order of the entity-data serializer registry — so
     * they are overridable in case a version renumbers them.
     */
    public static final int ITEM_ENTITY_DATA_INDEX = Integer.getInteger("quasar.itemDataIndex", 8);
    public static final int DATA_SERIALIZER_ITEM_STACK = Integer.getInteger("quasar.itemSerializer", 7);

    public static final int PLAY_SERVERBOUND_KEEP_ALIVE = id("play.serverbound.keep_alive", 0x1A);
    public static final int PLAY_SERVERBOUND_CHAT = id("play.serverbound.chat", 0x07);
    public static final int PLAY_SERVERBOUND_MOVE_POS = id("play.serverbound.move_pos", 0x1C);
    public static final int PLAY_SERVERBOUND_MOVE_POS_ROT = id("play.serverbound.move_pos_rot", 0x1D);
    public static final int PLAY_SERVERBOUND_MOVE_ROT = id("play.serverbound.move_rot", 0x1E);
    public static final int PLAY_SERVERBOUND_MOVE_STATUS = id("play.serverbound.move_status", 0x1F);
    public static final int PLAY_SERVERBOUND_CLIENT_TICK_END = id("play.serverbound.client_tick_end", 0x0B);
    public static final int PLAY_SERVERBOUND_CONFIRM_TELEPORT = id("play.serverbound.confirm_teleport", 0x00);
    public static final int PLAY_SERVERBOUND_PLAYER_ACTION = id("play.serverbound.player_action", 0x27);
    public static final int PLAY_SERVERBOUND_USE_ITEM_ON = id("play.serverbound.use_item_on", 0x3C);
    public static final int PLAY_SERVERBOUND_SET_CARRIED_ITEM = id("play.serverbound.set_carried_item", 0x33);

    /** How a creative client reports picking an item, and how this server learns what you hold. */
    public static final int PLAY_SERVERBOUND_SET_CREATIVE_MODE_SLOT =
            id("play.serverbound.set_creative_mode_slot", 0x36);

    /** Middle-click on a block. The client asks; the server decides what lands in the hotbar. */
    public static final int PLAY_SERVERBOUND_PICK_ITEM_FROM_BLOCK =
            id("play.serverbound.pick_item_from_block", 0x22);

    /** Documentation aid — every overridable key, in declaration order. */
    public static final String[] KEY_NAMES = {
        "handshake.serverbound.intention",
        "status.clientbound.response", "status.clientbound.pong",
        "status.serverbound.request", "status.serverbound.ping",
        "login.clientbound.disconnect", "login.clientbound.encryption_request",
        "login.clientbound.success", "login.clientbound.set_compression",
        "login.serverbound.start", "login.serverbound.encryption_response",
        "login.serverbound.plugin_response", "login.serverbound.acknowledged",
        "config.clientbound.plugin_message", "config.clientbound.disconnect",
        "config.clientbound.finish", "config.clientbound.keep_alive",
        "config.clientbound.registry_data", "config.clientbound.update_tags",
        "config.clientbound.known_packs",
        "config.serverbound.client_information", "config.serverbound.plugin_message",
        "config.serverbound.finish_ack", "config.serverbound.keep_alive",
        "config.serverbound.known_packs",
        "play.clientbound.disconnect", "play.clientbound.game_event",
        "play.clientbound.keep_alive", "play.clientbound.chunk_data",
        "play.clientbound.login", "play.clientbound.player_position",
        "play.clientbound.set_center_chunk", "play.clientbound.set_default_spawn",
        "play.clientbound.system_chat", "play.clientbound.block_update",
        "play.serverbound.keep_alive", "play.serverbound.chat",
        "play.serverbound.move_pos", "play.serverbound.move_pos_rot",
        "play.serverbound.move_rot", "play.serverbound.move_status",
        "play.serverbound.client_tick_end", "play.serverbound.confirm_teleport",
    };

    private Protocol() {}

    private static int id(String key, int fallback) {
        String raw = OVERRIDES.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.decode(raw.trim());
            if (parsed != fallback) {
                Log.info("Protocol override: %s = 0x%02X (built-in default was 0x%02X)", key, parsed, fallback);
            }
            return parsed;
        } catch (NumberFormatException e) {
            Log.warn("Ignoring unparseable protocol override %s=%s", key, raw);
            return fallback;
        }
    }

    private static Properties loadOverrides() {
        Properties props = new Properties();
        Path file = Path.of("protocol.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                Log.info("Loaded %d protocol override(s) from %s", props.size(), file.toAbsolutePath());
            } catch (IOException e) {
                Log.warn("Could not read protocol.properties: %s", e.getMessage());
            }
        }
        return props;
    }
}
