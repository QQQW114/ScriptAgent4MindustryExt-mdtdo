# 本地开发、调试与排障手册

> 面向所有开发与协作 Agent（Windows PowerShell 5.1 / PowerShell 7+）。  
> 核心原则：**所有检查、冷启动、热重载、控制台交互与冒烟验证均通过 `tools/mdt-dev.ps1` 完成，不要手写临时启动脚本或粗暴杀全机 Java 进程。**

---

## 1. 环境准备

### 情况 A：本机主开发工作区（`server/mdtdo/`）
目录已自带 `mdtserver/`、`stats-web/`、`email-service/`、`updater/` 与 `tools/`，**零配置直接使用**：

```powershell
cd server\mdtdo
pwsh -NoProfile -File tools\mdt-dev.ps1 status
```

### 情况 B：协作者独立克隆开源仓库（`ScriptAgent4MindustryExt-mdtdo/`）
1. **无服务端环境**：直接运行 `tools\mdt-dev.ps1 check` 即可完成全部静态代码、依赖、编码与文档卫生体检。
2. **有本地测试服**：复制 `tools/dev-local.example.json` 为 `tools/dev-local.json`（已 `.gitignore`，不入库），填入本机 `mdtserver` 绝对路径：
   ```json
   {
     "serverDir": "D:\\mdtserver",
     "socketHost": "127.0.0.1",
     "socketPort": 6859,
     "gamePort": 6567
   }
   ```
   确保本机测试服 `server.properties` 已开启 `socketInput=true`（仅绑定 `localhost`），并使用本仓库新版 `mdtserver/start-server.ps1`（含 `-Once` / `-Headless` / `-PidFile` 支持）。随后执行带 `-SyncToServer` 的命令即可自动同步 `scripts/`（自动保护 `data/`、`external-cp/`、`cache/`）并跑完整验收。

---

## 2. 统一调试 CLI 速查（`tools/mdt-dev.ps1`）

| 命令 | 适用场景 | 行为与验收标准 |
|---|---|---|
| `mdt-dev.ps1 check` | 每次改完代码或文档必跑 | 7 项静态门禁：KDoc 嵌套 `/*`、`listen<T>` 误用、`contextScript` ↔ `@file:Depends` 缺失、`.ps1` BOM / `.bat` ASCII+CRLF、文档防退化 Linter、敏感文件防泄漏、双仓漂移 |
| `mdt-dev.ps1 start [-ClearCache] [-ForceRestart] [-SyncToServer]` | 启动后台无头开发服持续调试 | 检查端口 → 可选清缓存 → 调用 `start-server.ps1 -Once -Headless` → 等待新控制台日志出现 `共找到N/加载N/启用N/出错0` 与 `Server loaded.` → 自动经 6859 复核 `scriptdiag count` 与 `sa listFailed`；超时自动抓 `jstack` |
| `mdt-dev.ps1 stop` | 停止开发服 | 仅按 PID 文件与本工作区命令行精准结束进程树（绝不误杀其他 Java 进程），等待 6567/6859 释放 |
| `mdt-dev.ps1 status` | 查看本机服务状态 | 输出托管进程 PID、6567/6859/8090/40150 端口状态、在线 `scriptdiag count` / `sa listFailed` 与双仓差异 |
| `mdt-dev.ps1 cmd "<指令>"` | 向运行中开发服发送控制台指令 | 严格 UTF-8 无 BOM 原始字节 + `\n` 发送，自动读取回显（例：`cmd "scriptdiag commands externalcp"`） |
| `mdt-dev.ps1 reload -Scripts <id1,id2> [-Scan] [-SyncToServer]` | 调试纯 `.kts` 脚本快速热重载 | 先跑静态体检 → 拒绝 `.kt` 库热重载 → 逐个 `sa load` 并监听 `script-load/current.log` → 自动校验 `scriptdiag state` 为 `Enabled failReason=无` 与 `sa listFailed` 空 |
| `mdt-dev.ps1 verify [-Mode cold\|hot] [-ClearCache] [-KeepRunning] [-SyncToServer]` | **提交 PR 前标准一键验收** | `cold`：静态体检 → 冷启动 → 全量校验 → 自动停服释放端口（除非 `-KeepRunning`）；`hot`：静态体检 → 热重载校验 |
| `mdt-dev.ps1 logs [-Type console\|game\|script\|error] [-Tail 80]` | 快速排查最新日志 | 一键读取最新 `console-*.log`、`log-0.txt`、`script-load/current.log` 或 `script-load/last-error.log` |
| `mdt-dev.ps1 smoke-web [-Port 18090]` | Web 门户改动冒烟 | 在独立临时目录与端口起实例，验证 `/api/health`、`/api/status`、`/api/events` 与三页面均 200 后自动清理 |
| `mdt-dev.ps1 smoke-email [-Port 40159]` | 邮件服务改动冒烟 | 用临时 `mockSend=true` 配置验证 `/health`(200)、无 Token(401)、非 QQ 域名(400)、合法 mock 发信(200) 后自动清理 |

### 主工作区双仓同步（`tools/sync-repo.ps1`，仅主工作区使用）

```powershell
# 查看 6 个同步域（scripts / docs / stats-web / updater / email-service / tools）差异
pwsh -NoProfile -File tools\sync-repo.ps1 -Action status

# 从开源嵌套仓拉取到本地工作区（始终保护 data / external-cp / cache / web-data / 凭据配置）
pwsh -NoProfile -File tools\sync-repo.ps1 -Action pull -DryRun
pwsh -NoProfile -File tools\sync-repo.ps1 -Action pull

# 将本地已验收改动推送同步至开源嵌套仓（仅镜像文件，不自动 git commit/push）
pwsh -NoProfile -File tools\sync-repo.ps1 -Action push -DryRun
pwsh -NoProfile -File tools\sync-repo.ps1 -Action push
```

- 嵌套仓位于 `mdc-do` 分支时，`sync-repo.ps1` 会自动跳过 `email-service` 及邮箱绑定专属文件；
- 嵌套仓处于非常规分支时默认拒绝同步，防止误将代码同步到过期分支。

---

## 3. 日志体系与游戏内诊断指令

### 正式日志位置（均位于 `mdtserver/config/logs/`）

| 文件 | 记录内容 | 适用排查场景 |
|---|---|---|
| `console-<yyyyMMdd-HHmmss>.log` | 启动器实时落盘的 Java 完整 stdout + stderr（已清理 ANSI 码与 JLine 重绘） | **排查首选**：SA 顶层初始化异常、主线程 `StackOverflowError` / `Error` 堆栈、完整加载输出 |
| `log-0.txt` / `last.log` | Mindustry 引擎运行日志 | 启动汇总 `共找到N脚本,加载成功N,启用成功N,出错0`、`Server loaded.`、地图与业务日志 |
| `script-load/current.log` / `last.log` | `java.util.logging` 记录的 SA 逐脚本编译、加载、依赖与 DB 初始化日志 | 定位具体脚本编译报错行号、热重载完成判定 |
| `script-load/last-error.log` | 启动器自动过滤的脚本警告与错误快照 | 快速确认是否有新增编译或运行期警告 |
| `restart-supervisor.log` | 生产守护启动器的崩溃退避记录 | 排查异常退出码与崩溃重启频次 |

### `scriptdiag` 控制台诊断指令（`wayzer/cmds/scriptDiag.kts`）

当指令或菜单入口疑似消失时，**不要只看 `[Enable]` 和 `出错0`**，直接经 `mdt-dev.ps1 cmd` 核验：

- `scriptdiag count`：脚本总数 / 已加载 / 已启用 / 失败 + 根指令注册数；
- `scriptdiag commands [过滤词]`：按指令名或别名搜索已注册的根指令，直接判定指令是否真的注册成功；
- `scriptdiag state <脚本id>`：查看单个脚本状态与 `failReason`；
- `scriptdiag failed`：列出 `failReason` 非空的脚本（配合 `sa listFailed` 使用）。

---

## 4. ScriptAgent 3.4.0 与 Windows 常见坑速查

| 现象 | 根因 | 正确做法 |
|---|---|---|
| 脚本显示 `[Enabled]`、`出错0`，但注册的指令报“无效指令” | **SA 3.4.0 编译器 Bug**（上游 issue #50）：KDoc 块注释内出现字面量 `/*`（如 `` `bundles/**` ``）会静默截断 `.ktc` 字节码，截断点后的顶层 `command()` 与 `onEnable` 全部丢失 | 块注释内绝不写 `/*`（改写文字描述）；改完跑 `mdt-dev.ps1 check`；可用 `javap -p -cp <cache.ktc>` 核对主类方法是否被截断 |
| 监听器编译通过且 `Enabled`，但事件永远不触发 | `listen<T>` 只挂 `arc.Events` 原生总线；SA 自定义事件（实现 `cf.wayzer.scriptAgent.Event`、用 `emitAsync()` 派发）必须用 `listenTo<T>` | 按事件定义选 API；`listenTo<T>` 内事件是 receiver（无 `it`，早退用 `return@listenTo`）；交由 `mdt-dev.ps1 check` 扫描 |
| 某脚本编译报 `Unresolved reference` 或级联失败 | ① 误写 `import mindustry.core.Core`（应为 `arc.Core`）；② 脚本顶层用了 `private const val`（应改 `private val`）；③ 跨脚本 `contextScript<T>()` 漏写 `@file:Depends(...)` | 先修最底层根脚本编译错；`mdt-dev.ps1 check` 自动检查 `@file:Depends` |
| 服务端端口在线、Socket 可连，但所有游戏内命令与世界 tick 冻结（“假活”） | 命令内抛出 `Error`（如 `with(receiver) { 同名函数() }` 自递归导致 `StackOverflowError`）；SA 只 `catch (Exception)`，`Error` 直接杀掉主线程 `HeadlessApplication` | 转发包装函数一律用显式接收者 `receiver.fn(...)`；假活必须重启，堆栈只在 `console-*.log` / stderr |
| 改了 `wayzer/lib/*.kt` 或 `vote.lib.kt` 后热重载报 `NoSuchMethodError` 或出现双单例 | 模块库热重载会产生同名类与静态单例双份，且 `sa load` 不级联重编已加载依赖方 | 严禁热重载 `.kt` 文件；必须执行 `mdt-dev.ps1 verify -Mode cold -ClearCache` |
| `sa load <脚本>` 没有任何回显 | 源码未变化时 SA 命中 `.ktc` 缓存静默 no-op，不是命令失效 | 以 `script-load/current.log` 与 `scriptdiag state <id>` 为准；删掉临时脚本后需手动 `sa unload <id>` |
| 6859 Socket 发命令回显“无效指令”或无响应 | 发送字节带了 UTF-8 BOM，或连续发送多条 `config socketInput*` 触发 `ServerControl` 监听线程重启竞态 | 统一用 `mdt-dev.ps1 cmd`（UTF-8 无 BOM + `\n`）；新版 `start-server.ps1` 已按 `false → port → address → true` 消除竞态 |
| PowerShell 5.1 执行 `.ps1` 中文乱码或莫名语法错 | `.ps1` 缺少 UTF-8 BOM，或字符串里混入全角弯引号 | 所有 `.ps1` 存为 UTF-8 with BOM、禁用全角弯引号；`.bat` 存为纯 ASCII + CRLF；由 `mdt-dev.ps1 check` 自动校验 |
| `jstack` 查冷启动卡死 | 启动器用错误管道读取方式会导致 `ConsoleHandler` 死锁 | 统一使用 `mdt-dev.ps1 start`（文件落盘 + 保持 stdin 打开）；卡死直接看 `jstack <pid>` |

---

## 5. H2 数据库本地排查速查

默认数据库：`mdtserver/config/scripts/data/h2DB.db`（`user=""`、`password=""`，仅在停服或确认文件锁安全时离线查询）：

```powershell
java -cp mdtserver\libs\h2-2.0.206.jar org.h2.tools.Shell `
  -url "jdbc:h2:$((Resolve-Path mdtserver\config\scripts\data\h2DB.db).Path)" `
  -user "" -password "" -sql "SHOW TABLES;"
```

- `INFORMATION_SCHEMA.TABLES` 表名为全大写（如 `MDTSTATSPLAYERS`），但 `MdtSettings` 列名为小写 `"key"` / `"value"`（`preserveKeywordCasing=true`）；
- `MdtStorage.transaction` 为私有，跨脚本只能调用其公开读写函数，不直接开事务。
