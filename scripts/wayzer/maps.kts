@file:Depends("wayzer/user/trustLevel", "3++协管高风险地图操作保护")

package wayzer

import arc.Events
import arc.func.Cons
import mindustry.game.EventType
import mindustry.game.EventType.WorldLoadBeginEvent
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.io.SaveIO
import mindustry.gen.Player
import wayzer.lib.PlayerData
import wayzer.user.TrustLevel
import java.time.Duration
import mindustry.maps.Map as MdtMap

name = "基础: 地图控制与管理"

private val patchLoadHooks = mutableListOf<Pair<Class<Any>, Cons<Any>>>()
private val trustLevel = contextScript<TrustLevel>()

private data class PluginAdminPendingAction(val key: String, val untilMillis: Long)
private val pluginAdminPendingActions = mutableMapOf<String, PluginAdminPendingAction>()
private val pluginAdminMapActionCooldownUntil = mutableMapOf<String, Long>()
private val pluginAdminMapActionConfirmMillis by config.key(15_000L, "3++强制换图/结束游戏二次确认有效期(ms)")
private val pluginAdminMapActionCooldownMillis by config.key(5 * 60_000L, "3++强制换图/结束游戏成功后冷却(ms)")

private fun requirePluginAdminMapActionConfirmation(player: Player?, actionKey: String, commandText: String): Boolean {
    if (player == null || !with(trustLevel) { isPluginAdmin(player) }) return true
    val uid = PlayerData[player].id
    val now = System.currentTimeMillis()
    val cooldownUntil = pluginAdminMapActionCooldownUntil[uid] ?: 0L
    if (cooldownUntil > now) {
        player.sendMessage("[yellow]协管高风险地图操作冷却中，还需 [white]${(cooldownUntil - now + 999L) / 1000L}[yellow] 秒。")
        return false
    }
    pluginAdminMapActionCooldownUntil.remove(uid)
    val pending = pluginAdminPendingActions[uid]
    if (pending?.key == actionKey && pending.untilMillis >= now) {
        pluginAdminPendingActions.remove(uid)
        return true
    }
    pluginAdminPendingActions[uid] = PluginAdminPendingAction(
        actionKey,
        now + pluginAdminMapActionConfirmMillis.coerceAtLeast(5_000L),
    )
    player.sendMessage(
        "[red]这是高风险协管操作。[orange]请在 ${pluginAdminMapActionConfirmMillis.coerceAtLeast(5_000L) / 1000L} 秒内再次输入 [white]$commandText[orange] 确认。"
    )
    return false
}

private fun markPluginAdminMapActionSuccess(player: Player?, action: String) {
    if (player == null || !with(trustLevel) { isPluginAdmin(player) }) return
    val uid = PlayerData[player].id
    pluginAdminMapActionCooldownUntil[uid] = System.currentTimeMillis() + pluginAdminMapActionCooldownMillis.coerceAtLeast(0L)
    logger.warning("[3++协管] ${player.plainName()}($uid) 执行了高风险操作：$action")
}

val configEnableInternMaps by config.key(false, "是否开启原版内置地图")
val nextSameMode by config.key(false, "自动换图是否选择相同模式地图,否则选择生存模式")

MapRegistry.register(this, object : MapProvider() {
    override suspend fun searchMaps(search: String?): Collection<MapInfo> {
        if (search == "@internal") return maps.defaultMaps()
            .mapIndexed { i, map -> MapInfo(this, i + 1, Gamemode.survival, map) }
        maps.reload()
        val mapList = (if (configEnableInternMaps) maps.all() else maps.customMaps())
            .sortedBy { it.file.lastModified() }
            .mapIndexed { i, map -> MapInfo(this, i + 1, bestMode(map), map) }
        return when {
            search.isNullOrEmpty() -> mapList
            search == "survive" -> mapList.filter { it.mode == Gamemode.survival }
            search == "attack" -> mapList.filter { it.mode == Gamemode.attack }
            search == "pvp" -> mapList.filter { it.mode == Gamemode.pvp }
            else -> mapList.filter {
                it.name.contains(search, ignoreCase = true) || it.description.contains(search, ignoreCase = true)
            }
        }
    }

    private fun bestMode(map: mindustry.maps.Map): Gamemode {
        // 优先使用地图内 rules 中保存的真实模式；不少本地/资源站地图文件名
        // 不遵守 A/P/S 前缀约定，仅靠文件名会把 PVP 图误识别成生存图。
        val rulesMode = runCatching { map.rules().mode() }.getOrNull()
        if (rulesMode == Gamemode.pvp || rulesMode == Gamemode.attack) return rulesMode

        return when (map.file.name().firstOrNull()) {
            'A' -> Gamemode.attack
            'P' -> Gamemode.pvp
            'S' -> Gamemode.survival
            'C' -> Gamemode.sandbox
            'E' -> Gamemode.editor
            else -> Gamemode.survival
        }
    }
})

registerVarForType<MapInfo>().apply {
    registerChild("id", "在/maps中的id") { it.id.toString().padStart(3, '0') }
    registerChild("mode", "地图设定模式") { it.mode.name }
    registerChild("name", "名字") { it.name }
    registerChild("author", "作者") { it.author }
    registerChild("description", "介绍") { it.description }
}

registerVarForType<MdtMap>().apply {
    registerChild("id", "在/maps中的id(仅支持当前地图)") {
        if (it === state.map) MapManager.current.id
        else -1
    }
    registerChild("mode", "地图设定模式(仅支持当前地图)") {
        if (it === state.map) MapManager.current.mode.name
        else "UnSupport"
    }
}

onEnable {
    //hack to stop origin gameOver logic
    val control = Core.app.listeners.find { it.javaClass.simpleName == "ServerControl" }
    val field = control.javaClass.getDeclaredField("inGameOverWait")
    field.apply {
        isAccessible = true
        logger.info("inExtraRound:" + get(control))
        setBoolean(control, true)
    }
    registerPatchLoadApplyHook("ContentPatchLoadEvent")
    registerPatchLoadApplyHook("DataPatchLoadEvent")
}

val waitingTime by config.key(Duration.ofSeconds(10)!!, "游戏结束换图的等待时间")
val gameOverMsgType by config.key(MsgType.InfoMessage, "游戏结束消息是显示方式")

class GameOverEvent(val winner: Team) : Event, Event.Cancellable {
    /**After cancelled, there is no broadcast and changeMap */
    override var cancelled: Boolean = false
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}
listen<EventType.GameOverEvent> { event ->
    state.gameOver = true
    Call.updateGameOver(event.winner)
    val isPvpGame = state.rules.pvp || MapManager.current.mode == Gamemode.pvp

    ContentHelper.logToConsole(
        if (isPvpGame) "&lcGame over! Team &ly${event.winner.name}&lc is victorious with &ly${Groups.player.size()}&lc players online on map &ly${state.map.name()}&lc."
        else "&lcGame over! Reached wave &ly${state.wave}&lc with &ly${Groups.player.size()}&lc players online on map &ly${state.map.name()}&lc."
    )
    val now = state.map
    launch(Dispatchers.game) {
        if (GameOverEvent(event.winner).emitAsync().cancelled) return@launch
        val pendingNextMap = MapManager.consumePendingAutoRotateMap()
        val map = pendingNextMap?.target ?: MapRegistry.nextMapInfo(
            MapManager.current,
            if (nextSameMode) MapManager.current.mode else Gamemode.survival
        )
        if (state.map != now) return@launch//已经通过其他方式换图
        val winnerMsg: Any =
            if (isPvpGame) "[YELLOW] {team.colorizeName} 队胜利![]".with("team" to event.winner) else ""
        val pendingMsg: Any = pendingNextMap?.let {
            "[accent]下次自动轮换地图投票指定，发起人：[white]${it.requestedBy}[]"
        } ?: ""
        val msg = """
                | [SCARLET]游戏结束![]"
                | {winnerMsg}
                | {pendingMsg}
                | 下一张地图为:[accent]{nextMap.name}[] By: [accent]{nextMap.author}[]
                | 下一场游戏将在 {waitTime} 秒后开始
            """.trimMargin().with(
            "nextMap" to map, "winnerMsg" to winnerMsg, "pendingMsg" to pendingMsg,
            "waitTime" to waitingTime.seconds
        )
        broadcast(msg, gameOverMsgType, quite = true)
        ContentHelper.logToConsole("Next Map is ${map.name}(ID:${map.id})")

        delay(waitingTime.toMillis())
        if (state.map != now) return@launch//已经通过其他方式换图
        MapManager.loadMap(map)
    }
}
fun applyPendingMapState() {
    MapManager.tmpVarSet?.invoke()
    MapManager.tmpVarSet = null
}

private fun registerPatchLoadApplyHook(eventSimpleName: String) {
    val eventClass = EventType::class.java.declaredClasses.firstOrNull { it.simpleName == eventSimpleName } ?: return
    @Suppress("UNCHECKED_CAST")
    val typedClass = eventClass as Class<Any>
    val listener = Cons<Any> { applyPendingMapState() }
    Events.on(typedClass, listener)
    patchLoadHooks += typedClass to listener
}

// 旧版 ContentPatchLoadEvent 与 159 DataPatchLoadEvent 会尽量提前应用；WorldLoadBeginEvent 继续兜底。
// applyPendingMapState 通过 tmpVarSet=null 保证只应用一次。
listen<WorldLoadBeginEvent>(insert = true) {
    applyPendingMapState()
}

onDisable {
    patchLoadHooks.forEach { (eventClass, listener) ->
        runCatching {
            Events::class.java.methods.firstOrNull { method ->
                method.name == "remove" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[1].isAssignableFrom(listener.javaClass)
            }?.invoke(null, eventClass, listener)
        }
    }
    patchLoadHooks.clear()
}
command("host", "管理指令: 换图") {
    usage = "[mapId]"
    permission = "wayzer.maps.host"
    body {
        val map = if (arg.isEmpty()) MapRegistry.nextMapInfo(MapManager.current)
        else arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("[red]请输入正确的地图ID".with())
        val commandText = if (arg.isEmpty()) "/host" else "/host ${arg[0]}"
        if (!requirePluginAdminMapActionConfirmation(player, "host:${map.provider}:${map.id}", commandText)) return@body
        if (MapManager.loadMapSync(map)) {
            broadcast("[green]强制换图为{info}".with("info" to map))
            markPluginAdminMapActionSuccess(player, commandText)
        }
    }
}
command("load", "管理指令: 加载存档") {
    usage = "<slot>"
    permission = "wayzer.maps.load"
    body {
        val file = arg[0].let { saveDirectory.child("$it.$saveExtension") }
        if (!file.exists() || !SaveIO.isSaveValid(file))
            returnReply("[red]存档不存在或者损坏".with())
        MapManager.loadSave(file)
        broadcast("[green]强制加载存档{slot}".with("slot" to arg[0]))
    }
}

command("gameover", "管理指令: 结束游戏") {
    usage = "[winner]"
    permission = "wayzer.maps.gameover"
    body {
        val winner = arg.firstOrNull()?.let { Team.all.firstOrNull { t -> t.name == it } }
            ?: state.rules.waveTeam
        val commandText = if (arg.isEmpty()) "/gameover" else "/gameover ${arg.joinToString(" ")}"
        if (!requirePluginAdminMapActionConfirmation(player, "gameover:${winner.id}", commandText)) return@body
        player?.let { broadcast("[yellow]${it.name}[yellow] 手动结束了当前游戏。".with()) }
        markPluginAdminMapActionSuccess(player, commandText)
        Events.fire(EventType.GameOverEvent(winner))
    }
}

PermissionApi.registerDefault("wayzer.maps.host", "wayzer.maps.load", "wayzer.maps.gameover", group = "@admin")
