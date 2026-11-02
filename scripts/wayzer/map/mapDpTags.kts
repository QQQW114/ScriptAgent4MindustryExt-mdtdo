@file:Depends("coreMindustry", "事件监听")
@file:Depends("wayzer/maps", "地图标签与加载事件")
@file:Depends("wayzer/map/externalCpHotReload", "外部DP加载/卸载")

package wayzer.map

import arc.struct.StringMap
import wayzer.MapChangeEvent
import wayzer.MapLoadFinishedEvent

/**
 * 地图 DP 标签：
 * - `[@dp=文件名.zip]` 或 `[@dp=文件名1.zip,文件名2.zip]`：本局自动加载指定外部DP；
 * - `[@nodp]` / `[@dp=none]` / `[@dp=off]`：本局禁止所有DP。
 * 标签写在“地图简介”里，由 wayzer/maps 的 applyDescriptionTags 转成 rules.tags。
 * 本脚本只负责在 MapChangeEvent 设置策略，在 MapLoadFinishedEvent 执行加载/卸载；
 * 进入地图后 /externalcp、投票、菜单的加载也会被 externalCpHotReload 的策略检查拦截。
 */
name = "地图DP标签"

private val externalCp = contextScript<ExternalCpHotReload>()

private var pendingBlockAll = false
private var pendingAllowedTargets: List<String>? = null
private var pendingSource = ""

private fun parseAllowedTargets(raw: String?): List<String>? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.equals("true", ignoreCase = true)) return null
    if (
        value.equals("off", ignoreCase = true) ||
        value.equals("none", ignoreCase = true) ||
        value.equals("false", ignoreCase = true) ||
        value == "0"
    ) {
        return emptyList()
    }
    return value.split(',', '|', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
}

private fun hasTag(tags: StringMap, name: String): Boolean =
    tags.keys().any { it.equals(name, ignoreCase = true) }

private fun tagValue(tags: StringMap, name: String): String? =
    tags.entries().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

listenTo<MapChangeEvent> {
    val tags = rules.tags
    val noDp = hasTag(tags, "@nodp") || hasTag(tags, "@noDP") || hasTag(tags, "@dpnone")
    val dpRaw = tagValue(tags, "@dp")
    val dpAllowed = parseAllowedTargets(dpRaw)

    pendingBlockAll = noDp || (dpAllowed != null && dpAllowed.isEmpty())
    pendingAllowedTargets = if (pendingBlockAll) null else dpAllowed
    pendingSource = when {
        noDp -> "地图标签 @nodp"
        pendingBlockAll -> "地图标签 @dp=$dpRaw"
        dpAllowed != null -> "地图标签 @dp"
        else -> ""
    }
    with(externalCp) { setMapDpPolicy(pendingBlockAll, pendingAllowedTargets, pendingSource) }
}

listenTo<MapLoadFinishedEvent> {
    if (pendingBlockAll) {
        runCatching {
            val message = with(externalCp) { unloadAllServerCpsForMapTag("地图标签 @nodp") }
            logger.info("地图标签禁止DP：${message.replace(Regex("\\[[^\\]]*]"), "")}")
        }.onFailure {
            logger.warning("地图标签 @nodp 卸载DP失败: ${it.message}")
        }
        return@listenTo
    }

    val targets = pendingAllowedTargets ?: return@listenTo
    if (targets.isEmpty()) return@listenTo

    // 先撤掉当前已加载的DP，保证本局只保留地图标签指定的集合。
    runCatching {
        with(externalCp) { unloadAllServerCpsForMapTag("地图标签 @dp 清理旧DP") }
    }.onFailure {
        logger.warning("地图标签 @dp 清理旧DP失败: ${it.message}")
    }
    targets.forEach { target ->
        runCatching {
            val result = with(externalCp) { loadServerCp(target, "地图标签 @dp") }
            val clean = result.replace(Regex("\\[[^\\]]*]"), "")
            if (result.contains("[red]")) logger.warning("地图标签加载DP失败 target=$target: $clean")
            else logger.info("地图标签加载DP成功 target=$target: $clean")
        }.onFailure {
            logger.warning("地图标签加载DP异常 target=$target: ${it.stackTraceToString()}")
        }
    }
}

onEnable {
    logger.info("地图DP标签已启用：支持 [@dp=文件名.zip,文件名2.zip] 与 [@nodp]")
}

onDisable {
    pendingBlockAll = false
    pendingAllowedTargets = null
    pendingSource = ""
}
