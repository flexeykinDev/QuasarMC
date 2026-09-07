package dev.quasar.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Splits the TCP stream into packets using the VarInt length prefix.
 *
 * <p>Written by hand rather than with {@code LengthFieldBasedFrameDecoder} because that decoder
 * cannot express a variable-width length field.
 */
public class VarIntFrameDecoder extends ByteToMessageDecoder {

    private final int maxPacketSize;

    public VarIntFrameDecoder(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!ctx.channel().isActive()) {
            in.clear();
            return;
        }
        in.markReaderIndex();

        int length = 0;
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                in.resetReaderIndex();
                return; // length prefix itself is still incomplete
            }
            byte b = in.readByte();
            length |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                if (length < 0 || length > maxPacketSize) {
                    throw new CorruptedFrameException(
                            "Packet length " + length + " outside [0, " + maxPacketSize + "]");
                }
                if (in.readableBytes() < length) {
                    in.resetReaderIndex();
                    return; // body not fully arrived yet
                }
                out.add(in.readRetainedSlice(length));
                return;
            }
        }
        throw new CorruptedFrameException("Packet length VarInt is longer than 5 bytes");
    }
}
