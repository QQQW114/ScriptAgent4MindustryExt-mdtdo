# 变更日志（CHANGELOG）

> 维护规则：**只记录影响玩家体验、运维部署、接口契约或开发工作流的显性变化**，每条限 1–2 行，按时间倒序追加在顶部。  
> 详细实现过程与验证记录留在对应 Git Commit / Pull Request，不在此展开。

## Unreleased

- **密码重置与改密设备吊销**：管理员 `/setpassword` 重置密码时在同一事务内吊销名下所有设备的自动登录绑定并安全退出在线会话（不踢人）；玩家自助 `/changepassword` 保留本机绑定并吊销其他设备的自动登录。
- **文档与工程规范重构**：精简重组 `docs/` 为四层现行文档（`architecture.md` / `workflow.md` / `dev-and-debug.md` / `deployment.md` / `backlog.md`），建立 Issue 驱动大改动、`.scratch/` 临时草稿箱与 `CHANGELOG.md` 分层体系，移除历史流水账与会话黑话文档。
- **入库开发调试工具链**：新增统一脚本 `tools/mdt-dev.ps1`（静态体检含 KDoc/`listenTo`/`@file:Depends`/文档防退化 Linter、无头冷启动、Socket 控制、热重载验收、Web/Email 隔离冒烟），并在本地主工作区新增双仓同步脚本 `tools/sync-repo.ps1`。
- **启动器 Socket 竞态修复**：`mdtserver/start-server.ps1` 先关闭 `socketInput` 再配置端口与地址后启用，消除 `Trigger.socketConfigChanged` 连续重启监听线程导致的 6859 绑定竞态；新增 `-Once` / `-Headless` / `-PidFile` 参数。
- **脚本卫生修复**：修复 `stats-web/start-web.bat` 换行符为 CRLF。

## 2026-11

- **2026-11-23** `fix(account)`：同 IP 小号公开广播隐藏完整游戏 UUID，仅保留名字与短 ID（日志仍留完整记录供管理排查）。
- **2026-11-22** `fix(mdc)`：封存已绑定账号 UUID 的游客态 MDC，连接/登录时自动并回账号，补齐 `accountAuth` 对 `trustPoint` 的 `@file:Depends`。
- **2026-11-19** `fix(idleBreakGuard)`：自然墙体/矿石等环境方块（`!block.hasBuilding()`）豁免批量拆除统计（V0.1.7）。
- **2026-11-16** `feat(web)`：门户 DP 上传单文件上限提高到 30 MB，地图保持 10 MB。
- **2026-11-16** `fix(idleBreakGuard)`：沙盒与编辑器模式豁免检测，忽略空 plan（V0.1.6）。
- **2026-11-07** `feat(idleBreakGuard)`：新增 21 种基础建筑豁免、接收台黑名单（只罚不还原）、多格建筑锚点修复与重连保留 OB 记录（V0.1.5）。
- **2026-11-06** `fix(web)`：门户安全加固（慢 body 限时、先验密码登录退避、`/api/health` 分级脱敏、公开事件流去 UUID 与指令过滤）。
- **2026-11-06** `feat(idleBreakGuard)`：非游客改为每日 04:00 后首次进服触发监控，游客每次进服监控。
- **2026-11-02** `feat(map)`：新增地图简介标签 `@dp` / `@nodp` 控制外部 DP 自动加载（`wayzer/map/mapDpTags.kts`）。
- **2026-11-02** `feat(account/email)`：主服上线 QQ 邮箱注册/补绑（移除旧 `/captcha`）、入服 10s 引导弹窗、本地 `email-service` 计划任务部署与限流（每日 100 封 / 全服注册 30 封每小时）；新增主服/分服一键更新脚本。
- **2026-11-01** `feat(updater)`：生产更新器支持运行中热更新（console socket 逐个 `sa unload/load` + `script-load/current.log` 判定 + 全服 `say` 广播）。
- **2026-11-01** `feat(quickOps)`：`/quickops` 协管快捷操作菜单迁移至 MenuV3 分页，支持 3++/4 免投票直达。

## 2026-10

- **2026-10-31** `feat(guard)`：新增新玩家批量框选拆除监控插件 `idleBreakGuard.kts`。
- **2026-10-28** `feat(punishment)`：检举投票收拢为仅已绑定玩家可发起与投票，赞成率阈值调整为 75%。
- **2026-10-26** `fix(menuV3)`：修复 `MenuV3.send()` 重发未回收旧 token 导致翻页自动关闭菜单的问题。
- **2026-10-24** `feat(security/punishment)`：新增 DOS 黑名单受信任 IP 白名单 `/doswhitelist`；投票观战取消 24h 自动到期（社区决议长期有效，非管理员直接观战仍保留 24h 上限）。
- **2026-10-18** `feat(web/punishment)`：门户 Web 新增服务器日志下载/尾部预览、历史事件抽屉与宿主机上行带宽卡；检举体系升级两段式投票检举、1 天/3 次永久直接限制、6 次永久限制类投票。
- **2026-10-17** `feat(core/web/punishment)`：升级 `stats-web` 为三页服务器门户与实时事件流；落地惩罚检举体系、`/votednd`、`/votetutorial`、`/vote quitMute`；新增 `scriptdiag` 诊断指令与 KDoc 嵌套块注释扫描器；完成全仓 `listen` → `listenTo` 修复与 DP 宽容加载改造。
- **2026-10-16** `chore(baseline)`：服务端基线跟进至 Mindustry v160.7 / MindustryX B509。
