package coreMindustry.lib

import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.ui.Menus
import mindustry.ui.builder.MenuBuilder
import mindustry.ui.builder.MenuResult
import mindustry.ui.builder.UiBuilder
import mindustry.ui.builder.UiBuilder.TableBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 自定义菜单会话层（实验功能）——基于 Mindustry 160.1 新增的服务端下发菜单系统。
 *
 * 上游能力（160.1，mindustry.ui.builder）：
 * - 服务端用 UiBuilder 组装 UI 组件树，经
 *   Call.menuBuilder(con, menuId, token, title, hideOnClick, hideExisting, fillScreen, ui) 下发给单个玩家；
 * - 客户端回传 Call.menuBuilderChoose(player, menuId, MenuResult)，服务端侧
 *   Menus.menuBuilderListeners.get(menuId).get(player, result) 派发，同时 fire MenuBuilderOptionChooseEvent。
 *
 * 为什么整个会话层放在 lib 目录的 kt 文件而不是脚本：ScriptAgent 里脚本（kts）之间不能直接 import 彼此的顶层函数，
 * 只有 coreMindustry/lib 下的 kt 文件配合 .metadata 的 +IMPORT DefaultImport coreMindustry.lib.* 才对全模块可见。
 * 因此这里承载全部逻辑，业务脚本直接 import 本文件的函数即可。
 *
 * 关键约束（实测踩点）：menuId 是客户端 menuBuilderListeners 数组的下标，不是业务编号：
 * - 每个菜单实例都要注册一个新的监听器并拿到递增下标，不能复用 id；
 * - Menus.registerMenuBuilder 只增不删，故采用监听器池 + 全局递增 id，池按需增长、下标不回收。
 *
 * 兼容性：这是 160.1 才有的网络能力，旧客户端无法渲染。调用方用 customMenuSupported 判定并捕获
 * sendCustomMenu 异常，回退到既有聊天菜单（回退策略由调用方决定）。
 */

/** 一个菜单会话的句柄。 */
class CustomMenuHandle internal constructor(
    internal val player: Player,
    internal val menuId: Int,
    internal val token: Long,
    internal val onSelect: (String, MenuResult) -> Unit,
    internal val onClose: (() -> Unit)?,
    private val activeFlag: AtomicBoolean,
) {
    fun isActive(): Boolean = activeFlag.get()

    internal fun deactivate() {
        activeFlag.set(false)
    }
}

private data class CustomMenuRegistration(val handle: CustomMenuHandle, val token: Long)

private val menuRegistrations = ConcurrentHashMap<Int, CustomMenuRegistration>()
private val nextMenuId = AtomicInteger(0)
private val nextToken = AtomicLong(1L)
private val listenersInstalledUpTo = AtomicInteger(-1)

/**
 * 客户端是否可能支持自定义菜单。
 * 不猜具体版本：只排除明显不可用的情形（无连接）；真正的兜底是 sendCustomMenu 的异常 + 调用方回退。
 */
fun customMenuSupported(player: Player): Boolean = player.con != null

/** 确保客户端存在下标为 menuId 的监听器（服务端注册；客户端按 id 建立自己的监听器）。 */
private fun ensureListenerInstalled(menuId: Int) {
    while (true) {
        val current = listenersInstalledUpTo.get()
        if (current >= menuId) return
        if (!listenersInstalledUpTo.compareAndSet(current, current + 1)) continue
        val index = current + 1
        Menus.registerMenuBuilder { player, result -> dispatchCustomMenu(index, player, result) }
    }
}

private fun dispatchCustomMenu(menuId: Int, player: Player, result: MenuResult) {
    val reg = menuRegistrations[menuId] ?: return
    val handle = reg.handle
    if (handle.player !== player) return
    if (!handle.isActive()) return
    if (result.token != 0L && result.token != reg.token) return
    val clicked = result.result
    if (clicked == null) handle.onClose?.invoke() else handle.onSelect(clicked, result)
}

/**
 * 渲染并下发一个自定义菜单。
 *
 * @param onSelect 点击回调：(结果id, 完整结果)；result.values 里带输入框/滑条/开关的值。
 * @param onClose 玩家直接关闭菜单时的回调（可空）。
 * @return 会话句柄。异常由调用方捕获并回退旧菜单。
 */
fun sendCustomMenu(
    player: Player,
    title: String?,
    hideOnClick: Boolean = true,
    onSelect: (String, MenuResult) -> Unit,
    onClose: (() -> Unit)? = null,
    content: TableBuilder.() -> Unit,
): CustomMenuHandle {
    pruneStale()
    val menuId = nextMenuId.getAndIncrement()
    val token = nextToken.getAndIncrement()
    val active = AtomicBoolean(true)
    val handle = CustomMenuHandle(player, menuId, token, onSelect, onClose, active)
    menuRegistrations[menuId] = CustomMenuRegistration(handle, token)
    ensureListenerInstalled(menuId)

    // UiBuilder.table() 是 Java 静态方法，不能写成 table { }。
    val root = UiBuilder.table()
    root.content()

    MenuBuilder.of(root)
        .id(menuId)
        .token(token)
        .title(title)
        .hideOnClick(hideOnClick)
        .hideExisting(true)
        .fillScreen(true)
        .show(player)
    return handle
}

/** 关闭并注销会话。 */
fun closeCustomMenu(handle: CustomMenuHandle) {
    handle.deactivate()
    menuRegistrations.remove(handle.menuId)
    runCatching { Call.hideMenuBuilder(handle.player.con, handle.menuId) }
}

/** 清理已失效/已离线玩家的会话（在下发新菜单时顺带执行，等价于 PlayerLeave 清理）。 */
private fun pruneStale() {
    menuRegistrations.entries.removeIf { (_, reg) ->
        !reg.handle.isActive() || !reg.handle.player.isAdded
    }
}