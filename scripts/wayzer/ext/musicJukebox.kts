@file:Depends("coreMindustry/menu", "服务器点歌")
@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/reGrief/trafficMonitor", "上行流量估算")
@file:Depends("wayzer/reGrief/worldResyncCoordinator", "世界重同步串行协调")

package wayzer.ext

import arc.Core
import arc.files.Fi
import arc.struct.Seq
import arc.util.serialization.Jval
import coreLibrary.lib.PermissionApi
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.VoteEvent
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.ArrayDeque
import java.util.Locale

name = "服务器点歌"

private val adminPermission = "wayzer.admin.music"
private val staffControlPermission = "wayzer.staff.musicControl"
private val trafficMonitor = contextScript<wayzer.reGrief.TrafficMonitor>()
private val worldResync = contextScript<wayzer.reGrief.WorldResyncCoordinator>()

private var maxMusicBytes by config.key(24L * 1024L * 1024L, "网络点歌允许下载的单曲最大大小(bytes)")
private val minMusicBytes by config.key(32L * 1024L, "网络点歌下载结果最小大小(bytes)，小于该值通常是版权/错误页面")
private var maxDurationSeconds by config.key(10 * 60L, "网络点歌允许的最大时长(秒)")
private val voteTimeoutSeconds by config.key(60L, "玩家点歌投票窗口(秒)")
private val votePopupDelaySeconds by config.key(12L, "点歌投票开始后多久给未表态玩家弹出菜单(秒)")
private val lowAgreeCooldownMillis by config.key(5L * 60L * 1000L, "点歌投票同意率低于阈值时发起者冷却(ms)")
private val lowAgreeCooldownPercent by config.key(25, "点歌投票同意率低于该百分比时触发发起者冷却")
private var cacheMaxTracks by config.key(6, "网络点歌最近缓存数量")
private val syncIntervalMillis by config.key(650L, "向多个玩家分批同步音乐资产的间隔(ms)")
private val syncRecoveryMillis by config.key(5_000L, "每名玩家完成音乐同步后的上行恢复间隔(ms)")
private val maxQueuedAssetSyncs by config.key(6, "音乐资产同步队列最大等待人数")
private val syncTrafficStartRatio by config.key(0.45, "开始下一名玩家音乐同步前允许占用的上行预算比例")
private val syncCapacityWaitMillis by config.key(90_000L, "等待上行恢复后开始音乐同步的最长时间(ms)")
private val assetWorldReadyTimeoutMillis by config.key(120_000L, "点歌资产同步后等待客户端重新确认进入世界的最低超时(ms)")
private val assetWorldReadyPerMbMillis by config.key(15_000L, "慢线路点歌同步每MB追加的就绪等待预算(ms)")
private val postConfirmPlayDelayMillis by config.key(1_500L, "客户端确认世界加载完成后，额外等待音频注册再播放的时间(ms)")
private val httpConnectTimeoutMillis by config.key(10_000, "网络点歌HTTP连接超时(ms)")
private val httpReadTimeoutMillis by config.key(25_000, "网络点歌HTTP读取超时(ms)")
private val voteBlockerId = "wayzer.ext.musicJukebox"

// 不能放在 config/assets/music 下：v159/B477 会把该目录内的所有文件视为固定服务器资产，
// 导致每名新玩家在进服时被动同步全部点歌缓存。点歌文件只作为服务端临时存储，
// 玩家明确同意后再临时注册到当前 state.data，并在同步队列不再使用后卸载。
private val musicRoot: File get() = File(Vars.dataDirectory.file(), "music-jukebox")
private val jukeboxDir: File get() = File(musicRoot, "jukebox")
private val libraryDir: File get() = File(musicRoot, "library")
private val legacyMusicRoot: File get() = File(Vars.dataDirectory.file(), "assets/music")
private val legacyJukeboxDir: File get() = File(legacyMusicRoot, "jukebox")
private val legacyLibraryDir: File get() = File(legacyMusicRoot, "library")

private data class MusicTrack(
    val key: String,
    val title: String,
    val artist: String,
    val durationMillis: Long,
    val file: File,
    val assetPath: String,
    val assetName: String,
    val sizeBytes: Long,
    val source: String,
    val builtIn: Boolean,
) {
    val musicName: String get() = "dp-$assetName"
    val display: String get() = if (artist.isBlank()) title else "$title - $artist"
    val durationText: String get() {
        if (durationMillis <= 0) return "未知"
        val total = durationMillis / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}

private data class NeteaseMeta(
    val id: String,
    val audioId: String,
    val title: String,
    val artist: String,
    val durationMillis: Long,
    val source: String,
    val cachePrefix: String = "netease",
)

private data class NeteaseInput(
    val type: String,
    val id: String,
) {
    val key: String get() = "netease-$type:$id"
}

private data class MusicVote(
    val id: Long,
    val track: MusicTrack,
    val starter: String,
    val starterUuid: String?,
    val eligibleUuids: Set<String>,
    val startedAt: Long = System.currentTimeMillis(),
    val agree: MutableSet<String> = linkedSetOf(),
    val neutral: MutableSet<String> = linkedSetOf(),
    val disagree: MutableSet<String> = linkedSetOf(),
)

private enum class MusicVoteChoice {
    Agree,
    Neutral,
    Disagree,
}

private data class PlayingMusic(
    val track: MusicTrack,
    val startedAt: Long = System.currentTimeMillis(),
)

private data class MusicSyncTask(
    val track: MusicTrack,
    val playerUuid: String,
    val playerName: String,
    val operator: String,
    val serial: Long,
    val interrupt: Boolean,
)

private var activeVote: MusicVote? = null
private val preparedTracks = linkedMapOf<String, MusicTrack>()
private val currentPlaying = mutableMapOf<String, PlayingMusic>()
private val pendingPlaySerial = mutableMapOf<String, Long>()
private val syncedMusicAssets = mutableMapOf<String, LinkedHashMap<String, MusicTrack>>()
private var nextPlaySerial = 0L
private var nextVoteId = 0L
private var preparingVoteBy: String? = null
private val musicVoteCooldownUntil = mutableMapOf<String, Long>()
private val musicSyncQueue = ArrayDeque<MusicSyncTask>()
private var musicSyncQueueRunning = false
private var processingMusicAssetPath: String? = null

private fun moveLegacyDirectory(source: File, destination: File): Int {
    if (!source.exists()) return 0
    var moved = 0
    source.walkTopDown().filter(File::isFile).forEach { file ->
        val relative = source.toPath().relativize(file.toPath()).toString()
        var target = File(destination, relative)
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() != file.length()) {
            val base = target.nameWithoutExtension.ifBlank { "music" }
            val ext = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            var index = 1
            do {
                target = File(target.parentFile, "$base-migrated-$index$ext")
                index++
            } while (target.exists())
        }
        val completed = if (target.exists() && target.length() == file.length()) {
            file.delete()
        } else {
            val modified = file.lastModified()
            val renamed = runCatching { file.renameTo(target) }.getOrDefault(false)
            if (!renamed) {
                runCatching {
                    file.copyTo(target, overwrite = false)
                    target.setLastModified(modified)
                    file.delete()
                }.getOrDefault(false)
            } else true
        }
        if (completed) moved++
    }
    source.walkBottomUp().filter(File::isDirectory).forEach { dir ->
        if (dir.listFiles().isNullOrEmpty()) runCatching { dir.delete() }
    }
    return moved
}

private fun reloadServerDataAssetsAfterMigration(): Boolean {
    val control = Core.app.listeners.firstOrNull { it.javaClass.simpleName == "ServerControl" } ?: return false
    val method = generateSequence(control.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { it.name == "loadDataAssets" && it.parameterCount == 0 }
        ?: return false
    return runCatching {
        method.isAccessible = true
        method.invoke(control)
        true
    }.onFailure {
        logger.warning("点歌目录迁移后刷新服务器固定资产列表失败，需重启一次服务端: ${it.message}")
    }.getOrDefault(false)
}

private fun ensureDirs() {
    musicRoot.mkdirs()
    jukeboxDir.mkdirs()
    libraryDir.mkdirs()
    val moved = moveLegacyDirectory(legacyJukeboxDir, jukeboxDir) +
        moveLegacyDirectory(legacyLibraryDir, libraryDir)
    if (moved > 0) {
        val refreshed = reloadServerDataAssetsAfterMigration()
        logger.info(
            "已将 $moved 个点歌文件从 assets/music 迁移到 ${musicRoot.absolutePath}；" +
                if (refreshed) "已刷新固定资产列表，新玩家不会默认同步这些歌曲。"
                else "请重启服务端，确保旧固定资产列表完全清除。"
        )
    }
}

private fun onlineMusicPlayers(): List<Player> =
    Groups.player.toList().filter { it.con != null && !it.isLocal }

private fun findOnlinePlayer(uuid: String): Player? =
    Groups.player.toList().firstOrNull { it.uuid() == uuid && it.con != null && !it.isLocal }

private fun voteCooldownMessage(player: Player): String? {
    val uuid = player.uuid()
    val until = musicVoteCooldownUntil[uuid] ?: return null
    val now = System.currentTimeMillis()
    if (until <= now) {
        musicVoteCooldownUntil.remove(uuid)
        return null
    }
    val left = ((until - now + 999L) / 1000L).coerceAtLeast(1L)
    return "[yellow]你上一首点歌投票同意率过低，点歌冷却剩余 ${left}s。"
}

private fun hasVoteSelection(vote: MusicVote, uuid: String): Boolean =
    uuid in vote.agree || uuid in vote.neutral || uuid in vote.disagree

private fun standardVoteBlockMessage(): String? =
    if (VoteEvent.current() != null) "[yellow]当前已有普通投票进行中，暂不能发起点歌投票。" else null

private fun musicVoteBlockMessage(): String? =
    if (activeVote != null) "[yellow]点歌投票进行中，暂不能发起其他投票。请先对点歌投票表态或等待其结束。" else null

private fun reservePendingPlay(players: List<Player>): Map<String, Long> {
    val serials = linkedMapOf<String, Long>()
    players.forEach { player ->
        val uuid = player.uuid()
        val serial = ++nextPlaySerial
        pendingPlaySerial[uuid] = serial
        serials[uuid] = serial
    }
    return serials
}

private fun isStillPending(uuid: String, serial: Long): Boolean = pendingPlaySerial[uuid] == serial

private fun clearPendingPlay(uuid: String, serial: Long) {
    if (pendingPlaySerial[uuid] == serial) pendingPlaySerial.remove(uuid)
}

private fun cancelPendingPlay(uuid: String) {
    pendingPlaySerial.remove(uuid)
}

private fun musicAssetSyncKey(track: MusicTrack): String =
    "${track.assetPath}|${track.assetName}|${track.sizeBytes}"

private fun isMusicAssetSynced(player: Player, track: MusicTrack): Boolean =
    syncedMusicAssets[player.uuid()]?.containsKey(musicAssetSyncKey(track)) == true

private fun markMusicAssetSynced(uuid: String, track: MusicTrack) {
    syncedMusicAssets.getOrPut(uuid) { linkedMapOf() }[musicAssetSyncKey(track)] = track
}

private fun syncedTracks(player: Player): List<MusicTrack> =
    syncedMusicAssets[player.uuid()]?.values?.distinctBy { musicAssetSyncKey(it) }?.toList().orEmpty()

private fun clearPlayerMusicSyncCache(uuid: String) {
    syncedMusicAssets.remove(uuid)
}

private fun sanitizeFilePart(text: String, fallback: String = "music"): String {
    val cleaned = text
        .replace(Regex("""[\\/:*?"<>|#\[\]\s]+"""), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(48)
    return cleaned.ifBlank { fallback }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2fMB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private fun parseByteLimit(input: String): Long? {
    val text = input.trim().lowercase(Locale.ROOT)
    val match = Regex("""^(\d+(?:\.\d+)?)(b|kb|k|mb|m)?$""").matchEntire(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues.getOrNull(2).orEmpty()
    val multiplier = when (unit) {
        "", "b" -> 1.0
        "kb", "k" -> 1024.0
        "mb", "m" -> 1024.0 * 1024.0
        else -> return null
    }
    return (value * multiplier).toLong()
}

private fun parseDurationSeconds(input: String): Long? {
    val text = input.trim().lowercase(Locale.ROOT)
    Regex("""^(\d+):(\d{1,2})$""").matchEntire(text)?.let { match ->
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        return minutes * 60L + seconds.coerceIn(0L, 59L)
    }
    val match = Regex("""^(\d+(?:\.\d+)?)(s|秒|m|min|分钟)?$""").matchEntire(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues.getOrNull(2).orEmpty()
    val multiplier = when (unit) {
        "", "s", "秒" -> 1.0
        "m", "min", "分钟" -> 60.0
        else -> return null
    }
    return (value * multiplier).toLong()
}

private fun limitText(): String = buildString {
    appendLine("[cyan]当前点歌限制")
    appendLine("[gray]单曲最大大小：[gold]${formatBytes(maxMusicBytes)}")
    appendLine("[gray]单曲最大时长：[gold]${maxDurationSeconds}s[gray]（${maxDurationSeconds / 60}:${"%02d".format(maxDurationSeconds % 60)}）")
    appendLine("[gray]网络点歌缓存数量：[gold]${cacheMaxTracks.coerceAtLeast(1)}[gray] 首")
    appendLine("[gray]运行时音乐同步：[green]不再按全服在线人数禁用[gray]，改由队列与上行预算保护")
    appendLine("[gray]同步队列上限：[gold]${maxQueuedAssetSyncs.coerceAtLeast(1)}[gray] 人；每人完成后恢复等待 ${maxOf(syncIntervalMillis, syncRecoveryMillis)}ms")
    appendLine("[gray]用法：")
    appendLine("[white]/music limit size 24mb")
    appendLine("[white]/music limit duration 10m")
    appendLine("[white]/music limit cache 6")
}

private fun memberValue(target: Any?, name: String): Any? {
    if (target == null) return null
    val clazz = target.javaClass
    clazz.fields.firstOrNull { it.name == name }?.let { field ->
        return runCatching { field.get(target) }.getOrNull()
    }
    clazz.methods.firstOrNull { it.parameterCount == 0 && it.name == name }?.let { method ->
        return runCatching { method.invoke(target) }.getOrNull()
    }
    return null
}

private fun callNoArg(target: Any?, name: String): Any? {
    if (target == null) return null
    return target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let { method -> runCatching { method.invoke(target) }.getOrNull() }
}

private fun dataManager(): Any? = memberValue(Vars.state, "data")

private fun loadedMusicSeq(): Seq<Any>? {
    @Suppress("UNCHECKED_CAST")
    return callNoArg(dataManager(), "getMusic") as? Seq<Any>
}

private fun loadedMusicHasName(assetName: String): Boolean =
    loadedMusicSeq()?.any { (memberValue(it, "name") as? String) == assetName } == true

private fun reloadAudioAssets() {
    val data = dataManager() ?: error("当前 Mindustry 版本没有 state.data，无法使用服务器点歌")
    val reload = data.javaClass.methods.firstOrNull { it.name == "reloadAudio" && it.parameterCount == 0 }
        ?: error("当前 Mindustry 版本没有 DataManager.reloadAudio，无法使用服务器点歌")
    reload.invoke(data)
}

private fun isTransientMusicPath(path: String): Boolean =
    path.startsWith("jukebox/") || path.startsWith("library/")

private fun isLegacyAutoSyncedMusicPath(path: String): Boolean =
    path.startsWith("server-assets/jukebox/") || path.startsWith("server-assets/library/")

private fun removeMusicAssetsWhere(predicate: (String) -> Boolean): Int {
    val seq = loadedMusicSeq() ?: return 0
    var removed = 0
    for (index in seq.size - 1 downTo 0) {
        val path = memberValue(seq[index], "path") as? String ?: continue
        if (predicate(path)) {
            seq.remove(index)
            removed++
        }
    }
    if (removed > 0) reloadAudioAssets()
    return removed
}

private fun removeLegacyAutoSyncedMusicAssets() {
    val removed = removeMusicAssetsWhere(::isLegacyAutoSyncedMusicPath)
    if (removed > 0) {
        logger.info("已从当前世界资产列表移除 $removed 个旧版默认同步点歌资产。")
    }
}

private fun cleanupIdleTransientMusicAssets() {
    // 排队中的歌曲不能提前挂到全局 state.data：原版资产表是全服共享的，
    // 新玩家在此期间进入会被迫下载所有已排队歌曲。这里只保留当前正在同步的单曲。
    val keepPaths = linkedSetOf<String>()
    processingMusicAssetPath?.let(keepPaths::add)
    removeMusicAssetsWhere { path -> isTransientMusicPath(path) && path !in keepPaths }
}

private fun cleanupMissingJukeboxAssets() {
    val seq = loadedMusicSeq() ?: return
    var changed = false
    for (i in seq.size - 1 downTo 0) {
        val asset = seq[i]
        val path = memberValue(asset, "path") as? String ?: continue
        if (path.startsWith("jukebox/") && !File(musicRoot, path).exists()) {
            seq.remove(i)
            changed = true
        }
    }
    if (changed) reloadAudioAssets()
}

private fun ensureMusicAsset(track: MusicTrack) {
    cleanupMissingJukeboxAssets()
    if (loadedMusicHasName(track.assetName)) return
    val data = dataManager() ?: error("当前 Mindustry 版本没有 state.data，无法注册音乐资产")
    val seq = loadedMusicSeq() ?: error("当前 Mindustry 版本没有 DataManager.getMusic，无法注册音乐资产")
    val cls = Class.forName("mindustry.mod.data.MusicAsset")
    val asset = cls.getDeclaredConstructor().newInstance()
    val readOverride = asset.javaClass.methods.firstOrNull { method ->
        method.name == "readOverride" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == Fi::class.java
    } ?: error("当前 Mindustry 版本 MusicAsset 不支持 readOverride")
    readOverride.invoke(asset, track.assetPath, Fi(track.file))

    for (i in seq.size - 1 downTo 0) {
        val old = seq[i]
        val oldName = memberValue(old, "name") as? String
        val oldPath = memberValue(old, "path") as? String
        if (oldName == track.assetName || oldPath == track.assetPath) seq.remove(i)
    }
    seq.add(asset)
    reloadAudioAssets()
}

private fun playMusicTo(player: Player, track: MusicTrack, interrupt: Boolean = true) {
    val con = player.con ?: return
    Call.playMusic(con, track.musicName, interrupt)
    val uuid = player.uuid()
    val playing = PlayingMusic(track)
    currentPlaying[uuid] = playing
    if (track.durationMillis > 0) {
        launch(Dispatchers.game) {
            delay(track.durationMillis + 5_000L)
            if (currentPlaying[uuid] === playing) currentPlaying.remove(uuid)
        }
    }
}

private fun stopMusicFor(player: Player) {
    val con = player.con ?: return
    cancelPendingPlay(player.uuid())
    Call.playMusic(con, "", true)
    currentPlaying.remove(player.uuid())
}

private fun stopCurrentMusicBeforeResync(player: Player) {
    val con = player.con ?: return
    Call.playMusic(con, "", true)
    currentPlaying.remove(player.uuid())
}

private fun stopMusicForAll(operator: String) {
    Groups.player.forEach { player ->
        if (player.con != null && !player.isLocal) stopMusicFor(player)
    }
    pendingPlaySerial.clear()
    musicSyncQueue.clear()
    activeVote = null
    preparingVoteBy = null
    cleanupIdleTransientMusicAssets()
    logger.info("管理员 $operator 停止了所有玩家的点歌音乐")
}

private fun assetWorldReadyTimeoutFor(track: MusicTrack): Long {
    val megabytes = ((track.sizeBytes + 1024L * 1024L - 1L) / (1024L * 1024L)).coerceAtLeast(1L)
    val slowLineBudget = 30_000L + megabytes * assetWorldReadyPerMbMillis.coerceAtLeast(0L)
    return maxOf(assetWorldReadyTimeoutMillis.coerceAtLeast(10_000L), slowLineBudget)
}

private suspend fun waitForMusicSyncCapacity(player: Player): String? {
    val deadline = System.currentTimeMillis() + syncCapacityWaitMillis.coerceAtLeast(5_000L)
    // 预算设置只读取一次，避免等待窗口内每秒反复经过持久化设置读取链路。
    val budget = with(trafficMonitor) { trafficBudgetMbps() }.coerceAtLeast(0.1)
    val startLine = budget * syncTrafficStartRatio.coerceIn(0.05, 0.95)
    var lastReason = "上行尚未恢复"
    while (System.currentTimeMillis() < deadline) {
        if (player.con == null) return "玩家已经离线"

        val otherWorldSync = Groups.player.any { other ->
            other !== player && other.con?.let { it.determiningAssets || it.receivingAssets || !it.hasConnected } == true
        }
        val averageTraffic = with(trafficMonitor) { averageTrafficMbps() }
        if (!otherWorldSync && averageTraffic <= startLine) return null

        lastReason = when {
            otherWorldSync -> "仍有其他玩家处于进服/世界同步阶段"
            else -> "当前平均上行 %.2fMbps，高于点歌启动线 %.2fMbps".format(averageTraffic, startLine)
        }
        delay(1_000L)
    }
    return "等待同步条件超时：$lastReason"
}

fun isJukeboxMusicPlaying(player: Player): Boolean = currentPlaying.containsKey(player.uuid())

fun isMusicAssetSyncActive(): Boolean = processingMusicAssetPath != null || with(worldResync) { isWorldResyncActive() }

private suspend fun sendWorldAndAssetsCompat(player: Player, track: MusicTrack): Boolean {
    return with(worldResync) {
        resyncWorldAndAssets(
            player = player,
            reason = "点歌:${track.display}",
            timeoutMillis = assetWorldReadyTimeoutFor(track),
            postConfirmDelayMillis = postConfirmPlayDelayMillis.coerceAtLeast(0L),
        )
    }
}

private fun ensureMusicSyncQueueRunner() {
    if (musicSyncQueueRunning) return
    musicSyncQueueRunning = true
    launch(Dispatchers.game) {
        try {
            while (musicSyncQueue.isNotEmpty()) {
                val task = musicSyncQueue.removeFirst()
                processMusicSyncTask(task)
                if (musicSyncQueue.isNotEmpty()) {
                    delay(maxOf(syncIntervalMillis, syncRecoveryMillis).coerceAtLeast(0L))
                }
            }
        } finally {
            musicSyncQueueRunning = false
            if (musicSyncQueue.isNotEmpty()) ensureMusicSyncQueueRunner()
        }
    }
}

private suspend fun processMusicSyncTask(task: MusicSyncTask) {
    processingMusicAssetPath = task.track.assetPath
    try {
        val player = findOnlinePlayer(task.playerUuid) ?: return
        if (!isStillPending(task.playerUuid, task.serial)) return

        if (isMusicAssetSynced(player, task.track)) {
            clearPendingPlay(task.playerUuid, task.serial)
            playMusicTo(player, task.track, task.interrupt)
            player.sendMessage("[green]开始播放：[gold]${task.track.display}[gray]（已同步，跳过重复同步）")
            return
        }

        val capacityError = waitForMusicSyncCapacity(player)
        if (capacityError != null) {
            clearPendingPlay(task.playerUuid, task.serial)
            player.sendMessage(
                "[yellow]为避免上行爆满，本次音乐资产同步已取消：[white]$capacityError\n" +
                    "[gray]已经同步过的歌曲仍可通过 [white]/music synced[gray] 播放。"
            )
            logger.warning(
                "点歌同步因容量保护取消: player=${task.playerName} track=${task.track.display} " +
                    "queue=${musicSyncQueue.size} online=${Groups.player.size()} reason=$capacityError"
            )
            return
        }

        runCatching { ensureMusicAsset(task.track) }.onFailure {
            clearPendingPlay(task.playerUuid, task.serial)
            player.sendMessage("[red]音乐资产注册失败：[white]${it.message}")
            logger.warning("点歌音乐资产注册失败 ${task.track.display}: ${it.stackTraceToString()}")
            return
        }

        stopCurrentMusicBeforeResync(player)
        player.sendMessage(
            "[cyan]轮到你同步点歌：[gold]${task.track.display}[gray]（${task.track.durationText}, ${formatBytes(task.track.sizeBytes)}）\n" +
                "[gray]同步完成后会自动开始播放；如不想听可输入 [white]/music stop[gray]。"
        )
        runCatching {
            val assetSyncSupported = sendWorldAndAssetsCompat(player, task.track)
            if (assetSyncSupported) markMusicAssetSynced(task.playerUuid, task.track)
            if (player.con != null && isStillPending(task.playerUuid, task.serial)) {
                clearPendingPlay(task.playerUuid, task.serial)
                playMusicTo(player, task.track, task.interrupt)
                player.sendMessage(
                    "[green]开始播放：[gold]${task.track.display}\n" +
                        "[gray]若仍无声，可用 [white]/music synced[gray] 手动重播，并检查客户端音乐音量。"
                )
            }
        }.onFailure {
            clearPendingPlay(task.playerUuid, task.serial)
            val reason = it.message ?: it.javaClass.simpleName
            if (reason.contains("同步超时")) {
                player.sendMessage(
                    "[yellow]音乐同步等待已超时，自动播放已取消。\n" +
                        "[gray]服务端还会短暂等待迟到确认；若之后提示同步完成，可用 [white]/music synced[gray] 手动播放。"
                )
            } else {
                player.sendMessage("[red]同步/播放音乐失败：[white]$reason")
            }
            logger.warning("同步/播放音乐给 ${task.playerName} 失败(${task.track.display}, by ${task.operator}): ${it.stackTraceToString()}")
        }
    } finally {
        processingMusicAssetPath = null
        cleanupIdleTransientMusicAssets()
    }
}

private fun enqueueMusicSyncTask(task: MusicSyncTask) {
    val duplicate = musicSyncQueue.any {
        it.playerUuid == task.playerUuid && it.track.assetPath == task.track.assetPath && it.serial == task.serial
    }
    if (duplicate) return
    if (musicSyncQueue.size >= maxQueuedAssetSyncs.coerceAtLeast(1)) {
        clearPendingPlay(task.playerUuid, task.serial)
        findOnlinePlayer(task.playerUuid)?.sendMessage(
            "[yellow]当前音乐同步队列已满，为避免持续占满服务器上行，本次不再加入队列。"
        )
        return
    }
    musicSyncQueue.add(task)
    findOnlinePlayer(task.playerUuid)?.sendMessage(
        "[cyan]已同意点歌：[gold]${task.track.display}[gray]，已进入同步队列（前方约 ${musicSyncQueue.size - 1} 人）。"
    )
    ensureMusicSyncQueueRunner()
}

private fun syncAndPlay(track: MusicTrack, players: List<Player>, operator: String, interrupt: Boolean = true) {
    val targets = players.distinctBy { it.uuid() }.filter { it.con != null && !it.isLocal }
    if (targets.isEmpty()) return
    val serials = reservePendingPlay(targets)

    targets.forEach { player ->
        val uuid = player.uuid()
        val serial = serials[uuid] ?: return@forEach
        if (isMusicAssetSynced(player, track)) {
            clearPendingPlay(uuid, serial)
            playMusicTo(player, track, interrupt)
            player.sendMessage("[green]开始播放：[gold]${track.display}[gray]（已同步，跳过重复同步）")
        } else {
            enqueueMusicSyncTask(
                MusicSyncTask(
                    track = track,
                    playerUuid = uuid,
                    playerName = player.plainName(),
                    operator = operator,
                    serial = serial,
                    interrupt = interrupt,
                )
            )
        }
    }
    cleanupIdleTransientMusicAssets()
}

private fun httpConnection(url: String, method: String = "GET", redirect: Int = 0): HttpURLConnection {
    if (redirect > 6) error("HTTP重定向过多")
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = false
    conn.requestMethod = method
    conn.connectTimeout = httpConnectTimeoutMillis
    conn.readTimeout = httpReadTimeoutMillis
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 MDT-Mindustry-Jukebox")
    conn.setRequestProperty("Referer", "https://music.163.com/")
    val code = conn.responseCode
    if (code in 300..399) {
        val location = conn.getHeaderField("Location") ?: error("HTTP $code 缺少 Location")
        conn.disconnect()
        val next = URL(URL(url), location).toString()
        return httpConnection(next, method, redirect + 1)
    }
    return conn
}

private fun httpGetText(url: String): String {
    val conn = httpConnection(url)
    try {
        val code = conn.responseCode
        if (code !in 200..299) error("HTTP $code")
        return conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    } finally {
        conn.disconnect()
    }
}

private fun looksLikeAudio(file: File): Boolean {
    if (!file.exists() || file.length() < minMusicBytes) return false
    val head = ByteArray(4)
    file.inputStream().use { it.read(head) }
    return (head[0].toInt().toChar() == 'I' && head[1].toInt().toChar() == 'D' && head[2].toInt().toChar() == '3') ||
        (head[0].toInt() and 0xff == 0xff && (head[1].toInt() and 0xe0) == 0xe0) ||
        (head[0].toInt().toChar() == 'O' && head[1].toInt().toChar() == 'g' && head[2].toInt().toChar() == 'g' && head[3].toInt().toChar() == 'S')
}

private fun downloadLimited(url: String, dest: File): Long {
    val conn = httpConnection(url)
    val tmp = File(dest.parentFile, dest.name + ".download")
    try {
        val code = conn.responseCode
        if (code !in 200..299) error("音乐下载失败：HTTP $code")
        val length = conn.getHeaderFieldLong("Content-Length", -1L)
        if (length > maxMusicBytes) error("音乐文件过大：${formatBytes(length)} > ${formatBytes(maxMusicBytes)}")
        dest.parentFile.mkdirs()
        var total = 0L
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxMusicBytes) error("音乐文件过大：>${formatBytes(maxMusicBytes)}")
                    output.write(buf, 0, read)
                }
            }
        }
        if (total < minMusicBytes) error("下载结果过小，可能是版权限制或错误页面：${formatBytes(total)}")
        if (!looksLikeAudio(tmp)) error("下载结果不是可识别的 mp3/ogg 音频")
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        return total
    } finally {
        conn.disconnect()
        if (tmp.exists()) tmp.delete()
    }
}

private fun parseNeteaseInput(input: String): NeteaseInput? {
    val text = input.trim()
    if (text.matches(Regex("""\d{3,20}"""))) return NeteaseInput("song", text)
    val isDj = text.contains("/dj", ignoreCase = true) || text.contains("music.163.com/dj", ignoreCase = true)
    val patterns = listOf(
        Regex("""[?&]id=(\d{3,20})"""),
        Regex("""/song\D+(\d{3,20})"""),
        Regex("""/song/(\d{3,20})"""),
        Regex("""/dj\D+(\d{3,20})"""),
        Regex("""/dj/(\d{3,20})"""),
    )
    val id = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) } ?: return null
    return NeteaseInput(if (isDj) "dj" else "song", id)
}

private fun validateNeteaseDuration(duration: Long, label: String) {
    if (duration > maxDurationSeconds * 1000L) {
        error("$label 过长：${duration / 1000}s > ${maxDurationSeconds}s")
    }
}

private fun fetchNeteaseSongMeta(id: String): NeteaseMeta {
    val url = "https://music.163.com/api/song/detail/?id=$id&ids=[$id]"
    val root = Jval.read(httpGetText(url))
    val songs = root.get("songs")?.asArray() ?: error("网易云返回缺少 songs")
    val song = songs.firstOrNull() ?: error("未找到网易云音乐：$id")
    val title = song.getString("name", "网易云$id").ifBlank { "网易云$id" }
    val duration = song.getLong("duration", 0L)
    val artists = runCatching {
        song.get("artists").asArray()
            .mapNotNull { it.getString("name", "").takeIf(String::isNotBlank) }
            .joinToString("/")
    }.getOrDefault("")
    validateNeteaseDuration(duration, "音乐")
    return NeteaseMeta(id, id, title, artists, duration, "网易云 $id")
}

private fun fetchNeteaseDjMeta(id: String): NeteaseMeta {
    val url = "https://music.163.com/api/dj/program/detail?id=$id"
    val root = Jval.read(httpGetText(url))
    val program = root.get("program") ?: error("未找到网易云DJ节目：$id")
    val mainSong = program.get("mainSong") ?: error("网易云DJ节目缺少 mainSong：$id")
    val audioId = mainSong.getLong("id", 0L).takeIf { it > 0L }?.toString()
        ?: error("网易云DJ节目缺少可下载音频ID：$id")
    val title = listOf(
        runCatching { program.getString("name", "") }.getOrDefault(""),
        runCatching { mainSong.getString("name", "") }.getOrDefault(""),
        "网易云DJ$id",
    ).first { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    val artist = listOf(
        runCatching { program.get("dj")?.getString("nickname", "") ?: "" }.getOrDefault(""),
        runCatching {
            mainSong.get("artists").asArray()
                .mapNotNull { it.getString("name", "").takeIf(String::isNotBlank) }
                .joinToString("/")
        }.getOrDefault(""),
    ).firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.orEmpty()
    val duration = runCatching { mainSong.getLong("duration", 0L) }.getOrDefault(0L)
        .takeIf { it > 0L }
        ?: runCatching { program.getLong("duration", 0L) }.getOrDefault(0L)
    validateNeteaseDuration(duration, "DJ节目")
    return NeteaseMeta(id, audioId, title, artist, duration, "网易云DJ $id", cachePrefix = "netease-dj")
}

private fun fetchNeteaseMeta(source: NeteaseInput): NeteaseMeta = when (source.type) {
    "dj" -> fetchNeteaseDjMeta(source.id)
    else -> fetchNeteaseSongMeta(source.id)
}

private fun prepareNeteaseTrack(input: String): MusicTrack {
    val source = parseNeteaseInput(input) ?: error("未能从输入中解析网易云音乐ID")
    preparedTracks[source.key]?.let { cached ->
        if (cached.file.exists() && cached.file.length() in minMusicBytes..maxMusicBytes && looksLikeAudio(cached.file)) return cached
        preparedTracks.remove(source.key)
    }
    val meta = fetchNeteaseMeta(source)
    val baseName = sanitizeFilePart("${meta.cachePrefix}-${meta.id}-${meta.audioId}-${meta.title}", "${meta.cachePrefix}-${meta.id}")
    val file = File(jukeboxDir, "$baseName.mp3")
    val size = if (file.exists() && file.length() in minMusicBytes..maxMusicBytes && looksLikeAudio(file)) {
        file.length()
    } else {
        downloadLimited("https://music.163.com/song/media/outer/url?id=${meta.audioId}.mp3", file)
    }
    file.setLastModified(System.currentTimeMillis())
    val track = MusicTrack(
        key = source.key,
        title = meta.title,
        artist = meta.artist,
        durationMillis = meta.durationMillis,
        file = file,
        assetPath = "jukebox/${file.name}",
        assetName = file.nameWithoutExtension,
        sizeBytes = size,
        source = if (source.type == "dj") "${meta.source}（音频 ${meta.audioId}）" else meta.source,
        builtIn = false,
    )
    preparedTracks[track.key] = track
    pruneJukeboxCache()
    return track
}

private fun audioFiles(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("mp3", "ogg") }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
        .toList()
}

private fun libraryTracks(): List<MusicTrack> = audioFiles(libraryDir).map { file ->
    val relative = libraryDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    val assetPath = "library/$relative"
    MusicTrack(
        key = "library:$relative",
        title = file.nameWithoutExtension,
        artist = "服务器音乐库",
        durationMillis = 0L,
        file = file,
        assetPath = assetPath,
        assetName = file.nameWithoutExtension,
        sizeBytes = file.length(),
        source = "服务器音乐库",
        builtIn = true,
    )
}

private fun resolveLibraryTrack(input: String?): MusicTrack? {
    val text = input?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val tracks = libraryTracks()
    text.toIntOrNull()?.let { index -> tracks.getOrNull(index - 1)?.let { return it } }
    val key = text.lowercase(Locale.ROOT).removeSuffix(".mp3").removeSuffix(".ogg")
    return tracks.firstOrNull {
        it.title.lowercase(Locale.ROOT) == key ||
            it.file.name.lowercase(Locale.ROOT) == text.lowercase(Locale.ROOT) ||
            it.assetPath.lowercase(Locale.ROOT).removeSuffix(".mp3").removeSuffix(".ogg") == key
    }
}

private fun cachedTracks(): List<MusicTrack> = audioFiles(jukeboxDir).map { file ->
    val id = Regex("""netease-(\d+)""").find(file.nameWithoutExtension)?.groupValues?.getOrNull(1)
    MusicTrack(
        key = id?.let { "netease:$it" } ?: "cache:${file.name}",
        title = file.nameWithoutExtension,
        artist = "最近缓存",
        durationMillis = 0L,
        file = file,
        assetPath = "jukebox/${file.name}",
        assetName = file.nameWithoutExtension,
        sizeBytes = file.length(),
        source = "最近缓存",
        builtIn = false,
    )
}

private fun pruneJukeboxCache() {
    val max = cacheMaxTracks.coerceAtLeast(1)
    val files = audioFiles(jukeboxDir).sortedByDescending { it.lastModified() }
    val activePaths = currentPlaying.values.map { it.track.assetPath }.toSet() +
        musicSyncQueue.map { it.track.assetPath }.toSet() +
        (activeVote?.track?.assetPath?.let(::setOf) ?: emptySet())
    files.drop(max).forEach { file ->
        val path = "jukebox/${file.name}"
        if (path in activePaths) return@forEach
        runCatching {
            if (file.delete()) {
                val it = preparedTracks.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value.file.absolutePath == file.absolutePath) it.remove()
                }
            }
        }
    }
}

private fun trackListText(title: String, tracks: List<MusicTrack>): String = buildString {
    appendLine("[cyan]$title")
    if (tracks.isEmpty()) {
        appendLine("[yellow]暂无音乐。音乐库目录：[white]${libraryDir.absolutePath}")
    } else {
        tracks.forEachIndexed { index, track ->
            appendLine("[gray]${index + 1}. [gold]${track.display}[gray] ${track.durationText} ${formatBytes(track.sizeBytes)} [darkgray]${track.source}")
        }
    }
}

private fun musicVoteSummary(vote: MusicVote): String {
    val total = vote.eligibleUuids.size.coerceAtLeast(1)
    val responded = (vote.agree.size + vote.neutral.size + vote.disagree.size).coerceAtLeast(1)
    val notVoted = (vote.eligibleUuids - vote.agree - vote.neutral - vote.disagree).size.coerceAtLeast(0)
    val agreePercent = vote.agree.size * 100 / responded
    return "[green]同意/听歌 ${vote.agree.size}/$responded（已表态同意率 ${agreePercent}%）[gray]，中立 ${vote.neutral.size}，拒绝 ${vote.disagree.size}，未表态 $notVoted/$total"
}

private fun sendMusicVotePrompt(vote: MusicVote) {
    val prompt = """
        |[accent]${vote.starter} 发起点歌投票：[gold]${vote.track.display}
        |[gray]来源：${vote.track.source}；时长：${vote.track.durationText}；大小：${formatBytes(vote.track.sizeBytes)}
        |[cyan]同意后会进入[white]排队同步队列[cyan]，排到且同步完成后才播放。
        |[gray]聊天直接发送单个字符表态：[green]1[gray] 同意听歌；[yellow].[gray] 中立；[red]0[gray] 拒绝。
        |[gray]投票 ${voteTimeoutSeconds.coerceAtLeast(5L)} 秒后结束；未表态玩家稍后会收到弹窗提示。
    """.trimMargin()
    onlineMusicPlayers().forEach { player -> player.sendMessage(prompt) }
}

private suspend fun openMusicVoteMenu(player: Player, voteId: Long) {
    val vote = activeVote ?: return
    if (vote.id != voteId || player.uuid() !in vote.eligibleUuids) return
    MenuBuilder<Unit>("点歌投票") {
        msg = """
            |[accent]${vote.starter} 点了一首：[gold]${vote.track.display}
            |[gray]来源：${vote.track.source}
            |[gray]时长：${vote.track.durationText}；大小：${formatBytes(vote.track.sizeBytes)}
            |[cyan]同意者才会进入排队同步队列；同步完成后自动播放。
            |
            |${musicVoteSummary(vote)}
        """.trimMargin()
        option("[green]同意\n[gray]同步并播放") { handleVoteAnswer(player, MusicVoteChoice.Agree) }
        option("[lightgray]中立\n[gray]不听，不反对") { handleVoteAnswer(player, MusicVoteChoice.Neutral) }
        option("[red]拒绝\n[gray]不听并反对") { handleVoteAnswer(player, MusicVoteChoice.Disagree) }
        option("关闭") {}
    }.sendTo(player, Duration.ofSeconds(voteTimeoutSeconds.coerceAtLeast(5L)).toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

private fun finishMusicVote(voteId: Long) {
    val vote = activeVote ?: return
    if (vote.id != voteId) return
    activeVote = null
    val total = vote.eligibleUuids.size.coerceAtLeast(1)
    val responded = (vote.agree.size + vote.neutral.size + vote.disagree.size).coerceAtLeast(1)
    val agreePercent = vote.agree.size * 100 / responded
    val notVoted = (vote.eligibleUuids - vote.agree - vote.neutral - vote.disagree).size.coerceAtLeast(0)
    val result = """
        |[accent]点歌投票结束：[gold]${vote.track.display}
        |[green]听歌人数：${vote.agree.size}/$responded[gray]；已表态同意率 ${agreePercent}%；中立 ${vote.neutral.size}，拒绝 ${vote.disagree.size}，未表态 $notVoted/$total（不计入同意率）。
    """.trimMargin()
    onlineMusicPlayers().forEach { it.sendMessage(result) }

    if (vote.starterUuid != null && vote.agree.size * 100 < responded * lowAgreeCooldownPercent.coerceAtLeast(0)) {
        val until = System.currentTimeMillis() + lowAgreeCooldownMillis.coerceAtLeast(0L)
        musicVoteCooldownUntil[vote.starterUuid] = until
        findOnlinePlayer(vote.starterUuid)?.sendMessage(
            "[yellow]本次点歌同意率低于 ${lowAgreeCooldownPercent}% ，已进入点歌冷却 ${lowAgreeCooldownMillis / 1000L}s。"
        )
    }
    logger.info("点歌投票结束: ${vote.track.display}, agree=${vote.agree.size}, neutral=${vote.neutral.size}, no=${vote.disagree.size}, responded=$responded, eligible=$total, notVoted=$notVoted, agreePercent=$agreePercent")
}

private fun startMusicVote(track: MusicTrack, starter: Player?) {
    if (activeVote != null) {
        starter?.sendMessage("[yellow]当前已有点歌投票未结束，请稍后再发起。")
        return
    }
    standardVoteBlockMessage()?.let {
        starter?.sendMessage(it)
        return
    }
    starter?.let { requester ->
        voteCooldownMessage(requester)?.let { msg ->
            requester.sendMessage(msg)
            return
        }
    }
    val players = onlineMusicPlayers()
    if (players.isEmpty()) {
        starter?.sendMessage("[yellow]当前没有可参与点歌投票的在线玩家。")
        return
    }

    val vote = MusicVote(
        id = ++nextVoteId,
        track = track,
        starter = starter?.plainName() ?: "控制台",
        starterUuid = starter?.uuid(),
        eligibleUuids = players.map { it.uuid() }.toSet(),
    )
    activeVote = vote
    sendMusicVotePrompt(vote)
    starter?.let { if (it.uuid() in vote.eligibleUuids) handleVoteAnswer(it, MusicVoteChoice.Agree) }

    logger.info("点歌投票开始: ${track.display} by ${vote.starter}, total=${vote.eligibleUuids.size}")
    launch(Dispatchers.game) {
        delay(Duration.ofSeconds(votePopupDelaySeconds.coerceAtLeast(1L)).toMillis())
        val current = activeVote
        if (current?.id == vote.id) {
            onlineMusicPlayers()
                .filter { it.uuid() in current.eligibleUuids && !hasVoteSelection(current, it.uuid()) }
                .forEach { player -> openMusicVoteMenu(player, current.id) }
        }
    }
    launch(Dispatchers.game) {
        delay(Duration.ofSeconds(voteTimeoutSeconds.coerceAtLeast(5L)).toMillis())
        finishMusicVote(vote.id)
    }
}

private fun handleVoteAnswer(player: Player, choice: MusicVoteChoice) {
    val vote = activeVote ?: return player.sendMessage("[yellow]当前没有正在进行的点歌投票。")
    val uuid = player.uuid()
    if (uuid !in vote.eligibleUuids) return player.sendMessage("[yellow]你不是本次点歌投票开始时的参与者，不能参与本次投票。")
    when (choice) {
        MusicVoteChoice.Agree -> {
            if (uuid in vote.agree) return player.sendMessage("[yellow]你已经同意过本次点歌。")
            vote.neutral.remove(uuid)
            vote.disagree.remove(uuid)
            vote.agree.add(uuid)
            syncAndPlay(vote.track, listOf(player), player.plainName(), interrupt = true)
        }
        MusicVoteChoice.Neutral -> {
            cancelPendingPlay(uuid)
            if (currentPlaying[uuid]?.track?.key == vote.track.key) stopMusicFor(player)
            vote.agree.remove(uuid)
            vote.disagree.remove(uuid)
            vote.neutral.add(uuid)
            player.sendMessage("[gray]你已对本次点歌选择中立，不会同步或播放。")
        }
        MusicVoteChoice.Disagree -> {
            cancelPendingPlay(uuid)
            if (currentPlaying[uuid]?.track?.key == vote.track.key) stopMusicFor(player)
            vote.agree.remove(uuid)
            vote.neutral.remove(uuid)
            vote.disagree.add(uuid)
            player.sendMessage("[yellow]已拒绝本次点歌，不会为你同步或播放。")
        }
    }
}

private fun handleMusicVoteChatAnswer(player: Player, text: String) {
    // 理论上点歌投票与标准投票互斥；这里再保守判断一次，避免脚本热重载/旧投票残留时
    // 同一个 "1/./0" 同时被两个投票系统消费。
    if (VoteEvent.current() != null) return
    val choice = when (text.trim().lowercase(Locale.ROOT)) {
        "1", "y", "yes", "赞成", "同意" -> MusicVoteChoice.Agree
        ".", "中立", "弃权", "neutral" -> MusicVoteChoice.Neutral
        "0", "n", "no", "反对", "拒绝" -> MusicVoteChoice.Disagree
        else -> return
    }
    val vote = activeVote ?: return
    if (player.uuid() !in vote.eligibleUuids) return
    handleVoteAnswer(player, choice)
}

private fun prepareAndThen(player: Player?, input: String, vote: Boolean = true) {
    if (!vote) {
        player?.sendMessage("[yellow]全服直接播放歌曲功能已移除，将改为发起点歌投票。")
    }
    if (activeVote != null) {
        player?.sendMessage("[yellow]当前已有点歌投票未结束，请稍后再发起。")
        return
    }
    standardVoteBlockMessage()?.let {
        player?.sendMessage(it)
        return
    }
    player?.let { requester ->
        voteCooldownMessage(requester)?.let { msg ->
            requester.sendMessage(msg)
            return
        }
    }
    preparingVoteBy?.let {
        player?.sendMessage("[yellow]当前 $it 正在解析/下载点歌，请稍后再发起。")
        return
    }
    val requesterName = player?.plainName() ?: "控制台"
    preparingVoteBy = requesterName
    player?.sendMessage("[yellow]正在解析/下载音乐，请稍候……")
    launch(Dispatchers.IO) {
        val result = runCatching { prepareNeteaseTrack(input) }
        launch(Dispatchers.game) {
            preparingVoteBy = null
            result.onSuccess { track ->
                startMusicVote(track, player)
            }.onFailure {
                player?.sendMessage("[red]点歌失败：[white]${it.message ?: it.javaClass.simpleName}")
                logger.warning("点歌解析/下载失败 by $requesterName: ${it.stackTraceToString()}")
            }
        }
    }
}

private suspend fun openTrackActionMenu(player: Player, track: MusicTrack, canAdmin: Boolean) {
    MenuBuilder<Unit>("点歌：${track.title}") {
        msg = "[cyan]${track.display}\n[gray]来源：${track.source}；大小：${formatBytes(track.sizeBytes)}"
        option("投票点歌") { startMusicVote(track, player) }
        option("返回") { openMusicMenu(player, canAdmin) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openTrackListMenu(player: Player, title: String, tracks: List<MusicTrack>, canAdmin: Boolean) {
    if (tracks.isEmpty()) {
        MenuBuilder<Unit>(title) {
            msg = trackListText(title, tracks)
            option("返回") { openMusicMenu(player, canAdmin) }
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }
    object : PagedMenuBuilder<MusicTrack>(tracks, prePage = 7) {
        override suspend fun renderItem(item: MusicTrack) {
            option("[gold]${item.display}\n[gray]${item.durationText} ${formatBytes(item.sizeBytes)}") {
                openTrackActionMenu(player, item, canAdmin)
            }
        }

        override suspend fun build() {
            this.title = title
            msg = "[gray]点击音乐可发起点歌投票；同意者会进入排队同步队列。"
            super.build()
            option("返回") { openMusicMenu(player, canAdmin) }
        }
    }.sendTo(player, 60_000)
}

private suspend fun openSyncedTrackActionMenu(player: Player, track: MusicTrack, canAdmin: Boolean) {
    MenuBuilder<Unit>("已同步：${track.title}") {
        msg = "[cyan]${track.display}\n[gray]该音乐已在你本次连接中完成同步，可直接重新播放，不会再次传输文件。"
        option("[green]立即播放") {
            playMusicTo(player, track, interrupt = true)
            player.sendMessage("[green]已手动播放：[gold]${track.display}")
        }
        option("停止音乐") {
            stopMusicFor(player)
            player.sendMessage("[green]已停止你的点歌音乐。")
        }
        option("返回") { openSyncedMusicMenu(player, canAdmin) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openSyncedMusicMenu(player: Player, canAdmin: Boolean) {
    val tracks = syncedTracks(player)
    if (tracks.isEmpty()) {
        MenuBuilder<Unit>("我的已同步音乐") {
            msg = "[yellow]你本次连接中还没有完成同步的点歌。\n[gray]同意点歌并完成下载后，会显示在这里。"
            option("返回") { openMusicMenu(player, canAdmin) }
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }
    object : PagedMenuBuilder<MusicTrack>(tracks, prePage = 7) {
        override suspend fun renderItem(item: MusicTrack) {
            option("[gold]${item.display}\n[gray]点击手动播放") {
                openSyncedTrackActionMenu(player, item, canAdmin)
            }
        }

        override suspend fun build() {
            title = "我的已同步音乐"
            msg = "[gray]这些歌曲已在你本次连接中完成同步，播放不会再次占用歌曲同步上行。"
            super.build()
            option("返回") { openMusicMenu(player, canAdmin) }
        }
    }.sendTo(player, 60_000)
}

private suspend fun openMusicMenu(player: Player, canAdmin: Boolean) {
    MenuBuilder<Unit>("服务器点歌") {
        msg = """
            |[cyan]网易云投票点歌：[white]/music vote <网易云歌曲/DJ ID或分享链接>
            |[gray]直接输入 /music <ID/链接> 也会发起投票，不会全服强制同步。
            |[cyan]手动重播已同步音乐：[white]/music synced
            |[cyan]停止自己音乐：[white]/music stop
            |[gray]音乐库目录：[white]${libraryDir.absolutePath}
        """.trimMargin()
        option("服务器音乐库") { openTrackListMenu(player, "服务器音乐库", libraryTracks(), canAdmin) }
        option("最近缓存") { openTrackListMenu(player, "最近点歌缓存", cachedTracks(), canAdmin) }
        newRow()
        option("我的已同步音乐") { openSyncedMusicMenu(player, canAdmin) }
        option("停止我的音乐") { stopMusicFor(player); player.sendMessage("[green]已停止你的点歌音乐。") }
        if (canAdmin) option("停止全服音乐") {
            stopMusicForAll(player.plainName())
            player.sendMessage("[green]已停止所有玩家的点歌音乐。")
        }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

command("music", "服务器点歌/音乐播放") {
    aliases = listOf("点歌", "bgm", "musicvote")
    usage = "[vote <网易云歌曲/DJ ID或链接>|yes|neutral|no|synced|replay <编号/名称>|stop|library|cache|limit|votelib <编号/名称>|queue|stopall]"
    body {
        val p = player
        val first = arg.getOrNull(0)?.lowercase(Locale.ROOT)
        val canAdmin = p == null || hasPermission(adminPermission)
        val canStaffControl = p != null && hasPermission(staffControlPermission)
        val canControl = canAdmin || canStaffControl
        when (first) {
            null, "menu", "菜单" -> {
                if (p == null) reply(musicCommandHelp(canAdmin, canControl).with())
                else openMusicMenu(p, canControl)
            }
            "help", "帮助" -> reply(musicCommandHelp(canAdmin, canControl).with())
            "yes", "y", "1", "同意" -> {
                val player = p ?: returnReply("[red]控制台不能参与点歌同意窗口。".with())
                handleVoteAnswer(player, MusicVoteChoice.Agree)
            }
            "neutral", "mid", "abstain", ".", "中立", "弃权", "跳过" -> {
                val player = p ?: returnReply("[red]控制台不能参与点歌投票。".with())
                handleVoteAnswer(player, MusicVoteChoice.Neutral)
            }
            "no", "n", "0", "拒绝", "反对" -> {
                val player = p ?: returnReply("[red]控制台不能参与点歌同意窗口。".with())
                handleVoteAnswer(player, MusicVoteChoice.Disagree)
            }
            "stop", "停止" -> {
                val player = p ?: returnReply("[red]控制台请使用 /music stopall。".with())
                stopMusicFor(player)
                reply("[green]已停止你的点歌音乐。".with())
            }
            "synced", "syncedlist", "已同步", "我的音乐" -> {
                val player = p ?: returnReply("[red]控制台没有玩家侧已同步音乐列表。".with())
                openSyncedMusicMenu(player, canAdmin)
            }
            "replay", "重播", "手动播放" -> {
                val player = p ?: returnReply("[red]控制台不能播放玩家侧音乐。".with())
                val tracks = syncedTracks(player)
                val input = arg.drop(1).joinToString(" ").trim()
                val track = input.toIntOrNull()?.let { tracks.getOrNull(it - 1) }
                    ?: tracks.firstOrNull {
                        input.isNotBlank() && (
                            it.title.equals(input, ignoreCase = true) ||
                                it.display.equals(input, ignoreCase = true) ||
                                it.assetName.equals(input, ignoreCase = true)
                            )
                    }
                    ?: returnReply("[red]未找到你本次连接中已同步的音乐。可使用 /music synced 打开菜单。".with())
                playMusicTo(player, track, interrupt = true)
                reply("[green]已手动播放：[gold]${track.display}".with())
            }
            "stopall", "停止全部", "stop-all" -> {
                if (!canControl) returnReply("[red]权限不足。".with())
                stopMusicForAll(p?.plainName() ?: "控制台")
                reply("[green]已停止所有玩家的点歌音乐，并取消当前点歌投票。".with())
            }
            "library", "lib", "音乐库" -> reply(trackListText("服务器音乐库", libraryTracks()).with())
            "cache", "缓存" -> reply(trackListText("最近点歌缓存", cachedTracks()).with())
            "queue", "队列" -> {
                val vote = activeVote
                val voteText = vote?.let { "\n[gray]当前点歌投票：[gold]${it.track.display}[gray]；${musicVoteSummary(it)}" }.orEmpty()
                val connectionStates = onlineMusicPlayers().groupingBy { player ->
                    player.con?.let { con ->
                        when {
                            con.determiningAssets -> "协商资产"
                            con.receivingAssets -> "接收资产"
                            !con.hasConnected -> "等待世界确认"
                            else -> "正常"
                        }
                    } ?: "无连接"
                }.eachCount()
                val stateText = connectionStates.entries.joinToString("，") { "${it.key} ${it.value}" }
                reply(
                    ("[cyan]点歌同步队列：[gold]${musicSyncQueue.size}[gray] 人等待；" +
                        "当前处理：[gold]${processingMusicAssetPath ?: "无"}[gray]。\n" +
                        "[cyan]连接状态：[white]$stateText$voteText").with()
                )
            }
            "limit", "limits", "限制" -> {
                if (!canAdmin) returnReply("[red]权限不足。".with())
                val sub = arg.getOrNull(1)?.lowercase(Locale.ROOT)
                val value = arg.getOrNull(2)
                when (sub) {
                    null, "show", "查看" -> reply(limitText().with())
                    "size", "maxsize", "大小" -> {
                        val bytes = value?.let(::parseByteLimit)
                            ?: returnReply("[red]用法：/music limit size <大小>，例如 24mb。".with())
                        if (bytes < minMusicBytes) returnReply("[red]大小限制不能低于 ${formatBytes(minMusicBytes)}。".with())
                        maxMusicBytes = bytes
                        reply("[green]已设置单曲最大大小为：[gold]${formatBytes(maxMusicBytes)}".with())
                    }
                    "duration", "time", "时长" -> {
                        val seconds = value?.let(::parseDurationSeconds)
                            ?: returnReply("[red]用法：/music limit duration <时长>，例如 10m、600、9:30。".with())
                        if (seconds <= 0L) returnReply("[red]时长限制必须大于0。".with())
                        maxDurationSeconds = seconds
                        reply("[green]已设置单曲最大时长为：[gold]${maxDurationSeconds}s".with())
                    }
                    "cache", "缓存" -> {
                        val count = value?.toIntOrNull()
                            ?: returnReply("[red]用法：/music limit cache <保留数量>，例如 6。".with())
                        if (count <= 0) returnReply("[red]缓存数量必须大于0。".with())
                        cacheMaxTracks = count
                        pruneJukeboxCache()
                        reply("[green]已设置网络点歌缓存保留数量为：[gold]${cacheMaxTracks} 首[gray]；已尝试清理超出的旧缓存。".with())
                    }
                    "syncplayers", "syncplayer", "同步人数" -> {
                        reply(
                            (
                                "[yellow]该限制已移除：[white]在线人数不再阻止点歌同步。\n" +
                                    "[gray]当前仍由单人串行队列、其他玩家世界同步检查和上行预算保护。"
                                ).with()
                        )
                    }
                    else -> reply(limitText().with())
                }
            }
            "playlib", "播放库" -> {
                val track = resolveLibraryTrack(arg.drop(1).joinToString(" "))
                    ?: returnReply("[red]未找到音乐库曲目。\n${trackListText("服务器音乐库", libraryTracks())}".with())
                p?.sendMessage("[yellow]全服直接播放歌曲功能已移除，将改为发起点歌投票。")
                startMusicVote(track, p)
            }
            "votelib", "投票库" -> {
                val player = p ?: returnReply("[red]控制台请使用 /music playlib。".with())
                val track = resolveLibraryTrack(arg.drop(1).joinToString(" "))
                    ?: returnReply("[red]未找到音乐库曲目。\n${trackListText("服务器音乐库", libraryTracks())}".with())
                startMusicVote(track, player)
            }
            "play", "播放" -> {
                val input = arg.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    ?: returnReply("[red]用法：/music vote <网易云歌曲/DJ ID或分享链接>".with())
                prepareAndThen(p, input, vote = false)
            }
            "vote", "投票" -> {
                val input = arg.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    ?: returnReply("[red]用法：/music vote <网易云歌曲/DJ ID或分享链接>".with())
                prepareAndThen(p, input, vote = true)
            }
            "cancel", "取消" -> {
                if (!canControl) returnReply("[red]权限不足。".with())
                activeVote = null
                preparingVoteBy = null
                reply("[green]已取消当前点歌投票。".with())
            }
            else -> {
                val input = arg.joinToString(" ").takeIf { it.isNotBlank() }
                    ?: returnReply(musicCommandHelp(canAdmin, canControl).with())
                prepareAndThen(p, input, vote = true)
            }
        }
    }
}

listen<EventType.PlayerChatEvent> { event ->
    handleMusicVoteChatAnswer(event.player, event.message)
}

private fun musicCommandHelp(canAdmin: Boolean, canControl: Boolean = canAdmin): String = buildString {
    appendLine("[cyan]服务器点歌")
    appendLine("[white]/music vote <网易云歌曲/DJ ID或分享链接>[gray] 发起60秒点歌投票；直接输入ID/链接也会发起投票")
    appendLine("[white]聊天直接发送单个 1|.|0[gray] 同意/中立/拒绝当前点歌投票；也兼容 [white]/music yes|neutral|no")
    appendLine("[white]/music stop[gray] 停止自己当前点歌音乐")
    appendLine("[white]/music synced[gray] 打开本次连接中已同步音乐的手动播放菜单")
    appendLine("[white]/music replay <编号/名称>[gray] 手动重播已同步音乐，不重复传输文件")
    appendLine("[white]/music library[gray] 查看服务器内置音乐库")
    appendLine("[white]/music votelib <编号/名称>[gray] 从音乐库发起点歌投票")
    appendLine("[white]/music queue[gray] 查看当前点歌投票/同步队列")
    if (canControl) {
        appendLine("[gold]协管：[white]/music stopall|cancel[gray] 停止全服音乐/取消点歌投票")
    }
    if (canAdmin) {
        appendLine("[gold]管理：[white]/music limit size|duration|cache <值>[gray] 调整限制")
    }
}

listen<EventType.PlayerLeave> { event ->
    val uuid = event.player.uuid()
    currentPlaying.remove(uuid)
    pendingPlaySerial.remove(uuid)
    clearPlayerMusicSyncCache(uuid)
    activeVote?.agree?.remove(uuid)
    activeVote?.neutral?.remove(uuid)
    activeVote?.disagree?.remove(uuid)
    cleanupIdleTransientMusicAssets()
}

listen<EventType.PlayerConnect> { event ->
    if (isMusicAssetSyncActive()) {
        logger.warning(
            "新玩家 ${event.player.plainName()} 在运行时音乐资产同步期间进入；" +
                "原版全局资产表可能让其额外参与当前歌曲协商。active=${with(worldResync) { activeResyncReason() }} " +
                "queue=${musicSyncQueue.size} online=${Groups.player.size()}"
        )
    }
}

onEnable {
    ensureDirs()
    removeLegacyAutoSyncedMusicAssets()
    cleanupIdleTransientMusicAssets()
    VoteEvent.registerStartBlocker(voteBlockerId) { _ -> musicVoteBlockMessage() }
}

onDisable {
    VoteEvent.unregisterStartBlocker(voteBlockerId)
    cleanupIdleTransientMusicAssets()
}

PermissionApi.registerDefault(adminPermission, group = "@admin")
PermissionApi.registerDefault(staffControlPermission, group = "@pluginAdmin")
