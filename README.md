# Quasar

A Minecraft server core written from scratch in Java 21, built around a **region-threaded** tick
engine: the world is partitioned into independent regions that tick in parallel on separate
threads, each at its own 20 TPS, with no global tick loop and no locks on world data.

Same core idea as Folia, implemented from zero rather than as patches over Mojang's code.

---

## Status: what actually works

This is a working server core, not a drop-in replacement for `server.jar`. Be clear on the split
before you judge it:

**Verified working** (measured, not assumed — see [Benchmarks](#benchmarks)):

- Region-threaded tick engine: regions form, merge, split and retire correctly
- Global safepoints for every structural change, with no locks on chunk or entity state
- Full connection lifecycle: handshake → status → login → configuration → play
- Packet framing, zlib compression, keep-alives, timeouts
- Async multi-threaded world generation (noise and superflat)
- Chunk streaming with a ticket-based load/unload lifecycle
- Chat, movement, per-region metrics, console commands
- Block breaking and placing, with change broadcasts and sequence acknowledgement
- Anvil persistence: edits survive unload and restart, and the world opens in external tools
- Full creative inventory: any of the 952 placeable items can be picked and placed
- Entity tracking, so players see each other move
- Player data, so you return where you left off
- Block entities and working containers, stored in Anvil and preserved even when unmodelled
- Item entities: drops fall, merge, and are picked up with no duplication or loss

**Not implemented** — deliberately, and it would be dishonest to imply otherwise:

- **Block behaviour.** No redstone, fluids, random ticks, or crafting. Blocks can be broken and
  placed, but nothing reacts: gravel floats, water does not flow.
- **Mobs, combat, physics.** Players move; nothing else does.
- **Survival mechanics.** Containers, the inventory and item entities are real, but there is no
  crafting, and no stack accounting on placement — creative items are infinite and placing never
  consumes one. Breaking a block does not drop it, which matches vanilla creative.
- **Item entity persistence.** Drops live in memory only; see [Item entities](#item-entities).
- **Furnaces, brewing stands, enchanting tables.** Containers with processing logic, not just slots.
- **Reading arbitrary vanilla worlds** without a `blocks.json` to hand. Worlds are Anvil, but the
  built-in block table only covers blocks this server itself uses. See
  [Persistence](#persistence--anvil).
- **Online mode.** No encryption, no Mojang session check. Startup *fails* if you enable it
  rather than silently running something insecure. Do not expose this to the internet.
- **Light engine.** Chunks ship full-bright sky light instead of propagating light.
- **Plugin API.**

**Verified against a real client** (vanilla 1.21.4 via PrismLauncher) for handshake, status, login
and configuration; every packet ID in [`Protocol`](src/main/java/dev/quasar/net/Protocol.java) and
every block state ID in [`Blocks`](src/main/java/dev/quasar/world/block/Blocks.java) now comes from
Mojang's own generated reports rather than from memory.

Two real bugs were found this way, both worth knowing about because neither is guessable:

1. **Dangling registry reference.** The wolf variants named `minecraft:taiga` while the biome
   registry contained only `minecraft:plains`. The client resolves cross-registry references when it
   handles Finish Configuration and aborts with *"Unbound values in registry"*. Any biome named
   anywhere in [`Registries`](src/main/java/dev/quasar/net/registry/Registries.java) must also be
   sent by it — hence the single `ONLY_BIOME` constant.
2. **Colliding packet ID.** `set_default_spawn_position` was `0x5A`; the real value is `0x5B`, and
   `0x5A` is `set_cursor_item`. The client happily decoded our 12-byte spawn packet as an item stack
   and died with *"found 11 bytes extra"* — naming a packet we never sent. A wrong ID shows up as a
   failure in something unrelated, which is exactly why guessing them is a bad idea.

A vanilla 1.21.4 client now joins, renders the world and plays: it sends `player_loaded`, walks and
looks (the movement packets are handled, not merely tolerated), streams chunks correctly as it
moves, flies in creative, and disconnects cleanly with every chunk ticket released. Ticks held
20 TPS throughout.

Breaking and placing blocks work. What the client still sends and this server ignores: `swing`,
`player_abilities`, and the inventory packets.

---

## Quick start

Requires JDK 21+. Nothing else — the Gradle wrapper is committed.

```bash
./gradlew fatJar
```

```bash
java -jar build/libs/quasar-0.1.0-all.jar
```

First run writes `quasar.properties` with every setting spelled out. Console commands: `help`,
`status`, `regions`, `players`, `world`, `mem`, `say <msg>`, `stop`.

---

## How the engine works

### The ownership rule

A region owns a set of chunks and every entity standing in them. While it ticks, its worker thread
is the **only** thread allowed to read or write that state. There is not a single lock on block data
or entity fields anywhere in this project — correctness comes entirely from that invariant.

Two things preserve it:

1. **Link radius.** Chunks within 2 of each other are always in the same region, and regions are the
   connected components of that relation. Nothing a tick does reaches further than that, so a region
   can never need state another region owns.
2. **Safepoints.** Region membership only changes when no region is ticking.

Anything that must touch a *different* region posts to that region's mailbox, drained by its owner
at the top of its next tick. `Region.assertOwned()` turns a violation into an immediate stack trace
instead of a silent data race.

### No global tick

Each region carries its own deadline and is dispatched to a work-stealing pool when it comes due. A
single dispatcher thread makes scheduling decisions; workers only execute. A region full of
expensive work slows down nobody but itself.

### Safepoints

When structural work is queued, the dispatcher stops *starting* ticks and waits for in-flight ones
to drain. That bounds the pause by the slowest single tick rather than by anything unbounded, and it
gives the rest of the server a stop-the-world primitive (`RegionScheduler.runAtSafepoint`) for
chunk unloading, shutdown, and anything else needing a consistent view. Safepoints are rate-limited,
because chunk loads and unloads are near-continuous while players move and one safepoint per change
would serialise the whole server.

### Chunk lifecycle

Ticket taken → generated on the world-gen pool → published and adopted by a region at the next
safepoint → ticket dropped → unloaded at a safepoint. Two failure modes are handled explicitly,
because both were caught in testing and both are silent when they go wrong:

- A ticket can vanish *while the chunk is still generating* (a player crosses a chunk boundary
  faster than generation completes). Such chunks are queued for unload on arrival instead of
  becoming permanently resident with no owner.
- A region whose last chunk unloads is retired — but any entity still in it is **re-homed** first. A
  player moving faster than chunks generate can empty their entire old view disc in one batch, and
  without re-homing they would land in a dead region, stop ticking, and never be seen again.

---

## Benchmarks

Identical workload, same machine (12 cores), only `engine.region-threads` changed. 10 bots scattered
6000 blocks apart → 10 independent regions, ~2958 chunks loaded, `engine.synthetic-tick-load-micros
= 8000` so each region tick costs a realistic 8 ms.

| `region-threads` | Slowest region TPS | Per-region MSPT | Peak regions ticking at once |
|---|---|---|---|
| 1  | **10.4** | 8.20 | 1 / 1 |
| 12 | **20.0** | 8.08 | 12 / 12 |

10 regions × 8 ms = 80 ms of work per 50 ms budget. One thread cannot keep up and falls to half
rate; twelve threads absorb it and hold the 20 TPS ceiling with headroom to spare.

The synthetic load exists because this core implements no block behaviour, so a real tick is nearly
free — which would make a fast serial loop indistinguishable from true parallelism. `parallel=` and
`peak=` in the metrics line count regions *actually inside a tick simultaneously*; a peak of 1 means
the threading bought nothing.

Reproduce:

```bash
java -cp build/libs/quasar-0.1.0-all.jar dev.quasar.bench.BotSwarm --count 10 --spread 6000 --walk-seconds 10 --seconds 40
```

The bots start together at spawn and walk apart, which exercises both halves of the region graph:
one shared region at the start that has to split as they separate. Pass `--teleport true` to jump
instead.

---

## Configuration

`quasar.properties`, rewritten with all defaults on first run.

| Key | Default | Notes |
|---|---|---|
| `server.port` | 25565 | |
| `server.view-distance` | 8 | Each player's disc is `(2n+1)²` chunks |
| `server.compression-threshold` | 256 | 0 disables compression |
| `server.online-mode` | false | **true is rejected at startup** |
| `world.generator` | noise | `noise` or `flat` |
| `world.min-y` / `world.height` | -64 / 384 | Must match the dimension registry entry |
| `engine.region-threads` | cores | Region tick workers |
| `engine.worldgen-threads` | cores/2 | Chunk generation pool |
| `engine.metrics-interval-seconds` | 30 | 0 disables the summary line |
| `engine.synthetic-tick-load-micros` | 0 | Benchmarking only |

---

## Protocol versions

Targets **Minecraft 1.21.4 (protocol 769)**.

Mojang renumbers play-phase packets on almost every release, and those numbers are the single most
likely thing here to be wrong for your client build. They are all gathered in one file —
`net/Protocol.java` — so a mismatch is a one-file fix rather than a hunt, and every one can be
overridden **without recompiling** by dropping a `protocol.properties` next to the jar:

```properties
play.clientbound.login = 0x2C
play.clientbound.chunk_data = 0x27
```

Two other version-sensitive tables:

- `world/block/Blocks.java` — global-palette block state IDs, overridable via `blocks.properties`.
  Only `AIR` (0) and `STONE` (1) are reliable across versions; the rest are best-effort.
- `net/registry/Registries.java` — the dimension, biome, damage-type, wolf and painting registries
  the client demands during configuration. Entry *names* matter more than their contents: a client
  accepts bland values but not a missing key it expects to resolve.

**Don't hand-write these — generate them.** Mojang's server jar emits the exact tables:

```bash
java -jar server.jar --reports
```

- `generated/reports/packets.json` — every packet with its phase, direction and protocol ID
- `generated/reports/blocks.json` — every block state; use the one flagged `"default": true`
- `generated/reports/registries.json` — registry contents

It only writes files and exits; no EULA, no server started. Get the jar for a specific version from
`https://launchermeta.mojang.com/mc/game/version_manifest_v2.json` → the version's metadata URL →
`downloads.server.url`. These generated files are deliberately gitignored rather than committed.

---

## Layout

```
engine/     Region, RegionManager, RegionScheduler, TickMetrics — the core
net/        Netty pipeline, protocol constants, packet listeners per state
  listener/   Handshake → Status → Login → Configuration → Play
  registry/   Datapack registries sent during configuration
world/      Chunk, ChunkSection, World, ticket system, generators
entity/     Entity, Player
nbt/        NBT tree model and network serialiser
bench/      BotSwarm load harness
```

Sections start *uniform* — one state, no backing array — and only materialise storage on the first
differing write, so the mostly-air-or-stone sections of a generated world stay cheap.

---

## Next steps

The full plan through to production lives in [ROADMAP.md](ROADMAP.md). Its critical path, in order:

1. **Enforced ownership.** `assertOwned()` exists but is barely called; today the no-locks guarantee
   rests on review discipline rather than on a check that fails loudly. Cheapest item on the path,
   and it protects every phase after it.
2. **A light engine**, so caves and interiors are not uniformly bright — and region-safe, meaning
   light updates cross a boundary only through a mailbox.
3. **Block physics**: gravel and sand fall, water and lava flow.
4. **Basic redstone**, which needs a clear answer to which region owns a component.
5. **Crafting**, without which survival is impossible.
6. **Persistence gaps**: item entities across chunk unload, and reading arbitrary vanilla worlds.
7. **A region-aware plugin and event API**, then basic mobs and combat.

Smaller things worth doing along the way: signs, a safepoint-duration metric, and making
`LINK_RADIUS` configurable.

## Persistence — Anvil

Worlds are written in Mojang's **Anvil** format: `<level-name>/region/r.<rx>.<rz>.mca`, plus a
`level.dat`. Third-party tools (Amulet, MCA Selector, NBTExplorer) read them, and the folder can be
dropped into `.minecraft/saves`.

Chunk NBT carries `DataVersion` 4189 — read out of `version.json` in the 1.21.4 client jar
(`world_version`), not guessed. Block palettes use names and properties from Mojang's `blocks.json`
report. Section indices are packed `64 / bits` per long without straddling, with
`bits = max(4, ceil(log2(paletteSize)))`; the floor of four is not optional.

**Only edited chunks are saved.** Generation is deterministic, so an untouched chunk costs nothing to
store and is simply regenerated — a world stays proportional to what was built, not to where people
walked. Loading checks disk first, so an edited chunk returns as it was left.

The chunk's NBT is built at a safepoint, where nothing can be mid-tick over it; deflating and writing
happen on the IO thread. A save therefore costs a pause proportional to how much was edited and none
proportional to disk speed. Chunks are saved on unload, on a timer
(`world.autosave-interval-seconds`), on `save` from the console, and on shutdown.

### Player data

Position, rotation and hotbar are written to `<level-name>/playerdata/<uuid>.dat` as gzipped NBT —
the same place and encoding a vanilla server uses. Only a subset of vanilla's fields is written;
anything else it expects is absent and filled with defaults. The hotbar is this server's own idea
and lives under a namespaced key rather than vanilla's `Inventory`, which has a different structure
and would be misread.

Saved when a player leaves and on every world save, loaded on join, with world spawn as the fallback
for someone who has never played. Snapshots are built on the thread that owns the player, so
positions are consistent, and only the finished NBT crosses to the IO thread.

Writes go through a temporary file and an atomic move. This file is rewritten constantly, and a
crash midway through a plain write would truncate it and lose the player entirely; a failed move
just leaves the previous one.

### Exporting a world to open elsewhere

Because only edited chunks are stored, a world opened in Minecraft would otherwise show a handful of
saved chunks surrounded by whatever vanilla's own generator produces. The console command `saveall`
writes every currently loaded chunk instead, edited or not, turning the view distance around the
players online into a world someone else can walk around in:

```
saveall          # with someone standing where you want the export centred
stop
```

Then copy `<level-name>/` into `.minecraft/saves/`. The bot harness can hold that position for you:
`--stay true` keeps bots wherever the server spawned them rather than scattering.

Heightmaps are recomputed on load rather than trusted from disk — they are a pure function of the
block data, so deriving them removes any chance of drift. Same for a section's non-air census.

### The block table, and what happens without it

Anvil identifies blocks by name and properties, so a numeric state ID needs translating. A built-in
table covers every block this server generates or places, which is all that writing its own worlds
requires.

Reading an *arbitrary* vanilla world needs the whole registry — some 28,000 states, which is Mojang's
data and is not shipped here. Drop a `blocks.json` from `--reports` next to the jar and the full
table loads at startup.

Without it, a chunk containing unknown blocks still loads, with those blocks standing in as stone —
but the chunk is flagged and **never written back**. The same applies to chunks carrying block
entities or scheduled ticks, which this server cannot represent. Degrading someone's world on disk
because a lookup table was missing would be far worse than declining to save it.

### Known limits

- Terrain comes from this server's own noise generator, not Mojang's. Chunks it saved load back
  exactly; anything vanilla generates beyond them follows vanilla's generator and will not line up at
  the seams.
- No light data is written, so vanilla relights on load.
- Only the overworld exists.

## Spawn

Players spawn on dry land, found by walking a coarse grid outward from the origin until a column's
surface clears sea level. Origin is a poor default — with a noise generator the terrain there is as
likely as not to sit under water, and spawning submerged makes every attempted placement target
water instead of air. The search generates no chunks; it only evaluates the generator's height
function, which is pure noise.

## The creative inventory

Every item in the creative menu can be picked and placed — 1,385 items, 952 of which are blocks.

The server sends none of that. **The creative inventory is built client-side**: the client already
knows every item and renders the whole menu without asking. All the server has to do is listen. When
a player picks something the client reports it in `set_creative_mode_slot`, and from then on the only
question is which block a given item ID places.

Almost always, the one whose name matches — `minecraft:oak_stairs` the item places
`minecraft:oak_stairs` the block. A handful disagree (`redstone` → `redstone_wire`, `carrot` →
`carrots`) and are listed in `ItemRegistry.NAME_OVERRIDES`. Items with no block — swords, food —
place nothing.

### Placement state

Blocks are placed in the state the click implies, not their default one:

| property | derived from | affects |
|---|---|---|
| `axis` | the clicked face | logs, pillars, chains |
| `half` / `type` | clicked face, and cursor height on it | stairs, slabs, trapdoors |
| `facing` | the player's yaw | stairs, furnaces, observers |
| `waterlogged` | whether the space held water | anything waterloggable |

Only properties the block's default state actually has are touched, and the adjusted combination is
looked up by name — if it does not exist, the default is used. That fallback is what makes this safe
across the whole block set without a per-block table: `type` means top/bottom on a slab but
single/left/right on a chest, and asking for a chest of type "bottom" simply fails the lookup and
leaves the chest alone.

Stairs face the way the player looks, so you walk up them going forwards; almost everything else
faces back towards the player so its front is the side you can see.

Not handled: blocks whose facing comes from the clicked face rather than the player — wall torches,
buttons, levers — keep their default state. Slabs do not merge into double slabs.

### Connection state

Some properties come from a block's *neighbours* rather than from the click, and have to be
recomputed on both sides whenever anything changes nearby. Placing or breaking a block re-derives
that position and its four horizontal neighbours:

- **fences, glass panes, iron bars** — `north/south/east/west` booleans
- **walls** — `none/low/tall` per side, plus a raised `up` post unless it is a straight run through
- **stairs** — `shape`, giving inner and outer corners
- **chests** — `type`, pairing adjacent chests into a double chest

Stair corners follow vanilla exactly, including the rule that suppresses a corner when the
neighbour is a stair of the same facing and half, so straight runs stay straight.

Fences match on the exact material, so oak does not join nether brick. Joining to ordinary *solid*
blocks is an approximation: neither the block report nor the protocol carries collision or shape
data, so solidity is inferred from the block's name against a curated list of decorative and
non-collidable families. Everything unmatched counts as solid, which is right far more often than
not; the failure mode is a fence connecting to something decorative it should have ignored.

Updates stop at the edge of the region that owns the chunk, so a connection there is left stale
rather than computed by reaching into another thread's state.

### Pick block

Middle-click puts the block you are looking at into the held hotbar slot. Resolved by block *name*,
so it works on any state — middle-clicking east-facing stairs hands you the stairs item rather than
failing because only the default state was mapped.

Vanilla in creative hunts for a slot already holding that item and switches to it. With no real
inventory to search, this overwrites the held slot, which is what a creative player is after: pick,
then place.

### Data files

`blocks.json` and `registries.json` from `java -jar server.jar --reports` are read from the working
directory at startup — 27,866 block states and 1,385 items in about 360 ms. They are Mojang's data,
so they are loaded at runtime rather than committed here.

Without them the server falls back to the nine-block starter hotbar and says so at startup, and
Anvil support narrows to the blocks this server generates.

## Why the server hands out a hotbar

A client holding nothing does not send `use_item_on` — right-clicking with an empty hand is inert,
so no amount of server-side logic makes placing work. With no inventory system, nothing would ever
fill that hand. So on join the server pushes a fixed nine-block hotbar
([`HotbarKit`](src/main/java/dev/quasar/item/HotbarKit.java)) as one Set Container Content on
window 0.

The pairing is the important part. Each entry ties an item ID to the block state that item places,
and item N sits in hotbar slot N while slot N places block N. That way the client's optimistic
prediction and the server's write agree, so placement neither flickers nor rolls back. A mismatched
table would reintroduce exactly the desync the pairing exists to prevent.

Both ID spaces come from Mojang's reports — `registries.json` under `minecraft:item` for item IDs,
`blocks.json` for default block states — and both are overridable in `hotbar.properties` as
`<name> = <itemId>:<blockState>`.

## Block entities and containers

Chests, barrels, hoppers, dispensers, droppers and shulker boxes open, hold items and persist.

Two adjacent chests facing the same way pair into a double chest and open as one 54-slot screen. The
pairing is derived from neighbours like any other connection state: a `left` half's partner sits
clockwise of its facing, a `right` half's counter-clockwise. A partner must be unpaired or already
pointing back, so a row of three chests cannot leave a half claimed by both its neighbours — a state
vanilla never produces and the client renders wrongly.

Each half keeps its own block entity on disk, with the window's slots laid end to end across the
two. That is what lets vanilla read the two chests back independently.

Block entities are stored per chunk and written into Anvil's `block_entities` list **verbatim**,
`id` and coordinates included. Storing them untouched is what lets a chunk carrying block entities
this server does not model — spawners, signs, beehives — be loaded, held and written back unchanged
rather than dropped. That is also why chunks with block entities are no longer loaded read-only;
only scheduled ticks still block a save, because nothing here can run them and writing the chunk
back without them would quietly cancel whatever they were going to do.

A block entity is discarded when its block is replaced, compared by block *name* rather than state:
a chest gaining a connection changes state without becoming a different block, and dropping its
contents for that would be a data-loss bug.

Which blocks are containers is a curated table. Nothing in the generated reports says which blocks
have a block entity, let alone how many slots it has. Furnaces, brewing stands and enchanting tables
are deliberately absent — their screens have fuel, progress and result slots whose behaviour is not
just "move items around", and a half-implemented furnace would be worse than none.

### Clicks

Window slots past the container map onto the player's real inventory rather than onto a copy, so
the two cannot disagree. Handled:

| mode | what it is |
|---|---|
| 0 | pick up and place, right-click for half-stacks and single items |
| 1 | shift-move, merging into matching stacks before filling empty ones |
| 2 | number keys 1-9, swapping the slot with that hotbar slot |
| 5 | drag-painting: hold the button and sweep to spread a stack across slots |
| 6 | double-click to gather matching stacks onto the cursor |

Drag-painting arrives as three separate packets — start, one per slot swept, then end — all mode 5,
distinguished by the button field. Nothing is applied until the end, because how the stack splits
depends on how many slots were swept in total. A left drag divides evenly, a right drag puts one in
each, and a middle drag fills each from an inexhaustible cursor, which is creative-only.

Every container update carries an incrementing revision. The client echoes the last one it saw on
each click and uses it to tell whether its prediction is still in step; sending a constant made
every update look like the same revision, which is indistinguishable from a stale client.

**The whole window is re-sent after every click** rather than sending per-slot deltas. Container
clicks have a lot of cases, and any disagreement between what the client predicted and what the
server did leaves items visibly duplicated or missing until something else resyncs. A full resync
costs a few hundred bytes and makes that class of bug impossible.

Clicking outside the window keeps the stack on the cursor instead of dropping it: there are no item
entities here, so a "drop" would silently destroy it. Closing a container with something on the
cursor puts it back in the inventory for the same reason.

**Breaking a container hands its contents to whoever broke it.** Vanilla drops them on the ground,
which needs item entities this server does not have — so the alternative was deleting them, which is
silent and unrecoverable. If the inventory is full the remainder is lost, and says so in the log
rather than vanishing quietly.

Item stacks carry an ID and a count and nothing else. Enchantments, damage and custom names are
component data this server does not model, and pretending to would mean dropping them on the first
click.

## Entity tracking

Players see each other: spawns, movement, head rotation and despawn, plus tab-list entries.

Only players in the same region are considered, and that is exact rather than a shortcut. Two
players close enough to see each other have overlapping chunk discs, and overlapping discs are one
region by construction; players in different regions are at least two view distances apart, well
beyond tracking range. So tracking runs entirely on the owning thread with no cross-region reads —
the same invariant that makes block edits lock-free.

Movement is sent as a delta, which the protocol encodes as a short of 1/4096 blocks and so tops out
at eight. A larger jump is sent as a despawn followed by a respawn rather than reaching for the
teleport packet, which was reworked in 1.21.2 and is one more thing to get wrong.

Tab-list entries are managed separately from entity spawn: the entity comes and goes with range,
but the tab entry lasts as long as the player is online, or the list would flicker as people walk
in and out of view. Skins are not sent — that needs signed profile properties from Mojang's session
servers, which an offline-mode server has no way to obtain.

## Item entities

Dropped stacks are real entities that fall, merge and are picked up.
[`ItemEntity`](src/main/java/dev/quasar/entity/ItemEntity.java) is deliberately small: it falls
straight down under gravity until a block is underneath, absorbs same-item stacks within one block,
lets any player within 1.6 blocks collect it, and despawns after 6000 ticks (five minutes, as in
vanilla) with a 20-tick delay before it can be regained.

They arrive from four routes — throwing with Q, dropping the cursor stack outside the window,
overflow when a container gives you more than fits, and the contents of a container you break.

**No horizontal motion, on purpose.** Vanilla throws items in an arc and lets them bounce and slide,
which needs real collision shapes. There are none here, and a half-simulated arc that clipped through
walls would be worse than a stack that drops straight down.

An item never changes its horizontal position, so it stays in the chunk it was dropped into and can
never need state another region owns. That means the whole thing runs on the owning region's thread
with the same zero-lock guarantee as everything else — merges and pickups are plain loops over
`region.entities()`.

**Two honest limits.** Items are *not persisted*: when their chunk unloads they are discarded rather
than saved, and unlike a player they do not force a chunk to stay loaded. Persisting them without
that second rule would mean every stack anyone ever threw held a chunk resident forever after the
last player left. And *breaking a block does not drop the block* — players are in creative, where
vanilla drops nothing either. Container contents do drop, because vanilla drops those even in
creative.

Add Entity carries no item stack, so a drop is invisible until its metadata arrives; the tracker
sends `set_entity_data` (index 8, serializer 7) right after the spawn and again whenever a merge or
partial pickup changes the count.

## Block editing, and why it needs no locks

Breaking and placing land on the owning region's thread and touch nothing else. That falls directly
out of the engine's invariant rather than from any locking: a player's view disc is always contained
in a single region, so *every* player who could witness a block change is an entity of the same
region already running the edit. The broadcast is a plain loop over `region.entities()`.

Placement replaces air *and water*, matching vanilla. Restricting it to air alone meant every
right-click below sea level was silently refused, which on screen is indistinguishable from a
placement that never arrived — the block just flashes and vanishes.

Three guards apply before a write, in `Player.isEditAllowed`:

- **Reach** — beyond ~8 blocks the edit is dropped. Without it the packet is a remote world-edit
  primitive for anyone who can open a socket.
- **Ownership** — a position outside the region's own chunks belongs to another thread and is
  refused, which keeps the single-writer rule true by construction rather than by convention.
- **Build height.**

A rejected edit is still acknowledged, and the authoritative state at that position is re-read and
sent afterwards, so a refused edit corrects the client rather than leaving its prediction standing.

**Send the ack before the block update, not after.** The client places optimistically and waits on
its sequence number; the ack retires that prediction and snaps the position back to whatever server
state it last knew. Sending the update first requires the client to correctly associate an in-flight
update with a pending prediction, and it does not — placements rolled back to air, while breaks
appeared to work only because the client predicts a break to air anyway. Acking first and then
asserting the truth makes the result independent of that association. This is the kind of bug the
bot harness cannot catch: both orderings look identical from a client that has no prediction logic.
