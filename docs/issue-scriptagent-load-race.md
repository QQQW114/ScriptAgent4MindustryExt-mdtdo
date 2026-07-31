# ScriptAgent 3.4.0 并行加载竞态：Issue 与上游修复验证记录

> 当前状态：维护者已修复，但截至 2026-07-29 尚未发布新的正式发行版。维护者提供的构建已替换进本地项目并完成两轮连续启动验证。

## 2026-07-29 上游修复与本地验证

维护者修复后的 `ScriptManager.loadScript(IScript, Continuation)` 快速路径已改为只有脚本完整进入 `Loaded` 状态后才返回实例：

```kotlin
if (info.scriptState.loaded) return info.inst!!
return queueRun(...)
```

这与 Issue 定位的根因一致：不再因 `inst` 已提前赋值就让其他并行脚本读取尚未就绪的 `classLoader`。

候选构建与本地替换信息：

- 维护者提供文件：`ScriptAgent4MindustryExt-e4b136c.jar`；
- 运行时显示版本：`ScriptAgent c2823c1`；
- 候选构建 SHA-256：`9D51FCDCB65D21C16564F544B83E2EDB4AAD5765427A791A577FB1F6DDDC2214`；
- 本地运行文件：`mdtserver/config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar`；
- 旧本地字节码补丁备份：`mdtserver/config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar.pre-upstream-e4b136c.bak`；
- 旧补丁版 SHA-256：`17BF570B0B58873D1CB85322E36FE418283EBADBA181018653E4C2061AB1477D`。

两轮独立启动均得到：

```text
ScriptAgent c2823c1
共找到154脚本,加载成功150,启用成功146,出错0
```

两轮均到达 `Server loaded`、成功监听 6567 端口，且启动阶段的 `ScriptClassLoader.kt:24`、`VerifyError`、脚本加载失败与编译错误均为 0。每轮结束后均确认测试进程不再存在，6567/10099 端口已释放，第二轮未出现 H2、MVStore 或端口锁冲突。

停服阶段仍观察到若干脚本集中停止超时，测试程序在发送 `exit` 并等待 60 秒后强制结束进程。这是停服/集中卸载边界，与本次启动竞态是两个问题，不影响“原随机加载失败已在两轮中未复现”的本地结论。

该构建仍属未发行候选；正式版发布后，应核对对应修复提交、内部版本和构建差异，再决定是否替换当前候选文件。

## 标题

`[Bug] ScriptAgent 3.4.0 / 2.3.2.5 并行加载时 ScriptClassLoader NPE，导致重启后脚本随机级联失败`

## 环境

- ScriptAgent4MindustryExt：`3.4.0`
- 内置依赖 `cf.wayzer:ScriptAgent`：`2.3.2.5`
- MindustryX：B480（基于 Mindustry v159.7）
- Java：`21.0.11`
- Windows headless 服务端

## 问题描述

服务器重新编译脚本后第一次启动通常正常，但使用同一份 `config/scripts/cache` 连续重启时，会随机出现 3～15 个以上脚本加载失败。每次失败的脚本数量和根脚本都可能不同，剩余失败大多是依赖级联失败。

曾经出现的根失败脚本包括：

- `wayzer/cmds/gatherTp`
- `wayzer/ext/ipRegion`
- `wayzer/ext/playerInfoTripleTap`
- `wayzer/user/achievement`
- `wayzer/user/ext/skills`

## 关键错误

```text
java.lang.NullPointerException
    at cf.wayzer.scriptAgent.impl.ScriptClassLoader.<init>(ScriptClassLoader.kt:24)
    at cf.wayzer.scriptAgent.impl.ManagerImpl.instantiate(ManagerImpl.kt:119)
    at cf.wayzer.scriptAgent.state.ScriptTransaction$loadScript$3.invokeSuspend(ScriptTransaction.kt:235)
```

## 复现方式

1. 使用 ScriptAgent 3.4.0 启动服务端并完成一次脚本编译。
2. 不删除脚本编译缓存，停止服务端。
3. 连续冷启动服务端多次。
4. 在不同启动轮次观察到不同数量的 `ScriptClassLoader.kt:24` NPE；依赖脚本随后报告 `Dependency Failed`。

首次编译较慢时不一定出现，缓存恢复较快、并行度较高时更容易出现，因此表现为随机竞态。

## 根因分析（根据反编译 2.3.2.5 确认）

`ScriptManager.loadScript` 当前的快速路径大致为：

```kotlin
val info = iScript.scriptInfo
val inst = info.inst
if (inst != null) return inst
return queueRun(info) { ... }
```

而 `Script` 基类构造函数会在构造尚未完全结束时执行：

```kotlin
scriptInfo.inst = this
```

实际事务随后才执行：

```kotlin
val (classLoader, inst) = ManagerImpl.instantiate(compiled)
info.classLoader = classLoader
inst.afterInit()
...
info.stateUpdateForce(ScriptState.Loaded)
```

`ScriptClassLoader` 构造函数会立即读取依赖的类加载器：

```kotlin
script.scriptDeps.map { it.scriptInfo.classLoader!! }
```

当脚本 A 正在构造时，`info.inst` 已经非空，但 `info.classLoader` 仍为空；并行加载脚本 B 看到 A 的 `inst != null` 后提前返回，之后 B 创建 `ScriptClassLoader` 并读取 A 的 `classLoader!!`，于是抛出 NPE。该错误再沿依赖图级联。

## 历史临时验证的修复

在本地对 `ScriptManager.loadScript(IScript, Continuation)` 的快速路径做了字节码补丁：

```kotlin
if (inst != null && info.scriptState.loaded) return inst
```

也就是只有脚本已经完整进入 `Loaded` 状态时才允许快速返回；构造期间会回到事务队列等待当前加载完成。

补丁后的本地 JAR 连续启动 20 次，均为：

```text
共找到155脚本,加载成功151,启用成功147,出错0
```

期间未再出现 `ScriptClassLoader.kt:24`、`VerifyError` 或依赖级联失败。该补丁只是验证竞态的临时 workaround，并非希望长期分发的二进制修复。

## 原 Issue 提出的修复方式

请在 ScriptAgent 源码中修复脚本实例的发布/加载顺序或并发控制，至少满足以下条件之一：

1. `loadScript` 只有在 `ScriptState.Loaded`（或等价的完整初始化状态）后才能返回 `info.inst`；或
2. 在向其他协程公开 `info.inst` 前先保证 `info.classLoader` 已设置；或
3. 对同一 `ScriptInfo` 的加载进行可靠的去重/等待，避免并行协程重复进入实例化路径。

同时建议增加“多个依赖脚本并行冷启动、缓存恢复启动”的回归测试，确认不会再读取到空的依赖 `classLoader`。

如果该问题已在更新版本中修复，也请说明对应版本或提交号。
