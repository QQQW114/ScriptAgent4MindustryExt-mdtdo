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

## 运行方式

加载、热重载和卸载不再只重放补丁字符串，而是通过 v159 `DataManager` 重建当前完整资产集合：

1. 保留当前地图、存档、服务器预置资产和其他脚本加入的资产；
2. 排除旧版本的同一个外部包；
3. 合并当前所有已加载外部包；
4. 统一加载 Patch、Content、Bundle、Sprite、Sound、Music；
5. 检查 Patch/Content 错误和外部资产缓存；
6. 修复已有建筑模块、电网、炮塔弹药、建筑血量和单位基础属性；
7. 通过 `worldResyncCoordinator` 串行向在线玩家重发世界与资产。

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
- 与当前地图、服务器资产或其他外部包发生路径/名称冲突时拒绝加载，避免静默覆盖；
- 同一时刻只允许一个加载/卸载操作；
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
