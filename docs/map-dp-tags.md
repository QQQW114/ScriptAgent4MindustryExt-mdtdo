# 地图 DP 标签

在**地图简介**里加入标签，进入该地图时由 `wayzer/map/mapDpTags` 自动处理外部DP（`scripts/external-cp/` 下的 CP/DP）。

> 标签由 `wayzer/maps` 的 `applyDescriptionTags` 解析：格式必须是 `[@标签名]` 或 `[@标签名=值]`（方括号、@、标签名只含字母数字）。

## 标签

### `[@dp=文件名]`：指定本局加载的DP

```text
[@dp=赛克兰特高阶科技.zip]
```

- 可以写外部CP文件名、去掉扩展名的名字、显示名或列表编号；
- 多个用逗号/`|`/`;` 分隔：

```text
[@dp=包A.zip,包B.zip]
```

- 进入地图并完成世界加载后，自动清理当前已加载的DP，再逐个加载指定DP；
- 本局手动 `/externalcp load` 也只能加载这些允许的DP，其它会被拒绝；
- 指定文件不存在时只记日志/提示，不阻断地图。

### `[@nodp]`：本局禁止所有DP

```text
[@nodp]
```

等价写法：`[@dp=none]`、`[@dp=off]`、`[@dp=false]`、`[@dp=0]`。

- 进入地图后卸载已加载的全部DP；
- 本局 `/externalcp` 命令、管理菜单、投票加载均会被拒绝；
- 换到无该标签的地图后策略自动恢复。

## 换图启动参数（Minecraft 风格 `@map[...]`）

除地图简介静态标签外，`/vote map`、`/vote nextmap` 与管理员 `/host` 均支持在地图 ID 后附加单个 `@map[...]`（别名 `@opt[...]`）参数块，仅对本次换图生效，不修改地图文件：

```text
/vote map 114 @map[dp=1,unlimited=true,pure=false]
/vote map 114 @map[dp="亚龙组合包 (1).zip",dp=2,flood=true]
/vote nextmap 114 @map[dp=1;2,!pure]
/host 114 @map[nodp=true,unlimited=true]
```

### 参数规则

- **语法底座**：复用 `coreMindustry/lib/selectorSyntax.kt`（引号与方括号内空格不会拆断参数，支持 `"..."`/`'...'` 与转义）。
- **`dp=`（外部 DP）**：支持 `/externalcp list` 列表编号、无后缀显示名与完整文件名；多包可重复写 `dp=1,dp=2` 或用 `;`/引号内 `,` 分隔。文件名不得包含 `[]=,;|`。
- **`nodp=true` / `dp=none` / `dp=off`**：本局显式禁用外部 DP；若目标地图自身简介或 `.msav` 内嵌规则已带 `@nodp` / `@dp=off|none|false|0`，则拒绝通过 `dp=` 附加 DP。
- **地图标签开关**：统一由中立注册表 `coreMindustry/lib/mapTags.kt`（`MapTagRegistry`）做大小写不敏感解析与同义词分组，支持 `标签=true|false`、`标签`（启用）与 `!标签`（禁用）：
  - `@pure`（纯净模式）、`@Unlimited`（无限资源）、`@flood` / `@floodV2`（洪水模式，禁用时同时移除两者）、`@hybrid`（杂交模式）、`@noSkills`（仅禁普通/2级/商店技能）及后续新增的 `mapScript/tags/` 脚本。
- **投票门槛**：`/vote map` 与 `/vote nextmap` 不带参数或 `@map[]` 为空时保持 **51%** 同意；带任意 DP 或标签参数时提高至 **80%** 同意。

## 注意

1. **加载时机**：地图标签的自动加载发生在 `MapLoadFinishedEvent`（世界已经载入之后），不是地图解析之前。
   如果地图本身依赖某个DP的方块/单位才能正常载入，请改用地图脚本的 `mapPatches`/内置DP，或先手动加载该DP再换图。
2. 换图参数在 `MapChangeEvent` 与 `WorldLoadBeginEvent`（`SaveVersion.readRules` 之后、`WorldLoadEvent` 之前）同步到 `state.rules.tags`，因此移除地图自带标签（如 `pure=false`）或在已有地图脚本的地图上追加标签（如 `unlimited=true`）均会按最终标签生效，换图后自动恢复。
3. 地图脚本加载与DP标签互不影响：`@pure`、`@Unlimited`、`@floodV2` 等地图脚本标签可同时存在。
4. 本功能只影响外部CP（`scripts/external-cp/`）加载；不会删除任何DP文件，也不会修改地图存档。
5. 相关日志：
   - `换图启动参数已应用: map=...`
   - `地图标签加载DP成功/失败 target=...`
   - `地图标签禁止DP...`
   - 游戏内 `/externalcp list` 会显示本局策略。
