# ARC Prometheus metrics

`arc-core-metrics` owns the platform-neutral registry, cached gauges, JVM/OS
sampling, and the localhost HTTP endpoint. `arc-core-paper` and
`arc-core-velocity` add platform snapshots; ARC and ProxyARC only bind those
collectors to plugin lifecycle and configuration.

## Performance model

- Prometheus scrape reads cached Micrometer values only. It never calls Paper,
  Velocity, filesystem, or MXBean APIs.
- Cheap JVM and platform values refresh every 5 seconds.
- Disk and world-level values refresh every 60 seconds.
- Paper world metrics use constant-time counters such as `entityCount` and
  `chunkCount`; they do not enumerate entities or chunks.
- Dynamic labels are bounded to known worlds, backends, GC pools, buffer pools,
  and enum states. Player names and UUIDs are never labels.
- Sampling failures are isolated and exported instead of stopping the plugin.

Intervals are clamped to safe ranges: cheap sampling 2–60 seconds and heavy
sampling 15–600 seconds.

## Metric groups

| Group | Important series |
|---|---|
| Runtime | `arc_application_info`, uptime/start time, available processors |
| JVM | heap/non-heap, threads, classes, GC, buffer pools, deadlocks |
| Process/host | CPU load/time, physical RAM, swap, virtual memory, file descriptors |
| Disk | total/usable/unallocated bytes for the plugin-data filesystem |
| Paper fast | TPS, tick duration/state/rate, players, loaded worlds |
| Paper heavy | per-world entities/chunks/block entities/players/distances/spawn limits, plugin/task counts |
| Velocity | proxy/backend players, registered servers, plugins/tasks, login/disconnect/backend counters |
| Modules | per-module readiness, lifecycle failures, init/reload/shutdown duration |
| Redis | connectivity/subscription/channels, bounded operation latency/results, reconnect outcomes |
| Health | cached/stale series, registry size, sample duration/failures/timestamp, scrape count/failures/duration |

Every series carries stable `application`, `platform`, and `server_name` tags.
The endpoint also exposes `/health` for a cheap readiness probe.

## Runtime endpoints

Paper binds only to localhost and is exposed through restricted nginx listeners
on Gercena. ProxyARC binds to `127.0.0.1:9950`; Velocity's existing
Prometheus-only nginx listener maps `/proxyarc/metrics` to it. No plugin metrics
endpoint is exposed directly to the internet.
