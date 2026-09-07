package dev.quasar.entity;

import dev.quasar.QuasarServer;
import dev.quasar.engine.Region;
import dev.quasar.item.HotbarKit;
import dev.quasar.item.ItemRegistry;
import dev.quasar.item.ItemStack;
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
import dev.quasar.world.blockentity.ContainerIo;
import dev.quasar.world.blockentity.Containers;
import dev.quasar.world.blockentity.OpenContainer;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
     * The player's inventory: 46 slots in vanilla's layout, of which 9-35 are the main grid and
     * 36-44 the hotbar.
     *
     * <p>Seeded from the starter kit, kept current from {@code set_creative_mode_slot}, and now
     * genuinely authoritative — container clicks move stacks in and out of it.
     */
    private final ItemStack[] inventory = new ItemStack[HotbarKit.INVENTORY_SLOTS];

    /** The stack on the cursor while a container screen is open. */
    private ItemStack carried = ItemStack.EMPTY;

    /** The container screen this player has open, or {@code null}. */
    private OpenContainer openContainer;

    /** Window IDs cycle 1-99; 0 is reserved for the player's own inventory. */
    private int nextWindowId = 1;

    /**
     * Revision counter sent with every container update.
     *
     * <p>The client echoes the last one it saw on each click and uses it to tell whether its own
     * prediction is still in step. Sending a constant made every update look like the same
     * revision, which is what a stale client looks like from the other side.
     */
    private int containerStateId = 1;

    /** Drag-painting state: held while the button is down and slots are being swept. */
    private boolean dragging;

    /** 0 = left drag (split evenly), 1 = right drag (one each), 2 = middle drag (fill). */
    private int dragKind;

    private final IntOpenHashSet dragSlots = new IntOpenHashSet();

    public Player(QuasarServer server, Connection connection, String name, UUID uuid,
                  int viewDistance, double x, double y, double z) {
        super(uuid, x, y, z);
        this.server = server;
        this.connection = connection;
        this.name = name;
        this.viewDistance = viewDistance;
        java.util.Arrays.fill(inventory, ItemStack.EMPTY);
    }

    private int hotbarSlotIndex() {
        return HotbarKit.FIRST_HOTBAR_SLOT + heldSlot;
    }

    public ItemStack inventorySlot(int slot) {
        return slot >= 0 && slot < inventory.length ? inventory[slot] : ItemStack.EMPTY;
    }

    public void setInventorySlot(int slot, ItemStack stack) {
        if (slot >= 0 && slot < inventory.length) {
            inventory[slot] = stack == null ? ItemStack.EMPTY : stack;
        }
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
    public void setSlotItem(int inventorySlot, int itemId, int count) {
        setInventorySlot(inventorySlot, ItemStack.of(itemId, count));
        Log.trace("%s put %d x item %d (%s) in slot %d",
                name, count, itemId, ItemRegistry.nameFor(itemId), inventorySlot);
    }

    /**
     * The block the held item would place.
     *
     * @return a block state, or -1 when the hand is empty or holding something unplaceable
     */
    private int heldBlockState() {
        int state = ItemRegistry.blockStateForItem(inventory[hotbarSlotIndex()].itemId());
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
        updateEntityTracking(region);
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

    // ---------------------------------------------------------------------------- containers

    /**
     * Opens the container at a position, if there is one.
     *
     * @return true when a screen was opened, so the caller knows not to treat the click as a place
     */
    public boolean tryOpenContainer(Region region, int x, int y, int z) {
        if (!region.ownsChunk(x >> 4, z >> 4)) {
            return false;
        }
        BlockStateRegistry.State state = BlockStateRegistry.byId(server.world().getBlock(x, y, z));
        if (state == null) {
            return false;
        }
        Containers.Kind kind = Containers.forBlock(state.name());
        if (kind == null) {
            return false;
        }

        // A double chest is two blocks presenting one screen. Ordering the halves left-then-right
        // keeps the slot layout stable regardless of which half was clicked.
        int[][] blocks;
        int[] partnerOffset = BlockConnections.chestPartnerOffset(state);
        if (partnerOffset != null) {
            int[] self = {x, y, z};
            int[] partner = {x + partnerOffset[0], y, z + partnerOffset[1]};
            boolean selfIsLeft = "left".equals(state.properties().get("type"));
            blocks = selfIsLeft ? new int[][] {self, partner} : new int[][] {partner, self};
            kind = Containers.largeChest(kind);
        } else {
            blocks = new int[][] {{x, y, z}};
        }

        int slotsPerBlock = kind.slots() / blocks.length;
        ItemStack[] items = new ItemStack[kind.slots()];
        java.util.Arrays.fill(items, ItemStack.EMPTY);

        for (int i = 0; i < blocks.length; i++) {
            Nbt.NbtCompound entity = blockEntityFor(kind, blocks[i]);
            if (entity == null) {
                return false;
            }
            ItemStack[] slice = ContainerIo.readItems(entity, slotsPerBlock);
            System.arraycopy(slice, 0, items, i * slotsPerBlock, slotsPerBlock);
        }

        int windowId = nextWindowId;
        nextWindowId = nextWindowId % 99 + 1;
        Containers.Kind opened = kind;
        openContainer = new OpenContainer(windowId, opened, blocks, slotsPerBlock, items);

        connection.send(Protocol.PLAY_CLIENTBOUND_OPEN_SCREEN, buf -> {
            ByteBufs.writeVarInt(buf, windowId);
            ByteBufs.writeVarInt(buf, opened.menuType());
            Nbt.writeNetwork(buf, Nbt.compound().putString("text", opened.title()));
        });
        sendContainerContent();
        Log.debug("%s opened %s at %d,%d,%d (%d block(s), %d slots)",
                name, opened.blockEntityId(), x, y, z, blocks.length, opened.slots());
        return true;
    }

    /** Fetches a container's block entity, creating an empty one if the block has none yet. */
    private Nbt.NbtCompound blockEntityFor(Containers.Kind kind, int[] position) {
        Chunk chunk = server.world().chunkAt(position[0] >> 4, position[2] >> 4);
        if (chunk == null) {
            return null;
        }
        Nbt.NbtCompound entity = chunk.blockEntity(position[0] & 15, position[1], position[2] & 15);
        if (entity == null) {
            // A container placed before this server tracked block entities, or one vanilla wrote
            // without contents. Give it an empty one rather than refusing to open it.
            entity = ContainerIo.newBlockEntity(kind, position[0], position[1], position[2]);
            chunk.setBlockEntity(position[0] & 15, position[1], position[2] & 15, entity);
        }
        return entity;
    }

    /**
     * Sends the open window's full contents.
     *
     * <p>Sent after every click rather than sending per-slot deltas. A container click has a lot of
     * cases — split stacks, partial merges, shift-move across two areas — and any disagreement
     * between what the client predicted and what the server did leaves items visibly duplicated or
     * missing until something else resyncs. A full resync costs a few hundred bytes and makes that
     * class of bug impossible.
     */
    private void sendContainerContent() {
        OpenContainer container = openContainer;
        if (container == null) {
            return;
        }
        int revision = ++containerStateId;
        connection.send(Protocol.PLAY_CLIENTBOUND_CONTAINER_SET_CONTENT, buf -> {
            ByteBufs.writeVarInt(buf, container.windowId());
            ByteBufs.writeVarInt(buf, revision);
            ByteBufs.writeVarInt(buf, container.totalSlots());
            for (int slot = 0; slot < container.totalSlots(); slot++) {
                ItemStack stack = windowSlot(container, slot);
                ByteBufs.writeItemStack(buf, stack.itemId(), stack.count());
            }
            ByteBufs.writeItemStack(buf, carried.itemId(), carried.count());
        });
    }

    /**
     * Reads a window slot.
     *
     * <p>Slots past the container map onto the player's real inventory rather than onto a copy, so
     * the two can never disagree: 27 main slots (inventory 9-35) then the hotbar (36-44).
     */
    private ItemStack windowSlot(OpenContainer container, int slot) {
        if (slot < container.containerSlots()) {
            return container.get(slot);
        }
        int playerIndex = slot - container.containerSlots();
        if (playerIndex < 27) {
            return inventorySlot(9 + playerIndex);
        }
        return inventorySlot(HotbarKit.FIRST_HOTBAR_SLOT + (playerIndex - 27));
    }

    private void setWindowSlot(OpenContainer container, int slot, ItemStack stack) {
        if (slot < container.containerSlots()) {
            container.set(slot, stack);
            return;
        }
        int playerIndex = slot - container.containerSlots();
        if (playerIndex < 27) {
            setInventorySlot(9 + playerIndex, stack);
        } else {
            setInventorySlot(HotbarKit.FIRST_HOTBAR_SLOT + (playerIndex - 27), stack);
        }
    }

    /**
     * Applies a click in the open window.
     *
     * <p>Handles the two modes that account for essentially all use: pick up and place (mode 0) and
     * shift-move (mode 1). Other modes — number-key swaps, drag-painting, double-click gather — are
     * ignored and answered with a resync, so an unhandled click does nothing rather than doing
     * something wrong.
     */
    public void handleContainerClick(int windowId, int slot, int button, int mode) {
        OpenContainer container = openContainer;
        if (container == null || container.windowId() != windowId) {
            return;
        }

        // Drag start and end carry slot -999, so drags are dispatched before the range check.
        if (mode == 5) {
            clickDrag(container, slot, button);
            persistContainer(container);
            sendContainerContent();
            return;
        }

        if (slot == -999) {
            // Clicked outside the window: vanilla drops the stack as an item entity. There are no
            // item entities here, so the stack would simply vanish -- better to keep it on the
            // cursor than to silently destroy it.
            sendContainerContent();
            return;
        }
        if (slot < 0 || slot >= container.totalSlots()) {
            sendContainerContent();
            return;
        }

        switch (mode) {
            case 0 -> clickPickup(container, slot, button);
            case 1 -> clickQuickMove(container, slot);
            case 2 -> clickHotbarSwap(container, slot, button);
            case 6 -> clickCollectMatching(container);
            default -> { }
        }

        persistContainer(container);
        sendContainerContent();
    }

    /**
     * Drag-painting: hold a button and sweep across slots to spread the held stack over them.
     *
     * <p>Arrives as three separate packets — start, one per slot swept, then end — all with mode 5,
     * distinguished by the button field. Nothing is applied until the end, because the split
     * depends on how many slots were swept in total.
     */
    private void clickDrag(OpenContainer container, int slot, int button) {
        switch (button) {
            case 0, 4, 8 -> {
                dragging = true;
                dragKind = button / 4; // 0 left, 1 right, 2 middle
                dragSlots.clear();
            }
            case 1, 5, 9 -> {
                if (dragging && slot >= 0 && slot < container.totalSlots()) {
                    dragSlots.add(slot);
                }
            }
            case 2, 6, 10 -> {
                if (dragging) {
                    applyDrag(container);
                }
                dragging = false;
                dragSlots.clear();
            }
            default -> {
                dragging = false;
                dragSlots.clear();
            }
        }
    }

    private void applyDrag(OpenContainer container) {
        if (carried.isEmpty() || dragSlots.isEmpty()) {
            return;
        }
        // Only slots that can actually take the item count towards the split.
        java.util.List<Integer> targets = new ArrayList<>();
        for (int slot : dragSlots) {
            ItemStack existing = windowSlot(container, slot);
            if (existing.isEmpty() || (existing.stacksWith(carried) && existing.spaceLeft() > 0)) {
                targets.add(slot);
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        int perSlot = switch (dragKind) {
            case 0 -> carried.count() / targets.size(); // left: split as evenly as it divides
            case 1 -> 1;                                // right: one apiece
            default -> ItemStack.MAX_STACK;             // middle: fill, creative only
        };
        if (perSlot <= 0) {
            return;
        }

        int remaining = carried.count();
        for (int slot : targets) {
            if (dragKind != 2 && remaining <= 0) {
                break;
            }
            ItemStack existing = windowSlot(container, slot);
            int space = existing.isEmpty() ? ItemStack.MAX_STACK : existing.spaceLeft();
            int give = Math.min(perSlot, space);
            if (dragKind != 2) {
                give = Math.min(give, remaining);
            }
            if (give <= 0) {
                continue;
            }
            setWindowSlot(container, slot,
                    existing.isEmpty() ? carried.withCount(give) : existing.grow(give));
            remaining -= give;
        }
        // A middle drag copies from an inexhaustible cursor, as creative mode does.
        if (dragKind != 2) {
            carried = carried.withCount(remaining);
        }
    }

    /** Number keys 1-9: swap the clicked slot with that hotbar slot. */
    private void clickHotbarSwap(OpenContainer container, int slot, int hotbarIndex) {
        if (hotbarIndex < 0 || hotbarIndex > 8) {
            return;
        }
        int inventoryIndex = HotbarKit.FIRST_HOTBAR_SLOT + hotbarIndex;
        ItemStack inSlot = windowSlot(container, slot);
        ItemStack inHotbar = inventorySlot(inventoryIndex);
        setWindowSlot(container, slot, inHotbar);
        setInventorySlot(inventoryIndex, inSlot);
    }

    /** Double-click: gather matching stacks from the window onto the cursor. */
    private void clickCollectMatching(OpenContainer container) {
        if (carried.isEmpty() || carried.spaceLeft() <= 0) {
            return;
        }
        for (int slot = 0; slot < container.totalSlots() && carried.spaceLeft() > 0; slot++) {
            ItemStack existing = windowSlot(container, slot);
            if (!existing.stacksWith(carried)) {
                continue;
            }
            int taken = Math.min(existing.count(), carried.spaceLeft());
            carried = carried.grow(taken);
            setWindowSlot(container, slot, existing.shrink(taken));
        }
    }

    private void clickPickup(OpenContainer container, int slot, int button) {
        ItemStack inSlot = windowSlot(container, slot);
        boolean rightClick = button == 1;

        if (carried.isEmpty()) {
            if (inSlot.isEmpty()) {
                return;
            }
            if (rightClick) {
                int half = (inSlot.count() + 1) / 2; // odd counts favour the cursor, as in vanilla
                carried = inSlot.withCount(half);
                setWindowSlot(container, slot, inSlot.shrink(half));
            } else {
                carried = inSlot;
                setWindowSlot(container, slot, ItemStack.EMPTY);
            }
            return;
        }

        if (inSlot.isEmpty()) {
            int moved = rightClick ? 1 : carried.count();
            setWindowSlot(container, slot, carried.withCount(moved));
            carried = carried.shrink(moved);
            return;
        }

        if (inSlot.stacksWith(carried)) {
            int moved = Math.min(rightClick ? 1 : carried.count(), inSlot.spaceLeft());
            if (moved > 0) {
                setWindowSlot(container, slot, inSlot.grow(moved));
                carried = carried.shrink(moved);
            }
            return;
        }

        // Different items: a left click swaps them, a right click does nothing.
        if (!rightClick) {
            setWindowSlot(container, slot, carried);
            carried = inSlot;
        }
    }

    /** Shift-click: move the stack to the other half of the window. */
    private void clickQuickMove(OpenContainer container, int slot) {
        ItemStack moving = windowSlot(container, slot);
        if (moving.isEmpty()) {
            return;
        }
        boolean fromContainer = slot < container.containerSlots();
        int start = fromContainer ? container.containerSlots() : 0;
        int end = fromContainer ? container.totalSlots() : container.containerSlots();

        // Merge into matching stacks first, then fill empty slots, which is what vanilla does.
        for (int target = start; target < end && !moving.isEmpty(); target++) {
            ItemStack existing = windowSlot(container, target);
            if (existing.stacksWith(moving)) {
                int moved = Math.min(moving.count(), existing.spaceLeft());
                if (moved > 0) {
                    setWindowSlot(container, target, existing.grow(moved));
                    moving = moving.shrink(moved);
                }
            }
        }
        for (int target = start; target < end && !moving.isEmpty(); target++) {
            if (windowSlot(container, target).isEmpty()) {
                setWindowSlot(container, target, moving);
                moving = ItemStack.EMPTY;
            }
        }
        setWindowSlot(container, slot, moving);
    }

    /** Writes the container back into its block entity so the change survives a save. */
    private void persistContainer(OpenContainer container) {
        int[][] blocks = container.blocks();
        for (int i = 0; i < blocks.length; i++) {
            int[] position = blocks[i];
            Chunk chunk = server.world().chunkAt(position[0] >> 4, position[2] >> 4);
            if (chunk == null) {
                continue;
            }
            Nbt.NbtCompound entity =
                    chunk.blockEntity(position[0] & 15, position[1], position[2] & 15);
            if (entity == null) {
                continue;
            }
            // Each half of a double chest keeps its own block entity, which is what lets vanilla
            // read the two chests back independently.
            ContainerIo.writeItems(entity, container.sliceFor(i));
            chunk.setBlockEntity(position[0] & 15, position[1], position[2] & 15, entity);
        }
    }

    /** Closes any open container, keeping whatever was on the cursor. */
    public void closeContainer() {
        OpenContainer container = openContainer;
        if (container == null) {
            return;
        }
        persistContainer(container);
        openContainer = null;
        if (!carried.isEmpty()) {
            // Nowhere to drop it, so put it back rather than destroy it.
            giveOrDrop(carried);
            carried = ItemStack.EMPTY;
        }
        sendInventory();
    }

    /**
     * Puts a stack into the inventory, merging into matching stacks before taking empty slots.
     *
     * <p>Hotbar first, then the main grid, matching where vanilla puts a pickup. Anything that will
     * not fit is discarded, and says so: with no item entities there is nowhere else for it to go,
     * and losing it quietly is exactly the failure this method exists to prevent elsewhere.
     */
    private void giveOrDrop(ItemStack stack) {
        int[] order = new int[inventory.length - 9];
        int index = 0;
        for (int slot = HotbarKit.FIRST_HOTBAR_SLOT; slot < inventory.length; slot++) {
            order[index++] = slot;
        }
        for (int slot = 9; slot < HotbarKit.FIRST_HOTBAR_SLOT; slot++) {
            order[index++] = slot;
        }

        for (int slot : order) {
            if (stack.isEmpty()) {
                return;
            }
            ItemStack existing = inventory[slot];
            if (existing.stacksWith(stack)) {
                int moved = Math.min(stack.count(), existing.spaceLeft());
                if (moved > 0) {
                    setInventorySlot(slot, existing.grow(moved));
                    stack = stack.shrink(moved);
                }
            }
        }
        for (int slot : order) {
            if (stack.isEmpty()) {
                return;
            }
            if (inventory[slot].isEmpty()) {
                setInventorySlot(slot, stack);
                stack = ItemStack.EMPTY;
            }
        }
        if (!stack.isEmpty()) {
            Log.warn("%s has no room for %d x item %d; it is lost",
                    name, stack.count(), stack.itemId());
        }
    }

    // ------------------------------------------------------------------------ entity tracking

    /**
     * Beyond this many blocks another player stops being sent. Kept under the chunk view distance
     * so someone appears well before the terrain they are standing on runs out.
     */
    private static final double TRACK_RANGE_SQUARED = 96.0 * 96.0;

    /**
     * A move larger than this is sent as a despawn and respawn rather than a delta.
     * {@code move_entity_pos_rot} encodes movement as a short of 1/4096 blocks, which tops out at
     * eight blocks; anything further has to be re-seeded.
     */
    private static final double MAX_DELTA = 7.5;

    /** Entity IDs currently spawned on this client, and where each was last reported. */
    private final Int2ObjectOpenHashMap<double[]> trackedEntities = new Int2ObjectOpenHashMap<>();

    /**
     * Sends spawns, moves and despawns for the other players this one can see.
     *
     * <p>Only players in the same region are considered, and that is not a shortcut — it is exact.
     * Two players close enough to see each other have overlapping chunk discs, and overlapping
     * discs are always one region by construction. Players in different regions are at least two
     * view distances apart, well beyond {@link #TRACK_RANGE_SQUARED}. So this runs entirely on the
     * owning thread with no cross-region reads.
     */
    private void updateEntityTracking(Region region) {
        IntOpenHashSet visible = new IntOpenHashSet();

        for (Entity entity : region.entities()) {
            if (entity == this || entity.isRemoved() || !(entity instanceof Player other)) {
                continue;
            }
            double dx = other.x - x;
            double dy = other.y - y;
            double dz = other.z - z;
            if (dx * dx + dy * dy + dz * dz > TRACK_RANGE_SQUARED) {
                continue;
            }
            visible.add(other.entityId());

            double[] last = trackedEntities.get(other.entityId());
            if (last == null) {
                spawnEntity(other);
                trackedEntities.put(other.entityId(), new double[] {other.x, other.y, other.z});
            } else {
                sendEntityMove(other, last);
            }
        }

        // Anything tracked but no longer visible has to be taken off the client.
        if (!trackedEntities.isEmpty()) {
            var iterator = trackedEntities.keySet().intIterator();
            while (iterator.hasNext()) {
                int id = iterator.nextInt();
                if (!visible.contains(id)) {
                    despawnEntity(id);
                    iterator.remove();
                }
            }
        }
    }

    private void spawnEntity(Player other) {
        // The client will not render a player entity it has no profile for, so the tab-list entry
        // has to go first.
        connection.send(Protocol.PLAY_CLIENTBOUND_PLAYER_INFO_UPDATE, buf -> {
            buf.writeByte(0x01 | 0x08); // add_player | update_listed
            ByteBufs.writeVarInt(buf, 1);
            ByteBufs.writeUuid(buf, other.uuid());
            ByteBufs.writeString(buf, other.name());
            ByteBufs.writeVarInt(buf, 0);  // no signed profile properties, so no skin
            buf.writeBoolean(true);        // listed in the tab list
        });

        connection.send(Protocol.PLAY_CLIENTBOUND_ADD_ENTITY, buf -> {
            ByteBufs.writeVarInt(buf, other.entityId());
            ByteBufs.writeUuid(buf, other.uuid());
            ByteBufs.writeVarInt(buf, Protocol.ENTITY_TYPE_PLAYER);
            buf.writeDouble(other.x);
            buf.writeDouble(other.y);
            buf.writeDouble(other.z);
            ByteBufs.writeAngle(buf, other.pitch);
            ByteBufs.writeAngle(buf, other.yaw);
            ByteBufs.writeAngle(buf, other.yaw); // head yaw
            ByteBufs.writeVarInt(buf, 0);        // type-specific data
            buf.writeShort(0);
            buf.writeShort(0);
            buf.writeShort(0);
        });
        sendHeadRotation(other);
        Log.trace("%s now sees %s", name, other.name());
    }

    private void sendEntityMove(Player other, double[] last) {
        double dx = other.x - last[0];
        double dy = other.y - last[1];
        double dz = other.z - last[2];

        if (Math.abs(dx) > MAX_DELTA || Math.abs(dy) > MAX_DELTA || Math.abs(dz) > MAX_DELTA) {
            // Too far for a delta: re-seed rather than reach for the reworked teleport packet.
            despawnEntity(other.entityId());
            spawnEntity(other);
            last[0] = other.x;
            last[1] = other.y;
            last[2] = other.z;
            return;
        }
        if (dx == 0 && dy == 0 && dz == 0) {
            sendHeadRotation(other);
            return;
        }

        connection.send(Protocol.PLAY_CLIENTBOUND_MOVE_ENTITY_POS_ROT, buf -> {
            ByteBufs.writeVarInt(buf, other.entityId());
            buf.writeShort((int) (dx * 4096));
            buf.writeShort((int) (dy * 4096));
            buf.writeShort((int) (dz * 4096));
            ByteBufs.writeAngle(buf, other.yaw);
            ByteBufs.writeAngle(buf, other.pitch);
            buf.writeBoolean(other.onGround);
        });
        sendHeadRotation(other);

        last[0] = other.x;
        last[1] = other.y;
        last[2] = other.z;
    }

    /** Head yaw is separate from body yaw, and without it heads never turn. */
    private void sendHeadRotation(Player other) {
        connection.send(Protocol.PLAY_CLIENTBOUND_ROTATE_HEAD, buf -> {
            ByteBufs.writeVarInt(buf, other.entityId());
            ByteBufs.writeAngle(buf, other.yaw);
        });
    }

    /**
     * Drops someone from this client's tab list.
     *
     * <p>Separate from entity despawn on purpose: the entity comes and goes with range, but the tab
     * entry should last as long as they are online, or the list would flicker as people walk in and
     * out of view.
     */
    public void sendPlayerInfoRemove(java.util.UUID uuid) {
        connection.send(Protocol.PLAY_CLIENTBOUND_PLAYER_INFO_REMOVE, buf -> {
            ByteBufs.writeVarInt(buf, 1);
            ByteBufs.writeUuid(buf, uuid);
        });
    }

    private void despawnEntity(int entityId) {
        connection.send(Protocol.PLAY_CLIENTBOUND_REMOVE_ENTITIES, buf -> {
            ByteBufs.writeVarInt(buf, 1);
            ByteBufs.writeVarInt(buf, entityId);
        });
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
                salvageContainer(previous, x, y, z);
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
        int clickedX = ByteBufs.blockPosX(packedPos);
        int clickedY = ByteBufs.blockPosY(packedPos);
        int clickedZ = ByteBufs.blockPosZ(packedPos);

        // Right-clicking a container opens it instead of building against it.
        if (tryOpenContainer(region, clickedX, clickedY, clickedZ)) {
            sendBlockChangedAck(sequence);
            return;
        }

        int x = clickedX;
        int y = clickedY;
        int z = clickedZ;

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
                createBlockEntityIfNeeded(placed, x, y, z);
                refreshConnections(region, x, y, z);
                // Read back rather than logging `placed`: connection updates run after the write,
                // so the value set a moment ago is not necessarily what ended up there.
                Log.debug("%s placed block %d at %d,%d,%d (item state %d, face %d, slot %d, region #%d)",
                        name, server.world().getBlock(x, y, z), x, y, z, state, face, heldSlot,
                        region.id());
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

    /**
     * Rescues a container's contents before its block is removed.
     *
     * <p>Vanilla drops them on the ground as item entities. There are none here, so breaking a
     * chest simply deleted whatever was inside — silent data loss, and unrecoverable. Handing the
     * contents to whoever broke it is the closest non-destructive equivalent.
     *
     * <p>Also closes the screen if it was this container, so a click afterwards cannot write stale
     * contents back over a block that no longer exists.
     */
    private void salvageContainer(int previousState, int x, int y, int z) {
        BlockStateRegistry.State state = BlockStateRegistry.byId(previousState);
        if (state == null) {
            return;
        }
        Containers.Kind kind = Containers.forBlock(state.name());
        if (kind == null) {
            return;
        }
        if (openContainer != null) {
            for (int[] block : openContainer.blocks()) {
                if (block[0] == x && block[1] == y && block[2] == z) {
                    closeContainer();
                    break;
                }
            }
        }

        Chunk chunk = server.world().chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return;
        }
        Nbt.NbtCompound entity = chunk.blockEntity(x & 15, y, z & 15);
        if (entity == null) {
            return;
        }
        int rescued = 0;
        for (ItemStack stack : ContainerIo.readItems(entity, kind.slots())) {
            if (!stack.isEmpty()) {
                giveOrDrop(stack);
                rescued++;
            }
        }
        if (rescued > 0) {
            sendInventory();
            Log.debug("%s broke %s at %d,%d,%d; %d stack(s) went to their inventory",
                    name, state.name(), x, y, z, rescued);
        }
    }

    /**
     * Attaches an empty block entity when a container is placed.
     *
     * <p>Only containers. A chest with no block entity cannot hold anything and vanilla would treat
     * the file as damaged; other block-entity blocks this server does not model are left bare,
     * which is the existing behaviour and does not get worse by being explicit about it.
     */
    private void createBlockEntityIfNeeded(int state, int x, int y, int z) {
        BlockStateRegistry.State placed = BlockStateRegistry.byId(state);
        if (placed == null) {
            return;
        }
        Containers.Kind kind = Containers.forBlock(placed.name());
        if (kind == null) {
            return;
        }
        Chunk chunk = server.world().chunkAt(x >> 4, z >> 4);
        if (chunk != null) {
            chunk.setBlockEntity(x & 15, y, z & 15, ContainerIo.newBlockEntity(kind, x, y, z));
        }
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
        // Seed from the kit only for a player with nothing, so an inventory restored from disk is
        // not overwritten by the starter set every time they rejoin.
        boolean empty = true;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                empty = false;
                break;
            }
        }
        if (empty) {
            for (int i = 0; i < HotbarKit.size(); i++) {
                setInventorySlot(HotbarKit.FIRST_HOTBAR_SLOT + i,
                        ItemStack.of(HotbarKit.ENTRIES.get(i).itemId(), 64));
            }
        }
        sendInventory();
    }

    /** Pushes the whole player inventory as window 0. */
    public void sendInventory() {
        connection.send(Protocol.PLAY_CLIENTBOUND_CONTAINER_SET_CONTENT, buf -> {
            ByteBufs.writeVarInt(buf, 0); // window 0: the player inventory
            ByteBufs.writeVarInt(buf, 1); // state ID; nothing here tracks container revisions
            ByteBufs.writeVarInt(buf, inventory.length);
            for (ItemStack stack : inventory) {
                ByteBufs.writeItemStack(buf, stack.itemId(), stack.count());
            }
            ByteBufs.writeItemStack(buf, carried.itemId(), carried.count());
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

        int inventorySlot = hotbarSlotIndex();
        setInventorySlot(inventorySlot, ItemStack.of(itemId, 1));
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
                .put("QuasarInventory", inventoryToNbt())
                .putInt("QuasarHeldSlot", heldSlot);
    }

    /** Inventory as a sparse slot list, the same shape a container's {@code Items} uses. */
    private Nbt.NbtList inventoryToNbt() {
        Nbt.NbtList list = new Nbt.NbtList(Nbt.TAG_COMPOUND);
        for (int slot = 0; slot < inventory.length; slot++) {
            ItemStack stack = inventory[slot];
            if (stack.isEmpty()) {
                continue;
            }
            String itemName = ItemRegistry.nameFor(stack.itemId());
            if (itemName == null) {
                continue;
            }
            list.add(Nbt.compound()
                    .putByte("Slot", slot)
                    .putString("id", itemName)
                    .putInt("count", stack.count()));
        }
        return list;
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
        if (data.get("QuasarInventory") instanceof Nbt.NbtList stored) {
            for (Nbt element : stored.items()) {
                if (element instanceof Nbt.NbtCompound entry
                        && entry.get("Slot") instanceof Nbt.NbtByte slot
                        && entry.get("id") instanceof Nbt.NbtString itemName) {
                    int itemId = ItemRegistry.idForName(itemName.value());
                    int count = entry.get("count") instanceof Nbt.NbtInt c ? c.value() : 1;
                    if (itemId > 0) {
                        setInventorySlot(slot.value() & 0xFF, ItemStack.of(itemId, count));
                    }
                }
            }
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
