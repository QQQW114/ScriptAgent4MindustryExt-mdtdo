> 状态：**未完成实机测试**。本文档记录设计、临时验证结果和待协作者验证的风险，不代表生产可用。
>
> 制作者：**Holy和他的dsh**

# `/vote map` 双大括号参数

## 语法

```text
/vote map <地图ID> {[@dp=文件名1,文件名2]} {[@标签=true|false]}
```

示例：

```text
/vote map 114514 {[@dp=靤.zip,面包.zip]}{[@Unlimited=true][@pure=true]}
```

- 第一块：给本次换图添加 `@dp` 标签，由现有 `mapDpTags` 在本局世界加载后自动加载指定外部 DP。
- 第二块：给本次换图添加/移除已注册的地图标签。`true` 添加，`false` 移除。
- 没有 `{}`：与旧 `/vote map <地图ID>` 完全一致。
- `{}` 内容为空：忽略该块；`{}` 本身仍作为占位。
- 最多两个 `{}`；第三个起直接报错，不发起投票。
- 大括号外不能有多余字符。

## 约束

### 第一块

- 只接受 `[@dp=...]`，不接受 `@cp`、`@nodp` 或任意其它标签。
- 若目标地图简介自带 `[@nodp]`、`[@nodp=...]`、`[@dp=none]` 或 `[@dp=off]`，命令阶段直接拒绝，避免投票通过的 DP 被地图策略静默忽略。
- 文件名必须是 `config/scripts/external-cp/` 中实际存在的 CP/DP；校验使用默认目录名，若修改了 `externalCpHotReload` 的目录配置，需要同步调整。
- 文件名可用单引号或双引号包裹；引号内的空格会保留。
- 由于地图标签值本身以逗号分隔，文件名内不能包含 `,`、`;`、`[`、`]`、`=`、`|`。
- 本功能**不会**在换图前预加载 DP；DP 仍按现有流程在 `MapLoadFinishedEvent` 加载。地图本身依赖 DP 内容才能载入时，本功能不适用。

### 第二块

- 只接受 `[@标签=true]` 或 `[@标签=false]`。
- 标签必须是服务端已注册的地图标签（查找 `mapScript/tags/<标签名>`，并兼容 `@floodV2`、`@Unlimited` 等别名）。
- 冷启动时 `TagSupport.fallbackTags` 必须包含 `@Unlimited -> mapScript/tags/unlimited`，否则标签脚本会因大小写路径不一致而不加载。
- 不能写 `@dp`、`@cp`、`@nodp`；CP 标签只能在第一块以 `@dp` 形式出现。
- 当前已注册的常用标签包括 `@pure`、`@Unlimited`；`@Unlimited` 通过本插件新增的 `mapScript/tags/unlimited.kts` 注册，本局开启无限资源。
- 标签只作用于本局，不写回地图文件/地图简介；下一次换图若不传参数，仍按地图自身标签执行。
- 未知标签、重复标签、同一标签同时 true/false 都会拒绝整条命令。

## 执行流程

1. 解析地图 ID 与最多两个大括号；失败时不创建投票。
2. 校验地图筛选系统、DP 文件存在性、标签注册情况。
3. 创建换图投票；无参数/空块保持 51%，带 DP 或标签参数时为 80%；投票描述中列出本次 DP 与标签变更。
4. 投票通过后，设置一次性 pending 参数，调用 `MapManager.loadMapSync`。
5. `MapChangeEvent`（优先级 Before）把 `@dp` 和标签变更写入本局 `Rules.tags`；随后现有 `mapDpTags` 读取 `@dp`，在 `MapLoadFinishedEvent` 加载 DP。
6. 地图脚本模块根据最终 `rules.tags` 加载 `@pure`、`@Unlimited` 等标签脚本。
7. 无论加载成功与否，pending 参数都会清理，不会泄漏到下一次换图。

## 已知限制与风险

- **时序**：`@dp` 不是换图前预加载；地图加载本身依赖 DP 时无法用本命令解决。
- **投票门槛**：无参数/空块保持旧 51%；带 DP 或标签参数时提高到 80%，避免 51% 附加 DP/无限资源绕过 `/vote cp` 的严格门槛。
- **标签权限放大**：第二块能开关任意已注册地图标签；标签脚本可能改变规则/技能/资源。当前只做注册表校验；带参数投票已提高到 80%，仍没有管理员二次确认。
- **DP 失败不回滚换图**：DP 不存在会在命令阶段拒绝；但投票通过后文件被删除/损坏时，DP 加载失败只记录日志，地图仍会切换。
- **不写回地图**：标签/DP 只影响本局；不会持久化到地图简介。
- **引号与空格**：命令入口仍按空格分词后再拼接，连续多个空格可能被压成一个；引号内的普通空格可用。文件名含连续空格时建议先改名。
- **标签大小写**：`@Unlimited` 与 `@unlimited` 都映射到 `mapScript/tags/unlimited`；前者依赖 `TagSupport.fallbackTags` 显式映射，后者走常规路径。
- **`@Unlimited` 的恢复**：标签脚本记录进入前的 `infiniteResources`，换图时恢复；若其它系统在局中修改该字段，恢复值以进入时为准。
- **实现压缩**：2 个插件 `voteMap.kts` 与 `unlimited.kts`；另在 `TagSupport.kt` 的 fallback 增加 1 行 `@Unlimited` 映射，未改 `externalCpHotReload.kts`。
- **并发换图**：pending 参数只匹配目标地图 ID；期间被管理员换到其它地图时不会误应用，但本次投票的附加参数也不会生效。

## 未验证风险汇总

- 真实客户端输入、引号与空格经过命令分词后的最终行为。
- 真实投票通过后 `@dp` 是否实际触发 `mapDpTags` 加载。
- DP 在投票后损坏/被删除时的失败路径，以及是否留下半加载状态。
- `@pure=false` 的卸载顺序和旧状态残留。
- `@Unlimited` 在真实 `/vote map` 投票链路中的生效与恢复。
- 投票取消、地图筛选、地图加载失败时 pending 参数清理。
- 带 DP/标签的投票为 80%，无参数/空块仍为 51%。
- CP 校验使用默认 `config/scripts/external-cp` 目录，修改目录配置后需同步。
- 连续多个空格的 CP 文件名会被压缩。
- `@Unlimited` 依赖 `unlimited.kts` 正常加载，未加载时会被拒绝。

完整步骤见 [实机测试计划](vote-map-params-test-plan.md)。

## 测试重点

1. 旧命令 `/vote map <ID>` 行为不变。
2. 空块、单块、双块、超过两块、块外多余字符。
3. 引号文件名（含空格）与不存在的 CP。
4. `@dp` 标签在 `MapChangeEvent` 后、`MapLoadFinishedEvent` 前可见；DP 实际加载。
5. `@pure=true/false`、`@Unlimited=true/false` 的添加/移除与局后恢复。

完整实机测试步骤见 [vote-map-params-test-plan.md](vote-map-params-test-plan.md)。
6. 未知标签、重复标签、第二块 CP 标签均被拒绝。
7. 投票失败/地图筛选拦截/地图加载失败时 pending 清理。
8. 无参数/空块 51% 与旧行为一致；带参数为 80%。

## 后续重构建议（低优先级：别名表双份）

当前 `voteMap.kts` 的 `mapTagScriptAliases` 与 `TagSupport.fallbackTags` 各自维护一份别名，新增别名时容易漏改。建议后续单独提一次小重构：

1. 在 `scripts/coreMindustry/lib/mapTagAliases.kt` 新增中立的别名表与解析函数：

```kotlin
object MapTagAliases {
    private val byTag = mapOf(
        "@floodV2" to "mapScript/tags/flood",
        "@Unlimited" to "mapScript/tags/unlimited",
    )
    fun resolve(tag: String): String? = byTag[tag]
}
```

2. `TagSupport.findTagScript` 改为先用 `MapTagAliases.resolve(tag)`，未命中再走 `mapScript/tags/<name>` 约定。
3. `voteMap.isKnownMapTag` 改为使用同一个 `MapTagAliases.resolve`，不再维护第二份映射。
4. 不要把这张表放进 `mapScript` 再让 `voteMap` 依赖 `mapScript`；此前该依赖会导致 `voteMap` 在地图加载时被停用。`coreMindustry/lib` 是更安全的中立位置。
5. 增加一条回归测试：冷启动后分别用 `@floodV2`、`@Unlimited` 触发标签脚本加载，确认不再依赖 `knownTags` 是否已被填充。

本轮先记录方案，不扩大 PR #11 的改动范围。
