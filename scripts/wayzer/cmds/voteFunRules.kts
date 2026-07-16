@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/map/funRuleModes", "临时玩法规则工具")
@file:Depends("wayzer/user/trustLevel", "信任等级")

package wayzer.cmds

import coreMindustry.lib.broadcast
import wayzer.VoteService

name = "投票临时玩法规则"

private val funRules = contextScript<wayzer.map.FunRuleModes>()
private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private var pureModeRoundsLeft = 0
private var pureModeAddedNoSkillThisRound = false
private var pureModeAddedLevel3BlockThisRound = false

private fun pureModeStatusText(): String =
    "[cyan]纯净模式：[white]剩余排队局数 [gold]$pureModeRoundsLeft[white]，当前局添加 @noSkills：[gold]${if (pureModeAddedNoSkillThisRound) "是" else "否"}[white]，禁用3级技能：[gold]${if (pureModeAddedLevel3BlockThisRound) "是" else "否"}"

private fun applyQueuedPureModeForNewRound() {
    pureModeAddedNoSkillThisRound = false
    pureModeAddedLevel3BlockThisRound = false
    if (pureModeRoundsLeft <= 0) return
    val changed = with(funRules) { addNoSkillsTag() }
    val level3Changed = with(funRules) { addPureModeLevel3BlockTag() }
    pureModeAddedNoSkillThisRound = changed
    pureModeAddedLevel3BlockThisRound = level3Changed
    pureModeRoundsLeft--
    broadcast("[cyan]纯净模式已生效：当前局已添加 @noSkills 并禁用3级技能，剩余排队局数 [gold]$pureModeRoundsLeft[cyan]。".with())
}

listen<EventType.WorldLoadEvent> {
    applyQueuedPureModeForNewRound()
}

fun VoteService.registerFunRuleVotes() {
    addSubVote("击杀当前所有单位", "", "killunits", "killallunits", "击杀单位") {
        VoteService.start(
            player!!,
            "击杀所有单位".with(),
            extDesc = "[yellow]该投票会清理当前地图全部单位，请确认不是正常玩法需要的单位。"
        ) {
            val count = with(funRules) { killAllUnits() }
            broadcast("[red]投票已通过：已击杀所有单位，共 [yellow]{count}[red] 个。".with("count" to count))
        }
    }

    addSubVote("开启120秒标准无限火力", "", "infinitefire", "standardfire", "firepower", "无限火力", "标准无限火力") {
        VoteService.start(
            player!!,
            "开启标准无限火力".with(),
            extDesc = "[yellow]该投票只临时补足炮塔开火所需弹药/液体/供电，不开启无限资源/核心资源填充/伤害翻倍。"
        ) {
            with(funRules) { enableStandardInfiniteFire(120_000L, "投票") }
        }
    }

    addSubVote("开启120秒无限火力promax", "", "infinitefirepromax", "firepowerpro", "promaxfire", "无限火力promax") {
        if (!with(trustLevel) { hasTrustLevel(player!!, "2") }) {
            returnReply("[red]无限火力promax投票需要2级信任等级及以上。普通无限火力可使用 [gold]/vote infinitefire[]。".with())
        }
        VoteService.start(
            player!!,
            "开启无限火力promax".with(),
            extDesc = "[yellow]该投票会让当前地图临时进入120秒无限火力promax：补足建筑输入、开启无限资源并提高伤害。"
        ) {
            with(funRules) { enableInfiniteFire(120_000L, "投票") }
        }
    }

    addSubVote("开启/关闭反应堆爆炸", "<on|off|status>", "reactor", "reactorexplosions", "反应堆爆炸") {
        val mode = arg.firstOrNull()?.lowercase()
        val enabled = when (mode) {
            "on", "true", "1", "enable", "enabled", "开启", "开" -> true
            "off", "false", "0", "disable", "disabled", "关闭", "关" -> false
            "status", "状态", null -> {
                val current = with(funRules) { reactorExplosionsEnabled() }
                returnReply("[yellow]当前反应堆爆炸：[white]{state}".with("state" to if (current) "开启" else "关闭"))
            }
            else -> returnReply("[red]用法：/vote reactor <on|off|status>".with())
        }
        val current = with(funRules) { reactorExplosionsEnabled() }
        if (current == enabled) {
            returnReply("[yellow]当前反应堆爆炸已经是：[white]{state}".with("state" to if (enabled) "开启" else "关闭"))
        }
        VoteService.start(
            player!!,
            "{action}反应堆爆炸".with("action" to if (enabled) "开启" else "关闭"),
            extDesc = "[yellow]该投票会热同步当前地图规则，影响钍反应堆/冲击反应堆等方块被摧毁时是否产生爆炸。"
        ) {
            with(funRules) { setReactorExplosions(enabled, "投票") }
        }
    }

    addSubVote("安排接下来若干局纯净模式", "<局数1-10|status>", "pure", "puremode", "cleanskill", "纯净模式") {
        val first = arg.firstOrNull()?.lowercase()
        if (first in setOf("status", "状态")) returnReply(pureModeStatusText().with())
        val rounds = (first?.toIntOrNull() ?: 1).coerceIn(1, 10)
        VoteService.start(
            player!!,
            "开启纯净模式(${rounds}局)".with(),
            extDesc = """
                |[cyan]玩法分类：[white]技能限制
                |[yellow]通过后从下一局开始，连续 [white]$rounds[yellow] 局自动添加 @noSkills 标签，并额外禁用3级技能。
                |[gray]最高可排队10局；如需取消可发起 /vote pureoff。
            """.trimMargin()
        ) {
            pureModeRoundsLeft = rounds
            pureModeAddedNoSkillThisRound = false
            pureModeAddedLevel3BlockThisRound = false
            broadcast("[cyan]投票已通过：接下来 [gold]$rounds[cyan] 局将自动进入纯净模式（添加 @noSkills，并禁用3级技能）。".with())
        }
    }

    addSubVote("解除/取消纯净模式", "", "pureoff", "purecancel", "取消纯净模式", "解除纯净模式") {
        VoteService.start(
            player!!,
            "解除纯净模式".with(),
            extDesc = """
                |[cyan]玩法分类：[white]技能限制
                |[yellow]通过后会清空后续纯净模式排队局数。
                |[gray]如果当前局的 @noSkills/3级禁用标签是纯净模式自动添加的，也会同步移除。
            """.trimMargin()
        ) {
            pureModeRoundsLeft = 0
            val removed = if (pureModeAddedNoSkillThisRound) with(funRules) { removeNoSkillsTag() } else false
            val removedLevel3 = if (pureModeAddedLevel3BlockThisRound) with(funRules) { removePureModeLevel3BlockTag() } else false
            pureModeAddedNoSkillThisRound = false
            pureModeAddedLevel3BlockThisRound = false
            broadcast(
                (if (removed || removedLevel3) "[green]投票已通过：已取消后续纯净模式，并解除当前局纯净模式技能限制。"
                else "[green]投票已通过：已取消后续纯净模式。").with()
            )
        }
    }
}

onEnable {
    VoteService.registerFunRuleVotes()
}
