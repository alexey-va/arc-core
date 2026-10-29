package ru.arc.paper.sharedloading

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcDisplayPassengerOwner
import ru.arc.paper.api.ArcDisplayPassengerService
import ru.arc.paper.display.PaperDisplayPassengerServiceProvider
import ru.arc.paper.api.ArcRedisDiagnostics
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.api.ArcSqlGroupSnapshot
import ru.arc.paper.api.ArcSqlLease
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.RedisOperations
import ru.arc.sql.SqlRuntime
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URL
import java.net.URLClassLoader
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource

class PaperSharedServicesClassLoaderTest : FreeSpec({
    "passenger host API exposes only JDK types and its own shared contracts" {
        val contracts = arrayOf(ArcDisplayPassengerService::class.java, ArcDisplayPassengerOwner::class.java)
        publicSignatureTypes(*contracts).all { it.classLoader == null || it in contracts } shouldBe true
        ArcDisplayPassengerOwner::class.java.getMethod(
            "duringOwnPassengerWrites", Set::class.java, Supplier::class.java,
        ).parameterTypes.toList() shouldBe listOf(Set::class.java, Supplier::class.java)
    }

    "separate display consumer classes share host passenger ownership through only the API" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("PassengerHost")
            PaperDisplayPassengerServiceProvider(host).use { service ->
                paper.server.servicesManager.load(ArcDisplayPassengerService::class.java) shouldBe service
                consumerLoader(isolateKotlin = true).use { loaderA ->
                    consumerLoader(isolateKotlin = true).use { loaderB ->
                        val name = "ru.arc.paper.display.PacketDisplayPassengerState"
                        val typeA = loaderA.loadClass(name)
                        val typeB = loaderB.loadClass(name)
                        (typeA !== typeB) shouldBe true
                        val kotlinFunctionA = loaderA.loadClass("kotlin.jvm.functions.Function0")
                        (kotlinFunctionA !== kotlin.jvm.functions.Function0::class.java) shouldBe true
                        (kotlinFunctionA !== loaderB.loadClass("kotlin.jvm.functions.Function0")) shouldBe true
                        (loaderA.loadClass(ArcDisplayPassengerOwner::class.java.name) === ArcDisplayPassengerOwner::class.java) shouldBe true
                        (loaderB.loadClass(ArcDisplayPassengerService::class.java.name) === ArcDisplayPassengerService::class.java) shouldBe true
                        val channel = Any()
                        val a = typeA.getConstructor(ArcDisplayPassengerOwner::class.java).newInstance(service.open(channel)) as ArcDisplayPassengerOwner
                        val b = typeB.getConstructor(ArcDisplayPassengerOwner::class.java).newInstance(service.open(channel)) as ArcDisplayPassengerOwner
                        a.seedNative(77, listOf(11)); b.seedNative(77, listOf(11))
                        a.setMounted(77, listOf(100)); b.setMounted(77, listOf(200))
                        a.duringOwnPassengerWrites(setOf(77), Supplier {
                            b.isOwnPassengerWrite(77)
                        }) shouldBe true
                        b.isOwnPassengerWrite(77) shouldBe false
                        b.nativePassengers(77, emptyList()) shouldBe listOf(11, 100)
                        a.setMounted(77, emptyList()); a.close()
                        b.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12, 200)
                        b.setMounted(77, emptyList()); b.close()
                    }
                }
            }
            paper.server.servicesManager.load(ArcDisplayPassengerService::class.java) shouldBe null
        }
    }

    "SQL consumer factories and runtime leases stay local to each plugin classloader" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcServiceHost")
            val consumerA = paper.createSimplePlugin("SqlConsumerA")
            val consumerB = paper.createSimplePlugin("SqlConsumerB")
            val service = RecordingSqlService()
            paper.server.servicesManager.register(ArcSqlService::class.java, service, host, ServicePriority.Normal)

            consumerLoader().use { loaderA ->
                consumerLoader().use { loaderB ->
                    val bindings = mutableListOf<AutoCloseable>()
                    try {
                        val localRuntimeA = loaderA.loadClass(SQL_RUNTIME_CLASS)
                        val localRuntimeB = loaderB.loadClass(SQL_RUNTIME_CLASS)
                        (localRuntimeA !== localRuntimeB) shouldBe true
                        (loaderA.loadClass(ArcSqlService::class.java.name) === ArcSqlService::class.java) shouldBe true
                        (loaderB.loadClass(ArcSqlLease::class.java.name) === ArcSqlLease::class.java) shouldBe true
                        publicSignatureTypes(ArcSqlService::class.java, ArcSqlLease::class.java)
                            .none { it.name.startsWith("ru.arc.sql.") } shouldBe true

                        val bindingA = installSqlConsumer(loaderA, consumerA)
                        bindings += bindingA
                        val bindingB = installSqlConsumer(loaderB, consumerB)
                        bindings += bindingB
                        val runtimeA = createSqlRuntime(loaderA, "consumer-a")
                        val runtimeB = createSqlRuntime(loaderB, "consumer-b")

                        service.leases.map { it.owner } shouldBe listOf("SqlConsumerA", "SqlConsumerB")

                        // Closing A releases A's borrowed lease, while B's factory remains installed.
                        bindingA.close()
                        service.leases.map { it.closeCount.get() } shouldBe listOf(1, 0)
                        val duplicateBinding = runCatching { installSqlConsumer(loaderB, consumerB) }
                        duplicateBinding.getOrNull()?.close()
                        duplicateBinding.exceptionOrNull()?.message shouldBe "A SQL runtime factory is already installed"

                        // A's own slot is clear and can be installed again without changing B's slot.
                        installSqlConsumer(loaderA, consumerA).use { }
                        bindingB.close()
                        service.leases.map { it.closeCount.get() } shouldBe listOf(1, 1)

                        // The bindings already closed these runtimes; direct close is still idempotent.
                        runtimeA.close()
                        runtimeB.close()
                        service.leases.map { it.closeCount.get() } shouldBe listOf(1, 1)
                    } finally {
                        try {
                            bindings.asReversed().forEach(AutoCloseable::close)
                        } finally {
                            paper.server.servicesManager.unregisterAll(host)
                        }
                    }
                }
            }
        }
    }

    "Redis API leases round-trip through separate consumer classloaders" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcRedisHost")
            val consumerA = paper.createSimplePlugin("RedisConsumerA")
            val consumerB = paper.createSimplePlugin("RedisConsumerB")
            val service = RecordingRedisService()
            paper.server.servicesManager.register(ArcRedisService::class.java, service, host, ServicePriority.Normal)

            consumerLoader().use { loaderA ->
                consumerLoader().use { loaderB ->
                    try {
                        val clientTypeA = loaderA.loadClass(REDIS_CLIENT_CLASS)
                        val clientTypeB = loaderB.loadClass(REDIS_CLIENT_CLASS)
                        (clientTypeA !== clientTypeB) shouldBe true
                        (loaderA.loadClass(RedisOperations::class.java.name) !== RedisOperations::class.java) shouldBe true
                        (loaderA.loadClass(ArcRedisService::class.java.name) === ArcRedisService::class.java) shouldBe true
                        publicSignatureTypes(ArcRedisService::class.java, ArcRedisLease::class.java)
                            .none { it.name.startsWith("ru.arc.redis.") } shouldBe true

                        val clientA = openPrimaryRedisClient(loaderA, consumerA)
                        val clientB = openPrimaryRedisClient(loaderB, consumerB)
                        invoke(
                            clientA,
                            "saveMap",
                            arrayOf(String::class.java, Map::class.java),
                            "shared",
                            mapOf("state" to "one"),
                        )
                        val firstRead = invoke(clientB, "loadMap", arrayOf(String::class.java), "shared") as CompletableFuture<*>
                        firstRead.get(1, TimeUnit.SECONDS) shouldBe mapOf("state" to "one")

                        invoke(clientA, "close", emptyArray())
                        service.leases.map { it.closeCount.get() } shouldBe listOf(1, 0)

                        invoke(
                            clientB,
                            "saveMap",
                            arrayOf(String::class.java, Map::class.java),
                            "shared",
                            mapOf("state" to "two"),
                        )
                        val secondRead = invoke(clientB, "loadMap", arrayOf(String::class.java), "shared") as CompletableFuture<*>
                        secondRead.get(1, TimeUnit.SECONDS) shouldBe mapOf("state" to "two")
                    } finally {
                        service.leases.forEach { it.api.close() }
                        paper.server.servicesManager.unregisterAll(host)
                    }
                }
            }
        }
    }
})

private const val SQL_RUNTIME_CLASS = "ru.arc.sql.SqlRuntime"
private const val SQL_SERVICES_CLASS = "ru.arc.paper.sql.PaperSqlServices"
private const val REDIS_CLIENT_CLASS = "ru.arc.paper.redis.PaperRedisClient"

private fun consumerLoader(isolateKotlin: Boolean = false): IsolatedConsumerClassLoader =
    IsolatedConsumerClassLoader(
        arrayOf(
            PaperSqlServices::class.java.codeSourceUrl(),
            SqlRuntime::class.java.codeSourceUrl(),
            PaperRedisClient::class.java.codeSourceUrl(),
            RedisOperations::class.java.codeSourceUrl(),
            kotlin.jvm.functions.Function0::class.java.codeSourceUrl(),
        ).distinct().toTypedArray(),
        ArcSqlService::class.java.classLoader,
        isolateKotlin,
    )

private fun Class<*>.codeSourceUrl(): URL = requireNotNull(protectionDomain.codeSource?.location)

private class IsolatedConsumerClassLoader(urls: Array<URL>, parent: ClassLoader, isolateKotlin: Boolean) : URLClassLoader(urls, parent) {
    private val childPackages = listOf("ru.arc.paper.sql.", "ru.arc.sql.", "ru.arc.paper.redis.", "ru.arc.redis.", "ru.arc.paper.display.") +
        if (isolateKotlin) listOf("kotlin.") else emptyList()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (childPackages.none(name::startsWith)) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}

private fun installSqlConsumer(loader: ClassLoader, plugin: Plugin): AutoCloseable {
    val services = loader.loadClass(SQL_SERVICES_CLASS)
    val instance = services.getField("INSTANCE").get(null)
    return invokeReflectively {
        services.getMethod("installConsumer", Plugin::class.java).invoke(instance, plugin) as AutoCloseable
    }
}

private fun createSqlRuntime(loader: ClassLoader, runtimeName: String): AutoCloseable {
    val runtimeType = loader.loadClass(SQL_RUNTIME_CLASS)
    val configType = loader.loadClass("ru.arc.sql.SqlConnectionConfig")
    val sslModeType = loader.loadClass("ru.arc.sql.SqlSslMode")
    val disabledMode = sslModeType.enumConstants.first { (it as Enum<*>).name == "DISABLED" }
    val configConstructor = configType.getConstructor(
        String::class.java,
        Int::class.javaPrimitiveType!!,
        String::class.java,
        String::class.java,
        String::class.java,
        sslModeType,
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!,
        Long::class.javaPrimitiveType!!,
        Long::class.javaPrimitiveType!!,
        Long::class.javaPrimitiveType!!,
        Long::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!,
    )
    val config = configConstructor.newInstance(
        "localhost",
        3306,
        "arc_test",
        "test_user",
        "test_secret",
        disabledMode,
        0,
        1,
        5_000L,
        5_000L,
        1_000L,
        60_000L,
        false,
    )
    val companion = runtimeType.getField("Companion").get(null)
    return invokeReflectively {
        companion.javaClass.getMethod("create", configType, String::class.java)
            .invoke(companion, config, runtimeName) as AutoCloseable
    }
}

private fun openPrimaryRedisClient(loader: ClassLoader, plugin: Plugin): Any {
    val client = loader.loadClass(REDIS_CLIENT_CLASS)
    return invokeReflectively {
        client.getMethod("openPrimary", Plugin::class.java).invoke(null, plugin)
    }
}

private fun invoke(
    target: Any,
    method: String,
    parameterTypes: Array<out Class<*>>,
    vararg arguments: Any?,
): Any? {
    return invokeReflectively { target.javaClass.getMethod(method, *parameterTypes).invoke(target, *arguments) }
}

private inline fun <T> invokeReflectively(block: () -> T): T = try {
    block()
} catch (failure: InvocationTargetException) {
    throw failure.targetException
}

private fun publicSignatureTypes(vararg contracts: Class<*>): List<Class<*>> =
    contracts.flatMap { contract ->
        contract.methods.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
    }

private class RecordingSqlService : ArcSqlService {
    val leases = CopyOnWriteArrayList<RecordingSqlLease>()

    override fun acquire(owner: String, settings: ArcSqlSettings): ArcSqlLease =
        RecordingSqlLease(owner).also(leases::add)

    override fun snapshot(): List<ArcSqlGroupSnapshot> = emptyList()
}

private class RecordingSqlLease(val owner: String) : ArcSqlLease {
    private val closed = AtomicBoolean()
    val closeCount = AtomicInteger()

    override val dataSource: DataSource = noJdbcDataSource()
    override val executor = Executor(Runnable::run)
    override val completionExecutor = Executor(Runnable::run)

    override fun evictConnection(connection: Connection) = error("No JDBC connections are opened by this test")

    override fun close() {
        if (closed.compareAndSet(false, true)) closeCount.incrementAndGet()
    }
}

private fun noJdbcDataSource(): DataSource =
    Proxy.newProxyInstance(
        DataSource::class.java.classLoader,
        arrayOf(DataSource::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "NoJdbcDataSource"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> error("Unexpected JDBC access in classloader bridge test: ${method.name}")
        }
    } as DataSource

private class RecordingRedisService : ArcRedisService {
    private val hashes = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    val leases = CopyOnWriteArrayList<RecordingRedisLease>()

    override fun acquire(owner: String, settings: ArcRedisSettings, origin: String): ArcRedisLease = lease(owner)

    override fun acquirePrimary(owner: String): ArcRedisLease = lease(owner)

    override fun diagnostics(): ArcRedisDiagnostics {
        val active = leases.count { !it.isClosed }
        return ArcRedisDiagnostics(
            activeLeases = active,
            connectionGroups = if (active == 0) 0 else 1,
            connectedGroups = if (active == 0) 0 else 1,
            activeChannels = 0,
        )
    }

    override fun close() {
        leases.forEach { it.api.close() }
    }

    private fun lease(owner: String) = RecordingRedisLease(owner, hashes).also(leases::add).api
}

private class RecordingRedisLease(
    val owner: String,
    private val hashes: ConcurrentHashMap<String, ConcurrentHashMap<String, String>>,
) : InvocationHandler {
    private val closed = AtomicBoolean()
    val closeCount = AtomicInteger()
    val isClosed: Boolean
        get() = closed.get()

    val api: ArcRedisLease = Proxy.newProxyInstance(
        ArcRedisLease::class.java.classLoader,
        arrayOf(ArcRedisLease::class.java),
        this,
    ) as ArcRedisLease

    override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? {
        val args = arguments.orEmpty()
        return when (method.name) {
            "saveMap" -> {
                requireOpen()
                hashes.computeIfAbsent(args[0] as String) { ConcurrentHashMap() }.putAll(args[1] as Map<String, String>)
                Unit
            }
            "loadMap" -> {
                requireOpen()
                CompletableFuture.completedFuture(hashes[args[0] as String]?.toMap().orEmpty())
            }
            "close" -> if (closed.compareAndSet(false, true)) closeCount.incrementAndGet() else Unit
            "toString" -> "RecordingRedisLease($owner)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args.firstOrNull()
            else -> error("Unexpected Redis API call in classloader bridge test: ${method.name}")
        }
    }

    private fun requireOpen() = check(!isClosed) { "Redis API lease is closed" }
}
