from __future__ import annotations

import tempfile
import tomllib
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

from scripts.verify_consumer_architecture import main


PAPER_MODULES = (
    "arc-core",
    "arc-core-logging",
    "arc-core-metrics",
    "arc-core-paper",
)

TEMPLATE_ROOT = Path(__file__).resolve().parents[2] / "templates" / "consumer-contract"


class ConsumerContractTemplateTest(unittest.TestCase):
    def test_new_plugin_templates_do_not_enable_metrics_by_default(self) -> None:
        for platform in ("paper", "velocity"):
            manifest = TEMPLATE_ROOT / platform / "arc-core-consumer.toml"
            with self.subTest(platform=platform):
                contract = tomllib.loads(manifest.read_text(encoding="utf-8"))
                self.assertNotIn("metrics", contract["capabilities"])


class ConsumerArchitectureVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.write_valid_paper_consumer()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write(self, relative_path: str, content: str) -> None:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def write_valid_paper_consumer(self) -> None:
        self.write(
            "arc-core-consumer.toml",
            """\
schema = 1
platform = "paper"
core_version = "2.1.0"
capabilities = ["health", "localized-text", "logging", "metrics", "paper-testing", "runtime"]
""",
        )
        runtime_dependencies = "\n".join(
            f'    implementation("ru.ruscrafting.arc:{module}:2.1.0")' for module in PAPER_MODULES
        )
        self.write(
            "build.gradle.kts",
            f"""\
dependencies {{
{runtime_dependencies}
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.1.0")
}}
""",
        )
        self.write(
            "src/main/kotlin/example/ExamplePlugin.kt",
            """\
package example

import ru.arc.core.PaperArcRuntime
import ru.arc.logging.ArcLogging
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.text.LocalizedMiniMessage

fun compose(plugin: Any, messages: LocalizedMiniMessage) {
    ArcLogging.install(config)
    ArcMetricsRuntime(metricsConfig)
    PaperArcRuntime.installScheduling(server, plugin)
    val runtime = PaperPluginRuntime(plugin, "example")
    runtime.registerHealth("service") { contribution() }
    runtime.reportHealthEvery(1200L)
}
""",
        )
        self.write(
            "src/test/kotlin/example/ExamplePluginTest.kt",
            """\
package example

import ru.arc.paper.testing.MockBukkitTestRuntime

fun testRuntime() = MockBukkitTestRuntime.open().use { }
""",
        )

    def write_valid_provider_host(self, platform: str = "paper") -> None:
        self.write(
            "arc-core-consumer.toml",
            f"""\
schema = 1
role = "provider-host"
platform = "{platform}"
core_version = "2.1.0"
capabilities = []
""",
        )
        self.write(
            "build.gradle.kts",
            """\
dependencies {
    implementation("ru.ruscrafting.arc:arc-core-paper-api:2.1.0")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.1.0")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.1.0")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.1.0")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.1.0")
}
""",
        )
        self.write(
            "src/main/kotlin/example/ProviderHost.kt",
            """\
package example

class ProviderHost {
    fun install(owner: Plugin) {
        val manager = owner.server.servicesManager
        val sql = PaperSqlServiceProvider(owner)
        val redis = PaperRedisServiceProvider(owner)
        val metrics = PaperArcMetricsProvider(null)
        manager.register(ArcCoreServices::class.java, this, owner, priority)
        manager.unregister(ArcCoreServices::class.java, this)
    }
}
""",
        )

    def verify(self) -> tuple[int, str]:
        output = StringIO()
        with redirect_stdout(output):
            exit_code = main([str(self.root)])
        return exit_code, output.getvalue()

    def test_accepts_canonical_paper_composition(self) -> None:
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code)
        self.assertIn("CONSUMER_CONTRACT_SUMMARY status=ok", output)
        self.assertIn("role=feature-consumer", output)

    def test_schematics_consumer_requires_shared_api_and_service_evidence(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace('"runtime"]', '"runtime", "paper-schematics"]'),
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-paper-api", output)
        self.assertIn("ArcSchematicService", output)

        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8")
            + '\ndependencies { compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.1.0") }\n',
            encoding="utf-8",
        )
        self.write("src/main/kotlin/example/Schematics.kt", "class Schematics(val library: ArcSchematicLibrary)\n")
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code, output)

    def test_action_panels_require_canonical_runtime_not_only_geometry_helpers(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace('"runtime"]', '"runtime", "paper-action-panels"]'),
            encoding="utf-8",
        )
        self.write("src/main/kotlin/example/Panel.kt", "class Panel(val geometry: ActionPanelGeometry)\n")
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("PaperActionPanels", output)
        self.write("src/main/kotlin/example/Panel.kt", "class Panel(val panels: PaperActionPanels<Context, Action>)\n")
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code, output)

    def test_optional_schematics_provider_module_is_version_checked(self) -> None:
        self.write_valid_provider_host()
        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8")
            + '\ndependencies { implementation("ru.ruscrafting.arc:arc-core-paper-schematics:2.0.0") }\n',
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-paper-schematics", output)

        build.write_text(build.read_text(encoding="utf-8").replace(":2.0.0", ":2.1.0"), encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code, output)

    def test_block_protection_requires_shared_api_and_typed_evidence(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(manifest.read_text().replace('"runtime"]', '"runtime", "paper-block-protection"]'))
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-paper-api", output)
        self.assertIn("ArcBlockProtectionService", output)
        build = self.root / "build.gradle.kts"
        build.write_text(build.read_text() + '\ndependencies { compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.1.0") }\n')
        self.write("src/main/kotlin/example/Protection.kt", "class Protection(val check: ArcBlockProtectionService)\n")
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code, output)

    def test_accepts_paper_provider_host_without_feature_consumer_baseline(self) -> None:
        self.write_valid_provider_host()

        exit_code, output = self.verify()

        self.assertEqual(0, exit_code, output)
        self.assertIn("role=provider-host platform=paper", output)
        self.assertIn("capabilities=", output)

    def test_provider_host_requires_each_provider_and_service_lifecycle_evidence(self) -> None:
        self.write_valid_provider_host()
        source = self.root / "src/main/kotlin/example/ProviderHost.kt"
        original = source.read_text(encoding="utf-8")

        for missing in (
            "PaperSqlServiceProvider",
            "PaperRedisServiceProvider",
            "PaperArcMetricsProvider",
            "servicesManager",
            ".register(",
            ".unregister(",
        ):
            with self.subTest(missing=missing):
                source.write_text(original.replace(missing, "missingEvidence", 1), encoding="utf-8")
                exit_code, output = self.verify()
                self.assertEqual(1, exit_code)
                self.assertIn("rule=provider-host-evidence", output)
        source.write_text(original, encoding="utf-8")

    def test_provider_host_requires_host_modules_and_test_scope(self) -> None:
        self.write_valid_provider_host()
        build = self.root / "build.gradle.kts"
        original = build.read_text(encoding="utf-8")

        build.write_text(original.replace('    implementation("ru.ruscrafting.arc:arc-core-sql:2.1.0")\n', ""), encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=required-module", output)
        self.assertIn("arc-core-sql", output)

        build.write_text(original.replace('    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.1.0")\n', ""), encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-paper-testing", output)

    def test_provider_host_role_cannot_bypass_paper_consumer_rules_on_velocity(self) -> None:
        self.write_valid_provider_host(platform="velocity")

        exit_code, output = self.verify()

        self.assertEqual(1, exit_code)
        self.assertIn("rule=provider-host-platform", output)
        self.assertIn("rule=required-capabilities", output)

    def test_provider_host_cannot_claim_feature_capabilities(self) -> None:
        self.write_valid_provider_host()
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace("capabilities = []", 'capabilities = ["runtime"]'),
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(1, exit_code)
        self.assertIn("rule=provider-host-capabilities", output)

    def test_provider_host_keeps_forbidden_local_implementation_rules(self) -> None:
        self.write_valid_provider_host()
        source = self.root / "src/main/kotlin/example/ProviderHost.kt"
        source.write_text(source.read_text(encoding="utf-8") + "\nfun badPool() = HikariDataSource(HikariConfig())\n", encoding="utf-8")

        exit_code, output = self.verify()

        self.assertEqual(1, exit_code)
        self.assertIn("rule=local-sql-pool", output)

    def test_accepts_paper_composition_without_a_metrics_exporter(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(', "metrics"', ""),
            encoding="utf-8",
        )
        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                '    implementation("ru.ruscrafting.arc:arc-core-metrics:2.1.0")\n',
                "",
            ),
            encoding="utf-8",
        )
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(
            source.read_text(encoding="utf-8")
            .replace("import ru.arc.metrics.core.ArcMetricsRuntime\n", "")
            .replace("    ArcMetricsRuntime(metricsConfig)\n", ""),
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(0, exit_code)
        self.assertIn("CONSUMER_CONTRACT_SUMMARY status=ok", output)

    def test_rejects_missing_required_module(self) -> None:
        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                '    implementation("ru.ruscrafting.arc:arc-core-metrics:2.1.0")\n',
                "",
            ),
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=required-module", output)

    def test_does_not_accept_commented_dependency(self) -> None:
        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                '    implementation("ru.ruscrafting.arc:arc-core-metrics:2.1.0")',
                '    // implementation("ru.ruscrafting.arc:arc-core-metrics:2.1.0")',
            ),
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=required-module", output)

    def test_rejects_direct_platform_scheduler(self) -> None:
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(source.read_text(encoding="utf-8") + "\nfun bad() = Bukkit.getScheduler()\n", encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=direct-platform-scheduler", output)

    def test_rejects_direct_mockbukkit(self) -> None:
        test = self.root / "src/test/kotlin/example/ExamplePluginTest.kt"
        test.write_text(test.read_text(encoding="utf-8") + "\nimport org.mockbukkit.mockbukkit.MockBukkit\n", encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=direct-mockbukkit", output)

    def test_accepts_domain_fixture_types_with_the_shared_runtime(self) -> None:
        source = self.root / "src/test/kotlin/example/ExamplePluginTest.kt"
        source.write_text(source.read_text(encoding="utf-8") + "\n" +
            "import org.mockbukkit.mockbukkit.WorldMock\n"
            "import org.mockbukkit.mockbukkit.entity.SkeletonMock\n"
            "fun domainFixture(world: WorldMock, actor: SkeletonMock) = world.name\n",
            encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code, output)

    def test_rejects_mockbukkit_lifecycle_and_direct_dependency(self) -> None:
        source = self.root / "src/test/kotlin/example/DirectTest.kt"
        for forbidden in (
            "MockBukkit.mock()",
            "MockBukkit.unmock()",
            "import org.mockbukkit.mockbukkit.MockBukkit as DirectRuntime",
            'testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")',
        ):
            with self.subTest(forbidden=forbidden):
                source.write_text(forbidden, encoding="utf-8")
                exit_code, output = self.verify()
                self.assertEqual(1, exit_code)
                self.assertIn("rule=direct-mockbukkit", output)

    def test_rejects_reflective_access_to_project_owned_arc_code(self) -> None:
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(
            source.read_text(encoding="utf-8")
            + '\nfun badBridge() = Class.forName("ru.arc.gui.DialogTables")\n',
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=project-owned-reflection", output)

    def test_redis_requires_core_api_and_shared_container(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "redis-networking"]',
            ),
            encoding="utf-8",
        )
        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                "dependencies {",
                "dependencies {\n"
                '    implementation("ru.ruscrafting.arc:arc-core-redis:2.1.0")\n'
                '    integrationTestImplementation("ru.ruscrafting.arc:arc-core-integration-testing:2.1.0")',
            ),
            encoding="utf-8",
        )
        main_source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        main_source.write_text(
            main_source.read_text(encoding="utf-8") + "\nval topic: ValidatedRedisTopic<Message> = createTopic()\n",
            encoding="utf-8",
        )
        self.write(
            "src/integrationTest/kotlin/example/RedisIntegrationTest.kt",
            """\
package example

import ru.arc.testing.containers.RedisTestService

fun redisTest() = RedisTestService.start().use { }
""",
        )
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code)
        self.assertIn("redis-networking", output)

    def test_rejects_arc_core_version_drift(self) -> None:
        build = self.root / "build.gradle.kts"
        build.write_text(build.read_text(encoding="utf-8").replace("arc-core-metrics:2.1.0", "arc-core-metrics:2.0.0"), encoding="utf-8")
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=core-version-drift", output)

    def test_rejects_plugin_local_hikari_pool(self) -> None:
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(
            source.read_text(encoding="utf-8") + "\nfun pool() = HikariDataSource(HikariConfig())\n",
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=local-sql-pool", output)

    def test_one_time_use_requires_shared_mysql_implementation(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "one-time-use"]',
            ),
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-sql", output)
        self.assertIn("MySqlOneTimeUseLedger", output)

    def test_accepts_typed_paper_platform_port_capabilities(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "chunk-tickets", "paper-audience", "paper-teleport"]',
            ),
            encoding="utf-8",
        )
        main_source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        main_source.write_text(
            main_source.read_text(encoding="utf-8")
            + "\nval audienceDelivery: PaperAudienceEffects = nativeAudience()"
            + "\nval teleports: PaperTeleportExecutor = nativeTeleports()"
            + "\nval chunkTickets: PaperChunkTicketRegistry = chunkTicketRegistry()\n",
            encoding="utf-8",
        )
        test_source = self.root / "src/test/kotlin/example/ExamplePluginTest.kt"
        test_source.write_text(
            test_source.read_text(encoding="utf-8")
            + "\nval recordedAudience = RecordingPaperAudienceEffects()"
            + "\nval recordedTeleports = RecordingPaperTeleportExecutor()\n",
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(0, exit_code)
        self.assertIn("paper-audience", output)
        self.assertIn("paper-teleport", output)

    def test_paper_menu_capability_requires_both_modules_and_canonical_usage(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "paper-menu"]',
            ),
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-menu", output)
        self.assertIn("arc-core-paper-menu", output)

        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                "dependencies {",
                'dependencies {\n    implementation("ru.ruscrafting.arc:arc-core-menu:2.1.0")'
                '\n    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.1.0")',
            ),
            encoding="utf-8",
        )
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(
            source.read_text(encoding="utf-8") + "\nval configuredMenus: PaperMenuRuntime = menuRuntime()\n",
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(0, exit_code)
        self.assertIn("paper-menu", output)

    def test_paper_menu_capability_accepts_native_dialogs_without_weakening_contract(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "paper-menu"]',
            ),
            encoding="utf-8",
        )
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(
            source.read_text(encoding="utf-8") + "\nval dialogs: PaperDialogRuntime = nativeDialogs()\n",
            encoding="utf-8",
        )

        # Native dialog usage is valid evidence, but it does not bypass required dependencies.
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-menu", output)
        self.assertIn("arc-core-paper-menu", output)
        self.assertNotIn("capability-evidence", output)

        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                "dependencies {",
                'dependencies {\n    implementation("ru.ruscrafting.arc:arc-core-menu:2.1.0")'
                '\n    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.1.0")',
            ),
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(0, exit_code)
        self.assertIn("paper-menu", output)

        # Declaring the capability still requires canonical API evidence.
        source.write_text(
            source.read_text(encoding="utf-8").replace("PaperDialogRuntime", "LocalDialogRuntime"),
            encoding="utf-8",
        )
        exit_code, output = self.verify()
        self.assertEqual(1, exit_code)
        self.assertIn("rule=capability-evidence", output)
        self.assertIn("paper-menu is declared", output)

    def test_neutral_menu_capability_requires_layout_module_and_canonical_usage(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "menu"]',
            ),
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(1, exit_code)
        self.assertIn("arc-core-menu", output)

        build = self.root / "build.gradle.kts"
        build.write_text(
            build.read_text(encoding="utf-8").replace(
                "dependencies {",
                'dependencies {\n    implementation("ru.ruscrafting.arc:arc-core-menu:2.1.0")',
            ),
            encoding="utf-8",
        )
        source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        source.write_text(
            source.read_text(encoding="utf-8") + "\nval layouts: MenuCatalogRepository = menuLayouts()\n",
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(0, exit_code)
        self.assertIn("menu", output)

    def test_rejects_paper_platform_capability_without_test_adapter_evidence(self) -> None:
        manifest = self.root / "arc-core-consumer.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"runtime"]',
                '"runtime", "paper-teleport"]',
            ),
            encoding="utf-8",
        )
        main_source = self.root / "src/main/kotlin/example/ExamplePlugin.kt"
        main_source.write_text(
            main_source.read_text(encoding="utf-8") + "\nval teleports: PaperTeleportExecutor = nativeTeleports()\n",
            encoding="utf-8",
        )

        exit_code, output = self.verify()

        self.assertEqual(1, exit_code)
        self.assertIn("paper-teleport", output)
        self.assertIn("RecordingPaperTeleportExecutor", output)

    def test_accepts_canonical_velocity_composition(self) -> None:
        velocity = self.root / "velocity"
        velocity.mkdir()
        (velocity / "arc-core-consumer.toml").write_text(
            """\
schema = 1
platform = "velocity"
core_version = "2.1.0"
capabilities = ["health", "logging", "metrics", "runtime"]
""",
            encoding="utf-8",
        )
        (velocity / "build.gradle.kts").write_text(
            """\
dependencies {
    implementation("ru.ruscrafting.arc:arc-core:2.1.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.1.0")
    implementation("ru.ruscrafting.arc:arc-core-metrics:2.1.0")
    implementation("ru.ruscrafting.arc:arc-core-velocity:2.1.0")
}
""",
            encoding="utf-8",
        )
        source = velocity / "src/main/kotlin/example/ExampleProxyPlugin.kt"
        source.parent.mkdir(parents=True)
        source.write_text(
            """\
package example

import ru.arc.core.VelocityArcRuntime
import ru.arc.logging.ArcLogging
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.runtime.PluginRuntime

fun compose() {
    ArcLogging.install(loggingPlatform, loggingConfig)
    ArcMetricsRuntime(metricsConfig, identity, dataPath)
    VelocityArcRuntime.installScheduling(server, plugin)
    val runtime = PluginRuntime("example", scheduler, eventSink, healthSink)
    runtime.registerHealth("service") { contribution() }
    runtime.reportHealthEvery(1200L)
}
""",
            encoding="utf-8",
        )
        output = StringIO()
        with redirect_stdout(output):
            exit_code = main([str(velocity)])
        self.assertEqual(0, exit_code)
        self.assertIn("platform=velocity", output.getvalue())

    def test_rejects_paper_capability_on_velocity(self) -> None:
        velocity = self.root / "velocity-invalid"
        velocity.mkdir()
        (velocity / "arc-core-consumer.toml").write_text(
            """\
schema = 1
platform = "velocity"
core_version = "2.1.0"
capabilities = ["health", "logging", "metrics", "paper-testing", "runtime"]
""",
            encoding="utf-8",
        )
        output = StringIO()
        with redirect_stdout(output):
            exit_code = main([str(velocity)])
        self.assertEqual(1, exit_code)
        self.assertIn("rule=capability-platform", output.getvalue())


if __name__ == "__main__":
    unittest.main()
