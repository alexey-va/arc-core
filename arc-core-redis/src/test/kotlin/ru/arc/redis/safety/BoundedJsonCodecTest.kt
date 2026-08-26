package ru.arc.redis.safety

import com.google.gson.Gson
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
})
