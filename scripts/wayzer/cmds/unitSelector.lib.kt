package wayzer.cmds

import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import mindustry.type.UnitType
import wayzer.lib.PlayerData

/** 统一管理指令单位选择器。主推 Minecraft 风格：@e[team=2,unit=mono]。 */
data class UnitSelection(
    val units: List<Unit>,
    val label: String,
    val directPlayer: Player? = null,
)

private data class UnitSelectorFilters(
    val team: Team? = null,
    val unitType: UnitType? = null,
    val limit: Int? = null,
    val sort: String? = null,
)

fun unitSelectorHelpText(): String =
    "@e、@e[unit=mono]、@e[team=2]、@e[team=2,unit=mono]、@a、@s；兼容旧写法 @t[2]/@u[mono]/@t[2,mono]；也可直接输入玩家UUID/短ID/#游戏ID/名字"

private fun safePlayerUnit(player: Player): Unit? =
    runCatching { player.unit() }.getOrNull()?.takeIf { it.isValid && !it.dead }

fun findOnlinePlayer(input: String, fuzzy: Boolean = true): Player? {
    val key = input.trim()
    if (key.isBlank()) return null
    if (key.startsWith("#")) {
        key.removePrefix("#").toIntOrNull()?.let { id ->
            Groups.player.getByID(id)?.let { return it }
        }
    }
    PlayerData.findByShortId(key)?.player?.let { return it }
    val plain = key.replace(" ", "")
    val exact = Groups.player.find {
        it.uuid() == key ||
                PlayerData[it].id == key ||
                PlayerData[it].shortId.equals(key, ignoreCase = true) ||
                it.name.equals(key, ignoreCase = true) ||
                it.plainName().equals(key, ignoreCase = true) ||
                it.name.replace(" ", "").equals(plain, ignoreCase = true) ||
                it.plainName().replace(" ", "").equals(plain, ignoreCase = true)
    }
    if (exact != null || !fuzzy) return exact
    return Groups.player.find {
        it.name.contains(key, ignoreCase = true) || it.plainName().contains(key, ignoreCase = true)
    }
}

private fun findUnitType(input: String): UnitType? {
    val key = input.trim().removePrefix("unit-")
    if (key.isBlank()) return null
    return Vars.content.units().find {
        it.name.equals(key, ignoreCase = true) || it.localizedName.equals(key, ignoreCase = true)
    }
}

private fun findTeam(input: String): Team? {
    val key = input.trim()
    if (key.isBlank()) return null
    key.toIntOrNull()?.let { id -> Team.all.getOrNull(id)?.let { return it } }
    return Team.all.firstOrNull { it.name.equals(key, ignoreCase = true) }
}

private fun unitTypeLabel(type: UnitType): String =
    "${type.localizedName}(${type.name})"

private fun parseSelectorBody(body: String): Map<String, String>? {
    if (body.isBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    body.split(',').forEach { rawPart ->
        val part = rawPart.trim()
        if (part.isBlank()) return@forEach
        val idx = part.indexOf('=')
        if (idx <= 0) return null
        val key = part.substring(0, idx).trim().lowercase()
        val value = part.substring(idx + 1).trim()
        if (key.isBlank() || value.isBlank()) return null
        result[key] = value
    }
    return result
}

private fun parseFilters(body: String): UnitSelectorFilters? {
    val args = parseSelectorBody(body) ?: return null
    var team: Team? = null
    var unitType: UnitType? = null
    var limit: Int? = null
    var sort: String? = null
    args.forEach { (key, value) ->
        when (key) {
            "team" -> team = findTeam(value) ?: return null
            "unit", "type" -> unitType = findUnitType(value) ?: return null
            "limit" -> limit = value.toIntOrNull()?.takeIf { it > 0 } ?: return null
            "sort" -> {
                val normalized = value.lowercase()
                if (normalized !in setOf("nearest", "furthest", "random")) return null
                sort = normalized
            }
            else -> return null
        }
    }
    return UnitSelectorFilters(team, unitType, limit, sort)
}

private fun applyFilters(
    units: List<Unit>,
    filters: UnitSelectorFilters,
    executor: Player?,
): List<Unit> {
    var result = units.asSequence()
        .filter { it.isValid && !it.dead }
    filters.team?.let { team -> result = result.filter { it.team == team } }
    filters.unitType?.let { type -> result = result.filter { it.type == type } }
    val sorted = when (filters.sort) {
        "random" -> result.toList().shuffled()
        "nearest", "furthest" -> {
            val origin = executor?.let { safePlayerUnit(it) }
            val list = result.toList()
            if (origin == null) list else {
                val sortedList = list.sortedBy { ((origin.x - it.x) * (origin.x - it.x) + (origin.y - it.y) * (origin.y - it.y)) }
                if (filters.sort == "furthest") sortedList.asReversed() else sortedList
            }
        }
        else -> result.toList()
    }
    return filters.limit?.let { sorted.take(it) } ?: sorted
}

private fun selectorLabel(base: String, filters: UnitSelectorFilters): String {
    val parts = mutableListOf<String>()
    filters.team?.let { parts += "队伍 ${it.id}(${it.name})" }
    filters.unitType?.let { parts += unitTypeLabel(it) }
    filters.limit?.let { parts += "最多${it}个" }
    filters.sort?.let { parts += "排序:${it}" }
    return if (parts.isEmpty()) base else "$base（${parts.joinToString("，")}）"
}

private fun resolveLegacySelector(key: String): UnitSelection? {
    Regex("""@t\[(\d{1,3})]""").matchEntire(key.lowercase())?.let { match ->
        val team = Team.all.getOrNull(match.groupValues[1].toIntOrNull() ?: return null) ?: return null
        return UnitSelection(
            Groups.unit.toList().filter { it.isValid && !it.dead && it.team == team },
            "队伍 ${team.id}(${team.name}) 的所有单位"
        )
    }
    Regex("""@(u|unit|type)\[([^]]+)]""").matchEntire(key.lowercase())?.let { match ->
        val type = findUnitType(match.groupValues[2]) ?: return null
        return UnitSelection(
            Groups.unit.toList().filter { it.isValid && !it.dead && it.type == type },
            "所有 ${unitTypeLabel(type)} 单位"
        )
    }
    Regex("""@t\[(\d{1,3})\s*,\s*(?:(?:type|unit)=)?([^]]+)]""").matchEntire(key.lowercase())?.let { match ->
        val team = Team.all.getOrNull(match.groupValues[1].toIntOrNull() ?: return null) ?: return null
        val type = findUnitType(match.groupValues[2]) ?: return null
        return UnitSelection(
            Groups.unit.toList().filter { it.isValid && !it.dead && it.team == team && it.type == type },
            "队伍 ${team.id}(${team.name}) 的 ${unitTypeLabel(type)} 单位"
        )
    }
    return null
}

fun resolveUnitSelection(input: String, executor: Player?, allowFuzzyPlayer: Boolean = true): UnitSelection? {
    val key = input.trim()
    if (key.isBlank()) return null

    val selectorMatch = Regex("""@(e|a|s)(?:\[([^]]*)])?""").matchEntire(key.lowercase())
    if (selectorMatch != null) {
        val selector = selectorMatch.groupValues[1]
        val filters = parseFilters(selectorMatch.groupValues.getOrNull(2).orEmpty()) ?: return null
        val baseUnits = when (selector) {
            "e" -> Groups.unit.toList().filter { it.isValid && !it.dead }
            "a" -> Groups.player.toList().mapNotNull { safePlayerUnit(it) }.distinctBy { it.id }
            "s" -> listOfNotNull(executor?.let { safePlayerUnit(it) })
            else -> return null
        }
        val baseLabel = when (selector) {
            "e" -> "所有单位"
            "a" -> "所有玩家附身单位"
            "s" -> "自己"
            else -> "选择器"
        }
        return UnitSelection(
            applyFilters(baseUnits, filters, executor),
            selectorLabel(baseLabel, filters),
            if (selector == "s") executor else null,
        )
    }

    resolveLegacySelector(key)?.let { return it }

    val target = findOnlinePlayer(key, allowFuzzyPlayer) ?: return null
    return UnitSelection(listOfNotNull(safePlayerUnit(target)), target.name, target)
}


