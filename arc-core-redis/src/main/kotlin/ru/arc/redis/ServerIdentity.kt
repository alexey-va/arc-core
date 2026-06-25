package ru.arc.redis

/** Local server name prepended to pub/sub payloads. */
fun interface ServerIdentity {
    fun name(): String
}
