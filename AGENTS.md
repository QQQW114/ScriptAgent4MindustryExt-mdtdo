# AGENTS.md —— MDT DO 仓库 AI Agent 操作规范

适用范围：所有在本项目（主工作区 `server/mdtdo/` 或开源仓 `QQQW114/ScriptAgent4MindustryExt-mdtdo`）协作的 AI Agent。

> 协作模式：**单模块、短会话**。每完成一个主要模块/主题的改动并提交 PR 后，请结束当前会话，下一个模块开新对话，避免上下文膨胀与规则漂移。

---

## 1. 新会话 3 步定向启动（严禁全仓乱读）

1. 通读本文件 `AGENTS.md`（理解硬红线与工作流）。
2. 打开 [docs/architecture.md](docs/architecture.md)，通过**模块路由表**找到本次任务对应的代码目录与唯一领域文档。
3. **只读**目标模块的 1 篇领域文档 + 目标源码；涉及本地启停/编译报错时再读 [docs/dev-and-debug.md](docs/dev-and-debug.md)。

---

## 2. 改动分级与职责分层（把信息放到正确的地方）

| 信息类型 | 唯一存放位置 | 严禁做法 |
|---|---|---|
| 现行系统规范（行为/配置/命令/表结构） | `docs/<系统>.md` | 禁止写开发过程、试错经过、测试日志 |
| 较大改动的需求拆解与多步进度追踪 | **GitHub Issues**（用 `.github/ISSUE_TEMPLATE/`） | 禁止在 `docs/` 里建任务清单或进度跟踪文件 |
| 单次改动的动机、验证数据与未覆盖边界 | **Git Commit & Pull Request** | 禁止在 `docs/` 按批次写流水账 |
| 面向运维/协作者的精炼变更摘要 | 根目录 `CHANGELOG.md`（每条 1–2 行） | 禁止写排查细节 |
| 开发中的测试计划、排查笔记、临时草稿 | **`.scratch/`**（已 `.gitignore`，不入库） | **绝对禁止提交进 `docs/`** |

- **小改动**（单脚本 bugfix、文案、单参数微调）：直接开分支修改 → 跑验收工具 → 开 PR。
- **中/大改动**（新增功能模块、跨多脚本协作、修改 `.kt` 核心库、新增数据库表、Web/更新器结构改动）：**必须先创建 GitHub Issue** 明确目标、边界与拆分子项，按模块分会话开发并在 Issue 勾选进度，每个 PR 关联 `Refs #N` 或 `Closes #N`。

---

## 3. 硬性工程红线

1. **环境与仓库边界**：
   - 本地 `mdtserver/` 是开发测试服，Agent 可直接用 `tools/mdt-dev.ps1` 启停、清缓存、发送控制台命令；
   - **生产服与远端推送受控**：不擅自 `git push`、不直推 `main` 或 `mdc-do`、不操作生产目录；主工作区变动需经 `tools/sync-repo.ps1` 同步至开源仓后由维护者审核提交。
2. **核心库变更纪律**：
   - 默认不修改 `scripts/wayzer/vote.lib.kt` 与 `scripts/wayzer/lib/*.kt`（包含全局单例与持久化层，热重载会导致类重复定义与双实例）；
   - 确需修改时必须先开 Issue 说明原因，且**严禁热重载**，必须执行 `pwsh -File tools/mdt-dev.ps1 verify -Mode cold -ClearCache` 通过冷启动全量验收。
3. **ScriptAgent 3.4.0 编译硬规则**：
   - **块注释内禁止出现字面量 `/*`**（SA 3.4.0 编译管线会静默截断 `.ktc` 产物，导致脚本显示 Enabled 但指令丢失）；
   - **SA 自定义事件必须用 `listenTo<T>`**（`listen<T>` 只挂 Arc 原生事件总线，监听自定义事件会静默失效）；
   - **跨脚本调用 `contextScript<T>()` 必须声明对应 `@file:Depends(...)`**，否则冷启动编译失败。
4. **Windows 脚本编码**：
   - 所有 `.ps1` 必须保存为 **UTF-8 with BOM**，且禁止使用全角弯引号；
   - 所有 `.bat`/`.cmd` 必须为 **纯 ASCII + CRLF**。
5. **凭据与运行数据绝不入库**：
   - 严禁提交或打印 `email-service/config.json`、`stats-web/web-config.json`（含真实凭据）、`mdtserver/server.properties`、`tools/dev-local.json`、`scripts/data/`、`scripts/cache/`、`scripts/external-cp/`、`stats-web/web-data/`、数据库文件与日志文件。
6. **已明确否决的需求（勿再提议或顺手实现）**：
   - 禁言拦截扩展到 `/r` 或私聊命令；
   - `/vote text` / `/vote sc` 增加冷却或敏感词过滤；
   - 玩家名规范化、同名冒充检测、或在投票文案追加 UUID/等级后缀；
   - IP 风控增加复杂行为语义规则（互骂/刷屏/票滥用判定）；
   - 自定义小音效切换至 Sound 通道或为此修改客户端；
   - 将游戏控制台 6859 端口暴露到公网（仅允许本机回环监听）。

---

## 4. 长期文档防退化纪律（机器自动拦截）

<!-- doc-lint-disable -->
为保证任何后续模型都能直接读懂文档，以下规则由 `tools/mdt-dev.ps1 check` 自动检查，违规将直接阻断验收：

1. **只写现行事实**：文档只描述当前生效的行为、配置、命令与代码位置；删掉旧逻辑时同步删掉旧描述，不要堆叠“历史追加记录”。
2. **禁止写入会话与 Agent 黑话**：严禁出现子 Agent 角色名或内部会话代号（如 `Lead`、`punish-docs`、`docs-diag`、`task-N`、`第X批`、`方案 A/B/C`、`Q1/N1/D1`、`已闭环基线`、`待某某回写`、`本轮实测`）。
3. **禁止新建过程文档**：禁止在 `docs/` 下创建 `*-test-plan.md`、`*-audit.md`、`*-report.md`、`temp-*.md`、`doc-sync-*.md`、`*-changelog.md`、`scripts-maintenance.md` 等文件；临时内容放 `.scratch/`。
4. **禁止写入本机绝对路径**：文档一律使用仓库相对路径，禁写 `C:\Users\...`、旧归档目录 `.agents/` 等。
5. **保持精简与登记**：`docs/backlog.md` 仅留未完成项（完成后直接删除，禁留 `✅`）；新增系统文档必须同步登记到 `docs/architecture.md` 的模块路由表；单篇文档建议不超过 400 行。
<!-- doc-lint-enable -->

---

## 5. 交付前强制验收命令

```powershell
# 1. 一键静态体检（无需服务端，秒级；覆盖 KDoc/listenTo/Depends/编码/文档卫生/敏感文件）
pwsh -NoProfile -File tools\mdt-dev.ps1 check

# 2. 脚本改动：标准冷启动全量验收（编译出错0 + scriptdiag失败0 + listFailed空 + 自动停服）
pwsh -NoProfile -File tools\mdt-dev.ps1 verify -Mode cold
#    如改了 .kt 核心库，必须加 -ClearCache；协作者独立克隆仓库请加 -SyncToServer

# 3. 调试中途改纯 .kts 可用热重载加速：
pwsh -NoProfile -File tools\mdt-dev.ps1 reload -Scripts wayzer/cmds/xxx

# 4. Web / 邮件服务改动跑隔离冒烟：
pwsh -NoProfile -File tools\mdt-dev.ps1 smoke-web
pwsh -NoProfile -File tools\mdt-dev.ps1 smoke-email
```

完整协作流程、PR 规范与分服同步规则见 [docs/workflow.md](docs/workflow.md) 与 [CONTRIBUTING.md](CONTRIBUTING.md)。
