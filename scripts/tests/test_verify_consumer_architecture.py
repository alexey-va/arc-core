from __future__ import annotations

import tempfile
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

    def verify(self) -> tuple[int, str]:
        output = StringIO()
        with redirect_stdout(output):
            exit_code = main([str(self.root)])
        return exit_code, output.getvalue()

    def test_accepts_canonical_paper_composition(self) -> None:
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
