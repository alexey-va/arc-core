package ru.arc.product

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Stable product paths shared by Paper telemetry and Velocity ingress. */
enum class ProductPath(val label: String) {
    NONE("none"),
    EXPLORER("explorer"),
    ENGINEER("engineer"),
    SETTLER("settler"),
}

enum class ProductActivity(val label: String) {
    DISCOVERY("discovery"),
    EXPLORATION("exploration"),
    GATHERING("gathering"),
    BUILDING("building"),
    CRAFTING("crafting"),
    COMBAT("combat"),
    ECONOMY("economy"),
    SOCIAL("social"),
    PROGRESSION("progression"),
}

enum class ProductFeature(
    val label: String,
    val path: ProductPath = ProductPath.NONE,
    val activity: ProductActivity = ProductActivity.DISCOVERY,
) {
    MAIN_MENU("main_menu"),
    HELP("help"),
    QUESTS("quests", ProductPath.EXPLORER, ProductActivity.PROGRESSION),
    DUNGEONS("dungeons", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    PARKOUR("parkour", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    DUELS("duels", ProductPath.EXPLORER, ProductActivity.COMBAT),
    MOUNTS("mounts", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    TREASURE("treasure", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    JOBS("jobs", ProductPath.ENGINEER, ProductActivity.ECONOMY),
    SLIMEFUN("slimefun", ProductPath.ENGINEER, ProductActivity.CRAFTING),
    SHOP("shop", ProductPath.ENGINEER, ProductActivity.ECONOMY),
    MARKET("market", ProductPath.ENGINEER, ProductActivity.ECONOMY),
    CONTRACTS("contracts", ProductPath.ENGINEER, ProductActivity.PROGRESSION),
    RTP("rtp", ProductPath.SETTLER, ProductActivity.EXPLORATION),
    HOMES("homes", ProductPath.SETTLER, ProductActivity.BUILDING),
    LANDS("lands", ProductPath.SETTLER, ProductActivity.BUILDING),
    TEAMS("teams", ProductPath.SETTLER, ProductActivity.SOCIAL),
    PLAYER_WARPS("player_warps", ProductPath.SETTLER, ProductActivity.SOCIAL),
    TRAILS("trails", ProductPath.SETTLER, ProductActivity.BUILDING);

    val countsAsSystem: Boolean
        get() = this != MAIN_MENU && this != HELP
}

enum class ProductOutcome(
    val label: String,
    val path: ProductPath,
    val activity: ProductActivity,
) {
    DUNGEON_VISIT("dungeon_visit", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    DUNGEON_COMPLETE("dungeon_complete", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    MOUNT_RIDE("mount_ride", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    TREASURE_CLAIM("treasure_claim", ProductPath.EXPLORER, ProductActivity.EXPLORATION),
    RTP_COMPLETE("rtp_complete", ProductPath.SETTLER, ProductActivity.EXPLORATION),
    CONTRACT_COMPLETE("contract_complete", ProductPath.ENGINEER, ProductActivity.PROGRESSION),
    GATHERING_THRESHOLD("gathering_threshold", ProductPath.ENGINEER, ProductActivity.GATHERING),
    BUILDING_THRESHOLD("building_threshold", ProductPath.SETTLER, ProductActivity.BUILDING),
    CRAFTING_THRESHOLD("crafting_threshold", ProductPath.ENGINEER, ProductActivity.CRAFTING),
    COMBAT_THRESHOLD("combat_threshold", ProductPath.EXPLORER, ProductActivity.COMBAT),
    SOCIAL_THRESHOLD("social_threshold", ProductPath.SETTLER, ProductActivity.SOCIAL),
    ADVANCEMENT("advancement", ProductPath.EXPLORER, ProductActivity.PROGRESSION),
}

enum class ProductAction(
    val label: String,
    val activity: ProductActivity,
) {
    MOVE("move", ProductActivity.EXPLORATION),
    WORLD_CHANGE("world_change", ProductActivity.EXPLORATION),
    BLOCK_BREAK("block_break", ProductActivity.GATHERING),
    BLOCK_PLACE("block_place", ProductActivity.BUILDING),
    CRAFT("craft", ProductActivity.CRAFTING),
    MOB_KILL("mob_kill", ProductActivity.COMBAT),
    PLAYER_DEATH("player_death", ProductActivity.COMBAT),
    CHAT("chat", ProductActivity.SOCIAL),
    ADVANCEMENT("advancement", ProductActivity.PROGRESSION),
}

enum class ProductEventKind(val label: String) {
    SESSION_START("session_start"),
    FIRST_JOIN("first_join"),
    MENU_OPEN("menu_open"),
    HELP_OPEN("help_open"),
    PATH_INTEREST("path_interest"),
    PATH_CHOICE("path_choice"),
    ACTIVITY("activity"),
    FEATURE_INTEREST("feature_interest"),
    MEANINGFUL_OUTCOME("meaningful_outcome"),
    DETAIL("detail"),
    SESSION_END("session_end"),
    SESSION_CENSORED("session_censored"),
}

enum class ProductDetailType(val label: String) {
    COMMAND("command"),
    WORLD("world"),
    TELEPORT_WORLD("teleport_world"),
    NPC("npc"),
    TELEPORT_CAUSE("teleport_cause"),
    SERVER("server"),
    SERVER_TARGET("server_target"),
    CONNECTION("connection"),
}

enum class ProductConnection(val label: String) {
    SERVER_CONNECT("server_connect"),
    SERVER_SWITCH("server_switch"),
    CONNECT_DENIED("connect_denied"),
    BACKEND_KICK_CONNECT("backend_kick_connect"),
    BACKEND_KICK_PLAY("backend_kick_play"),
    DISCONNECT_ACTIVE("disconnect_active"),
    LOGIN_CONFLICT("login_conflict"),
    LOGIN_CANCELLED_USER("login_cancelled_user"),
    LOGIN_CANCELLED_PROXY("login_cancelled_proxy"),
    LOGIN_CANCELLED_EARLY("login_cancelled_early"),
    PRE_SERVER_DISCONNECT("pre_server_disconnect"),
}

enum class ProductBackend(val label: String) {
    NONE("none"),
    SPAWN("spawn"),
    SURVIVAL("survival"),
    PARKOUR("parkour"),
    OTHER("other");

    companion object {
        fun classify(raw: String?): ProductBackend =
            when (raw?.trim()?.lowercase(Locale.ROOT)) {
                null, "" -> NONE
                "spawn", "classic" -> SPAWN
                "survival", "classic_survival" -> SURVIVAL
                "parkour" -> PARKOUR
                else -> OTHER
            }
    }
}

enum class ProductExitStage(val label: String) {
    BEFORE_MENU("before_menu"),
    BEFORE_PATH("before_path"),
    BEFORE_OUTCOME("before_outcome"),
    ENGAGED("engaged"),
}

enum class ProductTeleportType(val label: String) {
    COMMAND("command"),
    PLUGIN("plugin"),
    PORTAL("portal"),
    ITEM("item"),
    SPECTATE("spectate"),
    DISMOUNT("dismount"),
    OTHER("other");

    companion object {
        fun classify(cause: String): ProductTeleportType =
            when (cause.trim().uppercase(Locale.ROOT)) {
                "COMMAND" -> COMMAND
                "PLUGIN" -> PLUGIN
                "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY" -> PORTAL
                "ENDER_PEARL", "CONSUMABLE_EFFECT", "CHORUS_FRUIT" -> ITEM
                "SPECTATE" -> SPECTATE
                "DISMOUNT", "EXIT_BED" -> DISMOUNT
                else -> OTHER
            }
    }
}

enum class ProductEntryPoint(val label: String) {
    COMMAND("command"),
    GAMEPLAY("gameplay"),
    WORLD("world"),
    API("api"),
}

enum class ProductCohort(val label: String) {
    NEW("new"),
    RETURNING("returning"),
    OBSERVED("observed"),
}

enum class ProductWorldType(val label: String) {
    HUB("hub"),
    SURVIVAL("survival"),
    RESOURCE("resource"),
    NETHER("nether"),
    END("end"),
    DUNGEON("dungeon"),
    PARKOUR("parkour"),
    OTHER("other");

    companion object {
        fun classify(
            worldName: String,
            dungeon: Boolean = false,
        ): ProductWorldType {
            if (dungeon) return DUNGEON
            val world = worldName.trim().lowercase(Locale.ROOT)
            return when {
                "parkour" in world -> PARKOUR
                world == "mining" || world.startsWith("resource") -> RESOURCE
                world.endsWith("_nether") || world == "world_nether" -> NETHER
                world.endsWith("_the_end") || world == "world_the_end" -> END
                world in setOf("world", "spawn", "classic") -> HUB
                world in setOf("survival", "vanilla") -> SURVIVAL
                else -> OTHER
            }
        }
    }
}

data class ProductCommandInterest(
    val feature: ProductFeature,
    val event: ProductEventKind = ProductEventKind.FEATURE_INTEREST,
)

/** Only the command root is retained; arguments and player text are discarded. */
object ProductCommandClassifier {
    private val commands: Map<String, ProductCommandInterest> =
        buildMap {
            aliases(ProductFeature.MAIN_MENU, ProductEventKind.MENU_OPEN, "mm", "menu", "testmenu")
            aliases(ProductFeature.HELP, ProductEventKind.HELP_OPEN, "help", "begin")
            aliases(ProductFeature.QUESTS, names = arrayOf("quest", "quests"))
            aliases(ProductFeature.DUNGEONS, names = arrayOf("dungeon", "dungeons", "em", "elitemobs", "ag"))
            aliases(ProductFeature.PARKOUR, names = arrayOf("parkour", "pa"))
            aliases(ProductFeature.DUELS, names = arrayOf("duel", "duels", "arcduel", "arcduels"))
            aliases(ProductFeature.MOUNTS, names = arrayOf("mount", "mounts"))
            aliases(ProductFeature.TREASURE, names = arrayOf("treasure", "treasures"))
            aliases(ProductFeature.JOBS, names = arrayOf("job", "jobs"))
            aliases(ProductFeature.SLIMEFUN, names = arrayOf("sf", "slimefun"))
            aliases(ProductFeature.SHOP, names = arrayOf("shop", "eshop", "serverstore"))
            aliases(ProductFeature.MARKET, names = arrayOf("ah", "auction", "auctionhouse", "market"))
            aliases(ProductFeature.CONTRACTS, names = arrayOf("contract", "contracts"))
            aliases(ProductFeature.RTP, names = arrayOf("rtp"))
            aliases(ProductFeature.HOMES, names = arrayOf("home", "homes", "sethome"))
            aliases(ProductFeature.LANDS, names = arrayOf("land", "lands"))
            aliases(ProductFeature.TEAMS, names = arrayOf("team", "teams", "clan", "clans", "party"))
            aliases(ProductFeature.PLAYER_WARPS, names = arrayOf("pw", "pwarp", "pwarps", "playerwarp", "playerwarps"))
            aliases(ProductFeature.TRAILS, names = arrayOf("trail", "trails"))
        }

    fun classify(message: String): ProductCommandInterest? = root(message)?.let(commands::get)

    fun root(message: String): String? =
        message
            .trim()
            .removePrefix("/")
            .trimStart()
            .takeWhile { !it.isWhitespace() }
            .lowercase(Locale.ROOT)
            .takeIf { it.length in 1..48 && COMMAND_TOKEN.matches(it) }

    private fun MutableMap<String, ProductCommandInterest>.aliases(
        feature: ProductFeature,
        event: ProductEventKind = ProductEventKind.FEATURE_INTEREST,
        vararg names: String,
    ) {
        names.forEach { put(it, ProductCommandInterest(feature, event)) }
    }

    private val COMMAND_TOKEN = Regex("[a-z0-9:_-]+")
}

data class ProductDetail(
    val type: ProductDetailType,
    val key: String,
    val display: String? = null,
)

data class ProductExitContext(
    val server: String? = null,
    val world: String? = null,
    val command: String? = null,
    val npcId: String? = null,
    val npcName: String? = null,
    val feature: ProductFeature? = null,
    val activity: ProductActivity? = null,
    val stage: ProductExitStage,
    val teleportCause: String? = null,
    val connection: ProductConnection? = null,
    val trail: List<String> = emptyList(),
)

data class ProductSignal(
    val eventId: String,
    val source: String,
    val player: String,
    val occurredAt: Long,
    val kind: ProductEventKind,
    val path: ProductPath = ProductPath.NONE,
    val feature: ProductFeature? = null,
    val activity: ProductActivity? = null,
    val outcome: ProductOutcome? = null,
    val detail: ProductDetail? = null,
    val exit: ProductExitContext? = null,
    val sessionSeconds: Long = 0,
    val activeSeconds: Long = 0,
    val systems: Set<String> = emptySet(),
)

private data class ProductWireEvent(
    val version: Int = 0,
    val eventId: String? = null,
    val source: String? = null,
    val player: String? = null,
    val occurredAt: Long = 0,
    val kind: String? = null,
    val path: String? = null,
    val feature: String? = null,
    val activity: String? = null,
    val outcome: String? = null,
    val detailType: String? = null,
    val detailKey: String? = null,
    val detailDisplay: String? = null,
    val exitServer: String? = null,
    val exitWorld: String? = null,
    val exitCommand: String? = null,
    val exitNpcId: String? = null,
    val exitNpcName: String? = null,
    val exitFeature: String? = null,
    val exitActivity: String? = null,
    val exitStage: String? = null,
    val exitTeleportCause: String? = null,
    val exitConnection: String? = null,
    val exitTrail: List<String>? = null,
    val sessionSeconds: Long = 0,
    val activeSeconds: Long = 0,
    val systems: List<String>? = null,
)

/** Strict v1 Redis contract shared by ARC and ProxyARC. */
object ProductWireCodec {
    const val VERSION = 1
    const val CHANNEL = "arc-product-interest-v1"
    private const val MAX_MESSAGE_LENGTH = 4_096
    private const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
    private const val MAX_SESSION_SECONDS = 24 * 60 * 60L
    private val EVENT_ID = Regex("[a-f0-9-]{16,64}")
    private val SOURCE = Regex("[a-z0-9_.-]{1,32}")
    private val PSEUDONYM = Regex("[a-f0-9]{64}")
    private val SYSTEMS = ProductFeature.entries.filter(ProductFeature::countsAsSystem).mapTo(linkedSetOf(), ProductFeature::label)

    fun encode(signal: ProductSignal, gson: Gson): String =
        gson.toJson(
            ProductWireEvent(
                version = VERSION,
                eventId = signal.eventId,
                source = signal.source,
                player = signal.player,
                occurredAt = signal.occurredAt,
                kind = signal.kind.label,
                path = signal.path.label.takeUnless { signal.path == ProductPath.NONE },
                feature = signal.feature?.label,
                activity = signal.activity?.label,
                outcome = signal.outcome?.label,
                detailType = signal.detail?.type?.label,
                detailKey = signal.detail?.key,
                detailDisplay = signal.detail?.display,
                exitServer = signal.exit?.server,
                exitWorld = signal.exit?.world,
                exitCommand = signal.exit?.command,
                exitNpcId = signal.exit?.npcId,
                exitNpcName = signal.exit?.npcName,
                exitFeature = signal.exit?.feature?.label,
                exitActivity = signal.exit?.activity?.label,
                exitStage = signal.exit?.stage?.label,
                exitTeleportCause = signal.exit?.teleportCause,
                exitConnection = signal.exit?.connection?.label,
                exitTrail = signal.exit?.trail?.takeLast(MAX_TRAIL_STEPS),
                sessionSeconds = signal.sessionSeconds,
                activeSeconds = signal.activeSeconds,
                systems = signal.systems.sorted().take(32),
            ),
        )

    fun decode(
        payload: String,
        origin: String,
        now: Long,
        retentionDays: Int,
        gson: Gson,
    ): ProductSignal? {
        if (payload.length !in 2..MAX_MESSAGE_LENGTH || !SOURCE.matches(origin)) return null
        val wire = runCatching { gson.fromJson(payload, ProductWireEvent::class.java) }.getOrNull() ?: return null
        if (wire.version != VERSION) return null
        val eventId = wire.eventId?.takeIf(EVENT_ID::matches) ?: return null
        val source = wire.source?.takeIf { it == origin && SOURCE.matches(it) } ?: return null
        val player = wire.player?.takeIf(PSEUDONYM::matches) ?: return null
        val oldest = now - retentionDays.coerceIn(1, 35) * 86_400_000L
        if (wire.occurredAt !in oldest..(now + MAX_CLOCK_SKEW_MILLIS)) return null
        val kind = ProductEventKind.entries.firstOrNull { it.label == wire.kind } ?: return null
        val path =
            ProductPath.entries.firstOrNull { it.label == wire.path }
                ?: wire.path?.let { return null }
                ?: ProductPath.NONE
        val feature =
            ProductFeature.entries.firstOrNull { it.label == wire.feature }
                ?: wire.feature?.let { return null }
        val activity =
            ProductActivity.entries.firstOrNull { it.label == wire.activity }
                ?: wire.activity?.let { return null }
        val outcome =
            ProductOutcome.entries.firstOrNull { it.label == wire.outcome }
                ?: wire.outcome?.let { return null }
        val detailType =
            ProductDetailType.entries.firstOrNull { it.label == wire.detailType }
                ?: wire.detailType?.let { return null }
        val detail =
            detailType?.let { type ->
                val key = wire.detailKey?.takeIf { isValidDetailKey(type, it) } ?: return null
                ProductDetail(type, key, sanitizeDisplay(wire.detailDisplay))
            }
        val exitStage =
            ProductExitStage.entries.firstOrNull { it.label == wire.exitStage }
                ?: wire.exitStage?.let { return null }
        val exitConnection =
            ProductConnection.entries.firstOrNull { it.label == wire.exitConnection }
                ?: wire.exitConnection?.let { return null }
        val exitTrail = wire.exitTrail.orEmpty()
        if (exitTrail.size > MAX_TRAIL_STEPS || exitTrail.any { !isValidTrailStep(it) }) return null
        val exit =
            exitStage?.let { stage ->
                ProductExitContext(
                    server = wire.exitServer?.takeIf(::isServerKey) ?: wire.exitServer?.let { return null },
                    world = wire.exitWorld?.takeIf(::isWorldKey) ?: wire.exitWorld?.let { return null },
                    command = wire.exitCommand?.takeIf(::isCommandKey) ?: wire.exitCommand?.let { return null },
                    npcId = wire.exitNpcId?.takeIf(::isNpcKey) ?: wire.exitNpcId?.let { return null },
                    npcName = sanitizeDisplay(wire.exitNpcName),
                    feature =
                        ProductFeature.entries.firstOrNull { it.label == wire.exitFeature }
                            ?: wire.exitFeature?.let { return null },
                    activity =
                        ProductActivity.entries.firstOrNull { it.label == wire.exitActivity }
                            ?: wire.exitActivity?.let { return null },
                    stage = stage,
                    teleportCause = wire.exitTeleportCause?.takeIf(::isCauseKey) ?: wire.exitTeleportCause?.let { return null },
                    connection = exitConnection,
                    trail = exitTrail,
                )
            }
        val rawSystems = wire.systems.orEmpty()
        if (rawSystems.size > SYSTEMS.size || rawSystems.any { it !in SYSTEMS }) return null
        val systems = rawSystems.toSet()
        if (wire.sessionSeconds !in 0..MAX_SESSION_SECONDS || wire.activeSeconds !in 0..wire.sessionSeconds) return null
        if (kind in setOf(ProductEventKind.PATH_INTEREST, ProductEventKind.PATH_CHOICE) && path == ProductPath.NONE) return null
        if (kind == ProductEventKind.ACTIVITY && activity == null) return null
        if (kind == ProductEventKind.FEATURE_INTEREST && feature == null) return null
        if (kind == ProductEventKind.MEANINGFUL_OUTCOME && outcome == null) return null
        if (kind == ProductEventKind.DETAIL && detail == null) return null
        if (kind == ProductEventKind.SESSION_END && exit == null) return null
        if (kind != ProductEventKind.DETAIL && detail != null) return null
        if (kind != ProductEventKind.SESSION_END && exit != null) return null
        return ProductSignal(
            eventId = eventId,
            source = source,
            player = player,
            occurredAt = wire.occurredAt,
            kind = kind,
            path = path,
            feature = feature,
            activity = activity,
            outcome = outcome,
            detail = detail,
            exit = exit,
            sessionSeconds = wire.sessionSeconds,
            activeSeconds = wire.activeSeconds,
            systems = systems,
        )
    }

    fun isValidDetailKey(type: ProductDetailType, value: String): Boolean =
        when (type) {
            ProductDetailType.COMMAND -> isCommandKey(value)
            ProductDetailType.WORLD,
            ProductDetailType.TELEPORT_WORLD,
            -> isWorldKey(value)
            ProductDetailType.NPC -> isNpcKey(value)
            ProductDetailType.TELEPORT_CAUSE -> isCauseKey(value)
            ProductDetailType.SERVER,
            ProductDetailType.SERVER_TARGET,
            -> isServerKey(value)
            ProductDetailType.CONNECTION -> ProductConnection.entries.any { it.label == value }
        }

    fun sanitizeDisplay(value: String?): String? =
        value
            ?.replace(LEGACY_COLOR, "")
            ?.replace(MINI_TAG, "")
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.take(64)
            ?.takeIf { it.isNotBlank() }

    fun normalizeWorld(value: String): String? = value.trim().lowercase(Locale.ROOT).takeIf(::isWorldKey)

    fun normalizeServer(value: String): String? = value.trim().lowercase(Locale.ROOT).takeIf(::isServerKey)

    fun normalizeCause(value: String): String? = value.trim().lowercase(Locale.ROOT).takeIf(::isCauseKey)

    fun trailStep(kind: String, value: String): String? = "$kind=$value".takeIf(::isValidTrailStep)

    private fun isCommandKey(value: String): Boolean = COMMAND_KEY.matches(value)
    private fun isWorldKey(value: String): Boolean = WORLD_KEY.matches(value)
    private fun isServerKey(value: String): Boolean = SERVER_KEY.matches(value)
    private fun isNpcKey(value: String): Boolean = NPC_KEY.matches(value)
    private fun isCauseKey(value: String): Boolean = CAUSE_KEY.matches(value)
    private fun isValidTrailStep(value: String): Boolean = TRAIL_STEP.matches(value)

    private val COMMAND_KEY = Regex("[a-z0-9:_-]{1,48}")
    private val WORLD_KEY = Regex("[a-z0-9_.-]{1,64}")
    private val SERVER_KEY = Regex("[a-z0-9_.-]{1,32}")
    private val NPC_KEY = Regex("[0-9]{1,10}")
    private val CAUSE_KEY = Regex("[a-z0-9_]{1,32}")
    private val TRAIL_STEP = Regex("[a-z_]{2,20}=[a-z0-9_.:-]{1,64}")
    private val LEGACY_COLOR = Regex("(?i)[§&][0-9A-FK-ORX]")
    private val MINI_TAG = Regex("<[^>]{1,32}>")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_TRAIL_STEPS = 12
}

object ProductPseudonym {
    fun of(playerId: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("ruscrafting-product-v1:$playerId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun eventId(): String = UUID.randomUUID().toString()
}
