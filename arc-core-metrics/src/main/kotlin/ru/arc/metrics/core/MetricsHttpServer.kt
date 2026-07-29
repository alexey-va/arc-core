package ru.arc.metrics.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Local scrape server. All expensive values are cached before a request arrives. */
class MetricsHttpServer(
    private val registry: PrometheusMeterRegistry,
    private val config: MetricsConfig,
    private val executorFactory: () -> ExecutorService = {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arc-metrics-http").apply { isDaemon = true }
        }
    },
) {
    private val log = LoggerFactory.getLogger(MetricsHttpServer::class.java)
    private val scrapeRequests = Counter.builder("arc_metrics_scrape_requests").register(registry)
    private val scrapeFailures = Counter.builder("arc_metrics_scrape_failures").register(registry)
    private val scrapeDuration = Timer.builder("arc_metrics_scrape_duration").register(registry)

    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null

    val actualPort: Int
        get() = httpServer?.address?.port ?: config.bindPort

    fun start() {
        stop()
        if (!config.enabled) return

        val server = HttpServer.create(InetSocketAddress(config.bindHost, config.bindPort), 0)
        server.createContext("/metrics", ::handleMetrics)
        server.createContext("/health") { exchange ->
            try {
                respond(exchange, 200, "ok\n", "text/plain; charset=utf-8")
            } finally {
                exchange.close()
            }
        }
        val newExecutor = executorFactory()
        server.executor = newExecutor
        try {
            server.start()
        } catch (failure: Exception) {
            newExecutor.shutdownNow()
            server.stop(0)
            throw failure
        }
        executor = newExecutor
        httpServer = server
        log.info("Prometheus metrics listening on {}:{}", config.bindHost, actualPort)
    }

    fun stop() {
        httpServer?.stop(1)
        httpServer = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handleMetrics(exchange: HttpExchange) {
        scrapeRequests.increment()
        val timer = Timer.start(registry)
        try {
            if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                respond(exchange, 405, "Method Not Allowed\n", "text/plain; charset=utf-8")
                return
            }
            respond(
                exchange,
                200,
                registry.scrape(),
                "text/plain; version=0.0.4; charset=utf-8",
            )
        } catch (failure: Throwable) {
            scrapeFailures.increment()
            log.error("Prometheus scrape failed", failure)
            runCatching { respond(exchange, 500, "error\n", "text/plain; charset=utf-8") }
        } finally {
            timer.stop(scrapeDuration)
            exchange.close()
        }
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
