# Paper testing with MockBukkit

`arc-core-paper-testing` is the canonical Paper test-kit for ARC and sibling
plugins. It pins the compatible test runtime pair used by this checkout:

- Paper API `1.21.11-R0.1-SNAPSHOT`;
- MockBukkit `mockbukkit-v1.21:4.116.3`.

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

Wrap the complete scenario in `failOnUnsupportedMockBukkitOperation { ... }`.
MockBukkit models an unimplemented API as a JUnit assumption abort, which test
engines report as skipped. The ARC guard converts that condition into a real
failure so a green build cannot hide an unexecuted scenario.

`MockBukkitTestRuntime` also provides:

- `server` for exact MockBukkit APIs;
- `createSimplePlugin(name)` when a scheduler/listener seam needs a named
  descriptor-free plugin without inventing a test subclass;
- `addPlayer` and `addSimpleWorld` fixtures;
- `callEvent` returning the same event for cancellation/state assertions;
- `performTicks` for deterministic delayed and repeating task behavior;
- `playerDataSaveCount` and `adventureTitles` for patched Paper observations;
- idempotent `close`, including plugin disable and scheduler shutdown.

MockBukkit owns the process-global Bukkit singleton. Never share one runtime
between tests or run tests that own it concurrently in the same JVM. A nested
runtime fails immediately with an ownership error rather than silently leaking
players, listeners, tasks, or plugins across cases.

Use two deliberate port layers when Paper delivery and feature meaning are not
the same thing:

- the plugin owns a semantic port named after the feature action;
- `arc-core-paper` owns the exact reusable Paper call or lifecycle;
- the plugin's native adapter composes the two;
- tests replace the semantic port for domain behavior and use the matching
  `arc-core-paper-testing` recorder when the exact delivery matters.

For example, giveaways decide what a countdown means; Core only delivers the
Adventure payload. This avoids both one callback per effect and a generic
platform context that leaks Paper mechanics into the service:

```kotlin
interface GiveawayAudience {
    fun showCountdown(player: Player, seconds: Int)
}

class PaperGiveawayAudience(
    private val effects: PaperAudienceEffects = NativePaperAudienceEffects,
) : GiveawayAudience {
    override fun showCountdown(player: Player, seconds: Int) {
        effects.showTitle(player, countdownTitle(seconds))
    }
}

class GiveawayService(private val audience: GiveawayAudience) {
    fun tick(player: Player, seconds: Int) = audience.showCountdown(player, seconds)
}
```

`PaperAudienceEffects`, `PaperTeleportExecutor`, and
`PaperPlayerDataPersistence` have recording implementations in the test-kit.
`PaperChunkTicketRegistry` exposes an injectable exact backend because its
reference counting, borrowed-ticket ownership, uncertain release, and close
retry are Core lifecycle behavior rather than MockBukkit behavior.

`MockBukkitTestRuntime` also installs test-JVM compatibility patches before it
opens the server. Byte Buddy retransformation replaces only existing method
bodies and remains compatible with JaCoCo/other class transformers. The current
patch set supplies deterministic test semantics for `Player.saveData`, item
`effectiveName`/hover events, block passability, and `teleportAsync`. An ARC
`PlayerMock` subclass records Adventure titles because that method is an
inherited default and cannot be added safely by HotSwap. These patches belong
only to `arc-core-paper-testing`; no consumer production artifact contains the
agent or patched classes.

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

Do not treat an ARC compatibility body as proof of CraftBukkit internals. For
example, the patched `saveData` records the call but does not write a player DAT
file, and patched `teleportAsync` delegates to MockBukkit's synchronous
teleport. Exact persistence, chunk loading, packet rendering, NMS, and plugin
interoperability still require a real Paper E2E lane.

Before writing a compatibility patch, classify the missing operation:

1. Check the pinned Paper and MockBukkit versions and inspect the resolved
   sources/signature. Upgrade the shared pair when the current release already
   implements the operation compatibly.
2. If multiple plugins need the same exact Paper mechanism, or it owns shared
   lifecycle/safety rules, add a typed production port to `arc-core-paper` and
   the recorder or exact compatibility body to `arc-core-paper-testing`.
3. If the behavior is feature-shaped (farm blocks/entities, giveaway state,
   arena rules), keep a small semantic port and native adapter in that plugin;
   put its fake or MockBukkit adapter in `src/test`.
4. Bytecode-patch only an existing, exact MockBukkit method body with small
   deterministic semantics. Fail when a requested signature is absent. Never
   simulate NMS, packets, chunk generation, another plugin, or a domain state
   machine in a global patch.
5. Preserve a real Paper E2E lane for behavior the mock cannot prove.

Promote a plugin-local adapter to Core only after a second real consumer, a
verified cross-plugin duplicate, or a lifecycle/safety invariant establishes a
stable shared contract. Update `shared-primitives.md`, the consumer capability,
and both production/test modules together. Do not move feature DTOs, locale
keys, or a service locator into Core merely to reduce line count.

The local authoritative API evidence for this contract is the resolved
MockBukkit `4.116.3` artifact manifest, which declares Paper API
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
