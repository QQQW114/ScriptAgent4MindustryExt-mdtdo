package coreMindustry

import mindustry.game.EventType.TextInputEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class OnTextInputResult(val player: Player, val id: Int, val text: String?) : Event {
    companion object : Event.Handler()
}

listen<TextInputEvent> {
    OnTextInputResult(it.player, it.textInputId, it.text).launchEmit(coroutineContext + Dispatchers.game)
}

/** 玩家 -> 当前未结束的 textInput 数量（弹窗/输入框也算“已有弹窗”，入服提示需要避开）。 */
private val activeTextInputs = ConcurrentHashMap<String, Int>()

fun hasActiveTextInput(player: Player): Boolean = (activeTextInputs[player.uuid()] ?: 0) > 0

suspend fun textInput(
    player: Player,
    title: String,
    message: String = "",
    default: String = "",
    lengthLimit: Int = Int.MAX_VALUE,
    isNumeric: Boolean = false,
    timeoutMillis: Int = 60_000
): String? {
    val id = Random.nextInt(Int.MIN_VALUE, 0)
    val key = player.uuid()
    activeTextInputs.merge(key, 1, Int::plus)
    try {
        return withTimeoutOrNull(timeoutMillis.toLong()) {
            Call.textInput(player.con, id, title, message, lengthLimit, default, isNumeric)
            nextEvent<OnTextInputResult> { it.player == player && it.id == id }.text
        }
    } finally {
        activeTextInputs.computeIfPresent(key) { _, count -> if (count <= 1) null else count - 1 }
    }
}
export(::textInput)
export(::hasActiveTextInput)
