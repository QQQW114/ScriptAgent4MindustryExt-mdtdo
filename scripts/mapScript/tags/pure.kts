@file:Depends("wayzer/map/funRuleModes", "纯净模式规则工具")

package mapScript.tags

import wayzer.map.FunRuleModes

/**
 * 地图纯净模式标签。
 *
 * 在地图简介中加入 [@pure] 后，本地图载入时自动禁用普通技能与3级技能；
 * 地图脚本在本局结束/换图时卸载，并通过 FunRuleModes 恢复启用前的技能标签。
 */
registerMapTag("@pure")

name = "地图纯净模式"

private val funRules = contextScript<FunRuleModes>()
private var pureModeApplied = false

modeIntroduce(
    "纯净模式",
    "[cyan]本地图带有 [white][@pure][cyan] 标签：普通技能与3级技能均已禁用；换到无该标签的地图后自动恢复。"
)

onEnable {
    // 只在本脚本确实开启了纯净模式时记录所有权；
    // 若运行中模式已由投票/管理员开启，手动卸载标签脚本不应关闭他人的状态。
    pureModeApplied = with(funRules) { enablePureMode("地图标签 [@pure]") }
}

onDisable {
    if (pureModeApplied) {
        with(funRules) { disablePureMode("地图标签 [@pure] 结束") }
    }
    pureModeApplied = false
}
