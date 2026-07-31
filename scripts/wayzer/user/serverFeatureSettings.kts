@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/databaseFeatureSettings", "数据库业务功能开关")
@file:Depends("wayzer/user/trustLevel", "信任等级与4级权限")
@file:Depends("wayzer/user/seniorityLevel", "资历等级")
@file:Depends("coreMindustry/menu", "服务器功能设置菜单")
@file:Depends("coreMindustry/utilTextInput", "服务器功能设置输入框")

package wayzer.user

import coreLibrary.lib.PermissionApi
import coreMindustry.MenuBuilder
import wayzer.lib.DatabaseFeature
import wayzer.lib.DatabaseFeatureSettings
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ServerFeatureSettings
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

name = "服务器功能设置"

private val KEY_MDC_MULTIPLIER = "serverFeatures.mdcSettlementMultiplier"
private val KEY_FORUM_ENABLED = "serverFeatures.forumEnabled"
private val KEY_REGISTER_ONLINE_REQUIREMENT = "serverFeatures.registrationOnlineRequirementEnabled"
private val KEY_SOCIAL_ACTIONS_ENABLED = "serverFeatures.socialActionsEnabled"
private val KEY_DEFAULT_BOUND_LEVEL = "serverFeatures.defaultBoundTrustLevel"
private val KEY_DEFAULT_BOUND_LEVEL_SENIORITY_MIGRATED = "serverFeatures.defaultBoundLevelSeniorityMigrated"
private val LEGACY_TEST_MODE_KEY = "serverTestMode.enabled"
private val FEATURE_KEYS = listOf(
    KEY_MDC_MULTIPLIER,
    KEY_FORUM_ENABLED,
    KEY_REGISTER_ONLINE_REQUIREMENT,
    KEY_SOCIAL_ACTIONS_ENABLED,
    KEY_DEFAULT_BOUND_LEVEL,
    KEY_DEFAULT_BOUND_LEVEL_SENIORITY_MIGRATED,
    LEGACY_TEST_MODE_KEY,
)

private val trustLevel = contextScript<TrustLevel>()
private val seniorityLevel = contextScript<SeniorityLevel>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()

private data class FeatureSettingsLoadResult(
    val legacyTestModeWasEnabled: Boolean,
    val needsSeniorityMigration: Boolean,
)

private fun normalizeDefaultLevel(levelCode: String): String? = when (levelCode.trim().lowercase()) {
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    else -> null
}

private fun defaultLevelOrder(levelCode: String): Int = when (normalizeDefaultLevel(levelCode)) {
    "1" -> 1
    "2" -> 2
    "3" -> 3
    else -> 1
}

private fun normalizeMultiplier(value: Double): Double {
    require(value.isFinite()) { "MDC结算倍率必须是有限数字" }
    return BigDecimal.valueOf(value.coerceIn(0.0, 20.0))
        .setScale(2, RoundingMode.HALF_UP)
        .toDouble()
}

private fun multiplierText(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private class FeatureSettingsService : ServerFeatureSettings {
    @Volatile private var mdcMultiplier = 1.0
    @Volatile private var forum = true
    @Volatile private var registrationOnlineRequirement = true
    @Volatile private var socialActions = true
    @Volatile private var defaultBoundLevel = "1"

    fun loadFromDatabase(): FeatureSettingsLoadResult {
        val values = MdtStorage.getSettings(FEATURE_KEYS)
        mdcMultiplier = values[KEY_MDC_MULTIPLIER]
            ?.toDoubleOrNull()
            ?.let(::normalizeMultiplier)
            ?: 1.0
        forum = values[KEY_FORUM_ENABLED]?.toBooleanStrictOrNull() ?: true
        registrationOnlineRequirement = values[KEY_REGISTER_ONLINE_REQUIREMENT]?.toBooleanStrictOrNull() ?: true
        socialActions = values[KEY_SOCIAL_ACTIONS_ENABLED]?.toBooleanStrictOrNull() ?: true
        defaultBoundLevel = values[KEY_DEFAULT_BOUND_LEVEL]
            ?.let(::normalizeDefaultLevel)
            ?: "1"

        val legacyWasEnabled = values[LEGACY_TEST_MODE_KEY]?.toBooleanStrictOrNull() == true
        val normalized = linkedMapOf<String, String?>(
            KEY_MDC_MULTIPLIER to multiplierText(mdcMultiplier),
            KEY_FORUM_ENABLED to forum.toString(),
            KEY_REGISTER_ONLINE_REQUIREMENT to registrationOnlineRequirement.toString(),
            KEY_SOCIAL_ACTIONS_ENABLED to socialActions.toString(),
            KEY_DEFAULT_BOUND_LEVEL to defaultBoundLevel,
            // 旧测试模式已彻底弃用；保留旧键并强制置 false，兼容旧数据库且阻止旧状态复活。
            LEGACY_TEST_MODE_KEY to "false",
        )
        if (normalized.any { (key, value) -> values[key] != value }) {
            MdtStorage.setSettings(normalized)
        }
        return FeatureSettingsLoadResult(
            legacyTestModeWasEnabled = legacyWasEnabled,
            needsSeniorityMigration = values[KEY_DEFAULT_BOUND_LEVEL_SENIORITY_MIGRATED]
                ?.toBooleanStrictOrNull() != true,
        )
    }

    override fun mdcSettlementMultiplier(): Double = mdcMultiplier
    override fun forumEnabled(): Boolean = forum
    override fun registrationOnlineRequirementEnabled(): Boolean = registrationOnlineRequirement
    override fun socialActionsEnabled(): Boolean = socialActions
    override fun defaultBoundTrustLevelCode(): String = defaultBoundLevel

    override fun setMdcSettlementMultiplier(value: Double): Double {
        val fixed = normalizeMultiplier(value)
        MdtStorage.setSetting(KEY_MDC_MULTIPLIER, multiplierText(fixed))
        mdcMultiplier = fixed
        return fixed
    }

    override fun setForumEnabled(enabled: Boolean): Boolean {
        MdtStorage.setSetting(KEY_FORUM_ENABLED, enabled.toString())
        forum = enabled
        return enabled
    }

    override fun setRegistrationOnlineRequirementEnabled(enabled: Boolean): Boolean {
        MdtStorage.setSetting(KEY_REGISTER_ONLINE_REQUIREMENT, enabled.toString())
        registrationOnlineRequirement = enabled
        return enabled
    }

    override fun setSocialActionsEnabled(enabled: Boolean): Boolean {
        MdtStorage.setSetting(KEY_SOCIAL_ACTIONS_ENABLED, enabled.toString())
        socialActions = enabled
        return enabled
    }

    override fun setDefaultBoundTrustLevelCode(levelCode: String): MdtStorage.BoundTrustLevelUpgradeResult {
        val fixed = normalizeDefaultLevel(levelCode)
            ?: error("已绑定玩家默认等级只能是 1、2 或 3")
        val old = defaultBoundLevel
        if (fixed == old) return MdtStorage.BoundTrustLevelUpgradeResult(fixed)

        // 无论提高还是降低默认值，都确保现有账号至少达到“新的默认值”；存储层只抬升、不降级。
        val result = MdtStorage.setDefaultBoundPlayerLevelAndRaise(KEY_DEFAULT_BOUND_LEVEL, fixed)
        defaultBoundLevel = fixed
        return result
    }

    override fun statusText(): String = """
        |[cyan]结算MDC倍率：[white]×${multiplierText(mdcMultiplier)} [gray](范围0~20，仅4级可改)
        |[cyan]帖子系统：[white]${if (forum) "[green]开启" else "[red]关闭"}
        |[cyan]注册一小时要求：[white]${if (registrationOnlineRequirement) "[green]开启" else "[yellow]关闭"}
        |[cyan]点赞/点踩/认可：[white]${if (socialActions) "[green]开启" else "[red]关闭"}
        |[cyan]已绑定玩家默认等级：[white]$defaultBoundLevel [gray](信任+资历，仅1~3，仅4级可改)
    """.trimMargin()
}

private val featureSettings = FeatureSettingsService()
private val databaseFeatureUpdateMutex = Mutex()

private fun databaseFeatures(): DatabaseFeatureSettings =
    DatabaseFeatureSettings.getOrNull() ?: error("数据库业务功能设置尚未就绪")

private fun databaseFeatureStateText(feature: DatabaseFeature): String {
    val settings = databaseFeatures()
    return when {
        settings.enabled(feature) -> "[green]开启"
        !settings.masterEnabled() && settings.configuredEnabled(feature) -> "[gray]总开关暂停"
        else -> "[red]关闭"
    }
}

private fun canManageServerFeatures(player: Player?): Boolean =
    player == null || with(trustLevel) { isTrustAdmin(player) }

private data class OnlineLevelSnapshot(
    val trust: String,
    val seniority: String,
)

private fun currentOnlineLevelSnapshots(): Map<String, OnlineLevelSnapshot> =
    Groups.player.associate { online ->
        val uid = PlayerData[online].id
        uid to OnlineLevelSnapshot(
            trust = with(trustLevel) { getTrustLevelCode(uid, online) },
            seniority = with(seniorityLevel) { getSeniorityLevelCode(uid, online) },
        )
    }

private fun refreshPlayerLevelsAfterDefaultChange(oldOnlineLevels: Map<String, OnlineLevelSnapshot>) {
    // 清空当前进程内的信任/资历缓存即可；无需让存储层把生产库中可能很大的
    // 变更 UID 列表搬回游戏线程。在线玩家数量有界，只为实际发生显示变化的人发送事件。
    with(trustLevel) { clearTrustLevelCache() }
    with(seniorityLevel) { clearSeniorityLevelCache() }
    Groups.player.forEach { online ->
        val uid = PlayerData[online].id
        val old = oldOnlineLevels[uid] ?: return@forEach
        val newTrust = with(trustLevel) { getTrustLevelCode(uid, online) }
        val newSeniority = with(seniorityLevel) { getSeniorityLevelCode(uid, online) }
        if (old.trust != newTrust) with(trustLevel) { emitTrustLevelChanged(uid, old.trust, newTrust) }
        if (old.seniority != newSeniority) {
            with(seniorityLevel) { emitSeniorityLevelChanged(uid, old.seniority, newSeniority) }
        }
    }
}

private suspend fun changeDefaultBoundLevel(levelCode: String): MdtStorage.BoundTrustLevelUpgradeResult {
    val oldOnlineLevels = currentOnlineLevelSnapshots()
    val result = withContext(Dispatchers.IO) { featureSettings.setDefaultBoundTrustLevelCode(levelCode) }
    refreshPlayerLevelsAfterDefaultChange(oldOnlineLevels)
    return result
}

private fun defaultLevelChangeSummary(result: MdtStorage.BoundTrustLevelUpgradeResult): String =
    "扫描账号 ${result.scannedAccounts}；" +
            "信任资料新建 ${result.insertedProfiles}、提升 ${result.upgradedProfiles}；" +
            "资历资料新建 ${result.insertedSeniorityProfiles}、提升 ${result.upgradedSeniorityProfiles}。"

private suspend fun confirmDefaultLevelChange(player: Player, levelCode: String): Boolean {
    var confirmed = false
    val old = featureSettings.defaultBoundTrustLevelCode()
    MenuBuilder<Unit>("确认修改默认等级") {
        msg = """
            |[yellow]已绑定玩家默认等级：[white]$old[yellow] -> [gold]$levelCode
            |
            |${if (defaultLevelOrder(levelCode) > defaultLevelOrder(old))
                "[red]提高默认等级会在单个数据库事务中扫描所有账号，并把低于新值的信任等级和资历等级一同提升到 $levelCode 级。"
            else
                "[gray]降低默认等级不会批量降级现有玩家；仍会把极少数低于新值的信任/资历资料抬到新默认等级。"}
            |[gray]信任3+、3++、4级不会由此功能授予；资历4级也不会自动授予。
        """.trimMargin()
        option("[red]确认修改") { confirmed = true }
        option("取消") {}
    }.sendTo(player, 60_000)
    return confirmed
}

private suspend fun openServerFeatureMenu(player: Player) {
    if (!canManageServerFeatures(player)) {
        player.sendMessage("[red]权限不足：只有已登录的4级玩家可以打开服务器功能设置。")
        return
    }
    MenuBuilder<Unit>("服务器功能设置") {
        msg = featureSettings.statusText() +
                "\n[cyan]数据库业务总开关：[white]${if (databaseFeatures().masterEnabled()) "[green]开启" else "[red]关闭"}" +
                "\n[gray]子开关持久化到 MdtSettings；数据库业务总开关单独保存到 ScriptAgent 配置。"
        option("结算MDC倍率\n[gray]当前×${multiplierText(featureSettings.mdcSettlementMultiplier())}") {
            val input = with(textInput) {
                textInput(
                    player,
                    "修改结算MDC倍率",
                    "请输入0~20之间的倍率，最多保留两位小数。\n0表示本局结算不发放MDC；管理员技能的本局翻倍会在此倍率上继续×2。",
                    default = multiplierText(featureSettings.mdcSettlementMultiplier()),
                    lengthLimit = 8,
                    timeoutMillis = 60_000,
                )
            }?.trim()
            val value = input?.toDoubleOrNull()
            if (value == null || !value.isFinite() || value !in 0.0..20.0) {
                player.sendMessage("[red]倍率格式错误，需要0~20之间的数字。")
            } else {
                val fixed = withContext(Dispatchers.IO) { featureSettings.setMdcSettlementMultiplier(value) }
                broadcast("[yellow]服务器结算MDC倍率已由 [white]${player.name}[yellow] 修改为 [gold]×${multiplierText(fixed)}".with())
            }
            openServerFeatureMenu(player)
        }
        option("帖子系统\n${if (featureSettings.forumEnabled()) "[green]开启" else "[red]关闭"}") {
            val enabled = withContext(Dispatchers.IO) { featureSettings.setForumEnabled(!featureSettings.forumEnabled()) }
            broadcast("[yellow]帖子系统已由 [white]${player.name}[yellow] ${if (enabled) "[green]开启" else "[red]关闭"}".with())
            openServerFeatureMenu(player)
        }
        newRow()
        option("注册一小时要求\n${if (featureSettings.registrationOnlineRequirementEnabled()) "[green]开启" else "[yellow]关闭"}") {
            val enabled = withContext(Dispatchers.IO) {
                featureSettings.setRegistrationOnlineRequirementEnabled(!featureSettings.registrationOnlineRequirementEnabled())
            }
            broadcast("[yellow]注册一小时在线要求已由 [white]${player.name}[yellow] ${if (enabled) "开启" else "关闭"}".with())
            openServerFeatureMenu(player)
        }
        option("点赞/点踩/认可\n${if (featureSettings.socialActionsEnabled()) "[green]开启" else "[red]关闭"}") {
            val enabled = withContext(Dispatchers.IO) { featureSettings.setSocialActionsEnabled(!featureSettings.socialActionsEnabled()) }
            broadcast("[yellow]点赞/点踩/认可功能已由 [white]${player.name}[yellow] ${if (enabled) "[green]开启" else "[red]关闭"}".with())
            openServerFeatureMenu(player)
        }
        newRow()
        option("已绑定默认等级\n[gold]${featureSettings.defaultBoundTrustLevelCode()}级 [gray](信任+资历)") {
            val input = with(textInput) {
                textInput(
                    player,
                    "修改已绑定玩家默认等级",
                    "请输入 1、2 或 3。修改后会同时批量提升低于新值的信任与资历等级；不会批量降级任何现有玩家。",
                    default = featureSettings.defaultBoundTrustLevelCode(),
                    lengthLimit = 1,
                    isNumeric = true,
                    timeoutMillis = 60_000,
                )
            }?.trim()
            val level = input?.let(::normalizeDefaultLevel)
            if (level == null) {
                player.sendMessage("[red]默认等级只能是1、2或3。")
            } else if (level != featureSettings.defaultBoundTrustLevelCode() && confirmDefaultLevelChange(player, level)) {
                val result = changeDefaultBoundLevel(level)
                broadcast(
                    ("[yellow]已绑定玩家默认等级已由 [white]${player.name}[yellow] 修改为 [gold]${result.levelCode}级[yellow]；" +
                            defaultLevelChangeSummary(result)).with()
                )
            }
            openServerFeatureMenu(player)
        }
        option("数据库业务功能\n${if (databaseFeatures().masterEnabled()) "[green]总开关开启" else "[red]总开关关闭"}") {
            openDatabaseFeatureMenu(player)
        }
        newRow()
        option("刷新") { openServerFeatureMenu(player) }
        option("关闭") {}
    }.sendTo(player, 120_000)
}

private suspend fun openDatabaseFeatureMenu(player: Player) {
    if (!canManageServerFeatures(player)) {
        player.sendMessage("[red]权限不足：只有已登录的4级玩家可以管理数据库业务功能。")
        return
    }
    val settings = databaseFeatures()
    MenuBuilder<Unit>("数据库业务功能") {
        msg = settings.statusText() +
                "\n[gray]关闭总开关只暂停本页业务，不关闭数据库连接，也不影响账号、权限、封禁、风控与性能保护。"
        option("业务总开关\n${if (settings.masterEnabled()) "[green]开启" else "[red]关闭"}") {
            updateDatabaseBusinessMaster(!settings.masterEnabled(), player.name)
            openDatabaseFeatureMenu(player)
        }
        option("刷新") { openDatabaseFeatureMenu(player) }
        newRow()
        DatabaseFeature.entries.chunked(2).forEach { row ->
            row.forEach { feature ->
                option("${feature.displayName}\n${databaseFeatureStateText(feature)}") {
                    updateDatabaseFeature(feature, !settings.configuredEnabled(feature), player.name)
                    openDatabaseFeatureMenu(player)
                }
            }
            if (row.size == 1) option("") { openDatabaseFeatureMenu(player) }
            newRow()
        }
        option("返回服务器功能") { openServerFeatureMenu(player) }
        option("关闭") {}
    }.sendTo(player, 120_000)
}

private fun parseFeatureToggle(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
    "on", "true", "1", "open", "enable", "开启", "开" -> true
    "off", "false", "0", "close", "disable", "关闭", "关" -> false
    else -> null
}

private fun featureOperator(player: Player?): String = player?.name ?: "控制台"

private suspend fun updateMdcMultiplier(value: Double, operator: String): Double {
    val fixed = withContext(Dispatchers.IO) { featureSettings.setMdcSettlementMultiplier(value) }
    broadcast("[yellow]服务器结算MDC倍率已由 [white]$operator[yellow] 修改为 [gold]×${multiplierText(fixed)}".with())
    return fixed
}

private suspend fun updateForumEnabled(enabled: Boolean, operator: String) {
    withContext(Dispatchers.IO) { featureSettings.setForumEnabled(enabled) }
    broadcast("[yellow]帖子系统已由 [white]$operator[yellow] ${if (enabled) "[green]开启" else "[red]关闭"}".with())
}

private suspend fun updateRegistrationRequirement(enabled: Boolean, operator: String) {
    withContext(Dispatchers.IO) { featureSettings.setRegistrationOnlineRequirementEnabled(enabled) }
    broadcast("[yellow]注册一小时在线要求已由 [white]$operator[yellow] ${if (enabled) "[green]开启" else "[yellow]关闭"}".with())
}

private suspend fun updateSocialActions(enabled: Boolean, operator: String) {
    withContext(Dispatchers.IO) { featureSettings.setSocialActionsEnabled(enabled) }
    broadcast("[yellow]点赞/点踩/认可功能已由 [white]$operator[yellow] ${if (enabled) "[green]开启" else "[red]关闭"}".with())
}

private suspend fun updateDefaultBoundLevel(level: String, operator: String): MdtStorage.BoundTrustLevelUpgradeResult? {
    if (level == featureSettings.defaultBoundTrustLevelCode()) return null
    val result = changeDefaultBoundLevel(level)
    broadcast(
        ("[yellow]已绑定玩家默认信任/资历等级已由 [white]$operator[yellow] 修改为 [gold]${result.levelCode}级[yellow]；" +
                defaultLevelChangeSummary(result)).with()
    )
    return result
}

private suspend fun updateDatabaseBusinessMaster(enabled: Boolean, operator: String) {
    databaseFeatureUpdateMutex.withLock {
        databaseFeatures().setMasterEnabled(enabled)
    }
    broadcast(
        "[yellow]数据库业务功能总开关已由 [white]$operator[yellow] ${if (enabled) "[green]开启" else "[red]关闭"}".with()
    )
}

private suspend fun updateDatabaseFeature(feature: DatabaseFeature, enabled: Boolean, operator: String) {
    databaseFeatureUpdateMutex.withLock {
        withContext(Dispatchers.IO) { databaseFeatures().setConfiguredEnabled(feature, enabled) }
    }
    broadcast(
        "[yellow]${feature.displayName}已由 [white]$operator[yellow] ${if (enabled) "[green]开启" else "[red]关闭"}".with()
    )
}

private fun databaseFeatureStatusReply(feature: DatabaseFeature): String {
    val settings = databaseFeatures()
    val configured = if (settings.configuredEnabled(feature)) "开启" else "关闭"
    val effective = if (settings.enabled(feature)) "[green]开启" else "[red]关闭"
    val masterSuffix = if (!settings.masterEnabled() && settings.configuredEnabled(feature)) {
        "[gray]（子开关为$configured，当前被总开关暂停）"
    } else "[gray]（子开关为$configured）"
    return "[cyan]${feature.displayName}：[white]$effective$masterSuffix"
}

onEnable {
    ServerFeatureSettings.provide(this, featureSettings)
    val loaded = runCatching { featureSettings.loadFromDatabase() }
    loaded.onFailure { error ->
        logger.warning("服务器功能设置读取失败，暂用安全默认值：${error.message}")
    }
    loaded.getOrNull()?.let { result ->
        if (result.legacyTestModeWasEnabled) {
            logger.warning("旧服务器测试模式数据库开关曾为开启，现已强制写为 false；旧临时测试文件不会自动删除，只会被忽略。")
        }
        if (result.needsSeniorityMigration) {
            val oldOnlineLevels = currentOnlineLevelSnapshots()
            runCatching {
                val migrated = MdtStorage.setDefaultBoundPlayerLevelAndRaise(
                    KEY_DEFAULT_BOUND_LEVEL,
                    featureSettings.defaultBoundTrustLevelCode(),
                )
                MdtStorage.setSetting(KEY_DEFAULT_BOUND_LEVEL_SENIORITY_MIGRATED, "true")
                refreshPlayerLevelsAfterDefaultChange(oldOnlineLevels)
                migrated
            }.onSuccess { migrated ->
                logger.info("已完成旧数据库的默认绑定等级→资历等级一次性兼容迁移：${defaultLevelChangeSummary(migrated)}")
            }.onFailure { error ->
                logger.warning("默认绑定等级的资历兼容迁移失败，未写入完成标记，下次启动会重试：${error.message}")
            }
        }
        logger.info("服务器功能设置已加载：${featureSettings.statusText().replace('\n', ' ')}")
    }
}

command("serverfeatures", "管理指令：查看服务器功能总览/菜单") {
    aliases = listOf("features", "serverconfig", "服务器功能", "功能设置")
    usage = "[status|menu|database]；修改请优先使用各独立管理根指令"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) {
            returnReply("[red]权限不足：只有已登录的4级玩家或控制台可以修改服务器功能设置。".with())
        }
        val operator = featureOperator(player)
        when (arg.firstOrNull()?.lowercase()) {
            null, "menu", "菜单" -> {
                val p = player ?: returnReply((featureSettings.statusText() + "\n" + databaseFeatures().statusText()).with())
                openServerFeatureMenu(p)
            }
            "status", "状态" -> reply((featureSettings.statusText() + "\n" + databaseFeatures().statusText()).with())
            "database", "db", "数据库", "数据库功能" -> {
                val p = player ?: returnReply(databaseFeatures().statusText().with())
                openDatabaseFeatureMenu(p)
            }
            // 保留旧子指令，避免旧运维脚本立即失效；新建独立根指令便于 /help 搜索和自动补全。
            "mdc", "multiplier", "倍率", "结算倍率" -> {
                val value = arg.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..20.0 }
                    ?: returnReply("[red]用法：/mdcmultiplier <0~20>".with())
                updateMdcMultiplier(value, operator)
            }
            "forum", "post", "帖子" -> {
                val enabled = parseFeatureToggle(arg.getOrNull(1))
                    ?: returnReply("[red]用法：/forumtoggle <on|off>".with())
                updateForumEnabled(enabled, operator)
            }
            "registertime", "register", "注册时长", "注册要求" -> {
                val enabled = parseFeatureToggle(arg.getOrNull(1))
                    ?: returnReply("[red]用法：/registerrequirement <on|off>".with())
                updateRegistrationRequirement(enabled, operator)
            }
            "social", "reputation", "赞踩认可" -> {
                val enabled = parseFeatureToggle(arg.getOrNull(1))
                    ?: returnReply("[red]用法：/socialactions <on|off>".with())
                updateSocialActions(enabled, operator)
            }
            "defaultlevel", "default", "默认等级" -> {
                val level = arg.getOrNull(1)?.let(::normalizeDefaultLevel)
                    ?: returnReply("[red]用法：/defaultboundlevel <1|2|3>".with())
                if (updateDefaultBoundLevel(level, operator) == null) {
                    reply("[yellow]已绑定玩家默认信任/资历等级已经是 [white]$level[yellow] 级。".with())
                }
            }
            else -> replyUsage()
        }
    }
}

command("mdcmultiplier", "管理指令：查看/修改玩家结算MDC倍率") {
    aliases = listOf("mdcrate", "mdcsettlement", "结算倍率", "MDC倍率")
    usage = "[0~20]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        if (arg.isEmpty()) returnReply("[cyan]当前结算MDC倍率：[gold]×${multiplierText(featureSettings.mdcSettlementMultiplier())}".with())
        val value = arg[0].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..20.0 }
            ?: returnReply("[red]用法：/mdcmultiplier <0~20>".with())
        updateMdcMultiplier(value, featureOperator(player))
    }
}

command("forumtoggle", "管理指令：查看/开关帖子系统") {
    aliases = listOf("posttoggle", "forumsetting", "帖子开关", "论坛开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        if (arg.isEmpty()) returnReply("[cyan]帖子系统：${if (featureSettings.forumEnabled()) "[green]开启" else "[red]关闭"}".with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/forumtoggle <on|off>".with())
        updateForumEnabled(enabled, featureOperator(player))
    }
}

command("registerrequirement", "管理指令：查看/开关注册前一小时在线要求") {
    aliases = listOf("registertime", "registrationrequirement", "注册要求", "注册时长开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        if (arg.isEmpty()) {
            returnReply("[cyan]注册前一小时在线要求：${if (featureSettings.registrationOnlineRequirementEnabled()) "[green]开启" else "[yellow]关闭"}".with())
        }
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/registerrequirement <on|off>".with())
        updateRegistrationRequirement(enabled, featureOperator(player))
    }
}

command("socialactions", "管理指令：查看/开关点赞、点踩与认可") {
    aliases = listOf("socialtoggle", "reputationtoggle", "赞踩认可开关", "社交功能开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        if (arg.isEmpty()) returnReply("[cyan]点赞/点踩/认可：${if (featureSettings.socialActionsEnabled()) "[green]开启" else "[red]关闭"}".with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/socialactions <on|off>".with())
        updateSocialActions(enabled, featureOperator(player))
    }
}

command("defaultboundlevel", "管理指令：查看/修改已绑定玩家默认信任与资历等级") {
    aliases = listOf("defaultlevel", "bounddefaultlevel", "绑定默认等级", "默认绑定等级")
    usage = "[1|2|3]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        if (arg.isEmpty()) {
            returnReply("[cyan]已绑定玩家默认信任/资历等级：[gold]${featureSettings.defaultBoundTrustLevelCode()}级[gray]（只升不降）".with())
        }
        val level = normalizeDefaultLevel(arg[0]) ?: returnReply("[red]用法：/defaultboundlevel <1|2|3>".with())
        if (updateDefaultBoundLevel(level, featureOperator(player)) == null) {
            reply("[yellow]已绑定玩家默认信任/资历等级已经是 [white]$level[yellow] 级。".with())
        }
    }
}

command("databasefeatures", "管理指令：查看/开关数据库业务功能总开关") {
    aliases = listOf("dbfeatures", "databasebusiness", "数据库功能", "数据库业务开关")
    usage = "[on|off|status|menu]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        when (arg.firstOrNull()?.lowercase()) {
            null, "status", "状态" -> reply(databaseFeatures().statusText().with())
            "menu", "菜单" -> {
                val p = player ?: returnReply(databaseFeatures().statusText().with())
                openDatabaseFeatureMenu(p)
            }
            else -> {
                val enabled = parseFeatureToggle(arg[0])
                    ?: returnReply("[red]用法：/databasefeatures <on|off|status|menu>".with())
                updateDatabaseBusinessMaster(enabled, featureOperator(player))
            }
        }
    }
}

command("playtimerecording", "管理指令：开关玩家在线时长自动记录") {
    aliases = listOf("playtimetoggle", "onlinehours", "在线时长记录", "在线统计开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.PlayTimeRecording
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/playtimerecording <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("achievementtoggle", "管理指令：开关成就系统与自动检测") {
    aliases = listOf("achievementsetting", "成就开关", "成就系统开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.Achievement
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/achievementtoggle <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("wikitoggle", "管理指令：开关数据库Wiki系统") {
    aliases = listOf("wikisetting", "wiki开关", "百科开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.Wiki
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/wikitoggle <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("mdctransfertoggle", "管理指令：开关玩家MDC转账与红包") {
    aliases = listOf("mdcpaytoggle", "redpackettoggle", "转账红包开关", "MDC交易开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.MdcTransferAndRedPacket
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/mdctransfertoggle <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("recentplayerrecording", "管理指令：开关最近玩家记录与离线管理面板") {
    aliases = listOf("recentplayertoggle", "recentrecording", "最近玩家记录", "最近玩家开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.RecentPlayerRecording
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/recentplayerrecording <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("trustpromotiontoggle", "管理指令：开关信任等级自动晋升/降级检测") {
    aliases = listOf("trustautotoggle", "trustpromotion", "信任晋升开关", "信任自动调整")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.TrustPromotion
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/trustpromotiontoggle <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("senioritypromotiontoggle", "管理指令：开关资历等级自动晋升/调整检测") {
    aliases = listOf("seniorityautotoggle", "senioritypromotion", "资历晋升开关", "资历自动调整")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.SeniorityPromotion
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/senioritypromotiontoggle <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("leaderboardtoggle", "管理指令：开关数据库排行榜") {
    aliases = listOf("ranktoggle", "leaderboardsetting", "排行榜开关", "排行开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.Leaderboard
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/leaderboardtoggle <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

command("playerprofilestats", "管理指令：开关玩家信息菜单的数据库资料显示") {
    aliases = listOf("profilestatstoggle", "playerdatatoggle", "玩家资料显示", "资料显示开关")
    usage = "[on|off]"
    permission = "wayzer.admin.serverFeatures"
    body {
        if (!canManageServerFeatures(player)) returnReply("[red]仅已登录的4级玩家或控制台可修改。".with())
        val feature = DatabaseFeature.PlayerProfileStats
        if (arg.isEmpty()) returnReply(databaseFeatureStatusReply(feature).with())
        val enabled = parseFeatureToggle(arg[0]) ?: returnReply("[red]用法：/playerprofilestats <on|off>".with())
        updateDatabaseFeature(feature, enabled, featureOperator(player))
    }
}

PermissionApi.registerDefault("wayzer.admin.serverFeatures", group = "@admin")
