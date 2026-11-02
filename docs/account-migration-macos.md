> 首选不停服方式：使用 [`account-migration-tool.md`](account-migration-tool.md) 的跨平台迁移工具。  
> 本文的停服 SQL 仅作为应急/回滚手册。

# MDT 账户迁移操作手册（macOS）

> 适用：老账号用户名是乱填的、用户名是别人的 QQ、或需要把账号迁移到新 QQ。
> 原则：**只改 `MdtAccounts.username` 和 `MdtAccountBindings`，保留 `account:<id>` 与所有数据表，不新建账号、不搬数据。**
> 所有 SQL 操作前必须停服并备份数据库。

## 0. 准备

```bash
ROOT="$HOME/dsh/mdt-do/server/mdtserver"
H2="$ROOT/libs/h2-2.0.206.jar"
DB="jdbc:h2:$ROOT/config/scripts/data/h2DB.db"

STAMP=$(date +%Y%m%d-%H%M%S)
cp "$ROOT/config/scripts/data/h2DB.db.mv.db" \
   "$ROOT/config/scripts/data/h2DB.db.mv.db.bak-$STAMP"
cp "$ROOT/config/scripts/data/config.conf" \
   "$ROOT/config/scripts/data/config.conf.bak-$STAMP"
```

停服：

- 前台启动的按 `Ctrl+C`；
- 后台启动的停止对应进程；
- 确认端口释放：
  ```bash
  lsof -nP -iTCP:11451 -sTCP:LISTEN
  lsof -nP -iTCP:6859 -sTCP:LISTEN
  ```
  没有输出才继续。

## 1. 先查清楚，不要直接改

### 1.1 找账号

```bash
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT ID, USERNAME, STATUS FROM MDTACCOUNTS WHERE ID = 123 OR USERNAME = 'old_qq_or_name';"
```

### 1.2 查游戏主体

```bash
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT SUBJECT_UID, LAST_NAME, ACCOUNT_ID FROM MDTPLAYERSUBJECTS WHERE ACCOUNT_ID = 123;"
```

确认 `account:123` 和游戏 UUID 都在；**不要删这些行**。

### 1.3 查 QQ binding

```bash
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT ACCOUNT_ID, BINDING_TYPE, IDENTIFIER FROM MDTACCOUNTBINDINGS WHERE ACCOUNT_ID = 123;"
```

### 1.4 查邮箱/锁/奖励

```bash
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT \"KEY\", \"VALUE\" FROM MDTSETTINGS WHERE \"KEY\" LIKE 'email.%' AND (\"KEY\" LIKE '%123' OR \"KEY\" LIKE 'email.index.%');"
```

## 2. 场景 A：乱填用户名 → 新 QQ

```bash
ACCOUNT_ID=123
NEW_QQ=123456789

# 确认新 QQ 未被占用
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT ID, USERNAME FROM MDTACCOUNTS WHERE USERNAME='$NEW_QQ'; SELECT ACCOUNT_ID, IDENTIFIER FROM MDTACCOUNTBINDINGS WHERE BINDING_TYPE='QQ' AND IDENTIFIER='$NEW_QQ';"

# 改名 + 改 binding
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "UPDATE MDTACCOUNTS SET USERNAME='$NEW_QQ' WHERE ID=$ACCOUNT_ID; UPDATE MDTACCOUNTBINDINGS SET IDENTIFIER='$NEW_QQ' WHERE ACCOUNT_ID=$ACCOUNT_ID AND BINDING_TYPE='QQ';"
```

启动服务器，用新 QQ + 原密码登录，执行 `/bindemail`，输入 `新QQ@qq.com`。

成功后：

- 邮箱绑定；
- 1000 MDC；
- 等级/资历恢复（如之前被锁）。

## 3. 场景 B：用户名是别人的 QQ

同场景 A，但必须先验证旧账号密码，并由管理员确认归属。旧 QQ 释放后别人可注册，属预期。

## 4. 场景 C：邮箱已被其他账号绑定

```bash
EMAIL="123456789@qq.com"
HASH=$(printf '%s' "$EMAIL" | shasum -a 256 | awk '{print $1}')

java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT \"KEY\", \"VALUE\" FROM MDTSETTINGS WHERE \"KEY\"='email.index.$HASH' OR \"KEY\" LIKE 'email.bind.%';"
```

确认旧账号废弃/本人后，清理旧绑定：

```sql
DELETE FROM MDTSETTINGS WHERE "KEY"='email.bind.<旧accountId>';
DELETE FROM MDTSETTINGS WHERE "KEY"='email.index.<hash>';
```

再对目标账号执行 `/bindemail`。活人争同一邮箱：转人工。

## 5. 场景 D：目标 QQ 已有账号

不要合并账号，不要改 `account_id`。让玩家登录已有账号；确需合并走人工流程。

## 6. 场景 E：迁移后仍是 2 级

```bash
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT \"KEY\", \"VALUE\" FROM MDTSETTINGS WHERE \"KEY\" LIKE 'email.lock.%' OR \"KEY\" LIKE 'email.reward.%';"
```

- `email.bind.<id>` 为空：重新 `/bindemail`；
- 已绑定但没恢复：检查 `email.lock.<id>` 是否为 `原信任|原资历`；
- 不要手动锁 3++/4。

## 7. 场景 F：奖励没发/重复发

```bash
java -cp "$H2" org.h2.tools.Shell \
  -url "$DB" -user "" -password "" \
  -sql "SELECT \"KEY\",\"VALUE\" FROM MDTSETTINGS WHERE \"KEY\"='email.reward.123';"
```

没有 `done`：重新补绑；已是 `done`：不会重复发。

## 8. 验证

1. `scriptdiag state wayzer/user/emailBind` = Enabled；
2. 新 QQ + 原密码登录；
3. `/bindemail` 收码绑定；
4. `/emailbind 123` 显示新邮箱；
5. MDC = 原值 + 1000；
6. 等级/资历恢复；
7. `MDTPLAYERSUBJECTS.ACCOUNT_ID` 未变。

## 9. 回滚

```bash
cp "$ROOT/config/scripts/data/h2DB.db.mv.db.bak-$STAMP" \
   "$ROOT/config/scripts/data/h2DB.db.mv.db"
cp "$ROOT/config/scripts/data/config.conf.bak-$STAMP" \
   "$ROOT/config/scripts/data/config.conf"
```

重启服务器。

## 10. 红线

- 不在服务器运行时改 H2；
- 不删 `MDTPLAYERSUBJECTS`；
- 不改 `account_id`；
- 不新建账号再搬数据；
- 不手工改 `MdtAccounts.ID`；
- 不在没有备份时执行 UPDATE/DELETE。
