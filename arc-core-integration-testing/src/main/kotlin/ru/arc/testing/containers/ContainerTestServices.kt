package ru.arc.testing.containers

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class RedisTestEndpoint(
    val host: String,
    val port: Int,
)

/** One disposable Redis process with no host binary or fixed-port dependency. */
class RedisTestService private constructor(
    private val container: GenericContainer<*>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val endpoint: RedisTestEndpoint = RedisTestEndpoint(container.host, container.getMappedPort(REDIS_PORT))

    override fun close() {
        if (closed.compareAndSet(false, true)) container.stop()
    }

    companion object {
        const val DEFAULT_IMAGE = "redis:7.4-alpine"
        private const val REDIS_PORT = 6379

        fun start(image: String = DEFAULT_IMAGE): RedisTestService {
            require(image.isNotBlank() && image.length <= 200) { "Redis test image must be bounded" }
            val container = GenericContainer(DockerImageName.parse(image))
                .withExposedPorts(REDIS_PORT)
                .withCommand("redis-server", "--save", "", "--appendonly", "no")
                .waitingFor(
                    WaitAllStrategy()
                        .withStrategy(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
                        .withStrategy(Wait.forListeningPort()),
                )
                .withStartupTimeout(Duration.ofSeconds(60))
            container.start()
            return RedisTestService(container)
        }
    }
}

data class MySqlInitScript(
    val classpathResource: String,
    val order: Int = 10,
) {
    init {
        require(
            classpathResource.matches(RESOURCE_PATH) &&
                !classpathResource.startsWith('/') &&
                classpathResource.split('/').none { it == "." || it == ".." },
        ) {
            "MySQL init script must be a bounded classpath .sql resource"
        }
        require(order in 1..99) { "MySQL init script order must be between 1 and 99" }
    }

    internal val containerPath: String
        get() = "/docker-entrypoint-initdb.d/${order.toString().padStart(2, '0')}-${classpathResource.substringAfterLast('/')}"

    private companion object {
        val RESOURCE_PATH = Regex("[A-Za-z0-9_./-]{1,180}\\.sql")
    }
}

data class MySqlTestSettings(
    val image: String = MySqlTestService.DEFAULT_IMAGE,
    val database: String = "arc_test",
    val username: String = "arc_test",
    val password: String = "arc-test-password",
    val rootPassword: String = "arc-root-test-password",
    val provisionUser: Boolean = true,
    val initScripts: List<MySqlInitScript> = emptyList(),
) {
    init {
        require(image.isNotBlank() && image.length <= 200) { "MySQL test image must be bounded" }
        require(database.matches(SQL_IDENTIFIER)) { "MySQL test database must be a safe identifier" }
        require(username.matches(SQL_IDENTIFIER)) { "MySQL test username must be a safe identifier" }
        require(password.length in 8..256) { "MySQL test password must be between 8 and 256 characters" }
        require(rootPassword.length in 8..256) { "MySQL test root password must be between 8 and 256 characters" }
        require(initScripts.size <= 32) { "Too many MySQL init scripts" }
        require(initScripts.map(MySqlInitScript::containerPath).distinct().size == initScripts.size) {
            "MySQL init script destinations must be unique"
        }
    }

    override fun toString(): String =
        "MySqlTestSettings(image=$image, database=$database, username=$username, " +
            "password=<redacted>, rootPassword=<redacted>, provisionUser=$provisionUser, " +
            "initScripts=$initScripts)"

    private companion object {
        val SQL_IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    }
}

data class MySqlTestEndpoint(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
) {
    val jdbcUrl: String get() = "jdbc:mysql://$host:$port/$database"

    fun connect(): Connection = DriverManager.getConnection(jdbcUrl, username, password)

    override fun toString(): String =
        "MySqlTestEndpoint(host=$host, port=$port, database=$database, username=$username, password=<redacted>)"
}

/** One disposable MySQL service with optional ordered classpath init scripts. */
class MySqlTestService private constructor(
    private val container: GenericContainer<*>,
    settings: MySqlTestSettings,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val endpoint: MySqlTestEndpoint = MySqlTestEndpoint(
        host = container.host,
        port = container.getMappedPort(MYSQL_PORT),
        database = settings.database,
        username = settings.username,
        password = settings.password,
    )

    override fun close() {
        if (closed.compareAndSet(false, true)) container.stop()
    }

    companion object {
        const val DEFAULT_IMAGE = "mysql:8.0.46"
        private const val MYSQL_PORT = 3306

        fun start(settings: MySqlTestSettings = MySqlTestSettings()): MySqlTestService {
            val container = GenericContainer(DockerImageName.parse(settings.image))
                .withEnv("MYSQL_DATABASE", settings.database)
                .withEnv("MYSQL_ROOT_PASSWORD", settings.rootPassword)
                .withExposedPorts(MYSQL_PORT)
                .waitingFor(
                    WaitAllStrategy()
                        .withStrategy(Wait.forLogMessage(".*ready for connections.*\\n", 2))
                        .withStrategy(Wait.forListeningPort()),
                )
                .withStartupTimeout(Duration.ofSeconds(120))
            if (settings.provisionUser) {
                container.withEnv("MYSQL_USER", settings.username)
                container.withEnv("MYSQL_PASSWORD", settings.password)
            }
            settings.initScripts.sortedBy(MySqlInitScript::order).forEach { script ->
                container.withCopyFileToContainer(
                    MountableFile.forClasspathResource(script.classpathResource),
                    script.containerPath,
                )
            }
            container.start()
            return MySqlTestService(container, settings)
        }
    }
}
