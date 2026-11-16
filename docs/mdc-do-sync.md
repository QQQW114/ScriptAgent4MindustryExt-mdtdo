# 分服（mdc-do）同步规则

本分支是 **MDC DO 分服**，主服是 `main`。

## 同步什么

- 插件脚本：`scripts/**` 中的通用功能、安全修复、命令、地图与守卫逻辑；
- Web 门户：`stats-web/**`；
- 通用文档与维护记录；
- 所有改动仍需按对应改动类型做冷启动/脚本统计/KDoc 或 Web 验证。

## 不同步什么

分服**不部署、不跟进邮件系统与账号邮箱绑定**，以下路径不要从 `main` 同步/合并：

- `email-service/**`
- `docs/email-service.md`
- `docs/account-email-binding.md`
- `scripts/wayzer/user/emailBind.kts`
- `scripts/wayzer/user/emailOnboarding.kts`
- `scripts/wayzer/user/accountMigrate.kts`
- `updater/update-email-service.ps1` / `.bat`

> 分服分支已移除 `email-service/` 与 `docs/email-service.md`；`update-subserver.bat` 只使用 `scripts/**`，
> 不会启动或更新邮件服务。主服邮件服务只在 `main` 维护。

## 同步方式

1. 在 `main` 确认提交已审查、测试通过；
2. 只 cherry-pick 通用功能提交到 `mdc-do`，或按文件同步，避免带入账号/邮箱专属提交；
3. 同步后检查 `git status` 是否只含预期文件；
4. 脚本改动必须冷启动验证：`出错0`、`scriptdiag count` 失败 0、`sa listFailed` 空、相关脚本 `Enabled`，并跑 KDoc 扫描；
5. 推送 `mdc-do`，并在变更记录中注明分服已跟进。
