package dev.quasar.net;

import dev.quasar.QuasarServer;
import dev.quasar.net.listener.HandshakeListener;
import dev.quasar.net.pipeline.VarIntFrameDecoder;
import dev.quasar.net.pipeline.VarIntFrameEncoder;
import dev.quasar.util.Log;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The listening socket and per-connection pipeline.
 *
 * <p>Network threads are entirely separate from region tick threads. A packet arriving never blocks
 * a tick, and a slow tick never stalls the socket — the two only meet through region mailboxes.
 */
public final class NettyServer {

    /** Cap on a single inbound packet, before decompression. */
    private static final int MAX_PACKET_SIZE = 2 * 1024 * 1024;

    /** Drop a connection that has sent nothing for this long; catches half-open sockets. */
    private static final int READ_TIMEOUT_SECONDS = 30;

    private final QuasarServer server;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public NettyServer(QuasarServer server) {
        this.server = server;
    }

    public void bind(String host, int port) throws InterruptedException {
        boolean useEpoll = Epoll.isAvailable();
        bossGroup = newGroup(1, useEpoll, "quasar-net-boss");
        workerGroup = newGroup(0, useEpoll, "quasar-net-io");

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Connection connection = new Connection();
                        connection.setListener(new HandshakeListener(server, connection));
                        ch.pipeline()
                                .addLast("timeout", new ReadTimeoutHandler(READ_TIMEOUT_SECONDS))
                                .addLast("frame-decoder", new VarIntFrameDecoder(MAX_PACKET_SIZE))
                                .addLast("frame-encoder", new VarIntFrameEncoder())
                                .addLast("connection", connection);
                    }
                });

        channel = bootstrap.bind(new InetSocketAddress(host, port)).sync().channel();
        Log.info("Listening on %s:%d (%s transport)", host, port, useEpoll ? "epoll" : "nio");
    }

    /** @param threads 0 lets Netty pick its default (twice the core count) */
    private static EventLoopGroup newGroup(int threads, boolean useEpoll, String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return useEpoll ? new EpollEventLoopGroup(threads, factory) : new NioEventLoopGroup(threads, factory);
    }

    public void shutdown() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        Log.info("Network stopped");
    }
}
