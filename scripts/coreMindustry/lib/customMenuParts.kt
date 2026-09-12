package coreMindustry.lib

import mindustry.ui.builder.MenuResult
import mindustry.ui.builder.UiBuilder.*

/**
 * 自定义菜单渲染件（纯函数集合）——供 `coreMindustry/menu` 的新菜单会话层使用。
 *
 * 为什么单独拆一个文件：这些是 `UiBuilder.TableBuilder` 上的扩展函数，**必须同时被模块脚本与业务脚本看到**。
 * 所以本文件放在 `coreMindustry/lib/`，配合 `coreMindustry/.metadata` 的
 * `+IMPORT DefaultImport coreMindustry.lib.*` 让全模块默认可见。
 *
 * 注意（踩点记录）：
 * - `UiBuilder.table()/button()/label()` 都是 **Java 静态方法**，Kotlin **不会**把它们当带接收者的 lambda，
 *   因此只能 `val t = table(); t.xxx()`，不能写 `table { }`。
 * - 链式调用习惯上写成多行（`val b = button(x); b.clicked(...)`），便于在脚本环境里排错。
 */
object CustomMenuParts {
    const val RESULT_BACK = "@back"
    const val RESULT_CLOSE = "@close"

    fun isBack(result: String): Boolean = result == RESULT_BACK
    fun isClose(result: String): Boolean = result == RESULT_CLOSE

    /** 分区标题。 */
    fun TableBuilder.sectionHeader(text: String, hint: String? = null) {
        val box = table()
        box.background("black6")
        box.margin(8f)
        box.growX()

        val t = label(text)
        t.growX()
        t.color(arc.graphics.Color.valueOf("7bd0ff"))
        t.labelAlign("left")
        t.pad(4f)
        box.add(t)

        if (!hint.isNullOrBlank()) {
            val h = label(hint)
            h.growX()
            h.color(arc.graphics.Color.valueOf("9aa1b5"))
            h.labelAlign("left")
            h.pad(4f)
            box.add(h)
        }
        add(box.growX())
        row()
    }

    /**
     * 论坛式列表行：标题 + 副标题，右侧辅助信息，整行可点。
     * 覆盖整行的透明按钮保证"点哪都能进详情"。
     */
    fun TableBuilder.listRow(
        result: String,
        title: String,
        subtitle: String? = null,
        trailing: String? = null,
        accent: String = "4da3ff",
    ) {
        val box = table()
        box.background("black6")
        box.margin(6f)
        box.pad(6f)
        box.growX()

        val textCol = table()
        textCol.growX()

        val titleLabel = label(title)
        titleLabel.growX()
        titleLabel.color(arc.graphics.Color.valueOf(accent))
        titleLabel.labelAlign("left")
        textCol.add(titleLabel)

        if (!subtitle.isNullOrBlank()) {
            textCol.row()
            val sub = label(subtitle)
            sub.growX()
            sub.color(arc.graphics.Color.valueOf("9aa1b5"))
            sub.labelAlign("left")
            textCol.add(sub)
        }
        box.add(textCol.growX())

        if (!trailing.isNullOrBlank()) {
            val tail = label(trailing)
            tail.color(arc.graphics.Color.valueOf("ffd257"))
            tail.labelAlign("right")
            tail.pad(8f)
            box.add(tail)
        }

        val hit = button("")
        hit.clicked(result)
        hit.growX()
        hit.height(48f)
        box.add(hit)

        add(box.growX())
        row()
    }

    /** 正文文本行（自动换行）。 */
    fun TableBuilder.textRow(text: String) {
        val l = label(text)
        l.growX()
        l.wrap()
        l.labelAlign("left")
        l.pad(6f)
        add(l)
        row()
    }

    /** 一行多个操作按钮。 */
    fun TableBuilder.actionRow(actions: List<Pair<String, String>>) {
        val bar = buttonTable()
        bar.growX()
        actions.forEach { pair ->
            val b = button(pair.first)
            b.clicked(pair.second)
            b.growX()
            b.height(44f)
            bar.add(b)
        }
        add(bar.growX())
        row()
    }

    /** 统一的返回/关闭行。 */
    fun TableBuilder.navRow(backText: String = "返回", closeText: String = "关闭") {
        actionRow(listOf(backText to RESULT_BACK, closeText to RESULT_CLOSE))
    }
}
