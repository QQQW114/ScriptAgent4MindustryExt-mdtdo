@file:Depends("coreMindustry")
@file:Depends("wayzer/maps", "获取地图信息")
@file:Depends("wayzer/map/mapInfo", "显示地图信息", soft = true)
@file:Import("mapScript.lib.*", defaultImport = true)

/**
 * 该模块定义了一种特殊的kts：kts的生命周期与地图关联。
 * 当地图满足特定条件时(id/tag)，关联的kts会被enable，而一局游戏结束后，所有的kts会被disable。
 * */
package mapScript

import arc.Events
import arc.func.Cons
import arc.struct.Seq
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import mindustry.game.EventType
import wayzer.MapManager
import wayzer.MapLoadFinishedEvent
import wayzer.MapRegistry

val children get() = ScriptRegistry.allScripts { it != scriptInfo && it.dependsOn(scriptInfo) }
var loadedMapScriptKey: String? = null
private val hintedCommandKeys = mutableSetOf<String>()
private val patchLoadHooks = mutableListOf<Pair<Class<Any>, Cons<Any>>>()
private val scanRootOnReset by config.key(false, "每次换图/Reset时重新扫描地图脚本目录。生产服建议关闭，避免每局结束时磁盘扫描和脚本卸载造成卡顿")

private fun disableCurrentMapScripts(reason: String) {
    val startedAt = System.currentTimeMillis()
    MindustryDispatcher.safeBlocking {
        val targets = children.filter { it.enabled && it.inst?.mapScriptController != true }
        ScriptManager.transactionV2 {
            disable(targets)
            execute().printResult()
            val stillEnabled = targets.filter { it.enabled }
            if (stillEnabled.isNotEmpty()) {
                logger.warning("地图脚本卸载后仍有脚本处于启用状态(reason=$reason): ${stillEnabled.joinToString { it.id }}")
            }
        }.printResult()
    }
    val cost = System.currentTimeMillis() - startedAt
    if (cost >= 250L) logger.warning("地图脚本卸载耗时 ${cost}ms(reason=$reason)")
}

listen<EventType.ResetEvent> {
    loadedMapScriptKey = null
    hintedCommandKeys.clear()
    disableCurrentMapScripts("reset")
}

listen<EventType.ResetEvent> {
    // try update child scripts. 生产环境中每局换图扫描脚本根目录会带来明显停顿，默认关闭；调试热更新时再开启。
    if (!scanRootOnReset) return@listen
    val startedAt = System.currentTimeMillis()
    ScriptRegistry.scanRoot()
    MindustryDispatcher.safeBlocking {
        val outdated = children.filter {
            it.compiledScript?.source.run { this != null && this != it.source }
        }
        if (outdated.isNotEmpty()) {
            logger.info("Reload outdated script: $outdated")
            ScriptManager.transactionV2 {
                outdated.forEach { reload(it) }
            }.printResult()
        }
    }
    val cost = System.currentTimeMillis() - startedAt
    if (cost >= 250L) logger.warning("地图脚本Reset扫描耗时 ${cost}ms；生产服可保持 scanRootOnReset=false")
}

fun getToLoadMapScripts(): List<ScriptInfo> {
    return buildList {
        ScriptManager.getScriptNullable("mapScript/${MapManager.current.id}")?.id?.let { add(it) }
        state.rules.tags.get("@mapScript")?.let { add("mapScript/${it.toIntOrNull() ?: MapManager.current.id}") }
        addAll(TagSupport.findTags(state.rules).values)
    }.mapNotNull { scriptId ->
        ScriptRegistry.getScriptInfo(scriptId) ?: null.also {
            delayBroadcast("[red]该服务器不存在对应地图脚本，请联系管理员: {id}".with("id" to scriptId))
        }
    }
}

private fun CommandInfo.isUserFacingMapCommand(scriptIds: Set<String>): Boolean {
    val scriptId = script?.id ?: return false
    if (scriptId !in scriptIds || script?.enabled != true) return false
    if (attrs.any { it is Commands.Permission }) return false
    val lowerName = name.lowercase()
    if ("debug" in lowerName || "test" in lowerName) return false
    if (description.toString().contains("CHEATER", ignoreCase = true)) return false
    return true
}

private fun mapCommandAccessNames(command: CommandInfo): List<String> {
    val names = (listOf(command.name) + command.aliases)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    val conflicts = names.filter { name ->
        val visible = Commands.Root.subCommands()[name.lowercase()]
        visible != null && visible !== command && visible.script?.id?.startsWith("mapScript/") != true
    }
    return (conflicts.ifEmpty { listOf(command.name) })
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
}

fun hintMapScriptCommands(loadedScripts: List<ScriptInfo>) {
    val scriptIds = loadedScripts.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
    if (scriptIds.isEmpty()) return
    val names = Commands.Root.registeredSubCommands()
        .filter { it.isUserFacingMapCommand(scriptIds) }
        .flatMap { mapCommandAccessNames(it) }
        .distinctBy { it.lowercase() }
    if (names.isEmpty()) return
    val key = "${loadedMapScriptKey}:${names.joinToString(",")}"
    if (!hintedCommandKeys.add(key)) return

    val shown = names.take(6).joinToString("[]、[gold]") { "/mapcmd $it" }
    val more = if (names.size > 6) " [gray]等${names.size}个" else ""
    delayBroadcast("[yellow]可使用[gold]$shown[][yellow]来打开地图特定指令!$more".with())
}

fun loadCurrentMapScripts() {
    //load scripts
    val toLoad = getToLoadMapScripts()
    if (toLoad.isEmpty()) return
    val key = "${MapManager.current.provider}:${MapManager.current.id}"
    if (loadedMapScriptKey == key) return
    val previousKey = loadedMapScriptKey
    if (previousKey != null && previousKey != key) {
        // 正常换图会先触发 ResetEvent 卸载旧地图脚本；这里是兜底，防止异常加载流程导致新旧地图脚本/CP 同时启用。
        disableCurrentMapScripts("before-load:$previousKey->$key")
    }
    loadedMapScriptKey = key
    val startedAt = System.currentTimeMillis()
    MindustryDispatcher.safeBlocking {
        ScriptManager.transactionV2 { enable(toLoad) }.printResult()
    }
    val cost = System.currentTimeMillis() - startedAt
    if (cost >= 250L) logger.warning("地图脚本加载耗时 ${cost}ms: ${toLoad.joinToString { it.id }}")
    toLoad.forEach { checkEnabled(it) }
    hintMapScriptCommands(toLoad)
}

private fun memberValue(target: Any?, name: String): Any? {
    if (target == null) return null
    target.javaClass.fields.firstOrNull { it.name == name }?.let { field ->
        return runCatching { field.get(target) }.getOrNull()
    }
    target.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == name }?.let { method ->
        return runCatching { method.invoke(target) }.getOrNull()
    }
    return null
}

private fun addMapPatchStringsToEvent(event: Any, patches: List<String>) {
    @Suppress("UNCHECKED_CAST")
    val oldPatchSeq = memberValue(event, "patches") as? Seq<String>
    if (oldPatchSeq != null) {
        patches.forEach { oldPatchSeq.add(it) }
        return
    }

    @Suppress("UNCHECKED_CAST")
    val assets = memberValue(event, "assets") as? Seq<Any>
    if (assets != null) {
        val patchAssetClass = Class.forName("mindustry.mod.data.PatchAsset")
        val ctor = patchAssetClass.constructors.first { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
        patches.forEach { patch -> assets.add(ctor.newInstance(patch)) }
    }
}

private fun registerMapPatchLoadHook(eventSimpleName: String) {
    val eventClass = EventType::class.java.declaredClasses.firstOrNull { it.simpleName == eventSimpleName } ?: return
    @Suppress("UNCHECKED_CAST")
    val typedClass = eventClass as Class<Any>
    val listener = Cons<Any> { event ->
        val patches = getToLoadMapScripts().flatMap { it.inst?.mapPatches.orEmpty() }
        if (patches.isNotEmpty()) {
            runCatching {
                addMapPatchStringsToEvent(event, patches)
                logger.info("Patches loaded($eventSimpleName): ${patches.size}")
            }.onFailure {
                logger.warning("地图脚本CP注入失败($eventSimpleName): ${it.message}")
            }
        }
    }
    Events.on(typedClass, listener)
    patchLoadHooks += typedClass to listener
}

listen<EventType.WorldLoadEvent> { loadCurrentMapScripts() }
listenTo<MapLoadFinishedEvent> { loadCurrentMapScripts() }

onEnable {
    MapRegistry.register(this, ScriptMapGenerator.Provider)
    registerMapPatchLoadHook("ContentPatchLoadEvent")
    registerMapPatchLoadHook("DataPatchLoadEvent")
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
