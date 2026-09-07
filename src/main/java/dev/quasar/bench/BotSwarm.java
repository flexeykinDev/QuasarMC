package dev.quasar.bench;

import dev.quasar.net.ByteBufs;
import dev.quasar.net.Protocol;
import dev.quasar.net.ProtocolState;
import dev.quasar.net.pipeline.CompressionCodec;
import dev.quasar.net.pipeline.VarIntFrameDecoder;
import dev.quasar.net.pipeline.VarIntFrameEncoder;
import dev.quasar.util.Log;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A synthetic client swarm, used to exercise the server without a Minecraft installation.
 *
 * <h2>What this does and does not prove</h2>
 * The bots speak the protocol using the same {@link Protocol} constants the server does. That makes
 * them a genuine test of framing, compression, the login and configuration state machines, chunk
 * streaming and — the point of the exercise — the region engine under concurrent load. It does
 * <em>not</em> prove compatibility with a real Minecraft client: if a packet ID here is wrong for
 * your version, both sides are wrong in the same way and the swarm still passes. Only a real client
 * can confirm the IDs.
 *
 * <p>Usage: {@code --host localhost --port 25565 --count 8 --spread 4096 --seconds 30}
 *
 * <p>{@code spread} is the distance in blocks that bots scatter to. Anything above roughly
 * {@code (viewDistance * 16 * 2) + 64} puts each bot outside every other bot's chunk disc, which is
 * what makes the region graph split into independent components.
 */
public final class BotSwarm {

    private static final class Bot extends SimpleChannelInboundHandler<ByteBuf> {

        private final String username;
        private final double targetX;
        private final double targetZ;
        private final CountDownLatch joined;
        private final AtomicInteger chunksReceived = new AtomicInteger();
        private final AtomicInteger blockUpdates = new AtomicInteger();
        private final AtomicInteger blockAcks = new AtomicInteger();
        private final AtomicInteger entitiesSpawned = new AtomicInteger();
        private final AtomicInteger entitiesRemoved = new AtomicInteger();
        private final AtomicInteger entityMoves = new AtomicInteger();

        private ProtocolState state = ProtocolState.LOGIN;
        private Channel channel;
        private volatile boolean inPlay;
        private int nextSequence = 1;
        private volatile int openWindowId = -1;
        private volatile int openMenuType = -1;

        /** Last position sent to the server; block edits must happen within reach of it. */
        private double lastX;
        private double lastY;
        private double lastZ;

        Bot(String username, double targetX, double targetZ, CountDownLatch joined) {
            this.username = username;
            this.targetX = targetX;
            this.targetZ = targetZ;
            this.joined = joined;
        }

        boolean isInPlay() {
            return inPlay;
        }

        int chunksReceived() {
            return chunksReceived.get();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            send(Protocol.HANDSHAKE_SERVERBOUND_INTENTION, buf -> {
                ByteBufs.writeVarInt(buf, Protocol.VERSION);
                ByteBufs.writeString(buf, "localhost");
                buf.writeShort(25565);
                ByteBufs.writeVarInt(buf, 2); // intent: login
            });
            send(Protocol.LOGIN_SERVERBOUND_START, buf -> {
                ByteBufs.writeString(buf, username);
                ByteBufs.writeUuid(buf, dev.quasar.net.listener.LoginListener.offlineUuid(username));
            });
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            int packetId = ByteBufs.readVarInt(msg);
            switch (state) {
                case LOGIN -> handleLogin(packetId, msg);
                case CONFIGURATION -> handleConfiguration(packetId, msg);
                case PLAY -> handlePlay(packetId, msg);
                default -> { }
            }
        }

        private void handleLogin(int packetId, ByteBuf data) {
            if (packetId == Protocol.LOGIN_CLIENTBOUND_SET_COMPRESSION) {
                int threshold = ByteBufs.readVarInt(data);
                // Same ordering rule as the server side: after frame-encoder, so outbound
                // compresses before the length prefix is prepended.
                channel.pipeline().addAfter("frame-encoder", "compression",
                        new CompressionCodec(threshold, 8 * 1024 * 1024));
            } else if (packetId == Protocol.LOGIN_CLIENTBOUND_SUCCESS) {
                state = ProtocolState.CONFIGURATION;
                send(Protocol.LOGIN_SERVERBOUND_ACKNOWLEDGED, buf -> { });
                send(Protocol.CONFIG_SERVERBOUND_CLIENT_INFORMATION, buf -> {
                    ByteBufs.writeString(buf, "en_us");
                    buf.writeByte(8);              // view distance
                    ByteBufs.writeVarInt(buf, 0);  // chat mode
                    buf.writeBoolean(true);        // chat colours
                    buf.writeByte(0x7F);           // skin parts
                    ByteBufs.writeVarInt(buf, 1);  // main hand
                    buf.writeBoolean(false);       // text filtering
                    buf.writeBoolean(true);        // server listings
                });
            } else if (packetId == Protocol.LOGIN_CLIENTBOUND_DISCONNECT) {
                Log.warn("[%s] rejected at login: %s", username, ByteBufs.readString(data));
                channel.close();
            }
        }

        private void handleConfiguration(int packetId, ByteBuf data) {
            if (packetId == Protocol.CONFIG_CLIENTBOUND_KNOWN_PACKS) {
                // Echo an empty set: we have no packs of our own.
                send(Protocol.CONFIG_SERVERBOUND_KNOWN_PACKS, buf -> ByteBufs.writeVarInt(buf, 0));
            } else if (packetId == Protocol.CONFIG_CLIENTBOUND_FINISH) {
                state = ProtocolState.PLAY;
                send(Protocol.CONFIG_SERVERBOUND_FINISH_ACK, buf -> { });
            } else if (packetId == Protocol.CONFIG_CLIENTBOUND_KEEP_ALIVE) {
                long id = data.readLong();
                send(Protocol.CONFIG_SERVERBOUND_KEEP_ALIVE, buf -> buf.writeLong(id));
            } else if (packetId == Protocol.CONFIG_CLIENTBOUND_DISCONNECT) {
                Log.warn("[%s] rejected during configuration", username);
                channel.close();
            }
        }

        private void handlePlay(int packetId, ByteBuf data) {
            if (packetId == Protocol.PLAY_CLIENTBOUND_KEEP_ALIVE) {
                long id = data.readLong();
                send(Protocol.PLAY_SERVERBOUND_KEEP_ALIVE, buf -> buf.writeLong(id));
            } else if (packetId == Protocol.PLAY_CLIENTBOUND_CHUNK_DATA) {
                chunksReceived.incrementAndGet();
            } else if (packetId == Protocol.PLAY_CLIENTBOUND_PLAYER_POSITION) {
                // Track where the server actually put us. Without this the bot has no idea where
                // spawn is, and any edit it attempts is measured against a position of 0,0,0.
                ByteBufs.readVarInt(data); // teleport id
                lastX = data.readDouble();
                lastY = data.readDouble();
                lastZ = data.readDouble();
                if (!inPlay) {
                    inPlay = true;
                    joined.countDown();
                    // Stay at spawn for now. The driver loop walks the swarm apart gradually, which
                    // is what exercises both halves of the region graph: everyone shares one region
                    // at the start, and it has to split as they separate.
                }
            } else if (packetId == Protocol.PLAY_CLIENTBOUND_BLOCK_UPDATE) {
                long packed = data.readLong();
                int state = ByteBufs.readVarInt(data);
                blockUpdates.incrementAndGet();
                Log.info("[%s] block_update %d,%d,%d -> state %d", username,
                        ByteBufs.blockPosX(packed), ByteBufs.blockPosY(packed),
                        ByteBufs.blockPosZ(packed), state);

            } else if (packetId == Protocol.PLAY_CLIENTBOUND_BLOCK_CHANGED_ACK) {
                int sequence = ByteBufs.readVarInt(data);
                blockAcks.incrementAndGet();
                Log.info("[%s] block_changed_ack seq %d", username, sequence);

            } else if (packetId == Protocol.PLAY_CLIENTBOUND_OPEN_SCREEN) {
                openWindowId = ByteBufs.readVarInt(data);
                openMenuType = ByteBufs.readVarInt(data);
                Log.info("[%s] container opened: window %d, menu type %d (%s)",
                        username, openWindowId, openMenuType,
                        openMenuType == 5 ? "9x6 large" : openMenuType == 2 ? "9x3" : "other");

            } else if (packetId == Protocol.PLAY_CLIENTBOUND_ADD_ENTITY) {
                int id = ByteBufs.readVarInt(data);
                ByteBufs.readUuid(data);
                int type = ByteBufs.readVarInt(data);
                double ex = data.readDouble();
                double ey = data.readDouble();
                double ez = data.readDouble();
                entitiesSpawned.incrementAndGet();
                Log.info("[%s] sees entity %d (type %d) at %.1f, %.1f, %.1f",
                        username, id, type, ex, ey, ez);

            } else if (packetId == Protocol.PLAY_CLIENTBOUND_MOVE_ENTITY_POS_ROT) {
                entityMoves.incrementAndGet();

            } else if (packetId == Protocol.PLAY_CLIENTBOUND_REMOVE_ENTITIES) {
                int count = ByteBufs.readVarInt(data);
                entitiesRemoved.addAndGet(count);
                Log.info("[%s] loses sight of %d entity/entities", username, count);

            } else if (packetId == Protocol.PLAY_CLIENTBOUND_DISCONNECT) {
                Log.warn("[%s] disconnected during play", username);
                channel.close();
            }
        }

        void moveTo(double x, double y, double z) {
            lastX = x;
            lastY = y;
            lastZ = z;
            send(Protocol.PLAY_SERVERBOUND_MOVE_POS, buf -> {
                buf.writeDouble(x);
                buf.writeDouble(y);
                buf.writeDouble(z);
                buf.writeByte(1); // on ground
            });
        }

        /**
         * Moves the bot {@code progress} of the way from spawn to its scatter target, with a small
         * orbit on top so the server keeps doing chunk-tracking work after the walk finishes.
         *
         * @param progress 0 at spawn, 1 fully scattered
         */
        void advance(double progress, long tick) {
            double angle = (tick % 360) * Math.PI / 180.0;
            moveTo(targetX * progress + Math.cos(angle) * 24, 80,
                    targetZ * progress + Math.sin(angle) * 24);
        }

        /**
         * Places a container, opens it, moves a stack in and closes it.
         *
         * <p>Driven by a step counter from the main loop rather than by timers, so each stage
         * happens in order with the server's replies in between.
         *
         * @param chestItem the container item to place
         */
        void chestStep(int step, int chestItem) {
            int blockX = (int) Math.floor(lastX);
            int blockZ = (int) Math.floor(lastZ);
            int groundY = (int) Math.floor(lastY) - 1;

            switch (step) {
                case 0 -> pickCreativeItem(chestItem);
                case 1 -> placeAgainstTopOf(blockX, groundY, blockZ);
                // A second container beside the first, so the pair should present one window.
                case 2 -> placeAgainstTopOf(blockX + 1, groundY, blockZ);
                // A full stack, so a left-drag has something to split.
                case 3 -> pickCreativeItem(1, 64);
                case 4 -> openAt(blockX, groundY + 1, blockZ);
                // The first hotbar slot sits after the container's own slots and the 27 main ones.
                case 5 -> containerClick(containerSlotCount() + 27, 0, 0);
                // Drag-paint the held stack across two container slots: start, sweep, end.
                case 6 -> containerClick(-999, 0, 5);
                case 7 -> containerClick(0, 1, 5);
                case 8 -> containerClick(1, 1, 5);
                case 9 -> containerClick(-999, 2, 5);
                case 10 -> send(Protocol.PLAY_SERVERBOUND_CONTAINER_CLOSE,
                        buf -> ByteBufs.writeVarInt(buf, Math.max(openWindowId, 0)));
                // Break the chest holding those stacks; the contents must come back, not vanish.
                case 11 -> send(Protocol.PLAY_SERVERBOUND_PLAYER_ACTION, buf -> {
                    ByteBufs.writeVarInt(buf, 0);
                    ByteBufs.writeBlockPos(buf, blockX, groundY + 1, blockZ);
                    buf.writeByte(1);
                    ByteBufs.writeVarInt(buf, nextSequence++);
                });
                default -> { }
            }
        }

        /** Slots the open container itself has, inferred from the menu type the server sent. */
        private int containerSlotCount() {
            return openMenuType == 5 ? 54 : 27;
        }

        private void placeAgainstTopOf(int x, int y, int z) {
            send(Protocol.PLAY_SERVERBOUND_USE_ITEM_ON, buf -> {
                ByteBufs.writeVarInt(buf, 0);
                ByteBufs.writeBlockPos(buf, x, y, z);
                ByteBufs.writeVarInt(buf, 1); // face: +Y
                buf.writeFloat(0.5f);
                buf.writeFloat(1.0f);
                buf.writeFloat(0.5f);
                buf.writeBoolean(false);
                buf.writeBoolean(false);
                ByteBufs.writeVarInt(buf, nextSequence++);
            });
        }

        /** Right-clicks a container, which should open it rather than build against it. */
        private void openAt(int x, int y, int z) {
            send(Protocol.PLAY_SERVERBOUND_USE_ITEM_ON, buf -> {
                ByteBufs.writeVarInt(buf, 0);
                ByteBufs.writeBlockPos(buf, x, y, z);
                ByteBufs.writeVarInt(buf, 1);
                buf.writeFloat(0.5f);
                buf.writeFloat(1.0f);
                buf.writeFloat(0.5f);
                buf.writeBoolean(false);
                buf.writeBoolean(false);
                ByteBufs.writeVarInt(buf, nextSequence++);
            });
        }

        private void containerClick(int slot, int button, int mode) {
            if (openWindowId < 0) {
                Log.warn("[%s] click with no window open", username);
                return;
            }
            send(Protocol.PLAY_SERVERBOUND_CONTAINER_CLICK, buf -> {
                ByteBufs.writeVarInt(buf, openWindowId);
                ByteBufs.writeVarInt(buf, 0);   // client state ID
                buf.writeShort(slot);
                buf.writeByte(button);
                ByteBufs.writeVarInt(buf, mode);
                ByteBufs.writeVarInt(buf, 0);   // no predicted slot changes
                ByteBufs.writeVarInt(buf, 0);   // empty carried stack
            });
        }

        /** Jumps straight to the scatter target, skipping the gradual walk. */
        void teleportToTarget() {
            moveTo(targetX, 80, targetZ);
        }

        /**
         * Places a block above the one under the bot, then breaks it again.
         *
         * <p>Exercises the whole edit path server-side — packet decode, reach and ownership checks,
         * the world write, the broadcast and the sequence acknowledgement — without needing a real
         * client. It cannot confirm the client <em>renders</em> the result; only a real client can.
         */
        /**
         * Puts an item into hotbar slot 0, the way a creative client reports picking one.
         *
         * <p>Lets the harness exercise arbitrary items, not just the starter kit the server hands
         * out — which is the whole point of the item table.
         */
        void pickCreativeItem(int itemId) {
            pickCreativeItem(itemId, 1);
        }

        void pickCreativeItem(int itemId, int count) {
            send(Protocol.PLAY_SERVERBOUND_SET_CREATIVE_MODE_SLOT, buf -> {
                buf.writeShort(36);              // first hotbar slot
                ByteBufs.writeVarInt(buf, count);
                ByteBufs.writeVarInt(buf, itemId);
                ByteBufs.writeVarInt(buf, 0);    // components to add
                ByteBufs.writeVarInt(buf, 0);    // components to remove
            });
            send(Protocol.PLAY_SERVERBOUND_SET_CARRIED_ITEM, buf -> buf.writeShort(0));
        }

        void editBlocks() {
            // Edit right where the bot stands, so the server's reach check passes.
            int blockX = (int) Math.floor(lastX);
            int blockZ = (int) Math.floor(lastZ);
            int groundY = (int) Math.floor(lastY) - 1;

            // Click the top face of the block underfoot, placing into the air just above it.
            send(Protocol.PLAY_SERVERBOUND_USE_ITEM_ON, buf -> {
                ByteBufs.writeVarInt(buf, 0);                          // main hand
                ByteBufs.writeBlockPos(buf, blockX, groundY, blockZ);
                ByteBufs.writeVarInt(buf, 1);                          // face: +Y
                buf.writeFloat(0.5f);
                buf.writeFloat(1.0f);
                buf.writeFloat(0.5f);
                buf.writeBoolean(false);                               // head inside block
                buf.writeBoolean(false);                               // world border hit
                ByteBufs.writeVarInt(buf, nextSequence++);
            });
            // Stack a second block on top of the first.
            send(Protocol.PLAY_SERVERBOUND_USE_ITEM_ON, buf -> {
                ByteBufs.writeVarInt(buf, 0);
                ByteBufs.writeBlockPos(buf, blockX, groundY + 1, blockZ);
                ByteBufs.writeVarInt(buf, 1);
                buf.writeFloat(0.5f);
                buf.writeFloat(1.0f);
                buf.writeFloat(0.5f);
                buf.writeBoolean(false);
                buf.writeBoolean(false);
                ByteBufs.writeVarInt(buf, nextSequence++);
            });
            // Middle-click the block just placed, the way a creative client asks for it.
            send(Protocol.PLAY_SERVERBOUND_PICK_ITEM_FROM_BLOCK, buf -> {
                ByteBufs.writeBlockPos(buf, blockX, groundY + 1, blockZ);
                buf.writeBoolean(false); // include block-entity data
            });
            // Break only the upper one, so a placed block survives. A place-then-break pair nets
            // out to no change, which makes it useless for testing that edits persist.
            send(Protocol.PLAY_SERVERBOUND_PLAYER_ACTION, buf -> {
                ByteBufs.writeVarInt(buf, 0);                          // status: started digging
                ByteBufs.writeBlockPos(buf, blockX, groundY + 2, blockZ);
                buf.writeByte(1);                                      // face
                ByteBufs.writeVarInt(buf, nextSequence++);
            });
        }

        private void send(int packetId, dev.quasar.net.PacketWriter body) {
            if (channel == null || !channel.isActive()) {
                return;
            }
            ByteBuf buf = channel.alloc().buffer();
            ByteBufs.writeVarInt(buf, packetId);
            body.write(buf);
            channel.writeAndFlush(buf, channel.voidPromise());
        }
    }

    public static void main(String[] args) throws Exception {
        String host = arg(args, "--host", "localhost");
        int port = Integer.parseInt(arg(args, "--port", "25565"));
        int count = Integer.parseInt(arg(args, "--count", "8"));
        int spread = Integer.parseInt(arg(args, "--spread", "4096"));
        int seconds = Integer.parseInt(arg(args, "--seconds", "30"));
        long walkMillis = Long.parseLong(arg(args, "--walk-seconds", "10")) * 1000L;
        boolean teleport = Boolean.parseBoolean(arg(args, "--teleport", "false"));
        // --stay keeps bots wherever the server spawned them, which is what you want when the point
        // is to hold the chunks around spawn loaded rather than to scatter across the world.
        boolean stay = Boolean.parseBoolean(arg(args, "--stay", "false"));
        // --item <id> makes bots pick that item from the creative menu before each edit.
        // -1 means "leave the starter kit alone"; 0 is a valid request for an empty hand.
        int creativeItem = Integer.parseInt(arg(args, "--item", "-1"));
        // --chest <itemId> runs the container sequence instead of the block-edit loop.
        int chestItem = Integer.parseInt(arg(args, "--chest", "0"));

        Log.info("Connecting %d bots to %s:%d, scattering over %d blocks (%s) for %ds",
                count, host, port, spread,
                teleport ? "instant" : "walking over " + walkMillis / 1000 + "s", seconds);

        EventLoopGroup group = new NioEventLoopGroup(Math.min(count, 8));
        List<Bot> bots = new ArrayList<>(count);
        CountDownLatch joined = new CountDownLatch(count);

        try {
            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * i / count;
                double targetX = Math.cos(angle) * spread;
                double targetZ = Math.sin(angle) * spread;
                Bot bot = new Bot("Bot" + i, targetX, targetZ, joined);
                bots.add(bot);

                new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast("frame-decoder", new VarIntFrameDecoder(2 * 1024 * 1024))
                                        .addLast("frame-encoder", new VarIntFrameEncoder())
                                        .addLast("bot", bot);
                            }
                        })
                        .connect(host, port)
                        .sync();
                Thread.sleep(50); // stagger, so joins do not all land in one safepoint
            }

            if (joined.await(30, TimeUnit.SECONDS)) {
                Log.info("All %d bots reached the play phase", count);
            } else {
                Log.warn("Only %d/%d bots joined within 30s", count - (int) joined.getCount(), count);
            }

            long tick = 0;
            long start = System.currentTimeMillis();
            long deadline = start + seconds * 1000L;
            if (teleport) {
                for (Bot bot : bots) {
                    bot.teleportToTarget();
                }
            }
            while (System.currentTimeMillis() < deadline) {
                double progress = teleport
                        ? 1.0
                        : Math.min(1.0, (System.currentTimeMillis() - start) / (double) (walkMillis));
                for (Bot bot : bots) {
                    if (bot.isInPlay()) {
                        if (chestItem > 0) {
                            // One step every half second, so each stage sees the previous reply.
                            if (tick % 10 == 0) {
                                bot.chestStep((int) (tick / 10), chestItem);
                            }
                            continue;
                        }
                        if (!stay) {
                            bot.advance(progress, tick);
                        }
                        // Once settled, exercise the block-edit path a few times per bot.
                        // Edits wait until the walk is done so they land at the destination —
                        // except in stay mode, where there is no walk to wait for.
                        if ((stay || progress >= 1.0) && tick % 40 == 0) {
                            if (creativeItem >= 0) {
                                bot.pickCreativeItem(creativeItem);
                            }
                            bot.editBlocks();
                        }
                    }
                }
                tick++;
                Thread.sleep(50);
            }

            int totalChunks = 0;
            int live = 0;
            for (Bot bot : bots) {
                totalChunks += bot.chunksReceived();
                if (bot.isInPlay()) {
                    live++;
                }
            }
            int spawned = 0;
            int removed = 0;
            int moves = 0;
            for (Bot bot : bots) {
                spawned += bot.entitiesSpawned.get();
                removed += bot.entitiesRemoved.get();
                moves += bot.entityMoves.get();
            }
            Log.info("Done: %d/%d bots still in play, %d chunk packets received in total",
                    live, count, totalChunks);
            Log.info("Entity tracking: %d spawn(s), %d move(s), %d removal(s)",
                    spawned, moves, removed);
        } finally {
            group.shutdownGracefully().await(5, TimeUnit.SECONDS);
        }
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
