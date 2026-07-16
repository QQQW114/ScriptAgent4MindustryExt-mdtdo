# 2026-07-16 上游版本复核：Mindustry 159.6、MindustryX B477、ScriptAgent 3.4

## 参考项目状态

- 官方 Mindustry：`参考项目/Mindustry-master`，定位 `v159.6`，提交 `167be4e4a545f62f700185e0934834906f74c1f1`。
- MindustryX：`参考项目/MindustryX-main`，定位 `prerelease-2026.07.15.B477`，提交 `2e9198ab1478ed2270756f7d4a0d942592111ca1`，上游基线为官方 `v159.6`。
- 旧 MindustryX X35 源码快照保留在 `参考项目/MindustryX-X35-archive`。
- ScriptAgent 发布版：`参考项目/ScriptAgent4MindustryExt-3.4.0`，定位 `v3.4.0`，提交 `4a1af8f209a1a339acd38c70eafbe5f7367eff3b`。
- ScriptAgent 开发分支：`参考项目/tmp_ScriptAgent4MindustryExt`，8.0 分支提交 `6f16791563b877656a92f766d5243f816dc80207`，包含 v3.4.0 发布后的 v159 控制台与积分板修复。

## 官方 Mindustry v159.2 至 v159.6 中与本服相关的变化

### 建议跟进的修复

- v159.2：修复炮塔偶尔双重射击；修复 Data Patch 内容错误显示为 Mod 内容。
- v159.3：修复 Data Patch 生成图标加载、塑钢传送带刷屏崩溃、无正常终端时专服崩溃/输出异常，以及部分 159 存档失效。
- v159.3：增加禁用原版自动音乐的地图规则，并把音乐规则加入地图规则界面。
- v159.5：增加当前音乐逻辑变量与音乐音量规则。
- v159.6：修复地图开始前编辑规则时自定义音乐不更新、Data Patch 内容图标生成，并允许地图音乐规则加载 Mod 资产。

这些变化对外部 CP、点歌和服务器稳定性有正面作用，MindustryX B477 已同步到 v159.6。

### 没有解决的问题

- v159.1 到 v159.6 的 `NetServer.sendWorldAndAssets(Player)`、`sendWorldData(Player)` 与资产请求状态机没有实质变化。
- `sendWorldAndAssets` 仍会把在线连接重新切入资产确认和世界加载状态，不是单独发送一首音乐或一个音效的轻量接口。
- 因此运行中调用该方法仍可能导致玩家单位/核心机状态消失、重复进入世界或卡在未完成连接状态。159.6 不能直接解决当前点歌/小音效热同步问题。

## MindustryX B477 中与本服相关的变化

- 已跟进官方 v159.6，并继续保留本服使用的 X API 补丁，包括 `SendPacketEvent`、`LogicBlock.running`、健康变化事件等。切换回 X 端后，上行统计和世界处理器发包检测可恢复，不必继续官方端的 no-op 降级。
- 包含 `fb7d8bd`：修复服务端执行 `LogicBlock$LogicBuild.interactable` 时触发客户端 UI/渲染类初始化的崩溃。这与本服 X35 时手工做过的 headless 热修属于同一问题，上游现在已经正式修复。
- 包含 `2eae4d6`：修复 Data Patch 运行时新增物品/单位造成客户端控制组、核心资源显示等数组越界。对会覆盖/新增单位和物品的外部 CP 有帮助，但它主要修复客户端 UI，并不等于所有运行时 CP 冲突都已解决。
- 包含退出地图资源 UI 崩溃、物流显示等客户端修复。
- B477 是预览版，release notes 为空。当前已按“只升级 X、不升级 SA”的方式完成本地冷启动验证，准备先在生产服观察；不要同时迁移 SA 3.4，以免故障来源混杂。

## ScriptAgent v3.4.0 复核

### 它不是普通兼容更新

v3.4.0 同时进行了以下结构性变化：

- 切换 Kotlin K2 与新 SA Loader。
- 模块识别迁移到目录内 `.metadata`。
- 数据库从 `coreLibrary/DBApi.kts`、`DBConnector.kts` 重组为 `coreLibrary/db` 模块，API 包名改为 `coreLib.db.DBApi`，H2/PostgreSQL 拆成独立脚本。
- `CommandHandler` 从 Kotlin context receiver 写法改为 `CommandContext` 扩展函数写法；自定义 `Commands.Hidden`、技能命令属性等需要迁移。
- 多个 API 文件拆分为 `.api.kt` 与实现脚本，模块和依赖 ID 也发生变化。

当前 MDT 脚本树没有新版 `.metadata`，且技能系统、`betterTeam` 等仍存在旧式 `context(CommandContext)` 自定义处理器。直接更换 v3.4.0 的 JAR/脚本包会产生大量加载或编译错误，社区反馈符合源码变化。

### v3.4.0 发布标签本身仍有 v159 问题

- v3.4.0 发布后，8.0 分支又增加 `6f16791`，用于适配 v159 的 `ServerControl` 控制台字段变化。
- 同期积分板改为使用带 ID 的新版 `Call.infoPopup` 签名。
- 因此即便要做实验，也应以当前 8.0 分支作为参考，而不是只测试原始 v3.4.0 标签。

### `mapAssets` 链路仍不完整

- v3.4.0 为地图脚本增加 `mapAssets: List<DataAsset>`，并在 `DataPatchLoadEvent` 中注入资产。
- 但 `wayzer/maps.manager.kt` 换图后仍执行 `Vars.netServer.sendWorldData(player)`，没有调用官方 v159 的 `sendWorldAndAssets(player)`。
- 结果是在线玩家换到包含新地图资产的地图时，可能只收到世界数据而没有走资产需求流程。
- 直接把这里改成 `sendWorldAndAssets` 也不能草率完成：必须按官方 `WorldReloader` 的完整玩家 reset、连接状态和确认流程处理，否则会复现核心机/单位消失问题。

## 当前建议

1. **MindustryX B477 已进入生产观察，但不要同时升级 SA。** 本地清缓存冷启动结果为 `201` 个脚本、`197` 个加载成功、`144` 个启用成功、`0` 个错误；生产服继续使用 SA 3.3.2，重点验证换图、CP、音频、上行统计、逻辑保护及玩家进出服。
2. **生产服暂不升级 SA 3.4。** 它需要一次明确的迁移工程，而不是复制新 JAR；至少要先生成/维护 `.metadata`、迁移 Command API、数据库模块和控制台/积分板兼容。
3. **运行中音频/CP 热同步继续按高风险功能处理。** 159.6 与 X B477 均没有提供轻量单资产同步接口；预置资产可正常随首次连接发送，运行中新增资产应优先要求玩家重进。
4. 若后续迁移 SA 3.4，应在独立副本中分阶段进行：先替换 Loader 与基础模块并修到零加载错误，再迁移 MDT 自定义模块，最后测试数据库和全功能，不要直接覆盖正式服。

## B477 本地验证记录

- 运行 JAR：`server-2026.07.15.B477.jar`；ScriptAgent：`3.3.2`。
- 在移动旧脚本缓存后重新编译，服务端正常开服并加载地图，最终汇总为：`共找到201脚本,加载成功197,启用成功144,出错0`。
- `SendPacketEvent` 可用，X 端上行统计和逻辑包检测链路恢复；没有观察到 B477 新增的脚本编译或加载错误。
- `syncThrottle.kts` 的 X35 单连接快照节流接口在 B477 中已不存在/变更，因此脚本安全降级关闭。这个降级只影响实验性同步频率接管，不影响压力检测、发包统计和其它清理措施。
- 主动停服时 SA 3.3.2 的脚本集中卸载出现若干 3 秒超时；该现象发生在 `Shutting down server` 之后，不应误判为正常运行卡顿。
