package wayzer.reGrief

import mindustry.gen.Fire

val limit by config.key(500, "火焰格数限制")

/**
 * 火焰格数：160 起原版移除了 `Groups.fire` 实体分组（火焰改为按 tile 存储），
 * 这里改为遍历 `Groups.all` 并按类型统计——与 160 之前的分组语义等价，且不依赖版本专属分组字段。
 * 仅在火焰规则开启时按 tick 调用，规模为全部实体，属于本项目可接受的量级。
 */
val fireCount get() = Groups.all.count { it is Fire }
var done = false
listen<EventType.ResetEvent> { done = false }
listen(EventType.Trigger.update) {
    if (state.rules.fire && fireCount > limit) {
        done = true
        state.rules.fire = false
        broadcast("[yellow]火焰过多造成服务器卡顿,自动关闭火焰".with())
    }
}

listen<EventType.PlayerJoin> {
    if (done) {
        it.player.sendMessage("[yellow]火焰过多造成服务器卡顿,自动关闭火焰".with())
    }
}