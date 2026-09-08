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
- Propagating sky and block light, with no cross-region coordination needed
- Block physics: sand and gravel fall as entities, water and lava flow and drain
- Basic redstone: dust, levers, torches, repeaters and lamps
- Crafting: all 932 vanilla crafting recipes, at a crafting table
- Signs you can write on and read back
- Two-block structures: doors, beds and tall plants place and break as one
- Doors, trapdoors and fence gates open on right-click (iron ones need redstone, as in vanilla)
- Water and lava buckets pour and scoop
- Random ticks: grass spreads onto bare dirt and dies back under cover
- Item entities persist across chunk unload, in vanilla's own `entities/` region files

**Not implemented** — deliberately, and it would be dishonest to imply otherwise:

- **Block behaviour.** Gravity, fluids, basic redstone and grass spread work. Crops, leaf decay,
  fire and everything else do not.
- **Mobs and combat.** Players, dropped items and falling blocks move; nothing else does.
- **Survival mechanics.** Containers, the inventory, item entities and crafting are real, but
  there is no stack accounting on placement — creative items are infinite and placing never
  consumes one. Breaking a block does not drop it, which matches vanilla creative.
- **Block entity contents beyond containers.** Chests and their kin are sent to the client; signs,
  banners and spawners have no modelled data of their own.
- **Furnaces, brewing stands, enchanting tables.** Containers with processing logic, not just slots.
- **Reading arbitrary vanilla worlds** without a `blocks.json` to hand. Worlds are Anvil, but the
  built-in block table only covers blocks this server itself uses. See
  [Persistence](#persistence--anvil).
- **Online mode.** No encryption, no Mojang session check. Startup *fails* if you enable it
  rather than silently running something insecure. Do not expose this to the internet.
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
at the top of its next tick.

The invariant is **enforced**, not merely documented — see
[Ownership](src/main/java/dev/quasar/engine/Ownership.java). Every block read and write, every
entity move, and every structural change is checked, and a violation is an immediate stack trace
naming the thread, the region and the coordinate rather than a silent data race.

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

### Entities

Item entities are written to vanilla's own `entities/` region files, beside `region/` — the same
file format, a different tree, which is where Minecraft has kept entities since 1.17. Writing them
into the chunk instead would have worked here and quietly lost them for anything else that opened
the world, which is the opposite of what Anvil support is for.

Saved at a safepoint just before a chunk is dropped, while the owning region still holds them, and
restored when the chunk comes back. Age survives the round trip, so a stack saved four minutes into
its life still despawns on time rather than getting a fresh five.

Falling blocks are not persisted: one exists for well under a second, and it is a transitional
state rather than something a world contains.

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

## Signs

Placing a sign opens the editor, and what you type is stored on the block entity and shown to
everyone who can see it. Text goes out as a single `block_entity_data` packet rather than a chunk
resend.

The one detail worth stating: since 1.20.4 a sign holds `front_text` and `back_text`, each with four
`messages`, and **each message is a JSON text component serialised as a string** — not a bare
string. Getting that wrong gives a sign that saves, loads and renders perfectly blank, because the
client parses each entry and silently draws nothing when it will not parse. The escaping is done by
hand here, since this is the only JSON the server writes and a quote typed onto a sign would
otherwise break the component.

## Random ticks

Grass creeps onto bare dirt that has light and a grass neighbour, and dies back to dirt under
anything solid. Three positions per section per tick, as vanilla does, in the chunks around a
player rather than across everything loaded.

**An optimisation that was removed again**, because it is a useful warning: an early version
examined only a quarter of the chunks per tick, on a benchmark that seemed to show random ticks
costing two milliseconds a region. Re-running with random ticks disabled *entirely* gave a worse
figure than leaving them on — so the two milliseconds were run-to-run variance on this machine, and
the optimisation was justified by noise. It was deleted rather than kept just in case. This machine
swings between 18.4 and 20.0 TPS on identical runs, which is worth knowing before reading anything
into a single measurement here.

## Crafting

All 932 vanilla crafting recipes work at a crafting table: shaped and shapeless, ingredient tags,
patterns matched anywhere in the grid and mirrored, shift-click to craft repeatedly.

### Where the recipes come from

This is the one piece of game data Mojang's `--reports` does not emit. Recipes live as ~1400
individual files in the vanilla data pack **inside the jar**, so they are extracted rather than
written from memory — the same rule as every other version-specific table here:

```bash
java -jar build/libs/quasar-0.1.0-all.jar --extract-recipes path/to/minecraft-1.21.4-client.jar
```

That writes `recipes.json`: 932 crafting recipes, with the other 438 (smelting, stonecutting,
smithing) skipped because a crafting grid cannot produce them. Without the file, crafting is
disabled and says so at startup. A hand-written partial table would be worse than none, because the
missing half looks like a bug rather than an absence.

**Item tags are flattened at extraction.** A recipe asks for `#minecraft:planks`, and tags nest —
`#minecraft:logs` contains `#minecraft:logs_that_burn`. Resolving that once, at extraction, leaves
the server's matcher dealing only in concrete item IDs.

### Matching

Shapeless recipes match by multiset. Shaped ones are trimmed to their occupied rectangle first, so a
2×2 pattern works in any corner of a 3×3 grid, and are tried mirrored as well as normal — both are
vanilla behaviour, and a player notices immediately when they are missing.

A recipe naming an item this server does not know is dropped whole at load rather than half-loaded,
so it can never match something it should not.

### Honest limits

- **Crafting table only.** The 2×2 grid in the player's own inventory is not implemented — and it is
  not reachable anyway, because players are in creative, where that screen is the creative menu.
- **No recipe book.** The client's book will not suggest anything; you have to know the recipe.
- **No special recipes.** Firework, banner and shulker-dyeing recipes are code in vanilla rather
  than data, and the 32 `crafting_transmute` recipes are excluded with them.
- **No drag-painting inside the crafting window.** Containers support it; this does not yet, and an
  unhandled click resyncs rather than desynchronises.

### One bug worth recording

Registries load in an order that matters: a recipe resolves every ingredient to an item ID *as it
loads*, so loading recipes before items dropped all 932 as "unknown items" and left crafting quietly
disabled — the server started fine and said `Loaded 0 crafting recipes`. It now loads items first,
and the log line names the number dropped so the same failure cannot be silent again.

Closing the screen — or disconnecting with a full grid — hands everything back. That second path was
missing at first: the player was saved before the grid was returned, so logging out mid-craft saved
an inventory that did not contain the materials, and they ceased to exist.

## Redstone

Dust carries power and loses a level per block, levers switch, torches invert, repeaters delay and
re-emit, and lamps light. Right-clicking a lever flips it; right-clicking a repeater steps its delay.

### Which region owns a redstone component

The roadmap called this the hard question. The answer turned out to be that **no component needs an
owner, because no component is ever an object**. There is no circuit graph, no network registry,
nothing spanning chunks that would have to belong to somebody: every value is derived from the
neighbours of a single block. That is the same shape as fluid flow, and a neighbour is at most one
chunk away, so any loaded chunk it reaches is in the same region by construction.

A circuit is unbounded — a repeater chain can run for thousands of blocks — and that is fine for the
same reason a river is: it advances a block per update and re-queues, and cannot outrun the loaded,
connected chunks that make up its region.

Modelling a "network" object spanning the wire and giving it an owner would have created exactly the
cross-region shared mutable state this engine exists not to have. Keeping the derivation local is
what makes redstone free of coordination rather than the hardest thing to coordinate.

### Timing

Dust is instant, as in vanilla: a wire carries at most 15 blocks, so the recomputation is bounded
and finishes inside the tick that caused it rather than visibly charging up. Torches and repeaters
introduce the delay, and they schedule their flip for a future tick **of the owning region** — so a
clock's rate is measured in that region's ticks and stays correct when a region runs behind.

### What it costs when you have none

A block change wakes everything within two blocks, and the check for "is any of this redstone" runs
constantly. Doing that with name comparisons cost a full TPS on a world containing no redstone at
all. Both the wake test and the chunk-adoption scan now index a per-state `boolean[]`, and a
redstone-free world is back to 20.0 TPS at 2.85 MSPT with 1734 chunks loaded.

### Four bugs real play found that automation did not

All four came from someone building on the server, and none of them could have been caught by an
assertion on the server's own state — which is the point of keeping a real client in the loop.

1. **Falling blocks were invisible.** Gravity worked perfectly server-side; the entity tracker had
   an allow-list of entity types and falling blocks were not on it, so sand appeared to vanish and
   silently reappear somewhere below. The server log said `lost its support and is falling` while
   the player saw a block delete itself.
2. **Redstone rendered as unconnected dots.** Wire carries `power` *and* four connection
   properties, and only `power` was ever set. Every level was right and the circuit was inert to
   look at, because a wire with all four sides `none` draws as an isolated speck.
3. **Chests were invisible.** The chunk packet sent a hard-coded zero block entities. A chest's
   block model is empty — the whole chest is drawn by a block-entity renderer — so a chest in a
   loaded chunk was a hole in the air that still opened when clicked.
4. **Chests rendered black once visible.** Fixing (3) exposed this immediately: the light engine
   treats anything not in its transparency list as a full cube, so a chest zeroed the sky light at
   its own position — the exact value its renderer uses.

Sand also rested on redstone dust instead of falling through it, which is why gravity now asks
`BlockCollision.isPassable` rather than "is this air or water".

### Honest limits

- **No pistons, comparators, observers, dispensers or droppers.** Those are the interesting half of
  redstone and none of them are here.
- **No buttons or pressure plates.** Levers are the only manual input.
- **Simplified power model.** Vanilla distinguishes strong from weak power in ways this does not,
  so some quasi-connectivity and block-powering edge cases will behave differently.
- **No torch burnout**, so a torch in a fast loop will not stop.
- **Needs `blocks.json`.** The built-in block table has no redstone in it, so without Mojang's
  generated report redstone is simply inert rather than wrong.

### On testing this

Seven tests assert power levels and lit flags — redstone that does nothing leaves a perfectly valid
world behind, so "it ran without throwing" proves nothing. Stubbing out dust propagation fails five
of them. One test runs a wire across a chunk boundary, which is the region-safety claim in practice
rather than in prose.

There is deliberately no scripted-client check. The lesson from the physics scenario stands: mouse
aim cannot reliably hit a specific block face, and a check that cannot hit its target is worse than
no check.

## Block physics

Sand and gravel fall when their support goes. Water and lava flow, thin out with distance, fall
before they spread, and drain when their source is removed.

### Region safety, and how it differs from light

One update step moves a block or a fluid level exactly one block, so it only ever touches the six
neighbours of a position. A neighbour is at most one chunk away, and any *loaded* chunk one away is
in the same region — regions are the connected components of "within `LINK_RADIUS` chunks", so two
adjacent loaded chunks cannot belong to different ones.

Unlike light, a **cascade is unbounded**: water can run for hundreds of blocks. That is still safe,
because it advances one step per update and re-queues. The frontier can only reach chunks that are
loaded and connected, which is the same region by construction; where the loaded world ends, the
flow stops, as it does in vanilla.

Queue entries can still go stale if a region splits between queueing and processing. Rather than
re-homing the queue, each position is checked against the owning region and dropped if the chunk
changed hands — the new owner re-seeds its own chunks on adoption.

Unloaded chunks read as **stone**, not air, through `World.getBlockRaw`. `getBlock` answers air,
which is right for rendering and badly wrong here: air is "replaceable", so water would pour into
unloaded terrain and sand would fall into it, and both writes would then be silently dropped.

### Rates and budget

Water updates every 5 ticks and lava every 30, matching vanilla, which is what makes a stream creep
rather than appear. Gravity is checked every tick. Everything drains under a budget of 2048
positions per region tick, so a large flood defers instead of spiking.

### Honest limits

- **No source forming.** Two adjacent sources over a solid block do not create a third, so infinite
  water pools do not work.
- **No fluid interaction.** Water meeting lava does not make stone, cobblestone or obsidian, and
  nothing catches fire.
- **Falling blocks do not break anything.** They land on the first non-replaceable block; they do
  not destroy torches or crops on the way, and they do not hurt anyone.
- **Only sand and gravel fall.** Concrete powder and anvils behave the same way in vanilla and are
  not listed.

Cost with six bots and 1751 loaded chunks: worst MSPT 3.09, a full 20.0 TPS, up from 1.90 before
physics.

### On testing this

The behaviour is proved by unit tests and by `RegionTickTest`, which runs a real region tick and
asserts sand falls, water spreads, and the chunk gets lit — the tick path itself, not just the
algorithms.

The scripted-client scenario deliberately asserts **nothing** about gravity. Placing a block against
a specific face needs the crosshair on that face, and scripted mouse-look could not hit it reliably:
three attempts produced three different misses, and one of them "passed" while placing sand on flat
ground, where it is supported and can never fall. That is the same false-confidence trap as the
pick-block check that passed without a middle-click. `pwsh -File tools/client-test.ps1 -Scenario
physics` is there to look at, not to assert on.

## The light engine

Sky and block light propagate properly: caves are dark, overhangs cast shade, a torch lights a room,
and breaking the block that caps a shaft lets daylight fall to the floor.

### Why it needs no cross-region coordination

Light is capped at 15 and loses at least one level per block, so a change influences blocks at most
15 away. A section is 16 wide. Fifteen blocks from the very edge of a chunk therefore lands in the
*adjacent* chunk and cannot reach the one beyond it — the blast radius of any light update is the
3x3 chunks around it.

The engine already guarantees chunks within `LINK_RADIUS` (2) of each other share a region. **1 < 2**,
so every chunk a light update can touch is already owned by the region doing the update. Light needs
no mailbox and no ownership transfer; it falls out of the same geometry that makes block editing
lock-free.

The roadmap assumed light would need an explicit mailbox. It does not, and that is worth stating
precisely rather than quietly enjoying: the guarantee depends on two constants. Raising the maximum
light level, shrinking a section, or dropping `LINK_RADIUS` to 1 turns light into a silent
cross-region data race. `LightEngineTest.lightCannotReachBeyondTheAdjacentChunk` asserts the
arithmetic so that change fails loudly instead.

The *queue*, though, is per-region rather than per-world. Two regions ticking in parallel would
otherwise push and pop one shared queue — precisely the shared mutable state the engine exists not
to have. `World.setBlock` finds the right one through `Region.current()`, a thread-local the tick
loop maintains.

### Predictable latency

Propagation is breadth-first and drained under a budget (`DEFAULT_BUDGET`, 8192 positions per region
tick). A cascade — breaking the one block that opens a long cave to daylight — spreads over several
ticks instead of spiking one. A region's light cost is bounded by the budget, never by the size of
the cascade. Work left over is deferred, never dropped, which a test pins by draining a large
propagation through a deliberately tiny budget.

### Memory

Light is 4 bits per block: a section is 2048 bytes, and a chunk with sky and block light for 26
sections would be ~106 KB. A few thousand chunks would be hundreds of megabytes of near-identical
bytes — pitch black underground, full daylight above.

So a light section holds either a real array or a single uniform value, and only materialises when
something inside it actually varies. Generation fills every section above the terrain as uniform
daylight without allocating anything.

### Honest approximations

- **Occlusion is by block name, not by shape.** Vanilla decides from collision shape, so a slab
  blocks light from below but not the side. There are no shapes here, so a curated set of names
  lets light through and everything else stops it. Light does not leak past slabs, fences or stairs
  the way it should.
- **Emission is a hand-written table.** Mojang's generated block report carries every state and its
  properties but *not* luminance, so there is nothing to read it from. The listed blocks are right;
  an unlisted emitter reads as dark.
- **No daylight cycle.** Sky light is computed at full strength; there is no night, so sky light
  never dims.

Cost measured with six bots and 1734 loaded chunks: worst MSPT 1.90, a full 20.0 TPS.

## Enforced ownership

An invariant held up only by careful review is a habit, not an invariant. When the single-writer
rule breaks the symptom is never an exception — it is a block that reverts, an inventory that
duplicates, a chunk that saves half-written. Those corrupt silently, surface far from the cause, and
barely reproduce. This is the difference between "we are careful" and "the engine will not let you".

Checks come in two tiers:

- **Always on.** Comparing a thread reference is free, so anything that only asks "do I own the
  region I am already holding" runs in production too: `Region.assertOwned()`,
  `assertOwnedOrSafepoint()`, and every `*AtSafepoint` structural change.
- **Strict mode.** Checks that must first work out *which* region owns a coordinate need a lookup
  under a lock, far too costly for a path that runs on every block read. Those are gated on
  `engine.strict-ownership`, which defaults to `auto` — on under `--debug`, off otherwise.

Three kinds of access are legal without owning anything, and the list is deliberately short:
generation and loading (the chunk has no owning region yet, so nothing can be racing it),
safepoints (no region is ticking, and the dispatcher marks itself), and startup/shutdown.

**The errors are meant to be actionable**, so the wording is asserted by tests rather than left to
drift:

    Ownership violation: Writing a block at 5,70,5 (chunk 0,0) is owned by region #1,
    but was called from thread 'intruder'. Post the work to that region instead:
    region.post(() -> ...).

A violation inside a tick is caught and logged by `Region.runTick`, so one bad region cannot take
the server down — which also means a lone stack trace could scroll past unnoticed. The count is
therefore carried on every metrics line as `OWNERSHIP-VIOLATIONS=n` once non-zero.

### Proving the checks can fail

[`OwnershipTest`](src/test/java/dev/quasar/engine/OwnershipTest.java) deliberately breaks the rule
from a foreign thread and asserts it is caught. That is the point of it: an assertion that has never
fired is indistinguishable from one that *cannot* fire, and this project has been burnt by exactly
that twice — a bot swarm that ran green against a fully submerged world, and a pick-block check that
passed without a single middle-click ever being handled.

So the guards were verified by sabotage, not by observing green. Stubbing out the block check fails
exactly the two block-access tests; stubbing out the safepoint check fails exactly the two safepoint
tests. A test that cannot fail is not evidence.

There is also a test pinning the fact that strict mode off skips the expensive lookup — so anyone
making these checks unconditional has to change a test and decide knowingly, rather than
accidentally putting a locked lookup in a hot path.

## Automated real-client testing

[`tools/client-test.ps1`](tools/client-test.ps1) drives a **real vanilla 1.21.4 client** end to end:
it launches the instance through PrismLauncher, waits for the join, injects real keyboard and mouse
input, asserts on what the server logged, and reports any decode error the client wrote.

    pwsh -File tools/client-test.ps1

This exists because the bot swarm cannot fail the way a client can. It shares the server's own
constants and has no prediction logic, so an entire class of bug is invisible to it by construction
-- it ran green for hours against a world that was completely underwater. Every protocol and
prediction bug in this project so far was found by a human driving a real client. This automates
that.

No account is involved: Prism's `--offline` flag invents a throwaway profile, which is all an
offline-mode server needs, so the launcher's stored session is never touched.

The scenario walks, looks, breaks, places, pick-blocks, drops, opens the inventory and takes an
in-game screenshot, then checks the server log for each resulting action and scans the client log
for decode errors and new disconnect reports. It runs on a **superflat** world on purpose: on noise
terrain the spawn is wherever the generator puts it, and the first run walked into an ocean and
"broke" water, failing for reasons unrelated to the code under test.

**Limits worth knowing.** Input injection needs the game in the foreground, so a run takes over the
screen for about a minute. Assertions are only as good as their patterns -- the first version of the
pick-block check matched `picked up 1 x item 1` from an item entity and passed without a single
middle-click ever being handled, which is the same false-confidence trap the bot swarm falls into.
Anchor patterns on something only the code path under test can produce.

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
