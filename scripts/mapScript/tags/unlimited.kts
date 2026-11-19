package mapScript.tags

import mindustry.Vars

// 地图无限资源标签。
// 在地图标签中使用 [@Unlimited] 后，本局启用无限资源；换图后自动恢复为加载前状态。
registerMapTag("@Unlimited")

name = "地图无限资源"

private var previousInfiniteResources: Boolean? = null

modeIntroduce(
    "无限资源模式",
    "[cyan]本地图带有 [white][@Unlimited][cyan] 标签：本局启用无限资源；换图后自动恢复。"
)

onEnable {
    previousInfiniteResources = Vars.state.rules.infiniteResources
    Vars.state.rules.infiniteResources = true
}

onDisable {
    previousInfiniteResources?.let { Vars.state.rules.infiniteResources = it }
    previousInfiniteResources = null
}
