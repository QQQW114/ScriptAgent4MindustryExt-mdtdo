@file:Depends("coreMindustry", "监听客户端快照包")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/cmds/voteOb", "强制观战")
@file:Depends("wayzer/user/accountIpGuard", "风险IP/疑似小号线索")

// ── 插件版本 ────────────────────────────────────────────────────────────────
// idleBreakGuard 版本：V0.1.5
// 当前版本维护者：八色 和他的 dsh

package wayzer.ext

import arc.math.geom.Point2
import arc.util.Log
import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.lib.listenPacket2Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.ClientSnapshotCallPacket
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.world.blocks.ConstructBlock
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val pluginVersion = "V0.1.5"
private val pluginMaintainers = "八色 和他的 dsh"

private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val voteOb = contextScript<wayzer.cmds.VoteOb>()
private val ipGuard by lazy { contextScript<wayzer.user.AccountIpGuard>() }

// 监控窗口（按信任等级；3 级及以上不挂检测）
private val guestMonitorSeconds by config.key(120, "游客进服后的监控秒数")
private val level1MonitorSeconds by config.key(60, "1级玩家进服后的监控秒数")
private val level2MonitorSeconds by config.key(60, "2级玩家进服后的监控秒数")
private val dailyResetHour by config.key(4, "每日首次重置小时（0-23，默认凌晨4点）")

// ── 重连接续（2026-11-04）────────────────────────────────────────────────────
// 背景：/sync、/vote rollback、MapManager 换图等「客户端重新同步世界」都会让服务端
// 先收到 PlayerLeave、再收到 PlayerJoin。旧实现每次 Join 都 initMonitor，
// 监控窗口从头计时，等于把所有人重新纳入监控（回档之后尤其明显）。
//
// 做法：离开时只记时间戳（不建计时器，避免协程泄漏与调度乱序），
// 重连时若间隔在窗口内且旧监控还在，则只 refreshMonitor（保留 joinedAt/deadline）。
//
// 代码级回退：把下面这个开关改成 false，即恢复旧行为（离开立刻丢弃监控状态）。
private val reconnectResumeEnabled = true
private val reconnectGraceSeconds by config.key(30, "断线重连接续窗口（秒）：窗口内重连不重新开始监控")


// 批量选中拆除的真实建筑物数量阈值（超过才触发）
private val guestBatchThreshold by config.key(50, "游客批量选中真实建筑物数量上限")
private val level1BatchThreshold by config.key(100, "1级批量选中真实建筑物数量上限")
private val level2BatchThreshold by config.key(150, "2级批量选中真实建筑物数量上限")

private val observerSeconds by config.key(600, "疑似乱拆强制观战秒数")
private val keepObserverExtraSeconds by config.key(600, "解除检查判定可疑时延长观战秒数")
private val guestAltKeepObserver by config.key(true, "游客解除检查疑似小号时是否继续观战")
private val ignoreMapBlocks by config.key(true, "忽略地图原有物（derelict 队伍的巨石/废墟/残骸等）")
private val debugLog by config.key(false, "调试日志")
private val ignoreBoulders by config.key(true, "忽略地上的巨石（名称含 boulder 的方块）")
private val conveyorWhitelist by config.key(
    "conveyor,titanium-conveyor,plastanium-conveyor",
    "只拆除这些传送带时豁免（逗号分隔，不含路由器/溢流门/传送带桥等）"
)

// 建筑物豁免（2026-11-07）：名单内的方块在批量框选拆除时一律不计入。
// 用 block 内部名称（block.name），与 conveyorWhitelist 取并集；两处都可单独配置。
private val buildingWhitelist by config.key(
    "surge-conveyor,duct,copper-wall,copper-wall-large,scrap-wall,scrap-wall-large," +
        "scrap-wall-huge,scrap-wall-gigantic,beryllium-wall,beryllium-wall-large," +
        "tungsten-wall,tungsten-wall-large,duo,mechanical-drill,pneumatic-drill," +
        "micro-processor,logic-display,large-logic-display,tile-logic-display,canvas,large-canvas",
    "额外豁免的建筑物（block 内部名称，逗号分隔；与 conveyorWhitelist 取并集，选中不计入批量拆除）"
)

// 接收台黑名单（2026-11-07）：游客/1级在监控窗口内拆除或拆到批量选中里的接收台即立刻处罚。
// （用户 2026-11-07 决定：只处罚，不做“还原接收台”。）
// 默认 landing-pad（中文名“接收台”，地图开局自带、服务器科技里不可制造）。
private val receiverBlocks by config.key(
    "landing-pad",
    "接收台类方块的内部名称（逗号分隔）：监控对象选中并拆到即立即处罚"
)

private class Monitor(
    val uuid: String,
    val joinedAt: Long,
    var level: Int,
    var deadline: Long,
    val selected: MutableSet<Long> = mutableSetOf(),
    /** 选中集合里的接收台坐标（tile.pos），用于「疑似将要拆除接收台」判定。 */
    val receiverTiles: MutableSet<Int> = mutableSetOf(),
    var batchSeen: Boolean = false,
    var started: Boolean = false,
    var triggered: Boolean = false,
    var dailyUid: String? = null,
)

private class ObRecord(
    val uuid: String,
    val guest: Boolean,
    var until: Long,
    val selectedCount: Int,
)

private data class Batch(val uuid: String, val positions: IntArray)

private var loopJob: kotlinx.coroutines.Job? = null

private val monitors = ConcurrentHashMap<String, Monitor>()
private val obRecords = ConcurrentHashMap<String, ObRecord>()
private val incomingBatches = ConcurrentLinkedQueue<Batch>()

/** uuid -> 离开时刻（毫秒）。只用于「重连接续」判定，不需要每个玩家一个计时器。 */
private val leftAt = ConcurrentHashMap<String, Long>()

/** 上一次清理 leftAt 的时间，靠主循环节流（每 5 秒扫一次）。 */
private var lastGraceSweep = 0L

/**
 * 用「离开时刻 + 当前时间」判断这次 Join 是不是窗口内的重连。
 * 返回 true 表示应当接续原监控（不重置 joinedAt/deadline）。
 */
private fun tryResumeMonitor(player: Player): Boolean {
    val uuid = player.uuid()
    val left = leftAt.remove(uuid) ?: return false
    if (!reconnectResumeEnabled) return false
    val elapsed = System.currentTimeMillis() - left
    if (elapsed > reconnectGraceSeconds * 1000L) return false
    val monitor = monitors[uuid] ?: return false
    // 过期的旧窗口不能“续命”：游客应重新建窗，非游客按每日首次规则跳过。
    if (System.currentTimeMillis() > monitor.deadline) {
        monitors.remove(uuid)
        return false
    }
    Log.info("[IDLEGUARD] resume monitor uuid=$uuid after ${elapsed}ms")
    return true
}

/** 清理过期的离开记录与已到期的监控（都在主循环里调用，节流 5 秒）。 */
private fun sweepGrace(now: Long) {
    if (now - lastGraceSweep < 5_000L) return
    lastGraceSweep = now
    if (reconnectResumeEnabled) {
        val window = reconnectGraceSeconds * 1000L
        leftAt.entries.removeIf { now - it.value > window }
    }
    // 旧实现靠 PlayerLeave 顺手删 monitor；现在要留着重连接续，所以这里负责回收过期条目
    monitors.entries.removeIf { now > it.value.deadline }
}

private fun levelOf(player: Player): Int =
    with(trustLevel) { getTrustLevel(player) }.coerceIn(0, 4)

private fun windowSecondsFor(level: Int): Int = when {
    level <= 0 -> guestMonitorSeconds
    level == 1 -> level1MonitorSeconds
    level == 2 -> level2MonitorSeconds
    else -> 0
}

private fun thresholdFor(level: Int): Int = when {
    level <= 0 -> guestBatchThreshold
    level == 1 -> level1BatchThreshold
    else -> level2BatchThreshold
}

/** 豁免名单：传送带白名单与建筑物白名单的并集（block.name）。 */
private val exemptBlockNames by lazy {
    (conveyorWhitelist.split(',') + buildingWhitelist.split(','))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

/** 接收台名单（block.name）。 */
private val receiverBlockNames by lazy {
    receiverBlocks.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

private fun isReceiverBlock(block: mindustry.world.Block?): Boolean =
    block != null && block.name in receiverBlockNames

/** 每日首次判定用全局锁：单次 DB 操作很短；不用「每 uid 一个锁」避免锁对象永久堆积。 */
private val dailyFirstLock = Any()

/** 每日周期日期：凌晨 4 点前算前一天，4 点整切换到新周期。 */
private fun dailyCycleDate(now: LocalDateTime = LocalDateTime.now()): LocalDate {
    val hour = dailyResetHour.coerceIn(0, 23)
    return if (now.toLocalTime() < LocalTime.of(hour, 0)) {
        now.toLocalDate().minusDays(1)
    } else {
        now.toLocalDate()
    }
}

/** 每个账号主体只保留一个 key，值是当前周期日期；避免每天新增一行、长期膨胀。 */
private fun dailyFirstKey(uid: String): String =
    "idleBreakGuard.firstJoin." + uid

/**
 * 非游客每天首次进服才监控：
 * 返回 true 表示今天第一次见到这个主体；false 表示今天已经进过，不再监控。
 * 数据库异常时按“首次”处理，避免漏掉监控。
 */
private fun tryMarkDailyFirstJoin(uid: String): Boolean {
    val key = dailyFirstKey(uid)
    val cycle = dailyCycleDate().toString()
    return runCatching {
        synchronized(dailyFirstLock) {
            if (MdtStorage.getSetting(key) == cycle) {
                false
            } else {
                MdtStorage.setSetting(key, cycle)
                true
            }
        }
    }.onFailure {
        Log.warn("[IDLEGUARD] daily-first check failed uid=$uid: ${it.message}")
    }.getOrDefault(true)
}
private fun initMonitor(player: Player) {
    val uuid = player.uuid()
    val data = PlayerData[player]
    val level = levelOf(player)
    val seconds = windowSecondsFor(level)
    // 3 级以上豁免：先判断豁免，避免白白消耗当天的首次进服 key。
    if (seconds <= 0) {
        monitors.remove(uuid)
        return
    }

    // 游客始终监控；非游客每天首次进服才监控。
    var dailyUid: String? = null
    if (data.authed) {
        if (!tryMarkDailyFirstJoin(data.id)) {
            monitors.remove(uuid)
            Log.info("[IDLEGUARD] skip monitor: daily first join already used uid=${data.id}")
            return
        }
        dailyUid = data.id
    }

    val now = System.currentTimeMillis()
    monitors[uuid] = Monitor(
        uuid = uuid,
        joinedAt = now,
        level = level,
        deadline = now + seconds * 1000L,
        dailyUid = dailyUid,
    )
}

private fun refreshMonitor(player: Player) {
    val uuid = player.uuid()
    val monitor = monitors[uuid] ?: return
    val data = PlayerData[player]
    val level = levelOf(player)
    val seconds = windowSecondsFor(level)
    // 等级升到 3 级以上：直接撤销监控，不消耗每日首次 key。
    if (seconds <= 0) {
        monitors.remove(uuid)
        return
    }

    // 游客在本次会话中登录账号：按新账号补做“每日首次”判定。
    if (data.authed && monitor.dailyUid != data.id) {
        if (!tryMarkDailyFirstJoin(data.id)) {
            monitors.remove(uuid)
            Log.info("[IDLEGUARD] skip monitor after login: daily first join already used uid=${data.id}")
            return
        }
        monitor.dailyUid = data.id
    }

    monitor.level = level
    monitor.deadline = monitor.joinedAt + seconds * 1000L
}

private fun isRealBuildingBlock(block: mindustry.world.Block): Boolean {
    if (ignoreBoulders && block.name.contains("boulder")) return false
    if (block.name in exemptBlockNames) return false
    return true
}

private fun handleBatch(batch: Batch) {
    val player = Groups.player.find { it.uuid() == batch.uuid } ?: return
    val monitor = monitors[batch.uuid] ?: return
    if (monitor.triggered) return

    val now = System.currentTimeMillis()
    if (now > monitor.deadline) return

    // 监控期内等级变化时按新等级重算截止时间（仍以进服时间为起点）
    val level = levelOf(player)
    if (level != monitor.level) {
        monitor.level = level
        val seconds = windowSecondsFor(level)
        if (seconds <= 0) {
            monitors.remove(batch.uuid)
            return
        }
        monitor.deadline = monitor.joinedAt + seconds * 1000L
        if (now > monitor.deadline) return
    }

    var added = 0
    for (position in batch.positions) {
        val x = Point2.x(position).toInt()
        val y = Point2.y(position).toInt()
        val tile = Vars.world.tile(x, y) ?: continue
        if (ignoreMapBlocks && tile.team() == Team.derelict) continue
        val build = tile.build ?: continue
        if (build is ConstructBlock.ConstructBuild) {
            // 框选确认后，方块会先变成 ConstructBuild 再真正拆掉。
            // 之前这里直接 continue，导致 packet 到达时 selected 永远是 0，必须等真正拆够才触发。
            if (build.wasConstructing) continue
            monitor.started = true
            val previous = build.previous ?: continue
            if (isReceiverBlock(previous)) monitor.receiverTiles.add(build.tile.pos())
            if (!isRealBuildingBlock(previous)) continue
            val key = build.tile.pos().toLong()
            if (monitor.selected.add(key)) {
                added++
            }
            continue
        }
        if (isReceiverBlock(build.block)) monitor.receiverTiles.add(build.tile.pos())
        if (!isRealBuildingBlock(build.block)) continue
        val key = build.tile.pos().toLong()
        if (monitor.selected.add(key)) {
            added++
        }
    }

    // batchSeen 仅用于诊断；触发只取决于累计数量 + 是否已开始拆除。
    if (added >= 2) monitor.batchSeen = true

    if (debugLog) {
        Log.info("[IDLEGUARD] batch uuid=" + batch.uuid + " added=" + added + " selected=" + monitor.selected.size + " started=" + monitor.started)
    }
    if (monitor.started && !monitor.triggered &&
        monitor.selected.size > thresholdFor(monitor.level)
    ) {
        // C. 超过阈值：正常处罚（接收台专项判定见 BlockBuildBeginEvent）。
        trigger(player, monitor)
    }
}

/**
 * 统一处罚入口：强制观战 + 记录解除检查。同一监控窗口只处罚一次。
 * [reason] 会写进广播理由和玩家提示。
 */
private fun punish(player: Player, monitor: Monitor, reason: String) {
    if (monitor.triggered) return
    monitor.triggered = true
    if (debugLog) {
        Log.info("[IDLEGUARD] punish uuid=" + player.uuid() + " reason=" + reason + " selected=" + monitor.selected.size)
    }
    // 经维护者决定，这算是刻意的撤回：这里不给 voteOb 传兜底到期时间，
    // 系统类强制观战依赖 obRecords 的 tickRelease 解除（离线场景由 /vote quitOb 或管理员处理）。
    with(voteOb) { forceObPlayer(player, reason, null, null, "system") }
    obRecords[player.uuid()] = ObRecord(
        uuid = player.uuid(),
        guest = monitor.level <= 0,
        until = System.currentTimeMillis() + observerSeconds * 1000L,
        selectedCount = monitor.selected.size,
    )
    player.sendMessage("[red][监控] $reason，已被强制观战 ${observerSeconds / 60} 分钟。".with())
}

private fun trigger(player: Player, monitor: Monitor) {
    punish(player, monitor, "疑似乱拆（批量选中拆除 ${monitor.selected.size} 个建筑物）")
}

private fun releaseObserver(player: Player, reason: String) {
    with(voteOb) { releaseForceObPlayer(player) }
    obRecords.remove(player.uuid())
    player.sendMessage("[green][监控] $reason".with())
}

private fun keepObserver(player: Player, rec: ObRecord, reason: String) {
    rec.until = System.currentTimeMillis() + keepObserverExtraSeconds * 1000L

    player.sendMessage("[red][监控] $reason，观战限制延长。".with())
    broadcast("[yellow][监控] [white]${player.name}[yellow] 的观战解除检查发现异常（$reason），已延长观战。".with())
}

private fun normalizeIp(raw: String?): String = raw?.trim().orEmpty()

private fun checkGuestRelease(player: Player, rec: ObRecord) {
    val ip = normalizeIp(player.con?.address ?: player.ip())
    launch(Dispatchers.IO) {
        val binding = runCatching { MdtStorage.getIpAccountBinding(ip) }.getOrNull()
        val risk = runCatching { with(ipGuard) { isRiskIp(ip) } }.getOrDefault(false)
        val alt = binding != null || risk
        withContext(Dispatchers.game) {
            if (!Groups.player.contains(player)) return@withContext
            if (!with(voteOb) { isForceOb(player) }) {
                obRecords.remove(player.uuid())
                resetMonitorForNextOffense(player.uuid())
                return@withContext
            }
            if (alt && guestAltKeepObserver) {
                keepObserver(player, rec, "疑似小号（同IP有账号记录/风险IP）")
            } else {
                releaseObserver(player, "观战已解除。")
            }
        }
    }
}

private fun drainBatches() {
    while (true) {
        val batch = incomingBatches.poll() ?: return
        runCatching { handleBatch(batch) }
    }
}

/**
 * 玩家通过 /vote quitOb（或被管理员解除）提前退出观战后，清掉本次处罚的触发标记，
 * 让仍在原监控窗口内的后续拆除可以再次被监控；不延长原窗口，也不消耗新的每日首次。
 */
private fun resetMonitorForNextOffense(uuid: String) {
    val monitor = monitors[uuid] ?: return
    if (monitor.triggered) {
        monitor.triggered = false
        monitor.started = false
        monitor.batchSeen = false
        monitor.selected.clear()
        monitor.receiverTiles.clear()
        Log.info("[IDLEGUARD] reset monitor after ob release uuid=" + uuid)
    }
}

private fun tickRelease() {
    val now = System.currentTimeMillis()
    for ((uuid, rec) in obRecords) {
        val player = Groups.player.find { it.uuid() == uuid }
        if (player == null) {
            obRecords.remove(uuid)
            continue
        }
        // 玩家投票 /vote quitOb 或管理员提前解除时，立即清理记录并恢复监控
        if (!with(voteOb) { isForceOb(player) }) {
            obRecords.remove(uuid)
            resetMonitorForNextOffense(uuid)
            continue
        }
        if (now < rec.until) continue
        rec.until = now + 60_000L
        if (rec.guest) checkGuestRelease(player, rec)
        else releaseObserver(player, "观战已解除。")
    }
}

private fun debugState(uuid: String): String {
    val m = monitors[uuid] ?: return "none"
    return "level=${m.level} selected=${m.selected.size} batch=${m.batchSeen} started=${m.started} triggered=${m.triggered}"
}

fun debugJoin(player: Player) = initMonitor(player)
fun debugStateOf(uuid: String): String = debugState(uuid)

// 客户端每个快照会带上 unit.plans；这里只做线程安全的收集，实际判定回游戏线程。
listenPacket2Server<ClientSnapshotCallPacket> { con, packet ->
    val player = con.player
    val plans = packet.plans
    if (player != null && plans != null) {
        val positions = ArrayList<Int>()
        val iterator = plans.iterator()
        while (iterator.hasNext()) {
            val plan = iterator.next()
            if (plan.breaking) positions.add(Point2.pack(plan.x, plan.y))
        }
        if (positions.isNotEmpty()) {
            incomingBatches.add(Batch(player.uuid(), positions.toIntArray()))
        }
    }
    true
}

listen<EventType.BlockBuildBeginEvent> { event ->
    if (!event.breaking) return@listen
    val player = event.unit?.player ?: return@listen
    val monitor = monitors[player.uuid()] ?: return@listen
    val now = System.currentTimeMillis()
    if (now > monitor.deadline) return@listen

    // 多格建筑（接收台 3x3）的 BlockBuildBeginEvent 可能落在非锚点格子；
    // selected / receiverTiles 存的是 build.tile.pos()，这里统一换算成锚点坐标。
    val anchorPos = event.tile.build?.tile?.pos() ?: event.tile.pos()

    // A/B/C 的接收台判定：玩家开始拆接收台本身（游客/1级、窗口期内）。
    // ConstructBuild.previous 是原方块。
    // 2026-11-07 用户决定：只处罚，不实现“还原接收台”。
    val construct = event.tile.build as? ConstructBlock.ConstructBuild
    if (construct != null && isReceiverBlock(construct.previous) && monitor.level <= 1) {
        val pos = anchorPos
        val count = monitor.selected.size
        val overThreshold = count > thresholdFor(monitor.level)
        // B：批量选中里含接收台且未到阈值 → 「拆到接收台时」才处罚“疑似将要拆除接收台”；
        // 其余情况算直接拆接收台（A）。超阈值（C）走正常处罚理由。
        val reason = when {
            overThreshold -> null
            pos in monitor.receiverTiles && count > 1 -> "疑似将要拆除接收台"
            else -> "试图拆除接收台"
        }
        Log.info(
            "[IDLEGUARD] receiver break uuid=" + player.uuid() + " pos=" + event.tile.x + "," + event.tile.y +
                " reason=" + (reason ?: "normal")
        )
        if (reason == null) trigger(player, monitor) else punish(player, monitor, reason)
        return@listen
    }

    // 只有开始拆「选中集合里的 tile」才算这次框选开始拆除；
    // 否则选中一批建筑后去拆别的建筑也会被误判为触发。
    if (anchorPos.toLong() !in monitor.selected) return@listen
    monitor.started = true
    if (debugLog) Log.info("[IDLEGUARD] begin uuid=" + player.uuid() + " selected=" + monitor.selected.size)
    if (monitor.triggered) return@listen
    if (monitor.selected.size > thresholdFor(monitor.level)) {
        // C. 超过阈值：正常处罚（选中里含接收台也一样）。
        trigger(player, monitor)
    }
}

listen<EventType.PlayerJoin> { event ->
    val player = event.player
    // 窗口内重连（/sync、回档、换图重握手）→ 接续原监控，不重置窗口
    if (tryResumeMonitor(player)) {
        refreshMonitor(player)
    } else {
        initMonitor(player)
    }
    launch(Dispatchers.game) {
        delay(1_500L)
        refreshMonitor(player)
        delay(8_500L)
        refreshMonitor(player)
    }
}

listen<EventType.PlayerLeave> { event ->
    val uuid = event.player.uuid()
    // 不建计时器：只记时间戳，重连时由 tryResumeMonitor 判定窗口
    leftAt[uuid] = System.currentTimeMillis()
    if (!reconnectResumeEnabled) {
        monitors.remove(uuid)
    }
    obRecords.remove(uuid)
}

onEnable {
    Log.info("[IDLEGUARD] idleBreakGuard $pluginVersion 已启用（当前版本维护者：$pluginMaintainers）")
    loopJob = launch(Dispatchers.game) {
        while (true) {
            delay(100L)
            drainBatches()
            tickRelease()
            sweepGrace(System.currentTimeMillis())
        }
    }
}

onDisable {
    loopJob?.cancel()
    leftAt.clear()
    monitors.clear()
}

fun debugFeed(player: Player, positions: IntArray) {
    incomingBatches.add(Batch(player.uuid(), positions))
}
