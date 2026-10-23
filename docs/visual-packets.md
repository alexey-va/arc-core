# Server-wide visual packet budget

`PaperVisualPacketRuntime` is the ArcCore-hosted admission service for opt-in,
replaceable plugin visuals such as client-only displays, glow changes, and short
visual effects. It is not a limiter for vanilla gameplay traffic. Packet owners
must keep their existing latest desired state and retry a deferred transaction;
the shared runtime does not queue packet history.

## Host setup

ArcCore installs one provider on the server thread before any packet owner is
constructed. The provider owns its reporter scheduler and requires no ARC plugin
or global `Tasks` installation. Host wiring supplies its validated configuration:

```kotlin
val visualPacketRuntime = PaperVisualPacketRuntime.install(this, hostConfig)
```

ArcCore owns `plugins/ArcCore/visual-packets.yml`. On first startup it imports an
existing `plugins/ARC/modules/visual-packets.yml` only if the host file is absent;
otherwise it seeds bundled defaults. Existing host policy is never overwritten.
ArcCore closes the runtime after its consumers stop. Consumer shutdown or a
consumer scheduler reset must not close the shared provider. `PaperVisualPackets`
resolves Bukkit's shared `ArcVisualPacketBudget` service and fails fast if ArcCore
did not install it.

ArcCore embeds the exact `ArcVisualPacketBudget` and `VisualPacketAdmission` API
classes, `PaperVisualPacketRuntime` and its nested classes, and the `MetricPoint`
DTO. Consumers exclude those classes from their JARs and load them through
ArcCore's classloader. A plugin that constructs these visual owners
unconditionally declares ArcCore in `depend`. The owner migration requires a
coordinated ArcCore/ARC restart: older ARC versions still try to install a second
provider. No plugin creates a private fallback budget. Use feature names on
`PaperPacketDisplays` and `PaperViewerEntityGlow` to keep attribution useful.

ARC's reload delegates to `ArcCoreServices.reloadVisualPacketConfig()`, which
reloads the canonical host file. The host passes its own `Config` to
`visualPacketRuntime.reload(config)`; consumer-local `Config` instances must not
cross plugin classloaders. The full candidate is validated before application.
Existing server and viewer token balances are refilled under the
old rates, then retained under the new rates; reload never restores a full burst.
Removed source exceptions return to the ordinary shared viewer bucket; any debt
is transferred to that bucket and the obsolete source bucket is discarded.
New exceptions on existing connections start empty and refill at their configured
rate. Connection state remains weakly owned, with at most 256 configured source
buckets plus the ordinary bucket per connection.
Reload requires at least one recognized setting. Empty or unreadable configuration
must not silently replace active limits with defaults; missing individual keys
still use their documented defaults.

## Settings

| Key | Default | Allowed range | Meaning |
| --- | ---: | ---: | --- |
| `server-mbps` | `32` | `0.01`–`10000` | Shared server payload budget in decimal Mbit/s. |
| `viewer-mbps` | `2` | `0.01`–`10000` | Shared payload budget per connection for sources without an exception. |
| `source-viewer-mbps` | `{}` | Up to 256 exact stable source names, each `0.01`–`10000` | Separate per-connection payload bucket for each explicitly configured source; every write still charges `server-mbps`. |
| `burst-ms` | `250` | `10`–`30000` | Token bucket capacity as rate multiplied by this duration. |
| `log-interval-seconds` | `30` | `30`–`3600` | Interval for async metrics logging and bounded overload warnings. |
| `verbose-logs` | `true` in the bundled host policy | boolean | Emit interval totals and one line for each active source; the library fallback for a missing key remains `false`. |

Overload warnings are emitted at most once per configured reporting interval,
with one recovery message after pressure subsides. Source names are bounded to
256 buckets, including `_other_`; use stable names such as
`arccasino:slot-displays`, never player, world, or session identifiers.

Exceptions use the complete source label produced by the existing gateway. They
are exact matches, not prefixes or wildcards. Unknown sources share the ordinary
viewer bucket. The Core library and host ship with no exceptions; an operator
can opt in one known source in the canonical `plugins/ArcCore/visual-packets.yml`:

```yaml
server-mbps: 32.0
viewer-mbps: 2.0
source-viewer-mbps:
  'example-plugin:video-wall': 24.0
```

This allows that source up to 24 Mbit/s for one connection while unrelated
visuals still share 2 Mbit/s. It does not reserve bandwidth or bypass the shared
32 Mbit/s server limit, channel writability, real encoded-byte accounting or
cleanup ordering. Multiple viewers and configured sources compete for the same
server bucket. Cleanup debits the selected viewer bucket and the server bucket.

On activation and every successful reload, `ARC_VISUAL_PACKETS status=policy`
logs the effective server/viewer limits, burst and exception count. A bounded
`status=source_policy` line per configured exception records its exact source and
viewer limit, even when verbose interval logs are disabled or no packets are sent.
The existing `snapshot().settings` also contains the immutable effective map.

## Packet owner contract

Construct a `PaperVisualPackets(plugin, "feature-name")` on the server thread,
then call `write(connection, packets, cleanup)` from that connection's Netty event
loop. The gateway encodes the complete transaction once and budgets its actual
readable payload bytes before compression, framing, encryption, and TCP overhead.
The reported Mbit/s is therefore an application payload rate, not a wire-rate or
client-load measurement. A rejected transaction is released and must not be
treated as delivered. Spawn plus initial metadata should remain atomic. Cleanup
transactions bypass rate admission so removals can finish, but still wait for a
writable channel and debit their full payload cost from both buckets.
Custom latest-state transports can use `PaperVisualPackets.retry` for the shared
50 ms event-loop retry; keep one pending retry per connection, not per packet.
Native passenger/pose/vehicle-visibility corrections remain ordered with their
gameplay packets and are outside the replaceable cosmetic frame budget.

`PaperViewerParticles` handles the opposite case: a one-shot viewer-only cosmetic
effect. The caller selects the audience and cadence. On Paper the owner captures
coordinates, type and payload: no data, `DustOptions`, `DustTransition`, ARGB
`Color`, a `BlockData` state ID, or a copied PacketEvents item converted from a
cloned Bukkit `ItemStack`. Netty constructs and encodes the packet without Bukkit
reads, attempts one `PaperVisualPackets` write, and drops pressure or closed-channel
outcomes without retrying. This is best-effort cosmetic delivery, not a reliable
queue for gameplay warnings or acknowledgements. `close()` and
`invalidatePending()` prevent an already-enqueued event-loop task from sending
after its owner or session/target has ended. No native fallback bypasses admission.

Use explicit `emit(viewer, location, particle, count, offsetX, offsetY, offsetZ,
extra, data, force)` for full native parameters; overloads omit `force`, then
`data`, or all offsets/speed/data. They do not use Kotlin default-argument bridges.
`count=0` retains directional semantics. The short `emit` uses speed `1.0` and
`force=false`; the retained `dust` signature keeps its original speed `0.0` default.
`force` controls the packet's long-distance flag, not viewer selection or fan-out.
Unsupported or mismatched payloads fail before dispatch.

Defaults and flags follow the pinned Paper 1.21.11 API and
[CraftPlayer](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java);
[CraftWorld](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/CraftWorld.java)
overrides its no-force endpoint with `false` even though the World interface's
default method forwards `true`. Migrating world effects must preserve the original
receiver/range policy separately. Payload mappings match PacketEvents 2.12.1 and
[CraftParticle](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/CraftParticle.java).

`snapshot()` exposes bounded cumulative counters and current settings.
`metricPoints()` returns aggregate gauges and three series per source (bytes,
packets, deferrals) for an existing host metrics collector. Source labels are
stable plugin/feature names, capped at 256, never player IDs or coordinates;
the runtime owns no separate registry. Periodic reporting uses
the async lifecycle task scope and does not perform per-packet logging or disk I/O.
