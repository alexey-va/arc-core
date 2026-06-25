package ru.arc.core

/** Minecraft tick timing shared by Paper (native) and Velocity (emulated). */
object TickConstants {
    const val TICK_MS: Long = 50L

    fun ticksToMillis(ticks: Long): Long = ticks * TICK_MS

    fun millisToTicks(millis: Long): Long = millis / TICK_MS
}
