@file:Depends("wayzer/map/performanceGuard", "统一性能优化系统")
@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.map

import mindustry.gen.Player
import wayzer.VoteEvent
import wayzer.user.TrustLevel

name = "旧实验性性能优化兼容入口"

private val perfGuard = contextScript<PerformanceGuard>()
private val trustLevel = contextScript<TrustLevel>()

private fun canManage(operator: Player?): Boolean =
    operator == null || with(trustLevel) { hasTrustLevel(operator, "3+") }

private suspend fun startCompatVote(starter: Player, enable: Boolean): Boolean {
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = (if (enable) "开启统一性能优化" else "关闭本局性能优化").with(),
        extDesc = "[gray]旧实验性入口已合并到统一性能优化系统，不再运行第二套清理或换图逻辑。",
        supportSingle = true,
    )
    if (!event.awaitResult()) return false
    with(perfGuard) {
        if (enable) enableConservative(starter.name) else disableForCurrentRound(starter.name)
    }
    return true
}

onEnable {
    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "xperf", "[cyan]统一性能优化旧入口[gray]（需50%同意）") {
        aliases = listOf("实验性性能优化", "强力性能优化")
        usage = "<on|off|status>"
        permission = "wayzer.vote.xperf"
        body {
            when (arg.getOrNull(0)?.lowercase()) {
                "on", "enable", "开启" -> startCompatVote(player!!, true)
                "off", "disable", "关闭" -> startCompatVote(player!!, false)
                "status", "状态" -> player!!.sendMessage(with(perfGuard) { conservativeStatusText() })
                else -> replyUsage()
            }
        }
    }
}

command("xperf", "统一性能优化旧兼容入口") {
    usage = "[status|on|off]"
    aliases = listOf("实验性性能优化", "强力性能优化")
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态" -> reply(with(perfGuard) { conservativeStatusText() }.with())
            "on", "enable", "开启" -> {
                if (!canManage(player)) returnReply("[red]权限不足：只有3+、4级/admin或控制台可以直接开启。".with())
                with(perfGuard) { enableConservative(player?.name ?: "控制台") }
            }
            "off", "disable", "关闭" -> {
                if (!canManage(player)) returnReply("[red]权限不足：只有3+、4级/admin或控制台可以直接关闭。".with())
                with(perfGuard) { disablePerformanceGuard(player?.name ?: "控制台") }
            }
            "stage", "fallback", "换图" -> reply("[yellow]该危险旧功能已移除；清理、暂停和最终换图仅由统一性能优化系统按安全阈值执行。".with())
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.xperf")
