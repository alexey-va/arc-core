package ru.arc.repository

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for all entities stored in repositories.
 */
interface Entity {
    /**
     * Unique identifier for this entity.
     */
    fun id(): String
}

/**
 * Result wrapper for repository operations.
 */
sealed class RepoResult<out T> {
    data class Success<T>(val data: T) : RepoResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : RepoResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun getOrThrow(): T =
        when (this) {
            is Success -> data
            is Error -> throw cause ?: IllegalStateException(message)
        }

    inline fun <R> map(transform: (T) -> R): RepoResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): RepoResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (String, Throwable?) -> Unit): RepoResult<T> {
        if (this is Error) action(message, cause)
        return this
    }

    companion object {
        fun <T> success(data: T): RepoResult<T> = Success(data)
        fun error(message: String, cause: Throwable? = null): RepoResult<Nothing> = Error(message, cause)

        inline fun <T> runCatching(block: () -> T): RepoResult<T> = try {
            Success(block())
        } catch (e: Exception) {
            Error(e.message ?: "Unknown error", e)
        }
    }
}

/**
 * Core repository interface with CRUD operations.
 */
interface Repository<T : Entity> {
    /**
     * Get entity by ID.
     */
    suspend fun get(id: String): RepoResult<T?>

    /**
     * Get entity or create if not exists.
     */
    suspend fun getOrCreate(id: String, factory: () -> T): RepoResult<T>

    /**
     * Save entity.
     */
    suspend fun save(entity: T): RepoResult<Unit>

    /**
     * Delete entity by ID.
     */
    suspend fun delete(id: String): RepoResult<Unit>

    /**
     * Get all entities.
     */
    suspend fun all(): RepoResult<List<T>>

    /**
     * Check if entity exists.
     */
    suspend fun exists(id: String): RepoResult<Boolean>
}

/**
 * Observable repository with real-time updates.
 */
interface ObservableRepository<T : Entity> : Repository<T> {
    /**
     * Observe changes to a specific entity.
     */
    fun observe(id: String): Flow<T?>

    /**
     * Observe all changes.
     */
    fun observeAll(): Flow<List<T>>
}

/**
 * Context-aware repository for player-scoped data.
 */
interface ContextRepository<T : Entity> : Repository<T> {
    /**
     * Add context (e.g., player joined).
     */
    fun addContext(id: String)

    /**
     * Remove context (e.g., player left).
     */
    fun removeContext(id: String)

    /**
     * Get all active contexts.
     */
    fun getContexts(): Set<String>
}
