package ru.arc.redis

data class RedisConnection(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)
