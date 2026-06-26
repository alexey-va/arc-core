package ru.arc.redis.gson

import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
annotation class JsonSubtype(val clazz: KClass<*>, val name: String)
