# QuasarMC → production-ready (and better than Folia)

The goal is not to "catch up with Folia" but to build an **architecturally cleaner, more
predictable and more scalable** region-threaded core — one that can genuinely be put into
production.

**State as of 7 September 2026:**

- Working region-threaded tick
- Basic 1.21.4 protocol
- Block breaking and placing
- Containers and item entities
- Anvil (partial)
- No block behaviour, mobs, physics, light, or API

---

## Phase 0 — Foundations (mandatory before everything else)

**Goal:** make the core bulletproof.

### 1. A real light engine — **done (2026-09-08)**

Not merely "add light", but make it **region-safe**. Light updates must not create cross-region
dependencies without an explicit mailbox.

*Better than Folia:* predictable light-update latency, plus the ability to defer expensive
recalculation.

**Landed as** `world/light/`. Sky and block light propagate; budgeted at 8192 positions per region
tick, so a cascade defers instead of spiking. It turned out to need **no mailbox at all**: light
reaches at most 15 blocks, which is at most 1 chunk, and LINK_RADIUS already guarantees chunks
within 2 share a region. The queue is per-region even so, because a shared one would itself be
cross-region mutable state. Approximations (occlusion by name not shape, hand-written emission
table, no daylight cycle) are listed in the README.

### 2. A strict ownership model, with assertions — **done (2026-09-08)**

`Region.assertOwned()` already exists — strengthen it and cover nearly everything with it. Any
ownership violation must crash immediately with a comprehensible stack trace in debug mode.

This is what Folia badly lacks: there, such bugs usually surface as silent corruption.

**Landed as** `engine/Ownership.java`: block reads and writes, entity movement, region entity and
tick-count access, and every structural change are checked; cheap checks always on, lookup-based
ones behind `engine.strict-ownership` (auto = on with `--debug`). Errors name the thread, the region
and the coordinate, and say to post to the mailbox. Verified by sabotage in `OwnershipTest`, not by
observing green. See the README section "Enforced ownership".

### 3. Stable safepoints

Make safepoints as cheap and predictable as possible. Rate-limiting plus prioritisation (unload >
merge > split, and so on). Metrics: how much time the server spends inside safepoints.

### 4. Correct persistence

Full Anvil and `level.dat`. Save item entities, block entities and player data. Be able to load
vanilla worlds, at least partially.

---

## Phase 1 — Basic gameplay (creative → soft survival)

**Goal:** make the server comfortable to play on, at least in creative plus light survival.

| Priority | Feature | Why it matters / how to do it smarter than Folia |
|----------|---------|--------------------------------------------------|
| P0 | Block physics (gravel, sand, water, lava) | Implement as region-local simulation with a mailbox for boundaries |
| P0 | Redstone (at least basic) | The hardest part. Needs a clear model of "this redstone component belongs to this region" |
| P1 | Random ticks | Easy, but important for crop growth |
| P1 | Block updates / neighbour updates | Carefully, without cascading cross-region explosions |
| P1 | Inventory and crafting | Survival is impossible without it |
| P2 | Furnaces, brewing stands, enchanting tables | Processing logic |
| P2 | Signs, banners, heads | Small things, but they matter enormously to a server feeling alive |

**The key idea:** any system that can cross a region boundary must go **only** through a mailbox or
an explicit ownership transfer. No hidden global structures.

---

## Phase 2 — Entities and world

**Goal:** make the world alive.

### 1. Entity system 2.0

Clear ownership of an entity by a region. Moving an entity between regions is an atomic transfer.
AI and pathfinding stay inside the region, querying neighbours through the mailbox.

### 2. Mobs (basic)

Passive and simple hostile first; complex ones (villagers, raids) later.

### 3. Combat and damage

Fully region-local wherever possible.

### 4. World generation

Noise and flat already exist. Add proper biomes and structures, at least basic ones.

---

## Phase 3 — API and extensibility (the most important thing for the project's survival)

Without a good API the project dies, however perfect the core is.

### What to do a hundred times better than Folia

**1. A region-aware plugin API from day one.** Do not try to support the old Bukkit style. Build an
API that **forces** you to think in regions from the start:

    region.run(() -> { ... });
    region.schedule(...);
    entity.transferTo(otherRegion);

**2. A powerful event system.** Events have an explicit thread context, and you can subscribe to an
event "in this region only".

**3. Schedulers.** `RegionScheduler` (the beginnings already exist), `GlobalScheduler` for genuinely
global things only, and `EntityScheduler`.

**4. Datapack and registry support**, so content can be added without recompiling the core.

---

## Phase 4 — Production hardening

- Online mode with correct authentication
- Anti-cheat hooks, or at least extension points
- Metrics and profiling out of the box: TPS per region, MSPT, safepoint time, region count,
  entity count per region
- Graceful shutdown and crash recovery
- Configuration through comprehensible files, with hot-reload where possible
- Proxy support (Velocity/Bungee) out of the box
- Good region debugging tools — visualising region boundaries, like the BlueMap plugin for Folia

---

## Phase 5 — What could make QuasarMC genuinely better than Folia

This is where there is real ground to win.

**1. A predictable performance model.** Firm guarantees: "a region never waits on another region for
longer than X ms."

**2. Zero shared mutable state** between regions, as far as that is possible at all. Folia still has
some global and shared structures.

**3. Excellent developer experience.** Comprehensible errors when threading rules are broken, a
"strict ownership" mode for plugin development, and built-in visualisation of regions and load.

**4. Flexible regions.** A configurable link radius, the ability to pin a region, and dynamic region
sizing based on load.

**5. A modern stack.** Java 21+ features used fully (virtual threads where appropriate, structured
concurrency), with a clean and documented architecture.

---

## Recommended order of implementation

**The critical path — none of this is optional for production:**

1. ~~Light engine~~ — done
2. Block physics and water
3. Basic redstone
4. Inventory and crafting
5. Stable persistence
6. A proper plugin/event API
7. Mobs and combat, at a basic level

After that it is fair to call it an "almost survival" server.

---

## Final advice

QuasarMC currently wins on **architectural cleanliness**. The main danger is starting to bolt on
vanilla features however they happen to fit, and losing that cleanliness.

So the golden rule for the whole roadmap:

> Every new system must explicitly answer the question:
> **"Which region owns it, and what happens at the boundaries?"**

Hold to that rule and the project has a real chance of being not just "another core", but the best
region-threaded solution there is.

---

## Reality check against the current tree

Checked while adopting this roadmap, so no phase is planned against a wrong picture of the code.

**Sharper than the roadmap states.** `Region.assertOwned()` exists, but outside `Region.java` it has
**exactly one call site**. The ownership model is currently a convention held up by careful review,
not an enforced invariant — so Phase 0.2 is not "strengthen an existing guard", it is closer to
building the guarantee for the first time. It is also the cheapest item on the critical path and the
one that protects every later phase, which argues for doing it *before* the light engine rather than
after.

**Already present.** The mailbox is real: `Region.post(Runnable)` and `Region.postDelayed(Runnable,
int)`. Safepoint rate-limiting exists (`MIN_SAFEPOINT_INTERVAL_NANOS`), as does a long-safepoint
warning. `TickMetrics` exposes average, p95 and max MSPT, TPS, dropped ticks and sample count.

**Missing, as the roadmap says.** No safepoint *duration* metric is accumulated — only a count and a
per-occurrence warning, so "how much time does the server spend in safepoints" cannot be answered
today. Safepoint tasks are a plain FIFO queue with no prioritisation. `LINK_RADIUS` is a
`static final int` in `RegionManager`, not configuration, so Phase 5.4 starts by making it a
parameter.

**One correction to the status block.** Block entities *are* implemented and persisted, and item
entities work; what item entities lack is persistence across chunk unload. See the README.
