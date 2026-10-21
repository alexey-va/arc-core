package ru.arc.paper.packet

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.arc.config.Config
import ru.arc.paper.api.VisualPacketAdmission
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

class PaperVisualPacketRuntimeTest :
    FreeSpec({
        "shares each viewer bucket across sources and refills at the configured rate" {
            val clock = AtomicLong(0L)
            val runtime = PaperVisualPacketRuntime(settings(serverMbps = 10.0, viewerMbps = 1.0), clock::get)
            val connection = Any()

            runtime.acquire("arc:boards", connection, 125_000, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("farms:raids", connection, 1, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.VIEWER_RATE

            clock.addAndGet(500_000_000L)
            runtime.acquire("farms:raids", connection, 62_500, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED

            val snapshot = runtime.snapshot()
            snapshot.totals.attempts shouldBe 3L
            snapshot.totals.admitted shouldBe 2L
            snapshot.totals.viewerRate shouldBe 1L
            snapshot.sources.keys shouldBe setOf("arc:boards", "farms:raids", PaperVisualPacketRuntime.OVERFLOW_SOURCE)
        }

        "enforces the server bucket across different viewers" {
            val runtime = PaperVisualPacketRuntime(settings(serverMbps = 1.0, viewerMbps = 1.0), { 0L })

            runtime.acquire("one:visual", Any(), 62_500, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("two:visual", Any(), 62_500, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("three:visual", Any(), 1, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.SERVER_RATE

            runtime.snapshot().totals.serverRate shouldBe 1L
        }

        "an exact source exception sustains twelve moving maps without raising the ordinary shared viewer limit" {
            val clock = AtomicLong()
            val source = "arccasino:console-doom"
            val runtime = PaperVisualPacketRuntime(PaperVisualPacketRuntime.Settings(
                serverMbps = 32.0, viewerMbps = 2.0, burstMillis = 250,
                sourceViewerMbps = mapOf(source to 24.0),
            ), clock::get)
            val connection = Any()
            runtime.acquire("arc:boards", connection, 62_500, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("farms:displays", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE

            repeat(32) {
                clock.addAndGet(125_000_000L)
                // PacketEvents' 1.21.11 encoded 128x128 map update: 16,401 bytes.
                repeat(12) { runtime.acquire(source, connection, 16_401, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED }
                runtime.acquire("arc:boards", connection, 15_625, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
                runtime.acquire("farms:displays", connection, 15_625, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
                runtime.acquire("arccasino:console-doom-extra", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            }
            runtime.snapshot().sources.getValue(source).admitted shouldBe 384L
            runtime.snapshot().settings.viewerMbps shouldBe 2.0
            runtime.snapshot().viewerBuckets shouldBe 1
        }

        "source exceptions remain per connection and share the server cap and channel pressure" {
            val runtime = PaperVisualPacketRuntime(PaperVisualPacketRuntime.Settings(
                sourceViewerMbps = mapOf("game:video" to 24.0)), { 0L })
            val first = Any()
            val second = Any()
            runtime.acquire("game:video", first, 750_000, 1, false, false) shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
            runtime.acquire("game:video", first, 750_000, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("game:video", first, 1, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            runtime.acquire("game:video", second, 250_000, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("game:video", second, 1, 1, false, true) shouldBe VisualPacketAdmission.SERVER_RATE
            runtime.snapshot().viewerBuckets shouldBe 2
            runtime.snapshot().totals.channelBackpressure shouldBe 1L
        }

        "source cleanup waits for the channel and charges its exact cost to its own lane and the server" {
            val clock = AtomicLong()
            val runtime = PaperVisualPacketRuntime(PaperVisualPacketRuntime.Settings(
                sourceViewerMbps = mapOf("game:video" to 24.0)), clock::get)
            val connection = Any()
            runtime.acquire("game:video", connection, 1_000_000, 1, true, false) shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
            runtime.acquire("game:video", connection, 1_000_000, 1, true, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("game:video", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.SERVER_RATE
            clock.addAndGet(100_000_000L)
            runtime.acquire("game:video", connection, 50_000, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("game:video", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            runtime.acquire("arc:ordinary", connection, 62_500, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.close()
            runtime.acquire("game:video", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.CLOSED
            runtime.acquire("game:video", connection, 1, 1, true, true) shouldBe VisualPacketAdmission.ALLOWED
        }

        "reloading source exceptions retains balances and debt and cannot mint bursts by removing and adding a lane" {
            val clock = AtomicLong()
            val initial = PaperVisualPacketRuntime.Settings(serverMbps = 100.0, sourceViewerMbps = mapOf("game:video" to 24.0))
            val runtime = PaperVisualPacketRuntime(initial, clock::get)
            val connection = Any()
            runtime.acquire("game:video", connection, 700_000, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.updateSettings(initial.copy(sourceViewerMbps = mapOf("game:video" to 32.0)))
            runtime.acquire("game:video", connection, 60_000, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            runtime.acquire("game:video", connection, 100_000, 1, true, true) shouldBe VisualPacketAdmission.ALLOWED

            runtime.updateSettings(initial.copy(sourceViewerMbps = emptyMap()))
            runtime.acquire("arc:ordinary", connection, 20_000, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            runtime.acquire("game:video", connection, 12_500, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("arc:ordinary", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            runtime.updateSettings(initial)
            runtime.acquire("game:video", connection, 1, 1, false, true) shouldBe VisualPacketAdmission.VIEWER_RATE
            clock.addAndGet(50_000_000L)
            runtime.acquire("game:video", connection, 150_000, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("arc:ordinary", connection, 12_500, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
        }

        "source policy parsing is exact bounded immutable and rejects malformed reloads atomically" {
            val directory = Files.createTempDirectory("source-viewer-policy")
            val path = directory.resolve("visual-packets.yml")
            try {
                Files.writeString(path, "source-viewer-mbps:\n  'game:video.wall': 24.0\n")
                val config = Config(directory, "visual-packets.yml")
                val parsed = PaperVisualPacketRuntime.Settings.from(config)
                parsed.sourceViewerMbps shouldBe mapOf("game:video.wall" to 24.0)
                parsed.viewerMbps shouldBe 2.0
                val mutableRates = mutableMapOf("game:video.wall" to 24.0)
                val runtime = PaperVisualPacketRuntime(parsed.copy(sourceViewerMbps = mutableRates), { 0L })
                mutableRates.clear()
                runtime.snapshot().settings.sourceViewerMbps shouldBe parsed.sourceViewerMbps
                shouldThrow<UnsupportedOperationException> {
                    (runtime.snapshot().settings.sourceViewerMbps as MutableMap<String, Double>)["other:source"] = 100.0
                }
                listOf("[]", "fast", "{bad source: 24}", "{game:video: fast}", "{game:video: -1}", "{game:video: .nan}").forEach { invalid ->
                    Files.writeString(path, "source-viewer-mbps: $invalid\n")
                    config.reload()
                    shouldThrow<IllegalArgumentException> { runtime.reload(config) }
                    runtime.snapshot().settings.sourceViewerMbps shouldBe parsed.sourceViewerMbps
                }
                shouldThrow<IllegalArgumentException> {
                    parsed.copy(sourceViewerMbps = (0..PaperVisualPacketRuntime.MAX_SOURCES).associate { "source:$it" to 24.0 })
                }
                Files.writeString(path, "source-viewer-mbps: {}\n")
                config.reload()
                runtime.reload(config)
                runtime.snapshot().settings.sourceViewerMbps shouldBe emptyMap()
            } finally { directory.toFile().deleteRecursively() }
        }

        "admits one oversized transaction from full buckets and makes it repay the debt" {
            val clock = AtomicLong(0L)
            val runtime = PaperVisualPacketRuntime(settings(serverMbps = 1.0, viewerMbps = 1.0), clock::get)
            val connection = Any()

            runtime.acquire("arc:large-frame", connection, 150_000, 2, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("arc:large-frame", connection, 150_000, 2, cleanup = false, writable = true) shouldBe VisualPacketAdmission.SERVER_RATE

            clock.addAndGet(1_300_000_000L)
            runtime.acquire("arc:large-frame", connection, 150_000, 2, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
        }

        "cleanup bypasses rate admission but pays its full cost and still waits for writability" {
            val runtime = PaperVisualPacketRuntime(settings(serverMbps = 1.0, viewerMbps = 1.0), { 0L })
            val connection = Any()

            runtime.acquire("arc:frame", connection, 100_000, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("arc:remove", connection, 100_000, 1, cleanup = true, writable = false) shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
            runtime.acquire("arc:remove", connection, 100_000, 1, cleanup = true, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("arc:next-frame", connection, 1, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.SERVER_RATE

            runtime.recordSent("arc:remove", 100_000, 1, cleanup = true)
            val totals = runtime.snapshot().totals
            totals.cleanupAdmitted shouldBe 1L
            totals.cleanupBytes shouldBe 100_000L
            totals.channelBackpressure shouldBe 1L
        }

        "closed providers reject ordinary packets but allow writable cleanup" {
            val runtime = PaperVisualPacketRuntime(settings(), { 0L })
            val connection = Any()
            runtime.close()

            runtime.acquire("arc:frame", connection, 1, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.CLOSED
            runtime.acquire("arc:remove", connection, 1, 1, cleanup = true, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.acquire("arc:remove", connection, 1, 1, cleanup = true, writable = false) shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
        }

        "hot reload preserves current token balance and invalid candidates leave settings unchanged" {
            val clock = AtomicLong(0L)
            val runtime = PaperVisualPacketRuntime(settings(serverMbps = 10.0, viewerMbps = 1.0), clock::get)
            val connection = Any()
            runtime.acquire("arc:frame", connection, 60_000, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED

            runtime.updateSettings(settings(serverMbps = 10.0, viewerMbps = 2.0))
            runtime.acquire("arc:next", connection, 70_000, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.VIEWER_RATE

            val directory = Files.createTempDirectory("visual-packet-config")
            val config = Config(directory, "visual-packets.yml")
            shouldThrow<IllegalArgumentException> { runtime.reload(config) }
            config.setDouble("server-mbps", -1.0)
            shouldThrow<IllegalArgumentException> { runtime.reload(config) }
            runtime.snapshot().settings.viewerMbps shouldBe 2.0
            runtime.snapshot().settings.serverMbps shouldBe 10.0
        }

        "settings accept missing defaults and reject malformed or fractional values" {
            val config = Config(Files.createTempDirectory("visual-packet-settings"), "visual-packets.yml")
            PaperVisualPacketRuntime.Settings.from(config) shouldBe PaperVisualPacketRuntime.Settings()

            config.setString("server-mbps", "fast")
            shouldThrow<IllegalArgumentException> { PaperVisualPacketRuntime.Settings.from(config) }

            config.setDouble("server-mbps", 32.0)
            config.setDouble("burst-ms", 250.5)
            shouldThrow<IllegalArgumentException> { PaperVisualPacketRuntime.Settings.from(config) }

            config.setLong("burst-ms", 250L)
            config.setString("log-interval-seconds", "many")
            shouldThrow<IllegalArgumentException> { PaperVisualPacketRuntime.Settings.from(config) }

            config.setLong("log-interval-seconds", 30L)
            PaperVisualPacketRuntime.Settings.from(config).logIntervalSeconds shouldBe 30L
        }

        "source counters and low-cardinality metrics reflect only recorded transport writes" {
            val runtime = PaperVisualPacketRuntime(settings(), { 0L })
            val connection = Any()
            runtime.acquire("arc:boards", connection, 32, 2, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.recordSent("arc:boards", 16, 1, cleanup = false)
            runtime.recordSent("arc:boards", 16, 1, cleanup = false)
            runtime.acquire("bad source with player id", connection, 64, 1, cleanup = false, writable = true) shouldBe VisualPacketAdmission.ALLOWED
            runtime.recordSent("bad source with player id", 64, 1, cleanup = false)

            val snapshot = runtime.snapshot()
            snapshot.sources.size shouldBe 2
            snapshot.totals.sentBytes shouldBe 96L
            snapshot.totals.sentPackets shouldBe 3L
            snapshot.totals.admitted shouldBe 2L
            snapshot.sources.keys shouldBe setOf("arc:boards", PaperVisualPacketRuntime.OVERFLOW_SOURCE)
            runtime.metricPoints().any { it.name == "arc_visual_packet_payload_bytes_total" && it.value == 96.0 } shouldBe true
            val deferredPoints = runtime.metricPoints().filter { it.name == "arc_visual_packet_deferred_total" }
            deferredPoints shouldHaveSize VisualPacketAdmission.entries.count { it != VisualPacketAdmission.ALLOWED }
        }

        "concurrent acquisitions cannot overspend a shared viewer bucket" {
            val runtime = PaperVisualPacketRuntime(settings(serverMbps = 10.0, viewerMbps = 1.0), { 0L })
            val connection = Any()
            val workers = 200
            val ready = CountDownLatch(16)
            val start = CountDownLatch(1)
            val accepted = AtomicInteger()
            val pool = Executors.newFixedThreadPool(16)
            try {
                repeat(workers) {
                    pool.submit {
                        ready.countDown()
                        start.await()
                        if (runtime.acquire("arc:concurrent", connection, 1_000, 1, cleanup = false, writable = true) == VisualPacketAdmission.ALLOWED) {
                            accepted.incrementAndGet()
                        }
                    }
                }
                ready.await(5, TimeUnit.SECONDS) shouldBe true
                start.countDown()
                pool.shutdown()
                pool.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                pool.shutdownNow()
            }
            accepted.get() shouldBe 125
            runtime.snapshot().totals.admitted shouldBe 125L
        }
    }) {
    companion object {
        private fun settings(
            serverMbps: Double = 32.0,
            viewerMbps: Double = 2.0,
        ) =
            PaperVisualPacketRuntime.Settings(
                serverMbps = serverMbps,
                viewerMbps = viewerMbps,
                burstMillis = 1_000L,
            )
    }
}
