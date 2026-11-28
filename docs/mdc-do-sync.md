# 分服（MDC DO / `mdc-do`）定位与同步规范

## 1. 分服定位：客制化的主服

本项目同时服务两个远端社区服，代码同源但分支配置不同：

| 维度 | 主服 MDT DO（`main`） | 分服 MDC DO（`mdc-do`） |
|---|---|---|
| **定位** | 功能完整的主线正式服 | 客制化经济宽松分服（玩法与通用功能跟随主服） |
| **账号体系** | 强制 QQ 邮箱注册/补绑（无 `/captcha`），配备本地 `email-service` | 账号系统宽松，**不启用邮箱绑定与 `email-service`**，保留 `/captcha` |
| **经济与社区** | 标准结算倍率与完整帖子/社区功能 | 货币（MDC）收入倍率调高（经济自由），运营侧关闭部分帖子系统 |
| **生产更新脚本** | `updater/update-mainserver.bat`（或仅插件版） | `updater/update-subserver.bat`（默认拉取 `mdc-do` 分支） |

> 帖子开关（`/forumtoggle`）、MDC 结算倍率（`/mdcmultiplier`）等属于**运行期数据库配置**，保存在各服自己的 `config/scripts/data/`，不通过代码分支硬编码区分。分支差异仅集中在「是否包含邮箱绑定与邮件服务」。

---

## 2. 同步范围

### 应该从 `main` 同步到 `mdc-do` 的内容

- `scripts/**` 中的通用玩法、命令、地图标签、安全风控、性能保护、惩罚检举与 Bug 修复；
- `stats-web/**` 门户 Web 的功能与安全加固；
- `tools/**` 开发调试工具与通用文档（`AGENTS.md`、`CONTRIBUTING.md`、`CHANGELOG.md`、`docs/**` 中的通用文档）；
- `updater/**` 中除主服邮件更新脚本外的通用改进。

### 严禁同步到 `mdc-do` 的专属路径

以下路径仅属于主服邮箱体系，`mdc-do` 分支必须保持排除：

- `email-service/**`
- `docs/email-service.md`
- `docs/account-email-binding.md`
- `scripts/wayzer/user/emailBind.kts`
- `scripts/wayzer/user/emailOnboarding.kts`
- `scripts/wayzer/user/accountMigrate.kts`
- `updater/update-email-service.ps1` / `updater/update-email-service.bat`
- `updater/update-mainserver-scripts.bat`

> 另有共用文件 `scripts/wayzer/user/accountAuth.kts`、`scripts/wayzer/user/serverFeatureSettings.kts`、`scripts/coreMindustry/menu.kts` 在两分支上关于 `/captcha` 与邮箱入口存在差异，同步通用修复时请按文件或 `git cherry-pick` 仔细核对，避免带入邮箱依赖。

---

## 3. 标准同步步骤

1. 确认目标提交已在 `main` 完成 review 并通过 `tools/mdt-dev.ps1 verify -Mode cold` 验收。
2. 切到 `mdc-do` 分支，只 `git cherry-pick` 通用功能提交（或按文件合并并排除上表清单）。
3. 在 `mdc-do` 分支执行门禁验证：
   ```powershell
   pwsh -NoProfile -File tools\mdt-dev.ps1 check
   pwsh -NoProfile -File tools\mdt-dev.ps1 verify -Mode cold -SyncToServer
   ```
4. 检查 `git status` 未意外引入 `email-service/` 或邮箱脚本，经维护者确认后推送 `origin mdc-do`。
5. 生产分服端先运行 `updater/update-subserver.bat -DryRun` 核对变更清单，再正式更新。
