# Agent 调试经验与常见坑（避免重复踩坑）

> 目的：记录本项目中"卡很久、反复试错才找到根因、但解决后可复用"的经验，方便后续 Agent 不踩坑。
> 每节格式：现象 → 根因 → 正确做法。全部来自实际验证（含证据），不是推测。
> 更新：2026-08-22 首次整理（本轮统计 Web 与权限修复过程中沉淀）。

---

## 1. 服务器冷启动测试：启动器选错会"静默卡死"，且看起来像脚本问题

本项目的验证都以冷启动 + 控制台/命令 Socket 为准，启动方式不对会白等半小时。

### 1.1 不要用"自定义 .NET Task 读取 stdout 管道"的方式启动 java

- **现象**：`System.Diagnostics.Process` + `RedirectStandardOutput=true` + 自定义
  `[System.Threading.Tasks.Task]::Run([Action]{ ReadLine()... })` 读取输出 → java 启动后
  停在 `Loaded 1 data asset files / 3 mods loaded`，随后 **5 分钟无任何 ScriptAgent 日志**；
  日志里没有报错，`jstack` 显示主线程卡在 `runBlocking(bootstrap)`，
  `DefaultDispatcher-worker` 卡在 `java.util.logging.ConsoleHandler.publish` 的 Handler 锁上。
- **根因**：JVM 的 ConsoleHandler 往 stdout 写日志时，如果那个管道**没有人实际读取**
  （PowerShell 的 `Task.Run([Action]{...})` 闭包在跨线程时直接 Faulted，且错误不可见；
  `add_OutputDataReceived` 事件处理器则报 "There is no Runspace available"），
  管道写满 → ConsoleHandler 锁死 → ScriptAgent 初始化协程永远等不到 logger。
  这是"先写日志后初始化"的经典死锁，和我们的脚本代码毫无关系。
- **正确做法**：**控制台输出重定向到文件，而不是管道**——用一个 `cmd` 包装器（.cmd）执行：
  `java ... > config\logs\xxx-console.log 2>&1`；文件写入不会阻塞，ConsoleHandler 永不死锁。
  stdin 用 .NET `RedirectStandardInput = true` 保持打开（避免控制台读到 EOF 自动退出）；
  命令交互走 **6859 命令 Socket**（`socketInput true`），不要走 stdin。
- 参考实现：`.agents\stats-run.cmd` + `.agents\stats-test.ps1` / `verify-run.ps1`（可直接复用）。

### 1.2 "启动后没有输出"先查这几件事，再怀疑脚本

- java 控制台输出被重定向到**固定文件名**（`stats-run.cmd` 写死 `stats-test-console.log`），
  测试脚本却去读 `verify-console.log` → 永远超时。**先确认重定向目标与读取路径一致**。
- 直接 `& java ... | Out-File`（pwsh 管道方式）启动：进程 stdin 是 EOF → 控制台提示
  `Catch EndOfFile, again to exit application` → 开始退出流程 → 服务器加载后可能立即自杀。
- 前一个 java（尤其 `Kill(true)` 强杀）留下了 TIME_WAIT/端口占用 → 新进程卡在绑定端口。
  **重启前检查 6567/6859/10099 无 LISTEN**（`Wait-PortsFree` 可等待 90 秒）。
- 判据混淆：日志里"没有 ScriptAgent 输出"不等于"脚本没编译"——编译日志只在 stdout 文件里，
  JUL 的 `log-0.txt` 只记录 Mindustry 自己的 logger。**以 `共找到N脚本...` 行 + `config/scripts/cache`
  目录 mtime 为准判断加载完成。**

### 1.3 jstack 是诊断"静默卡死"的第一工具

- `jstack <pid>` 直接给出主线程/工作线程栈，一次就能定位是 Wait 在什么锁上。
- 本环境 `jstack` 可用（JDK 安装版），不要靠猜。

---

## 2. Windows PowerShell 编码与进程陷阱

- **`powershell.exe`（5.1）读 UTF-8 无 BOM 的 .ps1 会按 ANSI/GBK 解析**：中文全变乱码，
  报出一堆莫名其妙的语法错误（"Unexpected token"、"Missing closing '}'"）。
  对策：a) 用 `pwsh`（7+，默认 UTF-8）执行；b) 给面向 5.1 的 .ps1 写 UTF-8 **BOM**
  （项目惯例见 `start-server.ps1`——它带 BOM，5.1 可运行）。
- **`.cmd` 文件只能写 ASCII**：`cmd.exe` 按 ANSI 读取 .cmd，中文路径会乱码。
  对策：用环境变量传路径（`cd /d "%MDT_WORKDIR%"`），.cmd 本身保持纯 ASCII。
  `.bat` 同理：UTF-8 的 bat 里放中文注释，cmd 会把中文行按 GBK 拆成"命令"，
  出现 `'xx' is not recognized as an internal or external command` 且命令被拦腰截断——
  **启动器 .bat/.cmd 一律纯 ASCII**（中文提示放被调用的 .ps1 里，配合 `chcp 65001`）。
- **PowerShell 会把全角弯引号 `“ ”`（U+201C/U+201D）当作字符串引号**：`.ps1` 里写
  `Write-Host "...“提升(管理员权限)”..."` 会在弯引号处截断字符串，运行时报
  `The term '管理员权限' is not recognized as a name of a cmdlet`，
  但 `ParseFile` 却显示"PARSE OK"、错误行号常显示 1——**极易误判为别的问题**。
  对策：**.ps1 内不要使用全角弯引号**（用「」、（）或去除）。
- **PowerShell 里直接 `git commit -m "多行中文..."`**：git 会把消息尾部解析成 pathspec 报错；
  `-F <文件>` 最稳，但**消息文件不要带 BOM**（BOM 会混进 commit subject 首字符，表面看不出来，
  用 `git log --format=%s` 才发现）。追加参数用 `-q` 并用 `git log -1` 复核。
- **HttpListener 在 Windows 上的"拥有者"是 PID 4（System/HTTP.sys）**：`Get-NetTCPConnection`
  的 OwningProcess=4，直接杀 4 无效。必须先找到实际监听进程（`Get-CimInstance Win32_Process |
  Where CommandLine -match 'start-web'`）再 Stop-Process；`Stop-Job` 常常留下孤儿子进程。
- **HttpListener 非本机前缀需要 URL ACL 或管理员权限**：`-Bind 0.0.0.0` 会抛"拒绝访问"，
  捕获后提示用户用管理员运行或回退 `127.0.0.1`（`start-web.ps1` 已内置该提示）。

---

## 3. ScriptAgent / Kotlin 脚本常见编译坑

- **`import mindustry.core.Core` 是错的**：脚本环境里 `Core` 是 **`arc.Core`**
  （`performanceGuard.kts` 也是 `import arc.Core`）。写错只报 `Unresolved reference 'Core'`，
  且只有编译该脚本的那一行，容易看漏（级联失败时先看"编译脚本 xxx 失败"的两行错误）。
- **`private const val` 在脚本里不允许**（"Const 'val' is only allowed on top level..."）：
  脚本顶层会被编译进脚本类，`const` 随之变成类成员属非法；改 `private val`。
  曾导致 21 个依赖脚本级联加载失败——排查顺序：先修"编译失败"的根脚本。
- **跨脚本函数引用**：`private val trustLevel = contextScript<TrustLevel>()` +
  `import wayzer.user.TrustLevel`（contextScript 类型参数 = 脚本文件名派生的类名）；
  同一包内直接 `with(trustLevel) { ... }` 调用其公有函数。
- **事件系统**：自定义事件放 `wayzer/lib/TrustSystemEvents.kt`（`data class X : Event` +
  companion `Event.Handler`）；发射 `launch { X(...).emitAsync() }`；监听 `listen<X> { }`。
- **lib .kt 通过 `@file:Depends` 链可见**：要用 `wayzer.MapLoadFinishedEvent` 必须
  `@file:Depends("wayzer/maps")`（该事件定义在 maps 相关脚本的命名空间里），否则
  `Unresolved reference`——**这是按依赖声明可见，不是全量可见**。
- `.kts`/`.kt` 脚本若引用了不存在的变量（如删除后残留），编译器给 `Unresolved reference`；
  本次还遇到"删了变量但某处还引用"的低级遗漏——改完全局搜一遍被删符号名。
- **命令权限 DSL**：`permission = "..."` 已被 `requirePermission` 取代（旧写法还能用，仅警告）；
  关键事实：`CommandInfo.handle()` = **先执行全部 attr（含 Permission）再执行 body**，
  因此菜单/帮助"快速跳转"（`RootCommands.handleInput`）与手打指令是**同一条权限路径**，
  不存在跳转越权（本次世界处理器排查结论，见 `worldProcessorAdmin.kts` 注释）。

---

## 4. 数据库（H2 + Exposed）排查

- **连接凭证**：`coreLibrary/DBConnector.kts` 默认 `user=""`、`password=""`；
  H2 Shell 连：`java -cp libs/h2-2.0.206.jar org.h2.tools.Shell -url "jdbc:h2:<绝对路径>/h2DB.db" -user "" -password "" -sql "..."`。
- **表名大小写**：`INFORMATION_SCHEMA.TABLES` 里是 `MDTSTATSPLAYERS` 这种全大写；
  实际表列名保留创建时的原始大小写，且 `preserveKeywordCasing=true`：
  `MdtSettings` 的列是 **小写 `key`/`value`**——SQL 里 `"KEY"` 报 Column not found，`"key"` 才行。
- **Exposed 坑**：`Settings.id` 是 `EntityID<String>`，当 Map key 要 `.value`，
  `inList(listOf("a","b"))` 有 EntityID 重载可以直接传 `List<String>`；
  `MdtStorage.transaction` 是私有——**跨脚本只能调用它的公开读写函数**，不能直接开事务。
- **验证表是否创建**：看启动日志 `[Exposed] ... create tables statements took Xms`，
  或连库查 `INFORMATION_SCHEMA.TABLES`；比看代码可靠。

---

## 5. 服务端运行机制速查（少走弯路）

- 脚本加载完成标志：控制台 **`共找到N脚本,加载成功N,启用成功N,出错N`**；编译耗时约 6~35s，
  "编译脚本 xxx 失败"两行里第一行是错误位置（行号 : 列号）。
- 命令 Socket 交互：TCP 6859，**发 UTF-8 原始字节 + `\n`**（不要用 StreamWriter，会带 BOM 导致"无效指令"）；回复用 `DataAvailable + Read` 循环读。
- **本机开发服务端（2026-09-13 起，用户授权"这不是生产服，怎么方便怎么来"）**：`server.properties` 已置
  `socketInput=true`（备份 `server.properties.bak-menu`，还原=换回该文件并重启启动器）。
  于是 Agent **可以直接用 6859 命令 Socket 发控制台命令**，不必再麻烦用户手敲。
  菜单/脚本改动的最小热重载链路：`sa reload coreMindustry`（`lib/**` 属于模块，改了必须重编模块）
  → 再 reload 依赖它的业务脚本（`sa reload wayzer/user/wiki`、`sa reload wayzer/user/forumPosts`）。
  注意 `coreLibrary/commands/hotReload.kts` 的文件监视器**默认不开**，且 `onEnter` 跳过了 `lib` 目录，
  所以"存盘自动重载"对 `lib/**` 无效——改了 `lib` 下文件必须显式 reload 模块。
  另：socketInput 由启动器读 `server.properties` 生成启动参数，**改了配置要重启整个启动器**
  （只 kill java 不够——正在运行的 `start-server.ps1` 内存里还是旧配置，会把它写成 `false`）。
  测试实例仍走启动参数 `config socketInput true`（只传这一条，避免 socketConfigChanged 竞态）。
- 数据库/文件操作不要放游戏线程：现有模式 = 事件监听器只往 `ConcurrentLinkedQueue` 投递，
  `Dispatchers.IO` 协程单写者处理 + 内存态 `@Volatile` 快照给游戏线程读（参考
  `ext/serverStats.kts`、`reGrief/trafficMonitor.kts`）。

---

## 6. 验证习惯（本项目约定，避免"验证了个寂寞"）

- 冷启动验证后必须：杀 java 进程树、确认 6567/6859/10099 释放、删除测试日志/临时文件
  （`config/stats/`、`config/logs/stats-test-*.log`、`.agents/*.png` 等）；
  `mdtserver/config/stats/` 已加入 `.gitignore`，不应入库。
- 控制台/指令能验证的：权限拦截、状态展示、开关往返、脚本加载——用命令 Socket 实测；
  需要真实客户端的（加入/等级变化/菜单点击）标记为"未覆盖边界"写进维护文档。
- 大改动先跑一次"加载计数"基线，任何回归立刻在计数上暴露。

---

## 7. 提交与推送惯例（维护项目时直接照做）

- mdtdo 只本地提交（主题 `feat/fix(scope): 中文摘要` + `-m` 多行正文用 `-F 无BOM文件`）；
  推送前**先同步 scripts + docs 到 `ScriptAgent4MindustryExt-mdtdo`**（路径映射：
  `mdtserver/config/scripts/*` → `scripts/*`，`docs/*` → `docs/*`），再在该仓库提交并推送；
  `stats-web/`、`README.md` 属 mdtdo 本地资产，不进插件仓库。
- 插件仓库 `core.autocrlf=true`：直接 `Copy-Item` 即可，不必处理换行。
- 推送后抽查文件 MD5 一致（本地 vs 插件仓库），并确认两仓工作树干净。
