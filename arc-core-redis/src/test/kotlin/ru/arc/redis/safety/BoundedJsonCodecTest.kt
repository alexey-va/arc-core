package ru.arc.redis.safety

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class BoundedJsonCodecTest : FreeSpec({
    data class Wire(
        val protocolVersion: Int,
        val id: String,
        val origin: String,
        val values: List<String> = emptyList(),
    )

    fun codec(bounds: JsonResourceBounds = JsonResourceBounds(512, maxStringCharacters = 64)) = BoundedJsonCodec(
        gson = Gson(),
        type = Wire::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("protocolVersion", "id", "origin", "values"),
            requiredFields = setOf("protocolVersion", "id", "origin"),
        ),
        bounds = bounds,
        validate = { wire ->
            require(wire.protocolVersion == 1)
            require(wire.id.matches(Regex("[a-z0-9_-]{1,32}")))
            require(wire.origin.matches(Regex("[a-z0-9_-]{1,32}")))
            require(wire.values.size <= 8)
        },
    )

    "round-trips a validated closed wire type" {
        val codec = codec()
        val value = Wire(1, "message_1", "spawn", listOf("a", "b"))
        codec.decode(codec.encode(value)) shouldBe value
    }

    "rejects unknown, missing and invalid domain fields" {
        val codec = codec()
        shouldThrow<IllegalArgumentException> {
            codec.decode("""{"protocolVersion":1,"id":"ok","origin":"spawn","command":"op"}""")
        }
        shouldThrow<IllegalArgumentException> { codec.decode("""{"protocolVersion":1,"id":"ok"}""") }
        shouldThrow<IllegalArgumentException> {
            codec.decode("""{"protocolVersion":2,"id":"ok","origin":"spawn"}""")
        }
    }

    "uses Gson 2.11 strict parsing, consumes the document and rejects duplicate fields" {
        val codec = codec()
        listOf(
            """{'protocolVersion':1,'id':'ok','origin':'spawn'}""",
            """{"protocolVersion":1,"id":"ok","origin":"spawn"} trailing""",
            """{"protocolVersion":1,"id":"ok","origin":"spawn",}""",
            """{"protocolVersion":1,"id":"first","id":"second","origin":"spawn"}""",
        ).forEach { raw -> shouldThrow<RuntimeException> { codec.decode(raw) } }
    }

    "enforces payload, string, depth and container bounds before construction" {
        val codec = codec(JsonResourceBounds(160, maxDepth = 3, maxContainerEntries = 2, maxStringCharacters = 8))
        shouldThrow<IllegalArgumentException> { codec.decode("x".repeat(161)) }
        shouldThrow<IllegalArgumentException> {
            codec.decode("""{"protocolVersion":1,"id":"message-too-long","origin":"spawn"}""")
        }
        shouldThrow<IllegalArgumentException> {
            codec.decode("""{"protocolVersion":1,"id":"ok","origin":"spawn","values":["a","b","c"]}""")
        }
        shouldThrow<IllegalArgumentException> {
            codec.decode("""{"protocolVersion":1,"id":"ok","origin":"spawn","values":[["a"]]}""")
        }
        val deeplyNested = "[".repeat(40) + "0" + "]".repeat(40)
        shouldThrow<IllegalArgumentException> {
            codec.decode("""{"protocolVersion":1,"id":"ok","origin":"spawn","values":$deeplyNested}""")
        }
    }

    "supports a bounded root array without weakening object contracts" {
        val listType = object : TypeToken<List<String>>() {}.type
        val arrayCodec =
            BoundedJsonCodec.forType<List<String>>(
                gson = Gson(),
                type = listType,
                rootContract = JsonArrayContract(maxEntries = 2),
                bounds = JsonResourceBounds(64, maxDepth = 2, maxContainerEntries = 2, maxStringCharacters = 8),
                validate = { values -> require(values.all { it.isNotBlank() }) },
            )

        arrayCodec.decode("[\"one\",\"two\"]") shouldBe listOf("one", "two")
        shouldThrow<IllegalArgumentException> { arrayCodec.decode("[\"one\",\"two\",\"three\"]") }
        shouldThrow<IllegalArgumentException> { arrayCodec.decode("{\"one\":\"two\"}") }
    }

    "applies nested contracts before Gson can discard unknown fields" {
        data class Entry(val id: String)
        val listType = object : TypeToken<List<Entry>>() {}.type
        val codec =
            BoundedJsonCodec.forType<List<Entry>>(
                gson = Gson(),
                type = listType,
                rootContract =
                    JsonArrayContract(
                        maxEntries = 2,
                        elementContract = JsonObjectContract(setOf("id")),
                    ),
                bounds = JsonResourceBounds(128, maxDepth = 3, maxContainerEntries = 2, maxStringCharacters = 16),
                validate = { entries -> require(entries.all { it.id.isNotBlank() }) },
            )

        codec.decode("[{\"id\":\"one\"}]") shouldBe listOf(Entry("one"))
        shouldThrow<IllegalArgumentException> { codec.decode("[{\"id\":\"one\",\"command\":\"op\"}]") }
    }
})
