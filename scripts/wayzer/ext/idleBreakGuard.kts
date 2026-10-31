@file:Depends("coreMindustry", "监听客户端快照包")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/cmds/voteOb", "强制观战")
@file:Depends("wayzer/user/accountIpGuard", "风险IP/疑似小号线索")

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

private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val voteOb = contextScript<wayzer.cmds.VoteOb>()
private val ipGuard by lazy { contextScript<wayzer.user.AccountIpGuard>() }

// 监控窗口（按信任等级；3 级及以上不挂检测）
private val guestMonitorSeconds by config.key(120, "游客进服后的监控秒数")
private val level1MonitorSeconds by config.key(60, "1级玩家进服后的监控秒数")
private val level2MonitorSeconds by config.key(60, "2级玩家进服后的监控秒数")

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

private class Monitor(
    val uuid: String,
    val joinedAt: Long,
    var level: Int,
    var deadline: Long,
    val selected: MutableSet<Long> = mutableSetOf(),
    var batchSeen: Boolean = false,
    var started: Boolean = false,
    var triggered: Boolean = false,
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

private val conveyorNames by lazy {
    conveyorWhitelist.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

private fun initMonitor(player: Player) {
    val uuid = player.uuid()
    val level = levelOf(player)
    val seconds = windowSecondsFor(level)
    if (seconds <= 0) {
        monitors.remove(uuid)
        return
    }
    monitors[uuid] = Monitor(uuid, System.currentTimeMillis(), level, System.currentTimeMillis() + seconds * 1000L)
}

private fun refreshMonitor(player: Player) {
    val uuid = player.uuid()
    val monitor = monitors[uuid] ?: return
    val level = levelOf(player)
    val seconds = windowSecondsFor(level)
    if (seconds <= 0) {
        monitors.remove(uuid)
        return
    }
    monitor.level = level
    monitor.deadline = monitor.joinedAt + seconds * 1000L
}

private fun isRealBuildingBlock(block: mindustry.world.Block): Boolean {
    if (ignoreBoulders && block.name.contains("boulder")) return false
    if (block.name in conveyorNames) return false
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
            if (!isRealBuildingBlock(previous)) continue
            val key = build.tile.pos().toLong()
            if (monitor.selected.add(key)) {
                added++
            }
            continue
        }
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
        trigger(player, monitor)
    }
}

private fun trigger(player: Player, monitor: Monitor) {
    if (monitor.triggered) return
    monitor.triggered = true
    val count = monitor.selected.size
    if (debugLog) Log.info("[IDLEGUARD] trigger uuid=" + player.uuid() + " count=" + count)
    val reason = "疑似乱拆（批量选中拆除 $count 个建筑物）"
    with(voteOb) { forceObPlayer(player, reason, null, null, "system") }
    obRecords[player.uuid()] = ObRecord(
        uuid = player.uuid(),
        guest = monitor.level <= 0,
        until = System.currentTimeMillis() + observerSeconds * 1000L,
        selectedCount = count,
    )
    player.sendMessage("[red][监控] 检测到批量选中拆除 $count 个建筑物，已被强制观战 ${observerSeconds / 60} 分钟。".with())
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

private fun tickRelease() {
    val now = System.currentTimeMillis()
    for ((uuid, rec) in obRecords) {
        if (now < rec.until) continue
        val player = Groups.player.find { it.uuid() == uuid }
        if (player == null) {
            obRecords.remove(uuid)
            continue
        }
        if (!with(voteOb) { isForceOb(player) }) {
            obRecords.remove(uuid)
            continue
        }
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
    // 只有开始拆“选中集合里的 tile”才算这次框选开始拆除；
    // 否则选中一批建筑后去拆别的建筑也会被误判为触发。
    if (event.tile.pos().toLong() !in monitor.selected) return@listen
    monitor.started = true
    if (debugLog) Log.info("[IDLEGUARD] begin uuid=" + player.uuid() + " selected=" + monitor.selected.size)
    if (!monitor.triggered &&
        monitor.selected.size > thresholdFor(monitor.level)
    ) {
        trigger(player, monitor)
    }
}

listen<EventType.PlayerJoin> { event ->
    initMonitor(event.player)
    val player = event.player
    launch(Dispatchers.game) {
        delay(1_500L)
        refreshMonitor(player)
        delay(8_500L)
        refreshMonitor(player)
    }
}

listen<EventType.PlayerLeave> { event ->
    monitors.remove(event.player.uuid())
    obRecords.remove(event.player.uuid())
}

onEnable {
    loopJob = launch(Dispatchers.game) {
        while (true) {
            delay(100L)
            drainBatches()
            tickRelease()
        }
    }
}

onDisable {
    loopJob?.cancel()
}

fun debugFeed(player: Player, positions: IntArray) {
    incomingBatches.add(Batch(player.uuid(), positions))
}
