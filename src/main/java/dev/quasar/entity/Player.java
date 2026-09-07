package dev.quasar.entity;

import dev.quasar.QuasarServer;
import dev.quasar.engine.Region;
import dev.quasar.item.HotbarKit;
import dev.quasar.item.ItemRegistry;
import dev.quasar.nbt.Nbt;
import dev.quasar.net.ByteBufs;
import dev.quasar.net.Connection;
import dev.quasar.net.Protocol;
import dev.quasar.util.Log;
import dev.quasar.world.Chunk;
import dev.quasar.world.ChunkPos;
import dev.quasar.world.block.BlockConnections;
import dev.quasar.world.block.BlockPlacement;
import dev.quasar.world.block.BlockStateRegistry;
import dev.quasar.world.block.Blocks;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A connected player: the bridge between a Netty channel and a region tick.
 *
 * <h2>Threading</h2>
 * Packets arrive on the network thread and are pushed into {@link #inbound} as actions. They run at
 * the top of this player's next region tick, on the region thread, which is the only place player
 * and world state is touched. Outbound packets go the other way and can be sent from anywhere,
 * because Netty serialises writes itself.
 */
public final class Player extends Entity {

    /** Chunks streamed per tick. Higher fills the world in faster but makes joins spikier. */
    private static final int CHUNKS_PER_TICK = 8;

    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 15_000;
    private static final long KEEP_ALIVE_TIMEOUT_MILLIS = 30_000;

    private final QuasarServer server;
    private final Connection connection;
    private final String name;
    private final int viewDistance;

    private final Queue<Runnable> inbound = new ConcurrentLinkedQueue<>();

    /** Chunks the client has been sent and still has. Region thread only. */
    private final LongOpenHashSet sentChunks = new LongOpenHashSet();

    /** Chunks we hold a world ticket for. Region thread only. */
    private final LongOpenHashSet ticketedChunks = new LongOpenHashSet();

    private int lastCenterChunkX = Integer.MIN_VALUE;
    private int lastCenterChunkZ = Integer.MIN_VALUE;

    private long lastKeepAliveSentAt;
    private long pendingKeepAliveId;
    private boolean awaitingKeepAlive;
    private long lastKeepAliveResponseAt = System.currentTimeMillis();

    private int nextTeleportId = 1;

    /** Selected hotbar slot, 0-8. Decides what {@link #placeBlock} puts down. */
    private int heldSlot;

    /**
     * Item ID in each hotbar slot, as last reported by the client.
     *
     * <p>Seeded from the starter kit and then kept current from {@code set_creative_mode_slot}, so
     * anything picked out of the creative menu places the right block. 0 means empty.
     */
    private final int[] hotbarItems = new int[9];

    public Player(QuasarServer server, Connection connection, String name, UUID uuid,
                  int viewDistance, double x, double y, double z) {
        super(uuid, x, y, z);
        this.server = server;
        this.connection = connection;
        this.name = name;
        this.viewDistance = viewDistance;
    }

    public String name() {
        return name;
    }

    public Connection connection() {
        return connection;
    }

    public int viewDistance() {
        return viewDistance;
    }

    /** Queues an action to run on this player's region thread. Safe from any thread. */
    public void submit(Runnable action) {
        inbound.add(action);
    }

    public void setHeldSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            this.heldSlot = slot;
        }
    }

    public int heldSlot() {
        return heldSlot;
    }

    /**
     * Records what the client says is in an inventory slot.
     *
     * @param inventorySlot player-inventory index; only the hotbar (36-44) affects placement
     */
    public void setSlotItem(int inventorySlot, int itemId) {
        int hotbarIndex = inventorySlot - HotbarKit.FIRST_HOTBAR_SLOT;
        if (hotbarIndex >= 0 && hotbarIndex < hotbarItems.length) {
            hotbarItems[hotbarIndex] = itemId;
            Log.trace("%s put item %d (%s) in hotbar slot %d",
                    name, itemId, ItemRegistry.nameFor(itemId), hotbarIndex);
        }
    }

    /**
     * The block the held item would place.
     *
     * @return a block state, or -1 when the hand is empty or holding something unplaceable
     */
    private int heldBlockState() {
        int state = ItemRegistry.blockStateForItem(hotbarItems[heldSlot]);
        if (state >= 0) {
            return state;
        }
        // Without the item table the server cannot resolve arbitrary items, so the starter kit's
        // fixed slot mapping is still the best available answer.
        return ItemRegistry.isLoaded() ? -1 : HotbarKit.blockForSlot(heldSlot);
    }

    /** Whether this player's client currently holds the given chunk. Region thread only. */
    public boolean hasChunkLoaded(int chunkX, int chunkZ) {
        return sentChunks.contains(ChunkPos.key(chunkX, chunkZ));
    }

    // -------------------------------------------------------------------------------- ticking

    @Override
    public void tick(Region region) {
        drainInbound();
        if (!connection.isOpen()) {
            // Cleanup deliberately happens here rather than in the disconnect handler: the ticket
            // set is region-owned state, and the network thread must not touch it. Saving belongs
            // here for the same reason -- position is only consistent on the owning thread.
            server.savePlayer(this);
            int held = ticketedChunks.size();
            releaseTickets();
            region.removeEntity(this);
            Log.debug("%s cleaned up in region #%d, released %d chunk tickets", name, region.id(), held);
            return;
        }
        updateTrackedChunks();
        streamPendingChunks();
        tickKeepAlive();
    }

    private void drainInbound() {
        Runnable action;
        while ((action = inbound.poll()) != null) {
            try {
                action.run();
            } catch (Throwable t) {
                Log.error("Inbound action failed for " + name, t);
            }
        }
    }

    /**
     * Keeps the world tickets and the client's chunk cache in step with where the player is.
     *
     * <p>Only runs when the player crosses a chunk boundary — recomputing a 17×17 disc twenty times
     * a second for a stationary player is pure waste.
     */
    private void updateTrackedChunks() {
        int centerX = chunkX();
        int centerZ = chunkZ();
        if (centerX == lastCenterChunkX && centerZ == lastCenterChunkZ) {
            return;
        }
        lastCenterChunkX = centerX;
        lastCenterChunkZ = centerZ;

        connection.send(Protocol.PLAY_CLIENTBOUND_SET_CENTER_CHUNK, buf -> {
            ByteBufs.writeVarInt(buf, centerX);
            ByteBufs.writeVarInt(buf, centerZ);
        });

        LongOpenHashSet wanted = new LongOpenHashSet();
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                wanted.add(ChunkPos.key(centerX + dx, centerZ + dz));
            }
        }

        for (long key : wanted) {
            if (ticketedChunks.add(key)) {
                server.world().addTicket(ChunkPos.keyX(key), ChunkPos.keyZ(key));
            }
        }

        List<Long> released = new ArrayList<>();
        for (long key : ticketedChunks) {
            if (!wanted.contains(key)) {
                released.add(key);
            }
        }
        for (long key : released) {
            ticketedChunks.remove(key);
            sentChunks.remove(key);
            server.world().removeTicket(ChunkPos.keyX(key), ChunkPos.keyZ(key));
        }
    }

    /**
     * Sends a bounded number of ready chunks per tick, nearest first, so the ground under the
     * player appears before the horizon does.
     */
    private void streamPendingChunks() {
        int sent = 0;
        int centerX = chunkX();
        int centerZ = chunkZ();

        for (int ring = 0; ring <= viewDistance && sent < CHUNKS_PER_TICK; ring++) {
            for (int dx = -ring; dx <= ring && sent < CHUNKS_PER_TICK; dx++) {
                for (int dz = -ring; dz <= ring && sent < CHUNKS_PER_TICK; dz++) {
                    // Only the ring's perimeter; the interior was handled by earlier iterations.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cx = centerX + dx;
                    int cz = centerZ + dz;
                    long key = ChunkPos.key(cx, cz);
                    if (sentChunks.contains(key)) {
                        continue;
                    }
                    Chunk chunk = server.world().chunkAt(cx, cz);
                    if (chunk == null) {
                        continue; // still generating; a later tick will pick it up
                    }
                    sendChunk(chunk);
                    sentChunks.add(key);
                    sent++;
                }
            }
        }
    }

    private void sendChunk(Chunk chunk) {
        int biomeId = server.world().generator().biomeId();
        connection.send(Protocol.PLAY_CLIENTBOUND_CHUNK_DATA, buf -> {
            buf.writeInt(chunk.x());
            buf.writeInt(chunk.z());
            Nbt.writeNetwork(buf, chunk.heightmapNbt());

            // The sections blob is length-prefixed, so it is built first and measured.
            ByteBuf sections = buf.alloc().buffer();
            try {
                chunk.writeSections(sections, biomeId);
                ByteBufs.writeVarInt(buf, sections.readableBytes());
                buf.writeBytes(sections);
            } finally {
                sections.release();
            }

            ByteBufs.writeVarInt(buf, 0); // no block entities
            chunk.writeLight(buf);
        });
    }

    private void tickKeepAlive() {
        long now = System.currentTimeMillis();
        if (awaitingKeepAlive) {
            if (now - lastKeepAliveResponseAt > KEEP_ALIVE_TIMEOUT_MILLIS) {
                Log.info("%s timed out", name);
                disconnect("Timed out");
            }
            return;
        }
        if (now - lastKeepAliveSentAt >= KEEP_ALIVE_INTERVAL_MILLIS) {
            lastKeepAliveSentAt = now;
            pendingKeepAliveId = now;
            awaitingKeepAlive = true;
            connection.send(Protocol.PLAY_CLIENTBOUND_KEEP_ALIVE, buf -> buf.writeLong(pendingKeepAliveId));
        }
    }

    /** Called from the network thread when the client answers a keep-alive. */
    public void onKeepAlive(long id) {
        if (awaitingKeepAlive && id == pendingKeepAliveId) {
            awaitingKeepAlive = false;
            lastKeepAliveResponseAt = System.currentTimeMillis();
        }
    }

    // --------------------------------------------------------------------------- block editing

    /**
     * How far a player may reach to edit a block, squared.
     *
     * <p>Vanilla creative reach is about 5 blocks; the extra slack absorbs the difference between
     * the client's eye position and the feet position tracked here, plus latency. Without any
     * limit the packet is a remote world-edit primitive for anyone who can connect.
     */
    private static final double MAX_REACH_SQUARED = 8.0 * 8.0;

    /**
     * Breaks the block at a packed position.
     *
     * <p>Runs on the owning region's thread. It needs no locking and no cross-region messaging, and
     * that falls out of the engine's invariant: a player's view disc is always contained in one
     * region, so every player who could see this change is an entity of the same region this call
     * is already running on.
     */
    public void breakBlock(Region region, long packedPos, int sequence) {
        int x = ByteBufs.blockPosX(packedPos);
        int y = ByteBufs.blockPosY(packedPos);
        int z = ByteBufs.blockPosZ(packedPos);

        if (isEditAllowed(region, x, y, z)) {
            int previous = server.world().getBlock(x, y, z);
            if (!Blocks.isAir(previous)) {
                server.world().setBlock(x, y, z, Blocks.AIR);
                refreshConnections(region, x, y, z);
                Log.debug("%s broke block %d at %d,%d,%d (region #%d)",
                        name, previous, x, y, z, region.id());
            }
        }
        finishEdit(region, x, y, z, sequence);
    }

    /**
     * Places a block against the given face of an existing one.
     *
     * @param face 0=-Y 1=+Y 2=-Z 3=+Z 4=-X 5=+X, as the protocol numbers them
     */
    public void placeBlock(Region region, long packedPos, int face, float cursorY, int sequence) {
        int x = ByteBufs.blockPosX(packedPos);
        int y = ByteBufs.blockPosY(packedPos);
        int z = ByteBufs.blockPosZ(packedPos);

        switch (face) {
            case 0 -> y--;
            case 1 -> y++;
            case 2 -> z--;
            case 3 -> z++;
            case 4 -> x--;
            case 5 -> x++;
            default -> { }
        }

        // Only replace air. Without this, right-clicking a solid block would overwrite the block
        // you clicked rather than build outward from it.
        if (isEditAllowed(region, x, y, z)) {
            int existing = server.world().getBlock(x, y, z);
            int state = heldBlockState();
            if (state < 0) {
                // Empty hand, or an item with no block — a sword, food. Nothing to place, but the
                // acknowledgement below still has to go out.
                Log.trace("%s used a non-placeable item in slot %d", name, heldSlot);
            } else if (Blocks.isReplaceable(existing)) {
                int placed = BlockPlacement.stateFor(
                        state, face, cursorY, yaw, Blocks.isWater(existing));
                server.world().setBlock(x, y, z, placed);
                refreshConnections(region, x, y, z);
                Log.debug("%s placed block %d at %d,%d,%d (item state %d, face %d, slot %d, region #%d)",
                        name, placed, x, y, z, state, face, heldSlot, region.id());
            } else {
                // Previously silent, which made a refused placement indistinguishable from a
                // placement that never arrived — both just looked like the block flashing.
                Log.debug("%s could not place at %d,%d,%d: occupied by block %d",
                        name, x, y, z, existing);
            }
        }
        finishEdit(region, x, y, z, sequence);
    }

    /**
     * Closes out an edit: acknowledge it, then state the truth about that position.
     *
     * <p>The order is deliberate and was arrived at the hard way. The client places optimistically
     * and waits for its sequence number; on receiving the ack it retires the prediction and snaps
     * the position back to whatever server state it last knew. Sending the block update
     * <em>first</em> requires the client to correctly associate an in-flight update with a pending
     * prediction — and in practice it did not, so placements rolled back to air while breaks, which
     * the client predicts to air anyway, appeared to work. Acking first and then asserting the
     * authoritative state makes the outcome independent of that association.
     *
     * <p>The state is re-read rather than assumed, so a <em>rejected</em> edit also corrects the
     * client instead of leaving its prediction standing.
     */
    private void finishEdit(Region region, int x, int y, int z, int sequence) {
        sendBlockChangedAck(sequence);
        int authoritative = server.world().getBlock(x, y, z);
        broadcastBlockUpdate(region, x, y, z, authoritative);
    }

    /** Rejects edits out of reach, outside the build height, or in a chunk this region does not own. */
    private boolean isEditAllowed(Region region, int x, int y, int z) {
        if (y < server.world().minY() || y > server.world().maxY()) {
            return false;
        }
        double dx = (x + 0.5) - this.x;
        double dy = (y + 0.5) - this.y;
        double dz = (z + 0.5) - this.z;
        if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQUARED) {
            Log.debug("%s tried to edit %d,%d,%d out of reach", name, x, y, z);
            return false;
        }
        // The ownership check is what keeps the single-writer rule honest: a position outside this
        // region's chunks belongs to another thread and must not be touched from here.
        if (!region.ownsChunk(x >> 4, z >> 4)) {
            Log.debug("%s tried to edit %d,%d,%d outside region #%d", name, x, y, z, region.id());
            return false;
        }
        return true;
    }

    /**
     * Re-derives connection state for a changed position and its four horizontal neighbours.
     *
     * <p>Connections are mutual: placing a fence has to update the fence beside it too, and
     * breaking one has to make its neighbour let go. Only the horizontal ring matters — no family
     * handled here connects vertically.
     *
     * <p>Confined to chunks this region owns, so it cannot reach across into another thread's
     * state. At the edge of a region that means a connection is left stale rather than computed
     * unsafely.
     */
    private void refreshConnections(Region region, int x, int y, int z) {
        applyConnection(region, x, y, z);
        applyConnection(region, x - 1, y, z);
        applyConnection(region, x + 1, y, z);
        applyConnection(region, x, y, z - 1);
        applyConnection(region, x, y, z + 1);
    }

    private void applyConnection(Region region, int x, int y, int z) {
        if (!region.ownsChunk(x >> 4, z >> 4)) {
            return;
        }
        int current = server.world().getBlock(x, y, z);
        if (Blocks.isAir(current)) {
            return;
        }
        int connected = BlockConnections.updatedState(server.world()::getBlock, x, y, z, current);
        if (connected != current) {
            server.world().setBlock(x, y, z, connected);
            broadcastBlockUpdate(region, x, y, z, connected);
        }
    }

    private void broadcastBlockUpdate(Region region, int x, int y, int z, int state) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        for (Entity entity : region.entities()) {
            if (entity instanceof Player other
                    && !other.isRemoved()
                    && other.hasChunkLoaded(chunkX, chunkZ)) {
                other.sendBlockUpdate(x, y, z, state);
            }
        }
    }

    public void sendBlockUpdate(int x, int y, int z, int state) {
        connection.send(Protocol.PLAY_CLIENTBOUND_BLOCK_UPDATE, buf -> {
            ByteBufs.writeBlockPos(buf, x, y, z);
            ByteBufs.writeVarInt(buf, state);
        });
    }

    private void sendBlockChangedAck(int sequence) {
        connection.send(Protocol.PLAY_CLIENTBOUND_BLOCK_CHANGED_ACK,
                buf -> ByteBufs.writeVarInt(buf, sequence));
    }

    // ------------------------------------------------------------------------ outbound helpers

    /**
     * Fills the player's hotbar with the starter kit.
     *
     * <p>Sends the entire 46-slot player inventory in one Set Container Content on window 0, since
     * there is no per-slot state worth maintaining. Slots 36-44 are the hotbar; everything else is
     * sent empty.
     *
     * <p>This is what makes placing possible at all: a client holding nothing does not send
     * {@code use_item_on}, so right-click is inert no matter what the server would like to do.
     */
    public void sendStarterKit() {
        // Seed from the kit only for a player with nothing, so a hotbar restored from disk is not
        // overwritten by the starter set every time they rejoin.
        boolean empty = true;
        for (int item : hotbarItems) {
            if (item > 0) {
                empty = false;
                break;
            }
        }
        if (empty) {
            for (int i = 0; i < HotbarKit.size() && i < hotbarItems.length; i++) {
                hotbarItems[i] = HotbarKit.ENTRIES.get(i).itemId();
            }
        }

        connection.send(Protocol.PLAY_CLIENTBOUND_CONTAINER_SET_CONTENT, buf -> {
            ByteBufs.writeVarInt(buf, 0); // window 0: the player inventory
            ByteBufs.writeVarInt(buf, 1); // state ID; nothing here tracks container revisions
            ByteBufs.writeVarInt(buf, HotbarKit.INVENTORY_SLOTS);

            for (int slot = 0; slot < HotbarKit.INVENTORY_SLOTS; slot++) {
                int hotbarIndex = slot - HotbarKit.FIRST_HOTBAR_SLOT;
                if (hotbarIndex >= 0 && hotbarIndex < hotbarItems.length && hotbarItems[hotbarIndex] > 0) {
                    ByteBufs.writeItemStack(buf, hotbarItems[hotbarIndex], 64);
                } else {
                    ByteBufs.writeEmptyItemStack(buf);
                }
            }
            ByteBufs.writeEmptyItemStack(buf); // the cursor-carried stack
        });
    }

    /**
     * Middle-click: put the block being looked at into the held hotbar slot.
     *
     * <p>Looked up by block <em>name</em>, so any state works — picking east-facing stairs hands
     * you the stairs item rather than failing because only the default state was mapped.
     *
     * <p>Vanilla in creative would hunt for a slot that already holds the item and switch to it.
     * With no real inventory to search, overwriting the held slot is the behaviour that matches
     * what a creative player expects: middle-click, then place.
     */
    public void pickBlock(Region region, long packedPos) {
        int x = ByteBufs.blockPosX(packedPos);
        int y = ByteBufs.blockPosY(packedPos);
        int z = ByteBufs.blockPosZ(packedPos);
        if (!region.ownsChunk(x >> 4, z >> 4)) {
            return;
        }

        int state = server.world().getBlock(x, y, z);
        BlockStateRegistry.State known = BlockStateRegistry.byId(state);
        if (known == null || Blocks.isAir(state)) {
            return;
        }
        int itemId = ItemRegistry.itemForBlockName(known.name());
        if (itemId < 0) {
            Log.debug("%s picked %s, which has no item", name, known.name());
            return;
        }

        hotbarItems[heldSlot] = itemId;
        int inventorySlot = HotbarKit.FIRST_HOTBAR_SLOT + heldSlot;
        connection.send(Protocol.PLAY_CLIENTBOUND_CONTAINER_SET_SLOT, buf -> {
            ByteBufs.writeVarInt(buf, 0); // window 0: the player inventory
            ByteBufs.writeVarInt(buf, 1); // state ID; nothing here tracks container revisions
            buf.writeShort(inventorySlot);
            ByteBufs.writeItemStack(buf, itemId, 1);
        });
        Log.debug("%s picked %s (item %d) into slot %d", name, known.name(), itemId, heldSlot);
    }

    /** Sends the packets that put the client into the world. */
    public void sendJoinSequence() {
        int entityId = entityId();
        connection.send(Protocol.PLAY_CLIENTBOUND_LOGIN, buf -> {
            buf.writeInt(entityId);
            buf.writeBoolean(false);                       // hardcore
            ByteBufs.writeVarInt(buf, 1);                  // dimension count
            ByteBufs.writeString(buf, "minecraft:overworld");
            ByteBufs.writeVarInt(buf, server.config().maxPlayers);
            ByteBufs.writeVarInt(buf, viewDistance);
            ByteBufs.writeVarInt(buf, viewDistance);       // simulation distance
            buf.writeBoolean(false);                       // reduced debug info
            buf.writeBoolean(true);                        // enable respawn screen
            buf.writeBoolean(false);                       // limited crafting
            ByteBufs.writeVarInt(buf, 0);                  // dimension type: index into the registry
            ByteBufs.writeString(buf, "minecraft:overworld");
            buf.writeLong(server.world().seed());          // hashed seed
            buf.writeByte(1);                              // game mode: creative
            buf.writeByte(-1);                             // previous game mode: none
            buf.writeBoolean(false);                       // is debug world
            buf.writeBoolean(server.isFlatWorld());        // is flat world
            buf.writeBoolean(false);                       // no death location
            ByteBufs.writeVarInt(buf, 0);                  // portal cooldown
            ByteBufs.writeVarInt(buf, server.config().seaLevel);
            buf.writeBoolean(false);                       // enforces secure chat
        });

        connection.send(Protocol.PLAY_CLIENTBOUND_SET_DEFAULT_SPAWN, buf -> {
            ByteBufs.writeBlockPos(buf, (int) x, (int) y, (int) z);
            buf.writeFloat(0.0f);
        });

        // Tells the client to show the loading terrain screen until chunks arrive.
        connection.send(Protocol.PLAY_CLIENTBOUND_GAME_EVENT, buf -> {
            buf.writeByte(13);
            buf.writeFloat(0.0f);
        });

        sendPositionSync();
    }

    /** Synchronize Player Position, in the 1.21.2+ layout (teleport ID first, velocity included). */
    public void sendPositionSync() {
        int teleportId = nextTeleportId++;
        connection.send(Protocol.PLAY_CLIENTBOUND_PLAYER_POSITION, buf -> {
            ByteBufs.writeVarInt(buf, teleportId);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeDouble(0.0); // velocity X
            buf.writeDouble(0.0); // velocity Y
            buf.writeDouble(0.0); // velocity Z
            buf.writeFloat(yaw);
            buf.writeFloat(pitch);
            buf.writeInt(0);      // relative-movement flags: all absolute
        });
    }

    public void sendSystemMessage(String text) {
        connection.send(Protocol.PLAY_CLIENTBOUND_SYSTEM_CHAT, buf -> {
            Nbt.writeNetwork(buf, Nbt.compound().putString("text", text));
            buf.writeBoolean(false); // not an action-bar overlay
        });
    }

    public void disconnect(String reason) {
        connection.sendAndClose(Protocol.PLAY_CLIENTBOUND_DISCONNECT,
                buf -> Nbt.writeNetwork(buf, Nbt.compound().putString("text", reason)));
    }

    /**
     * Snapshots this player for disk.
     *
     * <p>Built on the region thread that owns the player, so the values are consistent; only the
     * finished tree crosses to the IO thread.
     *
     * <p>Position and rotation use vanilla's field names and shapes. The hotbar is this server's
     * own idea and goes under a namespaced key rather than vanilla's {@code Inventory}, which has a
     * different structure and would be misread.
     */
    public Nbt.NbtCompound toNbt() {
        Nbt.NbtList position = new Nbt.NbtList();
        position.add(new Nbt.NbtDouble(x));
        position.add(new Nbt.NbtDouble(y));
        position.add(new Nbt.NbtDouble(z));

        Nbt.NbtList rotation = new Nbt.NbtList();
        rotation.add(new Nbt.NbtFloat(yaw));
        rotation.add(new Nbt.NbtFloat(pitch));

        return Nbt.compound()
                .put("Pos", position)
                .put("Rotation", rotation)
                .putString("Dimension", "minecraft:overworld")
                .putInt("playerGameType", 1)
                .put("QuasarHotbar", new Nbt.NbtIntArray(hotbarItems.clone()))
                .putInt("QuasarHeldSlot", heldSlot);
    }

    /** Restores position, rotation and hotbar from a previously saved snapshot. */
    public void loadFromNbt(Nbt.NbtCompound data) {
        if (data.get("Pos") instanceof Nbt.NbtList position && position.size() == 3
                && position.items().get(0) instanceof Nbt.NbtDouble px
                && position.items().get(1) instanceof Nbt.NbtDouble py
                && position.items().get(2) instanceof Nbt.NbtDouble pz) {
            setPosition(px.value(), py.value(), pz.value());
        }
        if (data.get("Rotation") instanceof Nbt.NbtList rotation && rotation.size() == 2
                && rotation.items().get(0) instanceof Nbt.NbtFloat ry
                && rotation.items().get(1) instanceof Nbt.NbtFloat rp) {
            setRotation(ry.value(), rp.value());
        }
        if (data.get("QuasarHotbar") instanceof Nbt.NbtIntArray hotbar) {
            int[] stored = hotbar.value();
            System.arraycopy(stored, 0, hotbarItems, 0, Math.min(stored.length, hotbarItems.length));
        }
        if (data.get("QuasarHeldSlot") instanceof Nbt.NbtInt slot) {
            setHeldSlot(slot.value());
        }
    }

    /** Releases every world ticket this player held. Called when they leave. */
    public void releaseTickets() {
        for (long key : ticketedChunks) {
            server.world().removeTicket(ChunkPos.keyX(key), ChunkPos.keyZ(key));
        }
        ticketedChunks.clear();
        sentChunks.clear();
    }

    @Override
    public String toString() {
        return name + "@" + String.format("%.1f, %.1f, %.1f", x, y, z);
    }
}
