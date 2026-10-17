import cf.wayzer.scriptAgent.ScriptRegistry
import coreLibrary.lib.Commands
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.with
import coreMindustry.lib.command

/**
 * 诊断指令：脚本/指令注册状态一键查询。
 *
 * 背景（2026-10-17）：`externalCpHotReload` 的一次改动让脚本顶层初始化被静默中断 ——
 * 编译通过、脚本 [Enabled]、banner 出错0，但 `command()`/`onEnable()` 的注册全没执行，
 * 游戏内表现为"无效指令"，而 SA 不写任何日志。以后遇到"指令/菜单入口突然没了"，
 * 先用本指令确认到底是"没注册"还是"注册了但没权限"，不用再翻日志猜。
 */
name = "脚本诊断"

private fun rootCommandNames(): List<String> =
    Commands.Root.registeredSubCommands().map { it.name }.sorted()

private fun matchCommand(filter: String): List<String> =
    Commands.Root.registeredSubCommands()
        .filter { c ->
            c.name.contains(filter, true) || c.aliases.any { it.contains(filter, true) }
        }
        .map { "${it.name}${if (it.aliases.isEmpty()) "" else "(" + it.aliases.joinToString("/") + ")"}" }
        .sorted()

command("scriptdiag", "管理指令：脚本/指令注册诊断（排查'入口消失/无效指令'）") {
    aliases = listOf("脚本诊断", "sdiag")
    permission = "wayzer.admin.scriptDiag"
    usage = "[commands [过滤词]|failed|state <脚本id>|count]"
    body {
        when (arg.getOrNull(0)?.lowercase()) {
            null, "help", "帮助" -> {
                reply("[cyan]scriptdiag 用法：".with())
                reply("[white] commands [过滤] [gray]列出根指令（可按名字/别名过滤），确认目标指令是否注册".with())
                reply("[white] failed [gray]列出加载/启用失败脚本及原因（SA 的 failReason）".with())
                reply("[white] state <脚本id> [gray]查看单个脚本状态，如 wayzer/map/externalCpHotReload".with())
                reply("[white] count [gray]脚本总数/已加载/已启用/失败 统计".with())
            }

            "count", "统计" -> {
                val all = ScriptRegistry.allScripts { true }
                val loaded = all.count { it.scriptState.loaded }
                val enabled = all.count { it.scriptState.enabled }
                val failed = all.count { it.failReason != null }
                reply("[cyan]脚本统计：共${all.size} 加载${loaded} 启用${enabled} 失败${failed}".with())
                reply("[gray]根指令注册数=${rootCommandNames().size}[gray]".with())
            }

            "failed", "失败" -> {
                val failed = ScriptRegistry.allScripts { it.failReason != null }
                reply("[cyan]失败脚本：${failed.size} 个".with())
                failed.take(20).forEach { info ->
                    reply("[red]${info.id} [gray]${info.failReason}".with())
                }
                if (failed.isEmpty()) reply("[green]没有 failReason 非空的脚本（注意：顶层初始化静默中断时这里也可能是空的）".with())
            }

            "state", "状态" -> {
                val id = arg.getOrNull(1) ?: returnReply("[red]用法：scriptdiag state <脚本id>".with())
                val info = ScriptRegistry.getScriptInfo(id)
                    ?: ScriptRegistry.allScripts { true }.firstOrNull { it.id.endsWith(id, true) || it.id.contains(id, true) }
                if (info == null) returnReply("[red]未找到脚本：$id".with())
                reply("[cyan]脚本 ${info.id}".with())
                reply("[white]状态：${info.scriptState} [gray]failReason=${info.failReason ?: "无"}".with())
            }

            else -> {
                val filter = arg.getOrNull(1)
                if (arg[0].equals("commands", true) || arg[0] == "指令") {
                    val names = if (filter.isNullOrBlank()) rootCommandNames() else matchCommand(filter)
                    reply("[cyan]根指令${if (filter.isNullOrBlank()) "总数" else "匹配 '$filter'"}：${names.size}".with())
                    names.take(60).forEach { reply("[white]$it".with()) }
                    if (names.size > 60) reply("[gray]…还有 ${names.size - 60} 个".with())
                } else {
                    reply("[red]未知子命令，用 scriptdiag help 查看用法".with())
                }
            }
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.scriptDiag", group = "@admin")
