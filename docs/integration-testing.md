# Redis and MySQL integration testing

`arc-core-integration-testing` is the canonical real-storage test harness for
ARC sibling plugins. It owns Testcontainers startup, random host ports,
readiness, endpoint projection, and idempotent cleanup. The artifact is
test-only and must never be shaded into a production plugin.

## Dependency

With the source composite:

```kotlin
dependencies {
    integrationTestImplementation("ru.arc:arc-core-integration-testing:1.0-SNAPSHOT")
}
```

Published consumers use
`ru.ruscrafting.arc:arc-core-integration-testing:<release>`.

## Redis

```kotlin
RedisTestService.start().use { redis ->
    val client = RedisManager(connectionTo(redis.endpoint))
    // Exercise the real codec, pub/sub, CAS, reconnect, and cleanup path.
}
```

Core's `RedisNetworkLayerIntegrationTest` is the canonical application-layer
seam: two `RedisManager` instances prove correlated request/reply through real
pub/sub, then publish and refresh a real hash-backed presence lease. Consumer
integration tests keep their domain DTO and authorization policies, but reuse
the same `RedisTestService` rather than recreating container setup.

The default is a pinned Redis image. Pass an explicit image only when a test
intentionally verifies another supported server version. Always consume
`endpoint.host` and `endpoint.port`; never assume localhost or 6379.

## MySQL

```kotlin
val settings = MySqlTestSettings(
    image = "mysql:8.4.10",
    database = "plugin_test",
    username = "plugin_test",
    initScripts = listOf(MySqlInitScript("mysql/schema.sql", order = 10)),
)

MySqlTestService.start(settings).use { mysql ->
    val storage = PluginStorage.open(mysql.endpoint.jdbcUrl, mysql.endpoint.username, mysql.endpoint.password)
    // Assert migrations, durable readback, contention, and restart recovery.
}
```

Init scripts are bounded classpath `.sql` resources copied in explicit order.
If the production storage owns migrations, prefer opening that storage against
an empty database instead of maintaining a parallel test schema.

## Required cases

A storage integration suite covers the real happy path plus the dangerous
boundaries relevant to that plugin: duplicate/idempotent writes, reconnect or
restart, corrupt/incompatible schema rejection, contention/CAS behavior, and
recovery records surviving a fresh client instance. Use pure property and
failure-injection tests for exhaustive state-machine combinations; containers
prove the driver/server seam.

Containers require a reachable Docker-compatible daemon. On local Colima:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew integrationTest
```

Never skip a failed container test as a passing result. Report an unavailable
daemon separately from a verified storage contract.

## CI contract

Run container suites in a dedicated CI job, separate from unit/property tests,
on every pull request and trunk push. The GitHub-hosted Ubuntu runner exposes
Docker directly, so CI must not set Colima-specific environment variables.

The core jobs are deliberately stable and agent-readable:

```bash
./gradlew testAll
./gradlew integrationTestAll
```

Sibling plugins keep the same split with `test` and `integrationTest`. A tagged
core release repeats both gates before publishing, even when branch protection
already required the CI jobs. A missing Docker daemon is a failed integration
job, never a skipped or successful suite.
