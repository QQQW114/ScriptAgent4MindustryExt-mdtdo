@file:Depends("coreLibrary/lang", "多语言支持-核心")
@file:Depends("coreLibrary/extApi/KVStore", "储存语言设置")

package wayzer.user

import cf.wayzer.placehold.DynamicVar
import org.h2.mvstore.type.StringDataType

name = "玩家语言设置"

// MVMap 必须完全封装在本脚本 ClassLoader 内，不能作为公开属性跨脚本访问。
// SA 3.4 下其它脚本若直接调用 settings[uid]，会要求两个脚本加载器中的
// org.h2.mvstore.MVMap 满足同一类型约束，与 MDT H2 JDBC 驱动发生 LinkageError。
private val settings = contextScript<coreLibrary.extApi.KVStore>().open("langSettings", StringDataType.INSTANCE)

fun hasManualLangSetting(uid: String): Boolean = settings[uid] != null

var PlayerData.lang: String
    get() = settings[id] ?: player?.locale ?: "zh"
    set(v) {
        if (lang == v) return
        if (v == player?.locale) {
            settings.remove(id)
        } else {
            settings[id] = v
        }
    }

registerVarForType<Player>()
    .registerChild("lang", "多语言支持",  {
        kotlin.runCatching { PlayerData[it].lang }.getOrNull()
    })

command("lang", "设置语言") {
    permission = "wayzer.lang.set"
    type = CommandType.Client
    body {
        if (arg.isEmpty()) returnReply("[yellow]你的当前语言是: {receiver.lang}".with())
        val data = PlayerData[player!!]
        data.lang = arg[0]
        reply("[green]你的语言已设为 {v}".with("v" to data.lang))
    }
}

PermissionApi.registerDefault("wayzer.lang.set")
