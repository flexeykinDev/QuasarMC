package dev.quasar.net.listener;

import dev.quasar.QuasarServer;
import dev.quasar.engine.Region;
import dev.quasar.entity.Player;
import dev.quasar.net.ByteBufs;
import dev.quasar.net.Connection;
import dev.quasar.net.PacketListener;
import dev.quasar.net.Protocol;
import dev.quasar.util.Log;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Play-phase packets.
 *
 * <p>Nothing here mutates the player directly. Every handler reads its fields off the network
 * thread and then hands a closure to {@link Player#submit}, which runs it on the owning region's
 * thread. That indirection is the whole reason the engine needs no locks around entity state.
 */
public final class PlayListener implements PacketListener {

    private final QuasarServer server;
    private final Connection connection;
    private final Player player;

    /**
     * Unhandled packet IDs already reported for this connection, so each is logged once rather
     * than once per packet. Touched only from this connection's event loop.
     */
    private final IntOpenHashSet loggedUnhandled = new IntOpenHashSet();

    public PlayListener(QuasarServer server, Connection connection, Player player) {
        this.server = server;
        this.connection = connection;
        this.player = player;
    }

    @Override
    public void handle(int packetId, ByteBuf data) {
        if (packetId == Protocol.PLAY_SERVERBOUND_KEEP_ALIVE) {
            long id = data.readLong();
            player.submit(() -> player.onKeepAlive(id));

        } else if (packetId == Protocol.PLAY_SERVERBOUND_MOVE_POS) {
            double x = data.readDouble();
            double y = data.readDouble();
            double z = data.readDouble();
            player.submit(() -> player.setPosition(x, y, z));

        } else if (packetId == Protocol.PLAY_SERVERBOUND_MOVE_POS_ROT) {
            double x = data.readDouble();
            double y = data.readDouble();
            double z = data.readDouble();
            float yaw = data.readFloat();
            float pitch = data.readFloat();
            player.submit(() -> {
                player.setPosition(x, y, z);
                player.setRotation(yaw, pitch);
            });

        } else if (packetId == Protocol.PLAY_SERVERBOUND_MOVE_ROT) {
            float yaw = data.readFloat();
            float pitch = data.readFloat();
            player.submit(() -> player.setRotation(yaw, pitch));

        } else if (packetId == Protocol.PLAY_SERVERBOUND_CONFIRM_TELEPORT) {
            // The client echoes the teleport ID from Synchronize Player Position. Nothing here
            // depends on it yet, but it must not be mistaken for an unknown packet.
            int teleportId = ByteBufs.readVarInt(data);
            Log.trace("%s confirmed teleport %d", player.name(), teleportId);

        } else if (packetId == Protocol.PLAY_SERVERBOUND_SET_CARRIED_ITEM) {
            int slot = data.readShort();
            player.submit(() -> player.setHeldSlot(slot));

        } else if (packetId == Protocol.PLAY_SERVERBOUND_PICK_ITEM_FROM_BLOCK) {
            long position = data.readLong();
            data.readBoolean(); // include block-entity data, which this server has none of
            player.submit(() -> {
                Region region = player.region();
                if (region != null) {
                    player.pickBlock(region, position);
                }
            });

        } else if (packetId == Protocol.PLAY_SERVERBOUND_SET_CREATIVE_MODE_SLOT) {
            // Slot, then an item stack. Only the leading count and item ID are needed; the
            // component data that follows is skipped by simply not reading it, since the buffer is
            // discarded after this call.
            int slot = data.readShort();
            int count = ByteBufs.readVarInt(data);
            int itemId = count > 0 ? ByteBufs.readVarInt(data) : 0;
            player.submit(() -> player.setSlotItem(slot, itemId, count));

        } else if (packetId == Protocol.PLAY_SERVERBOUND_CONTAINER_CLICK) {
            // Window, then the client's own state ID, the slot, the button and the mode. The
            // changed-slot array and cursor stack the client predicts are deliberately not read:
            // the server recomputes the result and resyncs, so its view always wins.
            int windowId = ByteBufs.readVarInt(data);
            ByteBufs.readVarInt(data); // client state ID
            int slot = data.readShort();
            int button = data.readByte();
            int mode = ByteBufs.readVarInt(data);
            player.submit(() -> player.handleContainerClick(windowId, slot, button, mode));

        } else if (packetId == Protocol.PLAY_SERVERBOUND_CONTAINER_CLOSE) {
            ByteBufs.readVarInt(data); // window ID
            player.submit(player::closeContainer);

        } else if (packetId == Protocol.PLAY_SERVERBOUND_PLAYER_ACTION) {
            int status = ByteBufs.readVarInt(data);
            long position = data.readLong();
            data.readByte(); // face — irrelevant when breaking
            int sequence = ByteBufs.readVarInt(data);
            // Status 0 is "started digging", 2 is "finished digging". In creative the client
            // expects an instant break and only ever sends 0, so both are treated as a break.
            if (status == 0 || status == 2) {
                player.submit(() -> {
                    Region region = player.region();
                    if (region != null) {
                        player.breakBlock(region, position, sequence);
                    }
                });
            } else if (status == 3 || status == 4) {
                // 3 is control-Q (the whole stack), 4 is a plain Q (one item).
                boolean wholeStack = status == 3;
                player.submit(() -> player.dropHeld(wholeStack));
            }

        } else if (packetId == Protocol.PLAY_SERVERBOUND_USE_ITEM_ON) {
            ByteBufs.readVarInt(data);      // hand
            long position = data.readLong();
            int face = ByteBufs.readVarInt(data);
            data.readFloat();               // cursor X within the face
            // Cursor Y decides whether a slab or stair lands in the top or bottom half.
            float cursorY = data.readFloat();
            data.readFloat();               // cursor Z
            data.readBoolean();             // head inside the block
            data.readBoolean();             // world border hit (1.21.3+)
            int sequence = ByteBufs.readVarInt(data);
            player.submit(() -> {
                Region region = player.region();
                if (region != null) {
                    player.placeBlock(region, position, face, cursorY, sequence);
                }
            });

        } else if (packetId == Protocol.PLAY_SERVERBOUND_SIGN_UPDATE) {
            long position = data.readLong();
            boolean front = data.readBoolean();
            String[] lines = new String[4];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = ByteBufs.readString(data, 384);
            }
            player.submit(() -> {
                Region region = player.region();
                if (region != null) {
                    player.updateSign(region, position, front, lines);
                }
            });

        } else if (packetId == Protocol.PLAY_SERVERBOUND_CHAT) {
            // Only the message text is used. The signature, salt and acknowledgement bitset that
            // follow are part of Mojang's chat-reporting chain, which this server does not
            // participate in — it sends everything back out as system messages instead.
            String message = ByteBufs.readString(data, 256);
            server.handleChat(player, message);

        } else if (loggedUnhandled.add(packetId)) {
            // Everything else — tick end, swings, abilities, inventory — is accepted and ignored.
            // Unknown packets must not be fatal: clients and mods send plenty this server has no
            // opinion about.
            //
            // Logged once per ID per connection, not once per packet. client_tick_end alone
            // arrives twenty times a second, and logging every occurrence buries the one line that
            // actually matters when retargeting a protocol version.
            Log.debug("Unhandled play packet 0x%02X (%d bytes) from %s — further occurrences muted",
                    packetId, data.readableBytes(), player.name());
        }
    }

    @Override
    public void onDisconnect() {
        server.removePlayer(player);
    }
}
