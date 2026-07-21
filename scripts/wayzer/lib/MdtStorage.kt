package wayzer.lib

import coreLib.db.DBApi
import coreLib.db.DBApi.WithUpgrade
import coreLibrary.lib.get
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction as exposedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalDate
import java.util.logging.Logger
import kotlin.random.Random

/**
 * MDT 自定义系统的数据库存储层。
 *
 * 当前用现有 ScriptAgent/Exposed 标准接口注册表，业务脚本只通过这里读写数据。
 * 逻辑仍保留在各自脚本中，避免把信任/称号/成就等规则揉进同一个大脚本。
 *
 * subjectUid 使用 wayzer.lib.PlayerData[player].id；玩家登录账号后会切换为 account:<id> 主体，
 * 未登录时仍使用游戏 UUID 主体。
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object MdtStorage {
    const val UID_LENGTH = 128
    const val CODE_LENGTH = 96

    private const val SLOW_DB_TRANSACTION_WARN_MS = 200L
    private const val VERY_SLOW_DB_TRANSACTION_WARN_MS = 1000L
    private const val RECENT_REPUTATION_DAYS = 7L
    private const val KEEP_REPUTATION_DAILY_DAYS = 14L
    private val dbLogger = Logger.getLogger("wayzer.lib.MdtStorage")

    data class ReputationCounts(
        val receivedLikes: Int = 0,
        val receivedDislikes: Int = 0,
        val givenLikes: Int = 0,
        val givenDislikes: Int = 0,
    )

    data class ReputationVoteResult(
        val accepted: Boolean,
        val targetCount: Int,
        val totalCount: Int,
        val rejectedBy: String? = null,
    )

    data class RecognitionCounts(
        val received: Int = 0,
        val given: Int = 0,
    )

    data class TrustPointCounts(
        val current: Int = 0,
        val total: Int = 0,
    )

    data class TrustPointMigrationResult(
        val migratedCurrent: Int = 0,
        val migratedTotal: Int = 0,
        val targetCurrent: Int = 0,
        val targetTotal: Int = 0,
    ) {
        val changed: Boolean get() = migratedCurrent != 0 || migratedTotal != 0
    }

    data class AchievementStatsSnapshot(
        val points: TrustPointCounts = TrustPointCounts(),
        val reputation: ReputationCounts = ReputationCounts(),
        val recognition: RecognitionCounts = RecognitionCounts(),
        val playMillis: Long = 0L,
        val forumPosts: Int = 0,
        val trustLevelCode: String? = null,
        val seniorityLevelCode: String? = null,
    )

    data class CustomAchievementRecord(
        val code: String,
        val name: String,
        val enabled: Boolean,
        val hidden: Boolean,
        val conditionType: String,
        val conditionValue: String,
        val rewardPoints: Int,
        val titleCode: String?,
        val titleDisplay: String?,
        val updatedAt: Instant,
    )

    data class SeniorityProfileRecord(
        val uid: String,
        val levelCode: String?,
        val levelLocked: Boolean,
        val playMillis: Long,
    )

    data class TrustPromotionStats(
        val manualLevelCode: String?,
        val levelLocked: Boolean,
        val points: TrustPointCounts = TrustPointCounts(),
        val reputation: ReputationCounts = ReputationCounts(),
        val recentReputation: ReputationCounts = ReputationCounts(),
        val recognition: RecognitionCounts = RecognitionCounts(),
    )

    data class SeniorityAutoCheckStats(
        val seniority: SeniorityProfileRecord,
        val trustManualLevelCode: String?,
        val trustPoints: TrustPointCounts = TrustPointCounts(),
    )

    data class TitleDefinitionRecord(
        val code: String,
        val displayName: String,
        val description: String,
    )

    data class AccountRecord(
        val id: Int,
        val qq: String,
        val passwordHash: String,
        val status: String,
    )

    data class AutoLoginRecord(
        val account: AccountRecord,
        val subjectUid: String,
    )

    data class TitleShopItemRecord(
        val id: String,
        val titleContent: String,
        val price: Int,
        val requiredLevelCode: String,
        val requiredRecognitions: Int,
        val enabled: Boolean,
    )

    data class PlayerSkillRecord(
        val uid: String,
        val skillCode: String,
        val sourceTag: String,
    )

    data class ForumSectionRecord(
        val code: String,
        val name: String,
        val description: String,
        val sortOrder: Int,
        val enabled: Boolean,
    )

    data class ForumPostRecord(
        val id: Int,
        val sectionCode: String,
        val authorUid: String,
        val authorName: String,
        val title: String,
        val body: String,
        val pinned: Boolean,
        val status: String,
        val commentCount: Int,
        val authorLikeClicks: Int,
        val authorDislikeClicks: Int,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    data class ForumCommentRecord(
        val id: Int,
        val postId: Int,
        val authorUid: String,
        val authorName: String,
        val body: String,
        val status: String,
        val createdAt: Instant,
    )

    data class ForumStats(
        val currentPosts: Int,
        val totalPosts: Int,
    )

    data class ForumPostPage(
        val items: List<ForumPostRecord>,
        val total: Int,
    )

    data class ForumCommentPage(
        val items: List<ForumCommentRecord>,
        val total: Int,
    )

    data class RedPacketRecord(
        val id: Int,
        val senderUid: String,
        val senderName: String,
        val totalAmount: Int,
        val remainingAmount: Int,
        val totalShares: Int,
        val remainingShares: Int,
        val message: String,
        val status: String,
        val createdAt: Instant,
        val expireAt: Instant,
    )

    data class RedPacketClaimRecord(
        val id: Int,
        val packetId: Int,
        val claimerUid: String,
        val claimerName: String,
        val amount: Int,
        val claimedAt: Instant,
    )

    data class RedPacketClaimResult(
        val success: Boolean,
        val reason: String,
        val packet: RedPacketRecord? = null,
        val claim: RedPacketClaimRecord? = null,
    )

    data class LeaderboardEntry(
        val uid: String,
        val name: String?,
        val value: Int,
    )

    data class IpAccountBindingRecord(
        val ip: String,
        val accountId: Int,
        val accountQq: String?,
        val lastName: String?,
        val lastUuid: String?,
        val lastUsid: String?,
        val firstSeenAt: Instant,
        val lastSeenAt: Instant,
    )

    data class IpAccountBindResult(
        val success: Boolean,
        val binding: IpAccountBindingRecord?,
        val message: String,
    )

    object Accounts : IntIdTable("MdtAccounts") {
        val username = varchar("username", 50).uniqueIndex()
        val passwordHash = varchar("password_hash", 255)
        val status = varchar("status", 24).default("normal")
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val lastLoginAt = timestamp("last_login_at").nullable()
    }

    object AccountBindings : IntIdTable("MdtAccountBindings") {
        val account = reference("account_id", Accounts)
        val type = varchar("binding_type", 32)
        val identifier = varchar("identifier", 128)
        val verified = bool("verified").default(false)
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val lastSeenAt = timestamp("last_seen_at").nullable()
        val unique = uniqueIndex(type, identifier)
    }

    object PlayerSubjects : IdTable<String>("MdtPlayerSubjects"), WithUpgrade {
        // v2: 为账号系统补充 account/last_ip/last_usid/last_login_at 等字段。
        override val version: Int = 2
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val account = optReference("account_id", Accounts)
        val lastName = varchar("last_name", 64).nullable()
        val lastIp = varchar("last_ip", 64).nullable()
        val lastUsid = varchar("last_usid", 64).nullable()
        val lastLoginAt = timestamp("last_login_at").nullable()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object TrustProfiles : IdTable<String>("MdtTrustProfiles") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val manualLevelCode = varchar("manual_level_code", 8).nullable()
        val levelLocked = bool("level_locked").default(false)
        val currentPoints = integer("current_points").default(0)
        val totalPoints = integer("total_points").default(0)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object SeniorityProfiles : IdTable<String>("MdtSeniorityProfiles") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val levelCode = varchar("level_code", 4).nullable()
        val levelLocked = bool("level_locked").default(false)
        val playMillis = long("play_millis").default(0L)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ReputationStats : IdTable<String>("MdtReputationStats") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val receivedLikes = integer("received_likes").default(0)
        val receivedDislikes = integer("received_dislikes").default(0)
        val givenLikes = integer("given_likes").default(0)
        val givenDislikes = integer("given_dislikes").default(0)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ReputationDaily : IntIdTable("MdtReputationDaily") {
        val date = varchar("date", 10)
        val fromUid = varchar("from_uid", UID_LENGTH)
        val targetUid = varchar("target_uid", UID_LENGTH)
        val voteType = varchar("vote_type", 16)
        val count = integer("count").default(0)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(date, fromUid, targetUid, voteType)
    }

    object RecognitionStats : IdTable<String>("MdtRecognitionStats") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val received = integer("received").default(0)
        val given = integer("given").default(0)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object RecognitionPairs : IntIdTable("MdtRecognitionPairs") {
        val fromUid = varchar("from_uid", UID_LENGTH)
        val targetUid = varchar("target_uid", UID_LENGTH)
        val createdDate = varchar("created_date", 10)
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(fromUid, targetUid)
    }

    object RecognitionDaily : IntIdTable("MdtRecognitionDaily") {
        val date = varchar("date", 10)
        val fromUid = varchar("from_uid", UID_LENGTH)
        val targetUid = varchar("target_uid", UID_LENGTH)
        val unique = uniqueIndex(date, fromUid)
    }

    object TitleDefinitions : IdTable<String>("MdtTitleDefinitions") {
        override val id: Column<EntityID<String>> = varchar("code", CODE_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val displayName = varchar("display_name", 160)
        val description = text("description", eagerLoading = true).default("")
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object PlayerTitles : IntIdTable("MdtPlayerTitles") {
        val uid = varchar("subject_uid", UID_LENGTH)
        val titleCode = varchar("title_code", CODE_LENGTH)
        val sourceTag = varchar("source", 120).nullable()
        val obtainedAt = timestamp("obtained_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(uid, titleCode)
    }

    object EquippedTitles : IdTable<String>("MdtEquippedTitles") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val titleCode = varchar("title_code", CODE_LENGTH)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object PlayerAchievements : IntIdTable("MdtPlayerAchievements") {
        val uid = varchar("subject_uid", UID_LENGTH)
        val achievementCode = varchar("achievement_code", CODE_LENGTH)
        val sourceTag = varchar("source", 120).nullable()
        val completedAt = timestamp("completed_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(uid, achievementCode)
    }

    object CustomAchievements : IdTable<String>("MdtCustomAchievements") {
        override val id: Column<EntityID<String>> = varchar("code", CODE_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val name = varchar("name", 96)
        val enabled = bool("enabled").default(true)
        val hidden = bool("hidden").default(false)
        val conditionType = varchar("condition_type", 48)
        val conditionValue = varchar("condition_value", 128).default("1")
        val rewardPoints = integer("reward_points").default(0)
        val titleCode = varchar("title_code", CODE_LENGTH).nullable()
        val titleDisplay = varchar("title_display", 160).nullable()
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ShopPurchaseStats : IntIdTable("MdtShopPurchaseStats") {
        val uid = varchar("subject_uid", UID_LENGTH)
        val shopCode = varchar("shop_code", CODE_LENGTH)
        val itemId = varchar("item_id", CODE_LENGTH)
        val count = integer("count").default(0)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(uid, shopCode, itemId)
    }

    object TitleShopItems : IdTable<String>("MdtTitleShopItems") {
        override val id: Column<EntityID<String>> = varchar("item_id", CODE_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val titleContent = text("title_content", eagerLoading = true)
        val price = integer("price").default(0)
        val requiredLevelCode = varchar("required_level_code", 8).default("0")
        val requiredRecognitions = integer("required_recognitions").default(0)
        val enabled = bool("enabled").default(true)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object PlayerSkills : IntIdTable("MdtPlayerSkills") {
        val uid = varchar("subject_uid", UID_LENGTH)
        val skillCode = varchar("skill_code", CODE_LENGTH)
        val sourceTag = varchar("source", 120).default("")
        val obtainedAt = timestamp("obtained_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(uid, skillCode)
    }

    object RandomForms : IdTable<String>("MdtRandomForms") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val activeForm = varchar("active_form", 32).nullable()
        val dailyRewardDate = varchar("daily_reward_date", 10).nullable()
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object MutedPlayers : IdTable<String>("MdtMutedPlayers") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val reason = text("reason", eagerLoading = true)
        val operator = varchar("operator", UID_LENGTH).nullable()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ForumSections : IdTable<String>("MdtForumSections") {
        override val id: Column<EntityID<String>> = varchar("code", CODE_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val name = varchar("name", 64)
        val description = text("description", eagerLoading = true).default("")
        val sortOrder = integer("sort_order").default(0)
        val enabled = bool("enabled").default(true)
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ForumPosts : IntIdTable("MdtForumPosts"), WithUpgrade {
        // v2: 为帖子分区系统补充 section_code 字段。
        override val version: Int = 2
        val sectionCode = varchar("section_code", CODE_LENGTH).default("all")
        val authorUid = varchar("author_uid", UID_LENGTH)
        val authorName = varchar("author_name", 64)
        val title = varchar("title", 96)
        val body = text("body", eagerLoading = true)
        val pinned = bool("pinned").default(false)
        val status = varchar("status", 16).default("normal")
        val commentCount = integer("comment_count").default(0)
        val authorLikeClicks = integer("author_like_clicks").default(0)
        val authorDislikeClicks = integer("author_dislike_clicks").default(0)
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ForumComments : IntIdTable("MdtForumComments") {
        val post = reference("post_id", ForumPosts)
        val authorUid = varchar("author_uid", UID_LENGTH)
        val authorName = varchar("author_name", 64)
        val body = text("body", eagerLoading = true)
        val status = varchar("status", 16).default("normal")
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object ForumAuthorStats : IdTable<String>("MdtForumAuthorStats") {
        override val id: Column<EntityID<String>> = varchar("subject_uid", UID_LENGTH).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val totalPosts = integer("total_posts").default(0)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object RedPackets : IntIdTable("MdtRedPackets") {
        val senderUid = varchar("sender_uid", UID_LENGTH)
        val senderName = varchar("sender_name", 64)
        val totalAmount = integer("total_amount")
        val remainingAmount = integer("remaining_amount")
        val totalShares = integer("total_shares")
        val remainingShares = integer("remaining_shares")
        val message = varchar("message", 120).default("")
        val status = varchar("status", 16).default("active")
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
        val expireAt = timestamp("expire_at")
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    object RedPacketClaims : IntIdTable("MdtRedPacketClaims") {
        val packet = reference("packet_id", RedPackets)
        val claimerUid = varchar("claimer_uid", UID_LENGTH)
        val claimerName = varchar("claimer_name", 64)
        val amount = integer("amount")
        val claimedAt = timestamp("claimed_at").defaultExpression(CurrentTimestamp)
        val unique = uniqueIndex(packet, claimerUid)
    }

    object IpAccountBindings : IdTable<String>("MdtIpAccountBindings") {
        override val id: Column<EntityID<String>> = varchar("ip", 64).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val account = reference("account_id", Accounts)
        val lastName = varchar("last_name", 64).nullable()
        val lastUuid = varchar("last_uuid", UID_LENGTH).nullable()
        val lastUsid = varchar("last_usid", 64).nullable()
        val firstSeenAt = timestamp("first_seen_at").defaultExpression(CurrentTimestamp)
        val lastSeenAt = timestamp("last_seen_at").defaultExpression(CurrentTimestamp)
    }

    object Settings : IdTable<String>("MdtSettings") {
        override val id: Column<EntityID<String>> = varchar("key", 96).entityId()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
        val value = text("value", eagerLoading = true).nullable()
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    fun tables(): Array<Table> = arrayOf(
        Accounts,
        AccountBindings,
        PlayerSubjects,
        TrustProfiles,
        SeniorityProfiles,
        ReputationStats,
        ReputationDaily,
        RecognitionStats,
        RecognitionPairs,
        RecognitionDaily,
        TitleDefinitions,
        PlayerTitles,
        EquippedTitles,
        PlayerAchievements,
        CustomAchievements,
        ShopPurchaseStats,
        TitleShopItems,
        PlayerSkills,
        RandomForms,
        MutedPlayers,
        ForumSections,
        ForumPosts,
        ForumComments,
        ForumAuthorStats,
        RedPackets,
        RedPacketClaims,
        IpAccountBindings,
        Settings,
    )

    private fun now(): Instant = Instant.now()

    fun accountSubjectUid(accountId: Int): String = "account:$accountId"

    private fun slowDbCaller(): String {
        val self = MdtStorage::class.java.name
        return Thread.currentThread().stackTrace.firstOrNull {
            it.className == self && it.methodName !in setOf("transaction", "slowDbCaller")
        }?.methodName ?: "unknown"
    }

    private fun <T> transaction(block: Transaction.() -> T): T {
        val start = System.nanoTime()
        var error: Throwable? = null
        try {
            return exposedTransaction(DBApi.db.get()) { block() }
        } catch (t: Throwable) {
            error = t
            throw t
        } finally {
            val costMs = (System.nanoTime() - start) / 1_000_000L
            if (costMs >= SLOW_DB_TRANSACTION_WARN_MS) {
                val level = if (costMs >= VERY_SLOW_DB_TRANSACTION_WARN_MS) "严重慢事务" else "慢事务"
                val failed = error?.let { " failed=${it.javaClass.simpleName}: ${it.message}" } ?: ""
                dbLogger.warning("[数据库] $level: ${slowDbCaller()} cost=${costMs}ms thread=${Thread.currentThread().name}$failed")
            }
        }
    }

    private fun ResultRow.toAccountRecord() = AccountRecord(
        id = get(Accounts.id).value,
        qq = get(Accounts.username),
        passwordHash = get(Accounts.passwordHash),
        status = get(Accounts.status),
    )

    private fun findAccountByIdInTx(accountId: Int): AccountRecord? =
        Accounts.selectAll().where { Accounts.id eq accountId }.firstOrNull()?.toAccountRecord()

    fun findAccountById(accountId: Int): AccountRecord? = transaction { findAccountByIdInTx(accountId) }

    private fun ResultRow.toIpAccountBindingRecord(): IpAccountBindingRecord {
        val accountId = get(IpAccountBindings.account).value
        return IpAccountBindingRecord(
            ip = get(IpAccountBindings.id).value,
            accountId = accountId,
            accountQq = findAccountByIdInTx(accountId)?.qq,
            lastName = get(IpAccountBindings.lastName),
            lastUuid = get(IpAccountBindings.lastUuid),
            lastUsid = get(IpAccountBindings.lastUsid),
            firstSeenAt = get(IpAccountBindings.firstSeenAt),
            lastSeenAt = get(IpAccountBindings.lastSeenAt),
        )
    }

    private fun getIpAccountBindingInTx(ip: String): IpAccountBindingRecord? =
        IpAccountBindings.selectAll().where { IpAccountBindings.id eq ip }
            .firstOrNull()
            ?.toIpAccountBindingRecord()

    fun getIpAccountBinding(ip: String): IpAccountBindingRecord? = transaction {
        getIpAccountBindingInTx(ip)
    }

    fun bindIpToAccount(
        ip: String,
        accountId: Int,
        lastName: String? = null,
        uuid: String? = null,
        usid: String? = null,
    ): IpAccountBindResult = transaction {
        val accountEntity = EntityID(accountId, Accounts)
        val loginAt = now()
        val old = IpAccountBindings.selectAll().where { IpAccountBindings.id eq ip }.firstOrNull()
        if (old == null) {
            IpAccountBindings.insert {
                it[id] = ip
                it[account] = accountEntity
                it[IpAccountBindings.lastName] = lastName
                it[lastUuid] = uuid
                it[lastUsid] = usid
                it[firstSeenAt] = loginAt
                it[lastSeenAt] = loginAt
            }
            return@transaction IpAccountBindResult(true, getIpAccountBindingInTx(ip), "已绑定IP到账号")
        }

        val oldAccountId = old[IpAccountBindings.account].value
        if (oldAccountId != accountId) {
            return@transaction IpAccountBindResult(false, old.toIpAccountBindingRecord(), "该IP已绑定其他账号")
        }

        IpAccountBindings.update({ IpAccountBindings.id eq ip }) {
            it[IpAccountBindings.lastName] = lastName
            it[lastUuid] = uuid
            it[lastUsid] = usid
            it[lastSeenAt] = loginAt
        }
        IpAccountBindResult(true, getIpAccountBindingInTx(ip), "IP账号绑定已更新")
    }

    fun deleteIpAccountBinding(ip: String): Boolean = transaction {
        IpAccountBindings.deleteWhere { IpAccountBindings.id eq ip } > 0
    }

    fun findAccountByQq(qq: String): AccountRecord? = transaction {
        Accounts.selectAll().where { Accounts.username eq qq }.firstOrNull()?.toAccountRecord()
    }

    fun createAccount(qq: String, passwordHash: String): AccountRecord? = transaction {
        if (Accounts.selectAll().where { Accounts.username eq qq }.any()) return@transaction null
        val accountId = Accounts.insertAndGetId {
            it[username] = qq
            it[Accounts.passwordHash] = passwordHash
            it[status] = "normal"
        }.value
        AccountBindings.insert {
            it[account] = EntityID(accountId, Accounts)
            it[type] = "QQ"
            it[identifier] = qq
            it[verified] = true
        }
        findAccountByIdInTx(accountId)
    }

    fun updateAccountPassword(accountId: Int, passwordHash: String): Boolean = transaction {
        Accounts.update({ Accounts.id eq accountId }) {
            it[Accounts.passwordHash] = passwordHash
        } > 0
    }

    fun updateAccountPasswordByQq(qq: String, passwordHash: String): Boolean = transaction {
        Accounts.update({ Accounts.username eq qq }) {
            it[Accounts.passwordHash] = passwordHash
        } > 0
    }

    private fun deleteSubjectBusinessDataInTx(subjectUid: String) {
        val ownedTitleCodes = PlayerTitles.selectAll().where { PlayerTitles.uid eq subjectUid }
            .map { it[PlayerTitles.titleCode] }

        val authoredPostIds = ForumPosts.selectAll().where { ForumPosts.authorUid eq subjectUid }
            .map { it[ForumPosts.id].value }
        authoredPostIds.forEach { deleteForumPostInTx(it) }
        ForumComments.deleteWhere { ForumComments.authorUid eq subjectUid }
        ForumAuthorStats.deleteWhere { ForumAuthorStats.id eq subjectUid }

        val sentPacketIds = RedPackets.selectAll().where { RedPackets.senderUid eq subjectUid }
            .map { it[RedPackets.id].value }
        sentPacketIds.forEach { packetId ->
            RedPacketClaims.deleteWhere { RedPacketClaims.packet eq EntityID(packetId, RedPackets) }
        }
        RedPacketClaims.deleteWhere { RedPacketClaims.claimerUid eq subjectUid }
        RedPackets.deleteWhere { RedPackets.senderUid eq subjectUid }

        TrustProfiles.deleteWhere { TrustProfiles.id eq subjectUid }
        SeniorityProfiles.deleteWhere { SeniorityProfiles.id eq subjectUid }
        ReputationStats.deleteWhere { ReputationStats.id eq subjectUid }
        ReputationDaily.deleteWhere { (ReputationDaily.fromUid eq subjectUid) or (ReputationDaily.targetUid eq subjectUid) }
        RecognitionStats.deleteWhere { RecognitionStats.id eq subjectUid }
        RecognitionPairs.deleteWhere { (RecognitionPairs.fromUid eq subjectUid) or (RecognitionPairs.targetUid eq subjectUid) }
        RecognitionDaily.deleteWhere { (RecognitionDaily.fromUid eq subjectUid) or (RecognitionDaily.targetUid eq subjectUid) }
        PlayerTitles.deleteWhere { PlayerTitles.uid eq subjectUid }
        EquippedTitles.deleteWhere { EquippedTitles.id eq subjectUid }
        PlayerAchievements.deleteWhere { PlayerAchievements.uid eq subjectUid }
        ShopPurchaseStats.deleteWhere { ShopPurchaseStats.uid eq subjectUid }
        PlayerSkills.deleteWhere { PlayerSkills.uid eq subjectUid }
        RandomForms.deleteWhere { RandomForms.id eq subjectUid }
        MutedPlayers.deleteWhere { MutedPlayers.id eq subjectUid }

        ownedTitleCodes
            .filter { it.startsWith("shop_custom_") }
            .forEach { code ->
                val stillOwned = PlayerTitles.selectAll().where { PlayerTitles.titleCode eq code }.any()
                if (!stillOwned) TitleDefinitions.deleteWhere { TitleDefinitions.id eq code }
            }
    }

    private fun deleteAccountBusinessDataInTx(accountId: Int, accountEntity: EntityID<Int>) {
        val accountSubjectUid = accountSubjectUid(accountId)
        val boundGameUids = PlayerSubjects.selectAll().where { PlayerSubjects.account eq accountEntity }
            .map { it[PlayerSubjects.id].value }

        (listOf(accountSubjectUid) + boundGameUids)
            .distinct()
            .forEach { deleteSubjectBusinessDataInTx(it) }

        PlayerSubjects.deleteWhere { (PlayerSubjects.id eq accountSubjectUid) or (PlayerSubjects.account eq accountEntity) }
        IpAccountBindings.deleteWhere { IpAccountBindings.account eq accountEntity }
    }

    fun deleteAccount(accountId: Int): Boolean = transaction {
        val accountRow = Accounts.selectAll().where { Accounts.id eq accountId }.firstOrNull() ?: return@transaction false
        val accountEntity = accountRow[Accounts.id]
        deleteAccountBusinessDataInTx(accountId, accountEntity)
        AccountBindings.deleteWhere { AccountBindings.account eq accountEntity }
        Accounts.deleteWhere { Accounts.id eq accountId } > 0
    }

    fun deleteAccountByQq(qq: String): AccountRecord? = transaction {
        val accountRow = Accounts.selectAll().where { Accounts.username eq qq }.firstOrNull()
            ?: return@transaction null
        val account = accountRow.toAccountRecord()
        val accountEntity = accountRow[Accounts.id]
        deleteAccountBusinessDataInTx(account.id, accountEntity)
        AccountBindings.deleteWhere { AccountBindings.account eq accountEntity }
        Accounts.deleteWhere { Accounts.id eq account.id }
        account
    }

    fun recordAccountLogin(gameUuid: String, accountId: Int, ip: String, usid: String, lastName: String?): String = transaction {
        val accountEntity = EntityID(accountId, Accounts)
        val loginAt = now()
        val existing = PlayerSubjects.selectAll().where { PlayerSubjects.id eq gameUuid }.firstOrNull()
        if (existing == null) {
            PlayerSubjects.insert {
                it[id] = gameUuid
                it[account] = accountEntity
                it[PlayerSubjects.lastName] = lastName
                it[lastIp] = ip
                it[lastUsid] = usid
                it[lastLoginAt] = loginAt
            }
        } else {
            PlayerSubjects.update({ PlayerSubjects.id eq gameUuid }) {
                it[account] = accountEntity
                it[PlayerSubjects.lastName] = lastName
                it[lastIp] = ip
                it[lastUsid] = usid
                it[lastLoginAt] = loginAt
                it[updatedAt] = loginAt
            }
        }
        val subjectUid = accountSubjectUid(accountId)
        val subjectExisting = PlayerSubjects.selectAll().where { PlayerSubjects.id eq subjectUid }.firstOrNull()
        if (subjectExisting == null) {
            PlayerSubjects.insert {
                it[id] = subjectUid
                it[account] = accountEntity
                it[PlayerSubjects.lastName] = lastName
                it[lastIp] = ip
                it[lastUsid] = usid
                it[lastLoginAt] = loginAt
            }
        } else {
            PlayerSubjects.update({ PlayerSubjects.id eq subjectUid }) {
                it[account] = accountEntity
                it[PlayerSubjects.lastName] = lastName
                it[lastIp] = ip
                it[lastUsid] = usid
                it[lastLoginAt] = loginAt
                it[updatedAt] = loginAt
            }
        }
        Accounts.update({ Accounts.id eq accountId }) {
            it[lastLoginAt] = loginAt
        }
        subjectUid
    }

    fun autoLoginByDevice(gameUuid: String, usid: String): AutoLoginRecord? = transaction {
        val row = PlayerSubjects.selectAll().where { PlayerSubjects.id eq gameUuid }.firstOrNull() ?: return@transaction null
        val accountId = row[PlayerSubjects.account]?.value ?: return@transaction null
        // 自动登录只检查游戏 UUID + USID，不再检查 IP，避免玩家网络变化后频繁重新登录。
        if (row[PlayerSubjects.lastUsid] != usid) return@transaction null
        val account = findAccountByIdInTx(accountId) ?: return@transaction null
        AutoLoginRecord(account, accountSubjectUid(accountId))
    }

    fun getAccountForGameUuid(gameUuid: String): AccountRecord? = transaction {
        val accountId = PlayerSubjects.selectAll().where { PlayerSubjects.id eq gameUuid }
            .firstOrNull()?.get(PlayerSubjects.account)?.value ?: return@transaction null
        findAccountByIdInTx(accountId)
    }

    fun getAccountForSubjectUid(subjectUid: String): AccountRecord? = transaction {
        val accountId = PlayerSubjects.selectAll().where { PlayerSubjects.id eq subjectUid }
            .firstOrNull()?.get(PlayerSubjects.account)?.value ?: return@transaction null
        findAccountByIdInTx(accountId)
    }

    private fun subjectNameInTx(uid: String): String? =
        PlayerSubjects.selectAll().where { PlayerSubjects.id eq uid }
            .firstOrNull()
            ?.get(PlayerSubjects.lastName)

    fun getSubjectName(uid: String): String? = transaction {
        subjectNameInTx(uid)
    }

    fun getSubjectNames(uids: Iterable<String>): Map<String, String?> = transaction {
        val fixed = uids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (fixed.isEmpty()) return@transaction emptyMap()
        val found = PlayerSubjects.selectAll().where { PlayerSubjects.id inList fixed }
            .associate { it[PlayerSubjects.id].value to it[PlayerSubjects.lastName] }
        fixed.associateWith { found[it] }
    }

    fun getSubjectLastIp(uid: String): String? = transaction {
        PlayerSubjects.selectAll().where { PlayerSubjects.id eq uid }
            .firstOrNull()
            ?.get(PlayerSubjects.lastIp)
    }

    fun getSetting(key: String): String? = transaction {
        Settings.selectAll().where { Settings.id eq key }.firstOrNull()?.get(Settings.value)
    }

    fun getSettings(keys: Iterable<String>): Map<String, String?> = transaction {
        val result = linkedMapOf<String, String?>()
        keys.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { key ->
                result[key] = Settings.selectAll().where { Settings.id eq key }.firstOrNull()?.get(Settings.value)
            }
        result
    }

    private fun setSettingInTx(key: String, value: String?) {
        val old = Settings.selectAll().where { Settings.id eq key }.firstOrNull()
        if (old == null) {
            Settings.insert {
                it[id] = key
                it[Settings.value] = value
            }
        } else {
            Settings.update({ Settings.id eq key }) {
                it[Settings.value] = value
                it[updatedAt] = now()
            }
        }
    }

    fun setSetting(key: String, value: String?) = transaction {
        setSettingInTx(key, value)
        Unit
    }

    private fun ensureTrustProfile(uid: String) {
        if (TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.empty()) {
            TrustProfiles.insert { it[id] = uid }
        }
    }

    private fun ensureSeniorityProfile(uid: String) {
        if (SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.empty()) {
            SeniorityProfiles.insert { it[id] = uid }
        }
    }

    private fun ensureReputationStats(uid: String) {
        if (ReputationStats.selectAll().where { ReputationStats.id eq uid }.empty()) {
            ReputationStats.insert { it[id] = uid }
        }
    }

    private fun ensureRecognitionStats(uid: String) {
        if (RecognitionStats.selectAll().where { RecognitionStats.id eq uid }.empty()) {
            RecognitionStats.insert { it[id] = uid }
        }
    }

    private fun ensureRandomForm(uid: String) {
        if (RandomForms.selectAll().where { RandomForms.id eq uid }.empty()) {
            RandomForms.insert { it[id] = uid }
        }
    }

    fun touchSubject(uid: String, lastName: String? = null) = transaction {
        val existing = PlayerSubjects.selectAll().where { PlayerSubjects.id eq uid }.firstOrNull()
        if (existing == null) {
            PlayerSubjects.insert {
                it[id] = uid
                it[PlayerSubjects.lastName] = lastName
            }
        } else if (lastName != null && existing[PlayerSubjects.lastName] != lastName) {
            PlayerSubjects.update({ PlayerSubjects.id eq uid }) {
                it[PlayerSubjects.lastName] = lastName
                it[updatedAt] = now()
            }
        }
        Unit
    }

    // Trust level / points
    fun getManualLevelCode(uid: String): String? = transaction {
        TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()?.get(TrustProfiles.manualLevelCode)
    }

    fun setManualLevelCode(uid: String, levelCode: String) = transaction {
        ensureTrustProfile(uid)
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[manualLevelCode] = levelCode
            it[updatedAt] = now()
        }
    }

    fun isTrustLevelLocked(uid: String): Boolean = transaction {
        TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()?.get(TrustProfiles.levelLocked) ?: false
    }

    fun setTrustLevelLocked(uid: String, locked: Boolean) = transaction {
        ensureTrustProfile(uid)
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[levelLocked] = locked
            it[updatedAt] = now()
        }
    }

    fun getTrustPoints(uid: String): Int = transaction {
        TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()?.get(TrustProfiles.currentPoints) ?: 0
    }

    fun getTotalTrustPoints(uid: String): Int = transaction {
        TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()?.get(TrustProfiles.totalPoints) ?: 0
    }

    fun getTrustPointCounts(uid: String): TrustPointCounts = transaction {
        TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()?.let {
            TrustPointCounts(
                current = it[TrustProfiles.currentPoints],
                total = it[TrustProfiles.totalPoints],
            )
        } ?: TrustPointCounts()
    }

    private fun ResultRow.toTrustPointCounts() = TrustPointCounts(
        current = get(TrustProfiles.currentPoints),
        total = get(TrustProfiles.totalPoints),
    )

    private fun safeAddInt(a: Int, b: Int): Int =
        (a.toLong() + b.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun getTrustPromotionStats(uid: String): TrustPromotionStats = transaction {
        val trust = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()
        val recentSince = LocalDate.now().minusDays(RECENT_REPUTATION_DAYS - 1).toString()
        TrustPromotionStats(
            manualLevelCode = trust?.get(TrustProfiles.manualLevelCode),
            levelLocked = trust?.get(TrustProfiles.levelLocked) ?: false,
            points = trust?.toTrustPointCounts() ?: TrustPointCounts(),
            reputation = ReputationStats.selectAll().where { ReputationStats.id eq uid }.firstOrNull()?.toReputationCounts()
                ?: ReputationCounts(),
            recentReputation = receivedReputationSinceInTx(uid, recentSince),
            recognition = RecognitionStats.selectAll().where { RecognitionStats.id eq uid }.firstOrNull()?.toRecognitionCounts()
                ?: RecognitionCounts(),
        )
    }

    fun addTrustPoints(uid: String, amount: Int): Int = transaction {
        ensureTrustProfile(uid)
        val row = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.first()
        val current = row[TrustProfiles.currentPoints]
        val total = row[TrustProfiles.totalPoints]
        val newCurrent = (current + amount).coerceAtLeast(0)
        val newTotal = if (amount > 0) total + amount else total
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[currentPoints] = newCurrent
            it[totalPoints] = newTotal
            it[updatedAt] = now()
        }
        newCurrent
    }

    fun addTrustPointsBatch(rewards: Map<String, Int>): Map<String, Int> = transaction {
        val fixed = rewards
            .mapValues { it.value.coerceAtLeast(0) }
            .filterValues { it > 0 }
        if (fixed.isEmpty()) return@transaction emptyMap()

        val result = linkedMapOf<String, Int>()
        fixed.forEach { (uid, amount) ->
            ensureTrustProfile(uid)
            val row = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.first()
            val current = row[TrustProfiles.currentPoints]
            val total = row[TrustProfiles.totalPoints]
            val newCurrent = current + amount
            val newTotal = total + amount
            TrustProfiles.update({ TrustProfiles.id eq uid }) {
                it[currentPoints] = newCurrent
                it[totalPoints] = newTotal
                it[updatedAt] = now()
            }
            result[uid] = newCurrent
        }
        result
    }

    private fun addCurrentTrustPointsInTx(uid: String, amount: Int): Int {
        ensureTrustProfile(uid)
        val row = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.first()
        val current = row[TrustProfiles.currentPoints]
        val newCurrent = (current + amount).coerceAtLeast(0)
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[currentPoints] = newCurrent
            it[updatedAt] = now()
        }
        return newCurrent
    }

    fun addCurrentTrustPoints(uid: String, amount: Int): Int = transaction {
        addCurrentTrustPointsInTx(uid, amount)
    }

    fun transferTrustPoints(fromUid: String, toUid: String, amount: Int): Boolean = transaction {
        if (amount <= 0 || fromUid == toUid) return@transaction false
        ensureTrustProfile(fromUid)
        ensureTrustProfile(toUid)
        val fromCurrent = TrustProfiles.selectAll().where { TrustProfiles.id eq fromUid }.first()[TrustProfiles.currentPoints]
        if (fromCurrent < amount) return@transaction false
        addCurrentTrustPointsInTx(fromUid, -amount)
        addCurrentTrustPointsInTx(toUid, amount)
        true
    }

    /**
     * 将未登录 UUID 主体下已经落库的 MDC 合并到注册后的账号主体。
     *
     * 只迁移 TrustProfiles 中的 current/total MDC，不迁移手动信任等级或等级锁。
     * 若来源行没有其它信任元数据，迁移后会删除来源行；否则仅把来源 MDC 清零，避免后续重复使用。
     */
    fun migrateTrustPoints(fromUid: String, toUid: String): TrustPointMigrationResult = transaction {
        if (fromUid == toUid) {
            val target = TrustProfiles.selectAll().where { TrustProfiles.id eq toUid }.firstOrNull()?.toTrustPointCounts()
                ?: TrustPointCounts()
            return@transaction TrustPointMigrationResult(targetCurrent = target.current, targetTotal = target.total)
        }

        val fromRow = TrustProfiles.selectAll().where { TrustProfiles.id eq fromUid }.firstOrNull()
            ?: return@transaction TrustPointMigrationResult()
        val migratedCurrent = fromRow[TrustProfiles.currentPoints].coerceAtLeast(0)
        val migratedTotal = fromRow[TrustProfiles.totalPoints].coerceAtLeast(0)
        if (migratedCurrent == 0 && migratedTotal == 0) {
            return@transaction TrustPointMigrationResult()
        }

        ensureTrustProfile(toUid)
        val toRow = TrustProfiles.selectAll().where { TrustProfiles.id eq toUid }.first()
        val targetCurrent = safeAddInt(toRow[TrustProfiles.currentPoints], migratedCurrent)
        val targetTotal = safeAddInt(toRow[TrustProfiles.totalPoints], migratedTotal)
        TrustProfiles.update({ TrustProfiles.id eq toUid }) {
            it[currentPoints] = targetCurrent
            it[totalPoints] = targetTotal
            it[updatedAt] = now()
        }

        val hasSourceMetadata = fromRow[TrustProfiles.manualLevelCode] != null || fromRow[TrustProfiles.levelLocked]
        if (hasSourceMetadata) {
            TrustProfiles.update({ TrustProfiles.id eq fromUid }) {
                it[currentPoints] = 0
                it[totalPoints] = 0
                it[updatedAt] = now()
            }
        } else {
            TrustProfiles.deleteWhere { TrustProfiles.id eq fromUid }
        }

        TrustPointMigrationResult(migratedCurrent, migratedTotal, targetCurrent, targetTotal)
    }

    fun spendTrustPoints(uid: String, amount: Int): Boolean = transaction {
        if (amount <= 0) return@transaction true
        ensureTrustProfile(uid)
        val current = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.first()[TrustProfiles.currentPoints]
        if (current < amount) return@transaction false
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[currentPoints] = current - amount
            it[updatedAt] = now()
        }
        true
    }

    fun setTrustPoints(uid: String, value: Int): Int = transaction {
        val fixed = value.coerceAtLeast(0)
        ensureTrustProfile(uid)
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[currentPoints] = fixed
            it[updatedAt] = now()
        }
        fixed
    }

    fun setTotalTrustPoints(uid: String, value: Int): Int = transaction {
        val fixed = value.coerceAtLeast(0)
        ensureTrustProfile(uid)
        TrustProfiles.update({ TrustProfiles.id eq uid }) {
            it[totalPoints] = fixed
            it[updatedAt] = now()
        }
        fixed
    }

    // Seniority / play time
    private fun ResultRow.toSeniorityProfileRecord() = SeniorityProfileRecord(
        uid = get(SeniorityProfiles.id).value,
        levelCode = get(SeniorityProfiles.levelCode),
        levelLocked = get(SeniorityProfiles.levelLocked),
        playMillis = get(SeniorityProfiles.playMillis),
    )

    fun getSeniorityProfile(uid: String): SeniorityProfileRecord = transaction {
        SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.firstOrNull()?.toSeniorityProfileRecord()
            ?: SeniorityProfileRecord(uid, null, false, 0L)
    }

    fun getSeniorityLevelCode(uid: String): String? = transaction {
        SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.firstOrNull()?.get(SeniorityProfiles.levelCode)
    }

    fun setSeniorityLevelCode(uid: String, levelCode: String?) = transaction {
        ensureSeniorityProfile(uid)
        SeniorityProfiles.update({ SeniorityProfiles.id eq uid }) {
            it[SeniorityProfiles.levelCode] = levelCode
            it[updatedAt] = now()
        }
    }

    fun isSeniorityLevelLocked(uid: String): Boolean = transaction {
        SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.firstOrNull()?.get(SeniorityProfiles.levelLocked) ?: false
    }

    fun setSeniorityLevelLocked(uid: String, locked: Boolean) = transaction {
        ensureSeniorityProfile(uid)
        SeniorityProfiles.update({ SeniorityProfiles.id eq uid }) {
            it[SeniorityProfiles.levelLocked] = locked
            it[updatedAt] = now()
        }
    }

    fun getPlayMillis(uid: String): Long = transaction {
        SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.firstOrNull()?.get(SeniorityProfiles.playMillis) ?: 0L
    }

    fun getSeniorityAutoCheckStats(uid: String): SeniorityAutoCheckStats = transaction {
        val seniority = SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.firstOrNull()?.toSeniorityProfileRecord()
            ?: SeniorityProfileRecord(uid, null, false, 0L)
        val trust = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()
        SeniorityAutoCheckStats(
            seniority = seniority,
            trustManualLevelCode = trust?.get(TrustProfiles.manualLevelCode),
            trustPoints = trust?.toTrustPointCounts() ?: TrustPointCounts(),
        )
    }

    fun addPlayMillis(uid: String, millis: Long): Long = transaction {
        ensureSeniorityProfile(uid)
        val current = SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.first()[SeniorityProfiles.playMillis]
        if (millis <= 0L) return@transaction current
        val next = (current + millis).coerceAtLeast(0L)
        SeniorityProfiles.update({ SeniorityProfiles.id eq uid }) {
            it[playMillis] = next
            it[updatedAt] = now()
        }
        next
    }

    fun addPlayMillisBatch(deltas: Map<String, Long>): Map<String, Long> = transaction {
        val fixed = deltas
            .mapValues { it.value.coerceAtLeast(0L) }
            .filterValues { it > 0L }
        if (fixed.isEmpty()) return@transaction emptyMap()

        val result = linkedMapOf<String, Long>()
        fixed.forEach { (uid, delta) ->
            ensureSeniorityProfile(uid)
            val current = SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.first()[SeniorityProfiles.playMillis]
            val next = (current + delta).coerceAtLeast(0L)
            SeniorityProfiles.update({ SeniorityProfiles.id eq uid }) {
                it[playMillis] = next
                it[updatedAt] = now()
            }
            result[uid] = next
        }
        result
    }

    fun setPlayMillis(uid: String, millis: Long): Long = transaction {
        val fixed = millis.coerceAtLeast(0L)
        ensureSeniorityProfile(uid)
        SeniorityProfiles.update({ SeniorityProfiles.id eq uid }) {
            it[playMillis] = fixed
            it[updatedAt] = now()
        }
        fixed
    }

    // Reputation / likes / dislikes
    private fun ResultRow.toReputationCounts() = ReputationCounts(
        receivedLikes = get(ReputationStats.receivedLikes),
        receivedDislikes = get(ReputationStats.receivedDislikes),
        givenLikes = get(ReputationStats.givenLikes),
        givenDislikes = get(ReputationStats.givenDislikes),
    )

    fun getReputation(uid: String): ReputationCounts = transaction {
        ReputationStats.selectAll().where { ReputationStats.id eq uid }.firstOrNull()?.toReputationCounts()
            ?: ReputationCounts()
    }

    fun getReceivedReputation(uid: String, type: String): Int = getReputation(uid).let {
        if (type == "like") it.receivedLikes else it.receivedDislikes
    }

    private fun receivedReputationSinceInTx(uid: String, sinceDateInclusive: String): ReputationCounts {
        var likes = 0
        var dislikes = 0
        ReputationDaily.selectAll().where {
            (ReputationDaily.targetUid eq uid) and
                    (ReputationDaily.date greaterEq sinceDateInclusive)
        }.forEach { row ->
            when (row[ReputationDaily.voteType]) {
                "like" -> likes += row[ReputationDaily.count]
                "dislike" -> dislikes += row[ReputationDaily.count]
            }
        }
        return ReputationCounts(receivedLikes = likes, receivedDislikes = dislikes)
    }

    fun getRecentReceivedReputation(uid: String, sinceDateInclusive: String): ReputationCounts = transaction {
        receivedReputationSinceInTx(uid, sinceDateInclusive)
    }

    fun setReceivedReputation(uid: String, type: String, value: Int): Int = transaction {
        val fixed = value.coerceAtLeast(0)
        ensureReputationStats(uid)
        val column = if (type == "like") ReputationStats.receivedLikes else ReputationStats.receivedDislikes
        ReputationStats.update({ ReputationStats.id eq uid }) {
            it[column] = fixed
            it[updatedAt] = now()
        }
        fixed
    }

    fun addReceivedReputation(uid: String, type: String, delta: Int): Int = transaction {
        val old = getReceivedReputation(uid, type)
        setReceivedReputation(uid, type, old + delta)
    }

    fun dailyVoteTargetCount(date: String, fromUid: String, targetUid: String, type: String): Int = transaction {
        ReputationDaily.selectAll().where {
            (ReputationDaily.date eq date) and
                    (ReputationDaily.fromUid eq fromUid) and
                    (ReputationDaily.targetUid eq targetUid) and
                    (ReputationDaily.voteType eq type)
        }.sumOf { it[ReputationDaily.count] }
    }

    fun dailyVoteTotal(date: String, fromUid: String, type: String): Int = transaction {
        ReputationDaily.selectAll().where {
            (ReputationDaily.date eq date) and
                    (ReputationDaily.fromUid eq fromUid) and
                    (ReputationDaily.voteType eq type)
        }.sumOf { it[ReputationDaily.count] }
    }

    fun recordReputationVote(date: String, fromUid: String, targetUid: String, type: String): Int = transaction {
        ensureReputationStats(fromUid)
        ensureReputationStats(targetUid)

        val dailyRows = ReputationDaily.selectAll().where {
            (ReputationDaily.date eq date) and
                    (ReputationDaily.fromUid eq fromUid) and
                    (ReputationDaily.targetUid eq targetUid) and
                    (ReputationDaily.voteType eq type)
        }.toList()
        val daily = dailyRows.firstOrNull()
        val newTargetCount = dailyRows.sumOf { it[ReputationDaily.count] } + 1
        if (daily == null) {
            ReputationDaily.insert {
                it[ReputationDaily.date] = date
                it[ReputationDaily.fromUid] = fromUid
                it[ReputationDaily.targetUid] = targetUid
                it[ReputationDaily.voteType] = type
                it[count] = newTargetCount
            }
        } else {
            ReputationDaily.update({ ReputationDaily.id eq daily[ReputationDaily.id].value }) {
                it[count] = newTargetCount
                it[updatedAt] = now()
            }
            dailyRows.drop(1).forEach { duplicate ->
                ReputationDaily.deleteWhere { ReputationDaily.id eq duplicate[ReputationDaily.id].value }
            }
        }

        val targetStats = ReputationStats.selectAll().where { ReputationStats.id eq targetUid }.first().toReputationCounts()
        val fromStats = ReputationStats.selectAll().where { ReputationStats.id eq fromUid }.first().toReputationCounts()
        ReputationStats.update({ ReputationStats.id eq targetUid }) {
            if (type == "like") it[receivedLikes] = targetStats.receivedLikes + 1
            else it[receivedDislikes] = targetStats.receivedDislikes + 1
            it[updatedAt] = now()
        }
        ReputationStats.update({ ReputationStats.id eq fromUid }) {
            if (type == "like") it[givenLikes] = fromStats.givenLikes + 1
            else it[givenDislikes] = fromStats.givenDislikes + 1
            it[updatedAt] = now()
        }
        newTargetCount
    }

    fun recordReputationVoteChecked(
        date: String,
        fromUid: String,
        targetUid: String,
        type: String,
        totalLimit: Int?,
        targetLimit: Int?,
    ): ReputationVoteResult = transaction {
        ensureReputationStats(fromUid)
        ensureReputationStats(targetUid)

        val todayTotal = ReputationDaily.selectAll().where {
            (ReputationDaily.date eq date) and
                    (ReputationDaily.fromUid eq fromUid) and
                    (ReputationDaily.voteType eq type)
        }.sumOf { it[ReputationDaily.count] }
        if (totalLimit != null && todayTotal >= totalLimit) {
            return@transaction ReputationVoteResult(false, 0, todayTotal, "total")
        }

        val dailyRows = ReputationDaily.selectAll().where {
            (ReputationDaily.date eq date) and
                    (ReputationDaily.fromUid eq fromUid) and
                    (ReputationDaily.targetUid eq targetUid) and
                    (ReputationDaily.voteType eq type)
        }.toList()
        val daily = dailyRows.firstOrNull()
        val oldTargetCount = dailyRows.sumOf { it[ReputationDaily.count] }
        if (targetLimit != null && oldTargetCount >= targetLimit) {
            return@transaction ReputationVoteResult(false, oldTargetCount, todayTotal, "target")
        }

        val newTargetCount = oldTargetCount + 1
        if (daily == null) {
            ReputationDaily.insert {
                it[ReputationDaily.date] = date
                it[ReputationDaily.fromUid] = fromUid
                it[ReputationDaily.targetUid] = targetUid
                it[ReputationDaily.voteType] = type
                it[count] = newTargetCount
            }
        } else {
            ReputationDaily.update({ ReputationDaily.id eq daily[ReputationDaily.id].value }) {
                it[count] = newTargetCount
                it[updatedAt] = now()
            }
            dailyRows.drop(1).forEach { duplicate ->
                ReputationDaily.deleteWhere { ReputationDaily.id eq duplicate[ReputationDaily.id].value }
            }
        }

        val targetStats = ReputationStats.selectAll().where { ReputationStats.id eq targetUid }.first().toReputationCounts()
        val fromStats = ReputationStats.selectAll().where { ReputationStats.id eq fromUid }.first().toReputationCounts()
        ReputationStats.update({ ReputationStats.id eq targetUid }) {
            if (type == "like") it[receivedLikes] = targetStats.receivedLikes + 1
            else it[receivedDislikes] = targetStats.receivedDislikes + 1
            it[updatedAt] = now()
        }
        ReputationStats.update({ ReputationStats.id eq fromUid }) {
            if (type == "like") it[givenLikes] = fromStats.givenLikes + 1
            else it[givenDislikes] = fromStats.givenDislikes + 1
            it[updatedAt] = now()
        }
        ReputationVoteResult(true, newTargetCount, todayTotal + 1, null)
    }

    fun pruneReputationDaily(today: String) = transaction {
        val cutoff = runCatching {
            LocalDate.parse(today).minusDays(KEEP_REPUTATION_DAILY_DAYS - 1).toString()
        }.getOrDefault(today)
        ReputationDaily.deleteWhere { ReputationDaily.date less cutoff }
    }

    // Recognition
    private fun ResultRow.toRecognitionCounts() = RecognitionCounts(
        received = get(RecognitionStats.received),
        given = get(RecognitionStats.given),
    )

    fun getRecognition(uid: String): RecognitionCounts = transaction {
        RecognitionStats.selectAll().where { RecognitionStats.id eq uid }.firstOrNull()?.toRecognitionCounts()
            ?: RecognitionCounts()
    }

    fun hasRecognitionPair(fromUid: String, targetUid: String): Boolean = transaction {
        !RecognitionPairs.selectAll().where {
            (RecognitionPairs.fromUid eq fromUid) and (RecognitionPairs.targetUid eq targetUid)
        }.empty()
    }

    fun hasDailyRecognition(date: String, fromUid: String): Boolean = transaction {
        !RecognitionDaily.selectAll().where {
            (RecognitionDaily.date eq date) and (RecognitionDaily.fromUid eq fromUid)
        }.empty()
    }

    fun recordRecognition(date: String, fromUid: String, targetUid: String): Boolean = transaction {
        if (hasRecognitionPair(fromUid, targetUid) || hasDailyRecognition(date, fromUid)) return@transaction false
        ensureRecognitionStats(fromUid)
        ensureRecognitionStats(targetUid)
        RecognitionPairs.insert {
            it[RecognitionPairs.fromUid] = fromUid
            it[RecognitionPairs.targetUid] = targetUid
            it[createdDate] = date
        }
        RecognitionDaily.insert {
            it[RecognitionDaily.date] = date
            it[RecognitionDaily.fromUid] = fromUid
            it[RecognitionDaily.targetUid] = targetUid
        }
        val fromStats = RecognitionStats.selectAll().where { RecognitionStats.id eq fromUid }.first().toRecognitionCounts()
        val targetStats = RecognitionStats.selectAll().where { RecognitionStats.id eq targetUid }.first().toRecognitionCounts()
        RecognitionStats.update({ RecognitionStats.id eq fromUid }) {
            it[given] = fromStats.given + 1
            it[updatedAt] = now()
        }
        RecognitionStats.update({ RecognitionStats.id eq targetUid }) {
            it[received] = targetStats.received + 1
            it[updatedAt] = now()
        }
        true
    }

    fun pruneRecognitionDaily(today: String) = transaction {
        RecognitionDaily.deleteWhere { RecognitionDaily.date neq today }
    }

    // Titles
    private fun ResultRow.toTitleDefinitionRecord() = TitleDefinitionRecord(
        code = get(TitleDefinitions.id).value,
        displayName = get(TitleDefinitions.displayName),
        description = get(TitleDefinitions.description),
    )

    fun getTitleDefinition(code: String): TitleDefinitionRecord? = transaction {
        TitleDefinitions.selectAll().where { TitleDefinitions.id eq code }.firstOrNull()?.toTitleDefinitionRecord()
    }

    fun upsertTitleDefinition(code: String, displayName: String, description: String = ""): Boolean = transaction {
        val old = TitleDefinitions.selectAll().where { TitleDefinitions.id eq code }.firstOrNull()
        if (old == null) {
            TitleDefinitions.insert {
                it[id] = code
                it[TitleDefinitions.displayName] = displayName
                it[TitleDefinitions.description] = description
            }
            true
        } else {
            val changed = old[TitleDefinitions.displayName] != displayName || old[TitleDefinitions.description] != description
            if (changed) {
                TitleDefinitions.update({ TitleDefinitions.id eq code }) {
                    it[TitleDefinitions.displayName] = displayName
                    it[TitleDefinitions.description] = description
                    it[updatedAt] = now()
                }
            }
            changed
        }
    }

    fun playerOwnedTitles(uid: String): Set<String> = transaction {
        PlayerTitles.selectAll().where { PlayerTitles.uid eq uid }
            .mapTo(linkedSetOf()) { it[PlayerTitles.titleCode] }
    }

    fun grantTitle(uid: String, code: String, source: String = ""): Boolean = transaction {
        val old = PlayerTitles.selectAll().where {
            (PlayerTitles.uid eq uid) and (PlayerTitles.titleCode eq code)
        }.firstOrNull()
        if (old != null) return@transaction false
        PlayerTitles.insert {
            it[PlayerTitles.uid] = uid
            it[titleCode] = code
            it[PlayerTitles.sourceTag] = source.ifBlank { null }
        }
        true
    }

    fun revokeTitle(uid: String, code: String): Boolean = transaction {
        val removed = PlayerTitles.deleteWhere { (PlayerTitles.uid eq uid) and (PlayerTitles.titleCode eq code) } > 0
        if (removed) {
            EquippedTitles.deleteWhere { (EquippedTitles.id eq uid) and (EquippedTitles.titleCode eq code) }
        }
        removed
    }

    fun getEquippedTitle(uid: String): String? = transaction {
        EquippedTitles.selectAll().where { EquippedTitles.id eq uid }.firstOrNull()?.get(EquippedTitles.titleCode)
    }

    fun setEquippedTitle(uid: String, code: String) = transaction {
        val old = EquippedTitles.selectAll().where { EquippedTitles.id eq uid }.firstOrNull()
        if (old == null) {
            EquippedTitles.insert {
                it[id] = uid
                it[titleCode] = code
            }
        } else {
            EquippedTitles.update({ EquippedTitles.id eq uid }) {
                it[titleCode] = code
                it[updatedAt] = now()
            }
        }
    }

    fun clearEquippedTitle(uid: String) = transaction {
        EquippedTitles.deleteWhere { EquippedTitles.id eq uid }
    }

    // Shop / title shop
    private fun ResultRow.toTitleShopItemRecord() = TitleShopItemRecord(
        id = get(TitleShopItems.id).value,
        titleContent = get(TitleShopItems.titleContent),
        price = get(TitleShopItems.price),
        requiredLevelCode = get(TitleShopItems.requiredLevelCode),
        requiredRecognitions = get(TitleShopItems.requiredRecognitions),
        enabled = get(TitleShopItems.enabled),
    )

    fun titleShopItems(includeDisabled: Boolean = false): List<TitleShopItemRecord> = transaction {
        val rows = TitleShopItems.selectAll()
        rows.filter { includeDisabled || it[TitleShopItems.enabled] }
            .map { it.toTitleShopItemRecord() }
    }

    fun getTitleShopItem(id: String): TitleShopItemRecord? = transaction {
        TitleShopItems.selectAll().where { TitleShopItems.id eq id }.firstOrNull()?.toTitleShopItemRecord()
    }

    fun upsertTitleShopItem(
        id: String,
        titleContent: String,
        price: Int,
        requiredLevelCode: String,
        requiredRecognitions: Int,
        enabled: Boolean = true,
    ): Boolean = transaction {
        val old = TitleShopItems.selectAll().where { TitleShopItems.id eq id }.firstOrNull()
        if (old == null) {
            TitleShopItems.insert {
                it[TitleShopItems.id] = id
                it[TitleShopItems.titleContent] = titleContent
                it[TitleShopItems.price] = price.coerceAtLeast(0)
                it[TitleShopItems.requiredLevelCode] = requiredLevelCode
                it[TitleShopItems.requiredRecognitions] = requiredRecognitions.coerceAtLeast(0)
                it[TitleShopItems.enabled] = enabled
            }
            true
        } else {
            val changed = old[TitleShopItems.titleContent] != titleContent ||
                    old[TitleShopItems.price] != price.coerceAtLeast(0) ||
                    old[TitleShopItems.requiredLevelCode] != requiredLevelCode ||
                    old[TitleShopItems.requiredRecognitions] != requiredRecognitions.coerceAtLeast(0) ||
                    old[TitleShopItems.enabled] != enabled
            if (changed) {
                TitleShopItems.update({ TitleShopItems.id eq id }) {
                    it[TitleShopItems.titleContent] = titleContent
                    it[TitleShopItems.price] = price.coerceAtLeast(0)
                    it[TitleShopItems.requiredLevelCode] = requiredLevelCode
                    it[TitleShopItems.requiredRecognitions] = requiredRecognitions.coerceAtLeast(0)
                    it[TitleShopItems.enabled] = enabled
                    it[updatedAt] = now()
                }
            }
            changed
        }
    }

    fun deleteTitleShopItem(id: String): Boolean = transaction {
        TitleShopItems.deleteWhere { TitleShopItems.id eq id } > 0
    }

    fun titleShopIsEmpty(): Boolean = transaction {
        TitleShopItems.selectAll().empty()
    }

    fun recordShopPurchase(uid: String, shopCode: String, itemId: String): Int = transaction {
        val row = ShopPurchaseStats.selectAll().where {
            (ShopPurchaseStats.uid eq uid) and
                    (ShopPurchaseStats.shopCode eq shopCode) and
                    (ShopPurchaseStats.itemId eq itemId)
        }.firstOrNull()
        if (row == null) {
            ShopPurchaseStats.insert {
                it[ShopPurchaseStats.uid] = uid
                it[ShopPurchaseStats.shopCode] = shopCode
                it[ShopPurchaseStats.itemId] = itemId
                it[count] = 1
            }
            1
        } else {
            val newCount = row[ShopPurchaseStats.count] + 1
            ShopPurchaseStats.update({
                (ShopPurchaseStats.uid eq uid) and
                        (ShopPurchaseStats.shopCode eq shopCode) and
                        (ShopPurchaseStats.itemId eq itemId)
            }) {
                it[count] = newCount
                it[updatedAt] = now()
            }
            newCount
        }
    }

    fun shopPurchaseCount(uid: String, shopCode: String, itemId: String? = null): Int = transaction {
        val rows = ShopPurchaseStats.selectAll().where {
            if (itemId == null) {
                (ShopPurchaseStats.uid eq uid) and (ShopPurchaseStats.shopCode eq shopCode)
            } else {
                (ShopPurchaseStats.uid eq uid) and
                        (ShopPurchaseStats.shopCode eq shopCode) and
                        (ShopPurchaseStats.itemId eq itemId)
            }
        }
        rows.sumOf { it[ShopPurchaseStats.count] }
    }

    // Player skills
    private fun ResultRow.toPlayerSkillRecord() = PlayerSkillRecord(
        uid = get(PlayerSkills.uid),
        skillCode = get(PlayerSkills.skillCode),
        sourceTag = get(PlayerSkills.sourceTag),
    )

    fun playerSkills(uid: String): List<PlayerSkillRecord> = transaction {
        PlayerSkills.selectAll().where { PlayerSkills.uid eq uid }
            .map { it.toPlayerSkillRecord() }
    }

    fun playerOwnedSkillCodes(uid: String): Set<String> = transaction {
        PlayerSkills.selectAll().where { PlayerSkills.uid eq uid }
            .mapTo(linkedSetOf()) { it[PlayerSkills.skillCode] }
    }

    fun hasPlayerSkill(uid: String, skillCode: String): Boolean = transaction {
        !PlayerSkills.selectAll().where {
            (PlayerSkills.uid eq uid) and (PlayerSkills.skillCode eq skillCode)
        }.empty()
    }

    fun grantPlayerSkill(uid: String, skillCode: String, source: String = ""): Boolean = transaction {
        if (hasPlayerSkill(uid, skillCode)) return@transaction false
        PlayerSkills.insert {
            it[PlayerSkills.uid] = uid
            it[PlayerSkills.skillCode] = skillCode
            it[sourceTag] = source.take(120)
        }
        true
    }

    fun revokePlayerSkill(uid: String, skillCode: String): Boolean = transaction {
        PlayerSkills.deleteWhere { (PlayerSkills.uid eq uid) and (PlayerSkills.skillCode eq skillCode) } > 0
    }

    // Achievements
    fun completedAchievements(uid: String): Set<String> = transaction {
        PlayerAchievements.selectAll().where { PlayerAchievements.uid eq uid }
            .mapTo(linkedSetOf()) { it[PlayerAchievements.achievementCode] }
    }

    fun isAchievementCompleted(uid: String, code: String): Boolean = transaction {
        !PlayerAchievements.selectAll().where {
            (PlayerAchievements.uid eq uid) and (PlayerAchievements.achievementCode eq code)
        }.empty()
    }

    fun completeAchievement(uid: String, code: String, source: String = ""): Boolean = transaction {
        if (isAchievementCompleted(uid, code)) return@transaction false
        PlayerAchievements.insert {
            it[PlayerAchievements.uid] = uid
            it[achievementCode] = code
            it[PlayerAchievements.sourceTag] = source.ifBlank { null }
        }
        true
    }

    fun revokeAchievement(uid: String, code: String): Boolean = transaction {
        PlayerAchievements.deleteWhere { (PlayerAchievements.uid eq uid) and (PlayerAchievements.achievementCode eq code) } > 0
    }

    private fun ResultRow.toCustomAchievementRecord() = CustomAchievementRecord(
        code = get(CustomAchievements.id).value,
        name = get(CustomAchievements.name),
        enabled = get(CustomAchievements.enabled),
        hidden = get(CustomAchievements.hidden),
        conditionType = get(CustomAchievements.conditionType),
        conditionValue = get(CustomAchievements.conditionValue),
        rewardPoints = get(CustomAchievements.rewardPoints),
        titleCode = get(CustomAchievements.titleCode),
        titleDisplay = get(CustomAchievements.titleDisplay),
        updatedAt = get(CustomAchievements.updatedAt),
    )

    fun listCustomAchievements(includeDisabled: Boolean = false): List<CustomAchievementRecord> = transaction {
        val query = if (includeDisabled) {
            CustomAchievements.selectAll()
        } else {
            CustomAchievements.selectAll().where { CustomAchievements.enabled eq true }
        }
        query
            .orderBy(CustomAchievements.id to SortOrder.ASC)
            .map { it.toCustomAchievementRecord() }
    }

    fun getCustomAchievement(code: String): CustomAchievementRecord? = transaction {
        CustomAchievements.selectAll().where { CustomAchievements.id eq code }
            .firstOrNull()
            ?.toCustomAchievementRecord()
    }

    fun upsertCustomAchievement(record: CustomAchievementRecord): CustomAchievementRecord = transaction {
        val fixedCode = record.code.take(CODE_LENGTH)
        val fixedName = record.name.take(96).ifBlank { fixedCode }
        val fixedConditionType = record.conditionType.take(48)
        val fixedConditionValue = record.conditionValue.take(128)
        val fixedTitleCode = record.titleCode?.take(CODE_LENGTH)?.ifBlank { null }
        val fixedTitleDisplay = record.titleDisplay?.take(160)?.ifBlank { null }
        val exists = !CustomAchievements.selectAll().where { CustomAchievements.id eq fixedCode }.empty()
        if (exists) {
            CustomAchievements.update({ CustomAchievements.id eq fixedCode }) {
                it[name] = fixedName
                it[enabled] = record.enabled
                it[hidden] = record.hidden
                it[conditionType] = fixedConditionType
                it[conditionValue] = fixedConditionValue
                it[rewardPoints] = record.rewardPoints.coerceAtLeast(0)
                it[titleCode] = fixedTitleCode
                it[titleDisplay] = fixedTitleDisplay
                it[updatedAt] = now()
            }
        } else {
            CustomAchievements.insert {
                it[id] = fixedCode
                it[name] = fixedName
                it[enabled] = record.enabled
                it[hidden] = record.hidden
                it[conditionType] = fixedConditionType
                it[conditionValue] = fixedConditionValue
                it[rewardPoints] = record.rewardPoints.coerceAtLeast(0)
                it[titleCode] = fixedTitleCode
                it[titleDisplay] = fixedTitleDisplay
                it[updatedAt] = now()
            }
        }
        CustomAchievements.selectAll().where { CustomAchievements.id eq fixedCode }.first().toCustomAchievementRecord()
    }

    fun deleteCustomAchievement(code: String): Boolean = transaction {
        CustomAchievements.deleteWhere { CustomAchievements.id eq code } > 0
    }

    fun getAchievementStatsSnapshot(uid: String): AchievementStatsSnapshot = transaction {
        val trust = TrustProfiles.selectAll().where { TrustProfiles.id eq uid }.firstOrNull()
        val seniority = SeniorityProfiles.selectAll().where { SeniorityProfiles.id eq uid }.firstOrNull()
        ensureForumStatsInitializedInTx()
        val forumPosts = ForumAuthorStats.selectAll().where { ForumAuthorStats.id eq uid }
            .firstOrNull()
            ?.get(ForumAuthorStats.totalPosts)
            ?: ForumPosts.selectAll().where { ForumPosts.authorUid eq uid }.count().toInt()
        AchievementStatsSnapshot(
            points = trust?.toTrustPointCounts() ?: TrustPointCounts(),
            reputation = ReputationStats.selectAll().where { ReputationStats.id eq uid }.firstOrNull()?.toReputationCounts()
                ?: ReputationCounts(),
            recognition = RecognitionStats.selectAll().where { RecognitionStats.id eq uid }.firstOrNull()?.toRecognitionCounts()
                ?: RecognitionCounts(),
            playMillis = seniority?.get(SeniorityProfiles.playMillis) ?: 0L,
            forumPosts = forumPosts,
            trustLevelCode = trust?.get(TrustProfiles.manualLevelCode),
            seniorityLevelCode = seniority?.get(SeniorityProfiles.levelCode),
        )
    }

    // Random forms
    fun getActiveRandomForm(uid: String): String? = transaction {
        RandomForms.selectAll().where { RandomForms.id eq uid }.firstOrNull()?.get(RandomForms.activeForm)
    }

    fun setActiveRandomForm(uid: String, code: String) = transaction {
        ensureRandomForm(uid)
        RandomForms.update({ RandomForms.id eq uid }) {
            it[activeForm] = code
            it[updatedAt] = now()
        }
    }

    fun clearActiveRandomForm(uid: String) = transaction {
        val old = RandomForms.selectAll().where { RandomForms.id eq uid }.firstOrNull() ?: return@transaction
        if (old[RandomForms.dailyRewardDate] == null) {
            RandomForms.deleteWhere { RandomForms.id eq uid }
        } else {
            RandomForms.update({ RandomForms.id eq uid }) {
                it[activeForm] = null
                it[updatedAt] = now()
            }
        }
    }

    fun getRandomFormRewardDate(uid: String): String? = transaction {
        RandomForms.selectAll().where { RandomForms.id eq uid }.firstOrNull()?.get(RandomForms.dailyRewardDate)
    }

    fun setRandomFormRewardDate(uid: String, date: String) = transaction {
        ensureRandomForm(uid)
        RandomForms.update({ RandomForms.id eq uid }) {
            it[dailyRewardDate] = date
            it[updatedAt] = now()
        }
    }

    // Forum / posts
    private const val FORUM_STATS_INITIALIZED_KEY = "forum.stats.initialized.v1"
    private const val FORUM_TOTAL_POSTS_KEY = "forum.stats.totalPosts"
    private const val FORUM_LOCKED_POST_IDS_KEY = "forum.lockedPostIds"
    private const val FORUM_PROTECTED_POST_IDS_KEY = "forum.protectedPostIds"

    private fun ResultRow.toForumSectionRecord() = ForumSectionRecord(
        code = get(ForumSections.id).value,
        name = get(ForumSections.name),
        description = get(ForumSections.description),
        sortOrder = get(ForumSections.sortOrder),
        enabled = get(ForumSections.enabled),
    )

    private fun ResultRow.toForumPostRecord() = ForumPostRecord(
        id = get(ForumPosts.id).value,
        sectionCode = get(ForumPosts.sectionCode),
        authorUid = get(ForumPosts.authorUid),
        authorName = get(ForumPosts.authorName),
        title = get(ForumPosts.title),
        body = get(ForumPosts.body),
        pinned = get(ForumPosts.pinned),
        status = get(ForumPosts.status),
        commentCount = get(ForumPosts.commentCount),
        authorLikeClicks = get(ForumPosts.authorLikeClicks),
        authorDislikeClicks = get(ForumPosts.authorDislikeClicks),
        createdAt = get(ForumPosts.createdAt),
        updatedAt = get(ForumPosts.updatedAt),
    )

    private fun ResultRow.toForumCommentRecord() = ForumCommentRecord(
        id = get(ForumComments.id).value,
        postId = get(ForumComments.post).value,
        authorUid = get(ForumComments.authorUid),
        authorName = get(ForumComments.authorName),
        body = get(ForumComments.body),
        status = get(ForumComments.status),
        createdAt = get(ForumComments.createdAt),
    )

    private fun lockedForumPostIdsInTx(): Set<Int> =
        Settings.selectAll().where { Settings.id eq FORUM_LOCKED_POST_IDS_KEY }
            .firstOrNull()
            ?.get(Settings.value)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    private fun setLockedForumPostIdsInTx(ids: Set<Int>) {
        setSettingInTx(FORUM_LOCKED_POST_IDS_KEY, ids.sorted().joinToString(","))
    }

    private fun protectedForumPostIdsInTx(): Set<Int> =
        Settings.selectAll().where { Settings.id eq FORUM_PROTECTED_POST_IDS_KEY }
            .firstOrNull()
            ?.get(Settings.value)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    private fun setProtectedForumPostIdsInTx(ids: Set<Int>) {
        setSettingInTx(FORUM_PROTECTED_POST_IDS_KEY, ids.sorted().joinToString(","))
    }

    private fun ensureForumStatsInitializedInTx() {
        if (Settings.selectAll().where { Settings.id eq FORUM_STATS_INITIALIZED_KEY }
                .firstOrNull()
                ?.get(Settings.value) == "true"
        ) return

        val countsByAuthor = ForumPosts.selectAll()
            .map { it[ForumPosts.authorUid] }
            .groupingBy { it }
            .eachCount()

        countsByAuthor.forEach { (uid, count) ->
            val old = ForumAuthorStats.selectAll().where { ForumAuthorStats.id eq uid }.firstOrNull()
            if (old == null) {
                ForumAuthorStats.insert {
                    it[id] = uid
                    it[totalPosts] = count
                }
            } else if (old[ForumAuthorStats.totalPosts] < count) {
                ForumAuthorStats.update({ ForumAuthorStats.id eq uid }) {
                    it[totalPosts] = count
                    it[updatedAt] = now()
                }
            }
        }

        val existingTotal = Settings.selectAll().where { Settings.id eq FORUM_TOTAL_POSTS_KEY }
            .firstOrNull()
            ?.get(Settings.value)
            ?.toIntOrNull() ?: 0
        val countedTotal = ForumPosts.selectAll().count().toInt()
        setSettingInTx(FORUM_TOTAL_POSTS_KEY, maxOf(existingTotal, countedTotal).toString())
        setSettingInTx(FORUM_STATS_INITIALIZED_KEY, "true")
    }

    private fun incrementForumPostStatsInTx(authorUid: String): Int {
        ensureForumStatsInitializedInTx()
        val currentTotal = Settings.selectAll().where { Settings.id eq FORUM_TOTAL_POSTS_KEY }
            .firstOrNull()
            ?.get(Settings.value)
            ?.toIntOrNull() ?: 0
        setSettingInTx(FORUM_TOTAL_POSTS_KEY, (currentTotal + 1).toString())

        val old = ForumAuthorStats.selectAll().where { ForumAuthorStats.id eq authorUid }.firstOrNull()
        val newAuthorCount = if (old == null) {
            ForumAuthorStats.insert {
                it[id] = authorUid
                it[totalPosts] = 1
            }
            1
        } else {
            val next = old[ForumAuthorStats.totalPosts] + 1
            ForumAuthorStats.update({ ForumAuthorStats.id eq authorUid }) {
                it[totalPosts] = next
                it[updatedAt] = now()
            }
            next
        }
        return newAuthorCount
    }

    fun ensureForumStatsInitialized() = transaction {
        ensureForumStatsInitializedInTx()
        Unit
    }

    fun getForumStats(): ForumStats = transaction {
        ensureForumStatsInitializedInTx()
        val current = ForumPosts.selectAll().where { ForumPosts.status eq "normal" }.count().toInt()
        val total = Settings.selectAll().where { Settings.id eq FORUM_TOTAL_POSTS_KEY }
            .firstOrNull()
            ?.get(Settings.value)
            ?.toIntOrNull() ?: current
        ForumStats(current, maxOf(total, current))
    }

    fun getForumAuthorPostCount(authorUid: String): Int = transaction {
        ensureForumStatsInitializedInTx()
        val statCount = ForumAuthorStats.selectAll().where { ForumAuthorStats.id eq authorUid }
            .firstOrNull()
            ?.get(ForumAuthorStats.totalPosts) ?: 0
        if (statCount > 0) statCount
        else ForumPosts.selectAll().where { ForumPosts.authorUid eq authorUid }.count().toInt()
    }

    fun isForumPostLocked(id: Int): Boolean = transaction {
        id in lockedForumPostIdsInTx()
    }

    fun lockedForumPostIds(): Set<Int> = transaction {
        lockedForumPostIdsInTx()
    }

    fun isForumPostProtected(id: Int): Boolean = transaction {
        id in protectedForumPostIdsInTx()
    }

    fun protectedForumPostIds(): Set<Int> = transaction {
        protectedForumPostIdsInTx()
    }

    fun setForumPostLocked(id: Int, locked: Boolean): Boolean = transaction {
        val exists = ForumPosts.selectAll().where { (ForumPosts.id eq id) and (ForumPosts.status eq "normal") }.any()
        if (!exists) return@transaction false
        val ids = lockedForumPostIdsInTx().toMutableSet()
        if (locked) ids += id else ids -= id
        setLockedForumPostIdsInTx(ids)
        true
    }

    fun setForumPostProtected(id: Int, protected: Boolean): Boolean = transaction {
        val exists = ForumPosts.selectAll().where { (ForumPosts.id eq id) and (ForumPosts.status eq "normal") }.any()
        if (!exists) return@transaction false
        val ids = protectedForumPostIdsInTx().toMutableSet()
        if (protected) ids += id else ids -= id
        setProtectedForumPostIdsInTx(ids)
        true
    }

    fun listForumSections(includeDisabled: Boolean = false): List<ForumSectionRecord> = transaction {
        ForumSections.selectAll()
            .filter { includeDisabled || it[ForumSections.enabled] }
            .map { it.toForumSectionRecord() }
            .sortedWith(compareBy<ForumSectionRecord> { it.sortOrder }.thenBy { it.code })
    }

    fun getForumSection(code: String): ForumSectionRecord? = transaction {
        ForumSections.selectAll().where { ForumSections.id eq code }.firstOrNull()?.toForumSectionRecord()
    }

    fun upsertForumSection(
        code: String,
        name: String,
        description: String,
        sortOrder: Int = 100,
        enabled: Boolean = true,
    ): Boolean = transaction {
        val fixedCode = code.trim().lowercase().take(CODE_LENGTH)
        val fixedName = name.replace('\n', ' ').replace('\r', ' ').trim().take(64)
        val fixedDescription = description.trim()
        if (fixedCode.isBlank() || fixedName.isBlank()) return@transaction false
        val old = ForumSections.selectAll().where { ForumSections.id eq fixedCode }.firstOrNull()
        if (old == null) {
            ForumSections.insert {
                it[id] = fixedCode
                it[ForumSections.name] = fixedName
                it[ForumSections.description] = fixedDescription
                it[ForumSections.sortOrder] = sortOrder
                it[ForumSections.enabled] = enabled
            }
            true
        } else {
            ForumSections.update({ ForumSections.id eq fixedCode }) {
                it[ForumSections.name] = fixedName
                it[ForumSections.description] = fixedDescription
                it[ForumSections.sortOrder] = sortOrder
                it[ForumSections.enabled] = enabled
                it[updatedAt] = now()
            } > 0
        }
    }

    fun setForumSectionEnabled(code: String, enabled: Boolean): Boolean = transaction {
        ForumSections.update({ ForumSections.id eq code }) {
            it[ForumSections.enabled] = enabled
            it[updatedAt] = now()
        } > 0
    }

    fun createForumPost(sectionCode: String, authorUid: String, authorName: String, title: String, body: String): ForumPostRecord? = transaction {
        ensureForumStatsInitializedInTx()
        val fixedTitle = title.replace('\n', ' ').replace('\r', ' ').trim().take(96)
        val fixedBody = body.trim()
        if (fixedTitle.isBlank() || fixedBody.isBlank()) return@transaction null
        val fixedSectionCode = sectionCode.trim().lowercase().take(CODE_LENGTH).ifBlank { "all" }
        val id = ForumPosts.insertAndGetId {
            it[ForumPosts.sectionCode] = fixedSectionCode
            it[ForumPosts.authorUid] = authorUid
            it[ForumPosts.authorName] = authorName.take(64)
            it[ForumPosts.title] = fixedTitle
            it[ForumPosts.body] = fixedBody
        }.value
        incrementForumPostStatsInTx(authorUid)
        ForumPosts.selectAll().where { ForumPosts.id eq id }.firstOrNull()?.toForumPostRecord()
    }

    fun getForumPost(id: Int): ForumPostRecord? = transaction {
        ForumPosts.selectAll().where { (ForumPosts.id eq id) and (ForumPosts.status eq "normal") }
            .firstOrNull()?.toForumPostRecord()
    }

    fun getForumPostAnyStatus(id: Int): ForumPostRecord? = transaction {
        ForumPosts.selectAll().where { ForumPosts.id eq id }
            .firstOrNull()?.toForumPostRecord()
    }

    fun listForumPosts(sectionCode: String? = null): List<ForumPostRecord> = transaction {
        ForumPosts.selectAll().where {
            if (sectionCode == null || sectionCode == "all") {
                ForumPosts.status eq "normal"
            } else {
                (ForumPosts.status eq "normal") and (ForumPosts.sectionCode eq sectionCode)
            }
        }
            .map { it.toForumPostRecord() }
            .sortedWith(
                compareByDescending<ForumPostRecord> { it.pinned }
                    .thenByDescending { it.updatedAt }
                    .thenByDescending { it.createdAt }
            )
    }

    fun listForumPostsPaged(
        sectionCode: String? = null,
        offset: Int = 0,
        limit: Int = 20,
        excludedSectionCodes: Set<String> = emptySet(),
    ): ForumPostPage = transaction {
        val baseCondition = if (sectionCode == null || sectionCode == "all") {
            ForumPosts.status eq "normal"
        } else {
            (ForumPosts.status eq "normal") and (ForumPosts.sectionCode eq sectionCode)
        }
        val condition = if (sectionCode == null || sectionCode == "all") {
            excludedSectionCodes.fold(baseCondition) { acc, code ->
                acc and (ForumPosts.sectionCode neq code)
            }
        } else {
            baseCondition
        }
        val total = ForumPosts.selectAll().where { condition }.count().toInt()
        val items = ForumPosts.selectAll().where { condition }
            .orderBy(
                ForumPosts.pinned to SortOrder.DESC,
                ForumPosts.updatedAt to SortOrder.DESC,
                ForumPosts.createdAt to SortOrder.DESC,
            )
            .limit(limit.coerceAtLeast(0))
            .offset(offset.coerceAtLeast(0).toLong())
            .map { it.toForumPostRecord() }
        ForumPostPage(items, total)
    }

    private fun leaderboardEntryInTx(uid: String, value: Int): LeaderboardEntry =
        LeaderboardEntry(uid, subjectNameInTx(uid), value)

    fun topCurrentTrustPoints(limit: Int = 10): List<LeaderboardEntry> = transaction {
        TrustProfiles.selectAll()
            .orderBy(TrustProfiles.currentPoints to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[TrustProfiles.id].value, it[TrustProfiles.currentPoints]) }
    }

    fun topTotalTrustPoints(limit: Int = 10): List<LeaderboardEntry> = transaction {
        TrustProfiles.selectAll()
            .orderBy(TrustProfiles.totalPoints to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[TrustProfiles.id].value, it[TrustProfiles.totalPoints]) }
    }

    fun topForumPosts(limit: Int = 10): List<LeaderboardEntry> = transaction {
        ensureForumStatsInitializedInTx()
        ForumAuthorStats.selectAll()
            .orderBy(ForumAuthorStats.totalPosts to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[ForumAuthorStats.id].value, it[ForumAuthorStats.totalPosts]) }
    }

    fun topReceivedLikes(limit: Int = 10): List<LeaderboardEntry> = transaction {
        ReputationStats.selectAll()
            .orderBy(ReputationStats.receivedLikes to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[ReputationStats.id].value, it[ReputationStats.receivedLikes]) }
    }

    fun topReceivedDislikes(limit: Int = 10): List<LeaderboardEntry> = transaction {
        ReputationStats.selectAll()
            .orderBy(ReputationStats.receivedDislikes to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[ReputationStats.id].value, it[ReputationStats.receivedDislikes]) }
    }

    fun topGivenLikes(limit: Int = 10): List<LeaderboardEntry> = transaction {
        ReputationStats.selectAll()
            .orderBy(ReputationStats.givenLikes to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[ReputationStats.id].value, it[ReputationStats.givenLikes]) }
    }

    fun topGivenDislikes(limit: Int = 10): List<LeaderboardEntry> = transaction {
        ReputationStats.selectAll()
            .orderBy(ReputationStats.givenDislikes to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[ReputationStats.id].value, it[ReputationStats.givenDislikes]) }
    }

    fun topReceivedRecognitions(limit: Int = 10): List<LeaderboardEntry> = transaction {
        RecognitionStats.selectAll()
            .orderBy(RecognitionStats.received to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[RecognitionStats.id].value, it[RecognitionStats.received]) }
    }

    fun topGivenRecognitions(limit: Int = 10): List<LeaderboardEntry> = transaction {
        RecognitionStats.selectAll()
            .orderBy(RecognitionStats.given to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { leaderboardEntryInTx(it[RecognitionStats.id].value, it[RecognitionStats.given]) }
    }

    fun countForumPostsByAuthorSince(authorUid: String, since: Instant): Int = transaction {
        ForumPosts.selectAll().where {
            (ForumPosts.authorUid eq authorUid) and
                    (ForumPosts.status eq "normal") and
                    (ForumPosts.createdAt greaterEq since)
        }.count().toInt()
    }

    fun updateForumPost(id: Int, title: String, body: String): Boolean = transaction {
        val fixedTitle = title.replace('\n', ' ').replace('\r', ' ').trim().take(96)
        val fixedBody = body.trim()
        if (fixedTitle.isBlank() || fixedBody.isBlank()) return@transaction false
        ForumPosts.update({ (ForumPosts.id eq id) and (ForumPosts.status eq "normal") }) {
            it[ForumPosts.title] = fixedTitle
            it[ForumPosts.body] = fixedBody
            it[updatedAt] = now()
        } > 0
    }

    fun setForumPostPinned(id: Int, pinned: Boolean): Boolean = transaction {
        ForumPosts.update({ (ForumPosts.id eq id) and (ForumPosts.status eq "normal") }) {
            it[ForumPosts.pinned] = pinned
            it[updatedAt] = now()
        } > 0
    }

    private fun deleteForumPostInTx(id: Int): Boolean {
        ForumComments.deleteWhere { ForumComments.post eq EntityID(id, ForumPosts) }
        val deleted = ForumPosts.deleteWhere { ForumPosts.id eq id } > 0
        if (deleted) {
            val ids = lockedForumPostIdsInTx()
            if (id in ids) setLockedForumPostIdsInTx(ids - id)
            val protectedIds = protectedForumPostIdsInTx()
            if (id in protectedIds) setProtectedForumPostIdsInTx(protectedIds - id)
        }
        return deleted
    }

    fun purgeForumPost(id: Int): Boolean = transaction {
        deleteForumPostInTx(id)
    }

    /** 兼容旧调用：现在普通删除改为软删除，帖子会进入回收站。 */
    fun deleteForumPost(id: Int): Boolean = transaction {
        softDeleteForumPostInTx(id)
    }

    private fun softDeleteForumPostInTx(id: Int): Boolean {
        val updated = ForumPosts.update({ (ForumPosts.id eq id) and (ForumPosts.status eq "normal") }) {
            it[ForumPosts.status] = "deleted"
            it[updatedAt] = now()
        } > 0
        if (updated) {
            val ids = lockedForumPostIdsInTx()
            if (id in ids) setLockedForumPostIdsInTx(ids - id)
        }
        return updated
    }

    fun softDeleteForumPost(id: Int): Boolean = transaction {
        softDeleteForumPostInTx(id)
    }

    fun restoreForumPost(id: Int): Boolean = transaction {
        ForumPosts.update({ (ForumPosts.id eq id) and (ForumPosts.status eq "deleted") }) {
            it[ForumPosts.status] = "normal"
            it[updatedAt] = now()
        } > 0
    }

    fun listDeletedForumPostsPaged(offset: Int = 0, limit: Int = 20): ForumPostPage = transaction {
        val condition = ForumPosts.status eq "deleted"
        val total = ForumPosts.selectAll().where { condition }.count().toInt()
        val items = ForumPosts.selectAll().where { condition }
            .orderBy(ForumPosts.updatedAt to SortOrder.DESC, ForumPosts.createdAt to SortOrder.DESC)
            .limit(limit.coerceAtLeast(0))
            .offset(offset.coerceAtLeast(0).toLong())
            .map { it.toForumPostRecord() }
        ForumPostPage(items, total)
    }

    fun createForumComment(postId: Int, authorUid: String, authorName: String, body: String): ForumCommentRecord? = transaction {
        val post = ForumPosts.selectAll().where { (ForumPosts.id eq postId) and (ForumPosts.status eq "normal") }.firstOrNull()
            ?: return@transaction null
        val fixedBody = body.trim()
        if (fixedBody.isBlank()) return@transaction null
        val id = ForumComments.insertAndGetId {
            it[ForumComments.post] = post[ForumPosts.id]
            it[ForumComments.authorUid] = authorUid
            it[ForumComments.authorName] = authorName.take(64)
            it[ForumComments.body] = fixedBody
        }.value
        ForumPosts.update({ ForumPosts.id eq postId }) {
            it[commentCount] = post[ForumPosts.commentCount] + 1
            it[updatedAt] = now()
        }
        ForumComments.selectAll().where { ForumComments.id eq id }.firstOrNull()?.toForumCommentRecord()
    }

    fun listForumComments(postId: Int): List<ForumCommentRecord> = transaction {
        ForumComments.selectAll().where {
            (ForumComments.post eq EntityID(postId, ForumPosts)) and (ForumComments.status eq "normal")
        }.map { it.toForumCommentRecord() }
            .sortedBy { it.createdAt }
    }

    fun listForumCommentsPaged(postId: Int, offset: Int = 0, limit: Int = 20): ForumCommentPage = transaction {
        val condition = (ForumComments.post eq EntityID(postId, ForumPosts)) and (ForumComments.status eq "normal")
        val total = ForumComments.selectAll().where { condition }.count().toInt()
        val items = ForumComments.selectAll().where { condition }
            .orderBy(ForumComments.createdAt to SortOrder.ASC)
            .limit(limit.coerceAtLeast(0))
            .offset(offset.coerceAtLeast(0).toLong())
            .map { it.toForumCommentRecord() }
        ForumCommentPage(items, total)
    }

    fun incrementForumPostAuthorReaction(postId: Int, type: String): Boolean = transaction {
        val row = ForumPosts.selectAll().where { (ForumPosts.id eq postId) and (ForumPosts.status eq "normal") }.firstOrNull()
            ?: return@transaction false
        ForumPosts.update({ ForumPosts.id eq postId }) {
            if (type == "dislike") it[authorDislikeClicks] = row[ForumPosts.authorDislikeClicks] + 1
            else it[authorLikeClicks] = row[ForumPosts.authorLikeClicks] + 1
            it[updatedAt] = now()
        } > 0
    }

    fun pruneForumPosts(maxNormalPosts: Int, cutoff: Instant): Int = transaction {
        val max = maxNormalPosts.coerceAtLeast(0)
        val lockedIds = lockedForumPostIdsInTx()
        val posts = ForumPosts.selectAll().where {
            (ForumPosts.status eq "normal") and (ForumPosts.pinned eq false)
        }.map { it.toForumPostRecord() }
            .filter { it.id !in lockedIds }
        val overflow = posts.size - max
        if (overflow <= 0) return@transaction 0

        val candidates = posts
            .filter { !it.createdAt.isAfter(cutoff) }
            .sortedWith(
                compareBy<ForumPostRecord> { it.authorLikeClicks - it.authorDislikeClicks }
                    .thenBy { it.commentCount }
                    .thenBy { it.createdAt }
            )
            .take(overflow)

        var removed = 0
        candidates.forEach {
            if (softDeleteForumPostInTx(it.id)) removed++
        }
        removed
    }

    // MDC red packets
    private fun ResultRow.toRedPacketRecord() = RedPacketRecord(
        id = get(RedPackets.id).value,
        senderUid = get(RedPackets.senderUid),
        senderName = get(RedPackets.senderName),
        totalAmount = get(RedPackets.totalAmount),
        remainingAmount = get(RedPackets.remainingAmount),
        totalShares = get(RedPackets.totalShares),
        remainingShares = get(RedPackets.remainingShares),
        message = get(RedPackets.message),
        status = get(RedPackets.status),
        createdAt = get(RedPackets.createdAt),
        expireAt = get(RedPackets.expireAt),
    )

    private fun ResultRow.toRedPacketClaimRecord() = RedPacketClaimRecord(
        id = get(RedPacketClaims.id).value,
        packetId = get(RedPacketClaims.packet).value,
        claimerUid = get(RedPacketClaims.claimerUid),
        claimerName = get(RedPacketClaims.claimerName),
        amount = get(RedPacketClaims.amount),
        claimedAt = get(RedPacketClaims.claimedAt),
    )

    private fun expireRedPacketsInTx(now: Instant = now()): List<RedPacketRecord> {
        val expired = RedPackets.selectAll().where { RedPackets.status eq "active" }
            .map { it.toRedPacketRecord() }
            .filter { !it.expireAt.isAfter(now) }
        expired.forEach { packet ->
            if (packet.remainingAmount > 0) {
                addCurrentTrustPointsInTx(packet.senderUid, packet.remainingAmount)
            }
            RedPackets.update({ RedPackets.id eq packet.id }) {
                it[RedPackets.status] = "expired"
                it[updatedAt] = now
            }
        }
        return expired
    }

    fun expireRedPackets(): List<RedPacketRecord> = transaction {
        expireRedPacketsInTx()
    }

    fun createRedPacket(
        senderUid: String,
        senderName: String,
        totalAmount: Int,
        totalShares: Int,
        message: String,
        expireAt: Instant,
    ): RedPacketRecord? = transaction {
        val amount = totalAmount.coerceAtLeast(0)
        val shares = totalShares.coerceAtLeast(0)
        if (amount <= 0 || shares <= 0 || amount < shares) return@transaction null
        ensureTrustProfile(senderUid)
        val current = TrustProfiles.selectAll().where { TrustProfiles.id eq senderUid }.first()[TrustProfiles.currentPoints]
        if (current < amount) return@transaction null
        addCurrentTrustPointsInTx(senderUid, -amount)
        val packetId = RedPackets.insertAndGetId {
            it[RedPackets.senderUid] = senderUid
            it[RedPackets.senderName] = senderName.take(64)
            it[RedPackets.totalAmount] = amount
            it[RedPackets.remainingAmount] = amount
            it[RedPackets.totalShares] = shares
            it[RedPackets.remainingShares] = shares
            it[RedPackets.message] = message.replace('\n', ' ').replace('\r', ' ').trim().take(120)
            it[RedPackets.status] = "active"
            it[RedPackets.expireAt] = expireAt
        }.value
        RedPackets.selectAll().where { RedPackets.id eq packetId }.firstOrNull()?.toRedPacketRecord()
    }

    fun getRedPacket(id: Int): RedPacketRecord? = transaction {
        expireRedPacketsInTx()
        RedPackets.selectAll().where { RedPackets.id eq id }.firstOrNull()?.toRedPacketRecord()
    }

    fun listRedPackets(limit: Int = 20, includeClosed: Boolean = false): List<RedPacketRecord> = transaction {
        expireRedPacketsInTx()
        val query = if (includeClosed) {
            RedPackets.selectAll()
        } else {
            RedPackets.selectAll().where { RedPackets.status eq "active" }
        }
        query
            .orderBy(RedPackets.createdAt to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .map { it.toRedPacketRecord() }
    }

    fun listRedPacketClaims(packetId: Int): List<RedPacketClaimRecord> = transaction {
        RedPacketClaims.selectAll().where { RedPacketClaims.packet eq EntityID(packetId, RedPackets) }
            .orderBy(RedPacketClaims.claimedAt to SortOrder.DESC)
            .map { it.toRedPacketClaimRecord() }
    }

    fun claimRedPacket(packetId: Int, claimerUid: String, claimerName: String): RedPacketClaimResult = transaction {
        expireRedPacketsInTx()
        val row = RedPackets.selectAll().where { RedPackets.id eq packetId }.firstOrNull()
            ?: return@transaction RedPacketClaimResult(false, "not_found")
        val packet = row.toRedPacketRecord()
        if (packet.senderUid == claimerUid) return@transaction RedPacketClaimResult(false, "self", packet)
        if (packet.status != "active") return@transaction RedPacketClaimResult(false, packet.status, packet)
        if (packet.remainingAmount <= 0 || packet.remainingShares <= 0) return@transaction RedPacketClaimResult(false, "empty", packet)
        val exists = RedPacketClaims.selectAll().where {
            (RedPacketClaims.packet eq EntityID(packetId, RedPackets)) and (RedPacketClaims.claimerUid eq claimerUid)
        }.any()
        if (exists) return@transaction RedPacketClaimResult(false, "claimed", packet)

        val maxAmount = packet.remainingAmount - (packet.remainingShares - 1)
        val amount = if (packet.remainingShares == 1) packet.remainingAmount else Random.nextInt(1, maxAmount + 1)
        val nextAmount = packet.remainingAmount - amount
        val nextShares = packet.remainingShares - 1
        val newStatus = if (nextAmount <= 0 || nextShares <= 0) "finished" else "active"
        val claimId = RedPacketClaims.insertAndGetId {
            it[RedPacketClaims.packet] = EntityID(packetId, RedPackets)
            it[RedPacketClaims.claimerUid] = claimerUid
            it[RedPacketClaims.claimerName] = claimerName.take(64)
            it[RedPacketClaims.amount] = amount
        }.value
        RedPackets.update({ RedPackets.id eq packetId }) {
            it[RedPackets.remainingAmount] = nextAmount
            it[RedPackets.remainingShares] = nextShares
            it[RedPackets.status] = newStatus
            it[updatedAt] = now()
        }
        addCurrentTrustPointsInTx(claimerUid, amount)
        val updatedPacket = RedPackets.selectAll().where { RedPackets.id eq packetId }.first().toRedPacketRecord()
        val claim = RedPacketClaims.selectAll().where { RedPacketClaims.id eq claimId }.first().toRedPacketClaimRecord()
        RedPacketClaimResult(true, "ok", updatedPacket, claim)
    }

    // Mute
    fun getMuteReason(uid: String): String? = transaction {
        MutedPlayers.selectAll().where { MutedPlayers.id eq uid }.firstOrNull()?.get(MutedPlayers.reason)
    }

    fun setMute(uid: String, reason: String, operatorUid: String? = null) = transaction {
        val old = MutedPlayers.selectAll().where { MutedPlayers.id eq uid }.firstOrNull()
        if (old == null) {
            MutedPlayers.insert {
                it[id] = uid
                it[MutedPlayers.reason] = reason
                it[operator] = operatorUid
            }
        } else {
            MutedPlayers.update({ MutedPlayers.id eq uid }) {
                it[MutedPlayers.reason] = reason
                it[operator] = operatorUid
                it[updatedAt] = now()
            }
        }
    }

    fun clearMute(uid: String): Boolean = transaction {
        MutedPlayers.deleteWhere { MutedPlayers.id eq uid } > 0
    }
}
