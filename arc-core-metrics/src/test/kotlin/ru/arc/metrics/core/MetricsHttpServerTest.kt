package ru.arc.metrics.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ru.arc.config.Config
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.Executors

class MetricsHttpServerTest :
    FreeSpec({
        "serves cached Prometheus text and stops its worker" {
            val directory = Files.createTempDirectory("arc-metrics-http")
            Files.writeString(
                directory.resolve("metrics.yml"),
                """
                enabled: true
                bind-host: "127.0.0.1"
                bind-port: 0
                """.trimIndent(),
            )
            val config = MetricsConfig(Config(directory, "metrics.yml"))
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val gauges = CachedGaugeStore(registry)
            gauges.applySnapshot(
                "test",
                listOf(MetricPoint("arc_test_value", "test", 7.0)),
            )
            val executor = Executors.newSingleThreadExecutor()
            val server = MetricsHttpServer(registry, config, executorFactory = { executor })

            server.start()
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${server.actualPort}/metrics"))
                    .GET()
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            server.stop()

            response.statusCode() shouldBe 200
            response.body() shouldContain "arc_test_value 7.0"
            response.body() shouldContain "arc_metrics_scrape_requests_total"
            executor.isShutdown.shouldBeTrue()
        }
    })
