@file:Depends("wayzer/map/serverPressure", "服务器压力判断")

package wayzer.reGrief

import mindustry.game.EventType
import wayzer.map.ServerPressure
import java.lang.reflect.Field

name = "v159全局快照频率保护"

private val pressure = contextScript<ServerPressure>()
private val enabled by config.key(true, "启v159全局快照频率保护")
private val maxIntervalMillis by config.key(320L, "v159快照保护最大间隔(ms)")

private data class NativeSnapshotInterval(
    val field: Field,
    val config: Any,
    val num: java.lang.reflect.Method,
    val set: java.lang.reflect.Method,
)

private val nativeInterval: NativeSnapshotInterval? = runCatching {
    val configClass = Class.forName("mindustry.net.Administration\$Config")
    val field = configClass.getField("snapshotInterval")
    val config = field.get(null)
    NativeSnapshotInterval(
        field,
        config,
        config.javaClass.getMethod("num"),
        config.javaClass.getMethod("set", Any::class.java),
    )
}.getOrNull()

private var originalInterval = 200
private var lastAppliedInterval = -1
private var warnedUnsupported = false

private fun currentNativeInterval(): Int {
    val holder = nativeInterval ?: return originalInterval
    return runCatching { (holder.num.invoke(holder.config) as Number).toInt() }
        .getOrDefault(originalInterval)
}

private fun setNativeInterval(value: Int): Boolean {
    val target = value.coerceAtLeast(20)
    val holder = nativeInterval ?: return false
    return runCatching {
        holder.set.invoke(holder.config, target)
        lastAppliedInterval = target
        true
    }.getOrDefault(false)
}

private fun desiredInterval(): Int {
    if (!enabled) return originalInterval
    val requested = with(pressure) { throttleIntervalMillis() }
    if (requested <= 0L) return originalInterval
    // 旧脚本的等级1曾使用 160ms，会比v159默认200ms更频繁；保证绝不降低原生频率。
    val maxInterval = maxIntervalMillis.coerceAtLeast(originalInterval.toLong()).toInt()
    return maxOf(originalInterval, requested.toInt()).coerceAtMost(maxInterval)
}

listen(EventType.Trigger.update) {
    if (nativeInterval == null) {
        if (!warnedUnsupported) {
            warnedUnsupported = true
            logger.warning("v159原生 snapshotInterval 不可反射，快照频率保护已降级为仅监控。")
        }
        return@listen
    }
    val target = desiredInterval()
    if (target != lastAppliedInterval) {
        if (setNativeInterval(target)) {
            if (target != originalInterval) {
                logger.info("v159全局快照间隔已调整为 ${target}ms（原生 ${originalInterval}ms）")
            } else if (lastAppliedInterval >= 0) {
                logger.info("v159全局快照间隔已恢复为 ${originalInterval}ms")
            }
        }
    }
}

onEnable {
    nativeInterval?.let {
        originalInterval = currentNativeInterval().coerceAtLeast(20)
        lastAppliedInterval = originalInterval
        logger.info("v159快照保护已启用：原生 snapshotInterval=${originalInterval}ms；仅保守调整原生间隔，不拦截或重发任何快照包")
    }
}

onDisable {
    if (nativeInterval != null) setNativeInterval(originalInterval)
}
