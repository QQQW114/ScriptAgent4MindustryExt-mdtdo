package wayzer.ext

import arc.func.Floatp
import arc.util.Reflect
import arc.util.Time
import mindustry.core.Version

val provider: Floatp = Reflect.get(Time::class.java, "deltaimpl")

onEnable {
    if (Version.build > 146) {
        logger.warning("Mindustry新版本已增加TPS限制，可以删除该脚本")
    }
    Time.setDeltaProvider {
        provider.get().coerceAtMost(6f)//10TPS at least
    }
}

onDisable {
    Time.setDeltaProvider(provider)
}