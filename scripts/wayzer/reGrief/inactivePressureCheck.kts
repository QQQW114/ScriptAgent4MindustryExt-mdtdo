@file:Depends("coreMindustry/menu", "菜单系统")
@file:Depends("wayzer/map/serverPressure", "服务器压力判断")

package wayzer.reGrief

import coreMindustry.MenuV2
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.map.ServerPressure
import java.time.Duration

name = "上行超限挂机检测"

private val pressure = contextScript<ServerPressure>()

private val checkIntervalMillis by config.key(5_000L, "挂机检测轮询间隔(ms)")
private val promptCooldownMillis by config.key(15 * 60 * 1000L, "上行超限挂机提示间隔(ms)")
private val responseTimeoutMillis by config.key(90 * 1000L, "挂机检测响应超时(ms)")
private val pressureSamplesRequired by config.key(3, "触发挂机检测前需要的连续上行超限样本数")

private data class PendingCheck(
    val playerName: String,
    val deadlineMillis: Long,
)

private val pending = mutableMapOf<String, PendingCheck>()
private var lastPromptMillis = 0L
private var pressureSamples = 0

private fun playerActive(player: Player, reason: String = "互动") {
    if (pending.remove(player.uuid()) != null) {
        player.sendMessage("[green][挂机检测] 已收到你的响应：$reason")
    }
}

private fun sendCheck(player: Player, deadline: Long) {
    pending[player.uuid()] = PendingCheck(player.name, deadline)
    player.sendMessage("[yellow][挂机检测] 当前服务器上行压力过高，请在 ${responseTimeoutMillis / 1000} 秒内点击弹窗按钮、聊天或点击地图证明仍在游玩。")
    launch(Dispatchers.game) {
        MenuV2(player) {
            msg = """
                |[yellow]服务器上行压力过高，正在清理挂机玩家。
                |
                |请点击下方按钮证明你还在游玩。
                |也可以通过聊天或点击地图解除本次检测。
            """.trimMargin()
            option("[green]我还在") { playerActive(player, "按钮确认") }
        }.send().await()
    }
}

private fun startPressureCheck(reason: String) {
    val now = System.currentTimeMillis()
    if (now - lastPromptMillis < promptCooldownMillis.coerceAtLeast(60_000L)) return
    lastPromptMillis = now

    val deadline = now + responseTimeoutMillis.coerceAtLeast(15_000L)
    val players = Groups.player.toList()
    if (players.isEmpty()) return
    broadcast("[yellow][上行优化] $reason，已发送挂机确认；无响应玩家将被移出服务器。".with())
    players.forEach { sendCheck(it, deadline) }
}

private fun expireChecks() {
    val now = System.currentTimeMillis()
    val expired = pending.filterValues { it.deadlineMillis <= now }.keys.toList()
    expired.forEach { uuid ->
        pending.remove(uuid)
        val player = Groups.player.find { it.uuid() == uuid } ?: return@forEach
        player.kick("[yellow]上行压力挂机检测无响应，请稍后重新加入。")
        broadcast("[yellow][挂机检测] 已移出无响应玩家：[white]${player.name}".with())
    }
}

private fun tickInactiveCheck() {
    val s = with(pressure) { currentPressure() }
    // 仅在游戏同步上行持续超限时检查挂机；新玩家世界流/音乐/CP流不能误触发踢人。
    if (s.trafficLevel <= 0) {
        pressureSamples = 0
        pending.clear()
        return
    }
    pressureSamples = (pressureSamples + 1).coerceAtMost(pressureSamplesRequired.coerceAtLeast(1))
    if (pressureSamples < pressureSamplesRequired.coerceAtLeast(1)) return
    startPressureCheck("上行超限，同步限制等级 ${s.throttleLevel}")
    expireChecks()
}

listen<EventType.PlayerChatEvent> { playerActive(it.player, "聊天") }
listen<EventType.TapEvent> { playerActive(it.player, "点击地图") }
listen<EventType.PlayerLeave> { pending.remove(it.player.uuid()) }
listen<EventType.WorldLoadEvent> { pending.clear() }
listen<EventType.ResetEvent> { pending.clear() }

onEnable {
    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(checkIntervalMillis.coerceAtLeast(1000L)).toMillis())
            tickInactiveCheck()
        }
    }
}
