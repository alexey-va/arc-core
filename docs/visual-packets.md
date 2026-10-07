# Server-wide visual packet budget

`PaperVisualPacketRuntime` is the ARC-hosted admission service for opt-in,
replaceable plugin visuals such as client-only displays, glow changes, and short
visual effects. It is not a limiter for vanilla gameplay traffic. Packet owners
must keep their existing latest desired state and retry a deferred transaction;
the shared runtime does not queue packet history.

## Host setup

Install one provider from ARC on the server thread after core scheduling is
installed and before any packet owner is constructed:

```kotlin
val visualPacketRuntime = PaperVisualPacketRuntime.install(this)
```

The default config is loaded through `ConfigManager` from
`plugins/ARC/modules/visual-packets.yml`. ARC should close the returned runtime
after its visual owners have stopped. `PaperVisualPackets` resolves the provider
through Bukkit's shared `ArcVisualPacketBudget` service and fails fast if ARC did
not install it.

The host embeds `arc-core-paper-api`; other plugins compile against that API and
load it through ARC's classloader. A plugin that constructs these visual owners
unconditionally must declare ARC in `depend`, and deployments must activate the
new host together with upgraded consumers. No plugin creates a private fallback
budget. Use feature names on `PaperPacketDisplays` and `PaperViewerEntityGlow` to
keep traffic attribution useful.

On config reload, reload the `Config` from the same module path and call
`visualPacketRuntime.reload(config)`. The full candidate is validated before
application. Existing server and viewer token balances are refilled under the
old rates, then retained under the new rates; reload never restores a full burst.
Reload requires at least one recognized setting. Empty or unreadable configuration
must not silently replace active limits with defaults; missing individual keys
still use their documented defaults.

## Settings

| Key | Default | Allowed range | Meaning |
| --- | ---: | ---: | --- |
| `server-mbps` | `32` | `0.01`–`10000` | Shared server payload budget in decimal Mbit/s. |
| `viewer-mbps` | `2` | `0.01`–`10000` | Shared payload budget per connection, across all plugins. |
| `burst-ms` | `250` | `10`–`30000` | Token bucket capacity as rate multiplied by this duration. |
| `log-interval-seconds` | `30` | `30`–`3600` | Interval for async metrics logging and bounded overload warnings. |
| `verbose-logs` | `false` | boolean | Emit interval totals and one line for each active source. |

Overload warnings are emitted at most once per configured reporting interval,
with one recovery message after pressure subsides. Source names are bounded to
256 buckets, including `_other_`; use stable names such as
`arccasino:slot-displays`, never player, world, or session identifiers.

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

`PaperViewerParticles` handles the opposite case: a one-shot viewer-only dust
effect. The caller selects the audience and cadence; the owner captures only
immutable coordinates, color, size and offsets, attempts one `PaperVisualPackets`
write, and drops pressure or closed-channel outcomes without retrying. `close()`
and `invalidatePending()` prevent an already-enqueued event-loop task from
sending after its owner or session/target has ended.

`snapshot()` exposes bounded cumulative counters and current settings.
`metricPoints()` returns aggregate gauges and three series per source (bytes,
packets, deferrals) for an existing host metrics collector. Source labels are
stable plugin/feature names, capped at 256, never player IDs or coordinates;
the runtime owns no separate registry. Periodic reporting uses
the async lifecycle task scope and does not perform per-packet logging or disk I/O.
