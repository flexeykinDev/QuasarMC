package dev.quasar.net.listener;

import dev.quasar.QuasarServer;
import dev.quasar.nbt.Nbt;
import dev.quasar.net.ByteBufs;
import dev.quasar.net.Connection;
import dev.quasar.net.PacketListener;
import dev.quasar.net.Protocol;
import dev.quasar.net.registry.Registries;
import dev.quasar.util.Log;
import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * The configuration phase: agree on datapacks, push registries, then hand over to play.
 *
 * <p>The ordering matters and is not obvious. The server offers its known packs; the client replies
 * with the subset it also has; only then may registry data go out, because the client uses the pack
 * agreement to decide which entries it can fill in from its own jar. Sending registries before that
 * exchange gets the connection dropped without a useful error.
 */
public final class ConfigurationListener implements PacketListener {

    private final QuasarServer server;
    private final Connection connection;
    private final String username;
    private final UUID uuid;

    private int viewDistance = 8;
    private boolean registriesSent;

    public ConfigurationListener(QuasarServer server, Connection connection, String username, UUID uuid) {
        this.server = server;
        this.connection = connection;
        this.username = username;
        this.uuid = uuid;
        sendKnownPacks();
    }

    private void sendKnownPacks() {
        connection.send(Protocol.CONFIG_CLIENTBOUND_KNOWN_PACKS, buf -> {
            ByteBufs.writeVarInt(buf, 1);
            ByteBufs.writeString(buf, "minecraft");
            ByteBufs.writeString(buf, "core");
            ByteBufs.writeString(buf, Protocol.VERSION_NAME);
        });
    }

    @Override
    public void handle(int packetId, ByteBuf data) {
        if (packetId == Protocol.CONFIG_SERVERBOUND_KNOWN_PACKS) {
            sendRegistriesAndFinish();
        } else if (packetId == Protocol.CONFIG_SERVERBOUND_CLIENT_INFORMATION) {
            readClientInformation(data);
        } else if (packetId == Protocol.CONFIG_SERVERBOUND_FINISH_ACK) {
            server.beginPlay(connection, username, uuid, viewDistance);

        } else {
            // Plugin messages, keep-alives and cookie responses need no action and are ignored
            // rather than treated as errors: clients and mods send them freely here. Logged
            // because a stalled join usually shows up as an unhandled ID at this stage.
            Log.debug("Unhandled configuration packet 0x%02X (%d bytes) from %s",
                    packetId, data.readableBytes(), username);
        }
    }

    private void readClientInformation(ByteBuf data) {
        try {
            ByteBufs.readString(data, 16); // locale
            int requested = data.readByte();
            viewDistance = Math.max(2, Math.min(requested, server.config().viewDistance));
            // Remaining fields (chat mode, skin parts, main hand, particle status) do not affect
            // anything this server does, so they are left unread.
        } catch (Exception e) {
            Log.debug("Could not parse client information from %s: %s", username, e.getMessage());
        }
    }

    private void sendRegistriesAndFinish() {
        if (registriesSent) {
            return;
        }
        registriesSent = true;

        List<Registries.Registry> registries = Registries.forWorld(server.world());
        for (Registries.Registry registry : registries) {
            connection.send(Protocol.CONFIG_CLIENTBOUND_REGISTRY_DATA, buf -> {
                ByteBufs.writeString(buf, registry.id());
                ByteBufs.writeVarInt(buf, registry.entries().size());
                for (Registries.Entry entry : registry.entries()) {
                    ByteBufs.writeString(buf, entry.id());
                    boolean hasData = entry.data() != null && !entry.data().isEmpty();
                    buf.writeBoolean(hasData);
                    if (hasData) {
                        Nbt.writeNetwork(buf, entry.data());
                    }
                }
            });
        }
        Log.debug("Sent %d registries to %s", registries.size(), username);

        connection.send(Protocol.CONFIG_CLIENTBOUND_FINISH, buf -> { });
    }
}
