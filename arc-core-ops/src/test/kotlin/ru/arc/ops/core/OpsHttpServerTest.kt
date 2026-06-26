package ru.arc.ops.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OpsLogBufferTest : FreeSpec({
    "OpsLogBuffer" - {
        "should keep only recent entries within capacity" {
            OpsLogBuffer.clear()
            OpsLogBuffer.resize(50)
            for (i in 1..52) {
                OpsLogBuffer.append("WARN", "msg$i")
            }

            val recent = OpsLogBuffer.recent(50)
            recent shouldHaveSize 50
            recent.first().message shouldBe "msg3"
            recent.last().message shouldBe "msg52"
        }
    }
})

class OpsRouterTest : FreeSpec({
    val platformInfo =
        OpsPlatformInfoProvider {
            OpsPlatformInfo(platform = "test", serverName = "unit", version = "0")
        }
    val config = TestOpsHttpConfig()

    "OpsRouter" - {
        "should return 404 for unknown route" {
            val router = OpsRouter.createStandard(platformInfo, consolePort = null)
            val request = router.buildRequest("GET", listOf("missing"), emptyMap(), "", config)
            val (code, body) = router.handle(request)
            code shouldBe 404
            body shouldContain "not_found"
        }

        "should return 501 when console port is not wired" {
            val router = OpsRouter.createStandard(platformInfo, consolePort = null)
            val request =
                router.buildRequest(
                    "POST",
                    listOf("console"),
                    emptyMap(),
                    """{"command":"say hi"}""",
                    config,
                )
            val (code, body) = router.handle(request)
            code shouldBe 501
            body shouldContain "not_supported"
            body shouldContain "console.execute"
        }

        "should execute console when port is wired" {
            val router =
                OpsRouter.createStandard(
                    platformInfo,
                    consolePort = OpsConsolePort { command ->
                        OpsResult.success("ran:$command")
                    },
                )
            val request =
                router.buildRequest(
                    "POST",
                    listOf("console"),
                    emptyMap(),
                    """{"command":"say hi"}""",
                    config,
                )
            val (code, body) = router.handle(request)
            code shouldBe 200
            body shouldContain "ran:say hi"
        }

        "should list capabilities including core routes" {
            val router = OpsRouter.createStandard(platformInfo, consolePort = null)
            router.capabilities.all() shouldContain "core.health"
            router.capabilities.all() shouldContain "console.execute"
        }
    }
})

class OpsHttpServerTest : FreeSpec({
    val testConfig =
        TestOpsHttpConfig(
            enabled = true,
            token = "unit-test-token",
            bindHost = "127.0.0.1",
            bindPort = 0,
            consoleEnabled = true,
        )
    val platformInfo =
        OpsPlatformInfoProvider {
            OpsPlatformInfo(platform = "test", serverName = "unit", version = "0")
        }

    "OpsHttpServer" - {
        "should reject requests without token" {
            val server =
                OpsHttpServer.create(
                    configProvider = { testConfig },
                    platformInfo = platformInfo,
                    consolePort = OpsConsolePort { OpsResult.success() },
                )
            server.start()
            try {
                val conn = open("http://127.0.0.1:${server.actualPort}/ops/health")
                conn.responseCode shouldBe 401
                readBody(conn) shouldContain "unauthorized"
            } finally {
                server.stop()
            }
        }

        "should accept authorized health check" {
            val server =
                OpsHttpServer.create(
                    configProvider = { testConfig },
                    platformInfo = platformInfo,
                    consolePort = OpsConsolePort { OpsResult.success() },
                )
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/health",
                        token = testConfig.token,
                    )
                conn.responseCode shouldBe 200
                readBody(conn) shouldContain "up"
            } finally {
                server.stop()
            }
        }

        "should return capabilities list" {
            val server =
                OpsHttpServer.create(
                    configProvider = { testConfig },
                    platformInfo = platformInfo,
                    consolePort = null,
                )
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/capabilities",
                        token = testConfig.token,
                    )
                conn.responseCode shouldBe 200
                readBody(conn) shouldContain "core.health"
            } finally {
                server.stop()
            }
        }
    }
})

private fun open(
    url: String,
    method: String = "GET",
    token: String? = null,
    body: String? = null,
): java.net.HttpURLConnection {
    val conn = java.net.URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 5_000
    conn.readTimeout = 5_000
    token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
    if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(java.nio.charset.StandardCharsets.UTF_8)) }
    }
    return conn
}

private fun readBody(conn: java.net.HttpURLConnection): String {
    val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
    return stream?.bufferedReader(java.nio.charset.StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
}
