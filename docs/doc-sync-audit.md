# 文档偏移巡检报告（2026-10-17）

> 背景：用户明确要求"**文档不能偏移**"。2026-10-17 当天有大量已完成变更（B509/v160.7 基线、SA KDoc 截断 bug、
> 调试设施、DP 兼容改造最终版、IP 风控审计与用户决策、惩罚体系进行中）可能没有落到 docs，本报告记录巡检范围、
> 发现的偏移、已修正项，以及留给其它 owner 的待办。
>
> 口径：只写有依据的内容（代码/提交/日志/实测），不确定的标"**未确认**"；本轮不修改脚本、不 commit、不重启服务器
> （运行态核对只用只读命令 `scriptdiag count`）。写作用域：`current-issues.md`、`project-memory.md`、
> `deploy-sync-checklist.md`、`v159-data-assets-hot-reload.md`、`official-v159-compat.md`、`security-guard.md`、
> `account-system.md` 与本报告。

## 1. 方法

- 以 2026-10-17 的变更清单为线索，对照 **git 提交**（`git log/show`）、**运行态**（`scriptdiag count`、`log-0.txt`、
  `config/logs/console-*.log`、`server.properties`、JAR/SA 插件 SHA-256）、**代码实现**
  （`externalCpHotReload.kts`、`scriptDiag.kts`、`start-server.ps1` 等）逐项核对；
- 文档侧按"版本 / 日期 / 工具 / 指令 / 完成状态 / 决策"维度扫描过期事实，重点核对与当天变更相关的文件；
- 行号按 2026-10-17 当前工作区文件；提交号以 mdtdo 仓库为准；DP 代码行号以 `729dff3` 后的脚本为准。

## 2. 结论摘要（偏移簇）

1. **版本基线整体过期**：`project-memory.md` / `current-issues.md` / `deploy-sync-checklist.md` / `official-v159-compat.md`
   还停在 **B495 / v160.3**（个别地方更早到 B480 / v159.7），而实际基线已是 **B509 / v160.7**（2026-10-16 切换，
   2026-10-17 17:38 冷启动 `159/155/150/出错0` 验证）。
2. **DP 兼容改造最终版未落文档（本次最危险的偏移）**：`v159-data-assets-hot-reload.md` 自 2026-08-03 后未更新，
   「防护与失败回滚」仍写"重复路径直接拒绝 / 同名冲突整包拒绝 / 补丁失败整包失败"，与 `.kts` 现状
   （`729dff3` 已放宽为警告与跳过）**语义相反**；照旧文档排查会误判"整包失败"。
3. **调试设施与 SA bug 未进全局状态页**：`scriptdiag`、启动器控制台逐行落盘 `console-<时间>.log`、
   KDoc 截断 bug / 上游 issue #50 只落在 `agent-debug-experience.md` / `dev-workspace-guide.md`，
   `current-issues.md` / `project-memory.md` 没有入口指引。
4. **IP 风控决策与惩罚体系状态缺失**：两份全局文档没有记录 IP 审计结论与用户决策（方案 C、否决行为规则），
   也没有惩罚体系"进行中、文件归 punish-docs"的状态指针，后续 Agent 容易重复提议或把设计当已实现。
5. **`scripts-maintenance.md` 2026-10-17 批次状态偏移（本轮未改，归 Lead / punish-docs）**：第十七批仍写
   "待小步重做 / 重做中（17:22 快照）"，而 `729dff3`（17:34）已完成全部 5 步；调试设施/启动器修复/IP 审计
   也没有独立批次。建议要点见 §5。

## 3. 已修正清单（本轮）

| 文件 | 修正内容 | 依据 |
|---|---|---|
| `project-memory.md` | 更新时间；日志优先级新增 `console-<时间>.log`；Socket 调试新增 `scriptdiag`；§1 快照 5 行（基线/JAR/时点/SA 运行时+issue #50/最近证据）；§3 版本口径 + 新增「文档防偏移」与"已明确否决"；§5 状态矩阵 4 行更新 + 新增「惩罚与检举体系」行；§6 时间线补 2026-08-14～10-17 并恢复被覆盖的 08-13 条目、按时间排序；§7 新增 4 条"不应恢复/勿提议"；§8 未决事项更新 + 新增 3 条；§9 接手提示加排查路径；§11 索引加 3 份新文档并修正过期指向；§12 更新记录补 4 条 | `cdc8b17`/`729dff3`/`7364043`/`7bec551`/`a739f99`；`scriptdiag count`；JAR SHA；日志 |
| `current-issues.md` | 头部更新时间；§3 的 v160.3 表述改为"v160.x（2026-09 在 v160.3 核对，未逐条复验）"；§5 改为「B509/v160.7 缺客户端侧验证」；09-27 交接状态标注为**历史快照**；文末新增「2026-10-17 交接状态与新增待办」（当前状态/4 项待办/已完成与决策） | 同上 |
| `deploy-sync-checklist.md` | JAR 行 → B509；执行顺序第 5 步计数 → `159/155/150/出错0`；客户端兼容说明 → v160.7；§4「最后一次变动」表更新（启动器 `7364043`/SA 包复核/libs/jar=/脚本代码）与核对口径；§5 风险项补 B509 验证 | JAR SHA、启动器 mtime+hash、`git log --name-only --since=2026-09-13` |
| `v159-data-assets-hot-reload.md` | 顶部状态注；新增「兼容口径（2026-10-17 最终版，提交 729dff3）」表（9 类场景 + 代码位置 + 异步入口用法）；改写「防护与失败回滚」3 条旧口径与 patch/content 失败口径；文末新增 2026-10-16/17 验证记录 | `729dff3` 提交说明；脚本 :349/:1037/:1048/:1125/:1206/:1240/:1441；`.ktc` 56 entries/157,498B；`log-0.txt` 17:41 |
| `official-v159-compat.md` | 标题与顶部基线块更新为 B509/v160.7（含提交/工作子模块/SHA 前缀），并注明其余章节为历史兼容记录 | `dev-workspace-guide.md`「当前候选基线」、JAR SHA |
| `security-guard.md` | `accountIpGuard` 关系条目补 [IP 风控审计报告](ip-risk-control-report.md) 与用户决策指针（方案 C、不做行为规则） | `ip-risk-control-report.md` §11、提交 `a739f99` |
| `account-system.md` | `accountIpGuard` 条目补审计发现的偏差（2次/24h 阈值未触发、风险期可续期、`/login` 不校验封禁）与决策指引 | `ip-risk-control-report.md` §6/§7/§11 |
| `doc-sync-audit.md`（本文件） | 新增：巡检范围、偏移簇、已修正/未修正清单、建议补的维护批次、遗留 owner、未确认清单 | 本报告 §3-§7 |

> 行数变化以 `git diff --stat -- docs/` 为准（本轮 7 个既有文件修改 + 1 个新增）。

## 4. 检查但未修改（及原因）

| 文档 | 核对结论 | owner / 说明 |
|---|---|---|
| `scripts-maintenance.md` | **存在偏移**：第十七批仍写"待小步重做 / 进行中（17:22 快照）"，`729dff3`（17:34）已完成；缺调试设施、启动器修复、IP 审计、惩罚体系批次 | Lead / punish-docs；任务明确禁改，本报告 §5 给要点 |
| `dev-workspace-guide.md` | 已是最新：基线 B509/v160.7、调试工具段、目录含 `tools`（`cdc8b17`/`7364043` 后更新） | Lead；本轮不动 |
| `punishment-system-design.md` | 已含 Lead 口径 Q1-Q5/Q7/Q9、N4/D5、实现偏离 D1-D4、用户否决项 N1-N3；等实现落地后第二阶段回写 | punish-docs |
| `ip-risk-control-report.md` | 已完成（含 §11 用户决策）；无需改 | ipguard-audit（已交付） |
| `agent-debug-experience.md` | §8/§9 已覆盖 KDoc 截断与排障；**建议补上游 issue #50 链接**（当前正文无） | docs-diag / Lead（不在本任务写作用域） |
| `help-menu.md` | 抽查：`/cp`、`/externalcp`、`/ScriptAgent ...` 等条目与代码一致；`scriptdiag` 属控制台诊断指令，**是否加入 `/help` 待定**（`menu.kts` 归 punish-docs） | 未改 |
| `trust-system.md` | 抽查：等级/权限/投票/同 IP 点赞等描述未见明确过期事实 | 未改 |
| `README.md` | 无版本号，入口链接正常 | 未改 |
| 其余专项文档（`performance-guard.md`、`v159-network-sync.md`、`database-system.md`、`custom-menu.md`、`server-status-stats.md`、`wiki-system.md`、`forum-posts.md`、`shop-system.md` 等） | 本轮只做版本/工具/指令关键词抽查，**未逐节复核**，未发现明确过期事实 | 建议相关改动时专项复核 |

## 5. 建议补的 `scripts-maintenance.md` 2026-10-17 批次要点（供 Lead 安排）

- **批次 A（调试设施，`cdc8b17` + `7364043`）**：SA KDoc 截断 bug 根因与 `.ktc` 证据（正常 55 entries/主类 ≈149KB →
  截断 26 entries/主类 ≈103.5KB）、`scriptdiag` 四个子命令、启动器控制台逐行落盘及修掉的两个坑
  （`$ErrorActionPreference=Stop` 中断、Windows PowerShell 5.1 `Tee-Object` 不实时刷盘）、嵌套注释扫描器、
  `agent-debug-experience.md` §8 修正/§9 新增、`dev-workspace-guide.md` 调试工具；附上游 issue #50。
- **批次 B（DP 兼容最终版，`729dff3`）**：5 小步明细；与 `dd8020a` 的差异仅为把触发 bug 的 KDoc 行 `- \`bundles/**\`：…`
  改为 `- bundles 目录：…`（功能等价）；每步 `.ktc` 均为全量（step1 153,023B/55 entries → step5 157,498B/56 entries）；
  运行态 `externalcp` 16 个 CP；`赛克兰特高阶科技.zip` 实测成功（patches=1/content=33/sprites=196，2 条 techTree 警告属基线既有）。
  **同时把现有第十七批的"待小步重做/进行中"改写为"已完成"**（当前文档状态已过期）。
- **批次 C（IP 风控审计 + 用户决策，`7bec551` / `a739f99`）**：文档类结果（11+1 类规则、60 次/8 个 IP 的生产日志行为证据、
  方案 C「只留报告、不改代码」、否决"行为规则"），注明"勿再提议"。
- **批次 D（惩罚体系设计，`3dcfd09` / `0acd0c7` / `a739f99` / `60aafcb`）**：N4/D5 决策、用户否决项 N1-N3、实现偏离 D1-D4；
  **实现落地后的实现批次由 punish-docs 补**，不要与本批混写。
- **批次 E（本巡检）**：docs 防偏移修正记录，可一段带过（正文见本报告）。

## 6. 遗留待办与 owner

| 事项 | owner | 备注 |
|---|---|---|
| `scripts-maintenance.md` 2026-10-17 批次（A-D）与第十七批状态改写 | Lead（+ punish-docs 负责惩罚实现批次） | 本任务禁改该文件 |
| 惩罚体系实现落地 + 设计文档回写最终接口 + `menu.kts` 菜单 | punish-system → punish-docs | 实现脚本当前未提交（工作区 4 改 + 1 新增：`voteMute/voteOb/playerBuildBan/playerMute` + `punishmentReport.kts`） |
| `agent-debug-experience.md` §9 补上游 issue #50 链接 | docs-diag / Lead | 不影响正确性，建议补 |
| `scriptdiag` 是否加入 `/help` 管理分区 | punish-docs（`menu.kts`）/ Lead 决定 | 当前仅控制台诊断也可接受 |
| B509/v160.7 真实客户端实测（观感/内容同步/DP） | 用户 | **未验证** |
| 插件仓库同步 + 生产部署 | Lead / 用户或运维 | 插件仓库 HEAD 仍 2026-10-16 `5f76efb`；生产不在本机 |
| SA 上游 issue #50 修复跟进 | 上游维护者 / 用户决定是否换包 | 当前运行 `c2823c1` |

## 7. 未确认清单

- B509/v160.7 的真实客户端表现（含安卓端）——**未验证**，不能由冷启动/命令 Socket 替代。
- SA 上游 issue #50 是否已被维护者确认/修复——**未确认**（本次未做联网核对）。
- `.agents/SA-3.4.0-release.jar` 是官方 3.4.0 发行包留档；是否切换运行包——**未决策/未确认**（当前运行仍是 `c2823c1`）。
- `scripts-maintenance.md` 更早批次（第十五/十六批）与当前代码的逐条一致性：只核对了冲突口径，未逐句复核。
- 运行态 `scriptdiag count` 的 `共160` 比 17:38 冷启动多 1 个脚本：按 `script-load/current.log`（`punishmentReport` 18:40 启用）
  判断是惩罚体系未提交脚本，**具体最终脚本数以提交后的冷启动为准**。

## 8. 本轮核对命令 / 证据索引

```powershell
git -C mdtdo log --oneline -15
git -C mdtdo status --short
# 运行态（只读）：经 .agents/sa-cmd.ps1
#   scriptdiag count -> 共160 加载156 启用151 失败0；根指令注册数=157
Get-Content mdtdo/mdtserver/config/logs/log-0.txt | Select-String "共找到|外部CP已加载"
#   17:38:54 共找到159脚本,加载成功155,启用成功150,出错0
#   17:41:28 外部CP已加载：赛克兰特高阶科技
Get-ChildItem mdtdo/mdtserver/config/logs/console-*.log   # 启动器逐行落盘已生效
Get-FileHash mdtdo/mdtserver/server-2026.10.10.B509.jar -Algorithm SHA256
#   FC1B01AB9F90B3F761AFB0045547FE2C0347B645BB050C648CB230698676AC74
Get-FileHash mdtdo/mdtserver/config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar -Algorithm SHA256
#   9D51FCDCB65D21C16564F544B83E2EDB4AAD5765427A791A577FB1F6DDDC2214
# 编译产物：cache/compiled/wayzer_map_externalCpHotReload/a1553f.ktc -> 56 entries / 157,498B
```

行数变化见 `git diff --stat -- docs/`；本轮修改的文件：`current-issues.md`、`project-memory.md`、
`deploy-sync-checklist.md`、`v159-data-assets-hot-reload.md`、`official-v159-compat.md`、`security-guard.md`、
`account-system.md`，新增 `doc-sync-audit.md`。
