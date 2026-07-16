@file:Depends("wayzer/map/funRuleModes", "临时玩法规则工具")

package wayzer.map

name = "手动加载地图脚本"

private val funRules = contextScript<wayzer.map.FunRuleModes>()

command("loadmapscript", "管理指令：手动加载某个地图脚本") {
    usage = "<mapId|mapScript路径>"
    aliases = listOf("loadMapScript", "加载地图脚本")
    permission = "wayzer.map.loadMapScript"
    body {
        if (arg.isEmpty()) replyUsage()
        val raw = arg[0]
        val result = with(funRules) { setMapScriptEnabled(raw, true) }
        if (result.success) {
            reply("[green]${result.message}：[yellow]${result.scriptId}[]".with())
            broadcast("[yellow][地图脚本][white]{operator}[yellow] 手动加载了 [cyan]{script}[]".with(
                "operator" to (player?.plainName() ?: "控制台"),
                "script" to result.scriptId
            ), quite = true)
        } else {
            reply("[red]加载地图脚本失败：[yellow]${result.scriptId}[red]，${result.message}".with())
        }
    }
}

command("unloadmapscript", "管理指令：手动关闭某个地图脚本/地图模式") {
    usage = "<mapId|mapScript路径>"
    aliases = listOf("disablemapscript", "closemapscript", "unloadMapScript", "关闭地图脚本", "关闭地图模式")
    permission = "wayzer.map.loadMapScript"
    body {
        if (arg.isEmpty()) replyUsage()
        val raw = arg[0]
        val result = with(funRules) { setMapScriptEnabled(raw, false) }
        if (result.success) {
            reply("[green]${result.message}：[yellow]${result.scriptId}[]".with())
            broadcast("[yellow][地图脚本][white]{operator}[yellow] 手动关闭了 [cyan]{script}[]".with(
                "operator" to (player?.plainName() ?: "控制台"),
                "script" to result.scriptId
            ), quite = true)
        } else {
            reply("[red]关闭地图脚本失败：[yellow]${result.scriptId}[red]，${result.message}".with())
        }
    }
}

command("mapscripts", "管理指令：列出当前启用的地图脚本") {
    aliases = listOf("mapScripts", "listmapscripts", "地图脚本列表", "当前地图脚本")
    permission = "wayzer.map.loadMapScript"
    body {
        reply(with(funRules) { currentMapScriptStatusText(activeOnly = true) }.with())
    }
}

PermissionApi.registerDefault("wayzer.map.loadMapScript", group = "@admin")
