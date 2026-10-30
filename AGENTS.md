# AGENTS.md —— 本仓库 AI agent 协作纪律

适用：在本仓库（`QQQW114/ScriptAgent4MindustryExt-mdtdo`）或其本地工作区里协作的所有 AI agent。
完整纪律见 [`docs/collaboration-discipline.md`](docs/collaboration-discipline.md)，本文件是 agent 精简版。

## 你能做什么

- 在分配的任务范围内**读代码/文档、改文件、做验证**；
- 通过共享任务板/消息与 Lead/其他 agent 协调写范围，避免并发改同一文件；
- 报告事实（文件、行号、命令输出、日志）。

## 你不能做什么（硬性）

1. **不 commit、不 push、不合并、不打 tag** —— 只交给 Lead/维护者；
2. **不重启游戏服/Web/生产**，不杀不属于本任务的进程；测试用临时副本；
3. 不改 `scripts/wayzer/vote.lib.kt`、`scripts/wayzer/lib/*.kt`；
4. 块注释内不写字面量 `/**`（SA 编译 bug 会静默截断），改完必须跑 KDoc 扫描；
5. SA 自定义事件用 `listenTo<T>`，不用 `listen<T>`；
6. `.ps1` 保存为 UTF-8 with BOM；
7. 不提交密钥/凭据/生产配置/运行数据（`web-config.json`、`web-data/`、日志、`external-cp/` 内容等）；
8. 不实现已否决需求（禁言拦截私聊、`/vote text|sc` 过滤、玩家名规范化、IP 风控行为规则扩展、
   音效频道切换、生产调试端口等）；
9. 不顺手重构/扩大范围；不伪造验证结果。

## 交付前自检

- `git status` 只含预期文件；
- 脚本改动：开发服冷启动 `出错0` → `scriptdiag count` 失败 0 → `sa listFailed` 空 → 相关脚本 `Enabled`
  → KDoc 扫描 0 命中；测完关闭自己启动的开发服；
- Web/更新器改动按 `docs/collaboration-discipline.md` §4 验证；
- 文档同步（维护批次/设计文档/current-issues/help-menu，按影响范围）；
- 报告格式：**文件清单 / 改动摘要 / 验证数字 / 未验证项 / 风险**。

## 必读

- `docs/collaboration-discipline.md`（协作总纪律）
- `docs/project-memory.md`（项目记忆）
- 对应专题文档（`punishment-system-design.md`、`web-portal.md`、`updater/README.md` 等）
- `CONTRIBUTING.md`（人类贡献者流程）
