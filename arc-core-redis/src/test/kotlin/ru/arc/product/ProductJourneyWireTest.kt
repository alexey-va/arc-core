package ru.arc.product

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ProductJourneyWireTest :
    FreeSpec({
        "normalizes Velocity command whitespace without retaining arguments" {
            ProductCommandClassifier.root("  /  RTP secret-player 100 64 200 ") shouldBe "rtp"
            ProductCommandClassifier.root("/RTP\tsecret-player") shouldBe "rtp"
            ProductCommandClassifier.classify("rtp secret-player")?.feature shouldBe ProductFeature.RTP
        }

        "round-trips the shared backend and connection vocabulary" {
            val now = 1_800_000_000_000L
            val signal =
                ProductSignal(
                    eventId = ProductPseudonym.eventId(),
                    source = "proxy",
                    player = ProductPseudonym.of("player"),
                    occurredAt = now,
                    kind = ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.SERVER_TARGET, "classic_survival"),
                )

            val payload = ProductWireCodec.encode(signal, Gson())

            ProductWireCodec.decode(payload, "proxy", now, 35, Gson()) shouldBe signal
            ProductWireCodec.decode(payload.replace("classic_survival", "../../secret"), "proxy", now, 35, Gson()).shouldBeNull()
        }

        "round-trips onboarding outcomes without losing the exact mechanic" {
            val now = 1_800_000_000_000L
            val signal =
                ProductSignal(
                    eventId = ProductPseudonym.eventId(),
                    source = "survival",
                    player = ProductPseudonym.of("new-player"),
                    occurredAt = now,
                    kind = ProductEventKind.MEANINGFUL_OUTCOME,
                    path = ProductPath.SETTLER,
                    feature = ProductFeature.AUTOBUILD,
                    activity = ProductActivity.BUILDING,
                    outcome = ProductOutcome.AUTOBUILD_COMPLETE,
                )

            ProductWireCodec.decode(ProductWireCodec.encode(signal, Gson()), "survival", now, 35, Gson()) shouldBe signal
        }

        "round-trips only bounded onboarding hint details" {
            val now = 1_800_000_000_000L
            val signal =
                ProductSignal(
                    eventId = ProductPseudonym.eventId(),
                    source = "survival",
                    player = ProductPseudonym.of("new-player"),
                    occurredAt = now,
                    kind = ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.ONBOARDING_HINT, ProductOnboardingHint.FOOTHOLD_MISMATCH.label),
                )
            val payload = ProductWireCodec.encode(signal, Gson())

            ProductWireCodec.decode(payload, "survival", now, 35, Gson()) shouldBe signal
            ProductWireCodec.decode(payload.replace("foothold_mismatch", "invented_hint"), "survival", now, 35, Gson()).shouldBeNull()
        }

        "rejects unknown connection labels" {
            val now = 1_800_000_000_000L
            val signal =
                ProductSignal(
                    eventId = ProductPseudonym.eventId(),
                    source = "proxy",
                    player = ProductPseudonym.of("player"),
                    occurredAt = now,
                    kind = ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.CONNECTION, ProductConnection.SERVER_SWITCH.label),
                )
            val payload = ProductWireCodec.encode(signal, Gson()).replace("server_switch", "invented")

            ProductWireCodec.decode(payload, "proxy", now, 35, Gson()).shouldBeNull()
        }

        "rejects unknown optional enum values instead of silently dropping them" {
            val now = 1_800_000_000_000L
            val signal =
                ProductSignal(
                    eventId = ProductPseudonym.eventId(),
                    source = "proxy",
                    player = ProductPseudonym.of("player"),
                    occurredAt = now,
                    kind = ProductEventKind.FEATURE_INTEREST,
                    feature = ProductFeature.RTP,
                )
            val payload = ProductWireCodec.encode(signal, Gson()).replace("\"rtp\"", "\"invented\"")

            ProductWireCodec.decode(payload, "proxy", now, 35, Gson()).shouldBeNull()
        }
    })
