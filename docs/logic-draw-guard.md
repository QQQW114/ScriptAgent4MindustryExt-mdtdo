# 逻辑绘图/显示器方块开关

对应脚本：`mdtserver/config/scripts/wayzer/map/logicDrawGuard.kts`

## 目的

用于处理服务器列表审核要求中的不适当内容风险，例如 NSFW 显示器/画布图、辱骂性文字或恶意逻辑绘图。

脚本通过修改当前 `state.rules.bannedBlocks` 来开关绘图相关方块。

## 指令

权限：

- `wayzer.admin.logicDraw`：逻辑绘图开关，默认 `@admin`。
- `wayzer.admin.blockBan`：本局单方块禁用/解禁，默认 `@admin`。

- `/logicdraw status`：查看当前策略和受控方块。
- `/logicdraw off`：禁止逻辑绘图/画布/显示器，向当前规则加入相关方块禁用。
- `/logicdraw on`：允许逻辑绘图/画布/显示器；如果地图自身原本禁用了这些方块，会尊重地图原规则，不强行解除地图自带禁用。
- `/logicdraw roundoff`：仅本局禁止逻辑绘图/画布/显示器，换图后自动恢复全局策略。
- `/logicdraw roundon`：仅本局允许逻辑绘图/画布/显示器，换图后自动恢复全局策略。
- `/logicdraw roundclear`：清除本局覆盖，恢复全局策略。
- `/blockban`：列出当前 `bannedBlocks`。
- `/blockban ban <方块ID>`：本局单独禁用某个建筑方块。
- `/blockban unban <方块ID>` 或 `/blockunban <方块ID>`：本局单独解禁某个建筑方块。
- `/blockban status <方块ID>`：查看某个方块当前是否被禁用。

别名：

- `/逻辑绘图`
- `/drawblocks`
- `/displayart`
- `/banblock`
- `/blockrule`
- `/blockunban`

## 当前受控方块

- `canvas`
- `large-canvas`
- `logic-display`
- `large-logic-display`
- `tile-logic-display`

## 持久化

开关状态保存到数据库设置项：`map.logicDraw.enabled`。

默认配置项：`defaultLogicDrawEnabled=true`，即不主动改变原玩法；如要默认禁用，可在脚本配置中改为 `false` 或在游戏内执行 `/logicdraw off`。

`roundoff`/`roundon` 与 `/blockban` 的单方块禁用/解禁只影响当前局，地图切换或 Reset 后清空。单方块解禁可以临时覆盖地图原本的 `bannedBlocks`，属于管理员高危操作。
