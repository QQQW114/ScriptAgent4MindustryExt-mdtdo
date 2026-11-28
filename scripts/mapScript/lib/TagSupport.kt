package mapScript.lib

import cf.wayzer.scriptAgent.ScriptRegistry
import cf.wayzer.scriptAgent.define.Script
import coreMindustry.lib.MapTagRegistry
import mindustry.Vars
import mindustry.game.Rules
import kotlin.properties.ReadOnlyProperty

/**用于注册Tag类的mapScript，通常存放位置为`mapScript/tag/xxx` */
object TagSupport {
    // tag -> scriptId
    val knownTags = mutableMapOf<String, String>()

    fun findTagScript(tag: String): String? {
        val normalized = if (tag.startsWith("@")) tag else "@$tag"
        knownTags.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value?.let { id ->
            if (ScriptRegistry.getScriptInfo(id) != null) return id
        }
        return MapTagRegistry.findScriptId(normalized)
    }

    fun findTags(rules: Rules): Map<String, String> {
        val mapTags = rules.tags.keys().toSet()
        val result = linkedMapOf<String, String>()

        // 大小写不敏感匹配所有以 @ 开头的规则标签，并统一委托 MapTagRegistry 解析别名与约定路径。
        mapTags.forEach { tag ->
            if (!tag.startsWith("@")) return@forEach
            findTagScript(tag)?.let { scriptId ->
                if (scriptId !in result.values) {
                    result[tag] = scriptId
                }
            }
        }

        return result
    }
}

fun Script.registerMapTag(name: String) {
    TagSupport.knownTags[name] = id
    MapTagRegistry.registerDynamic(name, id)
    onUnload {
        TagSupport.knownTags.remove(name)
        MapTagRegistry.unregisterDynamic(name, id)
    }
}

fun Script.mapTag(name: String): ReadOnlyProperty<Any?, String> {
    registerMapTag(name)
    return ReadOnlyProperty { _, _ ->
        Vars.state.rules.tags.get(name).orEmpty()
    }
}
