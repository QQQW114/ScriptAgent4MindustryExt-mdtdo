@file:Depends("coreMindustry/menu", "小音效菜单")
@file:Depends("wayzer/ext/musicJukebox", "服务器点歌")

package wayzer.ext

import arc.audio.Sound
import arc.files.Fi
import coreLibrary.lib.PermissionApi
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.broadcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import java.io.File
import java.util.Arrays

name = "服务器小音效菜单"

private val dataAssetSoundIdOffset = 100_000
private val musicJukebox = contextScript<wayzer.ext.MusicJukebox>()

private val defaultVolume by config.key(1.0, "播放小音效时的默认音量")
private val defaultPitch by config.key(1.0, "播放小音效时的默认音高")
private val minPlayIntervalMillis by config.key(800L, "全局播放小音效最小间隔，防止误点刷屏")
private val announceSoundPlay by config.key(false, "播放小音效时是否向全服发送文字提示")
private val assetSyncIntervalMillis by config.key(350L, "手动同步音效资产时每名玩家之间的间隔")
private val maxManualSyncPlayers by config.key(4, "允许管理员强制重发小音效资产的最大在线人数")
private val playViaMusicPacket by config.key(true, "使用playMusic字符串通道播放小音效；规避官方v159 DataAsset sound ID 超出short导致Call.sound无声的问题")
private val joinWarmupMillis by config.key(8_000L, "玩家加入后小音效首播保护窗口(ms)，窗口内改为延迟补发播放包")
private val joinWarmupReplayDelayMillis by config.key(3_500L, "玩家加入后小音效延迟补发等待(ms)，不触发世界/资产重同步")

private var lastPlayAt = 0L
private val syncedSoundAssets = mutableMapOf<String, MutableSet<String>>()
private val recentJoinAt = mutableMapOf<String, Long>()
private var lastDiskSoundWarnings: List<String> = emptyList()

private data class SoundEffectEntry(
    val index: Int,
    val id: Int,
    val name: String,
    val path: String,
    val hash: String?,
    val sound: Sound,
    val musicName: String,
) {
    val displayName: String get() = name.removePrefix("dp-").removePrefix("sfx-")
    val syncKey: String get() = "$path|$name|${hash ?: ""}"
}

private data class SoundPlayResult(
    val success: Boolean,
    val message: String,
)

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

private fun currentDataManager(): Any? = memberValue(Vars.state, "data")

private fun loadedSoundSeq(): arc.struct.Seq<Any>? {
    @Suppress("UNCHECKED_CAST")
    return callNoArg(currentDataManager(), "getSounds") as? arc.struct.Seq<Any>
}

private fun loadedMusicSeq(): arc.struct.Seq<Any>? {
    @Suppress("UNCHECKED_CAST")
    return callNoArg(currentDataManager(), "getMusic") as? arc.struct.Seq<Any>
}

private fun reloadAudioAssets() {
    val data = currentDataManager() ?: error("当前 Mindustry 版本没有 state.data，无法注册服务器小音效")
    val reload = data.javaClass.methods.firstOrNull { it.name == "reloadAudio" && it.parameterCount == 0 }
        ?: error("当前 Mindustry 版本没有 DataManager.reloadAudio，无法注册服务器小音效")
    reload.invoke(data)
}

private fun getSoundById(id: Int): Sound? = runCatching {
    val soundsClass = Class.forName("mindustry.gen.Sounds")
    val method = soundsClass.methods.firstOrNull { method ->
        method.name == "getSound" && method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.javaPrimitiveType
    } ?: return@runCatching null
    method.invoke(null, id) as? Sound
}.getOrNull()

private fun soundAssetDir(): File = File(Vars.dataDirectory.file(), "assets/sounds")

private fun audioFiles(root: File): List<File> {
    if (!root.exists()) root.mkdirs()
    return root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("ogg", "mp3") }
        .sortedBy { root.toPath().relativize(it.toPath()).toString().replace('\\', '/').lowercase() }
        .toList()
}

private fun relativeSoundAssetPath(file: File): String =
    soundAssetDir().toPath().relativize(file.toPath()).toString().replace('\\', '/')

private fun soundFileIssue(file: File): String? {
    val header = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(16)
            val len = input.read(buffer)
            if (len <= 0) ByteArray(0) else buffer.copyOf(len)
        }
    }.getOrNull() ?: return null
    val ext = file.extension.lowercase()
    val isOgg = header.size >= 4 && header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
        header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()
    val isMp3 = header.size >= 3 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
        header[2] == '3'.code.toByte() ||
        (header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
    val isMp4Family = header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

    return when {
        isMp4Family -> "${file.name} 实际是 M4A/MP4 容器，不是 Mindustry sound 可稳定解码的 MP3/OGG；请转码为真正的 .ogg 或 .mp3。"
        ext == "ogg" && !isOgg -> "${file.name} 扩展名为 .ogg，但文件头不是 OggS；若客户端无声，请重新转码。"
        ext == "mp3" && !isMp3 -> "${file.name} 扩展名为 .mp3，但文件头不像 MP3；若客户端无声，请重新转码。"
        else -> null
    }
}

private fun sfxAssetName(name: String): String = "sfx-$name"

private fun sfxMusicName(name: String): String = "dp-${sfxAssetName(name)}"

private fun registerAudioAssetFromFile(
    seq: arc.struct.Seq<Any>,
    assetClassName: String,
    path: String,
    publicName: String,
    file: File,
    hash: ByteArray?,
): Boolean {
    val assetClass = Class.forName(assetClassName)
    val readOverride = assetClass.methods.firstOrNull { method ->
        method.name == "readOverride" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == Fi::class.java
    } ?: error("当前 Mindustry 版本 $assetClassName 不支持 readOverride")

    val existing = seq.firstOrNull { asset ->
        (memberValue(asset, "path") as? String) == path ||
            (memberValue(asset, "name") as? String) == publicName
    }
    val existingHash = existing?.let { memberValue(it, "byteHash") as? ByteArray }
    if (existing != null && hash != null && existingHash != null && Arrays.equals(existingHash, hash)) {
        return false
    }

    for (i in seq.size - 1 downTo 0) {
        val old = seq[i]
        val oldPath = memberValue(old, "path") as? String
        val oldName = memberValue(old, "name") as? String
        if (oldPath == path || oldName == publicName) seq.remove(i)
    }

    val asset = assetClass.getDeclaredConstructor().newInstance()
    readOverride.invoke(asset, path, Fi(file))
    // DataAsset#setPath 会把 name 设为文件名。小音效复用 music 通道时加前缀，
    // 防止和地图/原版音乐同名，并让 /playMusic 可以按字符串稳定查到。
    assetClass.getField("name").set(asset, publicName)
    seq.add(asset)
    return true
}

private fun ensureSoundAssetsFromDisk(): Int {
    val soundSeq = loadedSoundSeq() ?: return 0
    val musicSeq = loadedMusicSeq()
    val files = audioFiles(soundAssetDir())
    if (files.isEmpty()) {
        lastDiskSoundWarnings = emptyList()
        return 0
    }

    var changed = 0
    val warnings = mutableListOf<String>()
    files.forEach { file ->
        val path = relativeSoundAssetPath(file)
        val name = file.nameWithoutExtension
        val assetName = sfxAssetName(name)
        // DataAsset 的 name 不会单独写入客户端世界数据，客户端会由 path 重新推导 name；
        // 因此 music path 必须让文件名本身带 sfx- 前缀，不能只放进 sfx/ 子目录。
        val musicPath = "$assetName.${file.extension.lowercase()}"
        val legacyMusicPath = "sfx/$path"
        val issue = soundFileIssue(file)
        if (issue != null) {
            warnings += issue
            var removed = false
            listOf(soundSeq, musicSeq).filterNotNull().forEach { seq ->
                for (i in seq.size - 1 downTo 0) {
                    val old = seq[i]
                    val oldPath = memberValue(old, "path") as? String
                    val oldName = memberValue(old, "name") as? String
                    if (oldPath == path || oldPath == musicPath || oldPath == legacyMusicPath || oldName == name || oldName == assetName) {
                        seq.remove(i)
                        removed = true
                    }
                }
            }
            if (removed) changed++
            return@forEach
        }

        val hash = runCatching { Fi(file).sha256() }.getOrNull()
        if (registerAudioAssetFromFile(soundSeq, "mindustry.mod.data.SoundAsset", path, name, file, hash)) {
            changed++
        }
        // 官方 v159 的 Call.sound 仍通过 short 写入 sound id，但 DataAudioLoader 给
        // DataAsset sound 分配 100001+ 的 ID，客户端读回后会错位/无声。这里额外把同一
        // 文件注册成 MusicAsset，用 Call.playMusic(String) 的字符串通道播放，避免 ID 溢出。
        // 客户端 DataAudioLoader 会把 data music 注册到 Core.assets 的 dp-${assetName} 键下，
        // 因此实际播放名必须带 dp- 前缀；只传 sfx-* 会被 SoundControl.findMusic 判定为找不到。
        if (musicSeq != null &&
            registerAudioAssetFromFile(musicSeq, "mindustry.mod.data.MusicAsset", musicPath, assetName, file, hash)
        ) {
            changed++
        }
    }

    lastDiskSoundWarnings = warnings
    if (changed > 0) {
        reloadAudioAssets()
        syncedSoundAssets.clear()
        logger.info("已热注册/刷新 $changed 个服务器小音效资产。")
    }
    return changed
}

private fun ensureSoundAssetsForWorld(reason: String = "manual"): Int =
    runCatching { ensureSoundAssetsFromDisk() }.getOrElse {
        logger.warning("刷新服务器小音效资产失败($reason): ${it.stackTraceToString()}")
        0
    }

private fun normalizeSoundKey(text: String): String {
    var value = text.trim().lowercase()
    repeat(2) {
        if (value.startsWith("@sfx-")) value = value.removePrefix("@sfx-")
        if (value.startsWith("dp-")) value = value.removePrefix("dp-")
        if (value.startsWith("sfx-")) value = value.removePrefix("sfx-")
    }
    listOf(".ogg", ".mp3").forEach { ext ->
        if (value.endsWith(ext)) value = value.dropLast(ext.length)
    }
    return value
}

private fun loadedSoundEffects(): List<SoundEffectEntry> {
    runCatching { ensureSoundAssetsFromDisk() }.onFailure {
        logger.warning("热注册服务器小音效资产失败: ${it.stackTraceToString()}")
    }
    val data = currentDataManager() ?: return emptyList()
    val sounds = (callNoArg(data, "getSounds") as? Iterable<*>) ?: return emptyList()
    val diskFiles = audioFiles(soundAssetDir())
    val entries = mutableListOf<SoundEffectEntry>()
    var nextId = dataAssetSoundIdOffset + 1
    val soundAssetByKey = mutableMapOf<String, Triple<Int, String?, Sound>>()

    for (asset in sounds) {
        val name = (memberValue(asset, "name") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: continue
        val path = (memberValue(asset, "path") as? String)?.takeIf { it.isNotBlank() } ?: name
        val hash = memberValue(asset, "stringHash") as? String

        // Dedicated server/headless 模式下 DataAudioLoader 会给每个 SoundAsset 连续注册 ID；
        // 即使文件名重复，也必须继续递增，避免后续音效 ID 错位。
        val id = nextId++
        val sound = getSoundById(id) ?: continue
        soundAssetByKey[path] = Triple(id, hash, sound)
        soundAssetByKey[name] = Triple(id, hash, sound)
    }

    diskFiles.forEach { file ->
        if (soundFileIssue(file) != null) return@forEach
        val path = relativeSoundAssetPath(file)
        val name = file.nameWithoutExtension
        val resolved = soundAssetByKey[path] ?: soundAssetByKey[name] ?: return@forEach
        entries += SoundEffectEntry(
            index = entries.size + 1,
            id = resolved.first,
            name = name,
            path = path,
            hash = resolved.second,
            sound = resolved.third,
            musicName = sfxMusicName(name),
        )
    }
    return entries
}

private fun markSoundEffectsSynced(player: Player, entries: List<SoundEffectEntry> = loadedSoundEffects()) {
    if (entries.isEmpty()) return
    val set = syncedSoundAssets.getOrPut(player.uuid()) { linkedSetOf() }
    entries.forEach { set.add(it.syncKey) }
}

private fun resolveSoundEffect(input: String?): SoundEffectEntry? {
    val text = input?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val entries = loadedSoundEffects()
    text.toIntOrNull()?.let { index -> entries.getOrNull(index - 1)?.let { return it } }
    val key = normalizeSoundKey(text)
    return entries.firstOrNull { entry ->
        normalizeSoundKey(entry.name) == key ||
            normalizeSoundKey(entry.displayName) == key ||
            normalizeSoundKey(entry.path.substringAfterLast('/')) == key ||
            normalizeSoundKey(entry.path) == key
    }
}

private fun soundEffectListText(): String {
    val entries = loadedSoundEffects()
    return buildString {
        appendLine("[cyan]服务器小音效列表")
        appendLine("[gray]目录：[white]${soundAssetDir().absolutePath}")
        appendLine("[gray]来源：Mindustry 159 Data Asset；播放默认走 playMusic 字符串通道规避官方sound ID问题。")
        if (entries.isEmpty()) {
            appendLine("[yellow]当前没有可播放音效。请将 .ogg/.mp3 放入 assets/sounds 后重启或换图加载资产。")
        } else {
            entries.forEach { entry ->
                appendLine("[gray]${entry.index}. [gold]${entry.displayName}[gray] soundId=${entry.id} music=${entry.musicName} path=${entry.path}")
            }
        }
        if (lastDiskSoundWarnings.isNotEmpty()) {
            appendLine()
            appendLine("[red]音频文件警告：")
            lastDiskSoundWarnings.take(8).forEach { appendLine("[yellow]- $it") }
            if (lastDiskSoundWarnings.size > 8) appendLine("[gray]……另有 ${lastDiskSoundWarnings.size - 8} 条。")
        }
    }
}

private fun playSoundEffect(
    entry: SoundEffectEntry,
    operator: String,
    volume: Float = defaultVolume.toFloat(),
    pitch: Float = defaultPitch.toFloat(),
    ignoreInterval: Boolean = false,
): SoundPlayResult {
    val now = System.currentTimeMillis()
    val interval = minPlayIntervalMillis.coerceAtLeast(0L)
    if (!ignoreInterval && interval > 0 && now - lastPlayAt < interval) {
        return SoundPlayResult(false, "[yellow]播放过快，请稍后再试。")
    }
    if (!ignoreInterval) lastPlayAt = now

    return runCatching {
        val targets = Groups.player.toList().filter { it.con != null && !it.isLocal }
        val safeVolume = volume.coerceIn(0f, 8f)
        val safePitch = pitch.coerceIn(0.05f, 20f)

        fun sendTo(player: Player) {
            player.con?.let { con ->
                if (playViaMusicPacket) {
                    // 小音效借用了音乐通道；interrupt=true 会直接停止点歌。
                    // 正在听点歌的玩家跳过本次小音效，优先保证长音乐不被短音效截断。
                    if (with(musicJukebox) { isJukeboxMusicPlaying(player) }) return
                    Call.playMusic(con, entry.musicName, true)
                } else {
                    Call.sound(con, entry.sound, safeVolume, safePitch, 0f)
                }
            }
        }

        fun shouldDelayForJoin(player: Player): Boolean {
            val joinedAt = recentJoinAt[player.uuid()] ?: return false
            val age = System.currentTimeMillis() - joinedAt
            return age >= 0L && age < joinWarmupMillis.coerceAtLeast(0L)
        }

        // 官方 v159 的 sendWorldAndAssets 会让客户端重载世界；实测在玩家刚进服后
        // 自动触发容易卡在“无核心机/无单位”状态。小音效播放链路绝不自动补发资产，
        // 只在管理员显式 /sfx sync 时同步。首次进服无声通常是客户端资产已经收到，
        // 但 dp-sfx-* 音频还没完成本地注册；对刚进服玩家延迟补发播放包即可规避，
        // 不需要重新发送世界/资产。
        val delayedTargets = targets.filter(::shouldDelayForJoin)
        targets.filterNot { it in delayedTargets }.forEach { player -> sendTo(player) }
        if (delayedTargets.isNotEmpty()) {
            val delayMillis = joinWarmupReplayDelayMillis.coerceAtLeast(0L)
            launch(Dispatchers.game) {
                if (delayMillis > 0L) delay(delayMillis)
                delayedTargets.forEach { player ->
                    if (player.con != null && !player.isLocal) sendTo(player)
                }
            }
        }
        logger.info("管理员 $operator 播放服务器小音效: ${entry.name} id=${entry.id} music=${entry.musicName} viaMusic=$playViaMusicPacket")
        if (announceSoundPlay) {
            broadcast("[accent]管理员播放了小音效：[gold]${entry.displayName}".with())
        }
        val channelTip = if (playViaMusicPacket) "；使用音乐通道，玩家音乐音量为0时会无声" else ""
        val joinTip = if (delayedTargets.isNotEmpty()) "；${delayedTargets.size}名刚进服玩家将延迟补发" else ""
        val syncTip = "（不会中途重发资产，避免客户端单位/世界重载$joinTip）"
        SoundPlayResult(true, "[green]已向全服播放小音效：[gold]${entry.displayName}[gray]$syncTip$channelTip")
    }.getOrElse {
        logger.warning("播放服务器小音效失败 ${entry.name}: ${it.stackTraceToString()}")
        SoundPlayResult(false, "[red]播放失败：[white]${it.message ?: it.javaClass.simpleName}")
    }
}

fun hasFixedSoundEffect(input: String): Boolean = resolveSoundEffect(input) != null

fun playFixedSoundEffect(
    input: String,
    operator: String = "script",
    ignoreInterval: Boolean = true,
): String {
    val entry = resolveSoundEffect(input)
        ?: return "[red]未找到小音效：[white]$input"
    return playSoundEffect(entry, operator, ignoreInterval = ignoreInterval).message
}

private suspend fun suppressReconnectIntroAfterAssetWorld(player: Player, originalCon: mindustry.net.NetConnection) {
    // 官方 v159 的 sendWorldAndAssets 会把 hasConnected 置回 false；
    // 若这是管理员手动 /sfx sync 触发的资产热同步，不应让客户端再次显示进服介绍/触发 PlayerJoin 链路。
    val deadline = System.currentTimeMillis() + 15_000L
    while (System.currentTimeMillis() < deadline) {
        if (player.con !== originalCon) return
        if (originalCon.hasConnected) return
        if (!originalCon.determiningAssets && !originalCon.receivingAssets) {
            originalCon.hasConnected = true
            return
        }
        delay(25L)
    }
}

private suspend fun sendWorldAndAssetsCompat(player: Player): Boolean {
    val con = player.con ?: return false
    val sendWorldAndAssets = Vars.netServer.javaClass.methods.firstOrNull { method ->
        method.name == "sendWorldAndAssets" && method.parameterTypes.size == 1
    }
    if (sendWorldAndAssets != null) {
        sendWorldAndAssets.invoke(Vars.netServer, player)
        suppressReconnectIntroAfterAssetWorld(player, con)
        return true
    }
    Call.worldDataBegin(con)
    Vars.netServer.sendWorldData(player)
    return false
}

private fun syncSoundAssetsToPlayers(operator: String, notify: Player? = null) {
    val players = Groups.player.toList().filter { it.con != null && !it.isLocal }
    if (players.isEmpty()) {
        notify?.sendMessage("[yellow]当前没有需要同步的在线玩家。")
        return
    }
    if (with(musicJukebox) { isMusicAssetSyncActive() }) {
        notify?.sendMessage("[yellow]当前正在进行音乐资产同步，不能叠加全服小音效重同步。")
        return
    }
    val safeMax = maxManualSyncPlayers.coerceAtLeast(1)
    if (players.size > safeMax) {
        notify?.sendMessage(
            "[yellow]当前在线 ${players.size} 人，已拒绝全服小音效世界重同步。\n" +
                "[gray]该操作会让所有玩家重新加载世界并瞬间占满上行；安全上限为 $safeMax 人。"
        )
        logger.warning("管理员 $operator 尝试全服重发小音效资产被容量保护拒绝: players=${players.size}, limit=$safeMax")
        return
    }
    launch(Dispatchers.game) {
        var assetSyncSupported = false
        var success = 0
        players.forEachIndexed { index, player ->
            if (index > 0) delay(assetSyncIntervalMillis.coerceAtLeast(0L))
            runCatching {
                if (player.con != null) {
                    if (sendWorldAndAssetsCompat(player)) {
                        assetSyncSupported = true
                        markSoundEffectsSynced(player)
                    }
                    success++
                }
            }.onFailure {
                logger.warning("同步小音效资产给 ${player.plainName()} 失败: ${it.message}")
            }
        }
        val message = if (assetSyncSupported) {
            "[green]已尝试向 $success/${players.size} 名玩家同步世界与服务器资产。"
        } else {
            "[yellow]当前 Mindustry 版本没有 sendWorldAndAssets，仅执行普通世界同步；声音资产可能仍需玩家重进。"
        }
        notify?.sendMessage(message)
        logger.info("管理员 $operator 手动同步服务器小音效资产: $success/${players.size}, assets=$assetSyncSupported")
    }
}

private suspend fun openSoundEffectMenu(player: Player) {
    val entries = loadedSoundEffects()
    if (entries.isEmpty()) {
        MenuBuilder<Unit>("服务器小音效") {
            msg = soundEffectListText()
            option("刷新") { openSoundEffectMenu(player) }
            option("[red]强制重发资产") { syncSoundAssetsToPlayers(player.name, player); openSoundEffectMenu(player) }
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }

    object : PagedMenuBuilder<SoundEffectEntry>(entries, prePage = 8) {
        override suspend fun renderItem(item: SoundEffectEntry) {
            option("[gold]${item.displayName}\n[gray]#${item.index} ${if (playViaMusicPacket) item.musicName else "id=${item.id}"}") {
                val result = playSoundEffect(item, player.name)
                player.sendMessage(result.message)
                openSoundEffectMenu(player)
            }
        }

        override suspend fun build() {
            title = "服务器小音效"
            msg = """
                |[cyan]点击条目会立即向全服播放。
                |[gray]目录：[white]${soundAssetDir().absolutePath}
                |[gray]新放入音频后点“刷新列表”或使用 /sfx reload。
                |[yellow]注意：官方v159中途重发资产会让客户端重载世界，播放时不会自动同步；/sfx sync 仅排障时手动使用。
                |[gray]当前播放通道：[white]${if (playViaMusicPacket) "playMusic(受客户端音乐音量影响)" else "Call.sound"}
            """.trimMargin()
            option("刷新列表") { openSoundEffectMenu(player) }
            option("[red]强制重发资产") { syncSoundAssetsToPlayers(player.name, player); refresh() }
            newRow()
            super.build()
        }
    }.sendTo(player, 60_000)
}

// 在脚本加载阶段先注册一次；onEnable/WorldLoadEvent 还会再兜底注册。
// 地图加载可能替换 state.data，若只在脚本加载时注册，玩家首次进服可能拿不到 sfx-* 资产，
// 直到脚本后续 /sfx play/list 再注册，导致“首次进服无声，重进正常”。
ensureSoundAssetsForWorld("script-load")

listen<EventType.WorldLoadEvent> {
    ensureSoundAssetsForWorld("world-load")
}

listen<EventType.PlayerJoin> { event ->
    recentJoinAt[event.player.uuid()] = System.currentTimeMillis()
}

listen<EventType.PlayerLeave> { event ->
    recentJoinAt.remove(event.player.uuid())
    syncedSoundAssets.remove(event.player.uuid())
}

onEnable {
    ensureSoundAssetsForWorld("enable")
}

command("sfx", "管理指令：播放服务器预置小音效") {
    aliases = listOf("soundfx", "sounds", "音效", "小音效", "播放音效")
    usage = "[list|play <编号/名称>|dir|reload|sync]"
    permission = "wayzer.admin.soundEffect"
    body {
        val p = player
        val first = arg.getOrNull(0)
        when (first?.lowercase()) {
            null, "menu", "菜单" -> {
                if (p != null) openSoundEffectMenu(p) else reply(soundEffectListText().with())
            }
            "list", "列表" -> reply(soundEffectListText().with())
            "dir", "目录" -> reply("[cyan]小音效目录：[white]${soundAssetDir().absolutePath}".with())
            "reload", "重载", "刷新资产" -> {
                val changed = runCatching { ensureSoundAssetsFromDisk() }.getOrElse {
                    returnReply("[red]刷新小音效资产失败：[white]${it.message ?: it.javaClass.simpleName}".with())
                }
                reply("[green]已刷新小音效资产，变更 $changed 个。\n${soundEffectListText()}".with())
            }
            "sync", "同步", "同步资产" -> {
                syncSoundAssetsToPlayers(p?.name ?: "控制台", p)
                reply("[yellow]已开始分批同步世界与服务器资产，请稍候。此操作会让客户端重载世界，可能短暂丢失单位/核心机显示；仅建议排障时手动使用。".with())
            }
            "play", "播放" -> {
                val target = arg.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    ?: returnReply("[red]用法：/sfx play <编号/名称>".with())
                val entry = resolveSoundEffect(target)
                    ?: returnReply("[red]未找到小音效：[white]$target\n${soundEffectListText()}".with())
                val result = playSoundEffect(entry, p?.name ?: "控制台")
                reply(result.message.with())
            }
            else -> {
                val target = arg.joinToString(" ")
                val entry = resolveSoundEffect(target)
                    ?: returnReply("[red]未找到小音效：[white]$target\n${soundEffectListText()}".with())
                val result = playSoundEffect(entry, p?.name ?: "控制台")
                reply(result.message.with())
            }
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.soundEffect", group = "@admin")
