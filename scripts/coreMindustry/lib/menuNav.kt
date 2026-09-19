package coreMindustry.lib

import mindustry.gen.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨菜单的"返回上一页"轻量导航（2026-09-13 新增）。
 *
 * 背景：帮助菜单（`coreMindustry/menu.kts`）等入口通过 `RootCommands.handleInput` 打开其它系统的菜单
 * （`/posts`、`/wiki`、`/achievements`…），这些系统自己的"返回/关闭"过去只会关掉自己，回不到入口那一页。
 *
 * 用法：
 * - 入口菜单在跳转前：`MenuNav.push(player, "返回帮助菜单") { 重开入口页 }`
 * - 目标菜单渲染时：`MenuNav.peek(player)` 非空才显示"返回"按钮；点击时用 `MenuNav.take(player)` 取出并执行
 *
 * 约束：每玩家只保留最近一条（后进覆盖，单层返回够用）；带 TTL，过期视为不存在；
 * `push`/`peek` 时顺带清理过期项，所以不需要注册 `PlayerLeave` 监听器，内存占用最多 O(在线玩家)。
 */
object MenuNav {
    /** 返回目标：显示用 [label] + 重新打开上一页的 [action] */
    class Target(val label: String, val expireAt: Long, val action: suspend () -> Unit)

    /** 超过这个时间没被消费就作废，避免"很久以后点返回跳回旧菜单" */
    private const val TTL_MILLIS = 3 * 60_000L

    /** 触发清理的阈值，防止极端情况下长时间不清理 */
    private const val PURGE_THRESHOLD = 64

    private val targets = ConcurrentHashMap<String, Target>()

    fun push(player: Player, label: String, action: suspend () -> Unit) {
        val now = System.currentTimeMillis()
        if (targets.size > PURGE_THRESHOLD) purge(now)
        targets[player.uuid()] = Target(label, now + TTL_MILLIS, action)
    }

    fun peek(player: Player): Target? {
        val target = targets[player.uuid()] ?: return null
        if (target.expireAt < System.currentTimeMillis()) {
            targets.remove(player.uuid())
            return null
        }
        return target
    }

    fun take(player: Player): Target? {
        val target = peek(player) ?: return null
        targets.remove(player.uuid())
        return target
    }

    fun clear(player: Player) {
        targets.remove(player.uuid())
    }

    private fun purge(now: Long) {
        targets.entries.removeAll { it.value.expireAt < now }
    }
}
