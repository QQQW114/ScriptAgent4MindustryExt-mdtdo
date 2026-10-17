@file:Depends("wayzer/vote", "投票服务（VoteEvent 公开 API）")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化（MdtSettings 键值存储）")

package wayzer.cmds

import mindustry.gen.Player
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 投票免打扰（/votednd）。
 *
 * 玩家开启后，每当有新投票出现，本脚本会在弹窗延迟（vote.lib 默认 20 秒）之前
 * 替该玩家投出「中立」票（VoteEvent.Action.Ignore），因此 vote.lib 的弹窗逻辑
 * 看到该玩家已在 voted 中就会跳过弹窗，实现「投票自动中立代替弹窗」。
 *
 * 设计约束（2026-10-17 Lead 性能要求）：
 * - 轮询热路径只做内存操作：每 tick 只读一次 VoteEvent.current() + identity 去重，
 *   **仅在新投票出现时**才遍历一次在线玩家；轮询里没有任何 DB/文件 IO、没有日志拼接；
 * - MdtStorage 的同步 Exposed 事务只在「脚本启用时加载一次」与「/votednd 变更后异步保存」
 *   两处出现，且都在 Dispatchers.IO 上执行，绝不在游戏线程；
 * - 自动投中立不给玩家发任何回执（避免刷屏），只回复手动 /votednd 操作。
 *
 * 持久化：MdtSettings 键 `vote.dnd.uids`，按行存「主体 uid」（账号 uid + 游戏 uuid）。
 * 数据库未就绪时降级为内存态并只告警一次；期间的开/关操作会记为待重放操作，
 * 首次成功加载后按顺序重放，不会覆盖旧记录。跨重启保留，重进/换号登录后按任一主体 uid 命中。
 *
 * 已知边界：
 * - 投票进行中才进服的玩家仍会先收到 vote.kts 的立即弹窗（该路径在 vote.lib/vote.kts 内部，
 *   本脚本不修改共享库），自动中立只能保证后续不会再弹 20 秒窗口；
 * - vote.lib 的投票处理协程在玩家被自动记票后会回一条「投票成功」，该回执来自共享库、
 *   无法在不修改 vote.lib 的前提下抑制。
 */
name = "投票免打扰"

// ---------- 配置（均带默认值，可在 SA 脚本配置里改） ----------

private val enabled by config.key(true, "是否启用投票免打扰功能")
private val pollIntervalMillis by config.key(300L, "新投票轮询间隔(ms)，建议 300-500；仅内存操作")
private val maxAutoIgnorePerVote by config.key(64, "单次投票最多自动中立人数上限（防异常配置下漏控）")

// ---------- 持久化状态 ----------

// MdtSettings 键：每行一个主体 uid（玩家全部身份：账号 uid + 游戏 uuid）
private val SETTING_KEY = "vote.dnd.uids"

// 内存态。所有读写都在 DND_LOCK 内，临界区只做集合操作（无 IO），游戏线程最多等几微秒。
private val dndUids = ConcurrentHashMap.newKeySet<String>()
private val DND_LOCK = Any()

// DB 是否已成功加载过一次；未加载完成前按「都未开启」处理（不阻塞、不猜测），
// 期间的开/关操作进入 pendingOps，加载成功后按顺序重放。
@Volatile private var dndLoaded = false
private val pendingOps = mutableListOf<Pair<Set<String>, Boolean>>()

// IO 侧串行锁：加载/保存任务都在 Dispatchers.IO 上，用该锁保证「先加载后保存」的顺序。
private val IO_LOCK = Any()

// 数据库告警只打一次，避免 DB 长期不可用时刷日志。
@Volatile private var dbWarned = false

private fun warnDbOnce(action: String, error: Throwable?) {
    if (dbWarned) return
    dbWarned = true
    logger.warning("投票免打扰：数据库未就绪（${action}失败：${error?.message ?: "未知错误"}），已降级为内存态并会在下次变更时自动重试。")
}

// ---------- 玩家身份 ----------

/**
 * 玩家全部稳定主体标识（账号 uid + 游戏 uuid）。
 *
 * PlayerData 只在在线真实玩家上可用；本地假玩家（con == null）会抛异常，
 * 此时退回会话 uuid，保证脚本本身不因异常中断。返回空集表示无法识别身份。
 */
private fun playerKeys(player: Player): Set<String> = runCatching {
    val data = PlayerData[player]
    (data.ids + data.id).filter { it.isNotBlank() }.toSet()
}.getOrElse {
    runCatching { setOf(player.uuid()).filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
}

/** 该玩家是否开启投票免打扰；热路径调用，纯内存 + PlayerData 缓存读取。 */
private fun isDndEnabled(player: Player): Boolean {
    if (dndUids.isEmpty()) return false
    return playerKeys(player).any { it in dndUids }
}

// ---------- 数据库加载 / 保存（只在 Dispatchers.IO 调用） ----------

private fun parseUidLines(raw: String?): Set<String> =
    raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * 从 MdtSettings 读一次状态（IO 线程）。成功则合并待重放操作并标记已加载；
 * 失败只告警一次并保持未加载状态，下次变更时自动重试。
 * 加载成功且存在进行中投票时，补一次自动中立（覆盖脚本热重载/启动期间的进行中投票）。
 */
private fun loadDndStateBlocking() {
    if (dndLoaded) return
    val raw = try {
        MdtStorage.getSetting(SETTING_KEY)
    } catch (e: Exception) {
        warnDbOnce("加载", e)
        return
    }
    val persisted = parseUidLines(raw)
    var loadedCount = 0
    synchronized(DND_LOCK) {
        if (dndLoaded) return
        dndUids.addAll(persisted)
        // 重放「加载完成前」的本地操作，避免被旧持久化记录覆盖。
        pendingOps.forEach { (keys, expectedOn) ->
            if (expectedOn) dndUids.addAll(keys) else dndUids.removeAll(keys)
        }
        pendingOps.clear()
        dndLoaded = true
        loadedCount = dndUids.size
    }
    logger.info("投票免打扰：已从数据库加载 ${loadedCount} 个开启主体")
    // 启动/热重载时可能已有进行中的投票：补一次（一次性，不在轮询热路径里）。
    launch(Dispatchers.game) { autoIgnoreActiveVote("加载完成补投") }
}

/** 把当前内存态写回 MdtSettings（IO 线程，串行）。未成功加载过则先尝试加载，失败则放弃本次写入。 */
private fun persistDndStateBlocking() {
    loadDndStateBlocking()
    if (!dndLoaded) return
    val text = synchronized(DND_LOCK) { dndUids.sorted().joinToString("\n") }
    try {
        MdtStorage.setSetting(SETTING_KEY, text)
    } catch (e: Exception) {
        warnDbOnce("保存", e)
    }
}

/** 变更后的异步持久化：更新内存是同步的（玩家立刻生效），写库放 IO 线程且串行。 */
private fun schedulePersist() {
    launch(Dispatchers.IO) {
        synchronized(IO_LOCK) {
            runCatching { persistDndStateBlocking() }
                .onFailure { logger.warning("投票免打扰：保存状态失败（内存态不受影响）：${it.message}") }
        }
    }
}

/** 更新内存态；DB 未加载完成时记录待重放操作。只在游戏线程 / 命令里调用（临界区极短）。 */
private fun applyDndState(player: Player, expectedOn: Boolean): Boolean {
    val keys = playerKeys(player)
    if (keys.isEmpty()) return false
    synchronized(DND_LOCK) {
        if (expectedOn) dndUids.addAll(keys) else dndUids.removeAll(keys)
        if (!dndLoaded) {
            pendingOps.add(keys to expectedOn)
            // 防御：DB 长期不可用时不无限增长（只保留最近 64 次开关）。
            if (pendingOps.size > 64) pendingOps.removeAt(0)
        }
    }
    return true
}

// ---------- 投票自动中立 ----------

/**
 * 对进行中的投票一次性补投中立。
 *
 * 只在新投票出现、手动开启、加载完成补投时调用；整个函数是纯内存操作：
 * 一次 allCanVote() 遍历（由 vote.lib 过滤不能投票的玩家）+ 每名玩家的身份集合判断。
 * 不做 DB/IO，不给玩家发回执（回执由 vote.lib 的投票处理协程发出）。
 */
private fun autoIgnoreActiveVote(reason: String) {
    val event = runCatching { VoteEvent.current() }.getOrNull() ?: return
    if (event.cancelled) return
    if (dndUids.isEmpty()) return
    val candidates = runCatching { event.allCanVote() }.getOrDefault(emptyList())
    if (candidates.isEmpty()) return
    val limit = maxAutoIgnorePerVote.coerceAtLeast(1)
    var count = 0
    for (p in candidates) {
        if (count >= limit) break
        // 发起者已由 vote.lib 自动投赞成，不干预。
        if (p === event.starter) continue
        // 已自行投过票的玩家不覆盖（同 IP 只计一票，最后一次投票为准）。
        if (p in event.voted) continue
        if (!isDndEnabled(p)) continue
        // trySend 到 vote.lib 的投票通道，可能因投票刚结束通道关闭而静默失败，不影响其他玩家。
        try {
            event.vote(p, VoteEvent.Action.Ignore)
            count++
        } catch (e: Exception) {
            // 单个玩家投票失败不能打断整轮补投；不在这里打日志（避免热路径刷日志）。
        }
    }
    if (count > 0) logger.info("投票免打扰[$reason]：已为 $count 名玩家自动投中立")
}

/** 玩家刚开启免打扰且当前有进行中投票、本人还没投票时，立即补中立（不等下一轮轮询）。 */
private fun autoIgnoreForPlayer(player: Player) {
    val event = runCatching { VoteEvent.current() }.getOrNull() ?: return
    if (event.cancelled) return
    if (event.starter === player) return
    if (player in event.voted) return
    val canVote = runCatching { event.allCanVote().any { it === player } }.getOrDefault(false)
    if (!canVote) return
    runCatching { event.vote(player, VoteEvent.Action.Ignore) }
}

// ---------- 轮询（Dispatchers.game，热路径） ----------

private var observedEvent: VoteEvent? = null
private var pollJob: Job? = null

/**
 * 单个轮询 tick 的全部工作：一次 VoteEvent.current() 读取 + identity 比较。
 * 只有出现新投票（引用变化）时才进入 autoIgnoreActiveVote 的一次性遍历；
 * 投票结束后把 observedEvent 归零，下一场投票会被重新识别。
 */
private fun pollOnce() {
    if (!enabled) return
    val current = runCatching { VoteEvent.current() }.getOrNull()
    if (current === observedEvent) return
    observedEvent = current
    if (current == null) return
    autoIgnoreActiveVote("新投票")
}

onEnable {
    pollJob?.cancel()
    observedEvent = null
    pollJob = launch(Dispatchers.game) {
        while (true) {
            try {
                delay(pollIntervalMillis.coerceIn(150L, 2000L))
                pollOnce()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 热路径异常不能抛到游戏线程；限频告警后继续轮询。
                logger.warning("投票免打扰：轮询异常（已忽略）：${e.message}")
            }
        }
    }
    // 状态加载只在启用时做一次（IO 线程），加载完成前按「都未开启」处理，不阻塞游戏线程。
    launch(Dispatchers.IO) {
        synchronized(IO_LOCK) {
            runCatching { loadDndStateBlocking() }
                .onFailure { logger.warning("投票免打扰：加载状态异常：${it.message}") }
        }
    }
}

onDisable {
    pollJob?.cancel()
    pollJob = null
    observedEvent = null
}

// ---------- 指令 ----------

private fun formatDndState(player: Player): String =
    if (isDndEnabled(player)) "[green]已开启" else "[gray]已关闭"

private suspend fun CommandContext.setDndAndReply(player: Player, expectedOn: Boolean) {
    if (!applyDndState(player, expectedOn)) {
        reply("[red]投票免打扰：无法识别你的玩家身份（未连接真实客户端），本次操作未生效。".with())
        return
    }
    schedulePersist()
    if (expectedOn) {
        reply("[green]已开启投票免打扰：之后的投票将自动为你投「中立」，不再弹出投票菜单。".with())
        reply("[gray]随时可用 [white]/votednd off[] 关闭。".with())
        // 开启时若已有进行中投票且本人未投，立即补中立（一次性，不在轮询热路径里）。
        autoIgnoreForPlayer(player)
    } else {
        reply("[yellow]已关闭投票免打扰：之后的投票会恢复弹窗提示。".with())
    }
}

command("votednd", "投票免打扰：开启后投票时自动投中立代替弹窗") {
    usage = "[on|off|status]（不带参数=切换）"
    aliases = listOf("投票免打扰", "免打扰", "dnd")
    body {
        val p = player ?: returnReply("[red]投票免打扰需要在游戏内使用。".with())
        when (val op = arg.getOrNull(0)?.trim()?.lowercase()) {
            null, "", "toggle", "切换" -> setDndAndReply(p, !isDndEnabled(p))

            "on", "开", "开启", "1", "true" -> setDndAndReply(p, true)
            "off", "关", "关闭", "0", "false" -> setDndAndReply(p, false)

            "status", "状态", "查询" -> {
                reply(("[cyan]投票免打扰：" + formatDndState(p)).with())
                reply("[gray]用法：[white]/votednd [on|off|status][gray]（不带参数=切换开关）".with())
            }

            else -> {
                reply("[red]未知参数：[white]$op".with())
                reply("[gray]用法：[white]/votednd [on|off|status][gray]（不带参数=切换开关）".with())
            }
        }
    }
}
