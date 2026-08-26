package ru.arc.testing.containers

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.net.Socket

class ContainerTestServicesIntegrationTest : FreeSpec({
    "Redis fixture accepts a real protocol round trip" {
        RedisTestService.start().use { redis ->
            Socket(redis.endpoint.host, redis.endpoint.port).use { socket ->
                socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine() shouldBe "+PONG"
            }
        }
    }

    "MySQL fixture accepts a real JDBC round trip" {
        MySqlTestService.start().use { mysql ->
            mysql.endpoint.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { result ->
                        result.next() shouldBe true
                        result.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }
})
