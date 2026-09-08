package dev.quasar.engine;

import dev.quasar.entity.Entity;
import dev.quasar.util.Log;
import dev.quasar.world.ChunkPos;
import dev.quasar.world.World;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains the partition of loaded chunks into {@link Region}s.
 *
 * <h2>The partition rule</h2>
 * Two loaded chunks within {@link #LINK_RADIUS} of each other are always in the same region, and
 * regions are the connected components of that relation. The radius is what makes the single-writer
 * rule safe: nothing in a tick — block updates, explosions, entity movement, block-entity access —
 * reaches further than {@code LINK_RADIUS} chunks, so a region tick can never need state another
 * region owns.
 *
 * <p>Every mutation here happens at a global safepoint, arranged by {@link RegionScheduler}, with
 * no region ticking. Callers on tick threads therefore <em>request</em> changes, which queue up and
 * are applied in a batch.
 */
public final class RegionManager {

    /**
     * Chebyshev radius, in chunks, that binds chunks into the same region.
     *
     * <p>2 covers the one-chunk reach of vanilla block and entity interactions plus a chunk of
     * slack. Raising it makes regions merge more eagerly (less parallelism, more headroom for
     * far-reaching custom mechanics); lowering it below 2 is not safe.
     */
    public static final int LINK_RADIUS = 2;

    /** Safety valve on one safepoint's work; see the drain loop for why it is needed. */
    private static final int MAX_CHANGES_PER_SAFEPOINT = 250_000;

    private sealed interface Change {
        record AddChunk(long key) implements Change {}
        record RemoveChunk(long key) implements Change {}
        record AddEntity(Entity entity) implements Change {}
        record RemoveEntity(Entity entity) implements Change {}
    }

    private final World world;
    private final long seed;

    private final Long2ObjectOpenHashMap<Region> chunkToRegion = new Long2ObjectOpenHashMap<>();
    private final List<Region> regions = new ArrayList<>();
    private final Queue<Change> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextRegionId = new AtomicInteger(1);

    /** Snapshot for lock-free reads by the dispatcher and by status commands. */
    private volatile List<Region> regionsView = List.of();

    private long merges;
    private long splits;

    public RegionManager(World world, long seed) {
        this.world = world;
        this.seed = seed;
    }

    // ------------------------------------------------------------------------------- requests

    public void requestChunkAdd(int chunkX, int chunkZ) {
        pending.add(new Change.AddChunk(ChunkPos.key(chunkX, chunkZ)));
    }

    public void requestChunkRemove(int chunkX, int chunkZ) {
        pending.add(new Change.RemoveChunk(ChunkPos.key(chunkX, chunkZ)));
    }

    public void requestEntityAdd(Entity entity) {
        pending.add(new Change.AddEntity(entity));
    }

    public void requestEntityRemove(Entity entity) {
        pending.add(new Change.RemoveEntity(entity));
    }

    void onEntityRemoved(Entity entity) {
        pending.add(new Change.RemoveEntity(entity));
    }

    public boolean hasPendingWork() {
        return !pending.isEmpty();
    }

    // ------------------------------------------------------------------------------- queries

    /** Immutable snapshot; safe to read from any thread. */
    public List<Region> regions() {
        return regionsView;
    }

    public int regionCount() {
        return regionsView.size();
    }

    public long merges() {
        return merges;
    }

    public long splits() {
        return splits;
    }

    /**
     * The region owning a chunk, or {@code null} if it is not loaded. Racy off-safepoint; use it
     * from a tick only for chunks the ticking region already owns.
     */
    public Region regionForChunk(int chunkX, int chunkZ) {
        synchronized (chunkToRegion) {
            return chunkToRegion.get(ChunkPos.key(chunkX, chunkZ));
        }
    }

    // ------------------------------------------------------------------------------ safepoint

    /**
     * Applies every queued structural change. Must be called with no region ticking.
     *
     * @return the number of changes applied
     */
    /**
     * Applies queued structural changes. Test-only.
     *
     * <p>Normally the scheduler calls this from inside a real safepoint. A test has no scheduler,
     * so it marks a safepoint itself and drives the partition directly.
     */
    public int applyPendingAtSafepointForTesting() {
        return applyPendingAtSafepoint();
    }

    int applyPendingAtSafepoint() {
        int applied = 0;
        // Held as region references, not IDs: a candidate can be merged away later in this same
        // batch, and we still need to check whoever inherited its chunks.
        Set<Region> splitCandidates = Collections.newSetFromMap(new IdentityHashMap<>());
        Change change;
        while ((change = pending.poll()) != null) {
            // Some changes legitimately enqueue follow-up work (an entity whose chunk is missing
            // asks for it and retries). Bound the drain so a pathological ping-pong degrades into
            // a warning and a deferred batch rather than a hung dispatcher and a frozen server.
            if (applied >= MAX_CHANGES_PER_SAFEPOINT) {
                Log.warn("Safepoint hit the %d-change cap; deferring the rest to the next one",
                        MAX_CHANGES_PER_SAFEPOINT);
                break;
            }
            applied++;
            switch (change) {
                case Change.AddChunk c -> addChunk(c.key());
                case Change.RemoveChunk c -> {
                    Region affected = removeChunk(c.key());
                    if (affected != null) {
                        splitCandidates.add(affected);
                    }
                }
                case Change.AddEntity c -> addEntity(c.entity());
                case Change.RemoveEntity c -> removeEntity(c.entity());
            }
        }

        if (!splitCandidates.isEmpty()) {
            Set<Region> toCheck = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Region candidate : splitCandidates) {
                Region current = resolveLiveOwner(candidate);
                if (current != null) {
                    toCheck.add(current);
                }
            }
            for (Region region : toCheck) {
                splitIfDisconnected(region);
            }
        }

        if (applied > 0) {
            regions.removeIf(r -> r.state() == Region.State.DEAD);
            regionsView = List.copyOf(regions);
        }
        return applied;
    }

    /**
     * Follows a region forward through any merges it was absorbed by.
     *
     * @return the live region now holding its chunks, or {@code null} if it simply emptied out
     */
    private Region resolveLiveOwner(Region region) {
        Region current = region;
        // The chain is at most as long as the merges in one batch, but guard anyway: a cycle here
        // would hang the dispatcher and take the whole server with it.
        for (int hops = 0; hops < 1000 && current != null; hops++) {
            if (current.state() != Region.State.DEAD) {
                return current;
            }
            current = current.absorbedInto;
        }
        return null;
    }

    private void addChunk(long key) {
        synchronized (chunkToRegion) {
            if (chunkToRegion.containsKey(key)) {
                return;
            }
        }
        int cx = ChunkPos.keyX(key);
        int cz = ChunkPos.keyZ(key);

        List<Region> neighbours = new ArrayList<>(4);
        synchronized (chunkToRegion) {
            for (int dx = -LINK_RADIUS; dx <= LINK_RADIUS; dx++) {
                for (int dz = -LINK_RADIUS; dz <= LINK_RADIUS; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Region r = chunkToRegion.get(ChunkPos.key(cx + dx, cz + dz));
                    if (r != null && !neighbours.contains(r)) {
                        neighbours.add(r);
                    }
                }
            }
        }

        Region target;
        if (neighbours.isEmpty()) {
            target = new Region(nextRegionId.getAndIncrement(), world, this, seed);
            regions.add(target);
            Log.debug("Region#%d created at chunk %s", target.id(), ChunkPos.fromKey(key));
        } else {
            // Absorb into the biggest neighbour so the expensive side of a merge stays small.
            neighbours.sort(Comparator.comparingInt(Region::chunkCount).reversed());
            target = neighbours.get(0);
            for (int i = 1; i < neighbours.size(); i++) {
                merge(target, neighbours.get(i));
            }
        }

        target.addChunkAtSafepoint(key);
        synchronized (chunkToRegion) {
            chunkToRegion.put(key, target);
        }
    }

    private void merge(Region target, Region victim) {
        if (target == victim || victim.state() == Region.State.DEAD) {
            return;
        }
        synchronized (chunkToRegion) {
            for (long chunkKey : victim.chunkKeys()) {
                chunkToRegion.put(chunkKey, target);
            }
        }
        target.absorbAtSafepoint(victim);
        merges++;
        Log.debug("Region#%d absorbed Region#%d (now %d chunks)", target.id(), victim.id(), target.chunkCount());
    }

    private Region removeChunk(long key) {
        Region region;
        synchronized (chunkToRegion) {
            region = chunkToRegion.remove(key);
        }
        if (region == null) {
            return null;
        }
        region.removeChunkAtSafepoint(key);
        if (region.chunkCount() == 0) {
            // A region with no chunks left is retired — but anything standing in it must be
            // re-homed first. A player moving faster than chunks generate can empty their entire
            // old disc in one batch before the new one is adopted; without this they would be
            // dropped into a dead region, stop ticking, and never be seen again.
            rehomeEntities(region);
            region.state.set(Region.State.DEAD);
            return null;
        }
        return region;
    }

    /**
     * Detaches every entity from {@code region} and queues it for re-assignment.
     *
     * <p>Re-uses the normal add path, which loads the entity's current chunk if it is missing and
     * retries, so the entity lands in whichever region ends up owning where it actually stands.
     */
    private void rehomeEntities(Region region) {
        if (region.entityCount() == 0) {
            return;
        }
        List<Entity> stranded = new ArrayList<>(region.entities());
        for (Entity entity : stranded) {
            region.removeEntityAtSafepoint(entity);
            entity.setRegion(null);
            pending.add(new Change.AddEntity(entity));
        }
        Log.debug("Re-homing %d entity/entities from retiring Region#%d", stranded.size(), region.id());
    }

    /**
     * Re-derives connected components for a region that just lost a chunk, and peels off any piece
     * that is no longer reachable. Splitting is what recovers parallelism after players separate.
     */
    private void splitIfDisconnected(Region region) {
        LongOpenHashSet remaining = new LongOpenHashSet(region.chunkKeys());
        if (remaining.size() <= 1) {
            return;
        }

        List<LongOpenHashSet> components = new ArrayList<>(2);
        while (!remaining.isEmpty()) {
            long start = remaining.iterator().nextLong();
            LongOpenHashSet component = new LongOpenHashSet();
            LongArrayList frontier = new LongArrayList();
            frontier.add(start);
            remaining.remove(start);
            component.add(start);

            while (!frontier.isEmpty()) {
                long current = frontier.removeLong(frontier.size() - 1);
                int cx = ChunkPos.keyX(current);
                int cz = ChunkPos.keyZ(current);
                for (int dx = -LINK_RADIUS; dx <= LINK_RADIUS; dx++) {
                    for (int dz = -LINK_RADIUS; dz <= LINK_RADIUS; dz++) {
                        long neighbour = ChunkPos.key(cx + dx, cz + dz);
                        if (remaining.remove(neighbour)) {
                            component.add(neighbour);
                            frontier.add(neighbour);
                        }
                    }
                }
            }
            components.add(component);
        }

        Log.trace("Split check: Region#%d (%d chunks) -> %d component(s)",
                region.id(), region.chunkCount(), components.size());
        if (components.size() <= 1) {
            return;
        }
        Log.debug("Region#%d (%d chunks) fell into %d components",
                region.id(), region.chunkCount(), components.size());

        // Largest component keeps the original region; the rest become new ones.
        components.sort(Comparator.comparingInt(LongOpenHashSet::size).reversed());
        for (int i = 1; i < components.size(); i++) {
            LongOpenHashSet component = components.get(i);
            Region fresh = new Region(nextRegionId.getAndIncrement(), world, this, seed);
            regions.add(fresh);

            for (long chunkKey : component) {
                region.removeChunkAtSafepoint(chunkKey);
                fresh.addChunkAtSafepoint(chunkKey);
                synchronized (chunkToRegion) {
                    chunkToRegion.put(chunkKey, fresh);
                }
            }

            for (Entity entity : new ArrayList<>(region.entities())) {
                long entityChunk = ChunkPos.key((int) Math.floor(entity.x()) >> 4, (int) Math.floor(entity.z()) >> 4);
                if (component.contains(entityChunk)) {
                    region.removeEntityAtSafepoint(entity);
                    fresh.addEntityAtSafepoint(entity);
                }
            }
            // Stagger the new region's first tick so freshly split regions do not all fire together.
            fresh.nextTickNanos = System.nanoTime() + (i * 1_000_000L);
            splits++;
            Log.debug("Region#%d split off Region#%d (%d chunks)", region.id(), fresh.id(), component.size());
        }
    }

    private void addEntity(Entity entity) {
        int cx = (int) Math.floor(entity.x()) >> 4;
        int cz = (int) Math.floor(entity.z()) >> 4;
        Region region;
        synchronized (chunkToRegion) {
            region = chunkToRegion.get(ChunkPos.key(cx, cz));
        }
        if (region == null) {
            // Only players are worth loading a chunk for. Anything else -- a dropped stack whose
            // chunk has just unloaded -- is discarded instead, or item entities would hold chunks
            // resident forever after everyone leaves.
            if (!(entity instanceof dev.quasar.entity.Player)) {
                entity.markRemoved();
                return;
            }
            // The chunk the player wants is not loaded. Load it, then retry on the next safepoint.
            requestChunkAdd(cx, cz);
            pending.add(new Change.AddEntity(entity));
            return;
        }
        region.addEntityAtSafepoint(entity);
        Log.debug("Entity %d joined Region#%d", entity.entityId(), region.id());
    }

    private void removeEntity(Entity entity) {
        Region region = entity.region();
        if (region != null) {
            region.removeEntityAtSafepoint(entity);
            entity.setRegion(null);
        }
    }
}
