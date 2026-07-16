@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/menu", "帖子菜单")
@file:Depends("coreMindustry/utilTextInput", "帖子文本输入")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/ext/playerReputation", "玩家赞踩")

package wayzer.user

import coreMindustry.MenuBuilder
import wayzer.lib.ForumPostCreatedEvent
import wayzer.lib.MdtStorage
import wayzer.lib.MdtTextFormat
import wayzer.lib.PlayerData
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

name = "MDT帖子系统"

private val trustLevel = contextScript<TrustLevel>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()
private val reputation = contextScript<wayzer.ext.PlayerReputation>()

private data class ForumSectionSeed(
    val code: String,
    val name: String,
    val description: String,
    val sortOrder: Int,
)

private data class ForumLevelRequirement(
    val code: String,
    val levelCode: String,
)

private val defaultForumSections = listOf(
    ForumSectionSeed("all", "全部分区", "显示全部的帖子", 0),
    ForumSectionSeed("mdt", "MDT分区", "mindustry游戏有关的分区", 10),
    ForumSectionSeed("other", "其他分区", "其他未分类内容", 20),
    ForumSectionSeed("chat", "搞七捻三", "闲聊吹水的板块。不得讨论政治、色情等违规内容。", 30),
    ForumSectionSeed("feedback", "运营反馈", "此处可为服务器提供建议，指出bug等", 40),
    ForumSectionSeed("level1", "1级分区", "1级及以上玩家可查看/发帖", 50),
    ForumSectionSeed("level2", "2级分区", "2级及以上玩家可查看/发帖", 60),
    ForumSectionSeed("level3", "3级分区", "3级及以上玩家可查看/发帖", 70),
    ForumSectionSeed("level3plus", "3+级分区", "3+级及以上玩家可查看/发帖", 80),
)

private val forumLevelRequirements = listOf(
    ForumLevelRequirement("level1", "1"),
    ForumLevelRequirement("level2", "2"),
    ForumLevelRequirement("level3", "3"),
    ForumLevelRequirement("level3plus", "3+"),
).associateBy { it.code }

private val FORUM_SECTION_LIST_PAGE_SIZE = 6
private val FORUM_LIST_PAGE_SIZE = 6
private val FORUM_COMMENT_PAGE_SIZE = 5
private val FORUM_BODY_PAGE_CHARS = 850
private val FORUM_MAX_SECTION_CODE_LENGTH = 32
private val FORUM_MAX_SECTION_NAME_LENGTH = 32
private val FORUM_MAX_SECTION_DESCRIPTION_LENGTH = 160
private val FORUM_MAX_TITLE_LENGTH = 48
private val FORUM_MAX_BODY_LENGTH = 2000
private val FORUM_MAX_COMMENT_LENGTH = 500
private val FORUM_DAILY_POST_LIMIT = 3
private val FORUM_MAX_NORMAL_POSTS = 500
private val FORUM_CLEANUP_MIN_AGE_DAYS = 30L
private val FORUM_MENU_TIMEOUT_MILLIS = 30 * 60_000
private val FORUM_INPUT_TIMEOUT_MILLIS = 30 * 60_000
private val FORUM_CLEANUP_DATE_KEY = "forum.cleanup.lastDate"
private val FORUM_POST_HISTORY_KEY = "forum.postChangeHistory"
private val FORUM_POST_HISTORY_LIMIT = 10
private val FORUM_DELETE_META_PREFIX = "forum.deleted."
private val FORUM_DELETE_DAILY_LIMIT = 5
private val FORUM_DELETE_SHORT_LIMIT = 2
private val FORUM_DELETE_SHORT_WINDOW_MILLIS = 10 * 60_000L
private val FORUM_CACHE_TTL_MILLIS = 30_000L
private val FORUM_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private data class ForumPostOption(
    val post: MdtStorage.ForumPostRecord,
    val text: String,
)

private data class ForumTimedCache<T>(
    val value: T,
    val loadedAt: Long,
)

private data class ForumPostListCacheValue(
    val items: List<ForumPostOption>,
    val total: Int,
)

private data class ForumPostChangeRecord(
    val timeMillis: Long,
    val actorName: String,
    val action: String,
    val postId: Int,
    val title: String,
)

private val forumSectionsCache = mutableMapOf<Boolean, ForumTimedCache<List<MdtStorage.ForumSectionRecord>>>()
private var forumStatsCache: ForumTimedCache<MdtStorage.ForumStats>? = null
private val forumPostListCache = mutableMapOf<String, ForumTimedCache<ForumPostListCacheValue>>()
private val forumPostCache = mutableMapOf<Int, ForumTimedCache<MdtStorage.ForumPostRecord?>>()
private val forumCommentPageCache = mutableMapOf<String, ForumTimedCache<MdtStorage.ForumCommentPage>>()
private var lockedForumPostIdsCache: ForumTimedCache<Set<Int>>? = null
private var protectedForumPostIdsCache: ForumTimedCache<Set<Int>>? = null
private var forumPostHistoryCache: ForumTimedCache<String>? = null

private fun <T> forumCacheValue(entry: ForumTimedCache<T>?): T? {
    if (entry == null) return null
    return if (System.currentTimeMillis() - entry.loadedAt <= FORUM_CACHE_TTL_MILLIS) entry.value else null
}

private fun clearForumCache() {
    forumSectionsCache.clear()
    forumStatsCache = null
    forumPostListCache.clear()
    forumPostCache.clear()
    forumCommentPageCache.clear()
    lockedForumPostIdsCache = null
    protectedForumPostIdsCache = null
    forumPostHistoryCache = null
}

private fun playerUid(player: Player): String = PlayerData[player].id

private fun requiredLevelForSection(sectionCode: String): String? =
    forumLevelRequirements[sectionCode.lowercase()]?.levelCode

private fun canViewForumSection(player: Player, sectionCode: String): Boolean {
    val required = requiredLevelForSection(sectionCode) ?: return true
    return with(trustLevel) { hasTrustLevel(player, required) }
}

private fun deniedSectionMessage(sectionCode: String): String {
    val required = requiredLevelForSection(sectionCode) ?: return "[red]等级不足，无法查看该分区。"
    return "[red]等级不足，无法查看该分区：需要 [yellow]$required 级[red] 或更高等级。"
}

private fun hiddenForumSectionCodes(player: Player): Set<String> =
    forumLevelRequirements.keys.filterNot { canViewForumSection(player, it) }.toSet()

private fun canUseForum(player: Player): Boolean =
    with(trustLevel) { hasTrustLevel(player, "1") }

private fun canManageForum(player: Player): Boolean =
    with(trustLevel) { hasTrustLevel(player, "3+") }

private fun canAdminForum(player: Player): Boolean =
    with(trustLevel) { isTrustAdmin(player) }

private fun canEditForumPost(player: Player, post: MdtStorage.ForumPostRecord, protected: Boolean = false): Boolean =
    (!protected || canAdminForum(player)) && (canManageForum(player) || playerUid(player) == post.authorUid)

private fun safeSettingSuffix(text: String): String =
    java.lang.Integer.toUnsignedString(text.hashCode(), 36)

private fun forumDeleteLimitKey(player: Player, scope: String): String =
    "forum.delete.$scope.${safeSettingSuffix(playerUid(player))}"

private fun canDeleteForumNow(player: Player): String? {
    if (canAdminForum(player)) return null
    val dailyKey = forumDeleteLimitKey(player, "daily.${LocalDate.now()}")
    val bucket = System.currentTimeMillis() / FORUM_DELETE_SHORT_WINDOW_MILLIS
    val shortKey = forumDeleteLimitKey(player, "short.$bucket")
    val daily = MdtStorage.getSetting(dailyKey)?.toIntOrNull() ?: 0
    val short = MdtStorage.getSetting(shortKey)?.toIntOrNull() ?: 0
    return when {
        daily >= FORUM_DELETE_DAILY_LIMIT -> "[red]今日删除帖子次数已达上限：$FORUM_DELETE_DAILY_LIMIT。请联系4级/admin处理。"
        short >= FORUM_DELETE_SHORT_LIMIT -> "[red]短时间删除帖子过多，请稍后再试或联系4级/admin处理。"
        else -> null
    }
}

private fun recordForumDelete(player: Player) {
    if (canAdminForum(player)) return
    val dailyKey = forumDeleteLimitKey(player, "daily.${LocalDate.now()}")
    val bucket = System.currentTimeMillis() / FORUM_DELETE_SHORT_WINDOW_MILLIS
    val shortKey = forumDeleteLimitKey(player, "short.$bucket")
    MdtStorage.setSetting(dailyKey, ((MdtStorage.getSetting(dailyKey)?.toIntOrNull() ?: 0) + 1).toString())
    MdtStorage.setSetting(shortKey, ((MdtStorage.getSetting(shortKey)?.toIntOrNull() ?: 0) + 1).toString())
}

private fun compactLine(text: String, limit: Int = 24): String {
    return MdtTextFormat.plainPreview(text, limit)
}

private fun sanitizeForumHistoryField(text: String, limit: Int = 80): String =
    text.replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', ' ')
        .trim()
        .take(limit)
        .ifBlank { "未知" }

private fun readForumPostHistory(): List<ForumPostChangeRecord> =
    MdtStorage.getSetting(FORUM_POST_HISTORY_KEY)
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|', limit = 5)
            if (parts.size != 5) return@mapNotNull null
            val timeMillis = parts[0].toLongOrNull() ?: return@mapNotNull null
            val postId = parts[3].toIntOrNull() ?: return@mapNotNull null
            ForumPostChangeRecord(
                timeMillis = timeMillis,
                actorName = parts[1],
                action = parts[2],
                postId = postId,
                title = parts[4],
            )
        }
        .take(FORUM_POST_HISTORY_LIMIT)
        .toList()

private fun saveForumPostHistory(records: List<ForumPostChangeRecord>) {
    val text = records.take(FORUM_POST_HISTORY_LIMIT).joinToString("\n") {
        "${it.timeMillis}|${sanitizeForumHistoryField(it.actorName)}|${sanitizeForumHistoryField(it.action, 16)}|${it.postId}|${sanitizeForumHistoryField(it.title, 48)}"
    }
    MdtStorage.setSetting(FORUM_POST_HISTORY_KEY, text)
    forumPostHistoryCache = null
}

private fun appendForumPostHistory(actorName: String, action: String, postId: Int, title: String) {
    val record = ForumPostChangeRecord(
        timeMillis = System.currentTimeMillis(),
        actorName = sanitizeForumHistoryField(actorName, 32),
        action = sanitizeForumHistoryField(action, 16),
        postId = postId,
        title = sanitizeForumHistoryField(title, 48),
    )
    saveForumPostHistory(listOf(record) + readForumPostHistory())
}

private fun forumDeletedMetaKey(postId: Int, field: String): String = "$FORUM_DELETE_META_PREFIX$postId.$field"

private fun saveForumDeletedMeta(postId: Int, actorName: String, reason: String) {
    MdtStorage.setSetting(forumDeletedMetaKey(postId, "by"), sanitizeForumHistoryField(actorName, 48))
    MdtStorage.setSetting(forumDeletedMetaKey(postId, "reason"), sanitizeForumHistoryField(reason, 160))
    MdtStorage.setSetting(forumDeletedMetaKey(postId, "at"), System.currentTimeMillis().toString())
}

private fun clearForumDeletedMeta(postId: Int) {
    MdtStorage.setSetting(forumDeletedMetaKey(postId, "by"), null)
    MdtStorage.setSetting(forumDeletedMetaKey(postId, "reason"), null)
    MdtStorage.setSetting(forumDeletedMetaKey(postId, "at"), null)
}

private fun forumDeletedMetaText(postId: Int): String {
    val settings = MdtStorage.getSettings(
        listOf(
            forumDeletedMetaKey(postId, "by"),
            forumDeletedMetaKey(postId, "reason"),
            forumDeletedMetaKey(postId, "at"),
        )
    )
    val by = settings[forumDeletedMetaKey(postId, "by")] ?: "未知"
    val reason = settings[forumDeletedMetaKey(postId, "reason")] ?: "未填写"
    val at = settings[forumDeletedMetaKey(postId, "at")]?.toLongOrNull()
        ?.let { FORUM_TIME_FORMATTER.format(Instant.ofEpochMilli(it)) }
        ?: "未知时间"
    return "[gray]删除者:[white]$by[] / 时间:$at / 原因:$reason"
}

private fun forumPostHistoryTextCached(): String {
    forumCacheValue(forumPostHistoryCache)?.let { return it }
    val records = readForumPostHistory()
    val text = if (records.isEmpty()) {
        "[gray]暂无帖子修改/删除记录"
    } else {
        records.mapIndexed { index, record ->
            val time = FORUM_TIME_FORMATTER.format(Instant.ofEpochMilli(record.timeMillis))
            "[gray]${index + 1}.[$time] [white]${record.actorName}[] [cyan]${record.action}[] #[white]${record.postId}[] [gray]${record.title}"
        }.joinToString("\n")
    }
    forumPostHistoryCache = ForumTimedCache(text, System.currentTimeMillis())
    return text
}

private fun normalizeSectionCode(input: String): String? {
    val code = input.trim().lowercase()
    if (code.isEmpty() || code.length > FORUM_MAX_SECTION_CODE_LENGTH) return null
    if (!Regex("[a-z0-9_-]+").matches(code)) return null
    return code
}

private fun ensureDefaultForumSections(): Boolean {
    return runCatching {
        var changed = false
        defaultForumSections.forEach { section ->
            if (MdtStorage.getForumSection(section.code) == null) {
                MdtStorage.upsertForumSection(section.code, section.name, section.description, section.sortOrder, true)
                changed = true
            }
        }
        if (changed) clearForumCache()
        true
    }.getOrElse {
        logger.warning("[MDT帖子系统] 初始化默认分区失败，可能是数据库表尚未完成创建：${it.message}")
        false
    }
}

private fun forumSections(): List<MdtStorage.ForumSectionRecord> {
    if (!ensureDefaultForumSections()) {
        return defaultForumSections.map {
            MdtStorage.ForumSectionRecord(it.code, it.name, it.description, it.sortOrder, true)
        }
    }
    return MdtStorage.listForumSections()
}

private fun forumSectionsCached(includeDisabled: Boolean = false): List<MdtStorage.ForumSectionRecord> {
    forumCacheValue(forumSectionsCache[includeDisabled])?.let { return it }
    val sections = if (includeDisabled) {
        if (!ensureDefaultForumSections()) {
            defaultForumSections.map {
                MdtStorage.ForumSectionRecord(it.code, it.name, it.description, it.sortOrder, true)
            }
        } else {
            MdtStorage.listForumSections(includeDisabled = true)
        }
    } else {
        forumSections()
    }
    forumSectionsCache[includeDisabled] = ForumTimedCache(sections, System.currentTimeMillis())
    return sections
}

private fun forumSectionOrDefault(code: String): MdtStorage.ForumSectionRecord =
    MdtStorage.getForumSection(code) ?: MdtStorage.ForumSectionRecord(
        code = code,
        name = code,
        description = "",
        sortOrder = 999,
        enabled = true,
    )

private fun forumSectionOrDefaultCached(code: String): MdtStorage.ForumSectionRecord =
    forumSectionsCached(includeDisabled = true).firstOrNull { it.code == code } ?: forumSectionOrDefault(code)

private fun forumSectionCachedOrNull(code: String): MdtStorage.ForumSectionRecord? =
    forumSectionsCached(includeDisabled = true).firstOrNull { it.code == code }
        ?: runCatching { MdtStorage.getForumSection(code) }.getOrNull()

private fun forumStatsCached(): MdtStorage.ForumStats {
    forumCacheValue(forumStatsCache)?.let { return it }
    val stats = MdtStorage.getForumStats()
    forumStatsCache = ForumTimedCache(stats, System.currentTimeMillis())
    return stats
}

private fun lockedForumPostIdsCached(): Set<Int> {
    forumCacheValue(lockedForumPostIdsCache)?.let { return it }
    val ids = MdtStorage.lockedForumPostIds()
    lockedForumPostIdsCache = ForumTimedCache(ids, System.currentTimeMillis())
    return ids
}

private fun protectedForumPostIdsCached(): Set<Int> {
    forumCacheValue(protectedForumPostIdsCache)?.let { return it }
    val ids = MdtStorage.protectedForumPostIds()
    protectedForumPostIdsCache = ForumTimedCache(ids, System.currentTimeMillis())
    return ids
}

private fun splitBody(body: String): List<String> {
    val pages = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        val text = current.toString().trimEnd()
        if (text.isNotBlank()) pages += text
        current.clear()
    }
    body.lines().forEach { line ->
        if (line.length > FORUM_BODY_PAGE_CHARS) {
            if (current.isNotEmpty()) flush()
            line.chunked(FORUM_BODY_PAGE_CHARS).forEach { pages += it }
            return@forEach
        }
        if (current.length + line.length + 1 > FORUM_BODY_PAGE_CHARS && current.isNotEmpty()) flush()
        current.append(line).append('\n')
    }
    flush()
    return pages.ifEmpty { listOf("[gray]暂无内容") }
}

private suspend fun askForumText(
    player: Player,
    title: String,
    message: String,
    default: String = "",
    limit: Int,
): String? {
    val timeoutMillis = FORUM_INPUT_TIMEOUT_MILLIS
    return with(textInput) {
        textInput(player, title, message, default = default, lengthLimit = limit, timeoutMillis = timeoutMillis)
    }?.trim()?.takeIf { it.isNotEmpty() }
}

private fun cleanupForumPostsIfNeeded(force: Boolean = false) {
    val today = LocalDate.now().toString()
    runCatching {
        if (!force && MdtStorage.getSetting(FORUM_CLEANUP_DATE_KEY) == today) return
        val cutoff = Instant.now().minus(Duration.ofDays(FORUM_CLEANUP_MIN_AGE_DAYS))
        val removed = MdtStorage.pruneForumPosts(FORUM_MAX_NORMAL_POSTS, cutoff)
        MdtStorage.setSetting(FORUM_CLEANUP_DATE_KEY, today)
        if (removed > 0) {
            clearForumCache()
            logger.info("[MDT帖子系统] 自动清理旧帖 $removed 条")
        }
    }.onFailure {
        logger.warning("[MDT帖子系统] 自动清理旧帖失败，可能是数据库表尚未完成创建：${it.message}")
    }
}

private fun postOptionText(post: MdtStorage.ForumPostRecord, sectionName: String, locked: Boolean, protected: Boolean): String {
    val pin = if (post.pinned) "[gold][置顶][] " else ""
    val lockedText = if (locked) "[cyan][锁定][] " else ""
    val protectedText = if (protected) "[red][保护][] " else ""
    val sectionText = compactLine(sectionName, 8)
    val author = compactLine(post.authorName, 12)
    return "$pin$lockedText$protectedText[white]#${post.id} ${compactLine(post.title, 24)}\n" +
            "[gray]作者:$author / 评论${post.commentCount} / 分区:$sectionText"
}

private fun sectionOptionText(section: MdtStorage.ForumSectionRecord): String =
    "[gold]${section.name}\n[gray]${compactLine(section.description, 24)}"

private fun forumPostListPageCached(
    sectionCode: String,
    offset: Int,
    limit: Int,
    hiddenSections: Set<String>,
): ForumPostListCacheValue {
    val hiddenKey = hiddenSections.toList().sorted().joinToString(",")
    val key = "${sectionCode.lowercase()}:${offset.coerceAtLeast(0)}:${limit.coerceAtLeast(0)}:$hiddenKey"
    forumCacheValue(forumPostListCache[key])?.let { return it }
    val postPage = MdtStorage.listForumPostsPaged(
        sectionCode,
        offset,
        limit,
        hiddenSections,
    )
    val sectionMap = forumSectionsCached(includeDisabled = true).associateBy { it.code }
    val lockedIds = lockedForumPostIdsCached()
    val protectedIds = protectedForumPostIdsCached()
    val items = postPage.items.map { post ->
        val name = sectionMap[post.sectionCode]?.name ?: post.sectionCode
        ForumPostOption(post, postOptionText(post, name, post.id in lockedIds, post.id in protectedIds))
    }
    val value = ForumPostListCacheValue(items, postPage.total)
    forumPostListCache[key] = ForumTimedCache(value, System.currentTimeMillis())
    return value
}

private fun forumPostCached(postId: Int): MdtStorage.ForumPostRecord? {
    forumCacheValue(forumPostCache[postId])?.let { return it }
    val post = MdtStorage.getForumPost(postId)
    forumPostCache[postId] = ForumTimedCache(post, System.currentTimeMillis())
    return post
}

private fun forumCommentPageCached(postId: Int, offset: Int, limit: Int): MdtStorage.ForumCommentPage {
    val key = "$postId:${offset.coerceAtLeast(0)}:${limit.coerceAtLeast(0)}"
    forumCacheValue(forumCommentPageCache[key])?.let { return it }
    val page = MdtStorage.listForumCommentsPaged(postId, offset, limit)
    forumCommentPageCache[key] = ForumTimedCache(page, System.currentTimeMillis())
    return page
}

private suspend fun openForumIndex(player: Player, initialPage: Int = 1) {
    db { cleanupForumPostsIfNeeded() }
    val manager = canManageForum(player)
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val (sections, stats) = db { forumSectionsCached() to forumStatsCached() }
            val totalPage = maxOf(1, (sections.size + FORUM_SECTION_LIST_PAGE_SIZE - 1) / FORUM_SECTION_LIST_PAGE_SIZE)
            selectedPage = selectedPage.coerceIn(1, totalPage)
            val pageItems = sections.drop((selectedPage - 1) * FORUM_SECTION_LIST_PAGE_SIZE).take(FORUM_SECTION_LIST_PAGE_SIZE)

            title = "帖子分区"
            msg = "[cyan]当前帖子总数：[white]${stats.currentPosts}[]，[cyan]总发帖数：[white]${stats.totalPosts}"

            pageItems.forEach { section ->
                option(sectionOptionText(section)) {
                    if (canViewForumSection(player, section.code)) {
                        openForumPostList(player, section.code)
                    } else {
                        player.sendMessage(deniedSectionMessage(section.code))
                        openForumIndex(player, selectedPage)
                    }
                }
                newRow()
            }
            repeat(FORUM_SECTION_LIST_PAGE_SIZE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }

            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            if (manager) option("管理分区") { openForumSectionManageMenu(player) }
            if (canAdminForum(player)) option("回收站") { openForumTrashMenu(player) }
            option("最近变更") { openForumPostHistoryMenu(player) }
            option("格式帮助") { openForumFormatHelp(player) }
            option("关闭") {}
        }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumFormatHelp(player: Player, backPostId: Int? = null, sectionCode: String = "all") {
    MenuBuilder<Unit>("帖子格式帮助") {
        msg = MdtTextFormat.helpText
        if (backPostId != null) option("返回帖子") { openForumPost(player, backPostId, sectionCode) }
        option("返回分区") { openForumIndex(player) }
        option("关闭") {}
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumPostHistoryMenu(player: Player) {
    val historyText = db { forumPostHistoryTextCached() }
    MenuBuilder<Unit>("帖子最近变更") {
        msg = """
            |[cyan]最近${FORUM_POST_HISTORY_LIMIT}条玩家修改/删除帖子记录：
            |
            |$historyText
        """.trimMargin()
        option("返回分区") { openForumIndex(player) }
        option("关闭") {}
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumTrashMenu(player: Player, initialPage: Int = 1) {
    if (!canAdminForum(player)) {
        player.sendMessage("[red]权限不足：只有4级/admin可以查看帖子回收站。")
        return
    }
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val offset = (selectedPage - 1).coerceAtLeast(0) * FORUM_LIST_PAGE_SIZE
            val page = db { MdtStorage.listDeletedForumPostsPaged(offset, FORUM_LIST_PAGE_SIZE) }
            val totalPage = maxOf(1, (page.total + FORUM_LIST_PAGE_SIZE - 1) / FORUM_LIST_PAGE_SIZE)
            selectedPage = selectedPage.coerceIn(1, totalPage)
            title = "帖子回收站"
            msg = "[cyan]仅4级/admin可恢复或彻底删除。当前页 ${page.items.size} 条 / 共 ${page.total} 条"
            page.items.forEach { post ->
                val meta = db { forumDeletedMetaText(post.id) }
                option("[yellow]#${post.id} ${compactLine(post.title, 24)}\n$meta") {
                    openForumTrashPostMenu(player, post.id, selectedPage)
                }
                newRow()
            }
            repeat(FORUM_LIST_PAGE_SIZE - page.items.size) {
                option("") { refresh() }
                newRow()
            }
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            option("返回分区") { openForumIndex(player) }
            option("关闭") {}
        }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumTrashPostMenu(player: Player, postId: Int, trashPage: Int = 1) {
    if (!canAdminForum(player)) {
        player.sendMessage("[red]权限不足：只有4级/admin可以管理帖子回收站。")
        return
    }
    val post = db { MdtStorage.getForumPostAnyStatus(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在：#$postId")
        openForumTrashMenu(player, trashPage)
        return
    }
    if (post.status != "deleted") {
        player.sendMessage("[yellow]该帖子不在回收站：#$postId")
        openForumPost(player, post.id, post.sectionCode)
        return
    }
    val meta = db { forumDeletedMetaText(post.id) }
    MenuBuilder<Unit>("回收站帖子：#${post.id}") {
        msg = """
            |[cyan]标题：[white]${post.title}
            |[cyan]作者：[white]${post.authorName}
            |[cyan]分区：[white]${post.sectionCode}
            |$meta
            |
            |[gray]正文预览：${compactLine(post.body, 80)}
        """.trimMargin()
        option("恢复帖子") {
            if (db { MdtStorage.restoreForumPost(post.id) }) {
                db { clearForumDeletedMeta(post.id); appendForumPostHistory(player.plainName(), "恢复", post.id, post.title) }
                clearForumCache()
                player.sendMessage("[green]已恢复帖子 #[white]${post.id}")
                openForumPost(player, post.id, post.sectionCode)
            } else {
                player.sendMessage("[yellow]恢复失败")
                openForumTrashMenu(player, trashPage)
            }
        }
        option("彻底删除") {
            confirmPurgeForumPost(player, post.id, trashPage)
        }
        newRow()
        option("返回回收站") { openForumTrashMenu(player, trashPage) }
        option("关闭") {}
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun confirmPurgeForumPost(player: Player, postId: Int, trashPage: Int = 1) {
    if (!canAdminForum(player)) {
        player.sendMessage("[red]权限不足：只有4级/admin可以彻底删除帖子。")
        return
    }
    val post = db { MdtStorage.getForumPostAnyStatus(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在：#$postId")
        openForumTrashMenu(player, trashPage)
        return
    }
    MenuBuilder<Unit>("彻底删除帖子") {
        msg = "[red]确认彻底删除帖子：[white]#${post.id} ${post.title}[]？\n[gray]此操作会删除帖子和评论，无法从回收站恢复。"
        option("确认彻底删除") {
            if (db { MdtStorage.purgeForumPost(post.id) }) {
                db { clearForumDeletedMeta(post.id); appendForumPostHistory(player.plainName(), "彻底删除", post.id, post.title) }
                clearForumCache()
                player.sendMessage("[green]已彻底删除帖子")
            } else {
                player.sendMessage("[yellow]彻底删除失败")
            }
            openForumTrashMenu(player, trashPage)
        }
        option("取消") { openForumTrashPostMenu(player, post.id, trashPage) }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumPostList(player: Player, sectionCode: String = "all", initialPage: Int = 1) {
    db { cleanupForumPostsIfNeeded() }
    val section = db { forumSectionOrDefaultCached(sectionCode) }
    if (!canViewForumSection(player, section.code)) {
        player.sendMessage(deniedSectionMessage(section.code))
        openForumIndex(player)
        return
    }
    val canPost = canUseForum(player)
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val offset = (selectedPage - 1).coerceAtLeast(0) * FORUM_LIST_PAGE_SIZE
            val hiddenSections = hiddenForumSectionCodes(player)
            val page = db { forumPostListPageCached(section.code, offset, FORUM_LIST_PAGE_SIZE, hiddenSections) }
            val pageItems = page.items
            val totalPosts = page.total
            val totalPage = maxOf(1, (totalPosts + FORUM_LIST_PAGE_SIZE - 1) / FORUM_LIST_PAGE_SIZE)
            selectedPage = selectedPage.coerceIn(1, totalPage)

            title = "帖子：${section.name}"
            msg = if (totalPosts == 0) {
                "[yellow]当前分区暂无帖子。\n[gray]${section.description}"
            } else {
                "[cyan]${section.description}\n[gray]仅加载当前页 ${pageItems.size} 条 / 共 $totalPosts 条"
            }

            pageItems.forEach { item ->
                option(item.text) { openForumPost(player, item.post.id, section.code) }
                newRow()
            }
            repeat(FORUM_LIST_PAGE_SIZE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }

            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            if (canPost) option("发布帖子") { createForumPostFlow(player, section.code) }
            option("格式帮助") { openForumFormatHelp(player) }
            option("返回分区") { openForumIndex(player) }
            option("关闭") {}
        }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumPost(player: Player, postId: Int, sectionCode: String = "all", initialPage: Int = 1) {
    val post = db { forumPostCached(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在或已被删除：#$postId")
        openForumPostList(player, sectionCode)
        return
    }
    if (!canViewForumSection(player, post.sectionCode)) {
        player.sendMessage(deniedSectionMessage(post.sectionCode))
        openForumIndex(player)
        return
    }
    val postSection = db { forumSectionOrDefaultCached(post.sectionCode) }
    val pages = splitBody(MdtTextFormat.render(post.body))
    val protected = db { post.id in protectedForumPostIdsCached() }
    val canEdit = canEditForumPost(player, post, protected)
    val canManage = canManageForum(player)
    val canAdmin = canAdminForum(player)
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            selectedPage = selectedPage.coerceIn(1, pages.size)
            val time = FORUM_TIME_FORMATTER.format(post.createdAt)
            title = post.title
            msg = """
                |[gray]帖子 #[white]${post.id}[]  [gray]作者：[white]${post.authorName}[]  [gray]$time
                |[gray]分区：[white]${postSection.name}
                |${if (protected) "[red]保护锁：[white]已开启，仅4级/admin可编辑或删除" else "[gray]保护锁：未开启"}
                |[gray]页数：[white]$selectedPage/${pages.size}[]  [gray]通过此页可直接为作者点赞/点踩。
                |
                |${pages[selectedPage - 1]}
            """.trimMargin()

            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/${pages.size}") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(pages.size); refresh() }
            newRow()
            option("为作者点赞") {
                val ok = with(reputation) { likePlayer(player, post.authorUid, post.authorName) }
                if (ok && db { MdtStorage.incrementForumPostAuthorReaction(post.id, "like") }) clearForumCache()
                openForumPost(player, post.id, sectionCode, selectedPage)
            }
            option("为作者点踩") {
                val ok = with(reputation) { dislikePlayer(player, post.authorUid, post.authorName) }
                if (ok && db { MdtStorage.incrementForumPostAuthorReaction(post.id, "dislike") }) clearForumCache()
                openForumPost(player, post.id, sectionCode, selectedPage)
            }
            newRow()
            option("发布评论") { createForumCommentFlow(player, post.id, sectionCode) }
            option("查看评论（${post.commentCount}条）") { openForumComments(player, post.id, sectionCode) }
            option("分享到聊天") { shareForumPostToChat(player, post.id, sectionCode) }
            newRow()
            if (canAdmin) {
                option(if (protected) "解除保护锁（4）" else "设置保护锁（4）") {
                    if (db { MdtStorage.setForumPostProtected(post.id, !protected) }) clearForumCache()
                    player.sendMessage(if (protected) "[green]已解除帖子保护锁" else "[green]已设置保护锁，4级以下不可编辑/删除")
                    openForumPost(player, post.id, sectionCode, selectedPage)
                }
                newRow()
            }
            if (canEdit) option("修改帖子") { editForumPostFlow(player, post.id, sectionCode) }
            option("格式帮助") { openForumFormatHelp(player, post.id, sectionCode) }
            if (canManage) {
                val locked = db { post.id in lockedForumPostIdsCached() }
                option(if (post.pinned) "取消置顶" else "置顶此帖") {
                    if (db { MdtStorage.setForumPostPinned(post.id, !post.pinned) }) clearForumCache()
                    player.sendMessage(if (post.pinned) "[green]已取消置顶" else "[green]已置顶此帖")
                    openForumPost(player, post.id, sectionCode, selectedPage)
                }
                option(if (locked) "解除清理锁定" else "锁定防清理") {
                    if (db { MdtStorage.setForumPostLocked(post.id, !locked) }) clearForumCache()
                    player.sendMessage(if (locked) "[green]已解除帖子自动清理锁定" else "[green]已锁定此帖，自动清理不会删除它")
                    openForumPost(player, post.id, sectionCode, selectedPage)
                }
                newRow()
                if (!protected || canAdmin) option("删除此帖") { confirmDeleteForumPost(player, post.id, sectionCode) }
                newRow()
            } else if (canEdit) {
                newRow()
            }
            option("返回列表") { openForumPostList(player, sectionCode) }
            option("关闭") {}
        }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun shareForumPostToChat(player: Player, postId: Int, sectionCode: String = "all") {
    val post = db { forumPostCached(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在或已被删除：#$postId")
        openForumPostList(player, sectionCode)
        return
    }
    if (!canViewForumSection(player, post.sectionCode)) {
        player.sendMessage(deniedSectionMessage(post.sectionCode))
        openForumIndex(player)
        return
    }
    val section = db { forumSectionOrDefaultCached(post.sectionCode) }
    val message = "[cyan][帖子分享] [white]{player.name}[cyan] 分享了帖子 [gold]#{id} {title}[cyan]（{section}）。[gray]输入 [gold]/posts {id}[] 快速打开。".with(
            "player" to player,
            "id" to post.id,
            "title" to compactLine(post.title, 36),
            "section" to section.name,
        )
    val receivers = Groups.player.filter { canViewForumSection(it, post.sectionCode) }
    receivers.forEach { it.sendMessage(message) }
    player.sendMessage("[green]已分享到聊天栏：/posts ${post.id} [gray](${receivers.size}名可查看玩家可见)")
    openForumPost(player, post.id, sectionCode)
}

private suspend fun openForumComments(player: Player, postId: Int, sectionCode: String = "all", initialPage: Int = 1) {
    val post = db { forumPostCached(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在或已被删除：#$postId")
        openForumPostList(player, sectionCode)
        return
    }
    if (!canViewForumSection(player, post.sectionCode)) {
        player.sendMessage(deniedSectionMessage(post.sectionCode))
        openForumIndex(player)
        return
    }
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val offset = (selectedPage - 1).coerceAtLeast(0) * FORUM_COMMENT_PAGE_SIZE
            val commentPage = db { forumCommentPageCached(post.id, offset, FORUM_COMMENT_PAGE_SIZE) }
            val comments = commentPage.items
            val totalComments = commentPage.total
            val totalPage = maxOf(1, (totalComments + FORUM_COMMENT_PAGE_SIZE - 1) / FORUM_COMMENT_PAGE_SIZE)
            selectedPage = selectedPage.coerceIn(1, totalPage)
            title = "评论：${post.title}"
            msg = if (totalComments == 0) {
                "[yellow]当前暂无评论。"
            } else {
                buildString {
                    appendLine("[gray]帖子 #[white]${post.id}[] / 评论 $totalComments 条 / 当前页加载 ${comments.size} 条")
                    comments.forEach { comment ->
                        appendLine()
                        appendLine("[cyan]#${comment.id} [white]${comment.authorName}[] [gray]${FORUM_TIME_FORMATTER.format(comment.createdAt)}")
                        appendLine(MdtTextFormat.render(comment.body))
                    }
                }
            }
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            option("发布评论") { createForumCommentFlow(player, post.id, sectionCode) }
            option("返回帖子") { openForumPost(player, post.id, sectionCode) }
            option("关闭") {}
        }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun createForumPostFlow(player: Player, sectionCode: String = "all") {
    if (!canUseForum(player)) {
        player.sendMessage("[yellow]等级不足：1级及以上玩家才能发布帖子。")
        return
    }
    val section = db { forumSectionOrDefaultCached(sectionCode) }
    if (!canViewForumSection(player, section.code)) {
        player.sendMessage(deniedSectionMessage(section.code))
        openForumIndex(player)
        return
    }
    val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
    val uid = playerUid(player)
    val authorName = player.plainName()
    val manager = canManageForum(player)
    val todayPosts = db { MdtStorage.countForumPostsByAuthorSince(uid, todayStart) }
    if (todayPosts >= FORUM_DAILY_POST_LIMIT && !manager) {
        player.sendMessage("[yellow]你今天发布的帖子已达上限：$FORUM_DAILY_POST_LIMIT")
        openForumPostList(player, section.code)
        return
    }
    val title = askForumText(player, "发布帖子 - ${section.name}", "请输入帖子标题，最多${FORUM_MAX_TITLE_LENGTH}字。", limit = FORUM_MAX_TITLE_LENGTH)
    if (title == null) {
        player.sendMessage("[yellow]已取消发帖")
        openForumPostList(player, section.code)
        return
    }
    val body = askForumText(
        player,
        "发布帖子 - 正文",
        "请输入帖子正文，最多${FORUM_MAX_BODY_LENGTH}字。\n支持 #/## 标题、- 列表、> 引用、**重点**、--- 分割线；可用 \\n 或 | 快速换行。",
        limit = FORUM_MAX_BODY_LENGTH,
    )
    if (body == null) {
        player.sendMessage("[yellow]已取消发帖")
        openForumPostList(player, section.code)
        return
    }
    val normalizedBody = MdtTextFormat.normalizeMultilineInput(body).take(FORUM_MAX_BODY_LENGTH)
    val post = db { MdtStorage.createForumPost(section.code, uid, authorName, title.take(FORUM_MAX_TITLE_LENGTH), normalizedBody) }
    if (post == null) {
        player.sendMessage("[red]发帖失败：标题或正文为空")
        openForumPostList(player, section.code)
        return
    }
    clearForumCache()
    launch { ForumPostCreatedEvent(uid, post.id).emitAsync() }
    player.sendMessage("[green]已发布帖子：[white]#${post.id} ${post.title}")
    openForumPost(player, post.id, section.code)
}

private suspend fun createForumCommentFlow(player: Player, postId: Int, sectionCode: String = "all") {
    if (!canUseForum(player)) {
        player.sendMessage("[yellow]等级不足：1级及以上玩家才能评论。")
        return
    }
    val post = db { forumPostCached(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在或已被删除：#$postId")
        openForumPostList(player, sectionCode)
        return
    }
    if (!canViewForumSection(player, post.sectionCode)) {
        player.sendMessage(deniedSectionMessage(post.sectionCode))
        openForumIndex(player)
        return
    }
    val body = askForumText(
        player,
        "发布评论",
        "评论帖子：${post.title}\n最多${FORUM_MAX_COMMENT_LENGTH}字。支持 **重点**、`代码`、\\n 或 | 换行。",
        limit = FORUM_MAX_COMMENT_LENGTH,
    )
    if (body == null) {
        player.sendMessage("[yellow]已取消评论")
        openForumPost(player, post.id, sectionCode)
        return
    }
    val uid = playerUid(player)
    val authorName = player.plainName()
    val normalizedBody = MdtTextFormat.normalizeMultilineInput(body).take(FORUM_MAX_COMMENT_LENGTH)
    val comment = db { MdtStorage.createForumComment(post.id, uid, authorName, normalizedBody) }
    if (comment == null) {
        player.sendMessage("[red]评论失败：帖子不存在或内容为空")
        openForumPost(player, post.id, sectionCode)
        return
    }
    clearForumCache()
    player.sendMessage("[green]已发布评论")
    openForumComments(player, post.id, sectionCode)
}

private suspend fun editForumPostFlow(player: Player, postId: Int, sectionCode: String = "all") {
    val post = db { forumPostCached(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在或已被删除：#$postId")
        openForumPostList(player, sectionCode)
        return
    }
    if (!canViewForumSection(player, post.sectionCode)) {
        player.sendMessage(deniedSectionMessage(post.sectionCode))
        openForumIndex(player)
        return
    }
    val protected = db { post.id in protectedForumPostIdsCached() }
    if (!canEditForumPost(player, post, protected)) {
        player.sendMessage(
            if (protected) "[red]此帖子已被4级/admin设置保护锁，只有4级/admin可以修改。"
            else "[red]权限不足：只有帖子作者、3+级玩家与管理员可以修改帖子。"
        )
        openForumPost(player, post.id, sectionCode)
        return
    }
    val title = askForumText(player, "修改帖子标题", "当前标题：${post.title}", default = post.title, limit = FORUM_MAX_TITLE_LENGTH)
    if (title == null) {
        player.sendMessage("[yellow]已取消修改")
        openForumPost(player, post.id, sectionCode)
        return
    }
    val body = askForumText(
        player,
        "修改帖子正文",
        "请输入新的帖子正文。\n支持 #/## 标题、- 列表、> 引用、**重点**、--- 分割线；可用 \\n 或 | 快速换行。",
        default = post.body,
        limit = FORUM_MAX_BODY_LENGTH,
    )
    if (body == null) {
        player.sendMessage("[yellow]已取消修改")
        openForumPost(player, post.id, sectionCode)
        return
    }
    val normalizedBody = MdtTextFormat.normalizeMultilineInput(body).take(FORUM_MAX_BODY_LENGTH)
    if (db { MdtStorage.updateForumPost(post.id, title.take(FORUM_MAX_TITLE_LENGTH), normalizedBody) }) {
        val actorName = player.plainName()
        db { appendForumPostHistory(actorName, "修改", post.id, title.take(FORUM_MAX_TITLE_LENGTH)) }
        clearForumCache()
        player.sendMessage("[green]已修改帖子")
    } else {
        player.sendMessage("[red]修改失败")
    }
    openForumPost(player, post.id, sectionCode)
}

private suspend fun confirmDeleteForumPost(player: Player, postId: Int, sectionCode: String = "all") {
    if (!canManageForum(player)) {
        player.sendMessage("[red]权限不足：只有3+级玩家与管理员可以删除帖子。")
        return
    }
    val post = db { forumPostCached(postId) } ?: run {
        player.sendMessage("[yellow]帖子不存在或已被删除：#$postId")
        openForumPostList(player, sectionCode)
        return
    }
    val protected = db { post.id in protectedForumPostIdsCached() }
    if (protected && !canAdminForum(player)) {
        player.sendMessage("[red]此帖子已被4级/admin设置保护锁，只有4级/admin可以删除。")
        openForumPost(player, post.id, sectionCode)
        return
    }
    db { canDeleteForumNow(player) }?.let {
        player.sendMessage(it)
        openForumPost(player, post.id, sectionCode)
        return
    }
    MenuBuilder<Unit>("确认删除帖子") {
        msg = "[red]确认删除帖子：[white]#${post.id} ${post.title}[]？\n[gray]帖子会进入回收站，4级/admin可恢复或彻底删除。"
        option("确认删除到回收站") {
            val reason = askForumText(player, "删除帖子理由", "请输入删除理由，最多80字。", limit = 80)
                ?: run {
                    player.sendMessage("[yellow]已取消删除")
                    openForumPost(player, post.id, sectionCode)
                    return@option
                }
            val actorName = player.plainName()
            if (db { MdtStorage.softDeleteForumPost(post.id) }) {
                db {
                    recordForumDelete(player)
                    saveForumDeletedMeta(post.id, actorName, reason)
                    appendForumPostHistory(actorName, "删除", post.id, post.title)
                }
                clearForumCache()
                player.sendMessage("[green]已将帖子移入回收站")
            } else player.sendMessage("[yellow]删除失败或帖子不存在")
            openForumPostList(player, sectionCode)
        }
        option("取消") { openForumPost(player, post.id, sectionCode) }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumSectionManageMenu(player: Player, initialPage: Int = 1) {
    if (!canManageForum(player)) {
        player.sendMessage("[red]权限不足：只有3+级玩家与管理员可以管理帖子分区。")
        return
    }
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val sections = db { forumSectionsCached(includeDisabled = true) }
            val totalPage = maxOf(1, (sections.size + FORUM_SECTION_LIST_PAGE_SIZE - 1) / FORUM_SECTION_LIST_PAGE_SIZE)
            selectedPage = selectedPage.coerceIn(1, totalPage)
            val pageItems = sections.drop((selectedPage - 1) * FORUM_SECTION_LIST_PAGE_SIZE).take(FORUM_SECTION_LIST_PAGE_SIZE)
            title = "帖子分区管理"
            msg = "[cyan]3+级与管理员可新增/修改帖子分区名称和介绍。"
            pageItems.forEach { section ->
                option("[gold]${section.name}\n[gray]${section.code} / ${compactLine(section.description, 18)}") {
                    openForumSectionEditMenu(player, section.code)
                }
                newRow()
            }
            repeat(FORUM_SECTION_LIST_PAGE_SIZE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            option("新增分区") { editForumSectionFlow(player, null) }
            option("返回分区") { openForumIndex(player) }
            option("关闭") {}
        }
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun openForumSectionEditMenu(player: Player, sectionCode: String) {
    if (!canManageForum(player)) {
        player.sendMessage("[red]权限不足：只有3+级玩家与管理员可以管理帖子分区。")
        return
    }
    val section = db { forumSectionCachedOrNull(sectionCode) } ?: run {
        player.sendMessage("[yellow]分区不存在：$sectionCode")
        openForumSectionManageMenu(player)
        return
    }
    MenuBuilder<Unit>("分区管理：${section.name}") {
        msg = """
            |[cyan]Code：[white]${section.code}
            |[cyan]名称：[white]${section.name}
            |[cyan]排序：[white]${section.sortOrder}
            |[cyan]介绍：
            |[gray]${section.description}
        """.trimMargin()
        option("修改名称/介绍") { editForumSectionFlow(player, section.code) }
        option("打开分区") { openForumPostList(player, section.code) }
        newRow()
        option("返回管理") { openForumSectionManageMenu(player) }
        option("关闭") {}
    }.sendTo(player, FORUM_MENU_TIMEOUT_MILLIS)
}

private suspend fun editForumSectionFlow(player: Player, fixedCode: String?) {
    if (!canManageForum(player)) {
        player.sendMessage("[red]权限不足：只有3+级玩家与管理员可以管理帖子分区。")
        return
    }
    val existing = fixedCode?.let { db { forumSectionCachedOrNull(it) } }
    val code = fixedCode ?: askForumText(
        player,
        "新增分区 - Code",
        "请输入分区code，仅允许英文小写、数字、_、-，最长${FORUM_MAX_SECTION_CODE_LENGTH}字符。",
        limit = FORUM_MAX_SECTION_CODE_LENGTH,
    )?.let { normalizeSectionCode(it) }
    if (code == null) {
        player.sendMessage("[yellow]已取消或分区code格式错误")
        openForumSectionManageMenu(player)
        return
    }
    val name = askForumText(
        player,
        if (existing == null) "新增分区 - 名称" else "修改分区名称",
        "请输入分区显示名称，最多${FORUM_MAX_SECTION_NAME_LENGTH}字。",
        default = existing?.name ?: "",
        limit = FORUM_MAX_SECTION_NAME_LENGTH,
    )
    if (name == null) {
        player.sendMessage("[yellow]已取消输入名称")
        openForumSectionManageMenu(player)
        return
    }
    val description = askForumText(
        player,
        if (existing == null) "新增分区 - 介绍" else "修改分区介绍",
        "请输入分区介绍，最多${FORUM_MAX_SECTION_DESCRIPTION_LENGTH}字。",
        default = existing?.description ?: "",
        limit = FORUM_MAX_SECTION_DESCRIPTION_LENGTH,
    )
    if (description == null) {
        player.sendMessage("[yellow]已取消输入介绍")
        openForumSectionManageMenu(player)
        return
    }
    val sortOrder = existing?.sortOrder
        ?: ((db { forumSectionsCached(includeDisabled = true).map { it.sortOrder }.maxOrNull() } ?: 90) + 10)
    val fixedName = name.take(FORUM_MAX_SECTION_NAME_LENGTH)
    val fixedDescription = description.take(FORUM_MAX_SECTION_DESCRIPTION_LENGTH)
    val ok = db {
        MdtStorage.upsertForumSection(
            code,
            fixedName,
            fixedDescription,
            sortOrder,
            true,
        )
    }
    if (ok) {
        clearForumCache()
        player.sendMessage("[green]已保存分区：[white]$name")
    }
    else player.sendMessage("[red]保存分区失败")
    openForumSectionEditMenu(player, code)
}

onEnable {
    // DBApi 的建表可能晚于业务脚本 onEnable。这里延迟执行，避免启动阶段查询尚未创建的表导致脚本卸载。
    launch(Dispatchers.IO) {
        delay(5_000)
        ensureDefaultForumSections()
        runCatching { MdtStorage.ensureForumStatsInitialized() }.onFailure {
            logger.warning("[MDT帖子系统] 初始化帖子统计失败，可能是数据库表尚未完成创建：${it.message}")
        }
        cleanupForumPostsIfNeeded(force = true)
    }
}

command("posts", "打开帖子列表") {
    aliases = listOf("post", "forum", "帖子", "帖子列表")
    usage = "[帖子ID|分区code|new|share <帖子ID>|history|trash|admin|lock <帖子ID>|unlock <帖子ID>|protect <帖子ID>|unprotect <帖子ID>|restore <帖子ID>|purge <帖子ID>]"
    attr(ClientOnly)
    body {
        val player = player!!
        when (val sub = arg.firstOrNull()) {
            null -> openForumIndex(player)
            "new", "add", "发布", "发帖" -> createForumPostFlow(player)
            "share", "分享" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                shareForumPostToChat(player, id)
            }
            "history", "log", "变更", "最近变更", "记录" -> openForumPostHistoryMenu(player)
            "trash", "回收站" -> openForumTrashMenu(player)
            "admin", "manage", "管理", "分区管理" -> openForumSectionManageMenu(player)
            "lock", "锁定" -> {
                if (!canManageForum(player)) returnReply("[red]权限不足：只有3+级玩家与管理员可以锁定帖子。".with())
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                if (db { MdtStorage.setForumPostLocked(id, true) }) {
                    clearForumCache()
                    reply("[green]已锁定帖子 #[white]$id[green]，自动清理不会删除它。".with())
                } else {
                    reply("[yellow]未找到可锁定的帖子 #[white]$id".with())
                }
            }
            "unlock", "解锁", "解除锁定" -> {
                if (!canManageForum(player)) returnReply("[red]权限不足：只有3+级玩家与管理员可以解锁帖子。".with())
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                if (db { MdtStorage.setForumPostLocked(id, false) }) {
                    clearForumCache()
                    reply("[green]已解除帖子 #[white]$id[green] 的自动清理锁定。".with())
                } else {
                    reply("[yellow]未找到可解锁的帖子 #[white]$id".with())
                }
            }
            "protect", "保护", "保护锁" -> {
                if (!canAdminForum(player)) returnReply("[red]权限不足：只有4级/admin可以设置帖子保护锁。".with())
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                if (db { MdtStorage.setForumPostProtected(id, true) }) {
                    clearForumCache()
                    reply("[green]已保护帖子 #[white]$id[green]，4级以下不可编辑/删除。".with())
                } else {
                    reply("[yellow]未找到可保护的帖子 #[white]$id".with())
                }
            }
            "unprotect", "解除保护", "取消保护" -> {
                if (!canAdminForum(player)) returnReply("[red]权限不足：只有4级/admin可以解除帖子保护锁。".with())
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                if (db { MdtStorage.setForumPostProtected(id, false) }) {
                    clearForumCache()
                    reply("[green]已解除帖子 #[white]$id[green] 的保护锁。".with())
                } else {
                    reply("[yellow]未找到可解除保护的帖子 #[white]$id".with())
                }
            }
            "restore", "恢复" -> {
                if (!canAdminForum(player)) returnReply("[red]权限不足：只有4级/admin可以恢复帖子。".with())
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                if (db { MdtStorage.restoreForumPost(id) }) {
                    db { clearForumDeletedMeta(id); appendForumPostHistory(player.plainName(), "恢复", id, "命令恢复") }
                    clearForumCache()
                    reply("[green]已恢复帖子 #[white]$id".with())
                } else {
                    reply("[yellow]恢复失败，帖子不存在或不在回收站。".with())
                }
            }
            "purge", "彻底删除" -> {
                if (!canAdminForum(player)) returnReply("[red]权限不足：只有4级/admin可以彻底删除帖子。".with())
                val id = arg.getOrNull(1)?.toIntOrNull() ?: replyUsage()
                val post = db { MdtStorage.getForumPostAnyStatus(id) }
                    ?: returnReply("[yellow]帖子不存在 #[white]$id".with())
                if (post.status != "deleted") returnReply("[yellow]只能彻底删除回收站中的帖子，请先普通删除到回收站。".with())
                confirmPurgeForumPost(player, id)
            }
            else -> {
                val id = sub.toIntOrNull()
                if (id != null) {
                    openForumPost(player, id)
                    return@body
                }
                val sectionCode = normalizeSectionCode(sub) ?: replyUsage()
                if (db { forumSectionCachedOrNull(sectionCode) } == null) {
                    returnReply("[yellow]未找到帖子或分区：$sub".with())
                }
                openForumPostList(player, sectionCode)
            }
        }
    }
}
