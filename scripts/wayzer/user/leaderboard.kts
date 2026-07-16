@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/menu", "排行榜菜单")

package wayzer.user

import coreMindustry.MenuBuilder
import wayzer.lib.ForumPostCreatedEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.RecognitionChangedEvent
import wayzer.lib.ReputationChangedEvent
import wayzer.lib.TrustPointChangedEvent

name = "MDT DO排行榜"

private val RANK_LIMIT = 10
private val RANK_CACHE_TTL_MILLIS = 30_000L

private data class RankCategory(
    val code: String,
    val title: String,
    val description: String,
    val load: () -> List<MdtStorage.LeaderboardEntry>,
)

private data class RankCacheEntry(
    val entries: List<MdtStorage.LeaderboardEntry>,
    val loadedAt: Long,
)

private val rankCache = mutableMapOf<String, RankCacheEntry>()

private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private val rankCategories = listOf(
    RankCategory("points_current", "MDC排行", "当前持有 MDC 数量排行。") { MdtStorage.topCurrentTrustPoints(RANK_LIMIT) },
    RankCategory("points_total", "累计MDC排行", "历史累计获得 MDC 数量排行。") { MdtStorage.topTotalTrustPoints(RANK_LIMIT) },
    RankCategory("forum_posts", "发帖数排行", "历史累计发帖数排行。") { MdtStorage.topForumPosts(RANK_LIMIT) },
    RankCategory("received_likes", "被赞排行", "玩家收到的赞总数排行。") { MdtStorage.topReceivedLikes(RANK_LIMIT) },
    RankCategory("received_dislikes", "被踩排行", "玩家收到的踩总数排行。") { MdtStorage.topReceivedDislikes(RANK_LIMIT) },
    RankCategory("given_likes", "送出赞排行", "玩家给别人点赞总数排行。") { MdtStorage.topGivenLikes(RANK_LIMIT) },
    RankCategory("given_dislikes", "送出踩排行", "玩家给别人点踩总数排行。") { MdtStorage.topGivenDislikes(RANK_LIMIT) },
    RankCategory("received_recognitions", "被认可排行", "玩家收到的认可总数排行。") { MdtStorage.topReceivedRecognitions(RANK_LIMIT) },
    RankCategory("given_recognitions", "认可他人排行", "玩家认可他人的总数排行。") { MdtStorage.topGivenRecognitions(RANK_LIMIT) },
)

private fun clearRankCache(vararg codes: String) {
    if (codes.isEmpty()) rankCache.clear()
    else codes.forEach { rankCache.remove(it) }
}

private fun loadRankEntriesCached(category: RankCategory, force: Boolean = false): List<MdtStorage.LeaderboardEntry> {
    val now = System.currentTimeMillis()
    val cached = rankCache[category.code]
    if (!force && cached != null && now - cached.loadedAt <= RANK_CACHE_TTL_MILLIS) {
        return cached.entries
    }
    val entries = category.load().filter { it.value > 0 }
    rankCache[category.code] = RankCacheEntry(entries, now)
    return entries
}

private fun onlineName(uid: String): String? =
    Groups.player.find { PlayerData[it].id == uid }?.plainName()

private val leadingStoredTitleRegex = Regex("""^\s*(?:\[[^\]\n]{1,24}\])+\s*""")

private fun cleanRankName(name: String): String {
    var result = name.trim()
    repeat(3) {
        val next = result.replace(leadingStoredTitleRegex, "").trim()
        if (next == result) return@repeat
        result = next
    }
    return result.ifBlank { name.trim() }
}

private fun displayName(entry: MdtStorage.LeaderboardEntry): String =
    onlineName(entry.uid)?.takeIf { it.isNotBlank() }?.let { cleanRankName(it) }
        ?: entry.name?.takeIf { it.isNotBlank() }?.let { cleanRankName(it) }
        ?: entry.uid.take(18)

private fun formatEntries(entries: List<MdtStorage.LeaderboardEntry>): String {
    if (entries.isEmpty()) return "[gray]暂无排行数据"
    return entries.mapIndexed { index, entry ->
        val medal = when (index) {
            0 -> "[gold]#1"
            1 -> "[lightgray]#2"
            2 -> "[orange]#3"
            else -> "[gray]#${index + 1}"
        }
        "$medal [white]${displayName(entry)}[] [gray]- [cyan]${entry.value}"
    }.joinToString("\n")
}

private suspend fun openRankCategory(player: Player, category: RankCategory, forceRefresh: Boolean = false) {
    val entries = db { loadRankEntriesCached(category, forceRefresh) }
    MenuBuilder<Unit>("排行榜：${category.title}") {
        msg = """
            |[cyan]${category.description}
            |[gray]最多显示前 $RANK_LIMIT 名；离线玩家优先显示最近记录名，取不到则显示UID。
            |
            |${formatEntries(entries)}
        """.trimMargin()
        option("刷新") { openRankCategory(player, category, forceRefresh = true) }
        option("返回") { openLeaderboard(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openLeaderboard(player: Player) {
    MenuBuilder<Unit>("MDT排行榜") {
        msg = "[cyan]请选择要查看的排行榜。\n[gray]排行榜来自已落盘的MDC、帖子、赞踩、认可统计。"
        rankCategories.forEachIndexed { index, category ->
            option("${index + 1}. ${category.title}\n[gray]${category.description}") {
                openRankCategory(player, category)
            }
            newRow()
        }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

listenTo<TrustPointChangedEvent> {
    clearRankCache("points_current", "points_total")
}

listenTo<ReputationChangedEvent> {
    clearRankCache("received_likes", "received_dislikes", "given_likes", "given_dislikes")
}

listenTo<RecognitionChangedEvent> {
    clearRankCache("received_recognitions", "given_recognitions")
}

listenTo<ForumPostCreatedEvent> {
    clearRankCache("forum_posts")
}

command("rank", "打开MDT排行榜") {
    aliases = listOf("leaderboard", "排行榜", "排行")
    attr(ClientOnly)
    body {
        openLeaderboard(player!!)
    }
}
