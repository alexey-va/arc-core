# Shared Paper SQL, Redis and technical metrics

The ARC host registers the service APIs from `arc-core-paper-api` once per
Paper JVM. Consumer JARs use that API as `compileOnly`, depend on ARC, and keep
local framework lifecycle/configuration objects. Do not share `Tasks`,
`ConfigManager` or `ModuleRegistry` across plugin classloaders.

## SQL

Install `PaperSqlServices.installConsumer(plugin)` before any
`SqlRuntime.create` or SQL store `open`. Own the returned binding before owning
stores so reverse-order shutdown closes stores first. Calls from nested core
stores use the same plugin-local factory. Missing service fails at acquisition;
it never silently opens a private pool. Standalone and Velocity callers retain
the existing owned-runtime default.

`ArcSqlService.acquire(owner, settings)` returns a scoped `DataSource`, normal
executor and reserved durable-completion executor. Equal complete settings
share one physical pool and both executor lanes. Credentials, database, SSL
mode, timeouts and sizing all participate in equality. Owner names do not.
Different databases or credentials are separate pools, even on the same host.
A service lookup does not create a database connection.

Closing a lease rejects new work from that lease and leaves other consumers
usable. Accepted tasks, accepted durable completions and checked-out connections
hold the group alive until they drain. One-time-use ledgers retain their
separate completion lane to avoid starvation when normal workers wait for pool
connections. Advisory-lock release failure evicts the real provider-owned
connection rather than returning an uncertain lock to the pool.

Consumer release is nonblocking. Host shutdown waits at most five seconds in
total for accepted work, checked-out connections and physical pool cleanup.
On timeout it reports aggregate remaining counts and lets safe background drain
continue; it never forcibly closes a connection still used by another operation.

Schema migrations, transaction boundaries, namespaces and repositories remain
consumer responsibilities. No data is moved. Sharing the same capacity reduces
the total connection ceiling compared with N private pools; observe in-flight
work, latency and database load before changing operator limits.

## Redis

Use `PaperRedisClient.open(plugin, connection, origin)` for an explicit profile.
Use `openPrimary(plugin)` only when the feature intentionally inherits ARC's
primary connection and server origin. The host configures that profile without
exposing its credentials through diagnostics.

Complete connection settings and origin define a physical group. Each lease
owns its channel listeners. `registerChannelUnique` replaces only that lease's
listener; another plugin's listener survives replacement, unsubscribe and close.
The provider multiplexes shared channels and reconciles subscription topology
without restarting other consumers' subscription on every `init`.

The existing `RedisOperations`, codecs, replay policies, request/reply timeouts
and domain validation remain in the consumer. A shared transport is not shared
domain state. A changed primary profile affects new leases; already acquired
leases keep their original group until consumer reload/close. Origin changes
must not rewrite live borrowers' wire identity.

## Technical metrics

`PaperArcMetrics` performs optional lookup of `ArcMetricsService`. Open one
stable owner scope and declare counters, gauges and timers through the API.
Close the scope with the plugin. Micrometer and the Prometheus listener remain
inside the host; plugins do not construct registries or bind additional ports.

Choose descriptive metric names and stable, bounded tag values. Never use
player names/UUIDs, requests, payloads or errors as tags. Use a stable feature
prefix to avoid name/type conflicts. Scope close removes only its meters and
makes late handles inert. Registry reload preserves handles and the latest
gauge values; counters/timers begin in the new registry. Samples without an
active registry are dropped rather than queued. Metrics availability must not
control a durable gameplay action.

`open(ownerId)` keeps the default 128-series budget. A consumer with a known,
bounded larger label space may call `open(ownerId, maxMetrics)` for 1–4096
series. The provider still caps owners at 64 and aggregate handles at 8192
(64 × the default 128), so explicit scope budgets do not increase the prior
provider-wide resource ceiling. Closing a scope releases its aggregate budget.

Existing domain telemetry (`ArcTelemetryProvider`) has its own event and
storage contract. It is not replaced by arbitrary technical counters.

## Release and verification

The host and every migrated consumer must use the same new API release on each
server. Only the host embeds `ru/arc/paper/api/**`; consumers compile against it.
Publish the immutable core version, build consumers from public coordinates,
check JAR ownership and descriptor dependency order, then activate the matched
batch. Do not hot-replace ARC while consumers retain leases from its classloader.

Tests cover compatible sharing, incompatible-group isolation, borrower close,
queued work drain, durable completion saturation, listener replacement,
registry reload, and classloader boundaries. Verify SQL and Redis behavior
against disposable real services, and read provider counts and current startup
logs after activation. Pool counts establish sharing; they do not establish a
measured reduction in JVM RSS by themselves.
