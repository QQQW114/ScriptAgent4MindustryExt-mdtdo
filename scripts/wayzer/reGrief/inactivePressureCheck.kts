@file:Depends("coreMindustry/menu", "菜单系统")
@file:Depends("wayzer/map/serverPressure", "服务器压力判断")
@file:Depends("wayzer/map/adaptivePlayerLimit", "自适应人数上限")

package wayzer.reGrief

import coreMindustry.MenuV2
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.map.AdaptivePlayerLimit
import wayzer.map.ServerPressure
import java.time.Duration

name = "上行超限挂机检测"

private val pressure = contextScript<ServerPressure>()
private val adaptiveLimit = contextScript<AdaptivePlayerLimit>()

private val checkIntervalMillis by config.key(5_000L, "挂机检测轮询间隔(ms)")
private val promptCooldownMillis by config.key(30 * 60 * 1000L, "上行超限挂机提示间隔(ms)")
private val responseTimeoutMillis by config.key(90 * 1000L, "挂机检测响应超时(ms)")
private val stalePressureMillis by config.key(30_000L, "压力快照过期后停止挂机检测(ms)")

private data class PendingCheck(
    val playerName: String,
    val deadlineMillis: Long,
)

private val pending = mutableMapOf<String, PendingCheck>()
private var lastPromptMillis = 0L

private fun playerActive(player: Player, reason: String = "互动") {
    if (pending.remove(player.uuid()) != null) {
        runCatching {
            player.sendMessage("[green][挂机检测] 已收到你的响应：$reason")
        }.onFailure {
            logger.warning("发送挂机检测响应确认失败(${player.name})：${it.message}")
        }
    }
}

private fun sendCheck(player: Player, deadline: Long) {
    val uuid = runCatching { player.uuid() }.getOrNull() ?: return
    pending[uuid] = PendingCheck(player.name, deadline)
    runCatching {
        player.sendMessage("[yellow][挂机检测] 当前服务器上行压力过高，请在 ${responseTimeoutMillis / 1000} 秒内点击弹窗按钮、聊天或点击地图证明仍在游玩。")
    }.onFailure {
        logger.warning("发送挂机检测提示失败(${player.name})：${it.message}")
    }
    launch(Dispatchers.game) {
        try {
            MenuV2(player) {
                msg = """
                    |[yellow]服务器上行压力过高，正在清理挂机玩家。
                    |
                    |请点击下方按钮证明你还在游玩。
                    |也可以通过聊天或点击地图解除本次检测。
                """.trimMargin()
                option("[green]我还在") { playerActive(player, "按钮确认") }
            }.send().await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 菜单发送/玩家断线只影响本次提示，不能取消挂机检测主循环。
            logger.warning("挂机检测菜单发送失败(${player.name})：${e.message}")
        }
    }
}

private fun startPressureCheck(reason: String, requiredPlayers: Int) {
    val now = System.currentTimeMillis()
    if (now - lastPromptMillis < promptCooldownMillis.coerceAtLeast(60_000L)) return

    val deadline = now + responseTimeoutMillis.coerceAtLeast(15_000L)
    val players = Groups.player.toList()
    if (players.size < requiredPlayers.coerceAtLeast(1)) return
    // 只有真正发出本轮检测后才开始冷却；空服/人数不足或广播失败时不消耗冷却窗口。
    if (runCatching {
            broadcast("[yellow][上行优化] $reason，已发送挂机确认；无响应玩家将被移出服务器。".with())
        }.isFailure
    ) {
        logger.warning("发送挂机检测全服提示失败：$reason")
        return
    }
    lastPromptMillis = now
    players.forEach { player ->
        runCatching { sendCheck(player, deadline) }.onFailure {
            logger.warning("创建挂机检测失败(${player.name})：${it.message}")
        }
    }
}

private fun expireChecks() {
    val now = System.currentTimeMillis()
    val expired = pending.filterValues { it.deadlineMillis <= now }.keys.toList()
    expired.forEach { uuid ->
        pending.remove(uuid)
        val player = Groups.player.find { it.uuid() == uuid } ?: return@forEach
        val kicked = runCatching {
            player.kick("[yellow]上行压力挂机检测无响应，请稍后重新加入。")
        }.onFailure {
            logger.warning("移出无响应玩家失败(${player.name})：${it.message}")
        }.isSuccess
        if (kicked) runCatching {
            broadcast("[yellow][挂机检测] 已移出无响应玩家：[white]${player.name}".with())
        }.onFailure {
            logger.warning("播报挂机检测移出结果失败(${player.name})：${it.message}")
        }
    }
}

private fun tickInactiveCheck() {
    val s = with(pressure) { currentPressure() }
    val playerCount = Groups.player.size()
    val requiredPlayers = with(adaptiveLimit) { initialPlayerLimit() } ?: run {
        // 自适应人数脚本尚未完成启动接管时 fail-safe，不使用过小的固定人数误触发挂机检测。
        pending.clear()
        return
    }
    val pressureFresh = System.currentTimeMillis() - s.updatedAtMillis <= stalePressureMillis.coerceAtLeast(5_000L)
    // 挂机检测跟随“同步限制”本身启用，并要求在线玩家达到自适应人数上限的初始/基础值才介入；
    // 同步限制自带滞回、检测又有长冷却，短暂压力波动不会反复弹窗踢人。
    // 压力采样过期时 fail-safe，不依据旧快照继续踢人。
    if (!pressureFresh || s.throttleLevel <= 0 || playerCount < requiredPlayers) {
        pending.clear()
        return
    }
    // 同步限制自身已经有最短保持时间和恢复滞回；不再额外等待连续样本，
    // 满足“限制等级 + 人数”后本轮即可启用。
    startPressureCheck("上行超限，同步限制等级 ${s.throttleLevel}", requiredPlayers)
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
            try {
                tickInactiveCheck()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 挂机检测不能因一次菜单/玩家连接异常而永久停止。
                logger.warning("挂机检测轮询异常，下一轮将继续：${e.message}")
            }
        }
    }
}
