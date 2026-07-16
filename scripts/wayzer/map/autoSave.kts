package wayzer.map

import arc.files.Fi
import arc.struct.StringMap
import cf.wayzer.placehold.VarString
import coreLibrary.lib.util.loop
import mindustry.core.GameState
import mindustry.io.SaveIO
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Level

name = "自动存档"
val autoSaveRange = 100 until 106
val voteSaveRange = 106 until 111

private fun saveSlotLine(id: Int, label: String): VarString? {
    val file = SaveIO.fileFor(id)
    if (!file.exists()) return null
    return if (SaveIO.isSaveValid(file)) {
        "[red]{id}[]: [yellow]{label}[] / [white]Save on {date hh:mm}".with(
            "id" to id,
            "label" to label,
            "date" to Date(file.lastModified())
        )
    } else {
        "[red]{id}[]: [scarlet]{label}[] / 存档损坏".with("id" to id, "label" to label)
    }
}

command("slots", "列出自动保存的存档") {
    body {
        val autoList = autoSaveRange.mapNotNull { saveSlotLine(it, "自动存档") }
        val voteList = voteSaveRange.mapNotNull { saveSlotLine(it, "投票存档") }
        reply(
            """
            |[green]===[white] 自动存档 [green]===
            |{autoList|joinLines}
            |[green]===[white] 投票存档 [green]===
            |{voteList|joinLines}
            |[gray]回档：/vote rollback <存档ID>
            |[gray]创建投票存档：/vote save [存档ID] [备注]
            |[green]===[white] 自动{autoRange} / 投票{voteRange} [green]===
        """.trimMargin().with(
            "autoRange" to autoSaveRange,
            "voteRange" to voteSaveRange,
            "autoList" to autoList,
            "voteList" to voteList
        )
        )
    }
}

val nextSaveTime: Date
    get() {//Every 10 minutes
        val t = Calendar.getInstance()
        t.set(Calendar.SECOND, 0)
        val mNow = t.get(Calendar.MINUTE)
        t.add(Calendar.MINUTE, (mNow + 10) / 10 * 10 - mNow)
        return t.time
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

onEnable {
    loop {
        val nextTime = nextSaveTime.time
        delay(nextTime - System.currentTimeMillis())
        if (state.`is`(GameState.State.playing)) {
            val minute = ((nextTime / TimeUnit.MINUTES.toMillis(1)) % 60).toInt() //Get the minute
            Core.app.post {
                val id = autoSaveRange.first + minute / 10
                val tmp = Fi.tempFile("save")
                try {
                    val extTag = StringMap.of(
                        "name", "[回档$id]" + state.map.name(),
                        "description", state.map.description(),
                        "author", state.map.author(),
                    )
                    writeSaveCompat(tmp, extTag)
                    tmp.moveTo(SaveIO.fileFor(id))
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "存档存档出错", e)
                    tmp.delete()
                }
                broadcast("[green]自动存档完成(整10分钟一次),存档号 [red]{id}".with("id" to id))
            }
        }
    }
}
