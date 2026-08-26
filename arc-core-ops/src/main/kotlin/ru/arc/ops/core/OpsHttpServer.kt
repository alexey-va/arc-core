package ru.arc.ops.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.arc.util.Logging
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import ru.arc.observability.RuntimeHealthProvider

/**
 * JDK [HttpServer] for authenticated ops endpoints under `/ops/`.
 */
class OpsHttpServer(
    private val configProvider: () -> OpsHttpConfig,
    private val router: OpsRouter,
    private val executorFactory: (Int) -> ExecutorService = { threadPoolSize ->
        Executors.newFixedThreadPool(threadPoolSize) { runnable ->
            Thread(runnable, "arc-ops-http").apply { isDaemon = true }
        }
    },
) {
    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null

    val actualPort: Int
        get() = httpServer?.address?.port ?: configProvider().bindPort

    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.enabled) return

        OpsLogBuffer.resize(cfg.errorBufferSize)

        val address = InetSocketAddress(cfg.bindHost, cfg.bindPort)
        val server = HttpServer.create(address, 0)
        server.createContext("/ops") { exchange -> handle(exchange) }
        val newExecutor = executorFactory(cfg.threadPoolSize)
        server.executor = newExecutor
        try {
            server.start()
        } catch (e: Exception) {
            newExecutor.shutdownNow()
            server.stop(0)
            throw e
        }
        executor = newExecutor
        httpServer = server
        Logging.info(
            "Ops HTTP listening on {}:{} (capabilities={})",
            cfg.bindHost,
            actualPort,
            router.capabilities.all().size,
        )
        if (cfg.token.isBlank() || cfg.token.startsWith("CHANGE_ME")) {
            Logging.warn("Ops HTTP token is not configured — requests will be rejected")
        }
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
        executor?.shutdownNow()
        executor = null
    }

    internal fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                respond(exchange, 204, "")
                return
            }

            val cfg = configProvider()
            val headers = exchange.requestHeaders.mapValues { it.value.firstOrNull().orEmpty() }

            if (!OpsAuth.isAuthorized(headers, cfg.token)) {
                val (code, body) = OpsJson.error(401, "unauthorized")
                respond(exchange, code, body)
                return
            }

            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path.removePrefix("/ops").trim('/')
            val segments = if (path.isEmpty()) emptyList() else path.split('/')
            val query = parseQuery(exchange.requestURI.rawQuery)
            val body = readBody(exchange)

            val request = router.buildRequest(method, segments, query, body, cfg)
            val (code, responseBody) = router.handle(request)
            respond(exchange, code, responseBody)
        } catch (t: Throwable) {
            Logging.error("Ops HTTP handler failed", t)
            val (code, body) = OpsJson.error(500, t.message ?: "internal_error")
            respond(exchange, code, body)
        } finally {
            exchange.close()
        }
    }

    companion object {
        fun create(
            configProvider: () -> OpsHttpConfig,
            platformInfo: OpsPlatformInfoProvider,
            consolePort: OpsConsolePort? = null,
            healthProvider: RuntimeHealthProvider? = null,
            executorFactory: (Int) -> ExecutorService = { threadPoolSize ->
                Executors.newFixedThreadPool(threadPoolSize) { runnable ->
                    Thread(runnable, "arc-ops-http").apply { isDaemon = true }
                }
            },
        ): OpsHttpServer =
            OpsHttpServer(
                configProvider = configProvider,
                router = OpsRouter.createStandard(platformInfo, consolePort, healthProvider),
                executorFactory = executorFactory,
            )
    }

    private fun readBody(exchange: HttpExchange): String {
        if (exchange.requestMethod.equals("GET", ignoreCase = true) ||
            exchange.requestMethod.equals("HEAD", ignoreCase = true)
        ) {
            return ""
        }
        return exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
            key to value
        }.toMap()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
