# 贡献指南（CONTRIBUTING）

欢迎参与 MDT DO 服务器仓库（`QQQW114/ScriptAgent4MindustryExt-mdtdo`）协作！本仓库基于 [way-zer/ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt)，统一维护游戏服务端插件脚本、门户 Web、邮件发信服务、生产更新器、开发工具与权威文档。

> 使用 AI / Vibe Coding 协作前，请务必让模型先读根目录 [`AGENTS.md`](AGENTS.md) 与 [`docs/workflow.md`](docs/workflow.md)。

## 仓库目录

| 目录 | 说明 |
|---|---|
| `scripts/` | ScriptAgent 服务端插件脚本（部署到 `config/scripts/`） |
| `stats-web/` | 独立门户 Web（Windows PowerShell HttpListener，零外部依赖） |
| `email-service/` | 主服本地 QQ 邮箱验证码发信服务（`127.0.0.1:40150`，不含密钥） |
| `updater/` | 生产自动更新器（主服 `main` / 分服 `mdc-do`、支持运行中热更新与回滚） |
| `tools/` | 本地静态体检、冷启动与热重载调试脚本（`mdt-dev.ps1`） |
| `docs/` | 现行架构、工作流与各系统规范文档（只记现行事实） |
| `.scratch/` | 本地临时草稿箱（除 README 外全部 `.gitignore`，放测试计划与排查笔记） |

## 分支策略

- **`main`**：主服（MDT DO）主线分支，包含完整功能（含 QQ 邮箱绑定与 `email-service/`）。**所有 PR 默认提向 `main`**，严禁直推。
- **`mdc-do`**：分服（MDC DO）分支，本质是客制化主服（关闭部分帖子系统、经济倍率宽松、账号系统无邮箱验证与 `email-service/`）。通用功能合入 `main` 后再按 [`docs/mdc-do-sync.md`](docs/mdc-do-sync.md) 同步到 `mdc-do`。
- **特性/修复分支**：`feat/<topic>`、`fix/<topic>`、`docs/<topic>`、`chore/<topic>`。

## 标准协作流程

1. **先分大小**：
   - 小改动（单脚本修复/文案/参数）：直接从 `main` 切分支开发。
   - 中/大改动（新模块、跨多脚本、修改 `.kt` 核心库、数据库表变更、Web/更新器架构改动）：**先提 GitHub Issue**（使用 `.github/ISSUE_TEMPLATE/feature-task.md`），拆分模块、明确边界，再按模块分 PR 推进并关联 `Refs #N` / `Closes #N`。
2. **单模块、短会话开发**：每次只做一件事，不顺手重构无关文件；完成后立刻开 PR 并结束当前 AI 会话。
3. **跑通本地验收门禁**：
   - 无服务端环境也必须先跑：`pwsh -NoProfile -File tools\mdt-dev.ps1 check`（必须 0 报错）。
   - 修改 `scripts/` 时配置本机测试服路径（复制 `tools/dev-local.example.json` 为 `tools/dev-local.json`），运行：
     `pwsh -NoProfile -File tools\mdt-dev.ps1 verify -Mode cold -SyncToServer`（改 `.kt` 库必须加 `-ClearCache`）。
   - 修改 `stats-web/` 或 `email-service/` 时运行 `smoke-web` / `smoke-email`。
4. **最小文档同步**：
   - 行为/配置/指令变化 → 同步修改对应 1 篇 `docs/<系统>.md`（只写现行事实），并在 `CHANGELOG.md` 顶部补充 1 行摘要。
   - 测试计划、排查记录、过程草稿放 `.scratch/`（不入库）或写在 PR 描述里，**不要提交进 `docs/`**。
5. **提交信息**：中文，`type(scope): 简明摘要`（`feat` / `fix` / `docs` / `refactor` / `chore` / `perf`），正文写清动机、变更与验证结果。

## 绝对红线速览

- 不修改 `scripts/wayzer/vote.lib.kt`、`scripts/wayzer/lib/*.kt`（确需修改须先开 Issue + 清缓存冷启动验证）；
- 块注释内禁止出现字面量 `/*`；SA 自定义事件必须用 `listenTo<T>`；`contextScript<T>()` 必配 `@file:Depends(...)`；
- `.ps1` 保存为 UTF-8 with BOM（禁全角弯引号）；`.bat`/`.cmd` 保存为纯 ASCII + CRLF；
- 绝不提交密钥、凭据、生产配置与运行数据（`email-service/config.json`、`web-data/`、`scripts/data/`、`scripts/external-cp/` 等）；
- 不实现已明确否决的需求（详见 [`AGENTS.md`](AGENTS.md) §3.6 与 [`docs/architecture.md`](docs/architecture.md)）。

## AI 辅助开发声明

本仓库接受 AI / Vibe Coding 贡献，但与人类代码适用同一质量门槛：必须通过 `tools/mdt-dev.ps1 check` 与对应实机验收，由维护者 review 后合并。
