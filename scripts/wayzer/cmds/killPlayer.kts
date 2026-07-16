@file:Depends("wayzer/user/shortID", "三位ID")
@file:Depends("wayzer/cmds/unitSelector", "管理单位选择器")

package wayzer.cmds

name = "MDT管理员击杀玩家单位"

command("kill", "管理指令：击杀指定玩家/选择器单位") {
    usage = "<玩家UUID/三位UID/#游戏ID/名字|@e[team=2,unit=mono]|@a|@s>"
    permission = "wayzer.admin.kill"
    aliases = listOf("killplayer", "killunit", "击杀")
    body {
        val targetInput = arg.getOrNull(0)?.trim().orEmpty()
        if (targetInput.isBlank()) {
            returnReply("[red]用法：/kill <玩家UUID/三位UID/#游戏ID/名字|选择器>\n[gray]选择器：${unitSelectorHelpText()}\n[gray]示例：/kill @e[team=2,unit=mono]、/kill @e[unit=mono]".with())
        }
        val selection = resolveUnitSelection(targetInput, player)
            ?: returnReply("[red]找不到目标：[white]$targetInput[red]。\n[gray]选择器：${unitSelectorHelpText()}".with())
        val units = selection.units.filter { it.isValid && !it.dead }
        if (units.isEmpty()) {
            returnReply("[yellow]目标 [white]${selection.label}[yellow] 当前没有可击杀的有效单位。".with())
        }
        units.forEach { it.kill() }
        reply("[green]已击杀 [white]${selection.label}[green]，共 [gold]${units.size}[green] 个单位。".with())
        selection.directPlayer?.sendMessage("[scarlet]你的当前单位已被管理员击杀。")
        logger.info("${player?.plainName() ?: "Console"} killed ${units.size} unit(s) by selector '$targetInput' (${selection.label})")
    }
}

PermissionApi.registerDefault("wayzer.admin.kill", group = "@admin")
