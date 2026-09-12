# 自定义菜单（实验功能，Mindustry 160.1）

> 更新记录：2026-09-12 首次实现（用户要求：用 160.1 的自定义菜单把 wiki 与帖子列表改成更接近现代论坛的界面，
> 并改造商店系统与成就系统，使其更易操作、可读性更高）。
> 状态：**实验性**，未做真实客户端验证（见文末边界）。

## 背景：160.1 新增了什么

Mindustry **160.1** 新增了"服务端向客户端下发自定义菜单"的网络能力，位于 `mindustry.ui.builder`：

| 组件 | 作用 |
|---|---|
| `UiBuilder` | 组装 UI 组件树的静态工厂：`table()/label()/button()/imageButton()/field()/check()/slider()/space()/buttonTable()/pane()/stack()` |
| `UiBuilder.NodeBuilder` | 公共属性：`grow/growX/growY/fill/expand/width/height/pad/align/colspan/uniform/color/id/condition` |
| `MenuBuilder` | 会话描述：`title/hideOnClick/hideExisting/fillScreen/token/id/ui`，`show(player)` 下发 |
| `MenuResult` | 客户端回传：`token`、`result`（被点按钮的 id，关闭时为 null）、`values`（输入框/滑条/开关的值） |
| `Call.menuBuilder` / `menuBuilderUpdate` / `hideMenuBuilder` / `menuBuilderChoose` | 网络入口 |

服务端下发：`Call.menuBuilder(con, menuId, token, title, hideOnClick, hideExisting, fillScreen, ui)`。
客户端回传：`Call.menuBuilderChoose(player, menuId, result)`，服务端侧
`Menus.menuBuilderListeners.get(menuId).get(player, result)` 派发（同时 fire `EventType.MenuBuilderOptionChooseEvent`）。

相比既有的聊天式按钮菜单（`coreMindustry.MenuBuilder`），它支持**多列布局、分区、滚动、文本输入与开关**，
因此适合做"论坛式"列表与详情页。

## 本项目实现

### 文件与职责

| 文件 | 职责 |
|---|---|
| `mdtserver/config/scripts/coreMindustry/lib/customMenu.kt` | **会话层**：监听器池、token 校验、`sendCustomMenu`/`closeCustomMenu`、陈旧会话清理 |
| `mdtserver/config/scripts/coreMindustry/lib/customMenuParts.kt` | **渲染件**：`sectionHeader`/`listRow`/`textRow`/`actionRow`/`navRow` 与 `RESULT_BACK`/`RESULT_CLOSE` 常量 |

两者都在 `coreMindustry/lib/` 下，配合 `coreMindustry/.metadata` 的
`+IMPORT DefaultImport coreMindustry.lib.*` 对全模块默认可见。业务脚本直接 `import coreMindustry.lib.xxx` 即可。

### 为什么放在 `lib/*.kt` 而不是脚本（重要踩点）

ScriptAgent 里**脚本（`.kts`）之间不能直接 import 彼此的顶层函数**——跨脚本调用要用
`contextScript<T>()`。只有 `lib/*.kt` 才是普通的、可被其它脚本 import 的 Kotlin 代码。
最初把会话层写成 `coreMindustry/customMenu.kts` 时，业务脚本 `import coreMindustry.sendCustomMenu` 全部解析失败，改为 `lib/*.kt` 后正常。

### 会话层的关键设计（`menuId` 是客户端数组下标）

- `menuId` **不是业务编号**，而是**客户端 `menuBuilderListeners` 数组的下标**：
  `menuBuilderChoose` 派发时做的是 `menuBuilderListeners.get(menuId).get(player, result)`。
- `Menus.registerMenuBuilder` **只增不删**，所以采用**监听器池 + 全局递增 id**：
  池按需增长（`ensureListenerInstalled`），每个会话认领一个全局递增 id，下标不回收。
- 每次下发带唯一 `token`，回传时校验；不匹配（玩家又开了新菜单）直接忽略。
- 会话在玩家离线或失效时于下次下发顺带清理（`pruneStale`），避免注册表随会话无限增长。

### 兼容性与回退（必须保留）

这是 160.1 才有的网络能力，**旧客户端无法渲染**。因此四个系统都采用同一模式：

```kotlin
// 1) 能力判定 + 数据准备（挂起查询在进入非挂起渲染函数之前完成）
if (!customMenuSupported(player)) return false
val data = runCatching { db { ... } }.getOrNull() ?: return false
// 2) 下发新菜单；任何异常都不向玩家抛出，而是返回 false 走回退
return runCatching { showXxxCustom(player, data); true }
    .getOrElse { logger.warning("...自定义菜单下发失败，回退聊天菜单：${it.message}"); false }
```

调用点形如 `if (openXxxCustom(...)) return`，紧随其后就是**原有的聊天菜单实现，完全保留**。

### Kotlin 脚本环境踩点（写这类代码时注意）

- `UiBuilder.table()/button()/label()` 是 **Java 静态方法**，Kotlin **不会**把它们当带接收者的 lambda：
  只能 `val t = table(); t.xxx()`，**不能**写 `table { }`。
- `LabelBuilder` 只有 `labelAlign("left"|"right"|"center")`，**没有** `left()/right()`（后者是 cell 属性 `align`）。
- 参数名不要叫 `build`：K2 分析器会与 `kotlin.collections.build` 冲突并抛
  `FileAnalysisException ... FirNamedFunctionSymbol kotlin/collections/build found`。
- `RootCommands.handleInput(...)` 与 `Player.hasPermission(...)` 都是 **suspend**：菜单回调不是挂起上下文，
  必须 `launch(Dispatchers.game) { ... }` 包一层，或把判断提前到 suspend 调用方。
- Kotlin 支持**嵌套块注释**：注释正文里出现 `/*` 会被当作嵌套开始，导致 `Unclosed comment`。
- 回传结果里 `@close`（关闭对话框）由框架处理；自定义 id 建议用 `前缀:参数` 形式（如 `open:<id>`、`page:2`）。

## 已接入的系统

| 系统 | 入口 | 新菜单形态 | 回退 |
|---|---|---|---|
| 商店列表 | `/shop` | 列表：商店名 + 说明 + 右侧"打开"；详情页：说明 + 入口指令 + 打开/返回/关闭 | 原聊天菜单 |
| Wiki | `/wiki`、`/wiki <id>` | 列表：标题 + 摘要 + 右侧字数/更新者 + 翻页；详情页：整页滚动正文 + 编辑/最近修改/返回/关闭 | 原聊天菜单（含分页正文） |
| 帖子 | `/posts`、帖子详情 | 列表：标题（置顶标记）+ 作者/时间/评论数 + 翻页 + 发布；详情页：正文 + 赞/踩/评论/分享/编辑/置顶/锁定/保护锁/删除 | 原聊天菜单（含分页正文） |
| 成就 | `/achievements` | 列表：成就名 + 要求/奖励 + 已完成标记；隐藏成就未完成前显示"？？？" | 原 `PagedMenuBuilder` 分页菜单 |

正文/列表规模控制：Wiki 与帖子详情正文超过 3000 字符时截断并提示（避免一次性下发巨量文本）。

## 已知边界与后续注意

- **未做真实客户端验证**：本轮只完成编译与冷启动加载验证；新菜单的实际渲染效果、点击回传、
  旧客户端回退行为都需要真实客户端受控测试。
- **回退判定的粒度**：`customMenuSupported` 只排除"无连接"，不解析客户端版本；
  真正的兜底依赖下发异常。若某版本客户端能连上但渲染不出菜单（无异常），会停留在"没有反应"，
  届时需要补更精确的能力探测（例如握手阶段的版本字段）。
- **局部更新未使用**：`Call.menuBuilderUpdate` + 元素 `id` 可以做局部刷新（例如翻页只换列表区），
  当前实现统一采用"关掉旧菜单、重开新菜单"，简单但会闪一下。
- **图片/图标**：`imageButton(icon)` 支持图标字符与 `image(region)`；当前只用了文字与颜色，
  没有引入贴图，避免客户端缺少对应 region。
