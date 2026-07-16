package mapScript.lib

import cf.wayzer.scriptAgent.ScriptRegistry
import cf.wayzer.scriptAgent.define.Script
import mindustry.Vars
import mindustry.game.Rules
import kotlin.properties.ReadOnlyProperty

/**用于注册Tag类的mapScript，通常存放位置为`mapScript/tag/xxx` */
object TagSupport {
    // tag -> scriptId
    val knownTags = mutableMapOf<String, String>()
    private val fallbackTags = mapOf(
        "@floodV2" to "mapScript/tags/flood"
    )

    fun findTagScript(tag: String): String? {
        val normalized = if (tag.startsWith("@")) tag else "@$tag"
        knownTags[normalized]?.let { return it }

        val scriptId = fallbackTags[normalized] ?: "mapScript/tags/${normalized.removePrefix("@")}"
        return scriptId.takeIf { ScriptRegistry.getScriptInfo(it) != null }
    }

    fun findTags(rules: Rules): Map<String, String> {
        val mapTags = rules.tags.keys().toSet()
        val result = knownTags.filterKeys { it in mapTags }.toMutableMap()

        // Tag scripts are not enabled during normal startup, so knownTags can be empty
        // before the first map using a tag is loaded. Fall back to the conventional path
        // mapScript/tags/<tagName> to let tagged maps load their tag scripts directly.
        mapTags.forEach { tag ->
            if (!tag.startsWith("@") || result.containsKey(tag)) return@forEach

            findTagScript(tag)?.let { result[tag] = it }
        }

        return result
    }
}

fun Script.registerMapTag(name: String) {
    TagSupport.knownTags[name] = id
    onUnload { TagSupport.knownTags.remove(name) }
}

fun Script.mapTag(name: String): ReadOnlyProperty<Any?, String> {
    registerMapTag(name)
    return ReadOnlyProperty { _, _ ->
        Vars.state.rules.tags.get(name).orEmpty()
    }
}
