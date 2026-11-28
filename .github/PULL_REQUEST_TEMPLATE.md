# PR 检查清单

> 提交前请逐项确认；未验证项请如实填写，不要留空或伪造。完整规范见 `AGENTS.md` 与 `docs/workflow.md`。

## 改动说明

- **关联 Issue**（中/大改动必填）：`Closes #` / `Refs #` / 小改动免 Issue
- **改了什么**：
- **为什么**：
- **影响范围**（勾选）：
  - [ ] `scripts/`（游戏插件脚本）
  - [ ] `scripts/wayzer/lib/*.kt` 或 `vote.lib.kt`（⚠️ 核心库变更，必须清缓存冷启动）
  - [ ] `stats-web/`（门户 Web）
  - [ ] `email-service/`（主服邮件服务）
  - [ ] `updater/`（更新器）
  - [ ] `tools/` / `docs/`
  - [ ] 需要同步至分服 `mdc-do`

## 门禁与红线自查

- [ ] 已运行 `pwsh -NoProfile -File tools\mdt-dev.ps1 check` 并 **PASS（0 报错）**
- [ ] 未提交密钥、凭据、生产配置、运行数据或 `.scratch/` 临时文件
- [ ] 文档仅记录现行事实，未新增 `*-test-plan.md` / `*-audit.md` / `*-report.md` 等过程文档，无会话黑话
- [ ] 如涉及玩家可见或运维变更，已在 `CHANGELOG.md` 顶部补充 1 行摘要

## 验证记录（请直接粘贴 `mdt-dev.ps1` 输出摘要）

```text
# 示例：
# 静态体检：PASS（KDoc 0 / listen 0 / Depends 0 / 编码 PASS / 文档卫生 PASS）
# 冷启动汇总：共找到N脚本,加载成功N,启用成功N,出错0
# scriptdiag count：... | sa listFailed：(空)
```

- **未覆盖 / 待真实客户端验证项**：
- **回滚方式与风险**：
