# 自定义菜单（160.1 服务端下发菜单）：尝试、回退与参考实现

> 状态：**方向未死**。2026-09-12 的手写实现已回退（按钮缩放/界面适配/缺失贴图）；
> 2026-09-13 拿到第三方参考实现 `MenuV3`（带布局引擎），已落地为 `coreMindustry/lib/menuV3.kt` 并通过编译验证，
> **尚未接入任何业务菜单**（等选定试点页面）。
> 本文档保留"为什么回退"、实现期踩点，以及 `MenuV3` 的机制/落地要点。

## 结论（2026-09-13 更新）

- 2026-09-12：自研的 `coreMindustry/lib/customMenu*.kt` 在四个入口（wiki/帖子/商店/成就）实测失败
  （按钮缩放异常、界面适配不了、缺失贴图），当日全部回退。
- 2026-09-13：找到并落地参考实现 `MenuV3`——它把布局、等宽、补空行、滚动、条件渲染都封装好了，
  **正是上次失败的根因所在**；编译与跨模块使用均已验证通过。菜单更新可以在这个基础上重启。

**回退原因（用户实测，2026-09-12）**：

1. **按钮缩放问题** —— 组件尺寸在客户端表现异常；
2. **界面适配问题** —— 布局无法稳定适配；
3. **缺失贴图** —— 需要的贴图在客户端不存在。

用户原话概括："实际几乎全是问题"。

## 回退了什么

- 删除新增库文件：`coreMindustry/lib/customMenu.kt`（会话层）、`coreMindustry/lib/customMenuParts.kt`（渲染件）。
- `wayzer/user/shopList.kts`、`wiki.kts`、`forumPosts.kts`、`achievement.kts`
  四个脚本整体还原到接入之前的版本（即 `3a0e5ef` 时的内容），保留原有聊天菜单实现。
- 复测：冷启动 `共找到158脚本,加载成功154,启用成功149,出错0`，与接入前完全一致。

## 上游能力（留档）

- **这是 v160 原版能力**：`mindustry/ui/builder/*`（`MenuBuilder`/`MenuResult`/`UiBuilder`/`UiTreeBuilder`/`UiDslParser`/
  `UiHotReload`/`UiKey`/`UiStyleLookup`）在**官方 Anuken 仓库**里就存在（`参考项目/Mindustry-master` 已核对），
  MindustryX 的 `patches/` 也没有改它 → 任何 **v160+ 客户端**都能渲染，不需要装 Mod。
- 下发：`Call.menuBuilder(con, menuId, token, title, hideOnClick, hideExisting, fillScreen, NodeBuilder)`
- 组件：`UiBuilder` 静态工厂 `table()/label()/button()/imageButton()/field()/check()/slider()/space()/buttonTable()/pane()/stack()`
- 回传：`Call.menuBuilderChoose(player, menuId, MenuResult)`；服务端 `Menus.menuBuilderListeners.get(menuId).get(player, result)` 派发，
  同时 fire `EventType.MenuBuilderOptionChooseEvent`
- `MenuResult` 字段：`token`、`result`（被点按钮 id，关闭时为 null）、`values`（输入框/滑条/开关的值）
- 局部更新：`MenuBuilder.update(player, elementId)` → `Call.menuBuilderUpdate(con, id, elementId, node)`，
  用于只换掉某个带 `id` 的节点，不必整页重发。

**关键约束**：`menuId` 是**客户端 `menuBuilderListeners` 数组的下标**，不是业务编号；
`Menus.registerMenuBuilder` 只增不删，所以**全局只能注册有限次**（原实现用"监听器池 + 全局递增 id + token 校验"处理，
`MenuV3` 落地时同样按 token 路由，见下文）。

## 实现期技术踩点（对写其它脚本同样有效）

1. **脚本（`.kts`）之间不能互相 import 顶层函数**：跨脚本只能走 `contextScript<T>()`；
   只有 `lib/*.kt` 是可被 import 的普通 Kotlin 代码（配合 `+IMPORT DefaultImport <模块>.lib.*`）。
   最初把会话层写成 `coreMindustry/customMenu.kts` 时，业务脚本 `import coreMindustry.sendCustomMenu` 全部解析失败。
2. `UiBuilder.table()/button()/label()` 是 **Java 静态方法**，Kotlin **不会**当带接收者的 lambda：
   只能 `val t = table(); t.xxx()`，不能写 `table { }`。
3. `LabelBuilder` 只有 `labelAlign("left"|"right"|"center")`，**没有** `left()/right()`（那是 cell 属性 `align`）。
4. 参数名不要叫 `build`：K2 分析器与 `kotlin.collections.build` 冲突，抛
   `FileAnalysisException ... FirNamedFunctionSymbol kotlin/collections/build found`。
5. `RootCommands.handleInput(...)` 与 `Player.hasPermission(...)` 都是 **suspend**，菜单回调不是挂起上下文，
   必须 `launch(Dispatchers.game) { ... }`，或把判断提前到 suspend 调用方。
6. Kotlin 支持**嵌套块注释**：注释正文里出现 `/*` 会被当作嵌套开始，导致 `Unclosed comment`。

## 2026-09-13：参考实现 `MenuV3` —— 布局问题有解了

用户提供了一份参考脚本（`menu.ui.kt`，`package coreMindustry`，460 行），是 `coreMindustry.MenuV3` +
`MenuV3.renderPaged` 的完整实现：**把 v160 的原生菜单 API 包成带布局引擎的 DSL**。已按它落地为
`coreMindustry/lib/menuV3.kt`（与既有 `coreMindustry/menu.new.kt` 的 MenuV2 并存，互不冲突）。

**来源说明（重要）**：该文件**不在上游 ScriptAgent 的任何 ref 里**（blob `53151fc…` 在
`git rev-list --all --objects` 中查不到）。上游 8.0 HEAD（`ad56edc`）的 `coreMindustry/menu.new.kt`
仍是旧 `Call.menu` 体系的 `MenuV2`。所以它是第三方/分支的演进版本，**按"自维护文件"处理**，不指望上游合并。

### 它为什么能解决"按钮缩放 / 错位 / 比例失调"

对照 Mindustry v160 源码（`work/core/src/mindustry/ui/builder/UiTreeBuilder.java`、`UiBuilder.java`）确认了机制：

1. **固定内容宽度**：`rootWidth = 520f`，根表 `.width(rootWidth).fillX()`，几何是确定的；
2. **每行单独包一层 Table**：外层表按行 `row()`，行内自成一张表 → 列宽不会跨行互相影响
   （旧实现一张大表混排，正是"错位"的根因）；
3. **显式等宽**：`columnWidth = rootWidth / columns - cellPad*2 - 1f`，落到 **cell 宽度**
   （`NodeBuilder.width()` 在运行时是 `Cell.width`）+ `fillX()`；
4. **不满行补空**：用 `space().width(columnWidth)` 占位 → 修掉"最后一个按钮被拉宽"的经典问题；
5. **固定 `optionHeight = 50f`、`cellPad = 4f`**：所有按钮同高同边距，缩放一致；
6. **溢出交给 pane**：内容包在 `ScrollPane`（`.growY().growX()`）里；`MenuBuilder.fillScreen` 默认 true，
   参考实现没有关掉（上游注释明确："大型复杂菜单必须为 true"）；
7. **文本换行**：`label(...).wrap()` + `labelAlign`，`msg` 单独占一行；
8. **客户端自适应**：`condition("portrait" / "landscape" / "width>=N")` 在**客户端**求值，
   不满足的节点直接不构建（无需重发菜单）；
9. **单选组**：`group("name")`，同组互斥且等宽同行；图标按 `btn.add(icon).size(32f)` 由原版排版。

### "缺失贴图"能缓解到什么程度

贴图解析走 `UiTreeBuilder.findDrawable(name, placeholder)`，回退链是
**atlas → `Icon.icons` 图标名 → `placeholder(...)` → `"nomap"`**。所以：

- 传一个不存在的 region 不会再让整个组件变形/空白，会退化成占位图；
- 图标名可以直接用原版图标名（`Icon.icons`），比自定义 region 安全得多；
- `image(region, size)` / `option(icon=…)` 都带显式尺寸，不会因缺图把布局撑坏。

结论没变的部分：**真正要显示的贴图必须客户端确实存在**——这是内容问题，不是布局问题；
工程上应只用原版 region/图标名，必要时显式给 `placeholder`。

### 落地时必须改的两处（已改）

1. **文件位置必须是 `lib/`**：实测把文件放在模块根目录（`coreMindustry/menuV3.kt`）时，
   探针脚本报 `Unresolved reference 'MenuV3'` —— 模块根 `.kt` 只对声明了
   `+DEPENDS coreMindustry module` 的脚本可见（例如 `wayzer/cmds/mapsCmd` 依赖 `coreMindustry/menu`），
   而 `lib/**` 才是对所有脚本可见的公共库（模块元数据里有 `+IMPORT DefaultImport coreMindustry.lib.*`）。
   最终位置：`coreMindustry/lib/menuV3.kt`，`package coreMindustry` 保持不变。
2. **回调注册不能按会话做**：v160 的 `Menus.registerMenuBuilder` 只往 `menuListeners` 数组尾部追加、
   **从不移除**，返回下标即回调 id。参考实现是"每个 MenuV3 实例注册一次"，会话结束后监听器仍被数组强引用，
   长期运行会无界增长。`MenuBuilder` 的字段注释写明 *"id 可以复用，只要按钮结果键唯一"*、`token` 就是给回调
   识别会话用的，因此改为：**全局只注册一次**（`sharedMenuId`，`lazy` + `runCatching` 兜底 -1），
   每次 `send()` 生成唯一 `sessionToken` 放进 `MenuBuilder.token(...)`，回调按 `result.token` 路由，
   `close()` 时 `releaseSession()`，同一玩家新开会话时回收旧会话。副作用是**过期点击会被忽略**（比原来更安全）。

### 验证（2026-09-13，X37 基线 + 清空编译缓存的全量重编译）

用两个**仅存在于测试副本**的探针脚本，把打算采用的调用姿势全部写了一遍
（`title/msg/rootWidth/cellPad/optionHeight/label/column/option/space/group/field/check/image/pane/condition/renderPaged/
`MenuResult.stringOrNull·floatOrNull·booleanOrNull``）：

| 场景 | 结果 |
| --- | --- |
| 文件放模块根目录 + 探针 | ❌ `Unresolved reference 'MenuV3'`（证明根目录 `.kt` 对无元数据依赖的脚本不可见） |
| 文件移到 `lib/` + coreMindustry 探针 | ✅ `158/154/149/出错0` |
| 再加 wayzer 模块探针（跨模块使用） | ✅ `159/155/150/出错0` |
| 去掉探针、真实脚本集全量重编译 | 见 `scripts-maintenance.md` 2026-09-13（第二批）条目 |

未覆盖边界：**真实客户端的观感（缩放/适配/图标是否齐全）必须由玩家侧实测**，
本轮只做到"编译通过 + 机制有源码依据"。

### 首个接入：成就页（2026-09-13）

`/achievements` 已改用 `MenuV3` 渲染（`wayzer/user/achievement.kts` 的 `showAchievementPage`）：

- **内容与旧聊天菜单逐字一致**（标题、进度 msg、6 条/页、分页 `<-`/`页/总`/`->`、管理入口、隐藏成就规则都没动）；
- **观感**按要求做成"保守 + 靠中 + 不铺满屏幕"：`fillScreen = false`（原版 `Dialog.show()` 会 `pack()` 后
  `centerWindow()` 居中）、`wrapInPane = false` + 固定高度 `pane("achievementList", 300f)`（列表在内部滚动，
  对话框高度可控）、`rootWidth = 440f`（比默认 520 略窄）；
- 旧实现 `showAchievementMenu`（`PagedMenuBuilder` 聊天菜单）**原样保留**，
  回退只需把命令体里的 `showAchievementPage(player!!)` 改回 `showAchievementMenu(player!!)`；
- 分页状态靠 `MenuV3.sessionState` 保存（`send()` 只清 items/callbacks，不清 sessionState），
  所以 `refresh()` 重发菜单后仍停在同一页；
- 管理入口点击时先 `close()` 再打开原有的旧式管理菜单，避免两种菜单叠在一起。

效果需要真实客户端确认（本环境只能验证编译与冷启动）。

## 若将来要重启这个方向（已按 2026-09-13 结论更新）

1. **从单个只读页面起步**（wiki 最合适）：`MenuV3(player) { title/msg + 一个 pane + 若干 option }`，
   先确认缩放与滚动行为，再铺开到帖子/商店/成就；
2. **图标只用原版 region / `Icon.icons` 名**，必要时显式 `placeholder`；不要引用没同步过的定制贴图；
3. **保留聊天菜单回退**：旧客户端（<160）无法渲染，`MenuV3` 只在 160+ 使用，
   业务脚本要能在渲染失败/无响应时退回原聊天菜单；
4. **不要重写布局代码**：直接用 `MenuV3` 的 `column/group/pane/space`，不要自己算宽度——
   本次失败正是手写布局导致的。
