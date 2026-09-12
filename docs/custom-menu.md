# 自定义菜单（160.1）：尝试与回退记录

> 状态：**已回退（不采用）**。
> 时间线：2026-09-12 实现并接入 wiki / 帖子 / 商店 / 成就四个入口 → 同日按用户实测反馈**全部回退**。
> 本文档保留"为什么回退"与实现期的技术踩点，避免后续再次投入同一方向。

## 结论

Mindustry 160.1 的服务端下发自定义菜单（`mindustry.ui.builder`）在本项目**不适用**，
四个入口已全部恢复为原有的聊天式菜单。回退原因（用户实测，2026-09-12）：

1. **按钮缩放问题** —— 组件尺寸在客户端表现异常；
2. **界面适配问题** —— 布局无法稳定适配；
3. **缺失贴图** —— 需要的贴图在客户端不存在。

用户原话概括："实际几乎全是问题"。

## 回退了什么

- 删除新增库文件：`coreMindustry/lib/customMenu.kt`（会话层）、`coreMindustry/lib/customMenuParts.kt`（渲染件）。
- `wayzer/user/shopList.kts`、`wiki.kts`、`forumPosts.kts`、`achievement.kts`
  四个脚本整体还原到接入之前的版本（即 `3a0e5ef` 时的内容），保留原有聊天菜单实现。
- 复测：冷启动 `共找到158脚本,加载成功154,启用成功149,出错0`，与接入前完全一致。

## 上游能力（留档，供将来评估）

- 下发：`Call.menuBuilder(con, menuId, token, title, hideOnClick, hideExisting, fillScreen, NodeBuilder)`
- 组件：`UiBuilder` 静态工厂 `table()/label()/button()/imageButton()/field()/check()/slider()/space()/buttonTable()/pane()/stack()`
- 回传：`Call.menuBuilderChoose(player, menuId, MenuResult)`；服务端 `Menus.menuBuilderListeners.get(menuId).get(player, result)` 派发，
  同时 fire `EventType.MenuBuilderOptionChooseEvent`
- `MenuResult` 字段：`token`、`result`（被点按钮 id，关闭时为 null）、`values`（输入框/滑条/开关的值）

**关键约束**：`menuId` 是**客户端 `menuBuilderListeners` 数组的下标**，不是业务编号；
`Menus.registerMenuBuilder` 只增不删，所以每次下发都要占一个新下标（原实现用"监听器池 + 全局递增 id + token 校验"处理）。

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

## 若将来要重启这个方向

先解决三个前置问题再动手，否则会重复本次的失败：

- **贴图来源**：确认目标贴图在客户端一定存在（只用原版/基础内容自带的 region，或先验证定制贴图的同步链路）；
- **尺寸与适配**：先在单个只读页面上做最小验证（一个 table + 一个 label + 一个 button），确认缩放与布局行为后再铺开；
- **兼容性**：旧客户端无法渲染该菜单，需要能判定客户端能力并保留聊天菜单回退。
