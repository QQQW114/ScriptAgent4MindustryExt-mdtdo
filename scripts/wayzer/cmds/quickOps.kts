@file:Depends("coreMindustry/menu", "调用标准菜单弹窗")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.cmds

import coreMindustry.MenuBuilder
import coreMindustry.lib.MsgType
import coreMindustry.lib.PlayerCommandReceiver
import coreMindustry.lib.RootCommands
import coreMindustry.lib.hasPermission
import coreLibrary.lib.CommandContext
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import coreLibrary.lib.Commands.Hidden
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.with
import mindustry.gen.Player
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import wayzer.user.TrustLevel

/**
 * 3++ 协管快捷操作菜单。
 *
 * 作用：把当前已注册的**全部投票项**列成一个菜单，3++ 与 4 级玩家点一下就能直接执行，
 * 不再需要"发起投票 + 等别人同意"——等价于免投票直接通过某项投票里的功能。
 *
 * 实现口径（重要）：
 * - 列出的是 `VoteEvent.VoteCommands` 当前已注册的子指令，因此**任何脚本新增的投票项都会自动出现**，
 *   不需要在这里逐个维护；被停用脚本的投票项自动消失。
 * - 执行统一走 `RootCommands.handleInput("/vote <子指令>")`，即**与玩家自己手打完全相同的路径**，
 *   权限（`wayzer.vote.*` 的 Permission attr）照常校验，不存在越权。
 * - 菜单只列出当前玩家**可见**的项：可见性判定与 `/help` 完整列表同款——只看 `Hidden` 类 attr
 *   （`Commands.Permission` 走 `hasPermission`，其余走 `attr.visible()`），不执行命令自己的逻辑 attr。
 * - 需要参数的投票项（usage 里带 `<...>` / `[...]`）不在菜单里空参数执行，而是提示改用指令补参数，
 *   避免误操作（例如跳波、设置波次）。
 */
name = "协管快捷操作菜单"

private val trustLevel = contextScript<TrustLevel>()

/** 能打开本菜单的下限：3++（与既有"3++ 开放风控菜单"口径一致）。 */
private fun canUseQuickOps(player: Player): Boolean =
    with(trustLevel) { hasTrustLevel(player, "3++") }

private fun needsArgument(command: CommandInfo): Boolean {
    val usage = command.usage
    return usage.contains('<') || usage.contains('[')
}

/**
 * 该投票项对当前玩家是否可见（与 `/help` 的分区/搜索判定同口径）。
 * 只检查 `Hidden` 类 attr，避免误执行命令自身的业务前置条件。
 */
private suspend fun isVisibleTo(player: Player, command: CommandInfo): Boolean {
    if (command.script?.enabled == false) return false
    val context = CommandContext.Command().apply {
        receiver = PlayerCommandReceiver(player)
        reply = { player.sendMessage(it, MsgType.Message) }
        arg = emptyList()
    }
    return command.attrs.all { attr ->
        attr !is Hidden || context.run {
            when (attr) {
                is Commands.Permission -> hasPermission(attr.permission)
                else -> attr.visible()
            }
        }
    }
}

suspend fun openQuickOpsMenu(player: Player) {
    val viewerUid = PlayerData[player].id
    val candidates = VoteEvent.VoteCommands.registeredSubCommands()
        .filter { it.script?.enabled != false }
        .sortedBy { it.name.lowercase() }
    // 先把可见项算出来（挂起调用），再进菜单构建，避免在 builder 内做挂起操作。
    val visible = candidates.filter { isVisibleTo(player, it) }

    MenuBuilder<Unit>("协管快捷操作") {
        msg = """
            |[cyan]以下是当前可用的全部投票项，点击即[white]直接执行[cyan]（无需发起投票、无需他人同意）。
            |[gray]执行路径与玩家手打 /vote 子指令完全一致，权限照常校验。
            |[gray]标有 [orange][参数][gray] 的条目需要在指令里补参数，菜单内不会空参数执行。
            |[gray]当前身份：${with(trustLevel) { getTrustLevelDisplayCode(viewerUid, player) }}
        """.trimMargin()

        if (visible.isEmpty()) {
            option("（当前没有可用的投票项）") { }
        } else {
            visible.forEach { command ->
                option(buildString {
                    append(command.name)
                    val desc = command.description.toString()
                    if (desc.isNotBlank()) append("[gray] · $desc")
                    if (needsArgument(command)) append("[orange] [参数]")
                }) {
                    if (needsArgument(command)) {
                        player.sendMessage(
                            "[yellow]该投票项需要参数，请使用指令：/vote ${command.name} ${command.usage}".with()
                        )
                        return@option
                    }
                    RootCommands.handleInput("/vote ${command.name}", player)
                }
            }
        }
        newRow()
        option("查看 /vote 完整列表") { RootCommands.handleInput("/vote", player) }
    }.sendTo(player, 90_000)
}

command("quickops", "协管快捷操作：列出所有投票项并可直接执行") {
    aliases = listOf("快捷操作", "协管菜单", "qops", "opmenu")
    body {
        val operator = player
            ?: returnReply("[red]该菜单需要在游戏内由玩家打开。".with())
        if (!canUseQuickOps(operator)) {
            returnReply("[red]权限不足：需要 3++ 级及以上协管。".with())
        }
        openQuickOpsMenu(operator)
    }
}

PermissionApi.registerDefault("wayzer.admin.quickOps", group = "@admin")

