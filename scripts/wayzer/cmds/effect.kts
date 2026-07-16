@file:Depends("wayzer/cmds/unitSelector", "管理单位选择器")

package wayzer.cmds

import arc.util.pooling.Pools
import arc.struct.Seq
import mindustry.content.StatusEffects
import mindustry.ctype.ContentType
import mindustry.entities.units.StatusEntry
import mindustry.gen.Unit
import mindustry.type.StatusEffect

name = "MDT管理员单位状态效果指令"

val effectDefaultDurationSeconds by config.key(120, "effect指令默认持续秒数")
val effectMaxDurationSeconds by config.key(3600, "effect指令最大持续秒数")
val effectMaxStacks by config.key(20, "effect指令单次最大叠加层数")
val effectMaxTargets by config.key(2000, "effect指令单次最大影响单位数")

private fun findStatusEffect(input: String): StatusEffect? {
    val key = input.trim()
    if (key.isBlank()) return null
    return content.getByName<StatusEffect>(ContentType.status, key)
        ?: content.statusEffects().firstOrNull {
            it.name.equals(key, ignoreCase = true) || it.localizedName.equals(key, ignoreCase = true)
        }
}

private fun suggestStatusEffects(input: String, limit: Int = 12): String {
    val key = input.trim().lowercase()
    if (key.isBlank()) return ""
    return content.statusEffects()
        .filter {
            it.name.lowercase().contains(key) ||
                    it.localizedName.lowercase().contains(key)
        }
        .take(limit)
        .joinToString(" ") { "[yellow]${it.name}[gray](${it.localizedName})" }
}

private fun statusName(effect: StatusEffect): String =
    "[yellow]${effect.name}[gray](${effect.localizedName})"

private val statusFieldCache = mutableMapOf<Class<*>, java.lang.reflect.Field?>()

@Suppress("UNCHECKED_CAST")
private fun statusEntries(unit: Unit): Seq<StatusEntry> {
    val field = statusFieldCache.getOrPut(unit.javaClass) {
        generateSequence(unit.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> runCatching { clazz.getDeclaredField("statuses") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
    } ?: error("当前单位类型 ${unit.javaClass.name} 没有 statuses 字段，无法直接叠加状态")
    return field.get(unit) as Seq<StatusEntry>
}

private fun applyStackedStatus(unit: Unit, effect: StatusEffect, duration: Float, stacks: Int): Int {
    if (effect == StatusEffects.none || effect == StatusEffects.dynamic || unit.isImmune(effect)) return 0
    var applied = 0
    repeat(stacks.coerceAtLeast(1)) {
        val entry = Pools.obtain(StatusEntry::class.java, ::StatusEntry)
        entry.damageTime = 0f
        entry.set(effect, duration)
        statusEntries(unit).add(entry)
        effect.applied(unit, duration, false)
        applied++
    }
    return applied
}

private fun clearAllStatuses(unit: Unit): Int {
    val count = statusEntries(unit).size
    if (count > 0) unit.clearStatuses()
    return count
}

private fun clearStatus(unit: Unit, effect: StatusEffect): Int {
    var count = 0
    statusEntries(unit).forEach { entry ->
        if (entry.effect == effect) count++
    }
    // Mindustry 的 Unit.unapply(effect) 只移除第一个匹配的 StatusEntry。
    // 本服 /effect 支持叠加同类 buff，因此按现有条目数重复移除，确保指定 buff 被清干净。
    repeat(count) {
        unit.unapply(effect)
    }
    return count
}

private fun effectUsage(): String =
    "[red]用法：/effect [选择器|玩家UUID] <效果ID> [叠加数] [秒数]\n" +
            "[red]清除：/effect [选择器|玩家UUID] clear [效果ID]\n" +
            "[gray]选择器：${unitSelectorHelpText()}\n" +
            "[gray]无选择器时默认自己；示例：/effect @e[team=2,unit=mono] fast 3，/effect overdrive 2 300，/effect @a clear fast，/effect clear"

command("effect", "管理指令：给单位添加或清除状态效果") {
    usage = "[选择器|玩家UUID] <效果ID|clear> [叠加数|效果ID] [秒数]"
    permission = "wayzer.admin.effect"
    aliases = listOf("效果", "buff")
    body {
        if (arg.isEmpty()) returnReply(effectUsage().with())
        val firstArg = arg[0].trim()
        val selectorTarget = if (firstArg.startsWith("@")) {
            resolveUnitSelection(firstArg, player)
                ?: returnReply("[red]找不到选择器：[white]$firstArg[red]。\n[gray]选择器：${unitSelectorHelpText()}".with())
        } else if (arg.size >= 2) {
            // 为避免与效果名冲突，非@目标只按精确玩家UUID/三位ID/#游戏ID/名字识别，不做名字模糊匹配。
            resolveUnitSelection(firstArg, player, allowFuzzyPlayer = false)
        } else null
        val hasExplicitTarget = selectorTarget != null
        val selection = selectorTarget ?: resolveUnitSelection("@s", player)
            ?: returnReply("[red]无法获取默认目标。".with())
        val effectIndex = if (hasExplicitTarget) 1 else 0
        val effectText = arg.getOrNull(effectIndex)?.trim().orEmpty()
        if (effectText.isBlank()) returnReply(effectUsage().with())

        val units = selection.units.filter { it.isValid && !it.dead }
        if (units.isEmpty()) returnReply("[yellow]目标 [white]${selection.label}[yellow] 当前没有可操作状态效果的有效单位。".with())
        if (units.size > effectMaxTargets) {
            returnReply("[red]目标单位过多：[white]${units.size}[red] 个，当前单次上限为 [white]$effectMaxTargets[red] 个。".with())
        }

        if (effectText.equals("clear", ignoreCase = true) || effectText == "清除" || effectText == "清理") {
            val clearEffectText = arg.getOrNull(effectIndex + 1)?.trim().orEmpty()
            val effect = if (clearEffectText.isBlank()) null else findStatusEffect(clearEffectText) ?: run {
                val suggestions = suggestStatusEffects(clearEffectText)
                val suffix = if (suggestions.isBlank()) "" else "\n[gray]相近效果：$suggestions"
                returnReply("[red]未找到状态效果：[white]$clearEffectText$suffix".with())
            }

            var affectedUnits = 0
            var clearedEntries = 0
            units.forEach { unit ->
                val cleared = if (effect == null) clearAllStatuses(unit) else clearStatus(unit, effect)
                if (cleared > 0) {
                    affectedUnits++
                    clearedEntries += cleared
                }
            }
            val targetText = effect?.let { statusName(it) } ?: "[yellow]全部状态效果"
            reply(
                "[green]已清除 [white]${selection.label}[green] 的 $targetText[green]：目标 [gold]$affectedUnits[green]/${units.size} 个单位，清理 [gold]$clearedEntries[green] 个状态条目。".with()
            )
            logger.info("${player?.plainName() ?: "Console"} effect-clear selector='${if (hasExplicitTarget) firstArg else "@s"}' effect=${effect?.name ?: "*"} entries=$clearedEntries units=$affectedUnits/${units.size}")
            return@body
        }

        val effect = findStatusEffect(effectText) ?: run {
            val suggestions = suggestStatusEffects(effectText)
            val suffix = if (suggestions.isBlank()) "" else "\n[gray]相近效果：$suggestions"
            returnReply("[red]未找到状态效果：[white]$effectText$suffix".with())
        }
        if (effect == StatusEffects.none || effect == StatusEffects.dynamic) {
            returnReply("[red]该状态效果不能直接添加：[white]${effect.name}".with())
        }

        val stacksText = arg.getOrNull(effectIndex + 1)
        val stacks = stacksText?.toIntOrNull() ?: 1
        if (stacks <= 0) returnReply("[red]叠加数必须为正整数。".with())
        if (stacks > effectMaxStacks) returnReply("[red]叠加数过大：[white]$stacks[red]，当前上限为 [white]$effectMaxStacks[red]。".with())

        val durationText = arg.getOrNull(effectIndex + 2)
        val durationSeconds = durationText?.toFloatOrNull() ?: effectDefaultDurationSeconds.toFloat()
        if (durationSeconds <= 0f) returnReply("[red]持续秒数必须为正数。".with())
        if (durationSeconds > effectMaxDurationSeconds) {
            returnReply("[red]持续时间过长：[white]${durationSeconds}[red] 秒，当前上限为 [white]$effectMaxDurationSeconds[red] 秒。".with())
        }

        var affectedUnits = 0
        var appliedStacks = 0
        val durationTicks = durationSeconds * 60f
        units.forEach { unit ->
            val applied = applyStackedStatus(unit, effect, durationTicks, stacks)
            if (applied > 0) {
                affectedUnits++
                appliedStacks += applied
            }
        }
        reply(
            "[green]已为 [white]${selection.label}[green] 添加 ${statusName(effect)}[green]：目标 [gold]$affectedUnits[green]/${units.size} 个单位，叠加 [gold]$appliedStacks[green] 层，持续 [white]${durationSeconds}[green] 秒。".with()
        )
        logger.info("${player?.plainName() ?: "Console"} effect selector='${if (hasExplicitTarget) firstArg else "@s"}' effect=${effect.name} stacks=$stacks duration=${durationSeconds}s units=$affectedUnits/${units.size}")
    }
}

PermissionApi.registerDefault("wayzer.admin.effect", group = "@admin")
