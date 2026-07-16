@file:Depends("wayzer/maps", "地图加载事件")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import mindustry.Vars
import mindustry.content.Blocks
import mindustry.ctype.ContentType
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.world.Block
import wayzer.MapLoadFinishedEvent
import wayzer.lib.MdtStorage

name = "逻辑绘图开关"

private val SETTING_KEY = "map.logicDraw.enabled"
private val defaultLogicDrawEnabled by config.key(true, "默认是否允许逻辑绘图/画布/逻辑显示器")

private val drawBlocks: List<Block>
    get() = listOfNotNull(
        Blocks.canvas,
        Blocks.largeCanvas,
        Blocks.logicDisplay,
        Blocks.largeLogicDisplay,
        Blocks.tileLogicDisplay,
    )

private var currentMapBaseBannedBlocks: Set<Block> = emptySet()
private var roundLogicDrawOverride: Boolean? = null
private val roundManualBannedBlocks = linkedSetOf<Block>()
private val roundManualAllowedBlocks = linkedSetOf<Block>()

private fun configuredLogicDrawEnabled(): Boolean =
    MdtStorage.getSetting(SETTING_KEY)?.toBooleanStrictOrNull() ?: defaultLogicDrawEnabled

private fun logicDrawEnabled(): Boolean = roundLogicDrawOverride ?: configuredLogicDrawEnabled()

private fun setLogicDrawEnabled(enabled: Boolean) {
    roundLogicDrawOverride = null
    MdtStorage.setSetting(SETTING_KEY, enabled.toString())
    applyLogicDrawRule(syncClients = true)
}

private fun setRoundLogicDrawEnabled(enabled: Boolean?) {
    roundLogicDrawOverride = enabled
    applyLogicDrawRule(syncClients = true)
}

private fun drawBlockNames(): String =
    drawBlocks.joinToString(", ") { it.name }

private fun currentBannedDrawBlocks(): List<Block> =
    drawBlocks.filter { Vars.state.rules.bannedBlocks.contains(it) }

private fun setBannedBlocks(target: Set<Block>) {
    Vars.state.rules.bannedBlocks.clear()
    target.forEach { Vars.state.rules.bannedBlocks.add(it) }
}

private fun applyLogicDrawRule(syncClients: Boolean = false) {
    val draw = drawBlocks.toSet()
    val current = Vars.state.rules.bannedBlocks.toSet()
    val manualDrawBans = roundManualBannedBlocks intersect draw
    val manualDrawAllows = roundManualAllowedBlocks intersect draw
    val target = if (logicDrawEnabled()) {
        // 只撤销本脚本额外加入的绘图方块封禁；如果地图自身原本封禁了这些方块，继续尊重地图规则。
        (current - draw) + ((currentMapBaseBannedBlocks intersect draw) - manualDrawAllows) + manualDrawBans
    } else {
        (current + draw + manualDrawBans) - manualDrawAllows
    }
    setBannedBlocks(target)
    if (syncClients) Call.setRules(Vars.state.rules)
}

private fun statusText(): String {
    val enabled = logicDrawEnabled()
    val configured = configuredLogicDrawEnabled()
    val overrideText = when (roundLogicDrawOverride) {
        null -> "[gray]无"
        true -> "[green]本局强制允许"
        false -> "[yellow]本局强制禁止"
    }
    val banned = currentBannedDrawBlocks().joinToString(", ") { it.name }.ifBlank { "无" }
    return """
        |[cyan]逻辑绘图/显示器方块开关
        |[white]当前策略：[yellow]${if (enabled) "允许" else "禁止"} [gray](全局默认：${if (configured) "允许" else "禁止"}；本局覆盖：$overrideText[gray])
        |[white]受控方块：[gray]${drawBlockNames()}
        |[white]当前已禁用的受控方块：[yellow]$banned
        |[white]本局手动禁用方块：[yellow]${roundManualBannedBlocks.joinToString(", ") { it.name }.ifBlank { "无" }}
        |[white]本局手动解禁方块：[yellow]${roundManualAllowedBlocks.joinToString(", ") { it.name }.ifBlank { "无" }}
        |[gray]用于处理 NSFW/辱骂等不适当显示器、画布、排序器/逻辑绘图内容。
    """.trimMargin()
}

private fun clearRoundState() {
    roundLogicDrawOverride = null
    roundManualBannedBlocks.clear()
    roundManualAllowedBlocks.clear()
}

private fun findBlock(input: String): Block? {
    val key = input.trim()
    if (key.isBlank()) return null
    return Vars.content.getByName<Block>(ContentType.block, key)
        ?: Vars.content.blocks().firstOrNull {
            it.name.equals(key, ignoreCase = true) ||
                    it.localizedName.equals(key, ignoreCase = true) ||
                    it.name.substringAfterLast('-').equals(key, ignoreCase = true)
        }
}

private fun suggestBlocks(input: String, limit: Int = 10): String {
    val key = input.trim().lowercase()
    if (key.isBlank()) return ""
    return Vars.content.blocks()
        .filter { !it.isFloor }
        .filter { it.name.lowercase().contains(key) || it.localizedName.lowercase().contains(key) }
        .take(limit)
        .joinToString(", ") { it.name }
}

private fun blockName(block: Block): String = "[yellow]${block.name}[gray](${block.localizedName})"

private fun setSingleBlockBanned(block: Block, banned: Boolean, operator: String) {
    if (banned) {
        roundManualAllowedBlocks.remove(block)
        roundManualBannedBlocks.add(block)
        Vars.state.rules.bannedBlocks.add(block)
    } else {
        roundManualBannedBlocks.remove(block)
        roundManualAllowedBlocks.add(block)
        Vars.state.rules.bannedBlocks.remove(block)
    }
    applyLogicDrawRule(syncClients = true)
    val action = if (banned) "禁用" else "解禁"
    broadcast("[yellow]管理员 [white]$operator[yellow] 已在本局{action}方块：{block}".with("action" to action, "block" to blockName(block)))
}

private fun bannedBlockList(limit: Int = 80): String {
    val list = Vars.state.rules.bannedBlocks.toList().sortedBy { it.name }
    if (list.isEmpty()) return "[green]当前没有被禁用的方块"
    val shown = list.take(limit).joinToString(", ") { it.name }
    val suffix = if (list.size > limit) "[gray] ... 另有 ${list.size - limit} 个" else ""
    return "[cyan]当前 bannedBlocks 共 [white]${list.size}[cyan] 个：\n[gray]$shown$suffix"
}

listenTo<MapLoadFinishedEvent> {
    clearRoundState()
    currentMapBaseBannedBlocks = Vars.state.rules.bannedBlocks.toSet()
    applyLogicDrawRule(syncClients = false)
}

listen<EventType.ResetEvent> {
    clearRoundState()
    currentMapBaseBannedBlocks = Vars.state.rules.bannedBlocks.toSet()
    applyLogicDrawRule(syncClients = false)
}

onEnable {
    currentMapBaseBannedBlocks = Vars.state.rules.bannedBlocks.toSet()
    applyLogicDrawRule(syncClients = true)
}

command("logicdraw", "管理指令：开关逻辑绘图/显示器/画布") {
    usage = "[status|on|off|roundoff|roundon|roundclear]"
    aliases = listOf("逻辑绘图", "drawblocks", "displayart")
    requirePermission("wayzer.admin.logicDraw")
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态" -> reply(statusText().with())
            "on", "enable", "allow", "开启", "允许" -> {
                setLogicDrawEnabled(true)
                broadcast("[green]管理员已允许逻辑绘图/画布/逻辑显示器。".with())
                reply(statusText().with())
            }
            "off", "disable", "deny", "关闭", "禁止" -> {
                setLogicDrawEnabled(false)
                broadcast("[yellow]管理员已禁止逻辑绘图/画布/逻辑显示器，相关方块已加入当前规则 bannedBlocks。".with())
                reply(statusText().with())
            }
            "roundoff", "thisoff", "gameoff", "本局禁止", "本局关闭", "本局禁用" -> {
                setRoundLogicDrawEnabled(false)
                broadcast("[yellow]管理员已在本局禁止逻辑绘图/画布/逻辑显示器；换图后自动恢复全局策略。".with())
                reply(statusText().with())
            }
            "roundon", "thison", "gameon", "本局允许", "本局开启" -> {
                setRoundLogicDrawEnabled(true)
                broadcast("[green]管理员已在本局允许逻辑绘图/画布/逻辑显示器；换图后自动恢复全局策略。".with())
                reply(statusText().with())
            }
            "roundclear", "clear", "本局恢复", "清除本局" -> {
                setRoundLogicDrawEnabled(null)
                broadcast("[green]管理员已清除本局逻辑绘图覆盖，恢复全局策略。".with())
                reply(statusText().with())
            }
            else -> replyUsage()
        }
    }
}

command("blockban", "管理指令：本局单独禁用/解禁方块") {
    usage = "[ban|unban|status|list] [方块ID]"
    aliases = listOf("banblock", "blockrule", "方块禁用", "方块解禁")
    requirePermission("wayzer.admin.blockBan")
    body {
        if (arg.isEmpty()) returnReply(bannedBlockList().with())
        val opRaw = arg.getOrNull(0)?.lowercase()
        val knownOps = setOf(
            "ban", "disable", "deny", "off", "禁用", "禁止",
            "unban", "enable", "allow", "on", "解禁", "允许", "解除",
            "status", "状态", "list", "列表"
        )
        val firstIsOp = opRaw != null && opRaw in knownOps
        val op = when (opRaw) {
            "ban", "disable", "deny", "off", "禁用", "禁止" -> "ban"
            "unban", "enable", "allow", "on", "解禁", "允许", "解除" -> "unban"
            "status", "状态" -> "status"
            "list", "列表" -> "list"
            else -> "ban"
        }
        if (op == "list") returnReply(bannedBlockList().with())

        val blockId = arg.getOrNull(if (firstIsOp) 1 else 0)?.trim().orEmpty()
        if (blockId.isBlank()) returnReply("[red]用法：/blockban ban <方块ID> 或 /blockban unban <方块ID>；/blockban list 查看当前禁用。".with())

        val block = findBlock(blockId) ?: run {
            val suggestions = suggestBlocks(blockId)
            val suffix = if (suggestions.isNotBlank()) "\n[gray]可能是：$suggestions" else ""
            returnReply("[red]未找到方块：[white]$blockId$suffix".with())
        }
        if (block == Blocks.air) returnReply("[red]air 不能加入 bannedBlocks。".with())
        if (block.isFloor) returnReply("[red]bannedBlocks 只用于建筑方块，地板/地形不能通过此指令禁用。".with())

        when (op) {
            "status" -> {
                val banned = Vars.state.rules.bannedBlocks.contains(block)
                reply("[cyan]方块 ${blockName(block)} [cyan]当前状态：[white]${if (banned) "已禁用" else "未禁用"}".with())
            }
            "ban" -> {
                if (Vars.state.rules.bannedBlocks.contains(block)) returnReply("[yellow]该方块当前已经处于禁用状态：{block}".with("block" to blockName(block)))
                setSingleBlockBanned(block, true, player?.plainName() ?: "控制台")
                reply("[green]已在本局禁用方块：{block}".with("block" to blockName(block)))
            }
            "unban" -> {
                if (!Vars.state.rules.bannedBlocks.contains(block)) returnReply("[yellow]该方块当前未被禁用：{block}".with("block" to blockName(block)))
                setSingleBlockBanned(block, false, player?.plainName() ?: "控制台")
                reply("[green]已在本局解禁方块：{block}".with("block" to blockName(block)))
            }
        }
    }
}

command("blockunban", "管理指令：本局单独解禁方块") {
    usage = "<方块ID>"
    aliases = listOf("unbanblock", "解除方块禁用")
    requirePermission("wayzer.admin.blockBan")
    body {
        val blockId = arg.getOrNull(0)?.trim().orEmpty()
        if (blockId.isBlank()) returnReply("[red]用法：/blockunban <方块ID>".with())
        val block = findBlock(blockId) ?: run {
            val suggestions = suggestBlocks(blockId)
            val suffix = if (suggestions.isNotBlank()) "\n[gray]可能是：$suggestions" else ""
            returnReply("[red]未找到方块：[white]$blockId$suffix".with())
        }
        if (block == Blocks.air) returnReply("[red]air 不能加入或移出 bannedBlocks。".with())
        if (!Vars.state.rules.bannedBlocks.contains(block)) returnReply("[yellow]该方块当前未被禁用：{block}".with("block" to blockName(block)))
        setSingleBlockBanned(block, false, player?.plainName() ?: "控制台")
        reply("[green]已在本局解禁方块：{block}".with("block" to blockName(block)))
    }
}

PermissionApi.registerDefault("wayzer.admin.logicDraw", group = "@admin")
PermissionApi.registerDefault("wayzer.admin.blockBan", group = "@admin")
