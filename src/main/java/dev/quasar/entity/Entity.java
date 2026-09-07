package dev.quasar.entity;

import dev.quasar.engine.Region;
import dev.quasar.world.ChunkPos;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anything with a position that ticks.
 *
 * <p>Position and rotation are plain fields with no synchronisation, because they are only ever
 * touched by the thread ticking the owning region. {@link #region} and {@link #removed} are
 * volatile: those two <em>are</em> read from other threads (the network layer checking whether a
 * player is still live, the manager reassigning ownership at a safepoint).
 */
public abstract class Entity {

    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(1);

    private final int entityId = NEXT_ENTITY_ID.getAndIncrement();
    private final UUID uuid;

    protected double x;
    protected double y;
    protected double z;
    protected float yaw;
    protected float pitch;
    protected boolean onGround;

    private volatile Region region;
    private volatile boolean removed;

    protected Entity(UUID uuid, double x, double y, double z) {
        this.uuid = uuid;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int entityId() { return entityId; }

    public UUID uuid() { return uuid; }

    public double x() { return x; }

    public double y() { return y; }

    public double z() { return z; }

    public float yaw() { return yaw; }

    public float pitch() { return pitch; }

    public boolean onGround() { return onGround; }

    public int chunkX() { return (int) Math.floor(x) >> 4; }

    public int chunkZ() { return (int) Math.floor(z) >> 4; }

    public long chunkKey() { return ChunkPos.key(chunkX(), chunkZ()); }

    public Region region() { return region; }

    public void setRegion(Region region) { this.region = region; }

    public boolean isRemoved() { return removed; }

    public void markRemoved() { this.removed = true; }

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Called once per tick of the owning region, on that region's thread. */
    public abstract void tick(Region region);
}
