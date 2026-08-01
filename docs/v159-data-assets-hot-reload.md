# v159 Data Assets / 外部 CP 热重载

## 支持格式

外部 CP 目录仍为：

`mdtserver/config/scripts/external-cp/`

当前支持：

- 单文件 `.json` / `.hjson` / `.json5`：作为一个 Data Patch 加载，继续兼容旧 CP 预处理。
- `.zip`：按 Mindustry v159 Data Assets 包加载，可包含：
  - `patches/`：JSON/HJSON/JSON5 数据补丁；
  - `content/<items|blocks|liquids|status|units|weather>/`：运行时新增内容；
  - `bundles/`：语言包；
  - `sprites/`：PNG 贴图；
  - `sounds/`：MP3/OGG 音效；
  - `music/`：MP3/OGG 音乐。

ZIP 可以直接以这些目录为根，也允许外层再包一层同名项目目录。

## `/cp` 管理入口与快速加载

`/cp` 已兼容 v159 完整 Data Assets，不再只把“数据包”理解为属性 Patch：

- `/cp`：游戏内打开总览菜单；控制台显示资产与 Patch 摘要；
- `/cp patches`：查看可卸载的属性 Patch 列表；
- `/cp dp [编号]`：列出或查看 `DataManager.getAllAssets()` 中的 Patch、Content、Bundle、Image、Sound、Music；
- `/cp files`：列出 `scripts/external-cp/` 下服务器可加载的 CP 文件；
- `/cp server`：游戏内打开服务器 CP 管理菜单；
- `/cp load <文件名|编号>`：管理员不经过投票，直接快速加载/热重载服务器 CP；
- `/cp unload <Patch编号>` / `/cp disable <Patch编号>`：仍只使用属性 Patch 自己的编号，避免与 DP 总表编号混用。

`/cp load` 不另写一套加载器，而是复用外部 CP 系统原有的路径校验、ZIP 防护、互斥、完整失败回滚、建筑/单位兼容修复和 `worldResyncCoordinator` 串行重同步。玩家投票入口 `/vote cp` 保持不变。

## 运行方式

加载、热重载和卸载不再只重放补丁字符串，而是通过 v159 `DataManager` 重建当前完整资产集合：

1. 保留当前地图、存档、服务器预置资产和其他脚本加入的资产；
2. 排除旧版本的同一个外部包；
3. 合并当前所有已加载外部包；
4. 在 v159 注销旧 Content 前，先解除玩家对旧 DP 单位的附身，并清除场上对旧动态内容的运行态引用；
5. 统一加载 Patch、Content、Bundle、Sprite、Sound、Music；
6. 检查 Patch/Content 错误和外部资产缓存；
7. 修复已有建筑模块、电网、炮塔弹药、建筑血量和单位基础属性；
8. 通过 `worldResyncCoordinator` 串行向在线玩家重发世界与资产。

卸载 ZIP 时会同时撤销新增内容和资源，不只删除其中的 patches。

## 防护与失败回滚

默认防护：

- 文件必须位于外部 CP 目录内，拒绝路径越界；
- ZIP 条目拒绝绝对路径、`..`、空路径和非法路径；
- 最大 2048 个 ZIP 文件、单资产 32MB、累计解压 128MB；
- 最大压缩比 200:1，阻止常见 ZIP 炸弹；
- 单包最多 4096 个 Data Assets；
- PNG 必须具有合法文件头，尺寸不得超过 v159 的 2000×2000 上限；
- 不支持的目录或扩展名不会执行，只记录警告；
- 同一包内重复路径直接拒绝；
- 同一包内重复名称原则上拒绝；唯一例外是恰好一张 `sprites/generated/**/<name>.png` 与一张普通同名贴图并存。该组合是 v159 原生生成资产格式，保留两项并按原版规则优先使用 `generated` 贴图，同时记录加载警告；多张 `generated`、第三张同名图或其他同名资产仍直接拒绝；
- 与当前地图、服务器资产或其他外部包发生路径/名称冲突时仍拒绝加载，不允许借 `generated` 优先级跨包覆盖；
- 同一时刻只允许一个加载/卸载操作；
- v159 `DataManager.load/reloadPatches` 会重建当前 ContentAsset 对象；每次重载前都会保守清理全部旧动态 Content 的运行态引用，而不是只处理本次被卸载的一个包；
- 原版 `Unloader.allItems` 只在方块初始化时生成一次静态物品快照。动态物品加载/卸载后会立即把该缓存刷新为当前 `Vars.content.items()`，并清除仍指向已注销物品的卸载器筛选配置，避免空筛选卸载器把旧物品传给相邻单位工厂后触发 `ItemModule.get` 越界；
- 玩家若正在附身 DP 新增单位，会在内容注销前先 `clearUnit()`，随后删除旧 DP 单位，防止完整世界同步写出客户端已无法解析的内容 ID；
- 清理范围还包括 DP 建筑/环境方块/地板/覆盖层、单位与建筑内的自定义物品/液体/状态/载荷/配置/建造计划、自定义洼地/天气、波次/规则/队伍引用；动态 Content 重载时保守清理现存子弹；
- 清场阶段只修改服务端世界，不对每个方块批量发送 `setNet`，成功后由统一完整重同步更新客户端，避免卸载时反而放大上行峰值；
- 清理完成后会验证玩家、单位、建筑、地图格等不再引用旧 Content；任何必要引用无法清除时直接阻止内容注销，优先保留旧 Content 注册状态，不进入“无效对象已下场、但场上仍有引用”的全员掉线状态；
- 失败明细有上限，大量地图格/实体同时失败时不会让防护器自身无限堆积错误文本；
- Patch 或新增 Content 标记为错误、资源缓存缺失、建筑/电网校验失败时，整包视为失败；
- 失败后重新加载操作前保存的完整 Data Assets，并再次执行世界兼容修复；
- 成功和回滚后的玩家同步都进入统一串行队列，避免同时发起多份世界流。

相关配置位于 `wayzer/map/externalCpHotReload` 的 ScriptAgent 配置中：

- `hardExternalCpBytes`
- `maxZipEntries`
- `maxZipEntryBytes`
- `maxZipExpandedBytes`
- `maxZipCompressionRatio`
- `maxExternalCpAssets`

## 已知边界

- 热重载仍然会修改全局内容对象，无法保证任意两套相互冲突的 CP 在玩法语义上兼容。
- 客户端接收新增贴图、音乐和内容仍需完整世界/资产重同步，不能当作轻量网络操作。
- JVM 进程级崩溃无法由脚本内部回滚；启动监督器默认会自动重启服务端，但最近一次自动保存仍决定可恢复到哪一刻。
- 若压缩包依赖 Mod 自定义 Java 内容类，v159 Data Patcher 的安全限制可能拒绝加载；外部 CP 热重载不绕过类解析限制。

## 验证记录

2026-07-22 使用包含 Patch、Content、Bundle、Sprite 的临时 v159 ZIP 完成实际加载、状态查询、卸载验证：

- ScriptAgent：156 个脚本，加载 152，启用 148，错误 0；
- ZIP：`patches=1, content=1, bundles=1, sprites=1`；
- 加载成功；
- 卸载成功并恢复对应补丁、内容和资产；
- 临时测试包及生成的资产缓存已清理。

2026-07-22 使用真实外部包 `惊鸿.zip` 复现并修复同包生成贴图冲突：

- 包含 `content=1, sprites=10, sounds=4`，解压后约 129.5KB；
- 同时提供 `sprites/惊鸿-preview.png` 与 `sprites/generated/<content-hash>/惊鸿-preview.png`；
- 对照 Mindustry v159.7 `DataImagePacker`，确认原版会优先装包 `generated` 贴图并跳过普通同名贴图；
- 实际加载、再次热重载和卸载均成功，新增方块注册为 `dp-惊鸿`，运行态选中的同名图路径为 `generated/.../惊鸿-preview.png`，4 个音效也进入资产表；
- 卸载后 `dp-惊鸿` 消失，已加载外部 CP 列表为空；
- 额外构造两个外部包的同路径覆盖，第二包仍被拒绝；构造“一张普通图+两张 generated 同名图”，读取阶段仍被拒绝；
- 冷启动结果仍为 156 个脚本、加载 152、启用 148、错误 0。

2026-07-30 补充运行态旧 DP Content 卸载前清理：

- 生产现象为“玩家附身 DP 新增单位后卸载 DP，全服玩家被踢出”；根因按 v159 内容注销与完整世界同步时序定位为场上仍引用旧 `UnitType`/Content ID；
- `coreMindustry/contentsTweaker.kts` 新增中央运行态清理器，`externalCpHotReload.kts` 的加载、热重载、卸载、失败回滚和脚本停用清理均在 `DataManager.load` 前调用；属性 Patch 的 `reloadPatches` 链路也使用同一保护；
- 受控过程中用真实包 `亚龙组合包 (1).zip` 确认可生成并附身 `dp-亚龙`，首次卸载进入清理器时暴露了普通建筑 `getPayloads()` 可返回 `null` 的 v159 差异，已改为可空处理；
- 按维护者要求，修复后不再继续执行“假玩家附身后实际卸载”的破坏性回归；最终本地冷启动为 155/151/146/0，两个修改脚本均编译、加载、启用成功。这不等价于生产多人卸载已验证，首次上线仍应在低人数窗口观察清理摘要中 `failures=0`。

2026-08-01 补充动态物品/液体模块容量保护：

- DP 新增物品不会自动扩展已经存在或由地图稍后重新加入的 `ItemModule`。当物品数由22变为27时，核心邻接更新会按新ID 22读取旧22长数组并触发 `ArrayIndexOutOfBoundsException`；生产堆栈已确认发生在放置建筑触发的 `CoreBlock$CoreBuild.onProximityUpdate`，不是 `/sync` 导致。
- 加载、卸载和失败回滚现在会就地调整 `ItemModule.empty`、活跃建筑及 `tile.build` 中暂时离组建筑的物品数组，并同步维护 `total`、`takeRotation` 和流量统计；`LiquidModule` 同步调整数组容量并修复失效 `current`。
- 动态 Content 注销前先按当前内容数量做一次容量预修复，清理器读取动态物品/液体时另有边界判断；`DataManager.load` 后再完整修复，并开启默认30秒的逐tick轻量守护覆盖迟到的活跃建筑。电网重建不进入高频守护。
- 地图24662加载真实 `仙古测试2.9.1.zip` 后，32秒与44秒检查均为 `items=27, ItemModule.empty=27, bad=0`，4个核心显式邻接更新正常；卸载后1/4/10秒均恢复 `items=22, ItemModule.empty=22, bad=0`，未见越界、卸载失败或回滚失败。冷启动仍为155/151/146/0。

2026-08-02 补充原版卸载器静态物品缓存保护：

- 生产日志在 DP 全部卸载后出现 `ItemModule.get -> UnitFactoryBuild.acceptItem -> UnloaderBuild.isPossibleItem/updateTile`，同时清理摘要已有 `itemArrays=892`，据此排除物品模块容量未缩回的旧问题。
- 对照 v159 `Unloader.java` 确认：无指定筛选物品的卸载器会遍历仅在 `Unloader.init()` 缓存一次的静态 `allItems`。DP 加载后该数组包含新增物品 ID，卸载后若不刷新，单位工厂会用已注销物品 ID 读取已恢复为原版长度的 `ItemModule`，从而越界。
- `coreMindustry/contentsTweaker.kts` 现统一扫描 `Groups.build` 与 `tile.build` 可达建筑，反射刷新 `Unloader.allItems`，校验对象身份与数组长度，并清除残留旧 `sortItem`；属性 Patch、脚本启用、世界加载和外部 DP 的正常/回滚 `DataManager.load` 链路都会刷新。
- 本地冷启动为 `共找到156脚本,加载成功152,启用成功147,出错0`。同一命令 Socket 内加载真实 `仙古测试2.9.1.zip` 时记录 `items=27, unloaderItems=22->27`，随后全部卸载记录 `items=22, unloaderItems=27->22`、`failures=0`；卸载后继续观察20秒，未出现 `ArrayIndexOutOfBoundsException`、`UnitFactoryBuild.acceptItem` 或 `UnloaderBuild.updateTile`。按既定边界未执行附身 DP 单位的破坏性卸载测试。
