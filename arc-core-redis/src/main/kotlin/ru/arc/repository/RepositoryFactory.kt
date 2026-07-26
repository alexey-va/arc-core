package ru.arc.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import ru.arc.redis.RedisOperations
import ru.arc.repository.redis.RedisStorage
import ru.arc.repository.redis.RedisSyncService

/**
 * Creates and initializes a [CachedRepository] backed by Redis.
 */
inline fun <reified T : Entity> redisRepo(
    redis: RedisOperations,
    gson: Gson,
    id: String,
    storageKey: String,
    updateChannel: String,
    scope: CoroutineScope,
    configure: RepoConfig.Builder<T>.() -> Unit = {},
): CachedRepository<T> {
    val entityType = object : TypeToken<T>() {}.type

    val config = RepoConfig.builder<T>(id)
        .storageKey(storageKey)
        .updateChannel(updateChannel)
        .apply(configure)
        .build()

    val storage = RedisStorage<T>(
        redis = redis,
        storageKey = storageKey,
        entityType = entityType,
        gson = gson,
    )

    val syncService = RedisSyncService<T>(
        redis = redis,
        channel = updateChannel,
        entityType = entityType,
        gson = gson,
    )

    val repo = CachedRepository(
        config = config,
        storage = storage,
        syncService = syncService,
        scope = scope,
    )

    runBlocking { repo.init().getOrThrow() }
    return repo
}
