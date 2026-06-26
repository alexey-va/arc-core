package ru.arc.ops.core

data class OpsRequest(
    val method: String,
    val segments: List<String>,
    val query: Map<String, String>,
    val body: String,
    val config: OpsHttpConfig,
    val platformInfo: OpsPlatformInfo,
)

/**
 * Method + path segment router. Registers capabilities as routes are added.
 */
class OpsRouter(
    private val platformInfo: OpsPlatformInfoProvider,
    val capabilities: OpsCapabilityRegistry = OpsCapabilityRegistry(),
) {
    private data class Route(
        val method: String,
        val segments: List<String>,
        val capability: String,
        val handler: (OpsRequest) -> Pair<Int, String>,
    )

    private val routes = mutableListOf<Route>()

    fun register(
        method: String,
        path: String,
        capability: String,
        handler: (OpsRequest) -> Pair<Int, String>,
    ) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        routes.add(Route(method.uppercase(), segments, capability, handler))
        capabilities.register(capability)
    }

    fun handle(request: OpsRequest): Pair<Int, String> {
        val route =
            routes.firstOrNull {
                it.method == request.method.uppercase() && it.segments == request.segments
            }
        if (route == null) {
            return OpsJson.error(404, "not_found")
        }
        return route.handler(request)
    }

    fun routeIndex(): List<Map<String, String>> =
        routes.map { route ->
            mapOf(
                "method" to route.method,
                "path" to "/ops/${route.segments.joinToString("/")}",
                "capability" to route.capability,
            )
        }

    fun buildRequest(
        method: String,
        segments: List<String>,
        query: Map<String, String>,
        body: String,
        config: OpsHttpConfig,
    ): OpsRequest =
        OpsRequest(
            method = method.uppercase(),
            segments = segments,
            query = query,
            body = body,
            config = config,
            platformInfo = platformInfo.info(),
        )

    companion object {
        fun createStandard(
            platformInfo: OpsPlatformInfoProvider,
            consolePort: OpsConsolePort?,
        ): OpsRouter {
            val router = OpsRouter(platformInfo)
            router.registerCoreRoutes(consolePort)
            return router
        }

        private fun OpsRouter.registerCoreRoutes(consolePort: OpsConsolePort?) {
            register("GET", "health", "core.health") { _ ->
                200 to OpsJson.ok(mapOf("status" to "up"))
            }

            register("GET", "info", "core.info") { request ->
                200 to
                    OpsJson.ok(
                        mapOf(
                            "platform" to request.platformInfo.platform,
                            "serverName" to request.platformInfo.serverName,
                            "version" to request.platformInfo.version,
                        ),
                    )
            }

            register("GET", "capabilities", "core.capabilities") { _ ->
                200 to OpsJson.ok(mapOf("capabilities" to capabilities.all()))
            }

            register("GET", "errors", "core.errors") { request ->
                val limit = request.query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
                val entries = OpsLogBuffer.recent(limit).map { it.toMap() }
                200 to OpsJson.ok(mapOf("entries" to entries))
            }

            register("GET", "", "core.index") { _ ->
                200 to
                    OpsJson.ok(
                        mapOf(
                            "routes" to routeIndex(),
                            "capabilities" to capabilities.all(),
                        ),
                    )
            }

            if (consolePort == null) {
                register("POST", "console", "console.execute") { request ->
                    OpsJson.notSupported("console.execute", request.platformInfo.platform)
                }
            } else {
                register("POST", "console", "console.execute") { request ->
                    if (!request.config.consoleEnabled) {
                        return@register OpsJson.error(403, "console_disabled")
                    }
                    val command = OpsJson.parseCommand(request.body)
                        ?: return@register OpsJson.error(400, "missing_command")
                    val result = consolePort.execute(command)
                    if (result.ok) {
                        200 to OpsJson.ok(mapOf("command" to command, "output" to result.output))
                    } else {
                        OpsJson.error(500, result.message.ifBlank { "console_failed" })
                    }
                }
            }
        }
    }
}
