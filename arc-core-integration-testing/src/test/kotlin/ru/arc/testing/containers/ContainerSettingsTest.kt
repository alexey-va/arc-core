package ru.arc.testing.containers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ContainerSettingsTest : FreeSpec({
    "settings keep image choice flexible while bounding identifiers and scripts" {
        val settings = MySqlTestSettings(
            image = "registry.example.test/custom-mysql:8",
            database = "plugin_test",
            username = "plugin_user",
            initScripts = listOf(MySqlInitScript("mysql/plugin-schema.sql", 20)),
        )
        settings.database shouldBe "plugin_test"
        settings.initScripts.single().containerPath shouldBe "/docker-entrypoint-initdb.d/20-plugin-schema.sql"

        shouldThrow<IllegalArgumentException> { MySqlTestSettings(database = "unsafe-name") }
        shouldThrow<IllegalArgumentException> { MySqlInitScript("../outside.sql") }
    }

    "settings and endpoints redact test credentials in diagnostics" {
        val settings = MySqlTestSettings(password = "plugin-password", rootPassword = "root-password")
        val endpoint = MySqlTestEndpoint("localhost", 3306, "plugin_test", "plugin_user", "endpoint-password")

        settings.toString() shouldContain "password=<redacted>"
        settings.toString() shouldNotContain "plugin-password"
        settings.toString() shouldNotContain "root-password"
        endpoint.toString() shouldContain "password=<redacted>"
        endpoint.toString() shouldNotContain "endpoint-password"
    }
})
