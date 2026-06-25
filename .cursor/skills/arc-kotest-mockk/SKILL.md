---
name: arc-kotest-mockk
description: >-
  Write Kotlin tests for ARC/arc-core with Kotest and MockK.
  Use when writing unit tests, Test*Config, or mocking in ARC or arc-core — not JUnit/Mockito.
---

# Kotest + MockK (ARC stack)

**Canon:** [`AGENTS.md`](../../AGENTS.md) § Boundary rules

## Required

- **Kotest** — `FreeSpec` or `StringSpec`; `shouldBe`, `shouldThrow`, not JUnit `assertEquals`
- **MockK** — `mockk`, `every`, `verify`; not Mockito
- **Test configs** — `TestMyConfig(enabled = false) : MyConfig(EmptyConfig)`
- **Scheduler tests** — `TestTaskScheduler` + `advanceMs()` via `Tasks.withScheduler(...)`

## Forbidden

- `@Ignore` / `@Disabled` — fix code or refactor
- JUnit `@Test` in Kotlin test files
- Mockito in Kotlin tests
- Calling real Bukkit API in arc-core unit tests (use MockBukkit only in arc-core-paper tests)

## Example

```kotlin
class MyServiceTest : FreeSpec({
    "MyService" - {
        "should return data when repository succeeds" {
            val repo = mockk<MyRepository>()
            every { repo.load("id") } returns Result.success(data)
            val service = MyService(repo)

            service.getData("id") shouldBe data
            verify { repo.load("id") }
        }
    }
})
```

## Run

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test --tests "ru.arc.myfeature.MyServiceTest"
```

Quiet logs in tests: `Logging.quietMode = true`
