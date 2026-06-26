package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.arc.redis.RedisOperations
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import ru.arc.repository.Storage
import java.lang.reflect.Type

/**
 * Redis implementation of Storage interface.
 */
class RedisStorage<T : Entity>(
    private val redis: RedisOperations,
    private val storageKey: String,
    private val entityType: Type,
    private val gson: Gson = Gson(),
) : Storage<T> {

    private val log = LoggerFactory.getLogger(RedisStorage::class.java)

    private fun isPresentJson(json: String?): Boolean = !json.isNullOrBlank()

    private suspend fun purgeTombstone(id: String) {
        redis.saveMapEntries(storageKey, id, null).await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun load(id: String): RepoResult<T?> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            log.debug("[RedisStorage:{}] load({})", storageKey, id)
            val values = redis.loadMapEntries(storageKey, id).await()
            val json = values.getOrNull(0)
            if (!isPresentJson(json)) {
                if (json != null) {
                    log.debug("[RedisStorage:{}] removing tombstone for {}", storageKey, id)
                    purgeTombstone(id)
                } else {
                    log.debug("[RedisStorage:{}] no data for {}", storageKey, id)
                }
                return@runCatching null
            }
            val payload = json!!
            log.debug(
                "[RedisStorage:{}] raw JSON for {}: {}",
                storageKey,
                id,
                if (payload.length > 200) payload.take(200) + "…" else payload,
            )
            try {
                gson.fromJson(payload, entityType) as T?
            } catch (e: Exception) {
                log.warn("[RedisStorage:{}] deserialization failed for {}: {}", storageKey, id, e.message)
                throw e
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loadMany(ids: Set<String>): RepoResult<Map<String, T>> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val values = redis.loadMapEntries(storageKey, *ids.toTypedArray()).await()
            val result = mutableMapOf<String, T>()

            ids.forEachIndexed { index, id ->
                val json = values.getOrNull(index)
                if (!isPresentJson(json)) {
                    if (json != null) purgeTombstone(id)
                    return@forEachIndexed
                }
                try {
                    val entity = gson.fromJson(json!!, entityType) as T
                    result[id] = entity
                } catch (e: Exception) {
                    log.warn("[RedisStorage:{}] Failed to deserialize entity {}: {}", storageKey, id, e.message)
                }
            }

            result
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loadAll(): RepoResult<Map<String, T>> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val allData = redis.loadMap(storageKey).await()
            val result = mutableMapOf<String, T>()

            for ((id, json) in allData) {
                if (!isPresentJson(json)) {
                    purgeTombstone(id)
                    continue
                }
                try {
                    val entity = gson.fromJson(json, entityType) as T
                    result[id] = entity
                } catch (e: Exception) {
                    log.warn("[RedisStorage:{}] Failed to deserialize entity {}: {}", storageKey, id, e.message)
                }
            }

            result
        }
    }

    override suspend fun save(entity: T): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val json = gson.toJson(entity)
            log.debug("[RedisStorage:{}] save({}) json size={}", storageKey, entity.id(), json.length)
            redis.saveMapEntries(storageKey, entity.id(), json).await()
            Unit
        }
    }

    override suspend fun saveMany(entities: Collection<T>): RepoResult<Unit> = withContext(Dispatchers.IO) {
        if (entities.isEmpty()) return@withContext RepoResult.success(Unit)

        RepoResult.runCatching {
            val keyValuePairs = entities.flatMap { entity ->
                listOf(entity.id(), gson.toJson(entity))
            }.toTypedArray()

            redis.saveMapEntries(storageKey, *keyValuePairs).await()
            Unit
        }
    }

    override suspend fun delete(id: String): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            redis.saveMapEntries(storageKey, id, null).await()
            Unit
        }
    }

    override suspend fun deleteMany(ids: Set<String>): RepoResult<Unit> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext RepoResult.success(Unit)

        RepoResult.runCatching {
            val keyValuePairs = ids.flatMap { id ->
                listOf<String?>(id, null)
            }.toTypedArray()

            redis.saveMapEntries(storageKey, *keyValuePairs).await()
            Unit
        }
    }

    override suspend fun exists(id: String): RepoResult<Boolean> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val values = redis.loadMapEntries(storageKey, id).await()
            val value = values.getOrNull(0)
            value != null && value.isNotEmpty()
        }
    }
}
