@file:Depends("coreMindustry/menu", "投票教程多步弹窗")
@file:Depends("wayzer/vote", "投票服务与 deny predicate")
@file:Depends("wayzer/mdtDatabase", "投票教程完成记录持久化")

package wayzer.cmds

import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.MenuBuilder
import coreMindustry.lib.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mindustry.gen.Player
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.util.concurrent.ConcurrentHashMap

/**
 * 新手投票教程。
 *
 * 规则（2026-10-17 用户需求）：
 * - 游客（当前会话未登录账号）进服后延迟弹一次多步教程；已完成过（跨重启持久化）则不再弹；
 * - **未完成教程的游客没有投票权**：通过 [VoteEvent.registerDenyVotePredicate] 排除，既收不到投票弹窗，
 *   投出的票也不计入，自己发起投票同样会被拦截；完成教程后立即恢复，无需重进；
 * - 任何玩家都可用 [VOTE_TUTORIAL_COMMAND]（别名 投票教程）重新打开教程复习；
 * - 教程中覆盖 `/vote ob`、`/vote quitob`、投票输入 `1/0/.`、`/votednd` 以及理性投票提醒。
 *
 * 性能约束（主线程阻塞自检）：
 * - [denyVoteForTutorial] 会随 `VoteEvent.canVoteNow` 在每次投票判定、每个玩家上被调用，
 *   因此**只读内存**（PlayerData 会话缓存 + ConcurrentHashMap 完成集合），O(1)、无日志、无字符串构造；
 * - 完成集合只在脚本启用时用 [Dispatchers.IO] 读一次数据库，加载完成前一律按“已完成”放行；
 * - 玩家完成教程时只改内存集合并向 conflated Channel 发信号，数据库写入在 [Dispatchers.IO] 协程里做；
 * - 进服弹窗只做内存判断与延迟（[joinDelayMillis]），join 回调中不访问数据库。
 */
private val VOTE_TUTORIAL_COMMAND = "votetutorial"
private val COMPLETED_SETTING_KEY = "vote.tutorial.completed"
private val DENY_PREDICATE_ID = "wayzer.cmds.voteTutorial"

/** 完成过教程的全部主体标识（游戏 uuid 与 account 主体 uid），只在加载/完成时写入。 */
private val completedIdentities = ConcurrentHashMap.newKeySet<String>()

/** 完成集合是否已成功从数据库加载；未加载成功时既不拦截也不弹窗（宁可放行也不误拦）。 */
@Volatile
private var completedLoaded = false

/** 本会话已弹过教程的玩家 uuid，避免同一会话重复弹窗；玩家离开时清理。 */
private val tutorialShownThisSession = ConcurrentHashMap.newKeySet<String>()

/** 完成事件的持久化信号：游戏线程只 trySend，实际 DB 写在 IO 协程里串行执行。 */
private val persistSignal = Channel<Unit>(Channel.CONFLATED)

private val joinDelayMillis by config.key(1500L, "游客进服后弹出投票教程的延迟(毫秒)")

private val PAGE_INTRO = 0
private val PAGE_EXIT = -1
private val PAGE_COMPLETE = -2

private data class TutorialPage(val title: String, val text: String)

private val tutorialIntroText = """
    |[accent]欢迎来到本服务器！
    |
    |[white]投票是服务器公共事务的决定方式：换图、踢出捣乱玩家、暂停游戏等都会用到投票。
    |
    |[yellow]如果不完成也可以玩，但你将没有投出票的权力。
    |
    |[gray]完成本教程大约需要 1 分钟，完成后即可参与投票。
    |[gray]之后随时可以输入 [gold]/votetutorial[] 重新查看本教程。
""".trimMargin()

private val tutorialCongratsText = """
    |[green]恭喜，你已经完成了基本投票教程，现在你可以参与投票了。
    |
    |[gray]如果以后再忘记规则，随时输入 [gold]/votetutorial[] 可以重新打开本教程。
    |[gray]另外，遇到投票时输入 [gold].[] 表示中立，中立票不计入赞成/反对，也是安全的表态方式。
""".trimMargin()

private val tutorialPages = listOf(
    TutorialPage(
        "[cyan]/vote ob — 投票强制观战",
        """
            |[white]当有人恶意破坏、捣乱时，可以用投票强制他进入观察者模式：
            |[green]/vote ob <玩家名或ID> <理由>
            |
            |[gray]· 这是一个投票，需要一定比例的玩家同意才会通过；
            |[gray]· 通过后目标玩家会被强制观战；
            |[gray]· 请只在理由充分时发起，不要滥用。
        """.trimMargin()
    ),
    TutorialPage(
        "[cyan]/vote quitob — 解除自己的观战限制",
        """
            |[white]如果你被强制观战（被 /vote ob 或被管理员处理），可以发起投票解除对自己的限制：
            |[green]/vote quitob
            |
            |[gray]· 这个投票只作用于你自己，是自救入口；
            |[gray]· 即使你被禁言，也可以使用 /vote quitob。
            |[gray]· 如果被禁言，可用 [green]/vote quitMute[] 发起解除禁言投票（仅临时禁言；永久禁言需联系管理员）。
        """.trimMargin()
    ),
    TutorialPage(
        "[red]检举 — 对方滥用权力时怎么办",
        """
            |[white]如果你被其他玩家用权限直接限制（禁言/强制观战/禁建），或者被投票限制成功，
            |[white]而你认为对方是在滥用权力，可以发起[red]检举投票[]。
            |
            |[green]入口：收到惩罚提示时在弹窗点「发起检举投票」，或输入 [gold]/punishreport[]（别名 /检举）。
            |
            |[yellow]检举说明：[white]投票发起者认为对方滥用权力，如果检举成功会禁止其一天使用权力；
            |[white]投票时会显示「此人已被成功检举了 xxx 次」（xxx = 对方累计被成功检举的次数）。
            |
            |[gray]· 由投票限制（/vote ob、/vote mute）引发的检举，需要先选择理由：
            |[gray]  对方恶意投票 / 对方投错了 / 对方判断错了（也可自定义输入）；
            |[gray]· 检举成功：解除对自己的该次惩罚，并禁止对方 1 天内直接限制他人；
            |[gray]· 若该次惩罚来自投票限制：还会额外禁止对方 1 天内发起限制类投票；
            |[gray]· 对方累计被成功检举 ≥3 次：永久禁止其直接限制他人；
            |[gray]· 对方累计被成功检举 ≥6 次：永久禁止其发起限制类投票；
            |[gray]· 没有充分理由请不要滥用检举；检举收拢后，只有已绑定（已登录账号）玩家可发起与参与投票，
            |[gray]  且需要已绑定玩家中较高比例（默认 75%）赞成才能通过。
        """.trimMargin()
    ),
    TutorialPage(
        "[cyan]投票时输入什么",
        """
            |[white]有人发起投票时，在聊天栏输入以下内容即可表态：
            |[green]1[] / [green]y[] / [green]赞成[]  →  同意
            |[red]0[] / [red]n[] / [red]反对[]  →  拒绝
            |[gray].[] / [gray]中立[]  →  中立
            |
            |[yellow]中立票不计入赞成/反对票数，不会影响投票结果。
        """.trimMargin()
    ),
    TutorialPage(
        "[cyan]/votednd — 投票免打扰",
        """
            |[white]如果你暂时不想参与任何投票，可以开启投票免打扰：
            |[green]/votednd
            |
            |[gray]开启后，遇到投票会自动投出中立票，不再弹出投票窗口打扰你。
        """.trimMargin()
    ),
    TutorialPage(
        "[yellow]请理性投票",
        """
            |[yellow]请不要跟风投票，尤其是观战他人的投票；
            |[yellow]如果理由不充分或者有疑问，请选择不同意。
            |
            |[gray]投票会影响其他玩家的游戏体验，请对自己的每一票负责。
        """.trimMargin()
    ),
)

/** 游客判定口径与投票服务一致：当前会话未登录账号（`PlayerData.authed` 为 false）。 */
private fun isGuestPlayer(player: Player): Boolean = !PlayerData[player].authed

/** 该玩家是否已完成教程：按会话 uuid 与当前主体 uid 查内存集合，O(1)。 */
private fun hasCompletedTutorial(player: Player): Boolean {
    val data = PlayerData[player]
    return completedIdentities.contains(data.uuid) || completedIdentities.contains(data.id)
}

/**
 * 投票 deny predicate 的判定体。
 *
 * 性能红线：此函数在每次投票判定时会对每个玩家调用，必须全内存、O(1)、无分配、无日志；
 * 加载未完成时返回 false（按已完成放行）。player 离线等异常由 vote.lib 的 runCatching 兜底。
 */
private fun denyVoteForTutorial(player: Player): Boolean {
    if (!completedLoaded) return false
    val data = PlayerData[player]
    if (data.authed) return false
    return !(completedIdentities.contains(data.uuid) || completedIdentities.contains(data.id))
}

private suspend fun loadCompletedIdentities() {
    // 数据库可能在脚本启用瞬间尚未就绪：IO 线程上重试，成功后内存标记加载完成。
    repeat(20) { attempt ->
        val result = runCatching { MdtStorage.getSetting(COMPLETED_SETTING_KEY) }
        if (result.isSuccess) {
            result.getOrNull()?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { completedIdentities.add(it) }
            completedLoaded = true
            logger.info("投票教程完成记录已加载：${completedIdentities.size} 条")
            return
        }
        if (attempt == 0) {
            logger.warning("投票教程完成记录暂时无法读取（数据库未就绪，稍后自动重试）: ${result.exceptionOrNull()?.message}")
        }
        delay(3_000L)
    }
    logger.warning("投票教程完成记录加载失败：已按“游客全部放行”降级，本次运行不启用教程投票限制")
}

/** 串行落库：conflated 信号保证最终快照一定被写入，游戏线程不做任何 DB 操作。 */
private suspend fun persistCompletedLoop() {
    for (ignored in persistSignal) {
        if (!completedLoaded) continue
        val snapshot = completedIdentities.sorted().joinToString("\n")
        runCatching { MdtStorage.setSetting(COMPLETED_SETTING_KEY, snapshot) }
            .onSuccess { logger.info("投票教程完成记录已保存：${completedIdentities.size} 条") }
            .onFailure { logger.warning("保存投票教程完成记录失败: ${it.message}") }
    }
}

/** 完成教程：写内存集合 + 异步持久化 + 立即解除投票限制（deny predicate 实时读取内存集合）。 */
private fun markTutorialCompleted(player: Player) {
    val data = runCatching { PlayerData[player] }.getOrNull() ?: return
    var changed = completedIdentities.add(data.uuid)
    if (data.id != data.uuid) changed = completedIdentities.add(data.id) || changed
    // 已登录会话可能带多个历史主体 uid，全部登记，换主体/换设备后仍能识别为已完成。
    data.ids.forEach { if (completedIdentities.add(it)) changed = true }
    if (changed) persistSignal.trySend(Unit)
    player.sendMessage("[green]投票教程已完成，你现在可以参与投票了。".with())
    logger.info("玩家 ${player.plainName()} 完成了投票教程（游客=${!data.authed}）")
}

private suspend fun showTutorialPage(player: Player, page: Int): Int? {
    val lastContentPage = tutorialPages.size
    val congratsPage = lastContentPage + 1
    return MenuBuilder<Int>("[gold]新手投票教程") {
        when (page) {
            PAGE_INTRO -> {
                msg = tutorialIntroText
                option("[green]开始学习") { 1 }
                newRow()
                option("[gray]稍后再说") { PAGE_EXIT }
            }

            congratsPage -> {
                msg = tutorialCongratsText
                option("[gray]上一步") { lastContentPage }
                newRow()
                option("[green]完成") { PAGE_COMPLETE }
            }

            else -> {
                val index = (page - 1).coerceIn(0, tutorialPages.lastIndex)
                val content = tutorialPages[index]
                msg = "[cyan]第 ${index + 1}/${lastContentPage} 步：${content.title}\n\n${content.text}"
                option("[gray]上一步") { page - 1 }
                if (index < tutorialPages.lastIndex) {
                    option("[green]下一步") { page + 1 }
                } else {
                    option("[green]下一步") { congratsPage }
                }
                newRow()
                option("[gray]退出") { PAGE_EXIT }
            }
        }
    }.sendTo(player, 120_000)
}

/** 多步教程主循环：逐页弹窗，直到退出/完成/超时。 */
private suspend fun openTutorial(player: Player) {
    var page = PAGE_INTRO
    while (true) {
        val next = showTutorialPage(player, page) ?: return
        when (next) {
            PAGE_EXIT -> return
            PAGE_COMPLETE -> {
                markTutorialCompleted(player)
                return
            }

            else -> page = next
        }
    }
}

listen<EventType.PlayerJoin> { event ->
    val player = event.player
    // join 回调只做内存判断 + 延迟，不访问数据库。
    if (!completedLoaded) return@listen
    if (!runCatching { isGuestPlayer(player) && !hasCompletedTutorial(player) }.getOrDefault(false)) return@listen
    if (!tutorialShownThisSession.add(player.uuid())) return@listen
    launch(Dispatchers.game) {
        delay(joinDelayMillis.coerceAtLeast(0L))
        // 延迟期间可能已离线或已完成登录：再次用内存状态确认，避免多弹/误弹。
        if (player.con == null) return@launch
        if (!runCatching { isGuestPlayer(player) && !hasCompletedTutorial(player) }.getOrDefault(false)) return@launch
        logger.info("向游客 ${player.plainName()} 弹出投票教程")
        openTutorial(player)
    }
}

listen<EventType.PlayerLeave> { event ->
    runCatching { tutorialShownThisSession.remove(event.player.uuid()) }
}

command(VOTE_TUTORIAL_COMMAND, "打开新手投票教程（游客完成后才能参与投票）") {
    aliases = listOf("投票教程", "votehelp")
    body {
        val target = player
        if (target == null) {
            // 控制台无玩家：输出内存状态，便于运维确认持久化加载情况（不触发任何 DB 写入）。
            reply(
                ("[cyan]投票教程状态：完成记录 [white]${completedIdentities.size}[cyan] 条；" +
                        "加载状态=[white]${if (completedLoaded) "已完成" else "未完成（限制未启用）"}").with()
            )
            return@body
        }
        openTutorial(target)
    }
}

onEnable {
    completedLoaded = false
    VoteEvent.registerDenyVotePredicate(DENY_PREDICATE_ID) { player -> denyVoteForTutorial(player) }
    onDisable {
        VoteEvent.unregisterDenyVotePredicate(DENY_PREDICATE_ID)
        tutorialShownThisSession.clear()
    }
    launch(Dispatchers.IO) { loadCompletedIdentities() }
    launch(Dispatchers.IO) { persistCompletedLoop() }
}
