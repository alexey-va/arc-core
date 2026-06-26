package ru.arc.redis.gson

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

@JsonType(
    property = "kind",
    subtypes = [
        JsonSubtype(clazz = WirePing::class, name = "ping"),
        JsonSubtype(clazz = WirePong::class, name = "pong"),
    ],
)
private sealed class WireAction

private data class WirePing(val payload: String) : WireAction()

private data class WirePong(val payload: String, val count: Int = 0) : WireAction()

class PolymorphismAdapterTest : FreeSpec({

    val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(WireAction::class.java, PolymorphismAdapter<WireAction>())
        .create()

    "serialize adds type discriminator" {
        val json = gson.toJson(WirePing("hello"))
        json.contains("\"kind\":\"ping\"") shouldBe true
        json.contains("\"payload\":\"hello\"") shouldBe true
    }

    "deserialize resolves subtype" {
        val json = """{"kind":"pong","payload":"world","count":3}"""
        val action = gson.fromJson(json, WireAction::class.java)
        action.shouldBeInstanceOf<WirePong>()
        (action as WirePong).payload shouldBe "world"
        action.count shouldBe 3
    }

    "roundtrip preserves data" {
        val original = WirePing("arc")
        val restored = gson.fromJson(gson.toJson(original), WireAction::class.java)
        restored.shouldBeInstanceOf<WirePing>()
        (restored as WirePing).payload shouldBe "arc"
    }
})
