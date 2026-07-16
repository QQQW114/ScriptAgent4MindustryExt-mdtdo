package wayzer.user.ext

import arc.util.io.Writes
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.CommandContext
import coreLibrary.lib.CommandHandler
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import coreMindustry.lib.ClientOnly
import coreMindustry.lib.broadcast
import coreMindustry.lib.player
import mindustry.Vars
import mindustry.gen.Building
import mindustry.gen.Call
import mindustry.gen.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration


object SkillPrecheck : CommandHandler {
    private val mapDisabled get() = Vars.state.rules.tags.getBool("@noSkills")

    context(CommandContext) override suspend fun handle() {
        ClientOnly.handle()
        val unit = player!!.unit()
        if (mapDisabled) returnReply("[red]当前地图禁用技能".with())
        if (player!!.dead() || unit == null || unit.dead) returnReply("[red]死亡状态无法使用技能".with())
    }
}

/** 技能基础检查：仅要求玩家在游戏内且未死亡，不检查 @noSkills。用于 3级技能/管理员技能/特殊技能的显式绕过。 */
object SkillPrecheckIgnoreNoSkills : CommandHandler {
    context(CommandContext) override suspend fun handle() {
        ClientOnly.handle()
        val unit = player!!.unit()
        if (player!!.dead() || unit == null || unit.dead) returnReply("[red]死亡状态无法使用技能".with())
    }
}

/** 3级技能检查：默认绕过普通 @noSkills，但会被投票纯净模式额外禁用。 */
object SkillPrecheckLevel3 : CommandHandler {
    private val pureModeDisabled get() = Vars.state.rules.tags.getBool("@pureNoLevel3Skills")

    context(CommandContext) override suspend fun handle() {
        ClientOnly.handle()
        val unit = player!!.unit()
        if (pureModeDisabled) returnReply("[red]当前处于纯净模式，3级技能已禁用".with())
        if (player!!.dead() || unit == null || unit.dead) returnReply("[red]死亡状态无法使用技能".with())
    }
}

object SkillNoPvp : CommandHandler {
    context(CommandContext) override suspend fun handle() {
        if (Vars.state.rules.pvp) returnReply("[red]当前技能PVP模式禁用".with())
    }
}

/** 技能冷却
 * @param coolDown in ms, -1一局冷却
 * */
class SkillCooldown(val coolDown: Int = -1) : CommandHandler {
    private val lastUsed = mutableMapOf<String, Long>()

    init {
        SkillCommands.allCooldown.add(this)
    }

    context(CommandContext) override suspend fun handle() {
        if (!checkCoolDown()) CommandInfo.Return()
    }

    context(CommandContext) fun checkCoolDown(): Boolean {
        val key = player!!.uuid()
        val usedAt = lastUsed[key]
        if (usedAt != null) {
            if (coolDown < 0) {
                reply("[red]该技能每局限用一次".with())
                return false
            }
            val now = System.currentTimeMillis()
            val cooldownUntil = usedAt + coolDown
            if (cooldownUntil >= now) {
                reply(
                    "[red]技能冷却，还剩{time 秒}".with(
                        "time" to Duration.ofMillis(cooldownUntil - now)
                    )
                )
                return false
            }
            lastUsed.remove(key)
        }
        return true
    }

    context(CommandContext) fun setCoolDown() {
        val key = player!!.uuid()
        lastUsed[key] = System.currentTimeMillis()
    }

    fun reset() = lastUsed.clear()

    fun reset(player: Player) {
        lastUsed.remove(player.uuid())
    }
}

enum class SkillMenuCategory(
    val code: String,
    val displayName: String,
    val description: String,
) {
    Common("common", "通用技能", "标准/初级技能，任何玩家都可查看"),
    Level2("level2", "2级技能", "资历2级及以上玩家可查看和使用"),
    Level3("level3", "3级技能", "资历3级及以上玩家可查看和使用；普通 noskill 不禁用，但投票纯净模式会禁用"),
    Shop("shop", "特殊/商店技能", "商店或特殊来源技能；分类对所有玩家可见，具体技能按条件显示"),
    Admin("admin", "管理员技能", "资历4级/信任4级/已登录admin 玩家可查看和使用，不受 noskill 与 PVP 限制"),
}

data class SkillMenuEntry(
    val code: String,
    val displayName: String,
    val category: SkillMenuCategory,
    val description: String,
    val command: String = "/skill $code",
    val visible: suspend (Player) -> Boolean = { true },
)

data class SkillMainMenuEntry(
    val code: String,
    val optionText: String,
    val visible: suspend (Player) -> Boolean = { true },
    val action: suspend (Player) -> Unit,
)

object SkillMainMenuRegistry {
    private val entries = linkedMapOf<String, SkillMainMenuEntry>()

    fun register(entry: SkillMainMenuEntry): Boolean {
        val fixed = entry.code.trim().lowercase()
        if (fixed.isEmpty()) return false
        entries[fixed] = entry.copy(code = fixed)
        return true
    }

    fun unregister(code: String): Boolean = entries.remove(code.trim().lowercase()) != null

    suspend fun visibleEntries(player: Player): List<SkillMainMenuEntry> =
        entries.values.filter { it.visible(player) }
}

object SkillMenuRegistry {
    private val entries = linkedMapOf<String, SkillMenuEntry>()

    fun register(entry: SkillMenuEntry): Boolean {
        val fixed = entry.code.trim().lowercase()
        if (fixed.isEmpty()) return false
        entries[fixed] = entry.copy(code = fixed)
        return true
    }

    fun unregister(code: String): Boolean = entries.remove(code.trim().lowercase()) != null

    fun all(): List<SkillMenuEntry> = entries.values.toList()

    suspend fun visibleEntries(player: Player, category: SkillMenuCategory): List<SkillMenuEntry> =
        entries.values.filter { it.category == category && it.visible(player) }
}

@Suppress("unused")
object SkillCommands : Commands() {
    val allCooldown = mutableListOf<SkillCooldown>()
    @Suppress("MemberVisibilityCanBePrivate")
    class SkillScope(val player: Player) {
        fun broadcastSkill(skill: String) = broadcast(
            "[yellow][技能][green]{player.name}[white]使用了[green]{skill}[white]技能."
                .with("player" to player, "skill" to skill), quite = true
        )

        //util

        fun syncTile(vararg builds: Building) {
            val outStream = ByteArrayOutputStream()
            val write = DataOutputStream(outStream)
            builds.forEach {
                write.writeInt(it.pos())
                write.writeShort(it.block.id.toInt())
                it.writeAll(Writes.get(write))
            }
            write.flush()
            Call.blockSnapshot(builds.size.toShort(), outStream.toByteArray())
        }
    }
}

/** 本局技能消费控制。
 *
 * 用于管理员技能“全场技能消费由xxx买单”：开启后，本局所有技能的 MDC 使用消耗直接免除。
 * 注意：这里只控制“使用消耗”，不影响商店购买消耗。
 */
object SkillCostManager {
    private var freeCostBy: String? = null

    fun freeCostSponsor(): String? = freeCostBy
    fun isFreeCostEnabled(): Boolean = freeCostBy != null

    fun enableFreeCost(sponsorName: String) {
        freeCostBy = sponsorName
    }

    fun disableFreeCost() {
        freeCostBy = null
    }
}

@CommandInfo.CommandBuilder
fun CommandInfo.skillBody(body: suspend context(CommandContext) SkillCommands.SkillScope.() -> Unit) {
    body {
        body.invoke(context, SkillCommands.SkillScope(player!!))
        attrs.filterIsInstance<SkillCooldown>().singleOrNull()?.setCoolDown()
    }
}
