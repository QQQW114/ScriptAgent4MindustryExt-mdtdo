# Agent 调试经验与常见坑（避免重复踩坑）

> 目的：记录本项目中"卡很久、反复试错才找到根因、但解决后可复用"的经验，方便后续 Agent 不踩坑。
> 每节格式：现象 → 根因 → 正确做法。全部来自实际验证（含证据），不是推测。
> 更新：2026-08-22 首次整理（本轮统计 Web 与权限修复过程中沉淀）；2026-10-17 增补第 8 节（SA 脚本顶层初始化静默失败）、第 9 节（KDoc 内嵌 `/**` 导致编译产物被静默截断的真因）与第 10 节（SA 事件监听 `listen` / `listenTo` 静默失效）。

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
- **`with(x) { 同名函数(...) }` 会递归到自己**（2026-09-27 4x4 核心区事故的根因）：
  `with(receiver) { f(...) }` 里 `f` 的解析优先级是**当前文件作用域 > 隐式接收者**，
  所以本文件若也声明了 `f`，这行就是无限自调用 → `StackOverflowError`。
  转发包装一律写显式接收者：`skillsCore.spawnOverlapError(...)`（同目录其它技能文件就是这么写的）。
  排查可全库扫"转发包装自递归"：`fun f(...)= with(X) { f(`。
- **命令里抛 Error（`StackOverflowError`/`OutOfMemoryError`）不是 Exception，框架接不住**：
  SA 的 `CommandInfo.handle()` 只有 `catch (e: Exception)`（`coreLibrary/lib/CommandApi.kt:196-204`），
  `Error` 会直接打到主线程 → `Exception in thread "HeadlessApplication" ...`，
  **服务端从此"假活"**：端口还在监听、6859 socket 还能连、`sa listFailed` 还能回，
  但世界不 tick、所有游戏内命令（技能/菜单/聊天回复）全部无响应 —— 必须重启，热重载无效。
  判活别只看端口：用依赖主线程的命令（如 `js 1+1`）确认；这种堆栈**只在服务端控制台窗口（stderr）**，
  `config/logs/log-0.txt` 里没有，所以"点了没反应"要先让用户看一眼窗口里的堆栈。
- **`sa scan` 不会发现"文件被删了"**：删掉临时脚本后要 `sa unload <名字>`（否则模块一直挂在内存里）。
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
- **reload 的静默特性（2026-09-13 实测）**：`sa load <module[/script]>` 只在**源码有变化时**才重编
  （判据：`config/scripts/cache/<hash>.ktc` 的 mtime 更新）；源码没变则是缓存命中的**静默 no-op**，
  socket 上没有任何回显——不要误判成"命令没生效"。命令只在出错/找不到目标时回显
  （如 `sa load 不存在的模块` → `找不到模块或者脚本`）。完整链路：
  `sa load <module>` → `sa load <依赖它的业务脚本>` → `sa listFailed` 确认无故障脚本。
- 数据库/文件操作不要放游戏线程：现有模式 = 事件监听器只往 `ConcurrentLinkedQueue` 投递，
  `Dispatchers.IO` 协程单写者处理 + 内存态 `@Volatile` 快照给游戏线程读（参考
  `ext/serverStats.kts`、`reGrief/trafficMonitor.kts`）。

---

## 6. 验证习惯（经验记录，不是规定动作）

> 下面是踩坑换来的经验，按当次任务取用即可；不必为了看起来合规而多做步骤。

- **本机实例是测试服**（2026-09-19 用户明确）：重启它、改 `server.properties`、开/关 `socketInput`、清 `config/scripts/cache`、
  跑冷启动验证都可以直接做，不用每次问；只有**真正的生产服**才需要用户/运维操作。
- 出错时最快的定位途径是命令 Socket 的 **`sa listFailed`**（会直接给出脚本名 + 编译错误原文，含行号），
  比翻日志快得多；改完只 `sa load <被改的脚本>` 即可，**不要动共享库**（一次失败会级联全部菜单）。
- 重启时**必须把监管器（`start-server.ps1`）和 java 一起停**，否则旧监管器会自动拉起一个实例，
  两个实例抢端口 → 第二次加载报大量"假故障"。

- **验证收尾经验**：一次冷启动/测试跑完，顺手处理残留（结束 java 进程树、确认 6567/6859/10099 释放、清掉临时日志）。不处理的话下次启动容易撞端口，或被当成"脚本没生效"的假故障；正式 config/logs 日志与数据库、外部 CP 等运行数据不要顺手删。
  （`config/stats/`、`config/logs/stats-test-*.log`、`.agents/*.png` 等）；
  `mdtserver/config/stats/` 已加入 `.gitignore`，不应入库。
- 控制台/指令能验证的：权限拦截、状态展示、开关往返、脚本加载——用命令 Socket 实测；
  需要真实客户端的（加入/等级变化/菜单点击）标记为"未覆盖边界"写进维护文档。
- 大改动先跑一次"加载计数"基线，任何回归立刻在计数上暴露。
- **隔离测试实例（`.agents/test160.ps1` + `.tmp-160test`）的两个坑（2026-09-13 核实）**：
  ① runner 的 `-Runner xxx.cmd` **写死了 JAR 文件名**（`test160-run.cmd`=B491、`test160-x37-run.cmd`=X37、
  `test160-b495-run.cmd`=当前基线 B495），所以**换基线后要同步加/改 runner，并把对应 JAR 放进 `.tmp-160test`**，
  否则 `-SkipSync` 跑的是旧版本；`test160.ps1` 只 robocopy 同步 `config/scripts`（排除 `cache/data/external-cp`），
  **不会**同步 JAR 或 `server.properties`。
  ② 该脚本收尾会 `Get-Process java | Kill` **杀掉本机所有 java 进程**，且启动期间要独占 6567/6859/10099——
  **正式服务端在跑时绝对不能执行**（会一起被杀、端口也会撞）。

---

## 7. 提交与推送惯例（维护项目时直接照做）

- mdtdo 只本地提交（主题 `feat/fix(scope): 中文摘要` + `-m` 多行正文用 `-F 无BOM文件`）；
  推送前**先同步 scripts + docs 到 `ScriptAgent4MindustryExt-mdtdo`**（路径映射：
  `mdtserver/config/scripts/*` → `scripts/*`，`docs/*` → `docs/*`），再在该仓库提交并推送；
  `stats-web/`、`README.md` 属 mdtdo 本地资产，不进插件仓库。
- 插件仓库 `core.autocrlf=true`：直接 `Copy-Item` 即可，不必处理换行。
- 推送后抽查文件 MD5 一致（本地 vs 插件仓库），并确认两仓工作树干净。
- **推 GitHub 超时就挂本地代理（2026-09-27 实测有效）**：本机 Clash/V2Ray 的混合端口是 **`127.0.0.1:7897`**，
  直连失败时报 `error: RPC failed; curl 28 Recv failure: Connection was reset` / `send-pack: unexpected disconnect`，
  此时用一次性代理推送即可（**不要**写进仓库 config，代理关掉后会反过来挡住直连）：
  ```powershell
  git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
  ```
  先确认端口在听（`TcpClient` 连 `127.0.0.1:7897`）再推；提交已经在本地成功时，重推只是补上传，不需要重做 commit。

---

## 8. SA 脚本顶层初始化静默失败：显示"加载成功"，命令却整个没注册

> 2026-10-17 实测记录（`wayzer/map/externalCpHotReload.kts` 兼容改造后）。现象、对照组和探针方法都是实测的；
> **真因已于同日 17:00 定位，见第 9 节：KDoc 内嵌字面量 `/**` 让 SA 静默产出截断的 .ktc**——本节保留现象与探针方法，
> 作为这类"显示成功、注册却全没执行"故障的通用排查路径。

### 8.1 现象：编译 / 启用 / 出错计数全部正常，但命令不存在

- 脚本**编译通过**，控制台打印 `[Enable] wayzer/map/xxx`，冷启动 banner 照样是 `加载成功…、出错0`；
- 但脚本注册的东西**全都没有**：控制台/游戏内执行它注册的命令报 `无效指令"externalcp",请使用 * help 查询`，
  `onEnable` 里注册的 `/vote cp` 子指令也一并不存在；
- SA 不写任何日志：`config/logs/log-0.txt`（游戏运行日志）、`config/logs/script-load/current.log`（SA 逐脚本日志）
  都没有异常痕迹；
- 机理（2026-10-17 17:00 已定位真因，见第 9 节）：SA 编译管线遇到 KDoc 内嵌字面量 `/**` 时会**静默产出截断的 .ktc**，
  该注释之后的函数与顶层语句全部没有生成——本例 `command("externalcp")`（文件尾部）与
  `onEnable { VoteService.registerExternalCpVote() }` 都在被截断的那一段里，所以命令和子指令同时消失。
  **`[Enable]` 和 banner 的"出错0"只反映编译/模块启用，不代表顶层注册跑完了。**

### 8.2 唯一可靠的探针：控制台执行它注册的命令

- 在服务端控制台执行该脚本注册的命令（本次 `externalcp`）：正常会回显 CP 列表（实测 `可用CP：16 个`），
  坏了就是 `无效指令"externalcp"`。**这是唯一能直接判定"注册到底发生没有"的探针**；
  只看 `[Enable]` 和 banner 的"出错0"都可能误判为正常。
- 现已工具化：`scriptdiag commands <关键词>` 可直接列出根指令（如 `scriptdiag commands externalcp`
  输出 `根指令匹配 'externalcp'：1` + `externalcp(ecp/外部cp/热重载cp)`），用法见 `dev-workspace-guide.md`「调试工具」。

### 8.3 定位手法：先覆盖回原版做对照，再插顶层日志探针二分

1. **对照组**：把插件仓库里上一个可用版本原样覆盖回来（本次 `d41e7b6`，1619 行），
   `sa reload wayzer/map/externalCpHotReload` 后控制台执行 `externalcp`。命令恢复 → 问题由**本轮内容改动**引入，
   不是服务端/框架坏了。（同一进程内 reload 就能恢复，不用重启。）
2. **顶层探针二分**：在脚本里插 `arc.util.Log.info("[ZZPROBE] …")`（`Log.info` 输出会进
   `config/logs/log-0.txt`，控制台重定向文件里也有），从头部往中间逐段加/移；每改一次 `sa reload <脚本>`，
   读 `log-0.txt` 找"最后一个没有输出的探针"，抛点就在它和下一个探针之间。
3. **探针必须插在顶层**：函数体/`onEnable` 里的探针只能证明函数被调用，定位不了顶层执行停在哪；
   每次 reload 后都要读新日志，别拿旧日志或缓存判断。

> 真因定位后不必再逐段二分；但遇到新的同类"显示成功但注册全无"故障，这套"对照组 + 顶层探针"仍然通用。

### 8.4 结论与操作建议

- 改这种上千行的巨型脚本（`externalCpHotReload.kts`：改坏时 1777 行 / 回退后 1619 行）**必须一步一验证**：
  一次只落一层改动 → `sa reload` → 控制台命令验证注册 → 通过再下一步。一次堆多层改动，失败后只能整体回退
  （本次 `bc48050`），时间成本远高于分步。
- 本次兼容改造拆成的 5 个可独立小步（资产冲突放宽 / 内容结构放宽 / 语言包合并 / 降级可见 / 异步入口）
  见 `scripts-maintenance.md` 第十七批。
- "脚本可用"的验收口径：**控制台执行其注册命令有正确回显**，而不是看到 `[Enable]` 就算过。
- 大脚本改动后的固定动作：先跑 `tools\scan-nested-block-comment.ps1` 扫描（见第 9 节），再 reload；
  若命令仍缺失，按第 9 节看 `.ktc` 产物大小/entries 是否被截断。

### 8.5 两条相关小知识（本次顺带确认）

- **控制台 `js` 是 JavaScript(Rhino) 求值器，不能写 Kotlin**：`wayzer/cmds/jsCmd.kts` 走
  `mods.scripts.runConsole(js)`。要用它调脚本函数，只能调脚本已暴露的入口；而且 `js` 在主线程**同步**执行，
  CP/DP 加载这种需要分帧同步的操作直接跑会互等卡死（本次因此才规划 `loadServerCpAsync` 异步入口）。
- **SA 热重载会重新注册 command**：本次同一进程内 `sa reload` 后就恢复了 `externalcp`（服务端没有重启）。
  之前"热重载不注册指令"的判断是错的——真正原因是编译产物被截断（见第 9 节）、注册根本没执行到；
  遇到 reload 后命令/子指令消失，先按本节查注册状态、再按第 9 节查 .ktc 是否被截断，不要先怀疑热重载机制。

---

## 9. SA 3.4.0 编译管线：KDoc 内嵌 `/**` 会让脚本编译产物被静默截断

> 2026-10-17 17:00 定位并验证——这是第 8 节"顶层初始化静默失败"的**真正根因**（不是脚本内容逻辑写错）；
> 本机 SA 3.4.0（参考项目 `ScriptAgent4MindustryExt-3.4.0`）。
> 已提上游 [issue #50](https://github.com/way-zer/ScriptAgent4MindustryExt/issues/50)（附最小复现与 `.ktc` 数据）；
> **截至 2026-10-17 上游未确认修复**，本地仍以维护者竞态修复构建 `c2823c1` + 本节规避规则（§9.2/§9.3）为准。

### 9.1 触发写法与后果

- **触发**：在 KDoc 块注释（`/** … */`）内部出现**字面量 `/**`**——包括 Markdown/代码引用写法，例如 `` `bundles/**` ``。
  本轮坏版本里就有一行：`* - \`bundles/**\`：可合并资产，两份都保留…`（`mergedDataAssets` 的 KDoc 里；
  bf135ea/dd8020a 版本第 **1119** 行，ec2678b 版本第 1053 行）。
- **后果（实测）**：SA 仍打印 `编译脚本完成`，但生成的 `.ktc` 是**截断的**：

  | 产物 | zip entries | 主类大小 | .ktc 文件大小 |
  |---|---|---|---|
  | 正常 | **55** | `wayzer/map/ExternalCpHotReload.class` **148,894B（≈149KB，实测样本）** | ≈152–153KB |
  | 截断 | **26** | 同主类 **103,508B（≈103.5KB，实测样本）** | ≈77KB |

  该 KDoc **所在的函数及其后**的函数/顶层语句全部没有生成：javap 对比正常版 154 个方法 / 截断版 93 个方法，
  从 `mergedDataAssets` 起往后连续缺失（`applyDataAssetsAndSanitize`、`applyExternalCp`、`unloadExternalCp`、
  `externalCpListText`、`loadServerCp`、`registerExternalCpVote` 以及 `onEnable` 相关的 `_init_$lambda` 等）。
- **表现**：脚本 `[Enabled]`、banner 出错0、`sa listFailed` 为空，但脚本尾部的 `command("externalcp")` 与
  `onEnable { VoteService.registerExternalCpVote() }` 根本没执行 → 游戏内/控制台"无效指令"（详见第 8 节）。
- **对照验证**：只把那处 `/**` 改掉（改写成「bundles 目录」），同一份兼容改动重新 `sa load` 后**立即恢复全量编译**
  （回到 55 entries / 主类 ≈149KB），控制台 `scriptdiag commands externalcp` 输出
  `根指令匹配 'externalcp'：1` + `externalcp(ecp/外部cp/热重载cp)`。

### 9.2 怎么确认/定位（最快证据链）

1. 看编译缓存产物：`mdtserver/config/scripts/cache/compiled/<脚本路径下划线化>/<hash>.ktc` **实际是 jar**
   （可直接统计 zip entries）。被截断时 entries 数明显偏少、主类明显变小，与本脚本的历史正常版本一对比就能看出
   （本轮正常 55 / 截断 26）。PowerShell 示意：
   ```powershell
   Add-Type -AssemblyName System.IO.Compression.FileSystem
   $z = [System.IO.Compression.ZipFile]::OpenRead("path\to\xxx.ktc")
   $z.Entries.Count
   $z.Entries | Where-Object { $_.Name -like "*ExternalCpHotReload.class" } | Select-Object Name, Length
   $z.Dispose()
   ```
2. 用 `javap` 看主类方法（`.ktc` 可直接当 classpath）：`javap -p -cp <xxx.ktc> wayzer.map.ExternalCpHotReload`，
   与正常版本 diff 方法清单；**从某个函数起连续缺到文件尾** = 源码在该函数之前的注释处被截断
   （本轮 `.agents\good-javap.txt` / `broken-javap.txt` 就是这样比出 61 个缺失方法的）。
3. 把可疑 KDoc 里的 `/**` 改掉后 `sa load`，对比新 `.ktc` 的 entries/主类大小；或直接跑 `tools\scan-nested-block-comment.ps1`。

### 9.3 预防（写 KDoc 的硬规则）

- **块注释内不写 `/**`**：写「`bundles` 目录」「`generated` 目录」这类文字，不要让注释里出现 `/*` 字符序列。
- 改完大脚本（尤其新增/修改 KDoc）先跑扫描器，再 `sa load`：
  ```powershell
  cd "C:\Users\qw114\Desktop\other\mdt保留\mdtdo"
  pwsh -File tools\scan-nested-block-comment.ps1
  ```
  状态机扫描 `mdtserver/config/scripts` 下全部 `.kts/.kt`（本轮 206 个文件，秒级）；命中会输出
  `文件 : 第 N 行 块注释内出现 '/*'`——对历史坏版本（`.agents\ktcs\broken.kts`）准确报出**第 1119 行**，对原版干净。
- **行注释里的 `/**` 无害**：如 `externalCpHotReload.kts` 里一直有
  `// DataImagePacker prioritizes generated/** and omits …` 这行普通注释，脚本正常编译；
  **字符串/字符字面量里的 `/*` 也不影响**（扫描器已排除行注释、字符串、字符三种状态）。
- 这类 bug **不报错、不进日志**，只能靠编译产物大小/entries，或最终"命令是否注册"发现。
  排查顺序建议：控制台命令/`scriptdiag` → `.ktc` 大小与 javap → 扫描器 → 最后才怀疑脚本逻辑。

---

## 10. SA 事件监听：`listen` 只挂 arc 原生事件总线，自定义事件必须 `listenTo`

> 2026-10-17 实测（提交 `69d4932`；完整审计见 [listen/listenTo 审计](listener-listen-vs-listenTo-audit.md)）。

- **现象**：`listen<SA 自定义事件>` **编译通过、脚本 `[Enabled]`、无任何报错，但监听器永远不触发**——典型表现是
  "等级统计不迁移 / 换图后队伍或 PVP 保护没反应"，日志毫无痕迹。本次全仓审计发现 3 处：
  `wayzer/ext/serverStats.kts:311`（`TrustLevelChangedEvent`）、`wayzer/map/betterTeam.kts:97`、
  `wayzer/map/pvpProtect.kts:152`（`MapLoadFinishedEvent`）；其中 **PVP 保护此前完全失效**（该监听是 `startPvpProtect()`
  的全仓唯一调用点），等级分布即时迁移也从未执行（进服路径会自愈，属"即时性丢失"）。
- **根因**：SA 有两套**同名但互不相通**的事件总线——
  - `listen<T> { }`（`coreMindustry/lib/ListenExt.kt`）挂到 **`arc.Events`**，由 `Events.fire(...)` 派发，适用于游戏原生
    `EventType.*`；
  - SA 自定义事件（实现 `cf.wayzer.scriptAgent.Event`、带 `Event.Handler`，由 `emitAsync()` / `launchEmit()` 派发）只走
    **`listenTo<T> { }`** 的 `Event.Handler`，**永远不会进 `arc.Events`**。
  `listen<T>` 的 `T` 约束是 `Any`，所以误用照样编译通过、只能靠审查/扫描发现。
- **判断方法**：看事件**定义**——有 `override val handler: Event.Handler` 或 `companion object : Event.Handler()` 的就是 SA
  自定义事件，**必须 `listenTo`**；`mindustry.game.EventType.*` / 明确 `Events.fire` 派发的用 `listen`。反向（原生事件用
  `listenTo`）是编译错误，所以本类 bug 只有"`listen` + SA 自定义事件"一个方向。
- **正确写法**：`listenTo<T> { ... }` 的 lambda 是**事件 receiver**（`suspend T.() -> Unit`）——事件属性直接写 `uid` /
  `this.info`，**没有隐式 `it`**，提前返回用 `return@listenTo`（从 `listen` 迁移最容易漏改这里，会报
  `Unresolved reference 'it'`）。可参考 `wayzer/maps.manager.kt`、`mapScript/module.kts:194`。
- **全仓审计命令**（改动后自查，规则见审计文档 §7）：
  ```powershell
  # 列出所有 listen<T>，凡类型不以 EventType. 开头的逐条人工确认（查定义/派发点）
  Get-ChildItem -Recurse mdtserver\config\scripts -Include *.kts,*.kt |
    Select-String '\blisten\s*<' | Where-Object { $_.Line -notmatch 'listen\s*<\s*EventType\.' }
  # 交叉核对 SA 事件派发点：应是 emitAsync/launchEmit（而不是 Events.fire）
  Get-ChildItem -Recurse mdtserver\config\scripts -Include *.kts,*.kt |
    Select-String 'emitAsync\(|launchEmit\('
  ```
  非 `EventType.` 前缀的 `listen` 只允许三类：显式 import 的原生事件、MindustryX 原生扩展（`listen<Any>(class)` 形式）、
  经核对确认由 `Events.fire` 派发的类型。
- **预防规则**：新增/审查监听器**先看事件定义再选 API**；自定义事件定义处加注释"必须 listenTo"；改动后跑上面的扫描命令。
- **热重载注意**：SA 单脚本 `sa load` **不会级联重载 `contextScript<T>()` 的依赖方**（实测重载 `betterTeam` 后其 8+ 个依赖方
  仍持旧实例）。本次 listen→listenTo 修复对监听器本身立即生效，但被依赖脚本的状态类改动建议**低峰期冷启动一次**彻底刷新。
