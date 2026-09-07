package dev.quasar.net;

import dev.quasar.net.pipeline.CompressionCodec;
import dev.quasar.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client connection: its protocol state, its outbound encoder, and the dispatch into whichever
 * {@link PacketListener} matches the current state.
 *
 * <p>All inbound work happens on this channel's Netty event loop. Outbound sends are safe from any
 * thread — Netty queues a write from a foreign thread onto the loop — which is what lets region
 * tick threads push packets directly without a handoff.
 */
public final class Connection extends SimpleChannelInboundHandler<ByteBuf> {

    /** Below this size compression costs more than it saves; matches the usual vanilla default. */
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;

    private static final int MAX_UNCOMPRESSED_PACKET = 8 * 1024 * 1024;

    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();

    private Channel channel;
    private volatile ProtocolState state = ProtocolState.HANDSHAKING;
    private volatile PacketListener listener;
    private volatile String identity = "unknown";

    public Channel channel() {
        return channel;
    }

    public boolean isOpen() {
        return channel != null && channel.isActive() && state != ProtocolState.CLOSED;
    }

    public SocketAddress remoteAddress() {
        return channel == null ? null : channel.remoteAddress();
    }

    public ProtocolState state() {
        return state;
    }

    public void setState(ProtocolState state) {
        this.state = state;
    }

    public void setListener(PacketListener listener) {
        this.listener = listener;
    }

    public PacketListener listener() {
        return listener;
    }

    /** A label for logs — the player name once known, the remote address before that. */
    public String identity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public long packetsSent() {
        return packetsSent.get();
    }

    public long packetsReceived() {
        return packetsReceived.get();
    }

    // --------------------------------------------------------------------------------- sending

    /** Encodes and sends a packet. Safe from any thread. */
    public void send(int packetId, PacketWriter body) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        ByteBuf buf = channel.alloc().buffer();
        try {
            ByteBufs.writeVarInt(buf, packetId);
            body.write(buf);
        } catch (Throwable t) {
            buf.release();
            Log.error("Failed to encode packet 0x" + Integer.toHexString(packetId) + " for " + identity, t);
            return;
        }
        packetsSent.incrementAndGet();
        channel.writeAndFlush(buf, channel.voidPromise());
    }

    /** Sends a packet and closes the channel once it has gone out. */
    public void sendAndClose(int packetId, PacketWriter body) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        ByteBuf buf = channel.alloc().buffer();
        ByteBufs.writeVarInt(buf, packetId);
        body.write(buf);
        state = ProtocolState.CLOSED;
        channel.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
    }

    public void close() {
        state = ProtocolState.CLOSED;
        if (channel != null) {
            channel.close();
        }
    }

    /**
     * Turns on packet compression. Install it only after the Set Compression packet has been
     * written, which the caller guarantees by sending that packet first — writes are ordered on the
     * event loop, so the packet announcing compression goes out uncompressed.
     *
     * <p>Position matters. Inbound must run frame-decoder then decompress; outbound must compress
     * then frame-encode. Outbound travels from the tail towards the head, so placing the codec
     * after {@code frame-encoder} in list order satisfies both directions at once.
     */
    public void enableCompression(int threshold) {
        if (channel.pipeline().get("compression") != null) {
            return;
        }
        channel.pipeline().addAfter("frame-encoder", "compression",
                new CompressionCodec(threshold, MAX_UNCOMPRESSED_PACKET));
    }

    // ------------------------------------------------------------------------------- inbound

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.channel = ctx.channel();
        this.identity = String.valueOf(ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        PacketListener current = listener;
        if (current == null || state == ProtocolState.CLOSED) {
            return;
        }
        packetsReceived.incrementAndGet();
        int packetId;
        try {
            packetId = ByteBufs.readVarInt(msg);
        } catch (Exception e) {
            Log.warn("Malformed packet header from %s: %s", identity, e.getMessage());
            close();
            return;
        }
        try {
            current.handle(packetId, msg);
        } catch (DecoderException e) {
            Log.warn("Bad packet 0x%02X in state %s from %s: %s", packetId, state, identity, e.getMessage());
            close();
        } catch (Exception e) {
            Log.error("Error handling packet 0x" + Integer.toHexString(packetId)
                    + " in state " + state + " from " + identity, e);
            close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        state = ProtocolState.CLOSED;
        PacketListener current = listener;
        if (current != null) {
            try {
                current.onDisconnect();
            } catch (Exception e) {
                Log.error("Disconnect handler failed for " + identity, e);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Half-open sockets and clients that vanish mid-handshake are routine, not errors worth a
        // stack trace at INFO level.
        if (cause instanceof java.io.IOException) {
            Log.debug("Connection %s dropped: %s", identity, cause.getMessage());
        } else {
            Log.warn("Connection %s error: %s", identity, cause);
        }
        close();
    }
}
