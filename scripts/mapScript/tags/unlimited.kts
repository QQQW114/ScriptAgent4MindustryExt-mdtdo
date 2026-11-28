package mapScript.tags

import mindustry.Vars
import mindustry.gen.Call

// 地图无限资源标签。
// 在地图简介/规则标签中使用 [@Unlimited] 或换图参数 @map[unlimited=true] 后，本局启用无限资源；换图后自动恢复。
registerMapTag("@Unlimited")

name = "地图无限资源"

private var previousInfiniteResources: Boolean? = null

modeIntroduce(
    "无限资源模式",
    "[cyan]本地图带有 [white][@Unlimited][cyan] 标签：本局启用无限资源；换图后自动恢复。"
)

onEnable {
    previousInfiniteResources = Vars.state.rules.infiniteResources
    if (!Vars.state.rules.infiniteResources) {
        Vars.state.rules.infiniteResources = true
        if (Vars.state.isPlaying) {
            Call.setRules(Vars.state.rules)
        }
    }
}

onDisable {
    previousInfiniteResources?.let { prev ->
        if (Vars.state.rules.infiniteResources != prev) {
            Vars.state.rules.infiniteResources = prev
            if (Vars.state.isPlaying) {
                Call.setRules(Vars.state.rules)
            }
        }
    }
    previousInfiniteResources = null
}
