package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class RedisWireTest : FreeSpec({

    "encode" {
        RedisWire.encode("spawn", "hello") shouldBe "spawn<>#<>#<>hello"
    }

    "decode" {
        RedisWire.decode("spawn<>#<>#<>hello") shouldBe ("spawn" to "hello")
    }

    "decode invalid" {
        RedisWire.decode("no-delimiter").shouldBeNull()
    }
})
