# Shared Paper schematics

`ArcSchematicService` is the shared boundary for schematic files, compact
immutable block snapshots, transforms and bounded native block placement.
The optional `arc-core-paper-schematics` module integrates WorldEdit; consumers
use the `ArcSchematic*` interfaces in `arc-core-paper-api`.

The ArcCore plugin installs one `PaperSchematicServiceProvider(plugin, settings)`
after WorldEdit is enabled, and closes it on shutdown. Installing the provider
does not inspect the filesystem or initialize a schematic library. The module
does not install a global `Tasks` scheduler. WorldEdit is a provided dependency,
not bundled into core. The implementation compiles against WorldEdit 7.3.18 and
Paper 1.21.11; native integration was also exercised with WorldEdit 7.4.2.

## Consumer ownership

Resolve the host service through Paper's services manager, open one lease with
an explicit configured root, and close the lease with the consumer lifecycle:

```kotlin
val service = requireNotNull(
    plugin.server.servicesManager.load(ArcSchematicService::class.java),
)
val library = service.open("ArcBuilder", configuredSchematicRoot)

library.load("civs/house", ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
    .thenCompose { snapshot ->
        library.transform(snapshot, ArcSchematicTransform(1, false))
    }
    .whenComplete { snapshot, problem ->
        // Marshal player/world/UI access onto the consumer's Paper lifecycle.
        // Recheck the request generation before installing the result.
    }

// On consumer shutdown:
library.close()
```

The root belongs to the consumer configuration. Existing roots such as
`../ARC/schematics` remain valid; opening a lease only normalizes the path.
Do not create another cache/executor per plugin or scan the root at startup.
All leases share one provider cache, admission queue, worker pool and tick
budgets. There are at most 256 simultaneously open leases.

IDs are case-sensitive relative paths, normally without an extension:
`civs/house`, `minecolonies/townhall`. `ArcSchematicIds.isValid(id)` is the shared
pure validator for catalogs and persisted domain records. It rejects absolute
paths, backslashes, colons, control characters, empty segments, `.` and `..`,
and IDs longer than 160 characters. The resolver permits a symlink for the
configured root and rejects symlinks below that resolved root.

Legacy explicit `.schem` and `.schematic` suffixes select the exact file.
An extensionless ID checks both suffixes and rejects an ambiguous pair. The
actual format must be recognized by the installed WorldEdit reader. Files are
gzip NBT; unsupported/malformed payloads fail before a snapshot is exposed.

## Asynchronous work and thread boundaries

`load`, `loadVerified`, `transform` and `invalidate` always dispatch through a
bounded worker queue, including cache hits. Never call `join()` or `get()` on
Paper's primary thread. A completion callback has no promised thread affinity;
schedule Bukkit/player/world operations explicitly in the consumer lifecycle.

File resolution, reading, gzip/NBT preflight, WorldEdit decoding, full-volume
geometry, hashes and retained-data weighing run on workers. WorldEdit's Bukkit
adapter may populate native block-state caches, so exact Bukkit string
canonicalization and native mirror/rotation happen on the primary thread.
One shared pump processes at most `palettePerTick` palette entries across all
pending work. It does not materialize every cell as a Bukkit object.

A heavy-work permit remains occupied through decode or transform, waiting for
palette preparation, and final snapshot/hash creation. At most `workerThreads`
heavy pipelines are active; other admitted work waits in a bounded FIFO. This
prevents many decoded clipboards accumulating while the primary-thread palette
budget catches up. Ordinary matching file loads coalesce. Verified loads read
their own bytes, then may reuse a decode of those exact bytes.

## Snapshot and identity contracts

Snapshots expose minimum, maximum and source origin, dimensions, volume,
`nonBarrierCount`, source-extra metadata, file SHA-256 and content SHA-256.
Air remains present. `nonBarrierCount` includes air. `blockAt` is constant-time;
`blocks()` is an immutable indexed view which creates value objects on demand.
It does not retain a block object for every cell. Full traversal is CPU work
and belongs on a worker.

The retained representation is a palette, an integer cell array and opaque
immutable block-entity NBT. No mutable WorldEdit `Clipboard`, Bukkit `BlockData`
or native object crosses the public API. A consumer may keep a snapshot after
cache eviction or lease close; it must release that reference when its preview
or project ends. Cache bounds cannot limit objects deliberately held by callers.
Transformed snapshots belong to the caller and are not added to a transform
cache. A provider rejects snapshots created by another provider.

`load` compares the resolved file's size, modification time and file identity
before using a cached snapshot. This is an ordinary cache freshness check, not
proof against a replacement which deliberately preserves those attributes.
`loadVerified(id, expectedFileSha256, policy)` always reads and hashes fresh
bounded bytes and either returns that exact version or fails `HASH_MISMATCH`.
It never combines a new file hash with an older cached clipboard. A changed
identity or byte count during acquisition fails `FILE_CHANGED`; retry from the
domain's current request state. Fingerprints use lowercase 64-character hex.

The content hash preserves the historical ArcBuilder player-book identity:

```text
sizeX:sizeY:sizeZ\n
dx:dy:dz:Bukkit BlockData.asString\n
...
```

Rows contain only non-air cells, relative to the minimum, ordered by Y, X, Z.
All Bukkit materials for which `isAir` is true are omitted; barriers are
included. Origin and opaque block-entity NBT are excluded. This hash describes
the block-state representation, while the file hash identifies all source
bytes. Player ownership, origin metadata, file naming and signature policy
remain consumer responsibilities.

Transforms mirror local X first, then rotate around the source origin. One
positive quarter turn maps `(x,y,z)` to `(-z,y,x)`. The returned snapshot uses
origin-relative positions and origin `(0,0,0)`. Native block properties rotate
and mirror with the geometry; NBT is preserved unchanged. Arbitrary embedded
coordinates inside opaque custom NBT are not rewritten. The file hash remains
the source fingerprint; the content hash is recomputed for the returned view.

## Content policy

The overload without a policy selects `REQUIRE_BLOCKS_ONLY`. It rejects a
source containing entities or biome data. A consumer whose established behavior
is block extraction must explicitly select `IGNORE_ENTITIES_AND_BIOMES`.
Both policies preserve block-entity NBT; neither policy pastes entities or
biomes. `hasBiomes` and `entityCount` report the original source extras even
when the consumer explicitly ignores them.

Input limits are checked before WorldEdit allocates its clipboard: compressed
file bytes, decompressed NBT bytes, declared arrays/lists, nesting depth and
schema dimensions. An aggregate node budget charges 64 bytes per NBT tag/list
element against `maximumInflatedBytes`, preventing empty compounds and short
values from amplifying object allocation in the decoder. Opaque string values
are skipped without reinterpretation during preflight. Root and Sponge V3
nested schematic dimensions are checked
independently, so an outer duplicate cannot hide an oversized inner schema.
WorldEdit must have its initialized server registry; an empty standalone test
registry is not a valid production decode environment.

## Native paste and connection repair

`paste(snapshot, target, options)` prepares on a worker and writes on Paper's
primary thread. The target anchor is where the snapshot origin is placed.
The caller explicitly chooses whether to skip air/barriers and whether to run
connection repair after placement. Block-entity NBT follows its cell. Paste is
block-only and supplies no protection checks, payment, undo or durable journal.
Those decisions remain in the gameplay plugin.

All target chunks must already be loaded. The service validates every target
chunk before the first write and rechecks a cell's chunk before using it.
It never synchronously loads or generates chunks. Consumers that need chunk
lifetimes should acquire them asynchronously and use the shared
`PaperChunkTicketRegistry` for the operation's lifetime. A target outside the
world build height is rejected. If a chunk disappears later, the operation
fails; its exception message includes partial change/skip counts. No implicit
rollback occurs.

`repairConnections(worldId, blocks)` accepts expected block-state strings at
WORLD coordinates. Pass a stable list and do not mutate it while the request is
pending. It touches only native fences, walls and glass panes, and skips a
position whose current state differs from the expected state. It uses native
shape validation without recursive neighbour physics. It does not repair
adjacent positions outside the supplied scope. For durable building workflows,
invoke repair only after the full transaction is committed so repaired shape
properties cannot invalidate intermediate journal comparisons.

Paste and repair share one aggregate `blocksPerTick` budget. Chunk validation,
cell skips and repair visits also consume that budget. Mutation counts are
write/skip counts, not necessarily distinct positions: optional repair can
write a position again.

`ArcSchematicOperation.cancel()` is the supported cancellation path. It signals
queued preparation to stop; when native work has started, it waits at most for
the current bounded batch. Supported cancellation completes with `cancelled=true`.
Provider-produced terminal completion means no later writes from that
operation. Treat `completion` as read-only; directly completing or cancelling
its `CompletableFuture` is not operation cancellation. Cancellation and failure
do not undo already applied blocks.

## Bounds and lifecycle

`ArcSchematicCacheSettings.defaults()` selects these host-wide values:

| Setting | Default |
| --- | ---: |
| `ttlMillis` | 120,000 |
| `maximumEntries` | 64 |
| `maximumCachedBytes` | 134,217,728 |
| `maximumFileBytes` | 16,777,216 |
| `maximumInflatedBytes` | 67,108,864 |
| `maximumVolume` | 2,000,000 |
| `maximumPendingRequests` | 64 |
| `workerThreads` | 2 |
| `palettePerTick` | 128 |
| `blocksPerTick` | 512 |

The cache evicts least-recently-used entries and expires unused entries with a
monotonic clock. Its conservative retained-data weight includes cells, palette
strings and block-entity NBT; it is not an exact JVM heap measurement. A single
snapshot heavier than the cache budget is returned without being cached.
Failures are not cached. Active decode/transforms and caller-owned snapshots
are separate from retained cache weight and have the bounds described above.

When request admission is full, the future fails `BUSY` without blocking the
caller. Closing a lease immediately terminates its client futures and cancels
its mutations without closing another consumer. A cancelled or closed client's
admission ticket may remain until bounded shared work finishes; cancellation
cannot refill an unbounded queue or kill another client sharing that decode.

`invalidate(id)` drops matching cache entries and prevents outstanding work
from repopulating the cache for that generation. It does not revoke already
returned immutable snapshots or promise cancellation of an earlier request.
It also works after the final file was removed while its root still exists.

Provider close is terminal: it stops admission, clears cache/queues, terminates
client and pending palette futures, cancels mutations and timers, and interrupts
workers without joining them on the primary thread. A late result cannot
repopulate the cache. Consumer callbacks never run under the cache monitor.

## Verification

Run the focused guard, identity, lifecycle and scheduling tests:

```bash
./gradlew :arc-core-paper-api:test :arc-core-paper-schematics:test \
  :arc-core-paper-schematics:assertKotlinOnly
```

The deterministic codec seam tests resource boundaries without inventing a
WorldEdit registry. Real server acceptance separately checks exact native
Bukkit strings/content hash, all eight mirror/rotation states, native paste,
connection repair, aggregate tick limits and optional WorldEdit absence.
Mock or source checks alone do not establish those native behaviors.
