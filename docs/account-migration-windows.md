> 首选不停服方式：使用 [`account-migration-tool.md`](account-migration-tool.md) 的跨平台迁移工具。  
> 本文的停服 SQL 仅作为应急/回滚手册。

# MDT 账户迁移操作手册（Windows）

> 适用：老账号用户名是乱填的、用户名是别人的 QQ、或需要把账号迁移到新 QQ。
> 原则：**只改 `MdtAccounts.username` 和 `MdtAccountBindings`，保留 `account:<id>` 与所有数据表，不新建账号、不搬数据。**
> 所有 SQL 操作前必须停服并备份数据库。

## 0. 准备

```powershell
$ROOT = "E:\mdtdo\mdtserver"          # 改成实际目录
$H2   = "$ROOT\libs\h2-2.0.206.jar"
$DB   = "jdbc:h2:" + ($ROOT -replace '\\','/') + "/config/scripts/data/h2DB.db"

$STAMP = Get-Date -Format "yyyyMMdd-HHmmss"
Copy-Item "$ROOT\config\scripts\data\h2DB.db.mv.db" "$ROOT\config\scripts\data\h2DB.db.mv.db.bak-$STAMP"
Copy-Item "$ROOT\config\scripts\data\config.conf" "$ROOT\config\scripts\data\config.conf.bak-$STAMP"
```

停服：

- 通过服务器控制台执行 `exit`，或停止启动脚本/计划任务；
- 确认端口已释放：
  ```powershell
  Get-NetTCPConnection -LocalPort 11451 -ErrorAction SilentlyContinue
  Get-NetTCPConnection -LocalPort 6859 -ErrorAction SilentlyContinue
  ```
  没有输出才继续。

## 1. 先查清楚，不要直接改

### 1.1 找账号

```powershell
$sql = @'
SELECT ID, USERNAME, STATUS FROM MDTACCOUNTS
WHERE ID = 123 OR USERNAME = 'old_qq_or_name';
'@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

把 `123` / `old_qq_or_name` 换成实际值。

### 1.2 查账号下的游戏主体

```powershell
$sql = @'
SELECT SUBJECT_UID, LAST_NAME, ACCOUNT_ID
FROM MDTPLAYERSUBJECTS
WHERE ACCOUNT_ID = 123;
'@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

确认 `account:123` 和对应游戏 UUID 都在；**不要删这些行**。

### 1.3 查 QQ binding

```powershell
$sql = @'
SELECT ACCOUNT_ID, BINDING_TYPE, IDENTIFIER
FROM MDTACCOUNTBINDINGS
WHERE ACCOUNT_ID = 123;
'@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

### 1.4 查邮箱/锁/奖励设置

```powershell
$sql = @'
SELECT "KEY", "VALUE" FROM MDTSETTINGS
WHERE "KEY" LIKE 'email.%'
  AND ("KEY" LIKE '%123' OR "KEY" LIKE 'email.index.%');
'@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

重点看：

```text
email.bind.123
email.lock.123
email.reward.123
email.index.<hash>
qq.index.<qq>
```

## 2. 场景 A：乱填用户名 → 新 QQ

条件：

- 账号密码可验证；
- 新 QQ 可收邮箱验证码；
- 新 QQ 未被注册。

操作：

```powershell
$accountId = 123
$newQq = "123456789"

# 先确认新 QQ 没有账号/没有绑定
$sql = @"
SELECT ID, USERNAME FROM MDTACCOUNTS WHERE USERNAME = '$newQq';
SELECT ACCOUNT_ID, IDENTIFIER FROM MDTACCOUNTBINDINGS WHERE BINDING_TYPE='QQ' AND IDENTIFIER='$newQq';
"@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql

# 改名 + 改 binding
$sql = @"
UPDATE MDTACCOUNTS SET USERNAME = '$newQq' WHERE ID = $accountId;
UPDATE MDTACCOUNTBINDINGS SET IDENTIFIER = '$newQq'
WHERE ACCOUNT_ID = $accountId AND BINDING_TYPE = 'QQ';
"@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

然后启动服务器，用**新 QQ + 原密码**登录，执行：

```text
/bindemail
```

输入 `新QQ@qq.com`，收码验证。成功后：

- 邮箱绑定；
- 1000 MDC；
- 等级/资历恢复（如果之前被锁）。

## 3. 场景 B：用户名是别人的 QQ → 迁移到自己的 QQ

和场景 A 相同，只是需要管理员确认旧账号归属：

1. 验证旧账号密码；
2. 验证新 QQ 邮箱；
3. 执行改名 SQL；
4. 启动后 `/bindemail`。

注意：旧 QQ 被释放后，别人可以注册该 QQ；这是预期行为。

## 4. 场景 C：邮箱已被其他账号绑定

先查：

```powershell
$email = "123456789@qq.com"
$hash = [BitConverter]::ToString(
  [Security.Cryptography.SHA256]::Create().ComputeHash(
    [Text.Encoding]::UTF8.GetBytes($email)
  )
).Replace('-','').ToLower()

$sql = @"
SELECT "KEY", "VALUE" FROM MDTSETTINGS
WHERE "KEY" = 'email.index.$hash'
   OR "KEY" LIKE 'email.bind.%';
"@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

如果 `email.index.<hash>` 指向另一个账号：

- **不要直接删**，先确认旧账号是否废弃/是否本人；
- 确认后，清理旧账号绑定：
  ```sql
  DELETE FROM MDTSETTINGS WHERE "KEY" = 'email.bind.<旧accountId>';
  DELETE FROM MDTSETTINGS WHERE "KEY" = 'email.index.<hash>';
  ```
- 再对目标账号执行 `/bindemail`。

如果是两个活人争同一邮箱：转人工，不要自动抢绑。

## 5. 场景 D：目标 QQ 已经存在账号

不要合并账号，不要改 `account_id`。  
处理：

- 让玩家登录已有 QQ 账号；
- 如果确实要迁移数据，另行走“账号合并”人工流程；
- 不要直接改 `MdtAccounts.ID` 或 `MDTPLAYERSUBJECTS.ACCOUNT_ID`。

## 6. 场景 E：迁移后等级还是 2 级

原因：`email.lock.<id>` 快照还在，但 `/bindemail` 没成功，或快照被清掉。

查：

```powershell
$sql = @'
SELECT "KEY", "VALUE" FROM MDTSETTINGS
WHERE "KEY" LIKE 'email.lock.%' OR "KEY" LIKE 'email.reward.%';
'@
java -cp $H2 org.h2.tools.Shell -url $DB -user "" -password "" -sql $sql
```

- 如果 `email.bind.<id>` 为空：让玩家重新 `/bindemail`；
- 如果已绑定但等级没恢复：检查 `email.lock.<id>` 的值是否为 `原信任|原资历`；
- 不要手动给 3++/4 锁级。

## 7. 场景 F：奖励没发/重复发

查：

```sql
SELECT "KEY","VALUE" FROM MDTSETTINGS WHERE "KEY"='email.reward.123';
```

- 没有 `done`：让玩家重新补绑，脚本会发 1000；
- 已是 `done`：不会重复发；
- 不要手工改 MDC 表，除非有审计记录。

## 8. 验证

启动服务器后：

1. `scriptdiag state wayzer/user/emailBind` = Enabled；
2. 用新 QQ + 原密码登录；
3. `/bindemail` 收码绑定；
4. `/emailbind 123` 显示新邮箱；
5. 检查 MDC = 原值 + 1000；
6. 检查信任/资历是否恢复；
7. 检查 `MDTPLAYERSUBJECTS` 的 `ACCOUNT_ID` 未变。

## 9. 回滚

```powershell
Copy-Item "$ROOT\config\scripts\data\h2DB.db.mv.db.bak-$STAMP" `
          "$ROOT\config\scripts\data\h2DB.db.mv.db" -Force
Copy-Item "$ROOT\config\scripts\data\config.conf.bak-$STAMP" `
          "$ROOT\config\scripts\data\config.conf" -Force
```

然后重启服务器。

## 10. 红线

- 不在服务器运行时改 H2；
- 不删 `MDTPLAYERSUBJECTS`；
- 不改 `account_id`；
- 不新建账号再搬数据；
- 不手工改 `MdtAccounts.ID`；
- 不在没有备份时执行 UPDATE/DELETE。
