package ru.arc.repository.redis

/** Wire strategy used to synchronize a Redis-backed repository. */
enum class RedisSyncMode {
    /** Publish the complete serialized entity to every subscriber. */
    ENTITY,

    /** Publish only the entity id; subscribers reload the durable Redis value. */
    INVALIDATION,
}
