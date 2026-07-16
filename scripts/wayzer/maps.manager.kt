@file:Suppress("MemberVisibilityCanBePrivate")

package wayzer

import arc.files.Fi
import arc.struct.StringMap
import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.emitAsync
import cf.wayzer.scriptAgent.thisContextScript
import coreLibrary.lib.config
import coreLibrary.lib.with
import coreMindustry.lib.broadcast
import coreMindustry.lib.game
import coreMindustry.lib.nextTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.core.GameState
import mindustry.game.Gamemode
import mindustry.game.Rules
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.io.MapIO
import mindustry.io.SaveIO
import mindustry.maps.Map

typealias RuleModifier = Rules.() -> Unit

class MapChangeEvent(
    val info: MapInfo,
    val map: Map,
    val rules: Rules = map.applyRules(info.mode),
) :
    Event, Event.Cancellable {
    /** Should call other load*/
    override var cancelled: Boolean = false
    @Deprecated("modify rules directly", ReplaceWith("rules.block()"))
    fun modifyRule(block: RuleModifier) {
        rules.block()
    }

        companion object : Event.Handler()
}

class MapLoadFinishedEvent(
    val info: MapInfo,
    val map: Map,
    val rules: Rules,
) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

object MapManager {
    var current: MapInfo =
        MapInfo(MapRegistry.SaveProvider, Vars.state.rules.idInTag, Vars.state.rules.mode(), Vars.state.map)
        private set
    internal var tmpVarSet: (() -> Unit)? = null

    data class PendingAutoRotateMap(
        val target: MapInfo,
        val requestedBy: String,
    )

    private var pendingAutoRotateMap: PendingAutoRotateMap? = null

    fun setPendingAutoRotateMap(target: MapInfo, requestedBy: String) {
        pendingAutoRotateMap = PendingAutoRotateMap(target, requestedBy)
    }

    fun getPendingAutoRotateMap(): PendingAutoRotateMap? =
        pendingAutoRotateMap

    fun consumePendingAutoRotateMap(): PendingAutoRotateMap? {
        val pending = getPendingAutoRotateMap()
        pendingAutoRotateMap = null
        return pending
    }

    fun clearPendingAutoRotateMap() {
        pendingAutoRotateMap = null
    }

    @Deprecated("old", level = DeprecationLevel.HIDDEN)
    fun loadMap(info: MapInfo? = null, isSave: Boolean = false) {
        loadMap(info)
    }

    fun loadMap(info: MapInfo? = null) {
        thisContextScript().launch(Dispatchers.game) {
            loadMapSync(info)
        }
    }

    suspend fun loadMapSync(info: MapInfo? = null): Boolean {
        @Suppress("NAME_SHADOWING") var info: MapInfo? = info
        try {
            info = info ?: MapRegistry.nextMapInfo()
            val map = info.loadMap().copyWithDisplayMeta(info)
            return loadMapSync(info, map)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            broadcast(
                "[red]加载地图地图{info.name}失败: {reason}".with(
                    "info" to (info ?: ""),
                    "reason" to (e.message ?: "")
                )
            )
            thisContextScript().launch(Dispatchers.game) {
                delay(1000)
                loadMapSync()
            }
            return false
        }
    }

    suspend fun loadMapSync(info: MapInfo, map: Map): Boolean {
        val event = MapChangeEvent(info, map).apply {
            rules.idInTag = info.id
            applyDescriptionTags(rules, map.description())
            if (info.description != map.description()) {
                applyDescriptionTags(rules, info.description)
            }
        }
        if (event.emitAsync().cancelled) return false

        thisContextScript().logger.info("loadMap $info")
        if (!Vars.net.server()) Vars.netServer.openServer()
        val players = Groups.player.toList()
        Call.worldDataBegin()
        Vars.logic.reset()
        //Hack: Some old tasks have posted, so we let they run.
        Vars.world.resize(0, 0)
        nextTick()

        current = info
        try {
            tmpVarSet = block@{
                if (map == MapRegistry.GeneratorMap) {
                    Vars.state.rules.idInTag = info.id
                    return@block
                }
                Vars.state.map = map
                Vars.state.rules = event.rules
            }
            info.provider.loadMap(info) // EventType.ResetEvent
            // EventType.WorldLoadBeginEvent : do set state.rules
            // EventType.WorldLoadEndEvent
            // EventType.WorldLoadEvent
            // Not generator: EventType.SaveLoadEvent
            if (map != MapRegistry.GeneratorMap) {
                // SaveVersion.readMeta may restore state.map from mapname in the .msav metadata.
                // Some resource-site maps keep "Editor Playtesting"/Unknown in the file while the
                // correct display name/author only exists in MapInfo.meta, so force the enriched map
                // back after loading as a final display-state guard.
                Vars.state.map = map
            }
            restoreModeFlags(info, event.rules, Vars.state.rules)
            mergeRuleTags(event.rules, Vars.state.rules)
            thisContextScript().logger.info(
                "loadMap rules: infoMode=${info.mode}, rulesMode=${Vars.state.rules.mode()}, pvp=${Vars.state.rules.pvp}, activeTeams=${
                    Vars.state.teams.getActive().filter { it.hasCore() }.joinToString(",") { it.team.name }
                }"
            )
            MapLoadFinishedEvent(info, Vars.state.map, Vars.state.rules).emitAsync()
        } catch (e: Throwable) {
            tmpVarSet = null
            players.forEach { it.add() }
            throw e
        }


        if (info.provider == MapRegistry.SaveProvider) {
            Vars.state.set(GameState.State.playing)
        } else {
            Vars.logic.play() // EventType.PlayEvent
        }

        players.forEach {
            if (it.con == null) return@forEach
            it.admin.let { was ->
                it.reset()
                it.admin = was
            }
        }
        // PVP换图时不能把上一张图/上一局的玩家队伍当成“旧队伍”保留下来。
        // betterTeam.randomTeam 会优先沿用 player.team()，用于玩家重连回到原队伍；
        // 但 MapManager 在换图后给所有在线玩家重新分队时，若玩家上一局都在 sharded，
        // 且新PVP图也包含 sharded 核心，就会导致所有玩家继续留在同一队。
        // 这里在 reset 之后、assignTeam 之前把待重分配的在线玩家临时置为 derelict，
        // 让 assigner 按当前PVP图核心队伍重新均衡分配。
        if (Vars.state.rules.pvp || info.mode == Gamemode.pvp) {
            players.filter { it.con != null }.forEach { it.team(Team.derelict) }
        }
        players.forEach {
            if (it.con == null) return@forEach
            it.team(Vars.netServer.assignTeam(it, players))
            Vars.netServer.sendWorldData(it)
        }
        players.forEach { it.add() }
        return true
    }

    fun loadSave(file: Fi) {
        val map = MapIO.createMap(file, true)
        loadMap(MapInfo(MapRegistry.SaveProvider, map.rules().idInTag, map.rules().mode(), map))
    }

    fun getSlot(id: Int): Fi? {
        val file = SaveIO.fileFor(id)
        if (!SaveIO.isSaveValid(file)) return null
        val voteFile = SaveIO.fileFor(configTempSaveSlot)
        if (voteFile.exists()) voteFile.delete()
        file.copyTo(voteFile)
        return voteFile
    }

    //private
    private val configTempSaveSlot by thisContextScript().config.key(111, "临时缓存的存档格位")

    /** Use for identity Save */
    private var Rules.idInTag: Int
        get() = tags.getInt("id", -1)
        set(value) {
            tags.put("id", value.toString())
        }

    private val mapTagRegex = Regex("\\[(@[a-zA-Z0-9]+)(=[^=\\]]+)?]")

    private fun applyDescriptionTags(rules: Rules, description: String) {
        mapTagRegex.findAll(description).forEach {
            val value = it.groupValues[2].takeIf(String::isNotEmpty) ?: "true"
            rules.tags.put(it.groupValues[1], value.removePrefix("="))
        }
    }

    private fun mergeRuleTags(from: Rules, to: Rules) {
        from.tags.keys().forEach { key ->
            to.tags.put(key, from.tags.get(key, ""))
        }
    }

    private fun restoreModeFlags(info: MapInfo, from: Rules, to: Rules) {
        // SaveIO.load may restore rules from the raw .msav after MapChangeEvent has prepared
        // map.applyRules(info.mode). If the uploaded map metadata says PVP but the embedded file
        // falls back to survival rules, state.rules.pvp becomes false: players will not be split
        // into PVP teams and gameover will not show a winning team. Keep map-specific rules loaded
        // by SaveIO, but restore the mode-critical flags from the selected MapInfo mode.
        to.pvp = info.mode == Gamemode.pvp || from.pvp
        to.attackMode = info.mode == Gamemode.attack || from.attackMode
        to.waves = from.waves
        to.defaultTeam = from.defaultTeam
        to.waveTeam = from.waveTeam
    }

    private fun Map.copyWithDisplayMeta(info: MapInfo): Map {
        val copiedTags = StringMap(tags)

        fun clean(value: String?): String? =
            value?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

        fun putMeta(tag: String) {
            clean(info.meta[tag])?.let { copiedTags.put(tag, it) }
        }

        // Resource-site API metadata is more reliable for display than the embedded msav meta.
        // Only overlay display fields; do not overwrite description blindly, because mapScript
        // tags such as [@flood] often live in the original map description.
        putMeta("name")
        putMeta("author")
        if (clean(copiedTags.get("description")) == null)
            clean(info.meta["description"])?.let { copiedTags.put("description", it) }

        return Map(file, width, height, copiedTags, custom, version, build).also {
            it.workshop = workshop
            it.spawns = spawns
            it.texture = texture
            it.mod = mod
            it.teams.addAll(teams)
        }
    }
}
