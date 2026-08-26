package ru.arc.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DurableRecordJournalTest : FunSpec({
    data class Record(val revision: Int, val payload: String)

    fun journal(maxBytes: Long = 128): Pair<Path, DurableRecordJournal<Record>> {
        val root = Files.createTempDirectory("arc-durable-journal-")
        return root to DurableRecordJournal(
            root = root,
            relativeDirectory = Path.of("recovery/records"),
            maxRecordBytes = maxBytes,
            encode = { "${it.revision}:${it.payload}".toByteArray(StandardCharsets.UTF_8) },
            decode = { bytes ->
                val (revision, payload) = bytes.toString(StandardCharsets.UTF_8).split(':', limit = 2)
                Record(revision.toInt(), payload)
            },
            validate = { require(it.revision >= 0) },
        )
    }

    test("commits readback and lists records in stable identifier order") {
        val (_, journal) = journal()

        journal.commit("second", Record(2, "later")) shouldBe Record(2, "later")
        journal.commit("first", Record(1, "earlier")) shouldBe Record(1, "earlier")

        journal.loadAll() shouldContainExactly listOf(
            DurableRecord("first", Record(1, "earlier")),
            DurableRecord("second", Record(2, "later")),
        )
    }

    test("acknowledgement is idempotent") {
        val (_, journal) = journal()
        journal.commit("match-1", Record(1, "pending"))

        journal.acknowledge("match-1") shouldBe true
        journal.acknowledge("match-1") shouldBe false
        journal.loadOrNull("match-1") shouldBe null
    }

    test("exact acknowledgement retains a newer record") {
        val (_, journal) = journal()
        journal.commit("match-1", Record(2, "newer"))

        journal.acknowledgeExactly("match-1", Record(1, "older"), Record::equals) shouldBe
            DurableAcknowledgementOutcome.CONTENT_MISMATCH
        journal.loadOrNull("match-1") shouldBe Record(2, "newer")
        journal.acknowledgeExactly("match-1", Record(2, "newer"), Record::equals) shouldBe
            DurableAcknowledgementOutcome.ACKNOWLEDGED
        journal.acknowledgeExactly("match-1", Record(2, "newer"), Record::equals) shouldBe
            DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
    }

    test("rejects traversal and oversized records before commit") {
        val (_, journal) = journal(maxBytes = 8)

        shouldThrow<IllegalArgumentException> { journal.commit("../escape", Record(1, "ok")) }
        shouldThrow<IllegalArgumentException> { journal.commit("record", Record(1, "payload-too-large")) }
    }

    test("rejects invalid durable readback") {
        val (root, journal) = journal()
        journal.commit("record", Record(1, "valid"))
        Files.writeString(root.resolve("recovery/records/record.json"), "-1:tampered")

        shouldThrow<IllegalArgumentException> { journal.loadOrNull("record") }
    }

    test("rejects a symbolic-link journal segment") {
        val root = Files.createTempDirectory("arc-durable-journal-root-")
        val outside = Files.createTempDirectory("arc-durable-journal-outside-")
        Files.createSymbolicLink(root.resolve("linked"), outside)

        shouldThrow<IllegalArgumentException> {
            DurableRecordJournal(
                root = root,
                relativeDirectory = Path.of("linked/records"),
                maxRecordBytes = 128,
                encode = { value: String -> value.toByteArray() },
                decode = ByteArray::decodeToString,
            )
        }
    }
})
