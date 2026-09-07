package dev.quasar.net;

import io.netty.buffer.ByteBuf;

/**
 * Handles inbound packets for one protocol state.
 *
 * <p>Called on the connection's Netty event loop. Implementations must not block and must not touch
 * region-owned state directly — they hand work to a region mailbox instead.
 */
public interface PacketListener {

    void handle(int packetId, ByteBuf data) throws Exception;

    /** Called once when the channel closes, regardless of reason. */
    default void onDisconnect() {}
}
