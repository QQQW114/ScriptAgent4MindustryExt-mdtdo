@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/maps", "地图/游戏结算事件")
@file:Depends("wayzer/map/betterTeam", "观察者队伍")

package wayzer.user

import arc.util.Log
import mindustry.game.Team
import wayzer.MapChangeEvent
import wayzer.map.BetterTeam
import wayzer.lib.PlayerData
import wayzer.lib.ServerTestMode
import java.time.Duration
import kotlin.math.ceil

private val IDLE_WEIGHT = 0.8
private val MDC_PER_HOUR_SCORE = 12.0
private val MAX_MDC_PER_GAME = 48.0
private val MIN_PLAYER_SECONDS = 60
private val MIN_ACTIVE_SECONDS = 60
private val MIN_REWARD_GAME_TIME: Duration = Duration.ofMinutes(15)

private val trustPoint = contextScript<TrustPoint>()
private val betterTeam = contextScript<BetterTeam>()

data class ContributionRewardData(
    val uuid: String,
    var uid: String = uuid,
    var name: String = "",
    var playedTime: Int = 0,
    var idleTime: Int = 0,
    @Transient var pvpTeam: Team = Team.sharded
) {
    var win: Boolean = false
    var score: Double = 0.0
    var reward: Int = 0

    val activeTime: Int
        get() = (playedTime - idleTime).coerceAtLeast(0)

    fun calculate(winner: Team) {
        win = state.rules.pvp && pvpTeam == winner
        val activeRate = if (playedTime > 0) activeTime.toDouble() / playedTime else 0.0
        val winBonus = if (win) 600.0 * activeRate else 0.0
        score = (playedTime - IDLE_WEIGHT * idleTime + winBonus).coerceAtLeast(0.0)
        reward = ceil((score * MDC_PER_HOUR_SCORE / 3600.0).coerceAtMost(MAX_MDC_PER_GAME)).toInt()
            .coerceAtLeast(0)
    }
}

private data class ContributionRewardSummary(
    val uid: String,
    val name: String,
    val playedTime: Int,
    val idleTime: Int,
    val activeTime: Int,
    val score: Double,
    val reward: Int,
    val win: Boolean
)

private val contributionData = mutableMapOf<String, ContributionRewardData>()
private var settledThisRound = false
private val doubleMdcRewardTag = "@doubleMdcReward"

private val Player.contribution: ContributionRewardData
    get() = contributionData.getOrPut(uuid()) { ContributionRewardData(uuid()) }

private fun updatePlayerSnapshot(player: Player): ContributionRewardData {
    val data = PlayerData[player]
    return player.contribution.apply {
        uid = data.id
        name = player.info.lastName.ifBlank { player.name }
        val currentTeam = player.team()
        if (currentTeam != betterTeam.spectateTeam) {
            pvpTeam = currentTeam
        }
    }
}

private fun formatSeconds(seconds: Int): String {
    val minute = seconds / 60
    val second = seconds % 60
    return when {
        minute > 0 && second > 0 -> "${minute}分${second}秒"
        minute > 0 -> "${minute}分"
        else -> "${second}秒"
    }
}

private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.find { PlayerData[it].id == uid }

private fun addRewardPointsBatch(rewards: Map<String, Int>) {
    val fixed = rewards.filterValues { it > 0 }
    if (fixed.isEmpty()) return
    with(trustPoint) { addTrustPointsBatch(fixed, "GameContribution") }
}

private fun resetContributionData() {
    contributionData.clear()
    settledThisRound = false
}

private fun buildRewardSummaries(winner: Team): List<ContributionRewardSummary> {
    val tagMultiplier = if (state.rules.tags.getBool(doubleMdcRewardTag)) 2 else 1
    val testMode = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() }
    val testMultiplier = testMode?.gameContributionRewardMultiplier() ?: 1
    val rewardMultiplier = tagMultiplier * testMultiplier
    return contributionData.values
        .groupBy { it.uid }
        .filter { (uid, _) -> testMode?.ownsUid(uid) ?: true }
        .map { (uid, list) ->
            val bestName = list.maxBy { it.playedTime }.name
            val played = list.sumOf { it.playedTime }
            val idle = list.sumOf { it.idleTime }
            val active = (played - idle).coerceAtLeast(0)
            val win = state.rules.pvp && list.any { it.pvpTeam == winner }
            val activeRate = if (played > 0) active.toDouble() / played else 0.0
            val winBonus = if (win) 600.0 * activeRate else 0.0
            val score = (played - IDLE_WEIGHT * idle + winBonus).coerceAtLeast(0.0)
            val baseReward = ceil((score * MDC_PER_HOUR_SCORE / 3600.0).coerceAtMost(MAX_MDC_PER_GAME)).toInt()
                .coerceAtLeast(0)
            val reward = baseReward * rewardMultiplier
            ContributionRewardSummary(
                uid = uid,
                name = bestName,
                playedTime = played,
                idleTime = idle,
                activeTime = active,
                score = score,
                reward = reward,
                win = win
            )
        }
        .filter {
            it.playedTime > MIN_PLAYER_SECONDS &&
                    it.activeTime >= MIN_ACTIVE_SECONDS &&
                    it.reward > 0
        }
        .sortedByDescending { it.score }
}

private fun settleContributionRewards(winner: Team, reason: String) {
    if (settledThisRound) return
    settledThisRound = true

    val gameTime by PlaceHold.reference<Duration>("state.gameTime")
    if (state.rules.infiniteResources || state.rules.editor || gameTime < MIN_REWARD_GAME_TIME) {
        resetContributionData()
        return
    }

    val tagDoubled = state.rules.tags.getBool(doubleMdcRewardTag)
    val testMultiplier = ServerTestMode.getOrNull()?.gameContributionRewardMultiplier() ?: 1
    val summaries = buildRewardSummaries(winner)
    contributionData.clear()

    if (summaries.isEmpty()) return

    val rewards = summaries.groupBy { it.uid }.mapValues { entry -> entry.value.sumOf { it.reward } }
    launch(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        runCatching { addRewardPointsBatch(rewards) }
            .onFailure { Log.err("Game contribution MDC rewards failed by $reason", it) }
            .onSuccess {
                val cost = System.currentTimeMillis() - startedAt
                if (cost >= 250L) Log.warn("Game contribution MDC batch write cost ${cost}ms by $reason, players=${rewards.size}")
                launch(Dispatchers.game) {
                    summaries.forEach { summary ->
                        onlinePlayerByUid(summary.uid)?.sendMessage(
                            "[green]本局贡献结算：MDC +${summary.reward} " +
                                    "[gray](活跃 ${formatSeconds(summary.activeTime)} / 在线 ${formatSeconds(summary.playedTime)}${if (tagDoubled) "，本局翻倍" else ""}${if (testMultiplier > 1) "，测试模式×$testMultiplier" else ""})"
                        )
                    }

                    val topList = summaries.take(6).mapIndexed { index, summary ->
                        val prefix = if (summary.win) "[green][胜][]" else ""
                        "[white]${index + 1}. $prefix${summary.name}[gray] " +
                                "+${summary.reward} MDC，活跃${formatSeconds(summary.activeTime)}/在线${formatSeconds(summary.playedTime)}"
                    }
                    broadcast(
                        """
                        |[gold]本局贡献MDC结算：[white]${summaries.size}名玩家获得奖励
                        |[gray]规则：活跃约每10分钟 +2 MDC，PVP胜利最多约额外 +2，单局基础最多 ${MAX_MDC_PER_GAME.toInt()} MDC。${if (tagDoubled) "[gold]本局管理员已开启结算翻倍。" else ""}${if (testMultiplier > 1) "[red]测试模式结算×$testMultiplier。" else ""}
                        |${topList.joinToString("\n")}
                        """.trimMargin().with()
                    )
                    Log.info("Game contribution MDC rewards settled by $reason: ${summaries.joinToString { "${it.uid}+${it.reward}" }}")
                }
            }
    }
}

listen<EventType.PlayerJoin> {
    updatePlayerSnapshot(it.player)
}

listen<EventType.PlayEvent> {
    launch(Dispatchers.gamePost) {
        Groups.player.forEach { player -> updatePlayerSnapshot(player) }
    }
}

onEnable {
    loop(Dispatchers.game) {
        delay(1000)
        Groups.player.forEach { player ->
            val data = updatePlayerSnapshot(player)
            data.playedTime++
            if (player.dead() || player.team() == betterTeam.spectateTeam) {
                data.idleTime++
            }
        }
    }
}

listen<EventType.GameOverEvent> {
    settleContributionRewards(it.winner, "GameOver")
}

listenTo<MapChangeEvent>(Event.Priority.Before) {
    if (contributionData.any { it.value.playedTime > MIN_PLAYER_SECONDS }) {
        settleContributionRewards(Team.derelict, "MapChange")
    } else {
        resetContributionData()
    }
}

listen<EventType.ResetEvent> {
    resetContributionData()
    state.rules.tags.remove(doubleMdcRewardTag)
}
