package dev.quasar.net.listener;

import dev.quasar.QuasarServer;
import dev.quasar.net.ByteBufs;
import dev.quasar.net.Connection;
import dev.quasar.net.PacketListener;
import dev.quasar.net.Protocol;
import dev.quasar.net.ProtocolState;
import dev.quasar.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The login handshake.
 *
 * <p>Offline mode only. Online mode would mean an RSA key exchange, AES stream ciphers on the
 * pipeline and a session-server round trip against Mojang; none of that is implemented, and the
 * server refuses to pretend otherwise — see {@link QuasarServer} startup, which fails loudly if
 * {@code server.online-mode} is set.
 */
public final class LoginListener implements PacketListener {

    /**
     * 1.20.5 through 1.21.1 ended Login Success with a "strict error handling" boolean, which
     * 1.21.2 removed. Flip this with {@code -Dquasar.loginSuccessStrictField=true} if you retarget
     * the server at one of those versions.
     */
    private static final boolean STRICT_ERROR_FIELD = Boolean.getBoolean("quasar.loginSuccessStrictField");

    private final QuasarServer server;
    private final Connection connection;

    private String username;
    private UUID uuid;

    public LoginListener(QuasarServer server, Connection connection) {
        this.server = server;
        this.connection = connection;
    }

    @Override
    public void handle(int packetId, ByteBuf data) {
        if (packetId == Protocol.LOGIN_SERVERBOUND_START) {
            handleLoginStart(data);
        } else if (packetId == Protocol.LOGIN_SERVERBOUND_ACKNOWLEDGED) {
            handleLoginAcknowledged();
        } else if (packetId == Protocol.LOGIN_SERVERBOUND_ENCRYPTION_RESPONSE) {
            disconnect("Encryption is not supported — run this server in offline mode");
        } else if (packetId != Protocol.LOGIN_SERVERBOUND_PLUGIN_RESPONSE) {
            throw new DecoderException("Unexpected login packet 0x" + Integer.toHexString(packetId));
        }
    }

    private void handleLoginStart(ByteBuf data) {
        if (username != null) {
            throw new DecoderException("Duplicate login start");
        }
        username = ByteBufs.readString(data, 16);
        if (!isValidUsername(username)) {
            disconnect("Invalid username");
            return;
        }
        // The client sends a UUID here, but in offline mode it is not authoritative: deriving it
        // the same way vanilla does keeps offline identities stable across sessions and servers.
        if (data.readableBytes() >= 16) {
            ByteBufs.readUuid(data);
        }
        uuid = offlineUuid(username);

        if (server.playerCount() >= server.config().maxPlayers) {
            disconnect("Server is full");
            return;
        }
        if (server.playerByName(username) != null) {
            disconnect("A player with that name is already connected");
            return;
        }

        connection.setIdentity(username);

        int threshold = server.config().compressionThreshold;
        if (threshold > 0) {
            connection.send(Protocol.LOGIN_CLIENTBOUND_SET_COMPRESSION,
                    buf -> ByteBufs.writeVarInt(buf, threshold));
            connection.enableCompression(threshold);
        }

        connection.send(Protocol.LOGIN_CLIENTBOUND_SUCCESS, buf -> {
            ByteBufs.writeUuid(buf, uuid);
            ByteBufs.writeString(buf, username);
            ByteBufs.writeVarInt(buf, 0); // no signed profile properties in offline mode
            if (STRICT_ERROR_FIELD) {
                buf.writeBoolean(true);
            }
        });
        Log.info("%s (%s) logging in from %s", username, uuid, connection.remoteAddress());
    }

    private void handleLoginAcknowledged() {
        if (username == null) {
            throw new DecoderException("Login acknowledged before login start");
        }
        connection.setState(ProtocolState.CONFIGURATION);
        connection.setListener(new ConfigurationListener(server, connection, username, uuid));
    }

    private void disconnect(String reason) {
        connection.sendAndClose(Protocol.LOGIN_CLIENTBOUND_DISCONNECT,
                buf -> ByteBufs.writeString(buf, "{\"text\":\"" + reason.replace("\"", "'") + "\"}"));
    }

    /** Same derivation vanilla uses for cracked accounts, so offline UUIDs match other servers. */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isValidUsername(String name) {
        if (name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
