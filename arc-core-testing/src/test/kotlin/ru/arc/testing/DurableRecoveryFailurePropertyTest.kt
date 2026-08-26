package ru.arc.testing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecoveryCompletion
import ru.arc.persistence.DurableRecoveryWorkflow
import java.util.concurrent.CompletableFuture

class DurableRecoveryFailurePropertyTest : FreeSpec({
    data class Record(val id: String, val checksum: String)
    data class Receipt(val checksum: String)

    "every pre-commit failure leaves no durable intent and never mutates gameplay" {
        checkAll(iterations = 100, Arb.stringPattern("[a-z]{4,12}"), Arb.stringPattern("[a-f0-9]{16}")) { id, checksum ->
            val candidate = Record(id, checksum)
            var durable: Record? = null
            var mutations = 0
            val injector = FailureInjector().apply { failNext("before-commit") }
            val workflow =
                DurableRecoveryWorkflow<Record, Receipt>(
                    commit = { record ->
                        runCatching {
                            injector.check("before-commit")
                            durable = record
                            record
                        }.fold(
                            onSuccess = CompletableFuture<Record>::completedFuture,
                            onFailure = CompletableFuture<Record>::failedFuture,
                        )
                    },
                    sameContent = Record::equals,
                    acknowledge = { _, _ -> error("No acknowledgement is valid without a durable record") },
                )

            runCatching {
                workflow.commitThenMutate(candidate) {
                    mutations++
                    CompletableFuture.completedFuture(it.checksum)
                }.join()
            }

            durable shouldBe null
            mutations shouldBe 0
        }
    }

    "every named post-commit crash point converges without loss or duplicate acknowledgement" {
        checkAll(iterations = 100, Arb.stringPattern("[a-z]{4,12}"), Arb.stringPattern("[a-f0-9]{16}")) { id, checksum ->
            listOf("after-commit", "after-mutation", "after-restore", "before-acknowledgement").forEach { crashPoint ->
                val injector = FailureInjector().apply { failNext(crashPoint) }
                val candidate = Record(id, checksum)
                var durable: Record? = null
                var mutations = 0
                var restores = 0
                var acknowledgements = 0

                fun workflow() = DurableRecoveryWorkflow<Record, Receipt>(
                    commit = { record ->
                        durable = record
                        runCatching { injector.check("after-commit") }
                            .fold(
                                onSuccess = { CompletableFuture.completedFuture(record) },
                                onFailure = CompletableFuture<Record>::failedFuture,
                            )
                    },
                    sameContent = Record::equals,
                    acknowledge = { record, receipt ->
                        runCatching { injector.check("before-acknowledgement") }
                            .fold(
                                onSuccess = {
                                    if (durable == record && receipt.checksum == record.checksum) {
                                        durable = null
                                        acknowledgements++
                                        CompletableFuture.completedFuture(DurableAcknowledgementOutcome.ACKNOWLEDGED)
                                    } else {
                                        CompletableFuture.completedFuture(DurableAcknowledgementOutcome.CONTENT_MISMATCH)
                                    }
                                },
                                onFailure = CompletableFuture<DurableAcknowledgementOutcome>::failedFuture,
                            )
                    },
                )

                runCatching {
                    workflow().commitThenMutate(candidate) { committed ->
                        mutations++
                        runCatching { injector.check("after-mutation") }
                            .fold(
                                onSuccess = { CompletableFuture.completedFuture(committed.checksum) },
                                onFailure = CompletableFuture<String>::failedFuture,
                            )
                    }.join()
                }

                val committed = durable ?: candidate.also { durable = it }
                runCatching {
                    workflow().restoreThenAcknowledge(committed) { record ->
                        restores++
                        runCatching { injector.check("after-restore") }
                            .fold(
                                onSuccess = { CompletableFuture.completedFuture(Receipt(record.checksum)) },
                                onFailure = CompletableFuture<Receipt>::failedFuture,
                            )
                    }.join()
                }

                if (durable != null) {
                    workflow().restoreThenAcknowledge(requireNotNull(durable)) {
                        restores++
                        CompletableFuture.completedFuture(Receipt(it.checksum))
                    }.join() shouldBe DurableRecoveryCompletion.Acknowledged(candidate, Receipt(checksum))
                }

                durable shouldBe null
                acknowledgements shouldBe 1
                (mutations <= 1) shouldBe true
                (restores in 1..2) shouldBe true
            }
        }
    }
})
