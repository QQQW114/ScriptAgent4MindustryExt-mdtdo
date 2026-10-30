# 协作纪律（MDT 服插件仓库 / mdtdo）

> 适用对象：本仓库的人类贡献者、维护者，以及在本地工作区协作的所有 AI agent。
> 权威范围：本文是协作层面的**总纪律**；具体技术细节以 `docs/` 下对应专题文档为准。
> 同步位置：插件仓库根 `CONTRIBUTING.md`（面向 GitHub 贡献者的精简版）、`AGENTS.md`（agent 精简版）、
> `.github/PULL_REQUEST_TEMPLATE.md`（PR 检查清单）。修改本文时请同步上述三处。

## 0. 角色与基本原则

| 角色 | 权限与职责 |
|---|---|
| 维护者（Owner，QQQW114） | 合并 `main`、生产部署、版本/需求决策、发布 |
| 人类贡献者 | fork 或分支 → PR；即使有仓库写权限，也**不直接推 `main`** |
| AI agent | 只读分析 + 在分配任务范围内改文件；**不 commit、不 push、不合并、不重启服务**，由 Lead/维护者统一提交 |

基本原则：

1. **最小改动**：一次 PR 只做一件事，不顺手重构/清理/格式化无关文件；
2. **先问再动**：需求不明确、涉及生产、涉及已否决功能、涉及数据兼容时，先问维护者；
3. **事实说话**：报告要带文件/行号/命令输出/提交号；不确定就说不确定，不臆测；
4. **中文沟通**：代码注释、提交信息、文档、面向玩家的文案统一中文（对外 issue 可中英）；
5. **不引入依赖**：脚本零外部依赖、Web 零外部依赖；新增联网/遥测/客户端改动需维护者批准。

## 1. 仓库结构与边界

```
scripts/**     SA 脚本（运行时核心资产，热重载敏感）
stats-web/**   Web 门户（独立 PowerShell 进程，PS 5.1）
updater/**     生产自动更新器（严格镜像 + 备份回滚 + 必须停服）
docs/**        权威文档（行为变更必须同步）
```

- 仓库 `scripts/` 是**源码镜像**；实际运行目录是本地 `mdtdo/mdtserver/config/scripts/`。
- 运行数据绝不入库：`web-data/`、`scripts/external-cp/` 内容、`data/config.conf`、`settings.bin`、
  数据库文件、日志、DP 上传包、备份目录等（`.gitignore` 已覆盖，提交前 `git status` 自查）。
- 上游关系：本仓库是 `way-zer/ScriptAgent4MindustryExt` 的 fork；`LICENSE.md` 版权口径沿用上游，
  不要移除/改写上游版权声明。

## 2. 绝对红线（违反会直接搞坏服务器/历史）

1. **不要修改 `scripts/wayzer/vote.lib.kt` 与 `scripts/wayzer/lib/*.kt`**：
   模块重编译会造成静态单例重复，服务器无法干净重启。需要扩展投票/通用库行为时，
   在 `scripts/wayzer/cmds/*`、`scripts/wayzer/ext/*`、`scripts/wayzer/user/*` 新建脚本，
   通过公开 API/事件扩展。
2. **任何块注释内不得出现字面量 `/**`**：SA 编译器已知 bug 会静默截断编译产物（`.ktc`），
   脚本仍显示 `[Enabled]` 但指令/逻辑丢失。改完必须跑：
   `pwsh -NoProfile -File "mdtdo\tools\scan-nested-block-comment.ps1"` → 0 命中。
3. **SA 自定义事件必须用 `listenTo<T>`**（`listen<T>` 只挂 arc/Mindustry 事件总线，订阅 SA 自定义事件会静默失效）。
   详见 `docs/listener-listen-vs-listenTo-audit.md`。
4. **`.ps1` 必须 UTF-8 with BOM**（Windows PowerShell 5.1 解析/中文要求）；`.bat` 用 ASCII + CRLF。
5. **不提交任何密钥/凭据/生产配置/运行数据**；`web-config.json` 属生产配置（仓库里只是模板），
   Web 的 `web-data/`、游戏 `external-cp/`、日志、数据库一律不入库。
6. **不操作生产服**：不去生产做实验、不重启、不杀进程、不改 `web-config.json`；生产更新走
   `updater/update-plugins.bat`（须停服）或维护者手动流程。
7. **不改客户端**；不给脚本引入外部网络/遥测；不复制粘贴来路不明的 jar/二进制。
8. **不破坏历史**：不 force-push 共享分支、不 rebase 已推送的 `main`、不改写他人提交。

## 3. 协作流程（人类贡献者）

1. **分支 + PR**：`feat/<topic>`、`fix/<topic>`、`docs/<topic>`、`chore/<topic>`；
   `main` 只由维护者合并，贡献者即使有写权限也不要直推。
2. **PR 内容必须包含**：
   - 改了什么、为什么（对应需求/issue）；
   - 影响范围（脚本 / Web / 更新器 / 文档 / 兼容性）；
   - 验证记录（见 §4：冷启动数字、脚本加载、KDoc 扫描、Web 自测等）；
   - 文档同步清单；风险与未验证项。
3. **review 规则**：至少 1 名维护者 review；无自动化 CI，由维护者本地复跑验证后合并。
   不通过时按 review 意见改，不在 PR 里争论已否决需求。
4. **提交信息**：中文，`type(scope): 摘要`（feat/fix/docs/refactor/chore/perf/test），
   正文写动机、方案、验证；一个提交一个主题。
5. **Issue**：先搜是否已有；附复现步骤/日志片段（脱敏）；**已明确否决的需求不要重复提**（见 §7）。

## 4. 验证纪律（提交/PR 前必须完成）

### 脚本（`scripts/**`）
1. 冷启动开发服：`mdtserver\启动服务器.bat`（或 `Start-Process cmd.exe '/c "启动服务器.bat"'`），
   等 6567/6859 就绪；
2. 检查 banner：`共找到N脚本,加载成功N,启用成功N,出错0`；
3. `scriptdiag count` → 失败 **0**；`sa listFailed` 空；相关脚本 `scriptdiag state <id>` = `Enabled failReason=无`；
4. KDoc 扫描 0 命中（见 §2.2）；
5. 热重载按依赖顺序：先被依赖脚本（如 `playerMute`），再 dependents；`lib`/`.kt` 改动建议重启验证；
6. 测试完成后停止你启动的开发服，确认端口释放、无残留进程。

### Web（`stats-web/**`）
- 本地起 Web，检查 `/api/health`、页面三页功能、前端/后端契约；`.ps1` BOM 校验；生产配置不进仓库。

### 更新器（`updater/**`）
- 用临时副本目标跑 `-DryRun` 与正式更新，验证严格镜像/保护目录/备份/`-Rollback`；
  **不得**用生产目录做测试；不得在测试中停生产。

### 通用
- `git status` 必须只包含预期文件；测试数据/日志/临时目录清理干净；
- **冷启动必须能过**：脚本编译失败=交付不合格，不允许"先合并再修"；
- 无法运行时验证的，在 PR 与文档中写明"未验证项"，由维护者在生产首次使用时验证。

## 5. 文档纪律

行为、配置、命令、口径发生变化时，必须同 PR 更新 `docs/`：

| 文档 | 何时更新 |
|---|---|
| `scripts-maintenance.md` | 每次脚本行为变更：顶部新增批次（编号顺延；需求/改动/验证/未验证） |
| 对应设计文档（如 `punishment-system-design.md`） | 口径/规则变化：追加"日期 + 口径"小节，不改写历史结论 |
| `current-issues.md` | 已修复项状态、未验证项、待办 |
| `help-menu.md` | 新增/修改游戏内指令、帮助条目 |
| `web-portal.md` / `stats-web/README.md` | Web API/配置/前端契约变化 |
| `deploy-sync-checklist.md` / `updater/README.md` | 部署/更新流程变化 |

- 文档与代码同 PR 提交；不允许"代码先合、文档后补"（维护者明确同意除外）。
- 文档要求：中文、事实、可复现；结论附代码位置（文件:行）与提交号。

## 6. AI agent 专属纪律

1. **不 commit / 不 push / 不合并 / 不打 tag**；由 Lead/维护者统一提交推送（写库权限仅在维护者侧）。
2. 开工前读任务描述 + 相关文档（`docs/project-memory.md`、专题设计文档）；不确定先问，不猜。
3. 确认本次任务的**写范围**：不并发修改同一文件；与其他 agent 通过共享任务板/消息协调；
   改动前先 `git status`，避免覆盖他人未提交改动。
4. **不重启游戏服/Web/生产**；测试用临时副本，或经批准启动开发服并在完成后关闭；
   不杀不属于自己任务的进程。
5. 自检（§4）后才能交付；报告必须给：文件清单、改动摘要、验证数字、未验证项、风险。
6. 严格遵守 §2 红线；不扩大范围（不做顺手重构）；不实现 §7 已否决功能。
7. AI 生成的改动视同普通贡献：**必须通过冷启动与验证**，由维护者 review 后合并（README 中的 AI 生成警告同样适用）。

## 7. 已明确否决的需求（勿再提议）

以下功能已由维护者评估并明确否决（时间受限/复杂度/不符合服务器定位），不要再提出或实现：

- 禁言时拦截 `r`/私聊消息；
- `/vote text|sc` 的冷却与敏感词过滤；
- 玩家名规范化/冒充检测；
- IP 风控行为规则扩展（"太复杂"类行为判定）；
- 音效频道切换（结论：保留音乐频道，不做客户端改动）；
- 生产服开启调试/`socketInput` 端口（控制台桥接仅作可选增强）。

如确需重启讨论，先向维护者说明新证据与必要性，不要直接开工。

## 8. 版本与兼容

- **基线**：跟进上游最新版本（当前 MindustryX `server-2026.10.10.B509` / Mindustry `v160.7`）。
- 小版本更新保持兼容；大版本破坏性更新尽量兼容；**直接改 DP 字段的破坏性更新不做兼容**。
- DP 加载系统：不自己定标准；内容与结构判断尽量放宽；`data/`、`external-cp/` 等运行数据默认不动。
- 修改上游原有脚本要最小化并可追溯（在维护批次记录）；能用新脚本/扩展实现就不改上游文件。

## 9. 生产与发布

- 生产部署用更新器（推荐）：
  - 插件：**先停服** → `updater/update-plugins.bat`（严格镜像 + 备份；必要时 `update-plugins-clear-cache.bat`）
  - Web：`updater/update-web.bat` → 重启 Web 进程（不需要停游戏服）
  - 回滚：`-Rollback` / `-RollbackTo <时间戳>`
- 发布前确认：变更集、文档、备份可用、回滚路径；生产密钥/玩家隐私/封禁数据不得外泄（日志请脱敏）。
- 生产环境的具体路径与步骤以 `docs/deploy-sync-checklist.md` 与 `updater/README.md` 为准。

## 10. 违规处理

- **红线**（§2）：回滚相关提交、取消写权限；造成线上事故的按维护者判断处理。
- **流程问题**（跳过验证/不更新文档/直推 main）：PR 打回并要求补齐；重复出现则暂停合并。
- **agent 违规**：该 agent 的产出不予合并，并由 Lead 重新分配任务。
