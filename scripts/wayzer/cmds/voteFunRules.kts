@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/map/funRuleModes", "临时玩法规则工具")
@file:Depends("wayzer/user/trustLevel", "信任等级")

package wayzer.cmds

import coreMindustry.lib.broadcast
import wayzer.VoteService

name = "投票临时玩法规则"

private val funRules = contextScript<wayzer.map.FunRuleModes>()
private val trustLevel = contextScript<wayzer.user.TrustLevel>()

fun VoteService.registerFunRuleVotes() {
    addSubVote("清空当前所有单位", "", "killunits", "killallunits", "击杀单位") {
        VoteService.start(
            player!!,
            "击杀所有单位".with(),
            extDesc = "[yellow]该投票会清理当前地图全部单位，请确认不是正常玩法需要的单位。"
        ) {
            val count = with(funRules) { killAllUnits() }
            broadcast("[red]投票已通过：已击杀所有单位，共 [yellow]{count}[red] 个。".with("count" to count))
        }
    }

    addSubVote("标准无限火力（120秒）", "", "infinitefire", "standardfire", "firepower", "无限火力", "标准无限火力") {
        VoteService.start(
            player!!,
            "开启标准无限火力".with(),
            extDesc = "[yellow]该投票只临时补足炮塔开火所需弹药/液体/供电，不开启无限资源/核心资源填充/伤害翻倍。"
        ) {
            with(funRules) { enableStandardInfiniteFire(120_000L, "投票") }
        }
    }

    addSubVote("无限火力ProMax（120秒）", "", "infinitefirepromax", "firepowerpro", "promaxfire", "无限火力promax") {
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

    addSubVote("切换反应堆爆炸", "<on|off|status>", "reactor", "reactorexplosions", "反应堆爆炸") {
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

    addSubVote("开启本局纯净模式", "[status]", "pure", "puremode", "cleanskill", "纯净模式") {
        val first = arg.firstOrNull()?.lowercase()
        if (first in setOf("status", "状态")) returnReply(with(funRules) { pureModeStatusText() }.with())
        if (first != null) returnReply("[red]用法：/vote pure [status]；纯净模式现在只对当前这局生效。".with())
        if (with(funRules) { isPureModeEnabled() }) {
            returnReply("[yellow]当前这局已经开启纯净模式。".with())
        }
        VoteService.start(
            player!!,
            "为当前局开启纯净模式".with(),
            extDesc = """
                |[cyan]玩法分类：[white]技能限制
                |[yellow]通过后立即为当前这局添加 @noSkills，并额外禁用3级技能。
                |[gray]换图/下一局不会自动延续；当前局可用 /vote pureoff 解除。
            """.trimMargin()
        ) {
            with(funRules) { enablePureMode("投票") }
        }
    }

    addSubVote("关闭本局纯净模式", "", "pureoff", "purecancel", "取消纯净模式", "解除纯净模式") {
        if (!with(funRules) { isPureModeEnabled() }) {
            returnReply("[yellow]当前这局没有开启纯净模式。".with())
        }
        VoteService.start(
            player!!,
            "关闭当前局纯净模式".with(),
            extDesc = """
                |[cyan]玩法分类：[white]技能限制
                |[yellow]通过后立即关闭当前这局纯净模式。
                |[gray]只移除纯净模式自己添加的标签，不会误删地图原本自带的 @noSkills 等限制。
            """.trimMargin()
        ) {
            with(funRules) { disablePureMode("投票") }
        }
    }
}

onEnable {
    VoteService.registerFunRuleVotes()
}
