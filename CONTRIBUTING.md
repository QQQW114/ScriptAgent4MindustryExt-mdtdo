# 贡献指南（CONTRIBUTING）

欢迎参与 MDT 服插件仓库！本仓库是 [way-zer/ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt)
的 fork，维护 MDT 服务器的 Game Script Agent（SA）脚本、Web 门户与生产更新器。

> **动工前必读**：完整的协作纪律、技术红线、验证要求与文档规范见
> [`docs/collaboration-discipline.md`](docs/collaboration-discipline.md)。
> 本文件只是面向 GitHub 的速览版；两者冲突时以完整文档为准。

## 仓库结构

```
scripts/**     SA 脚本（运行时核心资产，热重载敏感）
stats-web/**   Web 门户（独立 PowerShell 进程，PS 5.1）
updater/**     生产自动更新器（严格镜像 + 备份回滚 + 必须停服）
docs/**        权威文档（行为变更必须同步）
```

## 绝对红线（速览）

1. **不要改** `scripts/wayzer/vote.lib.kt` 与 `scripts/wayzer/lib/*.kt`（会导致静态单例重复、服务器无法干净重启）；
   需要扩展行为请新建 `scripts/wayzer/cmds|ext|user/*.kts`。
2. **块注释内不得出现字面量 `/**`**（SA 编译 bug 会静默截断脚本）；改完跑
   `pwsh -NoProfile -File "mdtdo\tools\scan-nested-block-comment.ps1"`，必须 0 命中。
3. **SA 自定义事件用 `listenTo<T>`**，不要用 `listen<T>`。
4. **`.ps1` 必须 UTF-8 with BOM**。
5. **不提交**密钥/凭据/生产配置/运行数据（`web-config.json`、`web-data/`、日志、`external-cp/` 内容、数据库等）。
6. **不操作生产服**（不重启、不杀进程、不做实验）；生产更新走 `updater/` 或维护者流程。
7. **不改客户端**、不引入外部依赖/遥测。
8. **不 force-push、不 rebase 已推送的 `main`**、不改写他人提交。

## 贡献流程

1. **分支 + PR**：`feat/<topic>`、`fix/<topic>`、`docs/<topic>`；`main` 只由维护者合并，
   即使你有仓库写权限也不要直推 `main`。
2. **PR 必须包含**：改了什么/为什么、影响范围、验证记录（见下）、文档同步清单、风险与未验证项。
3. **提交信息**：中文，`type(scope): 摘要`，正文写动机/方案/验证；一个 PR 一个主题，不顺手重构。
4. **review**：至少 1 名维护者 review；仓库无自动化 CI，维护者会本地复跑验证后合并。

## 验证要求（提交前）

- 脚本：开发服**冷启动**通过（banner `共找到N/加载N/启用N/出错0`）→ `scriptdiag count` 失败 0 →
  `sa listFailed` 空 → 相关脚本 `Enabled` → KDoc 扫描 0 命中；测完关闭你启动的开发服。
- Web：本地起服务自测 `/api/health` 与页面功能，`.ps1` BOM 校验。
- 更新器：临时副本目标跑 `-DryRun`/更新/`-Rollback`，不得用生产目录测试。
- **冷启动编译失败 = 不合格交付**，不允许"先合并再修"；无法运行时验证的请在 PR 里写明"未验证项"。

## 文档同步

行为/配置/命令/口径变化必须同 PR 更新 `docs/`：`scripts-maintenance.md`（顶部新增批次）、
对应设计文档、`current-issues.md`、`help-menu.md`（新指令）等。详见完整纪律文档 §5。

## 已否决需求（勿再提议）

禁言拦截私聊、`/vote text|sc` 过滤、玩家名规范化/冒充检测、IP 风控行为规则扩展、
音效频道切换、生产调试端口等已被维护者明确否决；如需重开讨论请先联系维护者。

## AI 使用声明

本仓库部分代码/文档由 AI 辅助生成。AI 生成内容视同普通贡献：必须通过冷启动与验证、
由维护者 review 后合并（详见根 `AGENTS.md` 与 `docs/collaboration-discipline.md` §6）。
