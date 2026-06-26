package ru.arc.redis.gson

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonType(val property: String, val subtypes: Array<JsonSubtype>)
