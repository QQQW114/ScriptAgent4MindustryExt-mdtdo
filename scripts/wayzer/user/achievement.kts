@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/menu", "成就菜单")
@file:Depends("coreMindustry/utilTextInput", "成就管理输入")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/user/seniorityLevel", "资历等级")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/ext/playerReputation", "赞踩数据")
@file:Depends("wayzer/ext/playerRecognition", "认可数据")
@file:Depends("wayzer/user/playerTitle", "称号奖励")

package wayzer.user

import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.hasPermission
import wayzer.lib.MdtStorage
import wayzer.lib.AchievementCompletedEvent
import wayzer.lib.ForumPostCreatedEvent
import wayzer.lib.PlayerData
import wayzer.lib.PlayerTitleChangedEvent
import wayzer.lib.RecognitionChangedEvent
import wayzer.lib.ReputationChangedEvent
import wayzer.lib.SeniorityLevelChangedEvent
import wayzer.lib.ShopPurchaseEvent
import wayzer.lib.TrustLevelChangedEvent
import wayzer.lib.TrustPointChangedEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

private data class AchievementTitleReward(
    val code: String,
    val displayName: String,
)

private data class AchievementDefinition(
    val code: String,
    val name: String,
    val requirement: String = "暂无公开要求",
    val rewardPoints: Int = 0,
    val titleRewards: List<AchievementTitleReward> = emptyList(),
    val hidden: Boolean = false,
    val special: Boolean = false,
    val condition: (String, Player?) -> Boolean = { _, _ -> false },
)

private data class AchievementTarget(
    val uid: String,
    val name: String,
    val player: Player?,
)

private data class ConditionPreset(
    val code: String,
    val name: String,
    val description: String,
    val valueHint: String,
    val defaultValue: String,
    val check: (String, Player?, MdtStorage.AchievementStatsSnapshot, String) -> Boolean,
)

private data class CustomAchievementCacheEntry(
    val items: List<MdtStorage.CustomAchievementRecord>,
    val loadedAt: Long,
)

private val shanghaiZone = ZoneId.of("Asia/Shanghai")
private val trustLevel = contextScript<TrustLevel>()
private val seniorityLevel = contextScript<SeniorityLevel>()
private val trustPoint = contextScript<TrustPoint>()
private val playerTitle = contextScript<PlayerTitle>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()

private val ACHIEVEMENT_COMPLETED_CACHE_TTL_MILLIS = 60_000L
private val CUSTOM_ACHIEVEMENT_CACHE_TTL_MILLIS = 60_000L
private val CUSTOM_ACHIEVEMENT_CODE_REGEX = Regex("[a-zA-Z0-9_\\-]{2,64}")
private val ACHIEVEMENT_CHECK_DEBOUNCE_MILLIS by config.key(1_200L, "成就自动检测防抖时间(ms)，用于合并连续点赞/点踩/认可/MDC等事件")

private data class CompletedAchievementCacheEntry(
    val codes: Set<String>,
    val loadedAt: Long,
)

private val completedAchievementCache = mutableMapOf<String, CompletedAchievementCacheEntry>()
private var customAchievementCache: CustomAchievementCacheEntry? = null
private val currentCheckStats = mutableMapOf<String, MdtStorage.AchievementStatsSnapshot>()
private val pendingAchievementChecks = mutableMapOf<String, kotlinx.coroutines.Job>()

private fun titleReward(code: String, displayName: String): AchievementTitleReward {
    val normalized = if (displayName.endsWith("[]")) displayName else "$displayName[]"
    return AchievementTitleReward(code, normalized)
}

private fun levelAtLeast(uid: String, player: Player?, levelCode: String): Boolean =
    with(trustLevel) { getTrustLevelOrder(uid, player) >= trustLevelOrder(levelCode) }

private fun today(): LocalDate = LocalDate.now(shanghaiZone)
private fun activeAchievementStats(uid: String): MdtStorage.AchievementStatsSnapshot =
    currentCheckStats[uid] ?: MdtStorage.getAchievementStatsSnapshot(uid)

private fun intValue(value: String): Int = value.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
private fun longValue(value: String): Long = value.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun currentMonthDay(): String {
    val now = today()
    return "%02d-%02d".format(now.monthValue, now.dayOfMonth)
}

private fun seniorityAtLeast(uid: String, player: Player?, levelCode: String): Boolean =
    with(seniorityLevel) { getSeniorityLevelOrder(uid, player) >= seniorityLevelOrder(levelCode) }

private val conditionPresets = listOf(
    ConditionPreset("received_likes", "获赞数", "累计收到的点赞数达到指定值。", "输入需要的获赞数，例如 10", "10") { _, _, stats, value ->
        stats.reputation.receivedLikes >= intValue(value)
    },
    ConditionPreset("given_likes", "点赞数", "累计给其他人的点赞数达到指定值。", "输入需要的点赞数，例如 10", "10") { _, _, stats, value ->
        stats.reputation.givenLikes >= intValue(value)
    },
    ConditionPreset("received_dislikes", "被踩数", "累计收到的点踩数达到指定值，一般建议用于趣味/隐藏成就。", "输入需要的被踩数，例如 5", "5") { _, _, stats, value ->
        stats.reputation.receivedDislikes >= intValue(value)
    },
    ConditionPreset("given_dislikes", "点踩数", "累计给其他人的点踩数达到指定值。", "输入需要的点踩数，例如 5", "5") { _, _, stats, value ->
        stats.reputation.givenDislikes >= intValue(value)
    },
    ConditionPreset("received_recognitions", "获认可数", "累计收到的认可数达到指定值。", "输入需要的获认可数，例如 1", "1") { _, _, stats, value ->
        stats.recognition.received >= intValue(value)
    },
    ConditionPreset("given_recognitions", "认可他人数", "累计认可其他人的次数达到指定值。", "输入需要的认可次数，例如 1", "1") { _, _, stats, value ->
        stats.recognition.given >= intValue(value)
    },
    ConditionPreset("forum_posts", "发帖数", "累计发帖数达到指定值。", "输入需要的发帖数，例如 1", "1") { _, _, stats, value ->
        stats.forumPosts >= intValue(value)
    },
    ConditionPreset("play_hours", "在线小时", "累计在线小时数达到指定值。", "输入需要的在线小时，例如 16", "1") { _, _, stats, value ->
        stats.playMillis >= longValue(value) * 60L * 60L * 1000L
    },
    ConditionPreset("current_mdc", "当前MDC", "当前持有 MDC 达到指定值。", "输入需要的当前MDC，例如 100", "100") { _, _, stats, value ->
        stats.points.current >= intValue(value)
    },
    ConditionPreset("total_mdc", "累计MDC", "历史累计获得 MDC 达到指定值。", "输入需要的累计MDC，例如 600", "600") { _, _, stats, value ->
        stats.points.total >= intValue(value)
    },
    ConditionPreset("trust_level", "信任等级", "信任等级至少达到指定等级。", "输入 0/1/2/3/3+/3++/4", "1") { uid, player, _, value ->
        levelAtLeast(uid, player, value)
    },
    ConditionPreset("seniority_level", "资历等级", "资历等级至少达到指定等级。", "输入 0/1/2/3/4", "1") { uid, player, _, value ->
        seniorityAtLeast(uid, player, value)
    },
    ConditionPreset("login_date", "特定日期在线/登录", "在指定日期触发检测即可完成；玩家加入时会自动检测。", "输入 MM-dd 或 yyyy-MM-dd，例如 06-01", currentMonthDay()) { _, _, _, value ->
        val now = today()
        val fixed = value.trim()
        fixed == currentMonthDay() || fixed == now.toString() || fixed == "${now.monthValue}-${now.dayOfMonth}"
    },
    ConditionPreset("login_hour", "特定小时在线/登录", "在指定小时触发检测即可完成。", "输入 0-23，例如 0 表示凌晨0点这一小时", "0") { _, _, _, value ->
        LocalTime.now(shanghaiZone).hour == (value.trim().toIntOrNull() ?: -1).coerceIn(-1, 23)
    },
)
private val conditionPresetByCode = conditionPresets.associateBy { it.code }

private val achievements = listOf(
    AchievementDefinition(
        code = "level_1_verified",
        name = "已认证",
        requirement = "完成账号认证/达到信任1级。",
        rewardPoints = 5,
        condition = { uid, player -> levelAtLeast(uid, player, "1") },
    ),
    AchievementDefinition(
        code = "level_2",
        name = "升到2级",
        requirement = "信任等级至少达到2级。",
        rewardPoints = 10,
        titleRewards = listOf(titleReward("member", "[green][成员]")),
        condition = { uid, player -> levelAtLeast(uid, player, "2") },
    ),
    AchievementDefinition(
        code = "level_3",
        name = "升到3级",
        requirement = "信任等级至少达到3级。",
        rewardPoints = 50,
        titleRewards = listOf(titleReward("active_player", "[pink][活跃玩家]")),
        condition = { uid, player -> levelAtLeast(uid, player, "3") },
    ),
    AchievementDefinition(
        code = "level_3_plus",
        name = "升到3+级",
        requirement = "信任等级至少达到3+级。",
        rewardPoints = 100,
        condition = { uid, player -> levelAtLeast(uid, player, "3+") },
    ),
    AchievementDefinition(
        code = "first_like_given",
        name = "首次回应",
        requirement = "累计给其他玩家点赞至少1次。",
        rewardPoints = 5,
        condition = { uid, _ -> activeAchievementStats(uid).reputation.givenLikes >= 1 },
    ),
    AchievementDefinition(
        code = "first_forum_post",
        name = "首次发贴",
        requirement = "累计发帖至少1次。",
        rewardPoints = 5,
        condition = { uid, _ -> activeAchievementStats(uid).forumPosts >= 1 },
    ),
    AchievementDefinition(
        code = "first_recognition_received",
        name = "第一次认可",
        requirement = "累计收到认可至少1次。",
        rewardPoints = 20,
        condition = { uid, _ -> activeAchievementStats(uid).recognition.received >= 1 },
    ),
    AchievementDefinition(
        code = "received_10_likes",
        name = "受到赞赏",
        requirement = "累计收到点赞至少10次。",
        rewardPoints = 20,
        condition = { uid, _ -> activeAchievementStats(uid).reputation.receivedLikes >= 10 },
    ),
    AchievementDefinition(
        code = "given_10_likes",
        name = "出于爱",
        requirement = "累计给其他玩家点赞至少10次。",
        rewardPoints = 10,
        condition = { uid, _ -> activeAchievementStats(uid).reputation.givenLikes >= 10 },
    ),
    AchievementDefinition(
        code = "thanks",
        name = "谢谢",
        requirement = "累计收到点赞至少10次，且累计给其他玩家点赞至少20次。",
        rewardPoints = 30,
        condition = { uid, _ ->
            activeAchievementStats(uid).let { it.reputation.receivedLikes >= 10 && it.reputation.givenLikes >= 20 }
        },
    ),
    AchievementDefinition(
        code = "solution_agency",
        name = "解决方案机构",
        requirement = "累计收到点赞至少100次，且累计收到认可至少20次。",
        rewardPoints = 200,
        titleRewards = listOf(titleReward("solution_agency", "[gold][解决方案机构]")),
        hidden = true,
        condition = { uid, _ ->
            activeAchievementStats(uid).let { it.reputation.receivedLikes >= 100 && it.recognition.received >= 20 }
        },
    ),
    AchievementDefinition(
        code = "children_day",
        name = "儿童节！",
        requirement = "在6月1日登录/在线并触发成就检测。",
        rewardPoints = 20,
        condition = { _, _ ->
            val now = today()
            now.monthValue == 6 && now.dayOfMonth == 1
        },
    ),
    AchievementDefinition(
        code = "new_year",
        name = "元旦快乐",
        requirement = "在1月1日登录/在线并触发成就检测。",
        titleRewards = listOf(titleReward("new_year_new_start", "[red][新年新气象]")),
        condition = { _, _ ->
            val now = today()
            now.monthValue == 1 && now.dayOfMonth == 1
        },
    ),
    AchievementDefinition(
        code = "seed_user",
        name = "种子用户",
        requirement = "特殊成就：由管理员授予。",
        rewardPoints = 10,
        titleRewards = listOf(titleReward("seed_user", "[green][ [gold]种子[pink]用户[green] ]")),
        special = true,
    ),
    AchievementDefinition(
        code = "contributor",
        name = "贡献者",
        requirement = "特殊成就：由管理员授予。",
        titleRewards = listOf(titleReward("contributor", "[gold][贡献者]")),
        special = true,
    ),
)

private fun loadCustomAchievementRecords(includeDisabled: Boolean = false, forceRefresh: Boolean = false): List<MdtStorage.CustomAchievementRecord> {
    val now = System.currentTimeMillis()
    val cached = customAchievementCache
    val records = if (!forceRefresh && cached != null && now - cached.loadedAt <= CUSTOM_ACHIEVEMENT_CACHE_TTL_MILLIS) {
        cached.items
    } else {
        MdtStorage.listCustomAchievements(includeDisabled = true).also {
            customAchievementCache = CustomAchievementCacheEntry(it, now)
        }
    }
    return if (includeDisabled) records else records.filter { it.enabled }
}

private fun invalidateCustomAchievementCache() {
    customAchievementCache = null
}

private fun customAchievementToDefinition(record: MdtStorage.CustomAchievementRecord): AchievementDefinition {
    val titleCode = record.titleCode
    val titleDisplay = record.titleDisplay
    val titleRewards = if (!titleCode.isNullOrBlank() && !titleDisplay.isNullOrBlank()) {
        listOf(titleReward(titleCode, titleDisplay))
    } else emptyList()
    return AchievementDefinition(
        code = record.code,
        name = record.name,
        requirement = conditionText(record),
        rewardPoints = record.rewardPoints,
        titleRewards = titleRewards,
        hidden = record.hidden,
        condition = { _, _ -> false },
    )
}

private fun allAchievementDefinitions(includeDisabledCustom: Boolean = false): List<AchievementDefinition> =
    achievements + loadCustomAchievementRecords(includeDisabledCustom).map { customAchievementToDefinition(it) }

private fun achievementByCode(includeDisabledCustom: Boolean = true): Map<String, AchievementDefinition> =
    allAchievementDefinitions(includeDisabledCustom).associateBy { it.code }

private fun evaluateCustomAchievement(
    uid: String,
    player: Player?,
    stats: MdtStorage.AchievementStatsSnapshot,
    record: MdtStorage.CustomAchievementRecord,
): Boolean {
    if (!record.enabled) return false
    val preset = conditionPresetByCode[record.conditionType] ?: return false
    return runCatching { preset.check(uid, player, stats, record.conditionValue) }.getOrDefault(false)
}

private fun conditionText(record: MdtStorage.CustomAchievementRecord): String {
    val preset = conditionPresetByCode[record.conditionType]
    return if (preset == null) "[red]未知条件:${record.conditionType}"
    else "${preset.name}：${record.conditionValue}"
}

private fun loadCompletedAchievements(uid: String, forceRefresh: Boolean = false): Set<String> {
    val now = System.currentTimeMillis()
    val cached = completedAchievementCache[uid]
    if (!forceRefresh && cached != null && now - cached.loadedAt <= ACHIEVEMENT_COMPLETED_CACHE_TTL_MILLIS) {
        return cached.codes
    }
    val codes = MdtStorage.completedAchievements(uid)
    completedAchievementCache[uid] = CompletedAchievementCacheEntry(codes, now)
    return codes
}

private fun rememberCompletedAchievement(uid: String, code: String) {
    val now = System.currentTimeMillis()
    val old = completedAchievementCache[uid]?.codes ?: MdtStorage.completedAchievements(uid)
    completedAchievementCache[uid] = CompletedAchievementCacheEntry(old + code, now)
}

private fun invalidateCompletedAchievements(uid: String) {
    completedAchievementCache.remove(uid)
}

fun completedAchievements(uid: String): Set<String> = loadCompletedAchievements(uid)
fun isAchievementCompleted(uid: String, code: String): Boolean = code in loadCompletedAchievements(uid)
private fun isAchievementCompleted(completed: Set<String>, code: String): Boolean = code in completed
private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.find { PlayerData[it].id == uid }

private fun displayName(uid: String, player: Player? = null): String =
    player?.name ?: PlayerData.findByShortId(uid)?.name ?: uid

private fun rewardText(achievement: AchievementDefinition): String {
    val parts = mutableListOf<String>()
    if (achievement.rewardPoints > 0) parts += "${achievement.rewardPoints} MDC"
    if (achievement.titleRewards.isNotEmpty()) parts += "称号奖励*${achievement.titleRewards.size}"
    return parts.joinToString("+").ifBlank { "无" }
}

private fun requirementText(achievement: AchievementDefinition): String =
    achievement.requirement.ifBlank { "暂无公开要求" }

private fun emitAchievementCompleted(uid: String, achievement: AchievementDefinition) {
    launch { AchievementCompletedEvent(uid, achievement.code).emitAsync() }
}

private fun giveRewards(uid: String, achievement: AchievementDefinition) {
    if (achievement.rewardPoints > 0) {
        with(trustPoint) { addTrustPoints(uid, achievement.rewardPoints, "Achievement:${achievement.code}") }
    }
    achievement.titleRewards.forEach { reward ->
        with(playerTitle) { grantTitle(uid, "ach_${reward.code}", reward.displayName, "成就奖励：${achievement.name}") }
    }
}

private fun completeAchievement(uid: String, achievement: AchievementDefinition, player: Player? = onlinePlayerByUid(uid)): Boolean {
    // 先落盘“已完成”，再发奖励，避免奖励触发的MDC/等级事件导致重复领奖。
    if (!MdtStorage.completeAchievement(uid, achievement.code, if (achievement.special) "admin" else "auto")) return false
    rememberCompletedAchievement(uid, achievement.code)

    giveRewards(uid, achievement)
    emitAchievementCompleted(uid, achievement)

    player?.sendMessage("[green]你完成了[gold]${achievement.name}[green]！奖励:${rewardText(achievement)}")
    if (achievement.hidden) {
        broadcast("[gold]${displayName(uid, player)}[yellow]完成了隐藏成就：[gold]${achievement.name}[yellow]！".with())
    }
    return true
}

fun checkAchievements(uid: String, player: Player? = onlinePlayerByUid(uid)): Int {
    var completedCount = 0
    val completed = completedAchievements(uid)
    val stats = MdtStorage.getAchievementStatsSnapshot(uid)
    currentCheckStats[uid] = stats
    try {
        achievements.asSequence()
            .filter { !it.special }
            .filter { !isAchievementCompleted(completed, it.code) }
            .filter { it.condition(uid, player) }
            .forEach { if (completeAchievement(uid, it, player)) completedCount++ }
        val enabledCustom = loadCustomAchievementRecords(includeDisabled = false)
        if (enabledCustom.isNotEmpty()) {
            val refreshedCompleted = if (completedCount > 0) completedAchievements(uid) else completed
            enabledCustom.asSequence()
                .filter { !isAchievementCompleted(refreshedCompleted, it.code) }
                .filter { evaluateCustomAchievement(uid, player, stats, it) }
                .map { customAchievementToDefinition(it) }
                .forEach { if (completeAchievement(uid, it, player)) completedCount++ }
        }
    } finally {
        currentCheckStats.remove(uid)
    }
    return completedCount
}

private fun checkAchievements(uids: Iterable<String>) {
    uids.distinct().forEach { checkAchievements(it) }
}

private fun scheduleAchievementCheck(uid: String, player: Player? = onlinePlayerByUid(uid), reason: String = "event") {
    pendingAchievementChecks.remove(uid)?.cancel()
    pendingAchievementChecks[uid] = launch(Dispatchers.game) {
        delay(ACHIEVEMENT_CHECK_DEBOUNCE_MILLIS.coerceAtLeast(0L))
        pendingAchievementChecks.remove(uid)
        runCatching {
            checkAchievements(uid, player ?: onlinePlayerByUid(uid))
        }.onFailure {
            logger.warning("成就自动检测失败(reason=$reason, uid=$uid): ${it.message}")
        }
    }
}

private fun scheduleAchievementCheck(uids: Iterable<String>, reason: String = "event") {
    uids.distinct().forEach { scheduleAchievementCheck(it, reason = reason) }
}

private fun visibleAchievementName(completedSet: Set<String>, achievement: AchievementDefinition): String {
    val completed = isAchievementCompleted(completedSet, achievement.code)
    return when {
        achievement.hidden && !completed -> "[gold]****（隐藏成就）"
        achievement.special -> "[purple][特殊][] ${achievement.name}"
        else -> achievement.name
    }
}

private fun optionText(completedSet: Set<String>, achievement: AchievementDefinition): String {
    val completed = isAchievementCompleted(completedSet, achievement.code)
    if (achievement.hidden && !completed) return "[gold]****（隐藏成就）"
    val reward = if (completed) "[green][已完成]" else "[gray]奖励：${rewardText(achievement)}"
    return "${visibleAchievementName(completedSet, achievement)}\n$reward"
}

private fun showAchievement(uid: String, player: Player, achievement: AchievementDefinition) {
    if (!isAchievementCompleted(uid, achievement.code)) {
        val requirement = requirementText(achievement)
        player.sendMessage(
            if (achievement.hidden) {
                "[yellow]这是隐藏成就，尚未完成前不会显示名字和奖励\n[cyan]达成要求：[white]$requirement"
            } else {
                "[yellow]你还没有完成成就：[white]${achievement.name}[]\n[cyan]达成要求：[white]$requirement\n[gray]奖励：${rewardText(achievement)}"
            }
        )
        return
    }
    broadcast("[gold]${player.name}[white]正在展示ta的[accent]${achievement.name}[gold]成就！[white] 奖励：${rewardText(achievement)}".with())
}

private suspend fun showAchievementMenu(player: Player) {
    val uid = PlayerData[player].id
    checkAchievements(uid, player)
    val completed = completedAchievements(uid)
    val defs = allAchievementDefinitions(includeDisabledCustom = false)
    val knownCodes = defs.mapTo(hashSetOf()) { it.code }
    val completedCount = completed.count { it in knownCodes }
    val canAdmin = player.hasPermission("wayzer.admin.achievement")

    object : PagedMenuBuilder<AchievementDefinition>(defs, prePage = 6) {
        override suspend fun renderItem(item: AchievementDefinition) {
            option(optionText(completed, item)) { showAchievement(uid, player, item) }
        }

        override suspend fun build() {
            title = "[yellow]成就系统"
            msg = """
                |[cyan]完成进度：[white]$completedCount/${defs.size}
                |[gray]点击已完成成就可向全服展示；隐藏成就未完成前不显示名字和奖励。
            """.trimMargin()
            if (canAdmin) {
                option("[yellow]成就管理\n[gray]添加/删除/编辑自定义成就") { showAchievementAdminMenu(player) }
                newRow()
            }
            super.build()
        }
    }.sendTo(player, 60_000)
}

private fun resolveTarget(text: String): AchievementTarget {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return AchievementTarget(uid, name, data?.player)
}

private fun normalizeCustomCode(raw: String): String? {
    val fixed = raw.trim().replace(' ', '_')
    if (!CUSTOM_ACHIEVEMENT_CODE_REGEX.matches(fixed)) return null
    return if (fixed.startsWith("custom_")) fixed else "custom_$fixed"
}

private suspend fun inputText(player: Player, title: String, message: String, default: String = "", limit: Int = 120): String? =
    with(textInput) { textInput(player, title, message, default = default, lengthLimit = limit, timeoutMillis = 60_000) }
        ?.trim()

private suspend fun inputInt(player: Player, title: String, message: String, default: Int, min: Int = 0): Int? {
    val raw = with(textInput) {
        textInput(player, title, message, default = default.toString(), lengthLimit = 10, isNumeric = true, timeoutMillis = 60_000)
    } ?: return null
    return raw.trim().toIntOrNull()?.coerceAtLeast(min)
}

private fun saveCustomAchievement(record: MdtStorage.CustomAchievementRecord): MdtStorage.CustomAchievementRecord {
    val saved = MdtStorage.upsertCustomAchievement(record)
    invalidateCustomAchievementCache()
    return saved
}

private fun newCustomAchievement(code: String, name: String): MdtStorage.CustomAchievementRecord =
    MdtStorage.CustomAchievementRecord(
        code = code,
        name = name,
        enabled = true,
        hidden = false,
        conditionType = "received_likes",
        conditionValue = "10",
        rewardPoints = 0,
        titleCode = null,
        titleDisplay = null,
        updatedAt = Instant.now(),
    )

private suspend fun createCustomAchievement(player: Player) {
    val rawCode = inputText(player, "新建成就", "输入成就代号，仅允许英文/数字/_/-；会自动加 custom_ 前缀", limit = 64) ?: return
    val code = normalizeCustomCode(rawCode) ?: run {
        player.sendMessage("[red]成就代号不合法：仅允许 2-64 位英文/数字/_/-")
        return
    }
    if (MdtStorage.getCustomAchievement(code) != null || code in achievementByCode(includeDisabledCustom = true)) {
        player.sendMessage("[red]成就代号已存在：$code")
        return
    }
    val name = inputText(player, "新建成就", "输入成就名称", default = code.removePrefix("custom_"), limit = 48)
        ?.ifBlank { code } ?: return
    val saved = saveCustomAchievement(newCustomAchievement(code, name))
    player.sendMessage("[green]已创建自定义成就：[yellow]${saved.name}[gray]（$code）")
    showCustomAchievementDetailMenu(player, saved.code)
}

private suspend fun selectConditionType(player: Player, record: MdtStorage.CustomAchievementRecord) {
    object : PagedMenuBuilder<ConditionPreset>(conditionPresets, prePage = 6) {
        override suspend fun renderItem(item: ConditionPreset) {
            val selected = if (item.code == record.conditionType) "[green][当前][] " else ""
            option("$selected[yellow]${item.name}\n[gray]${item.description}") {
                val value = inputText(player, "设置条件值", item.valueHint, default = item.defaultValue, limit = 128) ?: return@option
                val saved = saveCustomAchievement(record.copy(conditionType = item.code, conditionValue = value))
                player.sendMessage("[green]已设置条件：[yellow]${item.name} [white]$value")
                showCustomAchievementDetailMenu(player, saved.code)
            }
        }

        override suspend fun build() {
            title = "选择成就条件"
            msg = "[gray]第一版自定义成就使用单条件，避免复杂表达式拖慢检测。"
            super.build()
        }
    }.sendTo(player, 60_000)
}

private suspend fun showCustomAchievementDetailMenu(player: Player, code: String) {
    val record = MdtStorage.getCustomAchievement(code) ?: run {
        player.sendMessage("[red]自定义成就不存在或已删除：$code")
        showAchievementAdminMenu(player)
        return
    }
    MenuBuilder<Unit>("成就管理：${record.name}") {
        msg = """
            |[cyan]代号：[white]${record.code}
            |[cyan]状态：[white]${if (record.enabled) "启用" else "禁用"} ${if (record.hidden) "[gold]隐藏" else "[gray]公开"}
            |[cyan]条件：[white]${conditionText(record)}
            |[cyan]奖励：[white]${record.rewardPoints} MDC${if (!record.titleDisplay.isNullOrBlank()) " + 称号 ${record.titleDisplay}" else ""}
            |[gray]删除成就不会回滚玩家已经获得的奖励/完成记录。
        """.trimMargin()
        option(if (record.enabled) "[yellow]禁用成就" else "[green]启用成就") {
            saveCustomAchievement(record.copy(enabled = !record.enabled))
            showCustomAchievementDetailMenu(player, code)
        }
        option(if (record.hidden) "[yellow]改为公开" else "[gold]改为隐藏") {
            saveCustomAchievement(record.copy(hidden = !record.hidden))
            showCustomAchievementDetailMenu(player, code)
        }
        newRow()
        option("[cyan]修改名称\n[gray]当前：${record.name}") {
            val name = inputText(player, "修改成就名称", "输入新的成就名称", default = record.name, limit = 64) ?: return@option
            saveCustomAchievement(record.copy(name = name.ifBlank { record.name }))
            showCustomAchievementDetailMenu(player, code)
        }
        option("[cyan]修改条件\n[gray]${conditionText(record)}") { selectConditionType(player, record) }
        newRow()
        option("[yellow]修改MDC奖励\n[gray]当前：${record.rewardPoints}") {
            val points = inputInt(player, "修改MDC奖励", "输入完成时奖励的MDC，0表示无MDC奖励", record.rewardPoints) ?: return@option
            saveCustomAchievement(record.copy(rewardPoints = points))
            showCustomAchievementDetailMenu(player, code)
        }
        option("[pink]修改称号奖励\n[gray]${record.titleDisplay ?: "无"}") {
            val titleCode = inputText(player, "称号奖励代号", "输入称号代号，留空表示清除称号奖励", default = record.titleCode ?: "", limit = 64) ?: return@option
            if (titleCode.isBlank()) {
                saveCustomAchievement(record.copy(titleCode = null, titleDisplay = null))
                showCustomAchievementDetailMenu(player, code)
                return@option
            }
            val display = inputText(player, "称号显示名", "输入称号显示名，可带颜色，例如 [gold][大佬]", default = record.titleDisplay ?: "[gold][${record.name}]", limit = 120) ?: return@option
            saveCustomAchievement(record.copy(titleCode = titleCode, titleDisplay = display))
            showCustomAchievementDetailMenu(player, code)
        }
        newRow()
        option("[green]检测在线玩家\n[gray]让当前在线玩家立即尝试完成") {
            var count = 0
            Groups.player.forEach { count += checkAchievements(PlayerData[it].id, it) }
            player.sendMessage("[green]已检测在线玩家，本次新完成：[yellow]$count")
            showCustomAchievementDetailMenu(player, code)
        }
        option("[red]删除成就\n[gray]仅删除定义，不回滚已获得奖励") {
            val confirm = inputText(player, "确认删除", "输入 DELETE 确认删除 ${record.name}", limit = 16) ?: return@option
            if (confirm == "DELETE") {
                MdtStorage.deleteCustomAchievement(record.code)
                invalidateCustomAchievementCache()
                player.sendMessage("[green]已删除自定义成就：[yellow]${record.name}")
                showAchievementAdminMenu(player)
            } else {
                player.sendMessage("[yellow]已取消删除")
                showCustomAchievementDetailMenu(player, code)
            }
        }
        newRow()
        option("返回列表") { showCustomAchievementListMenu(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun showCustomAchievementListMenu(player: Player) {
    val records = loadCustomAchievementRecords(includeDisabled = true, forceRefresh = true)
    object : PagedMenuBuilder<MdtStorage.CustomAchievementRecord>(records, prePage = 6) {
        override suspend fun renderItem(item: MdtStorage.CustomAchievementRecord) {
            val state = if (item.enabled) "[green]启用" else "[gray]禁用"
            val hidden = if (item.hidden) "[gold]隐藏" else "[lightgray]公开"
            option("$state $hidden [yellow]${item.name}\n[gray]${item.code} | ${conditionText(item)} | 奖励:${item.rewardPoints}MDC") {
                showCustomAchievementDetailMenu(player, item.code)
            }
        }

        override suspend fun build() {
            title = "自定义成就列表"
            msg = if (records.isEmpty()) "[yellow]还没有自定义成就。" else "[gray]点击成就进入编辑；仅构建当前页，避免大列表卡顿。"
            super.build()
        }
    }.sendTo(player, 60_000)
}

private suspend fun showConditionHelpMenu(player: Player) {
    object : PagedMenuBuilder<ConditionPreset>(conditionPresets, prePage = 6) {
        override suspend fun renderItem(item: ConditionPreset) {
            option("[yellow]${item.name}\n[gray]${item.description}\n[lightgray]值：${item.valueHint}") { refresh() }
        }
        override suspend fun build() {
            title = "可用成就条件"
            msg = "[gray]这些条件会在相关事件、玩家加入与管理员手动检测时检查；检测时合并统计查询，避免每条自定义成就单独打库。"
            super.build()
        }
    }.sendTo(player, 60_000)
}

private suspend fun showAchievementAdminMenu(player: Player) {
    MenuBuilder<Unit>("成就管理") {
        val customCount = loadCustomAchievementRecords(includeDisabled = true).size
        msg = """
            |[cyan]内置成就：[white]${achievements.size}
            |[cyan]自定义成就：[white]$customCount
            |[gray]柠檬成就实现主要是脚本调用 finishAchievement 后记录并发经验；MDT 版保留事件驱动检测，并新增可配置条件与 MDC/称号奖励。
        """.trimMargin()
        option("[green]自定义成就列表\n[gray]编辑/删除已有自定义成就") { showCustomAchievementListMenu(player) }
        option("[yellow]新建自定义成就\n[gray]单条件 + MDC/称号奖励") { createCustomAchievement(player) }
        newRow()
        option("[cyan]可用条件参数\n[gray]点赞/获赞/发帖/在线时间/登录日期等") { showConditionHelpMenu(player) }
        option("[green]刷新缓存\n[gray]重新读取自定义成就定义") {
            invalidateCustomAchievementCache()
            loadCustomAchievementRecords(includeDisabled = true, forceRefresh = true)
            player.sendMessage("[green]成就定义缓存已刷新")
            showAchievementAdminMenu(player)
        }
        newRow()
        option("[yellow]检测在线玩家\n[gray]批量触发一次成就检查") {
            var count = 0
            Groups.player.forEach { count += checkAchievements(PlayerData[it].id, it) }
            player.sendMessage("[green]已检测在线玩家，本次新完成：[yellow]$count")
            showAchievementAdminMenu(player)
        }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

listenTo<TrustLevelChangedEvent> {
    scheduleAchievementCheck(uid, reason = "trust-level")
}

listenTo<ReputationChangedEvent> {
    scheduleAchievementCheck(uids, reason = "reputation")
}

listenTo<RecognitionChangedEvent> {
    scheduleAchievementCheck(uids, reason = "recognition")
}

listenTo<TrustPointChangedEvent> {
    scheduleAchievementCheck(uids, reason = "trust-point")
}

listenTo<SeniorityLevelChangedEvent> {
    scheduleAchievementCheck(uid, reason = "seniority")
}

listenTo<PlayerTitleChangedEvent> {
    scheduleAchievementCheck(uids, reason = "title")
}

listenTo<ShopPurchaseEvent> {
    scheduleAchievementCheck(uid, reason = "shop")
}

listenTo<ForumPostCreatedEvent> {
    scheduleAchievementCheck(uid, reason = "forum")
}

listen<EventType.PlayerJoin> {
    scheduleAchievementCheck(PlayerData[it.player].id, it.player, "join")
}

onEnable {
    Groups.player.forEach { scheduleAchievementCheck(PlayerData[it].id, it, "enable") }
}

onDisable {
    pendingAchievementChecks.values.forEach { it.cancel() }
    pendingAchievementChecks.clear()
}

command("achievements", "打开成就系统") {
    aliases = listOf("achievement", "成就", "成就系统")
    attr(ClientOnly)
    body {
        showAchievementMenu(player!!)
    }
}

command("achadmin", "管理指令：查看/授予/撤销玩家成就") {
    usage = "[menu] 或 <玩家id/3位id> [list|check|grant|revoke] [成就code]"
    permission = "wayzer.admin.achievement"
    aliases = listOf("achievementadmin", "成就管理")
    body {
        if (arg.isEmpty() || arg[0].equals("menu", ignoreCase = true) || arg[0] == "菜单") {
            val p = player ?: returnReply("[yellow]控制台请使用参数形式：/achadmin <玩家> list|check|grant|revoke".with())
            showAchievementAdminMenu(p)
            return@body
        }
        val target = resolveTarget(arg[0])
        val op = arg.getOrNull(1)?.lowercase() ?: "list"

        when (op) {
            "list", "查看" -> {
                val byCode = achievementByCode(includeDisabledCustom = true)
                val completed = completedAchievements(target.uid)
                    .map { byCode[it]?.name ?: it }
                    .joinToString(", ")
                    .ifBlank { "无" }
                returnReply(
                    """
                        |[cyan]玩家：[white]${target.name}
                        |[cyan]UID：[white]${target.uid}
                        |[cyan]已完成成就：[white]$completed
                    """.trimMargin().with()
                )
            }
            "check", "检测" -> {
                val count = checkAchievements(target.uid, target.player)
                reply("[green]已检查 [white]{name}[green] 的成就，本次新完成：[yellow]{count}".with(
                    "name" to target.name,
                    "count" to count
                ))
            }
            "grant", "give", "授予", "完成" -> {
                if (arg.size < 3) replyUsage()
                val achievement = achievementByCode(includeDisabledCustom = true)[arg[2]]
                    ?: returnReply("[red]未知成就 code，可用 /achadmin 打开菜单查看自定义成就".with())
                val changed = completeAchievement(target.uid, achievement, target.player)
                val message = if (changed)
                    "[green]已授予 [white]{name}[green] 成就：[gold]{achievement}"
                else
                    "[yellow]目标已经完成该成就：[gold]{achievement}"
                reply(message.with("name" to target.name, "achievement" to achievement.name))
            }
            "revoke", "remove", "撤销", "移除" -> {
                if (arg.size < 3) replyUsage()
                val code = arg[2]
                val changed = MdtStorage.revokeAchievement(target.uid, code)
                if (changed) invalidateCompletedAchievements(target.uid)
                val message = if (changed)
                    "[green]已撤销 [white]{name}[green] 的成就记录：[yellow]{code}[gray]（不会回滚已发奖励）"
                else
                    "[yellow]目标没有该成就记录：[white]{code}"
                reply(message.with("name" to target.name, "code" to code))
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.achievement", group = "@admin")

