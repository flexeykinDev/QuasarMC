package dev.quasar.net;

import io.netty.buffer.ByteBuf;

/**
 * Encodes a packet body (everything after the packet ID).
 *
 * <p>A functional interface rather than a class hierarchy: this server sends on the order of a
 * dozen distinct packets, and one small record per packet would be more ceremony than the encoding
 * it wraps.
 */
@FunctionalInterface
public interface PacketWriter {
    void write(ByteBuf buf);
}
