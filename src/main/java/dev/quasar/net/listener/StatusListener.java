package dev.quasar.net.listener;

import dev.quasar.QuasarServer;
import dev.quasar.net.ByteBufs;
import dev.quasar.net.Connection;
import dev.quasar.net.PacketListener;
import dev.quasar.net.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/** Answers the server-list ping: the JSON status blob, then the latency echo. */
public final class StatusListener implements PacketListener {

    private final QuasarServer server;
    private final Connection connection;
    private final int clientProtocol;
    private boolean responded;

    public StatusListener(QuasarServer server, Connection connection, int clientProtocol) {
        this.server = server;
        this.connection = connection;
        this.clientProtocol = clientProtocol;
    }

    @Override
    public void handle(int packetId, ByteBuf data) {
        if (packetId == Protocol.STATUS_SERVERBOUND_REQUEST) {
            if (responded) {
                throw new DecoderException("Duplicate status request");
            }
            responded = true;
            String json = server.statusJson(clientProtocol);
            connection.send(Protocol.STATUS_CLIENTBOUND_RESPONSE, buf -> ByteBufs.writeString(buf, json));
        } else if (packetId == Protocol.STATUS_SERVERBOUND_PING) {
            long payload = data.readLong();
            // The client closes the connection itself once the pong arrives.
            connection.sendAndClose(Protocol.STATUS_CLIENTBOUND_PONG, buf -> buf.writeLong(payload));
        } else {
            throw new DecoderException("Unexpected status packet 0x" + Integer.toHexString(packetId));
        }
    }
}
