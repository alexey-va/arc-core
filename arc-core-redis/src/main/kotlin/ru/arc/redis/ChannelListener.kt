package ru.arc.redis

fun interface ChannelListener {
    fun consume(channel: String, message: String, originServer: String)
}
