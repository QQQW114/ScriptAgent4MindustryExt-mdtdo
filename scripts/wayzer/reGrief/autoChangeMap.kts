@file:Depends("wayzer/maps", "换图")

package wayzer.reGrief

import wayzer.MapManager
import wayzer.MapRegistry
import kotlin.time.Duration.Companion.seconds

//预防一些卡服图，玩家无法复活发起投票

var newMap = false
var counter = 0
val idleFallbackMapId by config.key(13752, "无人游玩自动换到的默认地图ID")

fun hasPlayableAlivePlayer(): Boolean =
    Groups.player.count { !it.dead() && it.unit().health > 0 } > 0

onEnable {
    loop(Dispatchers.game) {
        delay(1.seconds)
        if (newMap || hasPlayableAlivePlayer()) {
            counter = 0
            return@loop
        }
        counter += 1
        if (counter > 100) {
            broadcast("[red]无人游玩，5秒后自动换到默认地图 [white]$idleFallbackMapId[red]".with())
            delay(5.seconds)
            if (newMap || hasPlayableAlivePlayer()) {
                counter = 0
                return@loop
            }
            val targetMap = MapRegistry.findById(idleFallbackMapId)
            val loaded = if (targetMap == null) {
                logger.warning("无人游玩自动换图：未找到默认地图ID $idleFallbackMapId，改为随机换图")
                MapManager.loadMapSync()
            } else {
                MapManager.loadMapSync(targetMap).also { ok ->
                    if (!ok) logger.warning("无人游玩自动换图：默认地图ID $idleFallbackMapId 加载被取消或失败，下一轮将继续检测")
                }
            }
            newMap = loaded
            counter = 0
        }
    }
}

listen<EventType.ResetEvent> {
    newMap = true
}

listen<EventType.ConnectPacketEvent> {
    //Someone request connect, maybe want to play
    newMap = false
}
