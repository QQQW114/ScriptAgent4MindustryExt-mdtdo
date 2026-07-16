@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/map/autoSave", "存档槽列表")

package wayzer.cmds

import arc.Core
import arc.files.Fi
import arc.struct.StringMap
import coreLibrary.lib.with
import coreMindustry.lib.broadcast
import kotlinx.coroutines.CompletableDeferred
import mindustry.Vars
import mindustry.core.GameState
import mindustry.io.SaveIO
import wayzer.VoteService
import java.util.Date
import java.util.logging.Level

name = "投票创建存档"

private val voteSaveSlots = 106..110
private val minVoteSaveIntervalMillis by config.key(5 * 60_000L, "投票创建存档成功后的最小间隔(ms)")
private var lastVoteSaveAt = 0L

private fun sanitizeNote(text: String): String =
    text.replace('\n', ' ').replace('\r', ' ').trim().take(80)

private fun cooldownLeftMillis(): Long =
    (lastVoteSaveAt + minVoteSaveIntervalMillis - System.currentTimeMillis()).coerceAtLeast(0L)

private fun formatLeft(ms: Long): String =
    "${(ms + 999L) / 1000L}秒"

private fun chooseVoteSaveSlot(): Int {
    voteSaveSlots.firstOrNull {
        val file = SaveIO.fileFor(it)
        !file.exists() || !SaveIO.isSaveValid(file)
    }?.let { return it }
    return voteSaveSlots.minByOrNull { SaveIO.fileFor(it).lastModified() } ?: voteSaveSlots.first
}

private fun slotStateText(slot: Int): String {
    val file = SaveIO.fileFor(slot)
    if (!file.exists()) return "[green]空槽"
    if (!SaveIO.isSaveValid(file)) return "[scarlet]原槽位存档损坏，将覆盖"
    return "[yellow]将覆盖原存档，原保存时间：${Date(file.lastModified())}"
}

private fun writeSaveCompat(file: Fi, extraTags: StringMap) {
    val legacyMethod = SaveIO::class.java.methods.firstOrNull { method ->
        method.name == "write" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Fi::class.java &&
                method.parameterTypes[1] == StringMap::class.java
    }
    if (legacyMethod != null) {
        legacyMethod.invoke(null, file, extraTags)
        return
    }

    val saveOptionsClass = Class.forName("mindustry.io.SaveOptions")
    val options = saveOptionsClass.getDeclaredConstructor().newInstance()
    saveOptionsClass.getField("extraTags").set(options, extraTags)
    val writeMethod = SaveIO::class.java.methods.firstOrNull { method ->
        method.name == "write" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Fi::class.java &&
                method.parameterTypes[1] == saveOptionsClass
    } ?: error("当前 Mindustry 版本找不到可用的 SaveIO.write(Fi, tags/options) 方法")
    writeMethod.invoke(null, file, options)
}

private suspend fun writeVoteSave(slot: Int, note: String, starterName: String): Boolean {
    val result = CompletableDeferred<Boolean>()
    Core.app.post {
        val tmp = Fi.tempFile("vote-save")
        try {
            if (!Vars.state.`is`(GameState.State.playing)) {
                tmp.delete()
                result.complete(false)
                return@post
            }
            val description = buildString {
                append(Vars.state.map.description())
                append("\n\n[gray]MDT投票存档：由 ")
                append(starterName)
                append(" 发起")
                if (note.isNotBlank()) {
                    append("；备注：")
                    append(note)
                }
            }
            val extTag = StringMap.of(
                "name", "[投票存档$slot]" + Vars.state.map.name(),
                "description", description,
                "author", Vars.state.map.author(),
            )
            writeSaveCompat(tmp, extTag)
            tmp.moveTo(SaveIO.fileFor(slot))
            lastVoteSaveAt = System.currentTimeMillis()
            result.complete(true)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "投票创建存档失败", e)
            tmp.delete()
            result.complete(false)
        }
    }
    return result.await()
}

fun VoteService.registerVoteSave() {
    addSubVote("创建当前游戏存档", "[存档ID] [备注]", "save", "存档") {
        if (!Vars.state.`is`(GameState.State.playing))
            returnReply("[red]当前不在游戏中，不能创建存档。".with())

        val left = cooldownLeftMillis()
        if (left > 0)
            returnReply("[yellow]距离上次投票存档成功还需要等待 [white]${formatLeft(left)}[yellow]。".with())

        val first = arg.firstOrNull()
        val explicitSlot = first?.toIntOrNull()
        if (explicitSlot == null && first != null && first.all { it.isDigit() })
            returnReply("[red]存档编号不合法。可用槽位：${voteSaveSlots.first}-${voteSaveSlots.last}".with())

        val slot = explicitSlot ?: chooseVoteSaveSlot()
        if (slot !in voteSaveSlots)
            returnReply("[red]投票存档只能使用槽位 ${voteSaveSlots.first}-${voteSaveSlots.last}，避免覆盖自动存档/系统临时槽。".with())

        val note = sanitizeNote((if (explicitSlot == null) arg else arg.drop(1)).joinToString(" "))
        val starter = player!!
        val starterName = starter.plainName()
        val eventDesc = "创建存档([green]{slot}[])".with("slot" to slot)
        val extDesc = """
            |[white]目标槽位：[yellow]$slot [gray]（投票存档槽：${voteSaveSlots.first}-${voteSaveSlots.last}）
            |[white]当前地图：[accent]${Vars.state.map.name()}[]
            |[white]备注：[lightgray]${note.ifBlank { "无" }}[]
            |[white]槽位状态：${slotStateText(slot)}
        """.trimMargin()

        VoteService.start(starter, eventDesc, extDesc = extDesc, supportSingle = true) {
            val currentLeft = cooldownLeftMillis()
            if (currentLeft > 0) {
                broadcast("[yellow]投票已通过，但投票存档冷却中，剩余 [white]${formatLeft(currentLeft)}[yellow]。".with())
                return@start
            }
            if (writeVoteSave(slot, note, starterName)) {
                broadcast("[green]投票存档创建成功，存档号：[red]{slot}[green]。可用 [gold]/slots[] 查看，或 [gold]/vote rollback {slot}[] 回档。".with("slot" to slot))
            } else {
                broadcast("[red]投票存档创建失败：当前不在游戏中或写入存档时发生错误，请查看服务端日志。".with())
            }
        }
    }
}

onEnable {
    VoteService.registerVoteSave()
}
