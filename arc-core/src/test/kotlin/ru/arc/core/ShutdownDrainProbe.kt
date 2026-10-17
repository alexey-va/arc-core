package ru.arc.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Separate JVM oracle: System.exit normally kills non-daemon accepted work too. */
object ShutdownDrainProbe {
    @JvmStatic fun main(args: Array<String>) {
        val output = Path.of(args[0])
        val shutdownStarted = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { shutdownStarted.countDown() })
        val worker = BoundedAsyncExecutor("exit-probe", 1, 2)
        worker.submit {
            check(shutdownStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(150) // Deterministic delayed I/O after ordinary shutdown hooks finish.
            Files.writeString(output, "accepted\n")
        }
        val stopOwner = Runnable { worker.closeAsync { Files.writeString(output, "terminal\n", APPEND) } }
        if (args.getOrNull(1) == "during-shutdown") Runtime.getRuntime().addShutdownHook(Thread(stopOwner))
        else stopOwner.run()
        exitProcess(0)
    }
}
