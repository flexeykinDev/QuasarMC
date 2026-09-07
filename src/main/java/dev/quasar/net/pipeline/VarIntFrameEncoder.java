package dev.quasar.net.pipeline;

import dev.quasar.net.ByteBufs;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Prepends the VarInt length prefix to an already-encoded packet body. */
public class VarIntFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        out.ensureWritable(ByteBufs.varIntSize(length) + length);
        ByteBufs.writeVarInt(out, length);
        out.writeBytes(msg);
    }
}
