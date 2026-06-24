package ru.arc.core

import kotlin.reflect.KClass

/**
 * Platform-neutral event bus. Velocity/Bukkit adapters register platform events
 * and forward them here, or handlers subscribe directly in core services.
 */
interface EventBus {
    fun <T : Any> register(
        eventClass: KClass<T>,
        handler: (T) -> Unit,
    ): EventRegistration

    fun unregister(registration: EventRegistration)

    fun unregisterAll()
}

interface EventRegistration {
    val eventClass: KClass<out Any>
    val isRegistered: Boolean

    fun unregister()
}

class SimpleEventBus : EventBus {
    private data class Handler<T : Any>(
        private val type: KClass<T>,
        private val handler: (T) -> Unit,
        @Volatile var registered: Boolean = true,
    ) : EventRegistration {
        override val eventClass: KClass<out Any> get() = type

        override val isRegistered: Boolean get() = registered

        override fun unregister() {
            registered = false
        }

        fun invoke(event: T) = handler(event)
    }

    private val handlers = mutableListOf<Handler<*>>()

    override fun <T : Any> register(
        eventClass: KClass<T>,
        handler: (T) -> Unit,
    ): EventRegistration {
        val registration = Handler(eventClass, handler)
        synchronized(handlers) {
            handlers.add(registration)
        }
        return registration
    }

    override fun unregister(registration: EventRegistration) {
        registration.unregister()
        synchronized(handlers) {
            handlers.removeIf { it === registration || !it.isRegistered }
        }
    }

    override fun unregisterAll() {
        synchronized(handlers) {
            handlers.forEach { it.unregister() }
            handlers.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> fire(event: T): Int {
        val eventClass = event::class
        var count = 0
        synchronized(handlers) {
            handlers.filter { it.isRegistered && it.eventClass.isInstance(event) }
                .forEach {
                    @Suppress("UNCHECKED_CAST")
                    (it as Handler<T>).invoke(event)
                    count++
                }
        }
        return count
    }
}

/** Global event bus — swap in tests via [withBus]. */
object Events {
    @Volatile
    var bus: EventBus = SimpleEventBus()

    val simple: SimpleEventBus
        get() = bus as SimpleEventBus

    fun reset() {
        bus.unregisterAll()
        bus = SimpleEventBus()
    }

    inline fun <T> withBus(testBus: SimpleEventBus, block: () -> T): T {
        val previous = bus
        bus = testBus
        return try {
            block()
        } finally {
            testBus.unregisterAll()
            bus = previous
        }
    }
}

inline fun <reified T : Any> on(noinline handler: (T) -> Unit): EventRegistration =
    Events.bus.register(T::class, handler)
