@file:Depends("wayzer/map/serverPressure", "服务器压力判断")

package wayzer.map

import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player

name = "自适应人数上限"

private val pressure = contextScript<ServerPressure>()

// ScriptAgent 会先加载脚本，再解析启动参数中的原版 `playerlimit 18`。
// 因此不能在顶层立刻读取 playerLimit；需要延迟接管，避免覆盖启动参数。
private var startupPlayerLimit = 0
private var limitControlInitialized = false
private val fallbackBaseLimit = 18

var adaptiveLimitEnabled by config.key(true, "是否启用自适应人数上限")
var baseLimitOverride by config.key(0, "基础人数上限；0表示使用服务端启动时playerLimit")
var maxDynamicLimit by config.key(32, "自适应人数最高上限")
var adjustStep by config.key(2, "每轮人数上限增减步长")
var adjustIntervalMillis by config.key(60_000L, "人数上限调整周期(ms)")
var expandHeadroom by config.key(2, "距离当前动态上限多少人以内即可尝试扩容")
var pressurePollMillis by config.key(5_000L, "上行压力采样检查周期(ms)")
var startupInitDelayMillis by config.key(10_000L, "启动后延迟接管原生playerLimit(ms)")
var trafficQuietMillis by config.key(5 * 60_000L, "允许扩容前需要连续无上行压力的时间(ms)")
var expandTrafficMbps by config.key(16.0, "允许扩容的平均上行阈值(Mbps)")
var kickFullMessage by config.key("服务器已满，请稍后再试。", "玩家超过动态上限时的踢出提示")

private var dynamicPlayerLimit = 0
private var lastTrafficPressureAt = System.currentTimeMillis()
private var lastAdjustAt = System.currentTimeMillis()
private val pendingPlayers = mutableSetOf<String>()

private fun currentNativePlayerLimit(): Int =
    Vars.netServer.admins.playerLimit.takeIf { it > 0 } ?: fallbackBaseLimit

private fun startupBaseLimit(): Int {
    if (startupPlayerLimit <= 0) startupPlayerLimit = currentNativePlayerLimit()
    return startupPlayerLimit
}

private fun configuredBaseLimit(): Int {
    val configured = if (baseLimitOverride > 0) baseLimitOverride else startupBaseLimit()
    return configured.takeIf { it > 0 } ?: fallbackBaseLimit
}

private fun effectiveMaxLimit(): Int =
    maxDynamicLimit.coerceAtLeast(configuredBaseLimit()).coerceAtLeast(1)

private fun effectiveStep(): Int =
    adjustStep.coerceAtLeast(1)

private fun ensureDynamicLimit(): Int {
    val base = configuredBaseLimit().coerceAtLeast(1)
    val maxLimit = effectiveMaxLimit()
    if (dynamicPlayerLimit <= 0) dynamicPlayerLimit = base
    dynamicPlayerLimit = dynamicPlayerLimit.coerceIn(base, maxLimit)
    return dynamicPlayerLimit
}

private fun pendingCount(): Int = pendingPlayers.size

private fun nativeTargetLimit(): Int {
    // 不再保留管理插队槽：服务器列表显示的原生上限就是当前动态上限。
    // Mindustry 原生 admin 即使绕过原版 playerLimit 进入 PlayerConnect，也会被下方逻辑按同一动态上限检查。
    return ensureDynamicLimit()
}

private fun refreshNativePlayerLimit(): Int {
    if (!limitControlInitialized) return Vars.netServer.admins.playerLimit
    val target = if (adaptiveLimitEnabled) nativeTargetLimit() else startupBaseLimit()
    if (Vars.netServer.admins.playerLimit != target) {
        Vars.netServer.admins.playerLimit = target
    }
    return target
}

private fun restoreNativePlayerLimit() {
    val restore = startupPlayerLimit
    if (restore <= 0) return
    if (Vars.netServer.admins.playerLimit != restore) {
        Vars.netServer.admins.playerLimit = restore
    }
}

private fun initializeLimitControl(now: Long = System.currentTimeMillis()) {
    if (limitControlInitialized) return
    startupBaseLimit()
    dynamicPlayerLimit = configuredBaseLimit().coerceIn(1, effectiveMaxLimit())
    // 启用后先观察一个完整安静窗口，避免刚热加载时没有5分钟历史却立刻扩容。
    lastTrafficPressureAt = now
    lastAdjustAt = now
    limitControlInitialized = true
    refreshNativePlayerLimit()
    logger.info("[自适应人数] 已接管原生 playerLimit=$startupPlayerLimit，动态上限=$dynamicPlayerLimit")
}

private fun isTrafficPressure(trafficLevel: Int, throttleLevel: Int, level: Int, reason: String): Boolean =
    trafficLevel > 0 || throttleLevel > 0 || (level > 0 && reason.contains("上行"))

private fun observeTrafficPressure(
    trafficLevel: Int,
    throttleLevel: Int,
    level: Int,
    reason: String,
    now: Long = System.currentTimeMillis()
) {
    if (isTrafficPressure(trafficLevel, throttleLevel, level, reason)) lastTrafficPressureAt = now
}

private fun trafficQuietFor(now: Long = System.currentTimeMillis()): Long =
    (now - lastTrafficPressureAt).coerceAtLeast(0L)

private fun trafficQuietEnough(now: Long = System.currentTimeMillis()): Boolean =
    trafficQuietFor(now) >= trafficQuietMillis.coerceAtLeast(0L)

private fun canExpandByTraffic(averageTrafficMbps: Double, now: Long = System.currentTimeMillis()): Boolean =
    trafficQuietEnough(now) && averageTrafficMbps < expandTrafficMbps

private fun adjustDynamicLimit(now: Long = System.currentTimeMillis()) {
    if (!adaptiveLimitEnabled) {
        restoreNativePlayerLimit()
        return
    }

    val s = with(pressure) { currentPressure() }
    observeTrafficPressure(s.trafficLevel, s.throttleLevel, s.level, s.reason, now)

    val before = ensureDynamicLimit()
    val current = Groups.player.size() + pendingCount()
    val base = configuredBaseLimit().coerceAtLeast(1)
    val maxLimit = effectiveMaxLimit()
    val step = effectiveStep()

    dynamicPlayerLimit = when {
        current >= before - expandHeadroom.coerceAtLeast(0) && before < maxLimit && canExpandByTraffic(s.averageTrafficMbps, now) ->
            (before + step).coerceAtMost(maxLimit)

        // 扩容从“快满”开始触发后，回收也要留出同样的缓冲，避免 18 上限、16 人扩到 20 后
        // 下一轮因 16 < 20 被立刻回收，导致人数上限在 18/20 间抖动。
        current < before - expandHeadroom.coerceAtLeast(0) - step ->
            (before - step).coerceAtLeast(base)

        else -> before
    }.coerceIn(base, maxLimit)

    val native = refreshNativePlayerLimit()
    lastAdjustAt = now

    if (dynamicPlayerLimit != before) {
        val direction = if (dynamicPlayerLimit > before) "扩容" else "回收"
        logger.info(
            "[自适应人数] $direction: $before -> $dynamicPlayerLimit, " +
                    "online=$current, native=$native, avgTraffic=${formatMbps(s.averageTrafficMbps)}Mbps"
        )
    }
}

private fun tickLimitControl() {
    if (!limitControlInitialized) return
    val now = System.currentTimeMillis()
    if (!adaptiveLimitEnabled) {
        restoreNativePlayerLimit()
        return
    }
    val s = with(pressure) { currentPressure() }
    observeTrafficPressure(s.trafficLevel, s.throttleLevel, s.level, s.reason, now)
    if (now - lastAdjustAt >= adjustIntervalMillis.coerceAtLeast(5_000L)) {
        adjustDynamicLimit(now)
    } else {
        refreshNativePlayerLimit()
    }
}

private fun playerPendingKey(player: Player): String = "${player.uuid()}:${player.usid()}"

private fun removePendingLater(key: String) {
    launch(Dispatchers.game) {
        delay(20_000L)
        if (pendingPlayers.remove(key)) refreshNativePlayerLimit()
    }
}

private fun formatMillis(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes > 0) "${minutes}分${rest}秒" else "${rest}秒"
}

private fun formatMbps(value: Double): String = "%.2f".format(value)

private fun statusText(): String {
    if (!limitControlInitialized) {
        return """
            |[cyan]自适应人数上限：[yellow]等待接管
            |[cyan]当前原生上限：[white]${Vars.netServer.admins.playerLimit}
            |[gray]脚本会在启动后延迟 ${formatMillis(startupInitDelayMillis.coerceAtLeast(0L))} 接管，避免覆盖启动参数中的原版 playerlimit。
        """.trimMargin()
    }
    val now = System.currentTimeMillis()
    val s = with(pressure) { currentPressure() }
    val base = configuredBaseLimit()
    val dynamic = ensureDynamicLimit()
    val native = Vars.netServer.admins.playerLimit
    val current = Groups.player.size()
    val pending = pendingCount()
    val quietFor = trafficQuietFor(now)
    val quietNeed = trafficQuietMillis.coerceAtLeast(0L)
    val quietLeft = (quietNeed - quietFor).coerceAtLeast(0L)
    val nextAdjust = (adjustIntervalMillis.coerceAtLeast(5_000L) - (now - lastAdjustAt)).coerceAtLeast(0L)
    return """
        |[cyan]自适应人数上限：${if (adaptiveLimitEnabled) "[green]启用" else "[yellow]关闭"}
        |[cyan]当前人数：[white]$current[]（待确认 $pending） / 动态上限 [white]$dynamic[] / 原生上限 [white]$native
        |[cyan]基础/最高/步长/快满提前量：[white]$base / ${effectiveMaxLimit()} / ${effectiveStep()} / ${expandHeadroom.coerceAtLeast(0)}
        |[cyan]平均上行：[white]${formatMbps(s.averageTrafficMbps)} Mbps[]，扩容阈值 [white]${formatMbps(expandTrafficMbps)} Mbps
        |[cyan]上行压力：[white]等级 ${s.trafficLevel}[] / 同步限制 ${s.throttleLevel} / 连续安静 [white]${formatMillis(quietFor)}[]，还需 [white]${formatMillis(quietLeft)}
        |[cyan]下次调整：[white]${formatMillis(nextAdjust)}
        |[gray]说明：服务器列表显示的原生上限会同步为动态上限；超过动态上限的玩家会被踢出，原生管理员也不再插队。
    """.trimMargin()
}

listen<EventType.PlayerConnect> { event ->
    if (!limitControlInitialized) return@listen
    if (!adaptiveLimitEnabled) return@listen
    val target = event.player
    val key = playerPendingKey(target)
    val projected = Groups.player.size() + pendingCount() + 1
    val limit = ensureDynamicLimit()
    if (projected > limit) {
        refreshNativePlayerLimit()
        target.kick("$kickFullMessage 当前动态上限：${limit}人。", 0L)
        return@listen
    }

    pendingPlayers += key
    refreshNativePlayerLimit()
    removePendingLater(key)
}

listen<EventType.PlayerJoin> {
    pendingPlayers.remove(playerPendingKey(it.player))
    refreshNativePlayerLimit()
}

listen<EventType.PlayerLeave> {
    pendingPlayers.remove(playerPendingKey(it.player))
    refreshNativePlayerLimit()
}

onEnable {
    launch(Dispatchers.game) {
        delay(startupInitDelayMillis.coerceAtLeast(0L))
        initializeLimitControl()
        while (true) {
            delay(pressurePollMillis.coerceAtLeast(1_000L))
            tickLimitControl()
        }
    }
}

onDisable {
    pendingPlayers.clear()
    restoreNativePlayerLimit()
}

command("adaptiveplayerlimit", "管理指令：自适应人数上限") {
    usage = "[status|on|off|reset|base <人数|reset>|max <人数>|threshold <Mbps>|step <人数>|headroom <人数>|quiet <分钟>]"
    aliases = listOf("人数上限", "自适应人数", "apl")
    permission = "wayzer.map.adaptivePlayerLimit"
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态" -> reply(statusText().with())

            "on", "enable", "开启", "启用" -> {
                adaptiveLimitEnabled = true
                initializeLimitControl()
                dynamicPlayerLimit = configuredBaseLimit().coerceIn(1, effectiveMaxLimit())
                val now = System.currentTimeMillis()
                lastTrafficPressureAt = now
                lastAdjustAt = now
                refreshNativePlayerLimit()
                reply(("[green]已启用自适应人数上限。\n" + statusText()).with())
            }

            "off", "disable", "关闭", "禁用" -> {
                adaptiveLimitEnabled = false
                pendingPlayers.clear()
                restoreNativePlayerLimit()
                val restore = startupPlayerLimit.takeIf { it > 0 } ?: Vars.netServer.admins.playerLimit
                reply("[green]已关闭自适应人数上限，并恢复服务端启动时 playerLimit=$restore。".with())
            }

            "reset", "重置" -> {
                initializeLimitControl()
                dynamicPlayerLimit = configuredBaseLimit().coerceIn(1, effectiveMaxLimit())
                lastAdjustAt = System.currentTimeMillis()
                refreshNativePlayerLimit()
                reply(("[green]已将动态上限重置为基础上限。\n" + statusText()).with())
            }

            "base", "基础" -> {
                val value = arg.getOrNull(1)
                    ?: returnReply("[red]请输入基础人数，例如：/adaptiveplayerlimit base 18；或 /adaptiveplayerlimit base reset 使用启动配置。".with())
                if (value.equals("reset", ignoreCase = true) || value == "默认") {
                    baseLimitOverride = 0
                } else {
                    baseLimitOverride = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: returnReply("[red]基础人数必须是正整数。".with())
                }
                initializeLimitControl()
                dynamicPlayerLimit = ensureDynamicLimit()
                refreshNativePlayerLimit()
                reply(("[green]已设置基础人数上限。\n" + statusText()).with())
            }

            "max", "最高", "最大" -> {
                val value = arg.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                    ?: returnReply("[red]请输入最高人数，例如：/adaptiveplayerlimit max 32".with())
                maxDynamicLimit = value
                initializeLimitControl()
                dynamicPlayerLimit = ensureDynamicLimit()
                refreshNativePlayerLimit()
                reply(("[green]已设置自适应最高人数。\n" + statusText()).with())
            }

            "threshold", "traffic", "上行", "阈值" -> {
                val value = arg.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }
                    ?: returnReply("[red]请输入上行阈值Mbps，例如：/adaptiveplayerlimit threshold 16".with())
                expandTrafficMbps = value
                reply(("[green]已设置扩容平均上行阈值为 ${formatMbps(value)} Mbps。\n" + statusText()).with())
            }

            "step", "步长" -> {
                val value = arg.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                    ?: returnReply("[red]请输入每轮增减人数，例如：/adaptiveplayerlimit step 2".with())
                adjustStep = value
                initializeLimitControl()
                dynamicPlayerLimit = ensureDynamicLimit()
                refreshNativePlayerLimit()
                reply(("[green]已设置人数上限调整步长。\n" + statusText()).with())
            }

            "headroom", "nearfull", "快满", "提前量" -> {
                val value = arg.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }
                    ?: returnReply("[red]请输入快满提前量，例如：/adaptiveplayerlimit headroom 2 表示上限18时16人即可尝试扩容。".with())
                expandHeadroom = value
                initializeLimitControl()
                dynamicPlayerLimit = ensureDynamicLimit()
                refreshNativePlayerLimit()
                reply(("[green]已设置快满扩容提前量。\n" + statusText()).with())
            }

            "quiet", "安静", "冷静" -> {
                val minutes = arg.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0L }
                    ?: returnReply("[red]请输入分钟数，例如：/adaptiveplayerlimit quiet 5".with())
                trafficQuietMillis = minutes * 60_000L
                reply(("[green]已设置扩容前无上行压力时间为 ${minutes} 分钟。\n" + statusText()).with())
            }

            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.map.adaptivePlayerLimit", group = "@admin")
