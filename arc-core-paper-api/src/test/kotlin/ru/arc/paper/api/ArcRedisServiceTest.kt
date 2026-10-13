package ru.arc.paper.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class ArcRedisServiceTest : FreeSpec({
    "settings retain full connection identity while redacting the password" {
        val password = "redis-secret-test-value"
        val settings = ArcRedisSettings("redis.internal", 6380, "arc", password)

        settings shouldBe ArcRedisSettings("redis.internal", 6380, "arc", password)
        (settings == ArcRedisSettings("redis.internal", 6381, "arc", password)) shouldBe false
        (settings == ArcRedisSettings("redis.internal", 6380, "other", password)) shouldBe false
        (settings == ArcRedisSettings("redis.internal", 6380, "arc", "different-secret")) shouldBe false
        settings.toString().contains(password) shouldBe false
        settings.toString().contains("<redacted>") shouldBe true
    }

    "rejects unusable endpoints and impossible diagnostics" {
        shouldThrow<IllegalArgumentException> { ArcRedisSettings(" ", 6379, null, null) }
        shouldThrow<IllegalArgumentException> { ArcRedisSettings("localhost", 0, null, null) }
        shouldThrow<IllegalArgumentException> { ArcRedisSettings("localhost", 65_536, null, null) }
        shouldThrow<IllegalArgumentException> { ArcRedisDiagnostics(1, 0, 0, 0) }
        shouldThrow<IllegalArgumentException> { ArcRedisDiagnostics(0, 1, 2, 0) }
    }

    "service and lease calls expose Java and JDK types without Kotlin default bridges" {
        val service = ArcRedisService::class.java
        val acquire = service.getMethod(
            "acquire",
            String::class.java,
            ArcRedisSettings::class.java,
            String::class.java,
        )
        acquire.returnType shouldBe ArcRedisLease::class.java
        service.getMethod("acquirePrimary", String::class.java).returnType shouldBe ArcRedisLease::class.java
        service.getMethod("diagnostics").returnType shouldBe ArcRedisDiagnostics::class.java

        val lease = ArcRedisLease::class.java
        lease.getMethod("healthCheck").returnType shouldBe CompletableFuture::class.java
        lease.getMethod("isConnected").returnType shouldBe Boolean::class.javaPrimitiveType
        lease.getMethod("isSubscriptionActive").returnType shouldBe Boolean::class.javaPrimitiveType
        lease.getMethod("channelCount").returnType shouldBe Int::class.javaPrimitiveType
        lease.getMethod("channels").returnType shouldBe Set::class.java
        lease.getMethod("saveMapEntries", String::class.java, Array<String>::class.java).returnType shouldBe
            CompletableFuture::class.java
        lease.getMethod("loadMap", String::class.java).returnType shouldBe CompletableFuture::class.java
        lease.getMethod("loadMapEntries", String::class.java, Array<String>::class.java).returnType shouldBe
            CompletableFuture::class.java
        lease.getMethod(
            "compareAndSetMapEntry",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        ).returnType shouldBe CompletableFuture::class.java

        service.declaredMethods.none { it.name.endsWith("\$default") } shouldBe true
        lease.declaredMethods.none { it.name.endsWith("\$default") } shouldBe true
        ArcRedisSettings::class.java.declaredConstructors.none {
            it.parameterTypes.any { parameter -> parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
        } shouldBe true
    }
})
