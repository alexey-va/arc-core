package ru.arc.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class AtomicFileStoreTest : FreeSpec({
    lateinit var root: Path

    beforeEach {
        root = createTempDirectory("arc-atomic-store-")
    }

    afterEach {
        root.toFile().deleteRecursively()
    }

    "commits, forces and returns a validated readback" {
        var validations = 0
        val store = stringStore(root, maxBytes = 32) { value ->
            require(value.startsWith("state:"))
            validations++
        }

        store.write("state:one") shouldBe "state:one"
        store.loadOrNull() shouldBe "state:one"
        validations shouldBe 4
        Files.readString(store.path) shouldBe "state:one"
    }

    "rejects oversized existing and outgoing data" {
        val store = stringStore(root, maxBytes = 4)
        shouldThrow<IllegalArgumentException> { store.write("12345") }
        Files.createDirectories(store.path.parent)
        Files.writeString(store.path, "12345")
        shouldThrow<IllegalArgumentException> { store.loadOrNull() }
    }

    "rejects traversal and symbolic links below the trusted root" {
        shouldThrow<IllegalArgumentException> {
            AtomicFileStore(root, Path.of("../escape"), 32, String::toByteArray, ByteArray::decodeToString)
        }
        val outside = createTempDirectory("arc-atomic-outside-")
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside)
            val linked = stringStore(root, Path.of("linked/state.txt"))
            shouldThrow<IllegalArgumentException> { linked.write("blocked") }
            Files.exists(outside.resolve("state.txt")) shouldBe false
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    "rejects a non-round-tripping codec before replacing committed state" {
        val original = stringStore(root)
        original.write("old")
        val store = AtomicFileStore(
            root = root,
            relativePath = Path.of("state/value.txt"),
            maxBytes = 32,
            encode = String::toByteArray,
            decode = { "bad" },
            validate = { require(it != "bad") },
        )
        shouldThrow<IllegalArgumentException> { store.write("good") }
        Files.list(store.path.parent).use { files ->
            files.filter { it.fileName.toString().endsWith(".tmp") }.toList().shouldBeEmpty()
        }
        Files.readString(store.path) shouldBe "old"
    }

    "deletion is idempotent and immediately visible" {
        val store = stringStore(root)
        store.write("value")
        store.deleteIfExists() shouldBe true
        store.deleteIfExists() shouldBe false
        store.loadOrNull() shouldBe null
    }
}) {
    companion object {
        private fun stringStore(
            root: Path,
            relativePath: Path = Path.of("state/value.txt"),
            maxBytes: Long = 1_024,
            validate: (String) -> Unit = {},
        ) = AtomicFileStore(
            root = root,
            relativePath = relativePath,
            maxBytes = maxBytes,
            encode = String::toByteArray,
            decode = ByteArray::decodeToString,
            validate = validate,
        )
    }
}
