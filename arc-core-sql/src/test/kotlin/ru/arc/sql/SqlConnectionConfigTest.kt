package ru.arc.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SqlConnectionConfigTest : StringSpec({
    "jdbc URL contains transport settings but never credentials" {
        val config =
            SqlConnectionConfig(
                host = "mysql.internal",
                database = "minecraft",
                username = "sql-user",
                password = "very-secret",
                sslMode = SqlSslMode.VERIFY_IDENTITY,
            )

        config.jdbcUrl() shouldBe
            "jdbc:mysql://mysql.internal:3306/minecraft?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=VERIFY_IDENTITY&connectTimeout=10000&socketTimeout=30000"
        config.jdbcUrl() shouldNotContain "sql-user"
        config.jdbcUrl() shouldNotContain "very-secret"
        config.toString() shouldContain "password=<redacted>"
        config.toString() shouldNotContain "very-secret"
    }

    "invalid database identifiers fail before pool creation" {
        shouldThrow<IllegalArgumentException> {
            SqlConnectionConfig(
                host = "localhost",
                database = "duels; DROP DATABASE minecraft",
                username = "duels",
                password = "",
            )
        }
    }

    "RSA key retrieval is limited to explicitly disabled TLS" {
        val disabled = SqlConnectionConfig(
            host = "localhost",
            database = "duels",
            username = "duels",
            password = "test-password",
            sslMode = SqlSslMode.DISABLED,
        )
        val encrypted = disabled.copy(sslMode = SqlSslMode.REQUIRED)

        disabled.jdbcUrl() shouldContain "&sslMode=DISABLED&allowPublicKeyRetrieval=true&"
        encrypted.jdbcUrl() shouldNotContain "allowPublicKeyRetrieval"
    }

    "host cannot inject JDBC URL parameters" {
        shouldThrow<IllegalArgumentException> {
            SqlConnectionConfig(
                host = "localhost/db?useSSL=false",
                database = "duels",
                username = "duels",
                password = "",
            )
        }
    }

    "pool bounds are validated" {
        shouldThrow<IllegalArgumentException> {
            SqlConnectionConfig(
                host = "localhost",
                database = "duels",
                username = "duels",
                password = "",
                minimumIdle = 4,
                maximumPoolSize = 2,
            )
        }
    }
})
