# DOS 黑名单受信任 IP 白名单（`/doswhitelist`）

> 状态：2026-10-24 实现（提交 `15017cc`），已通过两次冷启动编译/加载验证；**功能行为未实测**（用户要求跳过 add/解黑运行时验证，
> 见 §9/§10）。实现文件：`mdtserver/config/scripts/wayzer/security/dosWhitelist.kts`（约 626 行）。
> 维护记录见 `scripts-maintenance.md` 第二十八批；帮助入口见 `help-menu.md` 管理分类。

## 1. 问题背景

### 1.1 内核的两种连接拒绝与日志

MindustryX/Arc 内核在 `ArcNetProvider.java:130-131` 的连接过滤器中，对以下两种来访 IP 直接关闭连接，并打印：

```
Closing connection <ip> - IP marked as a potential DOS attack.
```

1. **并发连接数超过启动时缓存的 playerlimit**（玩家上限被顶满）；
2. **IP 命中 `netServer.admins.dosBlacklist`**——这是内核内存里的 `ObjectSet`，**没有过期时间、不落盘、官方没有任何清除指令**
   （唯一的原生手段是控制台 `dosBanClear`，见 §7）。

### 1.2 DOS 黑名单怎么被写入

- 每个连接在 3 秒内发送超过 `packetSpamLimit` 个包即触发反 DOS（`Administration.java:549`，上游默认 `packetSpamLimit=300`），
  该 IP 被加入 `dosBlacklist`。

### 1.3 大 NAT 出口连坐（本功能要解决的问题）

运营商 CGNAT、校园网、网吧、自有机房等**大 NAT 出口**下，多个玩家共享同一个公网 IP：
**只要其中一个客户端触发 packet spam，整个出口 IP 就会被拉黑**，该出口后的所有玩家都会一直进不来（直到有人手动清黑名单）。
使用人数越多、出口越大的场景越容易发生。本功能维护一份**受信任 IP / CIDR 白名单**，把这类可信出口从 DOS 黑名单中自动解除。

## 2. 功能与安全边界

- **自动解黑**：`Dispatchers.game` 上的轻量定时任务（默认每 **3000ms**，可配并钳制 1000~60000）遍历 `dosBlacklist` 的内存快照，
  对命中白名单的 IP 调用内核 `unBlacklistDos` 自动解除；启用时立即扫一次，`onDisable` 取消任务；`enabled=false` 时不动作。
- **日志/通知**：每次解除写日志，同一 IP 按 `logCooldownMillis`（默认 60s）限频；`notifyRemovals=true` 时私聊通知在线且有该指令权限的管理。
- **只解除 DOS 黑名单**：不解除 ban/kick 列表、不触碰 `accountIpGuard`/`securityGuard` 等其它风控、**不放宽玩家数量上限**。
- ⚠ **白名单 IP 会绕过内核反 DOS 拉黑**（这是功能本身的目的）：因此**只应对运营商大NAT/自有机房等可信静态出口配置**，
  不要随意把大网段加入白名单；CIDR 前缀被限制在 **/8 ~ /32**（`/0`~/7 直接拒绝，避免把反 DOS 整体关掉）。
- 白名单**不会**让被 ban 的玩家进来，也不影响服务器其它封禁/风控逻辑。

## 3. 命令与权限

**`/doswhitelist`**（别名 `dos白名单`、`ip白名单`、`doswl`；权限 `wayzer.admin.dosWhitelist`，默认 `@admin`，
控制台可用——控制台拥有全部权限，游戏内无权限会被拒绝）：

| 子命令 | 说明 |
| --- | --- |
| `list`（默认/无参） | 列出白名单条目（序号 + IP/CIDR + 备注）与**当前命中黑名单、会被自动解除的 IP**，以及本次启用以来的自动解除次数/最近一次 |
| `add <ip或CIDR> [备注]` | 添加条目（单 IPv4、IPv4 CIDR `/8`~/32、单 IPv6 精确匹配；主机位自动归零；备注最长 120 字符）；重复条目会提示已存在 |
| `remove <序号或ip>` | 按 `list` 序号或 IP/CIDR 移除 |
| `clear confirm` | 清空白名单（必须二次确认：`confirm`/`确认`/`yes`） |
| `status` | 查看启用状态、DB 加载状态、条目数、扫描间隔、dosBlacklist 当前大小、当前命中、累计解除次数、日志限频、通知开关与安全边界 |
| `check <ip>` | 查某 IP 是否在内核 DOS 黑名单、是否命中白名单；会提示"最多 N ms 后自动解除"或"未命中不会被解除" |

- 白名单在 `onEnable` 时从数据库异步加载（**加载完成前白名单暂不生效**，`status` 会显示加载中/失败）；加载完成后补扫一次。
- 增删改内存立即生效，落库在 IO 协程串行异步完成；热路径（定时扫描）**无 DB/IO**。

## 4. 配置项与默认值

SA 配置键全名 = `wayzer.security.dosWhitelist.<键名>`（`sa config get/set` 查看修改，下一轮扫描自动生效）：

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用自动解除受信任 IP 的 DOS 黑名单 |
| `cleanupIntervalMillis` | `3000` | 扫描 `dosBlacklist` 的间隔（ms），钳制 **1000~60000** |
| `notifyRemovals` | `true` | 自动解除时是否私聊通知在线且有该指令权限的管理 |
| `logCooldownMillis` | `60000` | 同一 IP 的解除日志/通知最小间隔（ms） |

## 5. CIDR 使用与安全建议

- 支持格式：单 IPv4（如 `1.2.3.4`）、IPv4 CIDR（如 `61.143.18.0/24`）、单 IPv6（精确匹配；暂不支持 IPv6 CIDR）。
- **/8 ~ /32**：`/0`~/7 会被 `add` 直接拒绝（错误提示"过宽网段会关闭反 DOS 保护"），非法前缀/非法 IP 同样拒绝。
- **只加可信出口**：优先用 `/24` 或更窄的大NAT/机房出口段；不要因为"某个玩家进不来"就加 `/16` 级网段。
- **定期复核**：出口 IP 会变化（家宽重拨、运营商迁移），建议每季度用 `list`/`check` 复核一次；失效条目及时 `remove`。
- 若白名单出口本身被恶意利用，包级风控（`securityGuard` 等）与人工封禁仍然有效；本功能只影响 DOS 黑名单。

## 6. 生产操作步骤

1. **同步脚本**：把 `mdtserver/config/scripts/wayzer/security/dosWhitelist.kts` 复制到生产对应目录（`scripts/wayzer/security/`）；
   该脚本不依赖其它新增脚本或额外依赖。
2. **加载**（二选一）：
   - 运行中：控制台 `sa scan` → `sa load wayzer/security/dosWhitelist`；
   - 或按常规流程重启服务器（推荐，保证冷启动编译一致）。
3. **确认加载**：控制台 `scriptdiag state wayzer/security/dosWhitelist` = `Enabled`；`sa listFailed` 为空；`scriptdiag commands doswhitelist` 命中。
4. **添加白名单**：管理员游戏内或控制台执行 `/doswhitelist add <ip或CIDR> <备注>`；用 `/doswhitelist list`/`status` 确认条目与命中情况；
   首次添加后建议让该出口玩家重连验证一次。
5. **持久化验证**：确认 `MdtSettings` 键 `security.dosWhitelist.entries`（每行 `ip或CIDR|备注`）已写入；重启后 `list` 仍在。
6. **帮助菜单**：`coreMindustry/menu.kts` 管理分类已加「DOS白名单」条目（提交 `15017cc` 已包含，随脚本一起同步 `menu.kts`）。

## 7. 紧急清理手段

| 场景 | 操作 |
| --- | --- |
| 想立刻清空**整个** DOS 黑名单（不问白名单） | 控制台 `dosBanClear`（`helpfulCmd.kts`，先打印被 ban 列表再 `dosBlacklist.clear()`） |
| 只解某个 IP | 控制台 `js Vars.netServer.admins.unBlacklistDos("1.2.3.4")`（Rhino JS 求值） |
| 白名单条目临时不用 | `/doswhitelist remove <序号或ip>`；全部清空用 `/doswhitelist clear confirm` |

> 紧急清空只解决当下；若触发源仍在同一出口反复 spam，黑名单会再次出现——这正是白名单要解决的问题。

## 8. 故障排查

| 现象 | 排查 |
| --- | --- |
| 玩家仍进不来、日志仍报 potential DOS attack | 1) `scriptdiag state wayzer/security/dosWhitelist` 是否 `Enabled`、DB 是否已加载（`/doswhitelist status`）；2) 该 IP 是否命中白名单（`/doswhitelist check <ip>`，黑名单判断按内核存储原始字符串精确匹配）；3) IP 是否被**其它机制**重新拉黑（反复 spam/其它风控），看 `securityGuard`/`accountIpGuard` 日志与内核控制台；4) 是否属于"并发超过 playerlimit"分支而非 DOS 黑名单（该分支白名单无法解决，需看人数上限/排障）。 |
| `/doswhitelist` 提示无权限 | 权限 `wayzer.admin.dosWhitelist` 默认 `@admin`；控制台不受限，游戏内需 4级/admin。 |
| `status` 显示 DB 加载中/失败、白名单不生效 | 检查 MdtStorage/数据库可用性；白名单加载完成后会自动补扫，无需重启。 |
| `add` 被拒绝 | 检查格式：IPv4/CIDR `/8`~`/32`、非法前缀/IP 会被拒；备注过长会被截断到 120 字符。 |
| 收不到自动解除通知 | `notifyRemovals` 是否开启、当前是否在线的管理员有该权限；日志仍在 `config/logs`。 |

## 9. 验证记录（2026-10-24）

- **两次冷启动（开发服）均通过**：banner `共找到164脚本,加载成功160,启用成功155,出错0`；`scriptdiag count` 失败 0；
  `sa listFailed` 为空；`scriptdiag state wayzer/security/dosWhitelist` = `Enabled`、`coreMindustry/menu` = `Enabled`；
  `scriptdiag commands doswhitelist` 命中；KDoc 嵌套注释扫描 **211 文件**干净。
- **功能行为未实测**（用户要求跳过）：`add`/自动解黑/持久化回读均未做运行时验证。

## 10. 未验证项 / TODO

- **功能链路未实测**：`add`→IP 进入 `dosBlacklist`→自动 `unBlacklistDos`→玩家重连成功；`list/remove/clear/check` 各子命令输出；
  重启后持久化回读；多管理员通知；大NAT真实场景连坐恢复效果。
- 需在**首次生产使用时验证**：启用后先加一个测试出口，制造/等待一次 packet spam 触发黑名单，确认 `status/list` 命中并自动解除。
- 可选增强（未实现）：IPv6 CIDR、按 IP 段统计/告警、与 `securityGuard` 的联动说明、白名单变更审计日志。

## 相关文档

- [脚本维护总览](scripts-maintenance.md)（第二十八批）
- [帮助菜单](help-menu.md)
- [安全风控](security-guard.md)
- 上游参考：`参考项目/MindustryX-main/work/core/src/mindustry/net/ArcNetProvider.java:130-131`、`Administration.java:549`
