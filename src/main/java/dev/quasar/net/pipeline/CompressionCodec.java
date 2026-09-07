package dev.quasar.net.pipeline;

import dev.quasar.net.ByteBufs;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.DecoderException;

import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Packet compression, enabled once the server sends Set Compression.
 *
 * <p>Frame layout after this handler is installed: a VarInt "uncompressed size", then the body.
 * A size of 0 means the body is stored uncompressed because it fell under the threshold.
 *
 * <p>The {@link Deflater}/{@link Inflater} instances are per-connection and only ever touched from
 * that connection's event loop, so no synchronisation is needed — but they hold native memory, so
 * {@link #handlerRemoved} must end them.
 */
public class CompressionCodec extends ByteToMessageCodec<ByteBuf> {

    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final Inflater inflater = new Inflater();
    private final int threshold;
    private final int maxUncompressed;
    private byte[] scratch = new byte[8192];

    public CompressionCodec(int threshold, int maxUncompressed) {
        this.threshold = threshold;
        this.maxUncompressed = maxUncompressed;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int uncompressed = msg.readableBytes();
        if (uncompressed < threshold) {
            ByteBufs.writeVarInt(out, 0);
            out.writeBytes(msg);
            return;
        }

        byte[] input = new byte[uncompressed];
        msg.readBytes(input);
        ByteBufs.writeVarInt(out, uncompressed);

        deflater.setInput(input);
        deflater.finish();
        while (!deflater.finished()) {
            int written = deflater.deflate(scratch);
            out.writeBytes(scratch, 0, written);
        }
        deflater.reset();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }
        int uncompressed = ByteBufs.readVarInt(in);
        if (uncompressed == 0) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }
        if (uncompressed < threshold) {
            throw new DecoderException(
                    "Badly compressed packet: size " + uncompressed + " is below threshold " + threshold);
        }
        if (uncompressed > maxUncompressed) {
            throw new DecoderException(
                    "Badly compressed packet: size " + uncompressed + " exceeds limit " + maxUncompressed);
        }

        byte[] compressed = new byte[in.readableBytes()];
        in.readBytes(compressed);
        inflater.setInput(compressed);

        byte[] result = new byte[uncompressed];
        int total = 0;
        while (total < uncompressed && !inflater.finished()) {
            int read = inflater.inflate(result, total, uncompressed - total);
            if (read == 0 && inflater.needsInput()) {
                break;
            }
            total += read;
        }
        inflater.reset();
        if (total != uncompressed) {
            throw new DecoderException("Inflated " + total + " bytes but header claimed " + uncompressed);
        }
        out.add(ctx.alloc().buffer(uncompressed).writeBytes(result));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        deflater.end();
        inflater.end();
        scratch = null;
    }
}
