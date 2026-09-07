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

/** Reads the single handshake packet and routes the connection to status or login. */
public final class HandshakeListener implements PacketListener {

    private final QuasarServer server;
    private final Connection connection;

    public HandshakeListener(QuasarServer server, Connection connection) {
        this.server = server;
        this.connection = connection;
    }

    @Override
    public void handle(int packetId, ByteBuf data) {
        if (packetId != Protocol.HANDSHAKE_SERVERBOUND_INTENTION) {
            throw new DecoderException("Expected handshake, got 0x" + Integer.toHexString(packetId));
        }

        int clientProtocol = ByteBufs.readVarInt(data);
        String serverAddress = ByteBufs.readString(data, 255);
        int serverPort = data.readUnsignedShort();
        int intent = ByteBufs.readVarInt(data);

        Log.debug("Handshake from %s: protocol=%d addr=%s:%d intent=%d",
                connection.identity(), clientProtocol, serverAddress, serverPort, intent);

        ProtocolState next;
        try {
            next = ProtocolState.fromIntent(intent);
        } catch (IllegalArgumentException e) {
            throw new DecoderException(e.getMessage());
        }

        connection.setState(next);
        if (next == ProtocolState.STATUS) {
            // Status pings from any version are answered; the version mismatch is what the client
            // renders in the server list, so rejecting here would just hide useful information.
            connection.setListener(new StatusListener(server, connection, clientProtocol));
        } else {
            if (clientProtocol != Protocol.VERSION) {
                // At INFO, not DEBUG: this is the single most useful line when someone cannot join,
                // and it names the exact number to put in quasar.properties to retarget.
                Log.info("Rejected %s: client speaks protocol %d, server speaks %d (%s). "
                                + "To target the client instead, start with -Dquasar.protocol=%d",
                        connection.identity(), clientProtocol, Protocol.VERSION,
                        Protocol.VERSION_NAME, clientProtocol);
                String message = clientProtocol < Protocol.VERSION
                        ? "Outdated client — this server runs " + Protocol.VERSION_NAME
                        : "Outdated server — this server runs " + Protocol.VERSION_NAME;
                connection.sendAndClose(Protocol.LOGIN_CLIENTBOUND_DISCONNECT,
                        buf -> ByteBufs.writeString(buf, "{\"text\":\"" + message + "\"}"));
                return;
            }
            connection.setListener(new LoginListener(server, connection));
        }
    }
}
