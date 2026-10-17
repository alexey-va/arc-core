package ru.arc.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.nio.file.Path
import java.io.File

class BoundedAsyncExecutorTest : FunSpec({
    test("System.exit waits for accepted writes and the terminal worker flush") {
      listOf("before-shutdown", "during-shutdown").forEach { mode ->
        val root = Files.createTempDirectory("arc-exit-drain")
        val output = root.resolve("receipt.txt")
        val classpath = listOf(BoundedAsyncExecutor::class.java, ShutdownDrainProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
            ShutdownDrainProbe::class.java.name, output.toString(), mode).redirectErrorStream(true).start()
        try {
            process.waitFor(10, TimeUnit.SECONDS) shouldBe true
            process.exitValue() shouldBe 0
            Files.readString(output) shouldBe "accepted\nterminal\n"
        } finally { if (process.isAlive) process.destroyForcibly(); root.toFile().deleteRecursively() }
      }
    }

    test("overload rejects without inline work and close returns before accepted work drains") {
        val worker = BoundedAsyncExecutor("bounded-test", 1, 1)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val calls = AtomicInteger()
        val submitter = Thread.currentThread()
        try {
            val first = worker.submit {
                (Thread.currentThread() !== submitter) shouldBe true
                started.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
                calls.incrementAndGet()
            }
            started.await(5, TimeUnit.SECONDS) shouldBe true
            val queued = worker.submit { calls.incrementAndGet() }
            worker.submit { calls.incrementAndGet() }.isCompletedExceptionally shouldBe true
            val drained = worker.closeAsync()
            drained.isDone shouldBe false
            worker.submit { calls.incrementAndGet() }.isCompletedExceptionally shouldBe true
            calls.get() shouldBe 0
            unblock.countDown()
            first.get(5, TimeUnit.SECONDS)
            queued.get(5, TimeUnit.SECONDS)
            drained.get(5, TimeUnit.SECONDS)
            calls.get() shouldBe 2
        } finally {
            unblock.countDown()
            worker.close()
        }
    }

    test("failed or observer-cancelled work cannot abort accepted durable siblings") {
        val worker = BoundedAsyncExecutor("bounded-failure-test", 1, 4)
        try {
            val failed = worker.submit<Int> { error("write failed") }
            val calls = AtomicInteger()
            val observation = worker.submit { calls.incrementAndGet() }
            observation.cancel(false)
            worker.closeAsync().get(5, TimeUnit.SECONDS)
            failed.isCompletedExceptionally shouldBe true
            calls.get() shouldBe 1
        } finally {
            worker.close()
        }
    }

    test("reserved finalizer waits for every worker and never runs on the closing caller") {
        val worker = BoundedAsyncExecutor("terminal-test", 2, 1)
        val started = CountDownLatch(2)
        val unblock = CountDownLatch(1)
        val calls = AtomicInteger()
        val caller = Thread.currentThread()
        try {
            repeat(2) {
                worker.execute { started.countDown(); check(unblock.await(5, TimeUnit.SECONDS)); calls.incrementAndGet() }
            }
            started.await(5, TimeUnit.SECONDS) shouldBe true
            worker.execute { calls.incrementAndGet() }
            val result = worker.closeAsync {
                calls.get() shouldBe 3
                (Thread.currentThread() !== caller) shouldBe true
                Thread.currentThread().isDaemon shouldBe false
                calls.incrementAndGet()
            }
            result.isDone shouldBe false
            unblock.countDown()
            result.get(5, TimeUnit.SECONDS)
            calls.get() shouldBe 4
            worker.closeAsync { error("late flush") }.isCompletedExceptionally shouldBe true
        } finally { unblock.countDown(); worker.close() }
    }

    test("empty terminal flush is dispatched and its failure remains observable") {
        val worker = BoundedAsyncExecutor("empty-terminal-test")
        val caller = Thread.currentThread()
        val result = worker.closeAsync {
            (Thread.currentThread() !== caller) shouldBe true
            error("terminal write failed")
        }
        runCatching { result.get(5, TimeUnit.SECONDS) }
        result.isCompletedExceptionally shouldBe true
    }
})
