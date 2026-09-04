# Configurable Paper Menus Implementation Plan

**Goal:** Build two reusable arc-core menu artifacts and migrate every active in-house Paper inventory to configuration-owned layout with typed Kotlin actions.

**Architecture:** `arc-core-menu` parses and validates immutable layout catalogs without Bukkit. `arc-core-paper-menu` renders those layouts through Inventory Framework 0.12.0 and owns sessions, clicks, pagination, feedback, and item-template safety. Consumers retain domain state and locale rendering while deleting duplicate raw-slot routing.

**Tech Stack:** Kotlin 2.3.0, Java 25, SnakeYAML Engine 3.0.1 through `ru.arc.config.Config`, Paper 1.21.11, Adventure 4.17.0, Inventory Framework 0.12.0, Kotest 6.0.7, MockBukkit through `arc-core-paper-testing`.

**Spec:** `docs/superpowers/specs/2026-09-02-configurable-paper-menus-design.md`

## Global Constraints

- Core menu artifacts use immutable release coordinate `ru.ruscrafting.arc:*:2.3.0`.
- Publishing 2.3.0 requires separate owner authorization; source commits and ordinary pushes do not grant it.
- Bundled defaults contain vanilla materials and custom model data zero; runtime overlays own verified resource-pack IDs.
- YAML owns layout and presentation templates, never arbitrary commands, permissions, persistence, or domain decisions.
- Every visible item name and lore root resolves `TextDecoration.ITALIC` to `FALSE`.
- Every migrated click action is bound to the rendered semantic element; consumer `when(rawSlot)` routing is removed.
- Candidate reload is all-or-nothing and leaves the current generation active on any validation failure.
- Local verification never starts Docker or Testcontainers.
- No production deployment, reload, restart, database/Redis write, or player-data mutation is part of this plan.

---

### Task 1: Platform-neutral menu model and layout resolver

**Files:**
- Modify: `settings.gradle.kts`
- Create: `arc-core-menu/build.gradle.kts`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuIds.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuLayout.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuContract.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuValidation.kt`
- Create: `arc-core-menu/src/test/kotlin/ru/arc/menu/MenuLayoutTest.kt`
- Create: `arc-core-menu/src/test/kotlin/ru/arc/menu/MenuContractTest.kt`

**Interfaces:**
- Produces: `MenuId`, `MenuElementId`, `MenuRegionId`, `MenuSlot`, `MenuElementLayout`, `MenuRegionLayout`, `MenuLayout`, `MenuContract`, `MenuValidationIssue`, and `MenuValidationException`.
- `MenuLayout.slot(MenuElementId): MenuSlot` is the only element-to-slot lookup.
- `MenuLayout.region(MenuRegionId): List<MenuSlot>` returns deterministic declaration order.

- [ ] **Step 1: Write failing model and validation tests**

  Cover rows 1 and 6, rejected rows 0 and 7, every slot 0 through 53, duplicate occupied slots, required and optional IDs, closed-contract unknown IDs, region order, unknown element references, and colliding pagination controls. Use hand-authored expected slots and typed issue codes.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :arc-core-menu:test`

  Expected: Gradle fails because the module or menu types do not exist.

- [ ] **Step 3: Implement the immutable types and validator**

  Use validated inline ID classes with lowercase `a-z0-9._-`, zero-based `MenuSlot`, and sealed placement types for one slot, repeated slots, and named regions. Return all deterministic validation issues from one pass; `validated(contract)` throws one exception containing the same ordered issues.

- [ ] **Step 4: Run GREEN and refactor**

  Run: `./gradlew :arc-core-menu:test`

  Expected: all model and contract tests pass with zero failures.

- [ ] **Step 5: Commit**

  Stage only Task 1 files and commit `feat(menu): add validated layout model`.

### Task 2: YAML parser, patterns, ranges, catalogs, and pagination

**Files:**
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuLayoutParser.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuSlotExpression.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuCatalog.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuCatalogRepository.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuPageState.kt`
- Create: `arc-core-menu/src/main/kotlin/ru/arc/menu/MenuFeedbackState.kt`
- Create: `arc-core-menu/src/test/kotlin/ru/arc/menu/MenuLayoutParserTest.kt`
- Create: `arc-core-menu/src/test/kotlin/ru/arc/menu/MenuCatalogRepositoryTest.kt`
- Create: `arc-core-menu/src/test/kotlin/ru/arc/menu/MenuPageStateTest.kt`
- Create: `arc-core-menu/src/test/kotlin/ru/arc/menu/MenuFeedbackStateTest.kt`
- Create: `arc-core-menu/src/test/resources/menu/complete.yml`
- Create: `arc-core-menu/src/test/resources/menu/invalid.yml`

**Interfaces:**
- Consumes: Task 1 menu model and `ru.arc.config.Config`.
- Produces: `MenuLayoutParser.parse(config, root, contracts): MenuCatalog`, `MenuCatalogRepository.current()`, `MenuCatalogRepository.replace(candidate)`, `MenuPageState`, and `MenuFeedbackState`.
- Replacement returns `MenuCatalogReplaceResult.Replaced` or `Rejected`; rejected replacement retains the exact previous catalog instance and generation.

- [ ] **Step 1: Write failing parser and state tests**

  Cover absolute slots, row/column positions, ordered lists, inclusive ranges, range unions, pattern matrices, missing and unused legend keys, short/long rows, malformed scalar types, complete multi-menu aggregation, page clamping, empty pages, overflow, repeated feedback replacement, render invalidation, expiry, and generation invalidation.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :arc-core-menu:test`

  Expected: compilation fails on missing parser, repository, page, and feedback APIs.

- [ ] **Step 3: Implement parser and state owners**

  Parse from non-mutating nullable `Config` accessors. Do not call accessors that inject defaults while validating a candidate. Collect structural parse issues instead of leaking `ClassCastException`. Resolve pattern symbols and regions before contract validation. Store catalog generations as monotonically increasing `Long` values.

- [ ] **Step 4: Run GREEN and mutation review**

  Run: `./gradlew :arc-core-menu:test`

  Expected: every parser branch and state transition passes. Confirm tests would fail for an off-by-one range, wrong page count, stale feedback restore, and partial catalog swap.

- [ ] **Step 5: Commit**

  Commit `feat(menu): parse and reload menu catalogs`.

### Task 3: Paper item templates and external-item boundary

**Files:**
- Modify: `settings.gradle.kts`
- Create: `arc-core-paper-menu/build.gradle.kts`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuItemTemplate.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuItemTemplateParser.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuExternalItemResolver.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuItemFactory.kt`
- Create: `arc-core-paper-menu/src/test/kotlin/ru/arc/paper/menu/PaperMenuItemTemplateTest.kt`
- Create: `arc-core-paper-menu/src/test/kotlin/ru/arc/paper/menu/PaperMenuItemFactoryTest.kt`

**Interfaces:**
- Consumes: `MenuLayout`, Paper API 1.21.11, and Adventure components.
- Produces: `PaperMenuItemTemplate`, `PaperMenuExternalItemResolver.resolve(id)`, typed resolution results, and `PaperMenuItemFactory.create(template, name, lore): ItemStack`.

- [ ] **Step 1: Write failing template and final-component tests**

  Cover valid materials, invalid/air materials, amounts 1 and 99, rejected 0 and 100, custom model data zero/positive/rejected negative, namespaced external IDs, resolver success clone isolation, missing resolver, failed resolver fallback, glint/tooltip flags, empty names, empty lore lines, and explicit non-italic roots on every final component.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :arc-core-paper-menu:test`

  Expected: the Paper menu module or APIs do not exist.

- [ ] **Step 3: Implement templates and factory**

  Keep ItemsAdder behind the resolver interface. Always clone resolved external stacks. Apply the configured presentation after resolution so locale content and safe flags win over provider metadata. Return bounded diagnostic keys without full YAML or player values.

- [ ] **Step 4: Run GREEN**

  Run: `./gradlew :arc-core-paper-menu:test`

  Expected: template validation and real `ItemMeta` assertions pass.

- [ ] **Step 5: Commit**

  Commit `feat(paper-menu): add safe item templates`.

### Task 4: Inventory Framework renderer and session lifecycle

**Files:**
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuContent.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuClick.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuSession.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuService.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuPagination.kt`
- Create: `arc-core-paper-menu/src/main/kotlin/ru/arc/paper/menu/PaperMenuFeedback.kt`
- Create: `arc-core-paper-menu/src/test/kotlin/ru/arc/paper/menu/PaperMenuSessionTest.kt`
- Create: `arc-core-paper-menu/src/test/kotlin/ru/arc/paper/menu/PaperMenuInteractionTest.kt`
- Create: `arc-core-paper-menu/src/test/kotlin/ru/arc/paper/menu/PaperMenuPaginationTest.kt`
- Create: `arc-core-paper-menu/src/test/kotlin/ru/arc/paper/menu/PaperMenuFeedbackTest.kt`

**Interfaces:**
- Produces: `PaperMenuContent`, `PaperMenuElement`, `PaperMenuRegionContent`, `PaperMenuService.open`, `PaperMenuSession.render/refresh/setPage/nextPage/previousPage/showFeedback/close`, typed session results, and `PaperMenuClickContext`.
- Inventory Framework types remain internal implementation details; consumer public code sees Paper and arc-core-menu types only.

- [ ] **Step 1: Write failing real-framework platform tests**

  Use one `MockBukkitTestRuntime` per case and the real IF 0.12.0 classes. Prove background fill, semantic button dispatch after configured movement, decoration non-clickability, disabled behavior, accepted click filtering, top/bottom cancellation, shift/number/double/drop/offhand cancellation, top-touching drag cancellation, viewer ownership, close idempotence, stale generation no-op, ordered region population, overflow rejection before mutation, and no stale handler after rerender.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :arc-core-paper-menu:test`

  Expected: missing renderer/session API failures.

- [ ] **Step 3: Implement the IF adapter**

  Build one low-priority repeated background pane and one static content pane. Create every clickable `GuiItem` with its semantic handler in the same call. Enforce primary-thread affinity. Track one session per player in `PaperMenuService`; opening a replacement closes and removes the previous session exactly once.

- [ ] **Step 4: Implement pagination and feedback through additional RED/GREEN cycles**

  Add page-control tests before each behavior. Feedback expiry uses injected `LifecycleTaskScope`, token invalidation, and a supplier for the latest normal item. Never capture a stale normal `ItemStack` for delayed restoration.

- [ ] **Step 5: Run GREEN and full core tests**

  Run: `./gradlew :arc-core-menu:test :arc-core-paper-menu:test`

  Expected: all menu tests pass without warnings or leaked MockBukkit state.

- [ ] **Step 6: Commit**

  Commit `feat(paper-menu): render lifecycle-safe configured menus`.

### Task 5: Core documentation, publication metadata, and architecture gates

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/shared-primitives.md`
- Modify: `docs/INDEX.md`
- Modify: `scripts/publish-release.sh`
- Modify: `scripts/verify_consumer_architecture.py`
- Modify: `scripts/tests/test_verify_consumer_architecture.py`
- Modify: `templates/consumer-contract/paper/arc-core-consumer.toml`
- Create: `docs/paper-menus.md`
- Create: `arc-core-paper-menu/src/test/resources/menu/example.yml`

**Interfaces:**
- Documents the exact YAML schema, API contracts, thread/lifecycle rules, reload policy, zMenu boundary, consumer setup, and verification commands.
- Adds `paper-menu` as an optional declared consumer capability that requires `arc-core-menu` and `arc-core-paper-menu`.

- [ ] **Step 1: Write failing verifier tests**

  Add fixtures proving a declared `paper-menu` capability fails without both artifacts and passes with both. Prove unrelated consumers do not acquire the dependency.

- [ ] **Step 2: Run RED**

  Run: `python3 -m unittest scripts.tests.test_verify_consumer_architecture`

  Expected: new capability cases fail before verifier support exists.

- [ ] **Step 3: Implement documentation and verifier support**

  Include a complete copy-paste consumer example with `MenuContract`, additive config merge, candidate validation, `PaperMenuContent`, reload replacement, and shutdown cleanup. Ensure release discovery stages both new Maven publications automatically.

- [ ] **Step 4: Run core gate**

  Run: `./gradlew testAll publishToMavenLocal`

  Expected: all unit/property/platform tests and the consumer verifier pass; both new artifacts and source JARs exist in Maven local.

- [ ] **Step 5: Commit and push core**

  Commit `docs(menu): publish consumer contract and guide`, review the complete diff, and ordinary-push `main`. Wait for both core CI jobs. Do not create a GitHub release without separate owner authorization.

### Task 6: Migrate ArcVotes and ArcEvents

**Files:**
- Modify: `ArcVotes/settings.gradle.kts`
- Modify: `ArcVotes/build.gradle.kts`
- Modify: `ArcVotes/arc-core-consumer.toml`
- Modify: `ArcVotes/src/main/kotlin/ru/ruscrafting/votes/paper/VoteMenu.kt`
- Modify: `ArcVotes/src/main/resources/config.yml`
- Modify: `ArcVotes/src/test/kotlin/ru/ruscrafting/votes/paper/VoteMenuMockBukkitTest.kt`
- Create: `ArcVotes/src/test/kotlin/ru/ruscrafting/votes/config/VoteMenuLayoutTest.kt`
- Modify: `ArcEvents/settings.gradle.kts`
- Modify: `ArcEvents/build.gradle.kts`
- Create: `ArcEvents/arc-core-consumer.toml`
- Modify: `ArcEvents/src/main/kotlin/ru/ruscrafting/events/paper/ArcEventsMenu.kt`
- Modify: `ArcEvents/src/main/resources/config.yml`
- Modify: existing ArcEvents menu platform tests
- Create: `ArcEvents/src/test/kotlin/ru/ruscrafting/events/config/ArcEventsMenuLayoutTest.kt`

**Interfaces:**
- Each plugin owns one `MenuCatalogRepository`, one menu contract per screen, one `PaperMenuService`, and domain handlers bound by semantic ID.
- Vote-site dynamic entries use a configured `sites` region; event screens use semantic fixed actions and configured collection regions.

- [ ] **Step 1: Add failing moved-slot route tests for ArcVotes**

  Copy the real bundled config to a temporary root, move one vote site, open through the real controller, click the new slot, and assert the link action occurs only there. Confirm RED while raw constants still own routing.

- [ ] **Step 2: Migrate ArcVotes and run GREEN**

  Replace `SITE_SLOTS`, `SOURCES_BY_SLOT`, raw holder/listener logic, and manual background fill with shared layouts and `PaperMenuContent`. Preserve loading/history states and async current-session guard.

  Run: `./gradlew test shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core`

- [ ] **Step 3: Repeat RED/GREEN for ArcEvents**

  Move one main and one admin action in temporary config, prove old routing fails, migrate every `EventsView`, then run `./gradlew test shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core` without the Docker-backed check task.

- [ ] **Step 4: Commit exact files**

  Commit each repository independently with `refactor(menu): use configured menu platform`. Do not push until immutable core 2.3.0 is available to standalone CI.

### Task 7: Migrate ArcFarms

**Files:**
- Modify: `ArcFarms/settings.gradle.kts`
- Modify: `ArcFarms/build.gradle.kts`
- Modify: `ArcFarms/arc-core-consumer.toml`
- Modify: `ArcFarms/src/main/resources/config.yml`
- Modify: `ArcFarms/src/main/kotlin/ru/ruscrafting/farms/config/ArcFarmsConfig.kt`
- Modify: `ArcFarms/src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsMenu.kt`
- Modify: `ArcFarms/src/main/kotlin/ru/ruscrafting/farms/paper/FarmMarketMenu.kt`
- Modify: `ArcFarms/src/main/kotlin/ru/ruscrafting/farms/paper/WorksiteEnterpriseMenu.kt`
- Modify: `ArcFarms/src/main/kotlin/ru/ruscrafting/farms/paper/farm/perk/FarmPerkController.kt`
- Modify: existing ArcFarms config and MockBukkit menu tests
- Create: `ArcFarms/src/test/kotlin/ru/ruscrafting/farms/config/ArcFarmsMenuLayoutTest.kt`

**Interfaces:**
- Menu IDs: `main`, `market`, `enterprise-overview`, `enterprise-farm`, every enterprise share/confirm view, and `farm-perks`.
- Dynamic buy/perk choices use named ordered regions; fixed actions use semantic IDs.

- [ ] **Step 1: Create the clean detached worktree and baseline**

  Add a detached worktree from current `main` under `/private/tmp`, leaving the occupied dirty checkout untouched. Run `./gradlew test shadowJar` there before edits. If baseline fails, diagnose without modifying the occupied checkout.

- [ ] **Step 2: Add failing config movement and overlap tests**

  Prove a moved `companies`, market accept, enterprise control, and perk offer slot does not work under the old implementation. Add candidate-reload rejection for overlap and out-of-range values.

- [ ] **Step 3: Migrate all four menu owners**

  Preserve existing locale, lifecycle-epoch checks, deferred inventory transitions, loading behavior, exact click types, and dynamic refresh. Remove duplicate slot constants and manual background loops.

- [ ] **Step 4: Run full allowed gate**

  Run: `RUSCRAFTING_OPS_ROOT=/Users/alexey23/RusCrafting/ruscrafting-ops ./gradlew test shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core`

- [ ] **Step 5: Commit detached source**

  Commit only migration files. Push `HEAD:main` only after fetching and proving fast-forward compatibility and after core 2.3.0 is published. Leave the user's dirty main checkout unchanged and report it remains behind the pushed commit.

### Task 8: Migrate ArcBuilder and ArcRanks

**Files:**
- Modify: ArcBuilder `settings.gradle.kts`, `build.gradle.kts`, `arc-core-consumer.toml`, `src/main/resources/modules/builder-tools.yml`, the three menu owners, reload preflight, and their focused tests/preview manifest.
- Modify: ArcRanks `settings.gradle.kts`, `build.gradle.kts`, `arc-core-consumer.toml`, `src/main/resources/config.yml`, the five classes under `src/main/kotlin/ru/ruscrafting/ranks/gui/`, and their focused tests/preview manifest.

**Interfaces:**
- ArcBuilder reuses its existing construction-menu slot YAML as the first compatibility fixture and replaces its local overlap validator with the shared catalog validator.
- ArcRanks maps all existing state variants onto semantic IDs while preserving config-generation guards and async snapshots.

- [ ] **Step 1: Create a detached ArcBuilder worktree and baseline**

  Preserve the occupied dirty checkout. Run `./gradlew --no-daemon test shadowJar` and `python3 ../arc-core/scripts/verify_consumer_architecture.py .` from the detached checkout before edits.

- [ ] **Step 2: RED/GREEN migrate ArcBuilder**

  First prove moved construction control, preview decision, and book-editor reset/copy actions fail under old routing. Migrate all three menus, retain transactional reload, and generate preview layout from the catalog.

  Run: `./gradlew --no-daemon test shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core` plus the verifier.

- [ ] **Step 3: RED/GREEN migrate ArcRanks**

  Add tests moving one fixed action and one dynamic region in each menu family. Replace public layout constants with catalog-derived compatibility accessors only where tests or external API require them. Preserve loading/error/action-pending states and latest config generation.

  Run: `./gradlew --no-daemon test compileIntegrationTestKotlin shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core`, the verifier, and `./scripts/render-visual-preview`.

- [ ] **Step 4: Commit exact repositories**

  Commit ArcBuilder from its detached worktree and ArcRanks on direct `main`. Hold pushes until core 2.3.0 exists.

### Task 9: Migrate ArcEcoJobs and ArcDuels

**Files:**
- Modify: `ArcEcoJobs/settings.gradle.kts`
- Modify: `ArcEcoJobs/build.gradle.kts`
- Create: `ArcEcoJobs/arc-core-consumer.toml`
- Modify: `ArcEcoJobs/src/main/resources/config.yml`
- Modify: `ArcEcoJobs/src/main/kotlin/ru/ruscrafting/ecojobs/paper/JobsMenu.kt`
- Modify: `ArcEcoJobs/src/test/kotlin/ru/ruscrafting/ecojobs/paper/JobsMenuMockBukkitTest.kt`
- Modify: `ArcEcoJobs/visual-preview.yml`
- Modify: `ArcDuels/settings.gradle.kts`
- Modify: `ArcDuels/paper/build.gradle.kts`
- Create: `ArcDuels/arc-core-consumer.toml`
- Modify: `ArcDuels/paper/src/main/resources/config.yml`
- Modify: `ArcDuels/paper/src/main/kotlin/ru/ruscrafting/duels/paper/DuelGuiService.kt`
- Modify: `ArcDuels/paper/src/main/kotlin/ru/ruscrafting/duels/paper/MultiplayerGuiService.kt`
- Modify: `ArcDuels/paper/src/test/kotlin/ru/ruscrafting/duels/paper/MultiplayerMockBukkitIntegrationTest.kt`
- Modify: `ArcDuels/paper/src/test/kotlin/ru/ruscrafting/duels/paper/MultiplayerInvitationMockBukkitTest.kt`
- Modify: `ArcDuels/visual-preview.yml`

**Interfaces:**
- ArcEcoJobs maps every sealed `JobsView` to a configured menu ID and replaces `contentSlots` with named regions plus shared pagination.
- ArcDuels maps every screen/state to a configured ID, keeps duel/session state in its services, and delegates only presentation/session ownership.

- [ ] **Step 1: Characterize all current routes**

  Extend existing MockBukkit journeys with one moved-slot assertion per screen family, including content-page item selection, back, previous/next, confirm/cancel, invitation, queue, and admin actions. Run the focused tests and record expected RED failures.

- [ ] **Step 2: Migrate ArcEcoJobs**

  Remove `contentSlots`, manual inventory construction, raw holder dispatch, and local page-control slot routing. Preserve pending-click serialization and async earnings refresh through session identity.

  Run: `./gradlew clean test shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core`.

- [ ] **Step 3: Migrate ArcDuels**

  Replace both raw menu services incrementally by screen family, keeping arena/session behavior untouched. Use shared feedback for rejected local actions and shared pagination for collections.

  Run: `./gradlew testAll :paper:shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core`.

- [ ] **Step 4: Commit exact repositories**

  Commit each repository independently and hold pushes until core 2.3.0 exists.

### Task 10: Adapt ARC and migrate remaining raw menus

**Files:**
- Modify: `ARC/settings.gradle.kts`, `build.gradle.kts`, `arc-core-consumer.toml`.
- Replace implementation in `ARC/src/main/kotlin/ru/arc/gui/GuiDsl.kt`, `ConfigGui.kt`, and `GuiDefaults.kt` while retaining source-compatible entry points.
- Migrate the menu classes under board, scheduled, stock, parkour, mounts, and EliteMobs hooks.
- Modify their `src/main/resources/guis/*.yml` files and focused tests.
- Update `ARC/src/main/kotlin/ru/arc/gui/GUI.md` and visual preview manifest/wrapper.

**Interfaces:**
- Existing `gui {}`, pagination, static pane, and navigation call sites delegate to shared sessions during the compatibility release.
- New or migrated screens use semantic elements and shared catalogs directly; no new `x/y` layout literal is introduced in Kotlin.

- [ ] **Step 1: Add failing facade and moved-layout tests**

  Prove existing DSL calls retain behavior while a configured back/save/field slot moves. Add route tests for every remaining raw menu family.

- [ ] **Step 2: Adapt the facade**

  Preserve source compatibility where practical, but route item placement and click ownership through shared definitions. Remove local background, pagination, defaults-slot, and config-item parsing implementations once no consumer remains.

- [ ] **Step 3: Migrate menu families in independent RED/GREEN cycles**

  Do board/scheduled forms, stock, parkour, mounts, and EliteMobs separately. Run focused tests after each family and the full test suite after executable changes.

- [ ] **Step 4: Run ARC gate**

  Run: `./gradlew test shadowJar -ParcCoreDir=/Users/alexey23/RusCrafting/arc-core` and `python3 ../arc-core/scripts/verify_consumer_architecture.py .`.

- [ ] **Step 5: Commit**

  Commit `refactor(gui): use shared configured menu platform` and hold push until core 2.3.0 exists.

### Task 11: Immutable release, standalone verification, visual review, and pushes

**Files:**
- No new implementation files unless a verification failure requires a focused tested fix.
- Generated visual reports stay under each repository's ignored build directory.

**Interfaces:**
- Core 2.3.0 release artifacts unblock clean standalone consumer resolution.
- Each pushed consumer commit is independently buildable without the local composite.

- [ ] **Step 1: Obtain release authorization**

  Ask the owner explicitly before creating/publishing the arc-core 2.3.0 GitHub release. Without authorization, stop at locally verified coordinated source and report consumer publication as blocked.

- [ ] **Step 2: Publish and verify core 2.3.0**

  After authorization and green core CI, create the immutable release through the supported GitHub release workflow. Verify every Maven artifact, `.module`, POM, JAR, and sources JAR resolves from Reposilite with matching checksums.

- [ ] **Step 3: Run fresh standalone consumer gates**

  Remove the `-ParcCoreDir` property and run each repository's allowed local test/package/verifier commands. Do not run local integration tests.

- [ ] **Step 4: Render and inspect all changed menu families**

  Run every component's `scripts/render-visual-preview` wrapper. Inspect all generated inventory and tooltip pages, confirm exact configured slots/background/click zones and non-italic content, and open each `index.html` for owner visual assessment. Do not deploy while visual acceptance is pending.

- [ ] **Step 5: Push direct trunks and inspect CI**

  Fetch each origin, reconcile only fast-forward-compatible changes, ordinary-push the verified commits, and inspect required CI. Do not create PRs, tickets, comments, releases beyond the separately authorized core release, or fallback branches.

- [ ] **Step 6: Final exact-scope audit**

  Search all migrated production sources for raw `createInventory`, `when(rawSlot)`, local `contentSlots`, duplicate background loops, and direct IF coordinates. Classify legitimate non-menu test fixtures or compatibility facades explicitly. Report source, tests, artifact publication, Git delivery, visual acceptance, and runtime activation as separate facts.
