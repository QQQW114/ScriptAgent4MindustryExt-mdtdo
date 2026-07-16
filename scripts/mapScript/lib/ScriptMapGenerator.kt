package mapScript.lib

import arc.Events
import arc.struct.Seq
import arc.struct.StringMap
import arc.util.Log
import cf.wayzer.placehold.VarString
import cf.wayzer.scriptAgent.ScriptManager
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.define.ScriptDsl
import cf.wayzer.scriptAgent.thisContextScript
import coreMindustry.lib.MindustryDispatcher
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Rules
import mindustry.io.JsonIO
import mindustry.maps.Map
import mindustry.world.Tiles
import wayzer.MapInfo
import wayzer.MapManager
import wayzer.MapProvider
import java.util.logging.Level
import kotlin.system.measureTimeMillis

class ScriptMapGenerator(val script: Script, val width: Int, val height: Int) {
    private val genRounds = LinkedHashMap<String, (tiles: Tiles) -> Unit>()
    val rules = Rules()

    init {
        genRound("init") { it.fill() }
    }

    fun genRound(name: String, body: (tiles: Tiles) -> Unit) {
        genRounds[name] = body
    }

    private fun memberValue(target: Any?, name: String): Any? {
        if (target == null) return null
        val clazz = target.javaClass
        clazz.fields.firstOrNull { it.name == name }?.let { field ->
            return runCatching { field.get(target) }.getOrNull()
        }
        clazz.methods.firstOrNull { it.parameterCount == 0 && it.name == name }?.let { method ->
            return runCatching { method.invoke(target) }.getOrNull()
        }
        return null
    }

    private fun eventClass(simpleName: String): Class<*>? =
        EventType::class.java.declaredClasses.firstOrNull { it.simpleName == simpleName }

    private fun fireEventWithSeq(eventSimpleName: String, seq: Seq<*>): Boolean {
        val eventClass = eventClass(eventSimpleName) ?: return false
        val ctor = eventClass.constructors.firstOrNull { ctor ->
            ctor.parameterTypes.size == 1 && Seq::class.java.isAssignableFrom(ctor.parameterTypes[0])
        } ?: return false
        Events.fire(ctor.newInstance(seq))
        return true
    }

    private fun applyLegacyPatchStrings(patches: Seq<String>): Boolean {
        val patcher = memberValue(Vars.state, "patcher") ?: return false
        val apply = patcher.javaClass.methods.firstOrNull { method ->
            method.name == "apply" &&
                method.parameterTypes.size == 1 &&
                Seq::class.java.isAssignableFrom(method.parameterTypes[0])
        } ?: return false
        apply.invoke(patcher, patches)
        return true
    }

    private fun loadNativeAssets(assets: Seq<Any>): Boolean {
        val data = memberValue(Vars.state, "data") ?: return false
        val load = data.javaClass.methods.firstOrNull { method ->
            method.name == "load" &&
                method.parameterTypes.size == 1 &&
                Seq::class.java.isAssignableFrom(method.parameterTypes[0])
        } ?: return false
        if (assets.isEmpty) return true
        load.invoke(data, assets)
        return true
    }

    private fun loadPatchesCompat() {
        // Mindustry 159+ 将 ContentPatchLoadEvent/state.patcher 迁移为 DataPatchLoadEvent/state.data。
        val assets = Seq<Any>()
        if (fireEventWithSeq("DataPatchLoadEvent", assets)) {
            if (!loadNativeAssets(assets)) {
                Log.warn("DataPatchLoadEvent fired, but DataManager.load is unavailable.")
            }
            return
        }

        val patches = Seq<String>()
        if (fireEventWithSeq("ContentPatchLoadEvent", patches) && !patches.isEmpty) {
            if (!applyLegacyPatchStrings(patches)) {
                Log.warn("ContentPatchLoadEvent fired, but legacy ContentPatcher.apply is unavailable.")
            }
        }
    }

    fun load() {
        try {
            //Load patches, MDT don't do this with loadGenerator
            try {
                loadPatchesCompat()
            } catch (e: Throwable) {
                Log.err("Failed to apply generator patches", e)
            }

            Vars.world.loadGenerator(width, height) { tiles ->
                genRounds.forEach { (name, round) ->
                    val time = measureTimeMillis { round.invoke(tiles) }
                    script.logger.info("Generate $name costs $time ms.")
                }
            }
        } catch (e: Throwable) {
            script.logger.log(Level.SEVERE, "loadGenerator出错", e)
            MapManager.loadMap()
        }
        MindustryDispatcher.safeBlocking {
            ScriptManager.enableScript(script, true)
        }
        if (!thisContextScript().checkEnabled(script.scriptInfo)) {
            MapManager.loadMap()
        }
    }

    data class Info(val info: MapInfo, val filters: Set<String>, val generator: ScriptMapGenerator)

    object Provider : MapProvider() {
        val knownMaps = mutableMapOf<Int, Info>()

        override suspend fun searchMaps(search: String?) = knownMaps.values
            .filter { search == null || search in it.filters }
            .map { it.info }

        override suspend fun findById(id: Int, reply: ((VarString) -> Unit)?): MapInfo? {
            return knownMaps[id]?.info
        }

        override suspend fun lazyGetMap(info: MapInfo): Map {
            val generator = knownMaps[info.id]!!.generator
            return Map(
                Vars.customMapDirectory.child("unknown"), generator.width, generator.height,
                StringMap().apply {
                    put("name", info.name)
                    put("author", info.author)
                    put("description", info.description)
                    put("rules", JsonIO.write(generator.rules))
                }, true
            )
        }

        override suspend fun loadMap(info: MapInfo) {
            val generator = knownMaps[info.id]?.generator ?: return MapManager.loadMap()
            generator.load()
        }
    }
}

@ScriptDsl
fun Script.registerGenerator(
    name: String,
    author: String,
    description: String,
    mode: Gamemode = Gamemode.survival,
    filter: Set<String> = setOf("all", "display", "special"),
    width: Int,
    height: Int,
    body: ScriptMapGenerator.() -> Unit
) {
    val mapId = id.split('/').last().toIntOrNull() ?: error("MapScript must named as {id}.kts")
    val info = MapInfo(
        ScriptMapGenerator.Provider, mapId, mode, meta = mapOf(
            "name" to name,
            "author" to author,
            "description" to description
        )
    )
    val generator = ScriptMapGenerator(this, width, height).apply(body)
    ScriptMapGenerator.Provider.knownMaps[mapId] = ScriptMapGenerator.Info(info, filter, generator)
    onUnload {
        ScriptMapGenerator.Provider.knownMaps.remove(mapId)
    }
}
