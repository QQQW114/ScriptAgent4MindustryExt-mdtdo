@file:Suppress("DSL_MARKER_APPLIED_TO_WRONG_TARGET")

package coreMindustry

import cf.wayzer.scriptAgent.thisContextScript
import cf.wayzer.scriptAgent.util.DSLBuilder
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.util.calPage
import coreMindustry.lib.game
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.ui.Menus
import mindustry.ui.builder.MenuResult
import mindustry.ui.builder.UiBuilder
import mindustry.ui.builder.UiBuilder.NodeBuilder
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import mindustry.ui.builder.MenuBuilder as MTMenuBuilder

/** 布局项：一个节点，或一个换行标记 */
private sealed interface MenuItem {
    object Row : MenuItem

    /** 单元格,[columns] 为加入时的列数，[autoSize] 表示参与等宽分配 */
    class Cell(val node: NodeBuilder<*>, val columns: Int, val autoSize: Boolean = false) : MenuItem
}

@Suppress("unused", "MemberVisibilityCanBePrivate")
@MenuV3.MenuBuilderDsl
open class MenuV3(
    val player: Player,
    private val block: suspend MenuV3.() -> Unit = { }
) {
    @DslMarker
    annotation class MenuBuilderDsl

    class RefreshReturn : Throwable("This method should only call in callback", null, false, false) {
        private fun readResolve(): Any = RefreshReturn()
    }

    open class FlagOptionBuilder {
        var name: String? = null
        var action: suspend () -> Unit = { }

        @MenuBuilderDsl
        @Throws(CommandInfo.Return::class)
        open fun option(name: String, action: suspend () -> Unit = { }) {
            this.name = name
            this.action = action
            CommandInfo.Return()
        }

        @MenuBuilderDsl
        fun refreshOption(name: String): Nothing {
            option(name)
            throw RefreshReturn()
        }

        object Dummy : FlagOptionBuilder() {
            override fun option(name: String, action: suspend () -> Unit) = Unit
        }
    }

    private var items = mutableListOf<MenuItem>()
    private val callbacks = mutableMapOf<String, suspend (MenuResult) -> Unit>()
    private var optionSeq = 0
    private var colCount = 0
    private var radioGroup: String? = null

    @MenuBuilderDsl
    var title = ""
    @MenuBuilderDsl
    var msg = ""
    @MenuBuilderDsl
    var columnPreRow = 1

    /**
     * 把整页内容包进一层滚动区。
     * 注意：**只在 `fillScreen=true` 时有意义**——`fillScreen=false` 时对话框按内容 `pack()`，
     * `growY` 没有可分配空间，滚动区高度会退化成内容高度（读起来像"没限制"）。
     * 当前三个接入页面都是 `wrapInPane=false` + 自己用 `pane(id, height)` 控制阅读区。
     */
    @MenuBuilderDsl
    var wrapInPane: Boolean = true

    /**
     * 是否让对话框铺满整个屏幕（v160 `MenuBuilder.fillScreen`）。
     *
     * `false`（默认）：`Dialog.show()` 会 `pack()` 到内容尺寸并由 `centerWindow()` 居中，
     * 得到的是一块**尺寸跟着内容走、居中、不随窗口大小变化**的面板，内容不会贴到窗口边上。
     * `true`：`setFillParent(true)`，对话框=整个窗口，内容仍按 [rootWidth] 限宽居中，
     * 但对话框背景会铺满窗口。
     *
     * 无论哪种取值，都要保证"标题 + 正文 + 按钮"的总高度不超过常见窗口高度
     * （`pane()` 的高度不会自动收缩），否则底部按钮会被挤出屏幕。参考预算见 docs/custom-menu.md。
     */
    @MenuBuilderDsl
    var fillScreen: Boolean = false

    /** 内容宽度 */
    @MenuBuilderDsl
    var rootWidth: Float = 520f

    /** 单元格内边距 */
    @MenuBuilderDsl
    var cellPad: Float = 4f

    /** 按钮高度 */
    @MenuBuilderDsl
    var optionHeight: Float = 50f

    val sessionState = mutableMapOf<String, Any?>()
    var onCancel: suspend () -> Unit = { }
    private var closed = false

    @MenuBuilderDsl
    inline fun <reified T> stateKey(
        default: T,
        keyPrefix: String = ""
    ): DSLBuilder.NameGet<ReadWriteProperty<Any?, T>> =
        DSLBuilder.NameGet { name ->
            val key = keyPrefix + name
            object : ReadWriteProperty<Any?, T> {
                @Suppress("UNCHECKED_CAST")
                override fun getValue(thisRef: Any?, property: KProperty<*>): T =
                    (sessionState.getOrPut(key) { default }) as T

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                    sessionState[key] = value
                }
            }
        }

    private fun addCell(node: NodeBuilder<*>, autoSize: Boolean = false) {
        items.add(MenuItem.Cell(node, columnPreRow, autoSize))
        colCount++
    }

    @MenuBuilderDsl
    fun newRow() {
        if (colCount > 0) {
            items.add(MenuItem.Row)
            colCount = 0
        }
    }

    private fun ensureSpace() {
        if (colCount >= columnPreRow) {
            items.add(MenuItem.Row)
            colCount = 0
        }
    }

    /** 把 [body] 内产生的布局项单独收集起来 交给容器使用 */
    private fun capture(body: () -> Unit): List<MenuItem> {
        val outerItems = items
        val outerCount = colCount
        val captured = mutableListOf<MenuItem>()
        items = captured
        colCount = 0
        try {
            body()
        } finally {
            items = outerItems
            colCount = outerCount
        }
        return captured
    }

    /**
     * 一行文本。[wrap] 为 true 时按容器宽度自动换行——**长文章必须开**，
     * 否则 Label 会按整行宽度撑开布局（阅读类页面务必用 `label(..., align = "left", wrap = true)`）。
     */
    @MenuBuilderDsl
    fun label(text: String, align: String = "center", wrap: Boolean = false) {
        newRow()
        val node = UiBuilder.label(text).growX().labelAlign(align).align(align).pad(cellPad)
        if (wrap) node.wrap()
        items.add(MenuItem.Cell(node, columnPreRow))
        items.add(MenuItem.Row)
        colCount = 0
    }

    @MenuBuilderDsl
    fun option(
        label: String,
        icon: String? = null,
        style: String? = null,
        growX: Boolean = true,
        body: suspend (MenuResult) -> Unit
    ) {
        ensureSpace()
        val id = "opt_${optionSeq++}"
        callbacks[id] = body
        val node = UiBuilder.button(label).clicked(id).height(optionHeight).pad(cellPad)
        if (icon != null) node.icon(icon)
        if (style != null) node.style(style)
        radioGroup?.let { node.group(it) }
        addCell(node, autoSize = growX)
    }

    @MenuBuilderDsl
    fun image(region: String, size: Float = 64f) {
        ensureSpace()
        addCell(UiBuilder.image(region).size(size).pad(cellPad))
    }

    @MenuBuilderDsl
    fun check(id: String, text: String, checked: Boolean = false) {
        ensureSpace()
        addCell(UiBuilder.check(text).id(id).checked(checked).pad(cellPad))
    }

    /** 输入框独占一行 任意按钮点击时都能通过 [MenuResult] 读到它的内容 */
    @MenuBuilderDsl
    fun field(id: String, hint: String = "", maxLength: Int = 0) {
        newRow()
        val node = UiBuilder.field("").id(id).growX().pad(cellPad)
        if (hint.isNotEmpty()) node.hint(hint)
        if (maxLength > 0) node.maxLength(maxLength)
        addCell(node)
        newRow()
    }

    /** 占位空格：高度与一行按钮一致，用于"本页条目不足时补齐行数"，避免翻页时下面的按钮上下跳动。 */
    @MenuBuilderDsl
    fun space() {
        ensureSpace()
        addCell(UiBuilder.space().height(optionHeight).pad(cellPad), autoSize = true)
    }

    @MenuBuilderDsl
    fun column(num: Int, body: () -> Unit) {
        newRow()
        val bakColumn = columnPreRow
        columnPreRow = num
        body()
        columnPreRow = bakColumn
        newRow()
    }

    /** 单选按钮组：同组按钮互斥 位于同一行且等宽 */
    @MenuBuilderDsl
    fun group(name: String, body: () -> Unit) {
        newRow()
        val bakGroup = radioGroup
        val bakColumn = columnPreRow
        radioGroup = name
        columnPreRow = Int.MAX_VALUE
        body()
        radioGroup = bakGroup
        columnPreRow = bakColumn
        newRow()
    }

    @MenuBuilderDsl
    fun pane(id: String = "", height: Float = 200f, body: () -> Unit) {
        newRow()
        val content = capture(body)
        val node = UiBuilder.pane().height(height).growX().pad(cellPad)
        if (id.isNotEmpty()) node.id(id)
        // 2026-09-13 修复：滚动区里的内容必须**显式限宽**。
        // ScrollPane 是按内容的"首选宽度"排布的，不设宽度时 `wrap` 的 Label 会按整行铺开，
        // 文字会横跨整屏且不换行（实测单行 2246px）。这里限到内容宽度，并给滚动条留 24px，
        // 避免文字被滚动条压住。
        node.add(buildTable(content, contentWidth()))
        addCell(node)
        newRow()
    }

    /** 内容列可用宽度（已扣除单元格内边距与滚动条位置） */
    private fun contentWidth(): Float = (rootWidth - cellPad * 2f - 24f).coerceAtLeast(120f)

    @MenuBuilderDsl
    fun condition(expr: String, body: () -> Unit) {
        newRow()
        val content = capture(body)
        addCell(buildTable(content, rootWidth).condition(expr))
        newRow()
    }

    /**
     * 构建一张内容表：每行单独包一层 table，行内按钮按 `tableWidth / 列数` 均分，行与行互不影响。
     *
     * **宽度必须写在"行"上（2026-09-13 第二次修正，源码级结论）**：
     * `Menus.menuBuilder` 执行的是 `UiTreeBuilder.build(dialog.cont, 根节点)`，
     * 它把根节点的**子项直接加进 `dialog.cont`**；根节点自己的属性只会走
     * `applyTableProp`，而该方法只认 `background` / `margin` / `align` 三个键。
     * 也就是说，写在根节点上的 `width()` / `fillX()` / `growX()` **全部被静默丢弃**。
     * 旧实现把 `width(rootWidth)` 写在根表上 → 无效 → 每行仍是 `growX()`，
     * 于是 `fillScreen=true`（对话框铺满窗口）时行被撑到整个窗口宽，
     * 单列按钮就变成"整屏宽按钮"（实测：关闭按钮 x18→2542，屏幕才 2560）。
     * 现在把宽度写到**每一行**的 cell 上；横向位置由 `dialog.cont` 的
     * `align("center")`（根节点的 `align` 是唯一生效的根属性）居中。
     */
    private fun buildTable(source: List<MenuItem>, tableWidth: Float): UiBuilder.TableBuilder {
        val table = UiBuilder.table().align("center").width(tableWidth)

        var cells = mutableListOf<MenuItem.Cell>()

        fun flushRow() {
            if (cells.isEmpty()) return
            val declared = cells.filter { it.autoSize }.maxOfOrNull { it.columns } ?: 1
            val columns = if (declared in 2..16) maxOf(declared, cells.size) else cells.size
            val columnWidth = tableWidth / columns - cellPad * 2 - 1f

            // 行宽 = 内容宽（而不是 growX 撑满对话框）；行内子元素默认居中
            val row = UiBuilder.table().width(tableWidth).align("center")
            cells.forEach { cell ->
                if (cell.autoSize) {
                    if (columns > 1) cell.node.width(columnWidth).fillX() else cell.node.growX()
                }
                row.add(cell.node)
            }
            if (columns in 2..16 && cells.size < columns && cells.any { it.autoSize }) {
                repeat(columns - cells.size) {
                    row.add(UiBuilder.space().width(columnWidth).pad(cellPad))
                }
            }
            table.add(row)
            table.row()
            cells = mutableListOf()
        }

        source.forEach { item ->
            when (item) {
                is MenuItem.Row -> flushRow()
                is MenuItem.Cell -> cells.add(item)
            }
        }
        flushRow()
        return table
    }

    private fun buildRoot(): NodeBuilder<*> {
        val source = if (msg.isEmpty()) items else buildList {
            add(MenuItem.Cell(UiBuilder.label(msg).growX().wrap().labelAlign("center").align("center").pad(cellPad), 1))
            add(MenuItem.Row)
            addAll(items)
        }
        val content = buildTable(source, if (wrapInPane) contentWidth() else rootWidth)
        if (!wrapInPane) return content
        // 根节点属性会被丢弃，所以"整页包一层滚动区"时，宽度必须写在 pane 自己的 cell 上。
        return UiBuilder.table()
            .align("center")
            .add(UiBuilder.pane().width(rootWidth).growY().add(content))
    }

    protected open suspend fun build() = block()

    private var closedSignal: CompletableDeferred<Unit>? = null
    /** 本次下发的会话令牌：客户端会在 [MenuResult.token] 里原样回传，用于把点击路由回本会话。 */
    private var sessionToken: Long = 0L

    suspend fun send(rebuild: Boolean = true): MenuV3 {
        closed = false
        if (rebuild) {
            items.clear()
            callbacks.clear()
            optionSeq = 0
            colCount = 0
            radioGroup = null
            build()
        }

        // 路由说明（2026-09-13 改动）：
        // v160 的 `Menus.registerMenuBuilder` 只往 `menuBuilderListeners` 数组尾部追加、**从不移除**，
        // 返回的下标就是回调 id。参考实现是"每个 MenuV3 实例注册一次"，会话结束后监听器仍被数组强引用，
        // 长期运行（每次开菜单都新建实例）会无界增长。而 `MenuBuilder` 的字段注释明确写着
        // "id 可以复用，只要按钮结果键唯一"，并且 `token` 就是给回调识别会话用的，
        // 因此这里改为**全局只注册一次** + 每会话唯一 token 路由。
        sessionToken = nextSessionToken()
        activeSessions[sessionToken] = this
        // 同一玩家的旧会话会被新菜单顶掉（hideExisting 默认 true）：回收路由表项，并**唤醒旧会话的 await**，
        // 否则每次翻页/跳转都会留下一个等到超时才结束的协程。
        // 注意：这里不能调用旧会话的 close()——所有会话共用同一个 menuId，close() 会把玩家当前的菜单一起关掉。
        activeSessions.entries.removeAll { entry ->
            val old = entry.value
            if (old !== this && old.player === player) {
                old.closed = true
                old.closedSignal?.complete(Unit)
                true
            } else false
        }

        MTMenuBuilder.of(buildRoot())
            .id(sharedMenuId)
            .token(sessionToken)
            .title(title.ifEmpty { null })
            .hideOnClick(false)
            .fillScreen(fillScreen)
            .show(player)

        return this
    }

    private fun releaseSession() {
        if (sessionToken != 0L) activeSessions.remove(sessionToken, this)
        sessionToken = 0L
    }

    private fun dispatch(result: MenuResult) {
        for ((id, cb) in callbacks) {
            if (result.`is`(id)) {
                script.launch(Dispatchers.game) {
                    try {
                        cb(result)
                    } catch (_: RefreshReturn) {
                        send()
                    } catch (_: CommandInfo.Return) {
                    }
                }
                return
            }
        }
        script.launch(Dispatchers.game) {
            onCancel()
            closedSignal?.complete(Unit)
        }
    }

    /** 局部替换某个 id 的节点 */
    fun update(id: String, node: NodeBuilder<*>) {
        val menuId = sharedMenuId
        if (menuId == -1) return
        MTMenuBuilder.of(node).id(menuId).update(player, id)
    }

    fun update(id: String, nodeDsl: String) = update(id, UiBuilder.parse(nodeDsl))

    fun MenuResult.stringOrNull(id: String): String? =
        try {
            getString(id)
        } catch (_: Exception) {
            null
        }

    fun MenuResult.floatOrNull(id: String): Float? =
        try {
            getFloat(id)
        } catch (_: Exception) {
            null
        }

    fun MenuResult.booleanOrNull(id: String): Boolean? =
        try {
            getBool(id)
        } catch (_: Exception) {
            null
        }

    suspend fun await() {
        closedSignal = CompletableDeferred()
        try {
            closedSignal!!.await()
        } finally {
            closedSignal = null
        }
    }

    suspend fun awaitWithTimeout(chooseTimeout: Duration = 60.seconds) {
        closedSignal = CompletableDeferred()
        try {
            withTimeoutOrNull(chooseTimeout) { closedSignal!!.await() }
        } finally {
            closedSignal = null
            if (!closed) {
                script.launch(Dispatchers.game) { onCancel() }
                close()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        closedSignal?.complete(Unit)
        releaseSession()
        if (sharedMenuId != -1) {
            Call.hideMenuBuilder(player.con, sharedMenuId)
        }
    }

    /** 只隐藏界面、保留会话（下次 [send] 会重新下发同一会话）。 */
    fun hide() = Call.hideMenuBuilder(player.con, sharedMenuId)

    fun refresh(): Nothing = throw RefreshReturn()

    companion object {
        private val script = thisContextScript()

        private val tokenSeq = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

        /** token -> 会话。回调按 token 路由，因此全局只需要一个监听器。 */
        private val activeSessions = java.util.concurrent.ConcurrentHashMap<Long, MenuV3>()

        private fun nextSessionToken(): Long = tokenSeq.incrementAndGet()

        /**
         * 全局唯一的回调 id。v160 的监听器数组只增不删，所以这里**绝不能**按会话注册。
         * [sharedMenuId] 为 -1 表示当前环境没有该 API（例如更旧的游戏版本）。
         */
        private val sharedMenuId: Int by lazy {
            runCatching {
                Menus.registerMenuBuilder { p, result ->
                    val session = activeSessions[result.token] ?: return@registerMenuBuilder
                    if (session.player !== p) return@registerMenuBuilder
                    session.dispatch(result)
                }
            }.getOrDefault(-1)
        }
    }
}

@Suppress("DuplicatedCode")
@MenuV3.MenuBuilderDsl
inline fun <T> MenuV3.renderPaged(
    list: List<T>,
    initialPage: Int = 1,
    prePage: Int = 9,
    columns: Int = 1,
    key: String = "",
    crossinline itemRender: (T) -> Unit
) {
    var selectedPage by stateKey(initialPage, keyPrefix = "renderPaged@$key-")
    val (page, totalPage) = calPage(selectedPage, prePage, list.size)
    val renderItems: () -> Unit = {
        repeat(prePage) {
            val i = (page - 1) * prePage + it
            if (i >= list.size) space() else itemRender(list[i])
        }
    }
    if (columns > 1) column(columns) { renderItems() } else renderItems()
    column(3) {
        option("<-") { selectedPage = page - 1; refresh() }
        option("$page/$totalPage") { refresh() }
        option("->") { selectedPage = page + 1; refresh() }
    }
}