@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/shopList", "商店列表入口")
@file:Depends("wayzer/user/shopCore", "通用商店核心")
@file:Depends("wayzer/user/ext/skills", "技能系统")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/user/seniorityLevel", "资历等级")
@file:Depends("wayzer/ext/playerRecognition", "认可数据")
@file:Depends("coreMindustry/menu", "技能商店菜单")
@file:Depends("wayzer/map/funRuleModes", "临时玩法规则工具")
@file:Depends("wayzer/ext/playerRandomForm", "随机形态")

package wayzer.user.ext

import arc.math.Mathf
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.CommandContext
import coreLibrary.lib.CommandHandler
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.ClientOnly
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Planets
import mindustry.content.StatusEffects
import mindustry.content.UnitTypes
import mindustry.content.Weathers
import mindustry.ctype.ContentType
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.UnitControlCallPacket
import mindustry.gen.Unit as MindustryUnit
import mindustry.type.UnitType
import mindustry.world.Block
import mindustry.world.blocks.defense.Wall
import mindustry.world.blocks.environment.StaticWall
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlin.random.Random

private object SkillShopConfig {
    const val SHOP_CODE = "skill"
    const val FACE_DOCTOR_PENDING_KEY = "skillShop.facephd.pendingBets"
}

private data class SkillShopDefinition(
    val id: String,
    val code: String,
    val displayName: String,
    val description: String,
    val buyPrice: Int,
    val useCost: Int,
    val requiredLevelCode: String = "1",
    val requiredRecognitions: Int = 0,
    val cooldownMillis: Int? = null,
    val oncePerGame: Boolean = false,
    val ignoreNoSkills: Boolean = false,
    val pvpDisabled: Boolean = true,
)

private val shopList = contextScript<wayzer.user.ShopList>()
private val shopCore = contextScript<wayzer.user.ShopCore>()
private val trustPoint = contextScript<wayzer.user.TrustPoint>()
private val seniorityLevel = contextScript<wayzer.user.SeniorityLevel>()
private val recognition = contextScript<wayzer.ext.PlayerRecognition>()
private val funRules = contextScript<wayzer.map.FunRuleModes>()
private val randomForm = contextScript<wayzer.ext.PlayerRandomForm>()

private val skillDefinitions = listOf(
    SkillShopDefinition("1", "radar", "雷达", "开雾300秒", buyPrice = 60, useCost = 4, cooldownMillis = 600_000),
    SkillShopDefinition("2", "fluid", "随机液体", "生成随机2x2液体", buyPrice = 60, useCost = 4, cooldownMillis = 300_000),
    SkillShopDefinition("3", "randomore", "随机矿", "生成随机2x2矿物", buyPrice = 60, useCost = 4, cooldownMillis = 300_000),
    SkillShopDefinition("4", "wallkiller", "粉碎墙壁", "粉碎3x3墙壁/天然墙", buyPrice = 100, useCost = 4, cooldownMillis = 300_000),
    SkillShopDefinition("5", "corezone4", "核心区", "召唤4x4核心区", buyPrice = 120, useCost = 6, cooldownMillis = 1_200_000),
    SkillShopDefinition("6", "betray", "叛变", "随机策反5个非本队单位", buyPrice = 120, useCost = 10, cooldownMillis = 300_000),
    SkillShopDefinition("7", "banme", "BAN自己5分钟", "踢出自己5分钟并获得5 MDC", buyPrice = 1, useCost = 0, ignoreNoSkills = true, pvpDisabled = false),
    SkillShopDefinition("8", "reptile", "爬爬盲盒", "随机召唤T1-T4爬爬", buyPrice = 60, useCost = 2, cooldownMillis = 200_000),
    SkillShopDefinition("9", "teleportation", "传送", "传送10个随机单位到自己位置", buyPrice = 60, useCost = 4, cooldownMillis = 300_000),
    // 10+ 为后续新增商品；保留原1-9避免覆盖已有购买数据。
    SkillShopDefinition("10", "rain", "唤雨", "让当前地图下雨60秒", buyPrice = 10, useCost = 0, cooldownMillis = 240_000, ignoreNoSkills = true, pvpDisabled = false),
    SkillShopDefinition("11", "kickmebutoct", "踢自己送oct", "踢出自己并为本队召唤oct", buyPrice = 60, useCost = 10, oncePerGame = true),
    SkillShopDefinition("12", "imcute", "我很可爱", "为自己添加2 MDC", buyPrice = 1, useCost = 0, oncePerGame = true, ignoreNoSkills = true, pvpDisabled = false),
    SkillShopDefinition("13", "lottery", "抽奖", "随机获得少量或大量MDC", buyPrice = 20, useCost = 5, oncePerGame = true, ignoreNoSkills = true, pvpDisabled = false),
    SkillShopDefinition("14", "randomunit", "随机单位", "从所有可召唤单位中抽一个", buyPrice = 60, useCost = 10, oncePerGame = true),
    SkillShopDefinition("15", "ultirandom", "[purple]终极随机", "谁知道会出现什么呢？", buyPrice = 200, useCost = 66, cooldownMillis = 20_000),
    SkillShopDefinition("16", "missilestorm", "导弹风暴", "5秒内持续召唤随机创伤导弹", buyPrice = 100, useCost = 20, cooldownMillis = 300_000),
    SkillShopDefinition("17", "fishonlyyou", "此生只属鱼你", "召唤一只只属于你的飞行 risso 鱼鱼，会试图跟随你", buyPrice = 10, useCost = 0, requiredLevelCode = "2", ignoreNoSkills = true),
    SkillShopDefinition("18", "missileburst", "导弹连射", "从当前单位朝向连续发射40个合金创伤分裂小导弹", buyPrice = 100, useCost = 10),
    SkillShopDefinition("19", "facephd", "[gold]对面对面读博", "双方下注猜胜负；PVP赌胜队，非PVP赌本局正常完成或失败/换图；发起者额外支付10%手续费", buyPrice = 200, useCost = 0, oncePerGame = true, ignoreNoSkills = true, pvpDisabled = false),
)

private val skillByCode = skillDefinitions.associateBy { it.code }

private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private val SKILL_OWNED_CACHE_TTL_MILLIS = 60_000L

private data class OwnedSkillCacheEntry(
    val codes: Set<String>,
    val loadedAt: Long,
)

private val ownedSkillCache = mutableMapOf<String, OwnedSkillCacheEntry>()

private fun invalidateOwnedSkillCache(playerUid: String) {
    ownedSkillCache.remove(playerUid)
}

private fun loadOwnedSkillCodesCached(playerUid: String, forceRefresh: Boolean = false): Set<String> {
    val now = System.currentTimeMillis()
    val cached = ownedSkillCache[playerUid]
    if (!forceRefresh && cached != null && now - cached.loadedAt <= SKILL_OWNED_CACHE_TTL_MILLIS) {
        return cached.codes
    }
    val codes = MdtStorage.playerOwnedSkillCodes(playerUid)
    ownedSkillCache[playerUid] = OwnedSkillCacheEntry(codes, now)
    return codes
}

private fun uid(player: Player): String = PlayerData[player].id

private fun sortedSkillItems(): List<SkillShopDefinition> = skillDefinitions.sortedWith(
    compareBy<SkillShopDefinition> { it.id.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.id }
)

private fun useLimitText(def: SkillShopDefinition): String {
    val parts = mutableListOf<String>()
    parts += "使用${def.useCost.coerceAtLeast(0)} MDC"
    if (def.oncePerGame) parts += "一局一次"
    def.cooldownMillis?.let { parts += "冷却${it / 1000}秒" }
    if (!def.ignoreNoSkills) parts += "noskill禁用"
    if (def.pvpDisabled) parts += "PVP禁用"
    return parts.joinToString(" / ")
}

private fun compactShopName(def: SkillShopDefinition): String = when (def.code) {
    "corezone4" -> "核心区4x4"
    "teleportation" -> "传送10单位"
    "fishonlyyou" -> "此生只属鱼你"
    else -> def.displayName
}

private fun compactShopDescription(def: SkillShopDefinition): String = when (def.code) {
    "radar" -> "开雾300秒"
    "fluid" -> "随机2x2液体"
    "randomore" -> "随机2x2矿物"
    "wallkiller" -> "粉碎3x3墙壁/天然墙"
    "corezone4" -> "召唤4x4核心区"
    "betray" -> "策反5个非本队单位"
    "banme" -> "自BAN5分钟得5MDC"
    "reptile" -> "随机T1-T4爬爬"
    "teleportation" -> "拉10个随机单位"
    "rain" -> "当前地图下雨60秒"
    "kickmebutoct" -> "自踢并召唤oct"
    "imcute" -> "给自己+2MDC"
    "lottery" -> "随机抽取MDC"
    "randomunit" -> "抽取一个随机单位"
    "ultirandom" -> "随机触发强力效果"
    "missilestorm" -> "5秒随机导弹风暴"
    "fishonlyyou" -> "召唤专属跟随鱼鱼"
    "missileburst" -> "朝向连射40发小导弹"
    "facephd" -> "下注猜胜负，发起者付手续费"
    else -> def.description.take(16)
}

private fun compactShopRule(def: SkillShopDefinition): String {
    val parts = mutableListOf("购${def.buyPrice}", "用${def.useCost}")
    def.cooldownMillis?.let { parts += "冷${it / 1000}s" }
    if (def.oncePerGame) parts += "1局"
    parts += "资${def.requiredLevelCode}"
    if (def.requiredRecognitions > 0) parts += "${def.requiredRecognitions}认"
    return parts.joinToString(" ")
}

private fun hasSkill(player: Player, def: SkillShopDefinition): Boolean =
    def.code in loadOwnedSkillCodesCached(uid(player))

private fun useRequirementError(player: Player, def: SkillShopDefinition): String? {
    val playerUid = uid(player)
    if (def.code !in loadOwnedSkillCodesCached(playerUid)) return "你还没有购买该技能，可在 /skillshop 购买"

    if (!with(seniorityLevel) { hasSeniorityLevel(player, def.requiredLevelCode) }) {
        return "资历等级不足：需要资历 ${def.requiredLevelCode}级"
    }

    if (def.requiredRecognitions > 0) {
        val receivedRecognitions = with(recognition) { playerReceivedRecognitions(playerUid) }
        if (receivedRecognitions < def.requiredRecognitions) {
            return "被认可数不足：需要 ${def.requiredRecognitions}，当前 ${receivedRecognitions}"
        }
    }
    return null
}

private suspend fun storeSkillVisible(player: Player, def: SkillShopDefinition): Boolean =
    useRequirementError(player, def) == null

private fun spendUseCost(player: Player, def: SkillShopDefinition): Boolean {
    val cost = def.useCost.coerceAtLeast(0)
    if (cost <= 0) return true
    SkillCostManager.freeCostSponsor()?.let { sponsor ->
        player.sendMessage("[yellow]本局技能消费由 [white]$sponsor[yellow] 买单，本次免除 [white]$cost MDC")
        return true
    }
    return with(trustPoint) { spendTrustPoints(PlayerData[player].id, cost, "Skill:${def.code}") }
}

private fun prepareUseError(player: Player, def: SkillShopDefinition): String? {
    useRequirementError(player, def)?.let { return it }
    if (!spendUseCost(player, def)) return "MDC不足：使用该技能需要 ${def.useCost} MDC"
    return null
}

private class ShopSkillPrecheck(private val def: SkillShopDefinition) : CommandHandler {
    private val mapDisabled get() = Vars.state.rules.tags.getBool("@noSkills")

    context(CommandContext) override suspend fun handle() {
        ClientOnly.handle()
        val unit = player!!.unit()
        if (player!!.dead() || unit == null || unit.dead) returnReply("[red]死亡状态无法使用技能".with())
        if (!def.ignoreNoSkills && mapDisabled) returnReply("[red]当前地图禁用技能".with())
        if (def.pvpDisabled && Vars.state.rules.pvp) returnReply("[red]当前技能PVP模式禁用".with())
    }
}

private suspend fun buySkill(player: Player, def: SkillShopDefinition) {
    if (hasSkill(player, def)) {
        player.sendMessage("[yellow]你已经拥有该技能，无需重复购买")
        return
    }

    val playerUid = uid(player)
    val currentPoints = with(trustPoint) { getTrustPoints(playerUid) }
    if (currentPoints < def.buyPrice) {
        player.sendMessage("[red]无法购买：MDC不足：需要 ${def.buyPrice}，当前 $currentPoints")
        return
    }
    if (!with(seniorityLevel) { hasSeniorityLevel(player, def.requiredLevelCode) }) {
        player.sendMessage("[red]无法购买：资历等级不足：需要资历 ${def.requiredLevelCode}级")
        return
    }
    if (def.requiredRecognitions > 0) {
        val receivedRecognitions = with(recognition) { playerReceivedRecognitions(playerUid) }
        if (receivedRecognitions < def.requiredRecognitions) {
            player.sendMessage("[red]无法购买：被认可数不足：需要 ${def.requiredRecognitions}，当前 $receivedRecognitions")
            return
        }
    }

    if (!with(shopCore) { completeShopPurchase(player, SkillShopConfig.SHOP_CODE, def.id, def.displayName, def.buyPrice) }) return

    val changed = MdtStorage.grantPlayerSkill(playerUid, def.code, "skillShop:${def.id}")
    invalidateOwnedSkillCache(playerUid)
    if (changed) {
        player.sendMessage("[green]已解锁技能：[white]${def.displayName}[]，可在 [gold]/skill[] -> [gold]特殊/商店技能[] 中使用")
    } else {
        player.sendMessage("[yellow]购买已完成，但技能已存在；如有疑问请联系管理员")
    }
}

private suspend fun openSkillShop(player: Player) {
    val playerUid = uid(player)
    val ownedSkills = db { loadOwnedSkillCodesCached(playerUid) }
    object : PagedMenuBuilder<SkillShopDefinition>(sortedSkillItems(), prePage = 5) {
        override suspend fun renderItem(item: SkillShopDefinition) {
            val owned = if (item.code in ownedSkills) " [green]✓[]" else ""
            option("${item.id}. [cyan]${compactShopName(item)}[]$owned\n[gray]效果：${compactShopDescription(item)}\n[gold]规则：${compactShopRule(item)}") {
                buySkill(player, item)
            }
        }

        override suspend fun build() {
            title = "[yellow]技能商店"
            msg = "[acid]点击商品即可购买；购买/使用要求中的等级均为资历等级。已拥有的技能不会重复扣MDC。"
            super.build()
        }
    }.sendTo(player, 60_000)
}

private fun spawnAround(type: UnitType, player: Player, count: Int, radius: Float = 56f, configure: (MindustryUnit) -> kotlin.Unit = {}) {
    val center = player.unit() ?: return
    repeat(count) {
        val angle = Random.nextFloat() * 360f
        val distance = Random.nextFloat() * radius
        val x = center.x + Mathf.cosDeg(angle) * distance
        val y = center.y + Mathf.sinDeg(angle) * distance
        type.create(player.team()).apply {
            set(x, y)
            configure(this)
            add()
        }
    }
}

private fun setFloorSquare(player: Player, block: Block, xRange: IntRange, yRange: IntRange = xRange) {
    val unit = player.unit() ?: return
    for (x in xRange) for (y in yRange) {
        Vars.world.tile(unit.tileX() + x, unit.tileY() + y)?.setFloorNet(block)
    }
}

private fun setOreSquare(player: Player, block: Block, xRange: IntRange, yRange: IntRange = xRange) {
    val unit = player.unit() ?: return
    for (x in xRange) for (y in yRange) {
        Vars.world.tile(unit.tileX() + x, unit.tileY() + y)?.setOverlayNet(block)
    }
}

private fun randomLiquidFloor(): Block = arrayOf(Blocks.tar, Blocks.slag, Blocks.arkyciteFloor, Blocks.cryofluid, Blocks.water).random()
private fun randomOre(): Block = arrayOf(
    Blocks.oreCopper,
    Blocks.oreLead,
    Blocks.oreScrap,
    Blocks.oreCoal,
    Blocks.oreTitanium,
    Blocks.oreThorium,
    Blocks.oreBeryllium,
    Blocks.oreTungsten,
).random()

private fun randomSummonableUnit(): UnitType? =
    Vars.content.units()
        .filter { !it.hidden && !it.internal && it.constructor != null && it.health > 0f }
        .randomOrNull()

private val missileStormUnitNames = listOf(
    "scathe-missile",
    "scathe-missile-phase",
    "scathe-missile-surge",
    "scathe-missile-surge-split",
)

private fun unitTypeByName(name: String): UnitType? =
    Vars.content.getByName<UnitType>(ContentType.unit, name)?.takeIf { it.constructor != null }

private fun availableMissileStormUnits(): List<UnitType> =
    missileStormUnitNames.mapNotNull(::unitTypeByName)

private val faceDoctorWagers = listOf(2, 10, 25, 50, 200, 500)

private enum class FaceDoctorBetKind {
    PvpTeam,
    GameSuccess,
    GameFailure;

    companion object {
        fun parse(value: String?): FaceDoctorBetKind? =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

private data class FaceDoctorBet(
    val casterUid: String,
    val casterName: String,
    val targetUid: String,
    val targetName: String,
    val wager: Int,
    val fee: Int,
    val kind: FaceDoctorBetKind,
    val team: Team?,
)

private val faceDoctorUsedThisGame = ConcurrentHashMap.newKeySet<String>()
private val faceDoctorPendingBets = ConcurrentHashMap<String, FaceDoctorBet>()

private fun faceDoctorFee(wager: Int): Int = ((wager.coerceAtLeast(1) + 9) / 10).coerceAtLeast(1)
private fun faceDoctorPot(wager: Int): Int = wager.coerceAtLeast(0) * 2

private fun teamDisplay(team: Team): String = "${team.coloredName()}[gray](id:${team.id})[]"
private fun bettableTeam(team: Team): Boolean = team != Team.derelict && team.id != 255

private fun activePvpBetTeams(): List<Team> {
    val teams = linkedSetOf<Team>()
    Team.all.forEach { team ->
        if (bettableTeam(team) && team.active()) teams += team
    }
    Groups.player.forEach { player ->
        val team = player.team()
        if (bettableTeam(team)) teams += team
    }
    return teams.distinctBy { it.id }.sortedBy { it.id }
}

private fun faceDoctorBetText(kind: FaceDoctorBetKind, team: Team? = null): String = when (kind) {
    FaceDoctorBetKind.PvpTeam -> team?.let { "此局游戏 ${teamDisplay(it)} 将赢得胜利" } ?: "PVP胜利队伍未选择"
    FaceDoctorBetKind.GameSuccess -> "本局正常完成/玩家方胜利"
    FaceDoctorBetKind.GameFailure -> "本局失败或中途换图"
}

private fun faceDoctorGamblingBlockedReason(): String? {
    val mode = Vars.state.rules.mode()
    return when {
        Vars.state.rules.editor || mode == Gamemode.editor -> "编辑器模式不允许面对面读博。"
        mode == Gamemode.sandbox -> "沙盒模式不允许面对面读博。"
        else -> null
    }
}

private fun faceDoctorPlayerSideWon(winner: Team): Boolean {
    val rules = Vars.state.rules
    val waveTeam = rules.waveTeam
    val defaultTeam = rules.defaultTeam
    return when {
        waveTeam != defaultTeam && winner == waveTeam -> false
        winner == defaultTeam -> true
        !rules.pvp && winner != Team.derelict -> true
        else -> false
    }
}

private fun faceDoctorRefund(bet: FaceDoctorBet, reason: String) {
    with(trustPoint) {
        addCurrentTrustPoints(bet.casterUid, bet.wager + bet.fee, "面对面读博退款")
        addCurrentTrustPoints(bet.targetUid, bet.wager, "面对面读博退款")
    }
    logger.info("面对面读博已退款(reason=$reason): ${bet.casterUid} vs ${bet.targetUid}, wager=${bet.wager}, fee=${bet.fee}")
}

private fun serializeFaceDoctorBet(bet: FaceDoctorBet): String =
    listOf(
        bet.casterUid,
        bet.targetUid,
        bet.wager.toString(),
        bet.fee.toString(),
        (bet.team?.id ?: -1).toString(),
        bet.kind.name,
    ).joinToString("|")

private fun deserializeFaceDoctorBet(line: String): FaceDoctorBet? {
    val parts = line.split('|')
    if (parts.size < 5) return null
    val wager = parts[2].toIntOrNull() ?: return null
    val fee = parts[3].toIntOrNull() ?: return null
    val teamId = parts[4].toIntOrNull() ?: -1
    val team = Team.all.getOrNull(teamId)
    val kind = FaceDoctorBetKind.parse(parts.getOrNull(5)) ?: if (team != null) {
        FaceDoctorBetKind.PvpTeam
    } else {
        FaceDoctorBetKind.GameSuccess
    }
    return FaceDoctorBet(
        casterUid = parts[0],
        casterName = parts[0],
        targetUid = parts[1],
        targetName = parts[1],
        wager = wager,
        fee = fee,
        kind = kind,
        team = team,
    )
}

private fun persistFaceDoctorPendingBets() {
    val text = faceDoctorPendingBets.values.joinToString("\n", transform = ::serializeFaceDoctorBet)
    MdtStorage.setSetting(SkillShopConfig.FACE_DOCTOR_PENDING_KEY, text.ifBlank { null })
}

private fun refundStoredFaceDoctorBets(reason: String) {
    val text = MdtStorage.getSetting(SkillShopConfig.FACE_DOCTOR_PENDING_KEY).orEmpty()
    if (text.isBlank()) return
    val bets = text.lineSequence().mapNotNull(::deserializeFaceDoctorBet).toList()
    MdtStorage.setSetting(SkillShopConfig.FACE_DOCTOR_PENDING_KEY, null)
    bets.forEach { faceDoctorRefund(it, reason) }
    if (bets.isNotEmpty()) {
        logger.warning("已退回遗留面对面读博下注 $reason，共 ${bets.size} 条")
    }
}

private fun refundPendingFaceDoctorBets(reason: String) {
    val bets = faceDoctorPendingBets.values.toList()
    faceDoctorPendingBets.clear()
    persistFaceDoctorPendingBets()
    bets.forEach { faceDoctorRefund(it, reason) }
    if (bets.isNotEmpty()) {
        broadcast("[yellow]未结算的面对面读博已因 [white]$reason[yellow] 取消并退回下注。".with(), quite = true)
    }
}

private fun settleOrRefundInterruptedFaceDoctorBets(reason: String) {
    val bets = faceDoctorPendingBets.values.toList()
    faceDoctorPendingBets.clear()
    persistFaceDoctorPendingBets()
    var settled = 0
    var refunded = 0
    bets.forEach { bet ->
        when (bet.kind) {
            FaceDoctorBetKind.PvpTeam -> {
                refunded++
                faceDoctorRefund(bet, reason)
            }

            FaceDoctorBetKind.GameSuccess,
            FaceDoctorBetKind.GameFailure -> {
                settled++
                settleFaceDoctorBet(
                    bet,
                    casterWon = bet.kind == FaceDoctorBetKind.GameFailure,
                    reason = "$reason：视为本局失败/换图"
                )
            }
        }
    }
    if (settled > 0) {
        broadcast("[yellow]面对面读博：因 [white]$reason[yellow]，非PVP赌局按失败/换图结算。".with(), quite = true)
    }
    if (refunded > 0) {
        broadcast("[yellow]未结算的PVP面对面读博已因 [white]$reason[yellow] 取消并退回下注。".with(), quite = true)
    }
}

private fun settleFaceDoctorBet(bet: FaceDoctorBet, casterWon: Boolean, reason: String) {
    val pot = faceDoctorPot(bet.wager)
    val winnerUid = if (casterWon) bet.casterUid else bet.targetUid
    val winnerName = if (casterWon) bet.casterName else bet.targetName
    val loserName = if (casterWon) bet.targetName else bet.casterName
    with(trustPoint) { addCurrentTrustPoints(winnerUid, pot, "面对面读博奖金") }
    broadcast(
        "[gold]面对面读博结算[white]：$winnerName[white] 赢走了 $loserName[white] 的下注，获得赌池 [gold]$pot MDC[white]！[gray]（$reason）".with(),
        quite = true
    )
}

private fun settlePendingFaceDoctorBets(winner: Team) {
    val bets = faceDoctorPendingBets.values.toList()
    faceDoctorPendingBets.clear()
    persistFaceDoctorPendingBets()
    bets.forEach { bet ->
        when (bet.kind) {
            FaceDoctorBetKind.PvpTeam -> {
                val team = bet.team
                if (team == null) {
                    faceDoctorRefund(bet, "PVP赌局缺少目标队伍")
                } else {
                    settleFaceDoctorBet(bet, casterWon = team == winner, reason = "胜利队伍：${teamDisplay(winner)}")
                }
            }

            FaceDoctorBetKind.GameSuccess,
            FaceDoctorBetKind.GameFailure -> {
                val playerWon = faceDoctorPlayerSideWon(winner)
                val casterWon = if (bet.kind == FaceDoctorBetKind.GameSuccess) playerWon else !playerWon
                val result = if (playerWon) "本局正常完成/玩家方胜利" else "本局失败"
                settleFaceDoctorBet(bet, casterWon, "$result；胜利队伍：${teamDisplay(winner)}")
            }
        }
    }
}

private suspend fun askFaceDoctorAccept(caster: Player, target: Player, wager: Int, kind: FaceDoctorBetKind, team: Team?): Boolean? {
    val fee = faceDoctorFee(wager)
    val pot = faceDoctorPot(wager)
    var accepted: Boolean? = null
    MenuBuilder<Unit>("面对面读博请求") {
        msg = """
            |[gold]${caster.plainName()}[white] 想要和你发起面对面读博。
            |[cyan]他赌：[white]${faceDoctorBetText(kind, team)}
            |[cyan]双方赌注：[gold]各 $wager MDC
            |[cyan]你接受后会先暂扣：[gold]$wager MDC
            |[cyan]对方发起手续费：[gold]$fee MDC[gray]（仅发起者额外支付）
            |[green]胜者将拿走双方赌注之和：[gold]$pot MDC[green]；手续费不进入奖池。
        """.trimMargin()
        option("接受下注") { accepted = true }
        option("拒绝") { accepted = false }
    }.sendTo(target, 30_000)
    return accepted
}

private suspend fun tryStartFaceDoctorBet(caster: Player, target: Player, wager: Int, kind: FaceDoctorBetKind, team: Team?) {
    val def = skillByCode.getValue("facephd")
    useRequirementError(caster, def)?.let {
        caster.sendMessage("[red]无法使用技能：$it")
        return
    }
    faceDoctorGamblingBlockedReason()?.let {
        caster.sendMessage("[red]$it")
        return
    }
    if (kind == FaceDoctorBetKind.PvpTeam && team == null) {
        caster.sendMessage("[red]PVP面对面读博必须选择目标胜利队伍。")
        return
    }
    if (Vars.state.rules.pvp != (kind == FaceDoctorBetKind.PvpTeam)) {
        caster.sendMessage("[yellow]当前地图模式已变化，请重新打开面对面读博菜单选择赌局。")
        return
    }
    if (faceDoctorUsedThisGame.contains(caster.uuid())) {
        caster.sendMessage("[red]面对面读博每局只能成功发起一次。")
        return
    }
    if (target === caster || PlayerData[target].id == PlayerData[caster].id) {
        caster.sendMessage("[yellow]你不能和自己面对面读博。")
        return
    }
    val fee = faceDoctorFee(wager)
    val casterCost = wager + fee
    val casterUid = PlayerData[caster].id
    val targetUid = PlayerData[target].id
    val casterPoints = with(trustPoint) { getTrustPoints(casterUid) }
    val targetPoints = with(trustPoint) { getTrustPoints(targetUid) }
    if (casterPoints < casterCost) {
        caster.sendMessage("[red]MDC不足：你需要下注 $wager MDC 并支付手续费 $fee MDC，共 $casterCost MDC；当前 $casterPoints。")
        return
    }
    if (targetPoints < wager) {
        caster.sendMessage("[yellow]目标玩家MDC不足，无法接受 $wager MDC 赌注。")
        target.sendMessage("[yellow]${caster.plainName()} 试图向你发起面对面读博，但你当前MDC不足以支付 $wager MDC 赌注。")
        return
    }

    broadcast("[gold]{caster.name}[white]向[gold]{target.name}[white]发起了面对面读博请求！".with("caster" to caster, "target" to target), quite = true)
    val accepted = askFaceDoctorAccept(caster, target, wager, kind, team)
    if (accepted != true) {
        caster.sendMessage("[yellow]面对面读博已取消：目标拒绝或超时。")
        target.sendMessage("[yellow]你已拒绝/超时未接受面对面读博。")
        return
    }
    faceDoctorGamblingBlockedReason()?.let {
        caster.sendMessage("[yellow]面对面读博取消：$it")
        target.sendMessage("[yellow]面对面读博取消：$it")
        return
    }
    if (Vars.state.rules.pvp != (kind == FaceDoctorBetKind.PvpTeam)) {
        caster.sendMessage("[yellow]面对面读博取消：地图模式已变化，请重新发起。")
        target.sendMessage("[yellow]面对面读博取消：地图模式已变化。")
        return
    }

    if (with(trustPoint) { getTrustPoints(casterUid) } < casterCost) {
        caster.sendMessage("[red]MDC不足：下注前余额发生变化，已取消。")
        target.sendMessage("[yellow]面对面读博取消：对方余额不足。")
        return
    }
    if (with(trustPoint) { getTrustPoints(targetUid) } < wager) {
        caster.sendMessage("[yellow]面对面读博取消：目标MDC不足，无法支付赌注。")
        target.sendMessage("[red]MDC不足，无法支付赌注：你需要 $wager MDC 才能接受下注。")
        return
    }
    if (!with(trustPoint) { spendTrustPoints(casterUid, casterCost, "Skill:facephd") }) {
        caster.sendMessage("[red]扣除下注/手续费失败，已取消。")
        return
    }
    if (!with(trustPoint) { spendTrustPoints(targetUid, wager, "Skill:facephd") }) {
        with(trustPoint) { addCurrentTrustPoints(casterUid, casterCost, "面对面读博退款") }
        caster.sendMessage("[yellow]目标扣除下注失败，已退回你的下注与手续费。")
        target.sendMessage("[red]MDC不足或扣除下注失败，无法支付赌注，面对面读博已取消。")
        return
    }

    val bet = FaceDoctorBet(
        casterUid = casterUid,
        casterName = caster.name,
        targetUid = targetUid,
        targetName = target.name,
        wager = wager,
        fee = fee,
        kind = kind,
        team = team,
    )
    faceDoctorUsedThisGame.add(caster.uuid())
    faceDoctorPendingBets[caster.uuid()] = bet
    persistFaceDoctorPendingBets()
    broadcast(
        "[gold]${bet.casterName}[white]和[gold]${bet.targetName}[white]发起了面对面赌博！双方各下注[gold]${wager} MDC[white]。如果 [cyan]${faceDoctorBetText(kind, team)}[white]，则[gold]${bet.casterName}[white]将获得赌池[gold]${faceDoctorPot(wager)} MDC[white]！".with(),
        quite = true
    )
}

private suspend fun openFaceDoctorTargetMenu(caster: Player, wager: Int, kind: FaceDoctorBetKind, team: Team?) {
    val targets = Groups.player.toList()
        .filter { it !== caster && PlayerData[it].id != PlayerData[caster].id }
        .sortedBy { it.plainName() }
    if (targets.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可进行面对面读博的在线目标。")
        return
    }
    PagedMenuBuilder(targets) { target ->
        val data = PlayerData[target]
        option("${target.name} [gray](${data.shortId})\n[gray]赌注 $wager MDC；点击后发送请求") {
            tryStartFaceDoctorBet(caster, target, wager, kind, team)
        }
    }.apply {
        title = "选择面对面读博对象"
        msg = "[cyan]赌注：[gold]$wager MDC\n[cyan]赌局：[white]${faceDoctorBetText(kind, team)}"
        sendTo(caster, 60_000)
    }
}

private suspend fun openFaceDoctorTeamMenu(caster: Player, wager: Int) {
    faceDoctorGamblingBlockedReason()?.let {
        caster.sendMessage("[red]$it")
        return
    }
    if (!Vars.state.rules.pvp) {
        MenuBuilder<Unit>("选择读博结果") {
            msg = "[cyan]非PVP地图赌本局最终结果：正常完成/玩家方胜利，或失败/换图。"
            option("[green]赌本局正常完成\n[gray]玩家方胜利时发起者赢得赌池") {
                openFaceDoctorTargetMenu(caster, wager, FaceDoctorBetKind.GameSuccess, null)
            }
            option("[red]赌本局失败/换图\n[gray]失败、投降或中途换图时发起者赢得赌池") {
                openFaceDoctorTargetMenu(caster, wager, FaceDoctorBetKind.GameFailure, null)
            }
            option("返回") { openFaceDoctorWagerMenu(caster) }
        }.sendTo(caster, 60_000)
        return
    }
    val teams = activePvpBetTeams()
    if (teams.isEmpty()) {
        caster.sendMessage("[yellow]当前PVP没有可选择的已有队伍。")
        return
    }
    object : PagedMenuBuilder<Team>(teams, prePage = 6) {
        override suspend fun renderItem(item: Team) {
            option("[white]${teamDisplay(item)}\n[gray]赌此队赢得本局PVP") {
                openFaceDoctorTargetMenu(caster, wager, FaceDoctorBetKind.PvpTeam, item)
            }
        }

        override suspend fun build() {
            title = "选择读博胜利队伍"
            msg = "[cyan]选择你认为会赢得本局PVP的队伍。"
            super.build()
        }
    }.sendTo(caster, 60_000)
}

private suspend fun openFaceDoctorWagerMenu(caster: Player) {
    val def = skillByCode.getValue("facephd")
    useRequirementError(caster, def)?.let {
        caster.sendMessage("[red]无法使用技能：$it")
        return
    }
    if (faceDoctorUsedThisGame.contains(caster.uuid())) {
        caster.sendMessage("[red]面对面读博每局只能成功发起一次。")
        return
    }
    faceDoctorGamblingBlockedReason()?.let {
        caster.sendMessage("[red]$it")
        return
    }
    MenuBuilder<Unit>("[gold]对面对面读博") {
        msg = """
            |[cyan]选择赌注档位。
            |[gray]发起者额外支付赌注10%的手续费（至少1 MDC）；双方下注会暂扣，胜者获得双方下注之和。
            |[gray]PVP地图需选择你赌赢的队伍；非PVP地图需选择本局正常完成或失败/换图。
        """.trimMargin()
        faceDoctorWagers.forEach { wager ->
            val fee = faceDoctorFee(wager)
            option("[gold]$wager MDC\n[gray]手续费 $fee MDC；胜者获得 ${faceDoctorPot(wager)} MDC") {
                openFaceDoctorTeamMenu(caster, wager)
            }
            newRow()
        }
        option("关闭") {}
    }.sendTo(caster, 60_000)
}

private fun spawnMissileBurstOnce(player: Player, missileType: UnitType, index: Int): Boolean {
    if (player.dead()) return false
    val source = player.unit()?.takeIf { it.isValid && !it.dead } ?: return false
    val rot = source.rotation
    val side = ((index % 5) - 2) * 3.5f
    val front = source.type.hitSize / 2f + 8f
    val x = source.x + Mathf.cosDeg(rot) * front + Mathf.cosDeg(rot + 90f) * side
    val y = source.y + Mathf.sinDeg(rot) * front + Mathf.sinDeg(rot + 90f) * side
    missileType.create(player.team()).apply {
        set(x, y)
        rotation(rot + (Random.nextFloat() * 6f - 3f))
        speedMultiplier(2f)
        add()
    }
    return true
}

private val EXCLUSIVE_FISH_FOLLOW_INTERVAL_MILLIS = 500L
private val EXCLUSIVE_FISH_KEEP_DISTANCE = 44f
private val EXCLUSIVE_FISH_STEP = 40f
private val EXCLUSIVE_FISH_TELEPORT_DISTANCE = 800f

private val exclusiveFishOwners = ConcurrentHashMap<Int, String>()
private val ownerExclusiveFish = ConcurrentHashMap<String, Int>()

private fun findUnitById(id: Int): MindustryUnit? =
    Groups.unit.toList().firstOrNull { it.id() == id }

private fun exclusiveFishOf(ownerUuid: String): MindustryUnit? =
    ownerExclusiveFish[ownerUuid]?.let(::findUnitById)?.takeIf { it.isValid && !it.dead }

private fun clearExclusiveFish(unitId: Int) {
    val owner = exclusiveFishOwners.remove(unitId) ?: return
    if (ownerExclusiveFish[owner] == unitId) ownerExclusiveFish.remove(owner)
}

private fun killExclusiveFish(ownerUuid: String) {
    val unitId = ownerExclusiveFish.remove(ownerUuid) ?: return
    exclusiveFishOwners.remove(unitId)
    findUnitById(unitId)?.takeIf { it.isValid && !it.dead }?.kill()
}

private fun summonExclusiveFish(player: Player): MindustryUnit {
    val center = player.unit()
    val angle = Random.nextFloat() * 360f
    val x = (center?.x ?: player.x) + Mathf.cosDeg(angle) * 28f
    val y = (center?.y ?: player.y) + Mathf.sinDeg(angle) * 28f
    val fish = UnitTypes.risso.create(player.team()).apply {
        set(x, y)
        elevation = 1f
        rotation(center?.rotation ?: 0f)
        add()
    }
    exclusiveFishOwners[fish.id()] = player.uuid()
    ownerExclusiveFish[player.uuid()] = fish.id()
    return fish
}

private fun updateExclusiveFishFollowers() {
    exclusiveFishOwners.toList().forEach { (unitId, ownerUuid) ->
        val fish = findUnitById(unitId)
        if (fish == null || !fish.isValid || fish.dead) {
            clearExclusiveFish(unitId)
            return@forEach
        }
        val owner = Groups.player.toList().firstOrNull { it.uuid() == ownerUuid }
        if (owner == null) {
            clearExclusiveFish(unitId)
            fish.kill()
            return@forEach
        }
        val target = owner.unit()
        if (owner.dead() || target == null || target.dead || target == fish) {
            fish.elevation = 1f
            return@forEach
        }

        if (fish.team() != owner.team()) fish.team(owner.team())
        fish.elevation = 1f
        val dx = target.x - fish.x
        val dy = target.y - fish.y
        val dst2 = dx * dx + dy * dy
        val keep2 = EXCLUSIVE_FISH_KEEP_DISTANCE * EXCLUSIVE_FISH_KEEP_DISTANCE
        if (dst2 > EXCLUSIVE_FISH_TELEPORT_DISTANCE * EXCLUSIVE_FISH_TELEPORT_DISTANCE) {
            fish.set(target.x + Random.nextFloat() * 32f - 16f, target.y + Random.nextFloat() * 32f - 16f)
            fish.snapInterpolation()
        } else if (dst2 > keep2) {
            val dst = sqrt(dst2.toDouble()).toFloat().coerceAtLeast(0.001f)
            val step = minOf(EXCLUSIVE_FISH_STEP, dst - EXCLUSIVE_FISH_KEEP_DISTANCE)
            fish.set(fish.x + dx / dst * step, fish.y + dy / dst * step)
            fish.rotation(fish.angleTo(target.x, target.y))
        }
    }
}

private fun replaceLogicDisplaysWithSolarPanels(): Int {
    val targets = setOf(Blocks.logicDisplay, Blocks.largeLogicDisplay, Blocks.tileLogicDisplay)
    val handled = mutableSetOf<Int>()
    var count = 0
    for (x in 0 until Vars.world.width()) {
        for (y in 0 until Vars.world.height()) {
            val tile = Vars.world.tile(x, y) ?: continue
            val build = tile.build
            val block = build?.block ?: tile.block()
            if (block !in targets) continue
            val anchor = build?.tile ?: tile
            val key = build?.pos() ?: anchor.pos()
            if (!handled.add(key)) continue
            anchor.setNet(Blocks.solarPanel, build?.team ?: Vars.state.rules.defaultTeam, 0)
            count++
        }
    }
    return count
}

private fun clearHiddenBuildItemsCompat(rules: Any): Int {
    val field = runCatching { rules.javaClass.getField("hiddenBuildItems") }.getOrNull() ?: return 0
    val value = field.get(rules) ?: return 0
    val size = runCatching { value.javaClass.getField("size").getInt(value) }
        .getOrElse { (value as? Collection<*>)?.size ?: 0 }
    runCatching { value.javaClass.methods.firstOrNull { it.name == "clear" && it.parameterCount == 0 }?.invoke(value) }
    return size
}

private fun openAllTechLimitsByUltimate(): String {
    val rules = Vars.state.rules
    val bannedBlocks = rules.bannedBlocks.size
    val bannedUnits = rules.bannedUnits.size
    val hiddenItems = clearHiddenBuildItemsCompat(rules)
    val researchedBefore = rules.researched.size
    rules.bannedBlocks.clear()
    rules.bannedUnits.clear()
    rules.blockWhitelist = false
    rules.unitWhitelist = false
    rules.hideBannedBlocks = false
    rules.schematicsAllowed = true
    rules.planet = Planets.sun
    Vars.content.blocks().forEach { rules.researched.add(it) }
    Vars.content.units().forEach { rules.researched.add(it) }
    Vars.content.items().forEach { rules.researched.add(it) }
    Vars.content.liquids().forEach { rules.researched.add(it) }
    Call.setRules(rules)
    val researchedAdded = (rules.researched.size - researchedBefore).coerceAtLeast(0)
    return "清理禁用方块 $bannedBlocks 个、禁用单位 $bannedUnits 个、隐藏建造物品 $hiddenItems 个，新增已研究内容 $researchedAdded 个"
}

private fun ultimateTeamCandidates(current: Team): List<Team> {
    val teams = linkedSetOf<Team>()
    fun addTeam(team: Team?) {
        if (team != null && team != current && team != Team.derelict && team.id != 255) teams += team
    }
    addTeam(Vars.state.rules.waveTeam)
    addTeam(Vars.state.rules.defaultTeam)
    Vars.state.teams.getActive().forEach { addTeam(it.team) }
    Groups.player.forEach { addTeam(it.team()) }
    Groups.unit.forEach { if (it.isValid && !it.dead) addTeam(it.team) }
    Groups.build.forEach { addTeam(it.team) }
    Team.baseTeams.forEach { addTeam(it) }
    return teams.distinctBy { it.id }.sortedBy { it.id }
}

private fun switchPlayerToRandomTeam(player: Player): Team? {
    val current = player.team()
    val target = ultimateTeamCandidates(current).randomOrNull() ?: return null
    player.team(target)
    player.unit()?.takeIf { it.isValid && !it.dead }?.team(target)
    return target
}

private fun lotteryReward(): Int {
    // 按需求文档中的权重执行；总权重为105，实际概率会按权重归一化。
    val roll = Random.nextInt(105)
    return when (roll) {
        in 0 until 50 -> 1
        in 50 until 75 -> 2
        in 75 until 90 -> 5
        in 90 until 103 -> 10
        else -> 99
    }
}

onEnable {
    launch(Dispatchers.IO) {
        delay(5_000)
        runCatching { refundStoredFaceDoctorBets("（服务端/脚本重启遗留）") }
            .onFailure { logger.warning("检查遗留面对面读博下注失败：${it.message}") }
    }
    with(shopList) {
        registerShop(SkillShopConfig.SHOP_CODE, "技能商店", "使用MDC购买特殊/商店技能", "/skillshop")
    }
    skillDefinitions.forEach { def ->
        SkillMenuRegistry.register(
            SkillMenuEntry(
                code = def.code,
                displayName = def.displayName,
                category = SkillMenuCategory.Shop,
                description = "${def.description}；${useLimitText(def)}",
                command = "/skill ${def.code}",
                visible = { storeSkillVisible(it, def) },
            )
        )
    }
    launch(Dispatchers.game) {
        while (true) {
            delay(EXCLUSIVE_FISH_FOLLOW_INTERVAL_MILLIS)
            updateExclusiveFishFollowers()
        }
    }
}

listen<EventType.PlayerJoin> {
    val playerUid = uid(it.player)
    launch(Dispatchers.IO) { loadOwnedSkillCodesCached(playerUid, forceRefresh = true) }
}

listen<EventType.PlayerLeave> {
    killExclusiveFish(it.player.uuid())
    invalidateOwnedSkillCache(uid(it.player))
}

listen<EventType.UnitDestroyEvent> {
    clearExclusiveFish(it.unit.id())
}

listen<EventType.WorldLoadEvent> {
    settleOrRefundInterruptedFaceDoctorBets("换图")
    faceDoctorUsedThisGame.clear()
    exclusiveFishOwners.clear()
    ownerExclusiveFish.clear()
}

listen<EventType.ResetEvent> {
    settleOrRefundInterruptedFaceDoctorBets("重置")
    faceDoctorUsedThisGame.clear()
    exclusiveFishOwners.clear()
    ownerExclusiveFish.clear()
}

listen<EventType.GameOverEvent> {
    settlePendingFaceDoctorBets(it.winner)
}

listenPacket2Server<UnitControlCallPacket> { con, packet ->
    val unit = packet.unit ?: return@listenPacket2Server true
    val ownerUuid = exclusiveFishOwners[unit.id()] ?: return@listenPacket2Server true
    val controller = con.player ?: return@listenPacket2Server false
    if (controller.uuid() == ownerUuid) {
        true
    } else {
        Call.announce(con, "[cyan]这条鱼鱼此生不属于你，无法附身。")
        false
    }
}

onDisable {
    refundPendingFaceDoctorBets("脚本卸载")
    faceDoctorUsedThisGame.clear()
    with(shopList) { unregisterShop(SkillShopConfig.SHOP_CODE) }
    skillDefinitions.forEach { SkillMenuRegistry.unregister(it.code) }
    exclusiveFishOwners.keys.toList().forEach { id -> findUnitById(id)?.takeIf { it.isValid && !it.dead }?.kill() }
    exclusiveFishOwners.clear()
    ownerExclusiveFish.clear()
}

command("skillshop", "打开技能商店") {
    aliases = listOf("技能商店")
    attr(ClientOnly)
    body { openSkillShop(player!!) }
}

command("radar", "商店技能：雷达".with(), commands = SkillCommands) {
    aliases = listOf("雷达")
    val def = skillByCode.getValue("radar")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        if (!Vars.state.rules.fog) returnReply("[yellow]当前地图没有战争迷雾可开启".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        launch(Dispatchers.game) {
            Vars.state.rules.fog = false
            Call.setRules(Vars.state.rules)
            delay(300_000)
            Vars.state.rules.fog = true
            Call.setRules(Vars.state.rules)
        }
        broadcastSkill("雷达")
    }
}

command("fluid", "商店技能：随机液体".with(), commands = SkillCommands) {
    aliases = listOf("随机液体")
    val def = skillByCode.getValue("fluid")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        setFloorSquare(player, randomLiquidFloor(), 0..1)
        broadcastSkill("随机液体")
    }
}

command("randomore", "商店技能：随机矿".with(), commands = SkillCommands) {
    aliases = listOf("随机矿")
    val def = skillByCode.getValue("randomore")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        setOreSquare(player, randomOre(), 0..1)
        broadcastSkill("随机矿")
    }
}

command("wallkiller", "商店技能：粉碎墙壁".with(), commands = SkillCommands) {
    aliases = listOf("粉碎墙壁", "强力粉碎者")
    val def = skillByCode.getValue("wallkiller")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        var removed = 0
        for (x in -1..1) for (y in -1..1) {
            val tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y) ?: continue
            val block = tile.block()
            if (block is Wall || block is StaticWall) {
                tile.setNet(Blocks.air)
                removed++
            }
        }
        player.sendMessage("[green]已粉碎墙壁：[white]$removed")
        broadcastSkill("粉碎墙壁")
    }
}

command("corezone4", "商店技能：核心区4x4".with(), commands = SkillCommands) {
    aliases = listOf("核心区4x4", "大核心区")
    val def = skillByCode.getValue("corezone4")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        setFloorSquare(player, Blocks.coreZone, -1..2)
        broadcastSkill("核心区4x4")
    }
}

command("betray", "商店技能：叛变".with(), commands = SkillCommands) {
    aliases = listOf("叛变", "策反")
    val def = skillByCode.getValue("betray")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        val targets = Groups.unit.filter { it.team != player.team() && !it.isPlayer }.toList().shuffled().take(5)
        if (targets.isEmpty()) returnReply("[yellow]当前没有可叛变的单位".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        targets.forEach {
            it.team(player.team())
            it.apply(StatusEffects.overclock, 60f * 5f)
        }
        player.sendMessage("[green]已策反单位：[white]${targets.size}")
        broadcastSkill("叛变")
    }
}

command("banme", "商店技能：BAN自己5分钟".with(), commands = SkillCommands) {
    aliases = listOf("BAN自己5分钟", "ban自己", "自ban")
    val def = skillByCode.getValue("banme")
    attr(ClientOnly)
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        with(trustPoint) { addTrustPoints(PlayerData[player].id, 5, "Skill:banme") }
        player.sendMessage("[green]+5 MDC[gray]（BAN自己5分钟）")
        broadcastSkill("BAN自己5分钟")
        player.con.kick("[yellow]你使用了 BAN自己5分钟，获得了 5 MDC。", 5 * 60 * 1000L)
    }
}

command("reptile", "商店技能：爬爬盲盒".with(), commands = SkillCommands) {
    aliases = listOf("爬爬盲盒")
    val def = skillByCode.getValue("reptile")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        if (Vars.state.rules.bannedBlocks.contains(Blocks.groundFactory)) returnReply("[red]该地图地面工厂已禁封，禁止召唤爬爬".with())
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (unit.blockOn() != Blocks.air) returnReply("[red]你只能在空地使用爬爬盲盒".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        val roll = Random.nextInt(100)
        val type = when (roll) {
            in 0..29 -> UnitTypes.crawler
            in 30..59 -> UnitTypes.atrax
            in 60..89 -> UnitTypes.spiroct
            else -> UnitTypes.arkyid
        }
        spawnAround(type, player, 1)
        broadcastSkill("爬爬盲盒")
    }
}

command("teleportation", "商店技能：传送".with(), commands = SkillCommands) {
    aliases = listOf("传送")
    val def = skillByCode.getValue("teleportation")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        val center = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        val targets = Groups.unit.filter { it != center }.toList().shuffled().take(10)
        if (targets.isEmpty()) returnReply("[yellow]当前没有可传送的单位".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        targets.forEach {
            it.set(center.x, center.y)
            it.snapInterpolation()
        }
        broadcastSkill("传送")
    }
}

command("rain", "商店技能：唤雨".with(), commands = SkillCommands) {
    aliases = listOf("唤雨")
    val def = skillByCode.getValue("rain")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        Call.createWeather(Weathers.rain, 1f, 60f * 60f, 0f, 0f)
        broadcastSkill("唤雨")
    }
}

command("kickmebutoct", "商店技能：踢自己送oct".with(), commands = SkillCommands) {
    aliases = listOf("踢自己送oct", "踢我给oct")
    val def = skillByCode.getValue("kickmebutoct")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown())
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        spawnAround(UnitTypes.oct, player, 1, 16f)
        broadcastSkill("踢自己送oct")
        player.con.kick("[yellow]你踢出了自己，并为队伍召唤了一只 oct。", 1_000L)
    }
}

command("imcute", "商店技能：我很可爱，请给我钱".with(), commands = SkillCommands) {
    aliases = listOf("我很可爱", "cute")
    val def = skillByCode.getValue("imcute")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown())
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        with(trustPoint) { addTrustPoints(PlayerData[player].id, 2, "Skill:imcute") }
        player.sendMessage("[green]+2 MDC[gray]（我很可爱，请给我钱）")
        broadcastSkill("我很可爱，请给我钱")
    }
}

command("lottery", "商店技能：抽奖".with(), commands = SkillCommands) {
    aliases = listOf("抽奖")
    val def = skillByCode.getValue("lottery")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown())
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        val reward = lotteryReward()
        with(trustPoint) { addTrustPoints(PlayerData[player].id, reward, "Skill:lottery") }
        broadcast("[gold]{player.name}[white]抽到了[gold]{reward} MDC[white]！".with("player" to player, "reward" to reward), quite = true)
    }
}

command("randomunit", "商店技能：随机单位".with(), commands = SkillCommands) {
    aliases = listOf("随机单位", "unitlottery")
    val def = skillByCode.getValue("randomunit")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown())
    skillBody {
        val type = randomSummonableUnit() ?: returnReply("[red]当前内容列表中没有可召唤单位".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        spawnAround(type, player, 1, 16f)
        broadcast("[yellow]{player.name}[white]召唤了随机单位：[accent]{unit}[white]！".with(
            "player" to player,
            "unit" to type.localizedName
        ), quite = true)
    }
}

command("missilestorm", "商店技能：导弹风暴".with(), commands = SkillCommands) {
    aliases = listOf("导弹风暴", "missileStorm")
    val def = skillByCode.getValue("missilestorm")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        val missileTypes = availableMissileStormUnits()
        if (missileTypes.isEmpty()) returnReply("[red]未找到可用的创伤导弹单位，无法释放导弹风暴".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        broadcast("[purple]{player.name}[white]释放了[purple]导弹风暴[white]！".with("player" to player), quite = true)
        launch(Dispatchers.game) {
            repeat(10) {
                repeat(5) {
                    spawnAround(missileTypes.random(), player, 1, 96f) { unit ->
                        unit.rotation(Random.nextFloat() * 360f)
                    }
                }
                delay(500L)
            }
        }
    }
}

command("missileburst", "商店技能：导弹连射".with(), commands = SkillCommands) {
    aliases = listOf("导弹连射", "missileBurst", "小导弹连射")
    val def = skillByCode.getValue("missileburst")
    attr(ShopSkillPrecheck(def))
    skillBody {
        val missileType = unitTypeByName("scathe-missile-surge-split")
            ?: returnReply("[red]未找到单位 scathe-missile-surge-split，无法释放导弹连射".with())
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        broadcast("[orange]{player.name}[white]开始[orange]导弹连射[white]！".with("player" to player), quite = true)
        launch(Dispatchers.game) {
            for (i in 0 until 40) {
                if (!spawnMissileBurstOnce(player, missileType, i)) break
                delay(80L)
            }
        }
    }
}

command("facephd", "商店技能：[gold]对面对面读博".with(), commands = SkillCommands) {
    aliases = listOf("对面对面读博", "面对面读博", "读博", "赌博", "faceDoctor")
    val def = skillByCode.getValue("facephd")
    attr(ClientOnly)
    skillBody {
        useRequirementError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        openFaceDoctorWagerMenu(player)
    }
}

command("fishonlyyou", "商店技能：此生只属鱼你".with(), commands = SkillCommands) {
    aliases = listOf("此生只属鱼你", "鱼鱼", "fishyou")
    val def = skillByCode.getValue("fishonlyyou")
    attr(ShopSkillPrecheck(def))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        val existing = exclusiveFishOf(player.uuid())
        if (existing != null) {
            val center = player.unit()
            if (center != null && existing.dst(center) > 120f) {
                existing.set(center.x + 24f, center.y)
                existing.elevation = 1f
                existing.snapInterpolation()
            }
            player.sendMessage("[cyan]你的鱼鱼仍然在你身边，其生只属鱼你。")
            return@skillBody
        }

        val fish = summonExclusiveFish(player)
        player.sendMessage("[cyan]你召唤了一只此生只属于你的 risso 鱼鱼。其他玩家无法附身它。")
        broadcast(
            "[cyan]{player.name}[white]召唤了一只[cyan]此生只属鱼ta[white]的鱼鱼！".with("player" to player),
            quite = true
        )
        fish.elevation = 1f
    }
}

command("ultirandom", "商店技能：[purple]终极随机".with(), commands = SkillCommands) {
    aliases = listOf("终极随机", "ultimateRandom")
    val def = skillByCode.getValue("ultirandom")
    attr(ShopSkillPrecheck(def)); attr(SkillCooldown(def.cooldownMillis ?: -1))
    skillBody {
        prepareUseError(player, def)?.let { returnReply("[red]无法使用技能：$it".with()) }
        broadcast("[purple]{player.name}[white]释放了[purple]终极随机[white]，即将开始....".with("player" to player), quite = true)
        for (i in 3 downTo 1) {
            broadcast("[purple]$i".with(), quite = true)
            delay(1_000L)
        }

        when (Random.nextInt(14)) {
            0 -> {
                with(trustPoint) { addTrustPoints(PlayerData[player].id, 100, "Skill:ultirandom") }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：获得[gold]100 MDC[white]！".with("player" to player), quite = true)
            }
            1 -> {
                val useFlood = Random.nextBoolean()
                val ok = if (useFlood) with(funRules) { setFloodMode(true) } else with(funRules) { setLordOfWarMode(true) }
                val mode = if (useFlood) "洪水" else "Lord of War"
                broadcast(
                    (if (ok) "[purple]{player.name}[white]激活了[purple]终极随机[white]：当前地图尝试启用[accent]{mode}[white]！"
                    else "[purple]{player.name}[white]激活了[purple]终极随机[white]：尝试启用[accent]{mode}[white]失败，请查看日志。")
                        .with("player" to player, "mode" to mode),
                    quite = true
                )
            }
            2 -> {
                val count = with(funRules) { killAllUnits() }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：击杀所有单位，共[accent]{count}[white]个！".with("player" to player, "count" to count), quite = true)
            }
            3 -> {
                val count = with(funRules) { damageAllBuildings(0.99f) }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：所有建筑受到了99%当前血量伤害，共影响[accent]{count}[white]个建筑！".with("player" to player, "count" to count), quite = true)
            }
            4 -> {
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：把自己踢出了服务器！".with("player" to player), quite = true)
                player.con.kick("[purple]终极随机：你被踢出了服务器。", 1_000L)
            }
            5 -> {
                broadcast("[red]服务器即将关闭...".with(), quite = true)
                launch {
                    delay(10_000L)
                    broadcast("[green]骗你的，我怎么可能真关。".with(), quite = true)
                }
            }
            6 -> {
                val count = replaceLogicDisplaysWithSolarPanels()
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：涩图禁令！已将[accent]{count}[white]个逻辑显示单元替换为太阳能板！".with("player" to player, "count" to count), quite = true)
            }
            7 -> {
                val result = openAllTechLimitsByUltimate()
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：开放科技！[gray]$result".with("player" to player), quite = true)
            }
            8 -> {
                var waves = 0
                repeat(10) {
                    runCatching {
                        Vars.logic.runWave()
                        waves++
                    }
                }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：立即跳波[accent]{waves}[white]波！".with("player" to player, "waves" to waves), quite = true)
            }
            9 -> {
                val count = Groups.player.toList().count { target ->
                    with(randomForm) { setCatgirlForm(target, reward = false, announce = false) }
                }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：将所有人变成猫娘！共影响[accent]{count}[white]名玩家。".with("player" to player, "count" to count), quite = true)
            }
            10 -> {
                val changed = with(funRules) { addNoSkillsTag() }
                val result = if (changed) "当前地图已添加 @noSkills 标签" else "当前地图本来就存在 @noSkills 标签"
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：禁用当前地图技能！[gray]$result".with("player" to player), quite = true)
            }
            11 -> {
                val uid = PlayerData[player].id
                val current = with(trustPoint) { getTrustPoints(uid) }
                val deducted = current.coerceAtMost(100)
                if (current >= 100) {
                    with(trustPoint) { spendTrustPoints(uid, 100, "Skill:ultirandom.penalty") }
                } else {
                    with(trustPoint) { setTrustPoints(uid, 0) }
                }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：被扣除[red]{deducted} MDC[white]！".with("player" to player, "deducted" to deducted), quite = true)
            }
            12 -> {
                Groups.unit.toList().forEach { unit ->
                    if (unit.isValid && !unit.dead) {
                        unit.apply(StatusEffects.unmoving, 15f * 60f)
                        unit.apply(StatusEffects.disarmed, 15f * 60f)
                    }
                }
                broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：砸哇路多！所有单位静止15秒！".with("player" to player), quite = true)
            }
            13 -> {
                val oldTeam = player.team()
                val newTeam = switchPlayerToRandomTeam(player)
                if (newTeam == null) {
                    broadcast("[purple]{player.name}[white]激活了[purple]终极随机[white]：反客为主失败，场上没有其它有效队伍！".with("player" to player), quite = true)
                } else {
                    broadcast(
                        "[purple]{player.name}[white]激活了[purple]终极随机[white]：反客为主！从 ${oldTeam.coloredName()}[white] 转投 ${newTeam.coloredName()}[white]！".with("player" to player),
                        quite = true
                    )
                }
            }
        }
    }
}
