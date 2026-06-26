package ru.arc.repository

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for a repository.
 */
data class RepoConfig<T : Entity>(
    /**
     * Unique identifier for this repository.
     */
    val id: String,

    /**
     * Storage key (e.g., Redis hash key).
     */
    val storageKey: String,

    /**
     * Channel for pub/sub updates.
     */
    val updateChannel: String,

    /**
     * Interval between background saves.
     */
    val saveInterval: Duration = 5.seconds,

    /**
     * Factory for creating new entities.
     */
    val entityFactory: ((String) -> T)? = null,

    /**
     * Whether to load all entities on startup.
     */
    val loadAllOnStart: Boolean = false,

    /**
     * Maximum retry attempts for failed operations.
     */
    val maxRetries: Int = 3,

    /**
     * Base delay for retry backoff.
     */
    val retryBaseDelay: Duration = 100.milliseconds,

    /**
     * Whether to enable file backups.
     */
    val enableBackups: Boolean = false,

    /**
     * Backup folder path.
     */
    val backupFolder: String? = null,

    /**
     * Interval between backups.
     */
    val backupInterval: Duration = 10.seconds,

    /**
     * Whether to enable automatic cache cleanup.
     */
    val enableCleanup: Boolean = true,

    /**
     * Interval between cleanup runs.
     */
    val cleanupInterval: Duration = 1.minutes,

    /**
     * Time after which non-context entities are evicted from cache.
     * Entities in context are never evicted.
     */
    val entityTimeout: Duration = 10.minutes
) {
    companion object {
        fun <T : Entity> builder(id: String): Builder<T> = Builder(id)
    }

    class Builder<T : Entity>(private val id: String) {
        private var storageKey: String = id
        private var updateChannel: String = "${id}_updates"
        private var saveInterval: Duration = 5.seconds
        private var entityFactory: ((String) -> T)? = null
        private var loadAllOnStart: Boolean = false
        private var maxRetries: Int = 3
        private var retryBaseDelay: Duration = 100.milliseconds
        private var enableBackups: Boolean = false
        private var backupFolder: String? = null
        private var backupInterval: Duration = 10.seconds
        private var enableCleanup: Boolean = true
        private var cleanupInterval: Duration = 1.minutes
        private var entityTimeout: Duration = 10.minutes

        fun storageKey(key: String) = apply { this.storageKey = key }
        fun updateChannel(channel: String) = apply { this.updateChannel = channel }
        fun saveInterval(interval: Duration) = apply { this.saveInterval = interval }
        fun entityFactory(factory: (String) -> T) = apply { this.entityFactory = factory }
        fun loadAllOnStart(load: Boolean) = apply { this.loadAllOnStart = load }
        fun maxRetries(retries: Int) = apply { this.maxRetries = retries }
        fun retryBaseDelay(delay: Duration) = apply { this.retryBaseDelay = delay }
        fun enableBackups(enable: Boolean) = apply { this.enableBackups = enable }
        fun backupFolder(folder: String) = apply { this.backupFolder = folder }
        fun backupInterval(interval: Duration) = apply { this.backupInterval = interval }
        fun enableCleanup(enable: Boolean) = apply { this.enableCleanup = enable }
        fun cleanupInterval(interval: Duration) = apply { this.cleanupInterval = interval }
        fun entityTimeout(timeout: Duration) = apply { this.entityTimeout = timeout }

        fun build(): RepoConfig<T> = RepoConfig(
            id = id,
            storageKey = storageKey,
            updateChannel = updateChannel,
            saveInterval = saveInterval,
            entityFactory = entityFactory,
            loadAllOnStart = loadAllOnStart,
            maxRetries = maxRetries,
            retryBaseDelay = retryBaseDelay,
            enableBackups = enableBackups,
            backupFolder = backupFolder,
            backupInterval = backupInterval,
            enableCleanup = enableCleanup,
            cleanupInterval = cleanupInterval,
            entityTimeout = entityTimeout
        )
    }
}

