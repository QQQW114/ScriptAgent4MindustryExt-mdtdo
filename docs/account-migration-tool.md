# MDT 账号运行中迁移工具（macOS / Windows）

> 不需要停服、不直接打开 H2 文件。  
> 工具只写请求文件，运行中的游戏服 `accountMigrate.kts` 在自己的数据库事务里改名。

## 1. 原理

```text
管理员运行 migrate-account.py
  → 写 config/stats/account-migrate-requests.jsonl
  → 游戏服 accountMigrate.kts 轮询
  → 在游戏进程内 transaction 改 username + QQ binding
  → 写 config/stats/account-migrate-results.jsonl
  → 工具读取结果并退出
```

保留：

```text
account:<id>
MDTPLAYERSUBJECTS
MDC / 称号 / 成就 / 资历 / 其他数据
```

只改：

```text
MDTACCOUNTS.USERNAME
MDTACCOUNTBINDINGS(QQ).IDENTIFIER
qq.index.<newQq>
```

## 2. 文件

```text
scripts/wayzer/user/accountMigrate.kts   游戏服侧
tools/migrate-account.py                 跨平台 CLI
tools/migrate-account.sh                 macOS/Linux 包装
tools/migrate-account.bat                Windows 包装
```

## 3. 部署

1. 把 `accountMigrate.kts` 同步到游戏服脚本目录；
2. 冷启动或热重载，确认：
   ```text
   scriptdiag state wayzer/user/accountMigrate
   ```
   显示 Enabled；
3. 把 `tools/` 放到能访问 `mdtserver` 目录的机器上（通常就是服务器本体）。

## 4. 使用

### macOS

```bash
cd /path/to/repo
./tools/migrate-account.sh \
  --server-dir /path/to/mdtserver \
  --account-id 123 \
  --new-qq 123456789
```

### Windows

```bat
cd /d E:\path\to\repo
tools\migrate-account.bat ^
  --server-dir E:\mdtdo\mdtserver ^
  --account-id 123 ^
  --new-qq 123456789
```

也可以直接：

```bat
py -3 tools\migrate-account.py --server-dir E:\mdtdo\mdtserver --account-id 123 --new-qq 123456789
```

### 先查询，不改

```bash
./tools/migrate-account.sh --server-dir /path/to/mdtserver --account-id 123 --check
```

```bat
tools\migrate-account.bat --server-dir E:\mdtdo\mdtserver --account-id 123 --check
```

### 旧邮箱与目标 QQ 不一致

工具默认拒绝，避免误清绑定。确认旧邮箱废弃/本人后：

```bash
./tools/migrate-account.sh --server-dir /path/to/mdtserver \
  --account-id 123 --new-qq 123456789 --clear-email
```

```bat
tools\migrate-account.bat --server-dir E:\mdtdo\mdtserver ^
  --account-id 123 --new-qq 123456789 --clear-email
```

`--clear-email` 会清掉旧 `email.bind`/`email.index`，迁移后必须让玩家重新 `/bindemail`。

## 5. 成功后的动作

```text
玩家用新 QQ 登录
  → /bindemail
  → 输入 新QQ@qq.com
  → 收验证码并验证
  → 绑定邮箱
  → 发 1000 MDC
  → 恢复等级/解锁
```

如果旧邮箱已经正确且就是新 QQ 邮箱，可不加 `--clear-email`，但账号绑定邮箱仍需通过 `/bindemail` 完整验证。

## 6. 常见错误

| 输出 | 处理 |
|---|---|
| 目标 QQ 已存在账号 | 不合并；让玩家登录已有账号，或走人工合并 |
| 账号已绑定邮箱 ... 不一致 | 确认后加 `--clear-email`，或先 `/emailbind <id> reset` |
| account:xxx 不存在 | 先 `/emailbind`/后台查账号 ID；不要猜 |
| 等待超时 | 确认 `accountMigrate.kts` 已 Enabled、`--server-dir` 指向同一个 mdtserver |
| 新 QQ 格式错误 | 5-12 位数字，不能以 0 开头 |
| 数据库更新失败 | 查看游戏服日志；不要重复执行，先确认当前用户名 |

## 7. 回滚

工具本身可反向迁移：

```bash
./tools/migrate-account.sh --server-dir /path/to/mdtserver \
  --account-id 123 --new-qq 旧QQ
```

如果数据库已被误改且无法反向，使用停服备份回滚（见 Windows/macOS 迁移手册的备份章节）。  
正常使用工具不需要停服。

## 8. 安全

- 工具只应在服务器本机运行；队列目录不要暴露给 Web/玩家；
- `config/stats/account-migrate-*.jsonl` 只允许管理员读写；
- 不要把 `--clear-email` 当默认操作；
- 迁移前先 `--check`，确认账号 ID 和旧 QQ；
- 涉及争议账号（别人 QQ）必须先人工确认归属。

## 9. 已验证

- macOS 本机：`--check` 走通队列，返回 `account:165 当前用户名=10000001`；
- 游戏服冷启动：`accountMigrate.kts` Enabled，banner 出错 0；
- 实际改名需在测试账号上执行一次后确认登录/数据/奖励。
