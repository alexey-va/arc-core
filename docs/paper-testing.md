# Paper testing with MockBukkit

`arc-core-paper-testing` is the canonical Paper test-kit for ARC and sibling
plugins. It pins the compatible test runtime pair used by this checkout:

- Paper API `1.21.11-R0.1-SNAPSHOT`;
- MockBukkit `mockbukkit-v1.21:4.110.0`.

The module is a test dependency. It must never be shaded into or added to a
production plugin runtime.

## Add the test-kit

With the normal `includeBuild("../arc-core")` composite:

```kotlin
dependencies {
    testImplementation("ru.arc:arc-core-paper-testing:1.0-SNAPSHOT")
}
```

Pure orchestration tests may additionally consume the platform-neutral
fixtures without starting MockBukkit:

```kotlin
dependencies {
    testImplementation("ru.arc:arc-core-testing:1.0-SNAPSHOT")
}
```

Use `DeterministicClock`, `ControlledExecutor`, and `FailureInjector` to drive
timeouts, queued completions, retries, and recovery failures without sleeps.
Keep Bukkit events, inventories, commands, scheduler ticks, and plugin
lifecycle in `arc-core-paper-testing`.

Public plugins that cannot access the private source composite use the
Java-21-compatible release from RusCrafting Reposilite instead:

```kotlin
repositories {
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content { includeGroup("ru.ruscrafting.arc") }
    }
}

dependencies {
    testImplementation("ru.ruscrafting.arc:arc-core-testing:<release>")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:<release>")
}
```

Do not repeat the MockBukkit coordinate in each plugin. Update the version pair
in the root `gradle.properties` (`paperApiVersion` and `mockBukkitVersion`) and
its compatibility tests once, then consume the published test-kit everywhere.

## Choose the right test layer

| Behavior | Required layer |
|----------|----------------|
| State transitions, allocation, validation, fairness, codecs | Pure Kotlin domain test; no server singleton |
| Bukkit/Paper events, commands, permissions, inventories, scheduler ticks, plugin enable/disable, player/world interaction | MockBukkit through `MockBukkitTestRuntime` |
| APIs explicitly unsupported by MockBukkit, plugin interoperability, NMS/runtime loading, real network/database topology | Narrow seam test plus isolated lab or exact-artifact integration evidence |

A Paper feature normally has both pure tests and MockBukkit tests. MockBukkit is
not a replacement for deterministic domain tests, and a passing MockBukkit test
is not evidence for an API that its exact version does not implement.

## Canonical Kotest pattern

Open one runtime per test and let Kotlin `use` own teardown:

```kotlin
class JoinListenerTest : FunSpec({
    test("join initializes the player and schedules the greeting") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<MyPlugin>()
            val player = paper.addPlayer("Agent")

            paper.server.pluginManager.isPluginEnabled(plugin) shouldBe true
            paper.performTicks(20)
            // Assert the observable message, inventory, event, or repository effect.
        }
    }
})
```

Use `loadPlugin<T>()` for a real plugin so descriptor loading and enable
lifecycle are exercised. `loadSimplePlugin<T>()` is reserved for small test-only
`JavaPlugin` implementations that intentionally have no descriptor.

`MockBukkitTestRuntime` also provides:

- `server` for exact MockBukkit APIs;
- `addPlayer` and `addSimpleWorld` fixtures;
- `callEvent` returning the same event for cancellation/state assertions;
- `performTicks` for deterministic delayed and repeating task behavior;
- idempotent `close`, including plugin disable and scheduler shutdown.

MockBukkit owns the process-global Bukkit singleton. Never share one runtime
between tests or run tests that own it concurrently in the same JVM. A nested
runtime fails immediately with an ownership error rather than silently leaking
players, listeners, tasks, or plugins across cases.

## What a strong platform test proves

For every affected Paper flow, cover the observable contract rather than a
private implementation method:

1. Load and enable the plugin or the narrow owning module.
2. Create the exact player/world/inventory/permission precondition.
3. Dispatch the real Bukkit event or command path.
4. Advance scheduler ticks explicitly when behavior is deferred.
5. Assert both the intended effect and forbidden side effects.
6. Exercise cancellation, invalid permission/input, quit/disable/reload cleanup,
   and repeated delivery when idempotence matters.
7. Close the runtime and let teardown surface leaked asynchronous failures.

For GUI tests, assert title, slots, item names/lore, click cancellation,
navigation, pagination boundaries, and cleanup after close/quit. For listeners,
assert priority/cancellation semantics and the resulting player or repository
state. For scheduled behavior, assert immediately before, exactly at, and after
the target tick.

## Unsupported operations

MockBukkit is an exact-version test double with deliberate gaps. When it throws
an unsupported-operation exception or cannot reproduce Paper behavior:

- do not delete the production call;
- do not introduce a production fallback solely for the test;
- do not mark the test ignored or treat the missing mock as a pass;
- isolate the verified Paper call behind the narrowest injected seam;
- test domain and orchestration semantics through that seam;
- retain a lab or controlled real-runtime check for the platform operation and
  report that layer separately.

The local authoritative API evidence for this contract is the resolved
MockBukkit `4.110.0` artifact manifest, which declares Paper API
`1.21.11-R0.1-SNAPSHOT`, plus the matching Paper API artifact used by the
module. Review those exact artifacts before adopting a version-sensitive helper
or simulation API.

## Verification

```bash
./gradlew :arc-core-paper-testing:test
./gradlew :arc-core-paper:test
./gradlew :arc-core-testing:test
./gradlew testAll publishToMavenLocal
```

The second command proves that a separate module consumes the test-kit. The
complete gate proves that the additional published artifact does not break the
rest of arc-core.
