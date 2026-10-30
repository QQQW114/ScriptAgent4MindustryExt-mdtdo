# PR 检查清单

> 提交前请逐项勾选；未完成的项要在描述里说明原因。完整纪律见 `docs/collaboration-discipline.md`。

## 改动说明

- [ ] 改了什么：
- [ ] 为什么（需求/issue 链接）：
- [ ] 影响范围：脚本 / Web / 更新器 / 文档 / 兼容性（勾选并简述）

## 红线自查

- [ ] 未修改 `scripts/wayzer/vote.lib.kt` 或 `scripts/wayzer/lib/*.kt`
- [ ] 块注释内无字面量 `/**`，KDoc 扫描 0 命中
- [ ] SA 自定义事件使用 `listenTo<T>`
- [ ] 新增/修改的 `.ps1` 为 UTF-8 with BOM
- [ ] 未提交密钥/凭据/生产配置/运行数据

## 验证记录

- [ ] 脚本：开发服冷启动通过（banner `出错0`）、`scriptdiag count` 失败 0、`sa listFailed` 空、相关脚本 `Enabled`
- [ ] KDoc 扫描：0 命中
- [ ] Web / 更新器验证（如适用）：
- [ ] 测试后已恢复现场（停开发服、清测试数据）

## 文档与风险

- [ ] 已同步文档：`scripts-maintenance.md` 批次 / 设计文档 / `current-issues.md` / `help-menu.md`
- [ ] 未验证项（如有）：
- [ ] 风险与回滚方式：

---
AI 辅助生成的改动请注明，并确保已通过上述验证。
