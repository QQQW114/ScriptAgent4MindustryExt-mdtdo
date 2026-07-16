@file:Depends("wayzer/user/ext/skills", "技能系统核心")

package wayzer.user.ext

import arc.audio.Sound
import arc.graphics.Color
import arc.math.Interp
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.struct.Seq
import arc.util.pooling.Pools
import arc.util.serialization.Jval
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.*
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.ClientOnly
import coreMindustry.lib.RootCommands
import coreMindustry.lib.broadcast
import coreMindustry.lib.hasPermission
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.Items
import mindustry.content.Liquids
import mindustry.content.Planets
import mindustry.content.StatusEffects
import mindustry.content.UnitTypes
import mindustry.content.Weathers
import mindustry.ctype.ContentType
import mindustry.ctype.MappableContent
import mindustry.ctype.UnlockableContent
import mindustry.entities.Damage
import mindustry.entities.Effect
import mindustry.entities.Fires
import mindustry.entities.abilities.Ability
import mindustry.entities.abilities.UnitSpawnAbility
import mindustry.entities.bullet.BulletType
import mindustry.entities.bullet.ContinuousBulletType
import mindustry.entities.bullet.LiquidBulletType
import mindustry.entities.units.StatusEntry
import mindustry.game.Team
import mindustry.gen.BlockUnitc
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Sounds
import mindustry.type.StatusEffect
import mindustry.type.UnitType
import mindustry.type.Weapon
import mindustry.world.Block
import mindustry.world.blocks.defense.Wall
import mindustry.world.blocks.defense.turrets.ContinuousTurret
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.blocks.environment.StaticWall
import wayzer.lib.PlayerData
import java.lang.reflect.Modifier
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.random.Random

private val skillsCore = contextScript<Skills>()
private val textInput get() = skillsCore.textInput
private val funRules get() = skillsCore.funRules
private val trustPoint get() = skillsCore.trustPoint
private fun godMenuAllowed(player: Player): Boolean = skillsCore.godMenuAllowed(player)
private fun skillAdmin(player: Player): Boolean = skillsCore.skillAdmin(player)
private suspend fun openSkillMainMenu(player: Player) = skillsCore.openSkillMainMenu(player)
private fun setBlockSquare(player: Player, block: mindustry.world.Block, range: IntRange) = skillsCore.setBlockSquare(player, block, range)
private fun placeBlockAtPlayer(player: Player, block: mindustry.world.Block, requireAir: Boolean = false): Boolean = skillsCore.placeBlockAtPlayer(player, block, requireAir)
private fun smashWalls(player: Player, range: IntRange): Int = skillsCore.smashWalls(player, range)
private fun spawnAround(type: UnitType, player: Player, count: Int, radius: Float = 56f, configure: (mindustry.gen.Unit) -> Unit = {}) =
    skillsCore.spawnAround(type, player, count, radius, configure)
private fun gaokaoReward(score: Int): Int = skillsCore.gaokaoReward(score)

private data class ExamResult(
    val uid: String,
    val name: String,
    val score: Int,
    val reward: Int,
)

private data class GodMultiplier(
    val label: String,
    val desc: String,
    val globalGetter: () -> Float,
    val globalSetter: (Float) -> Unit,
    val teamGetter: ((Team) -> Float)? = null,
    val teamSetter: ((Team, Float) -> Unit)? = null,
    val effectiveGetter: ((Team) -> Float)? = null,
)

private val godMultipliers = listOf(
    GodMultiplier(
        "建筑血量倍率",
        "影响所有建筑最大血量",
        { Vars.state.rules.blockHealthMultiplier },
        { Vars.state.rules.blockHealthMultiplier = it },
        { team -> Vars.state.rules.teams[team].blockHealthMultiplier },
        { team, value -> Vars.state.rules.teams[team].blockHealthMultiplier = value },
        { team -> Vars.state.rules.blockHealth(team) }
    ),
    GodMultiplier(
        "建筑伤害倍率",
        "影响建筑/炮塔造成的伤害",
        { Vars.state.rules.blockDamageMultiplier },
        { Vars.state.rules.blockDamageMultiplier = it },
        { team -> Vars.state.rules.teams[team].blockDamageMultiplier },
        { team, value -> Vars.state.rules.teams[team].blockDamageMultiplier = value },
        { team -> Vars.state.rules.blockDamage(team) }
    ),
    GodMultiplier(
        "单位血量/生命倍率",
        "影响所有单位最大血量",
        { Vars.state.rules.unitHealthMultiplier },
        { Vars.state.rules.unitHealthMultiplier = it },
        { team -> Vars.state.rules.teams[team].unitHealthMultiplier },
        { team, value -> Vars.state.rules.teams[team].unitHealthMultiplier = value },
        { team -> Vars.state.rules.unitHealth(team) }
    ),
    GodMultiplier(
        "单位攻击倍率",
        "影响单位武器造成的伤害",
        { Vars.state.rules.unitDamageMultiplier },
        { Vars.state.rules.unitDamageMultiplier = it },
        { team -> Vars.state.rules.teams[team].unitDamageMultiplier },
        { team, value -> Vars.state.rules.teams[team].unitDamageMultiplier = value },
        { team -> Vars.state.rules.unitDamage(team) }
    ),
    GodMultiplier(
        "单位坠毁伤害倍率",
        "影响单位坠毁伤害",
        { Vars.state.rules.unitCrashDamageMultiplier },
        { Vars.state.rules.unitCrashDamageMultiplier = it },
        { team -> Vars.state.rules.teams[team].unitCrashDamageMultiplier },
        { team, value -> Vars.state.rules.teams[team].unitCrashDamageMultiplier = value },
        { team -> Vars.state.rules.unitCrashDamage(team) }
    ),
    GodMultiplier(
        "单位建造速度倍率",
        "影响单位建造速度",
        { Vars.state.rules.unitBuildSpeedMultiplier },
        { Vars.state.rules.unitBuildSpeedMultiplier = it },
        { team -> Vars.state.rules.teams[team].unitBuildSpeedMultiplier },
        { team, value -> Vars.state.rules.teams[team].unitBuildSpeedMultiplier = value },
        { team -> Vars.state.rules.unitBuildSpeed(team) }
    ),
    GodMultiplier(
        "单位造价倍率",
        "影响单位生产成本",
        { Vars.state.rules.unitCostMultiplier },
        { Vars.state.rules.unitCostMultiplier = it },
        { team -> Vars.state.rules.teams[team].unitCostMultiplier },
        { team, value -> Vars.state.rules.teams[team].unitCostMultiplier = value },
        { team -> Vars.state.rules.unitCost(team) }
    ),
    GodMultiplier(
        "单位采矿速度倍率",
        "影响单位采矿速度",
        { Vars.state.rules.unitMineSpeedMultiplier },
        { Vars.state.rules.unitMineSpeedMultiplier = it },
        { team -> Vars.state.rules.teams[team].unitMineSpeedMultiplier },
        { team, value -> Vars.state.rules.teams[team].unitMineSpeedMultiplier = value },
        { team -> Vars.state.rules.unitMineSpeed(team) }
    ),
    GodMultiplier(
        "建造速度倍率",
        "影响方块建造速度",
        { Vars.state.rules.buildSpeedMultiplier },
        { Vars.state.rules.buildSpeedMultiplier = it },
        { team -> Vars.state.rules.teams[team].buildSpeedMultiplier },
        { team, value -> Vars.state.rules.teams[team].buildSpeedMultiplier = value },
        { team -> Vars.state.rules.buildSpeed(team) }
    ),
    GodMultiplier(
        "建造消耗倍率",
        "影响全局建造资源消耗",
        { Vars.state.rules.buildCostMultiplier },
        { Vars.state.rules.buildCostMultiplier = it },
        null,
        null,
        null
    ),
    GodMultiplier(
        "太阳能发电倍率",
        "影响太阳能板等太阳能发电输出",
        { Vars.state.rules.solarMultiplier },
        { Vars.state.rules.solarMultiplier = it },
        null,
        null,
        null
    ),
    GodMultiplier(
        "拆除返还倍率",
        "影响拆除建筑时返还资源比例",
        { Vars.state.rules.deconstructRefundMultiplier },
        { Vars.state.rules.deconstructRefundMultiplier = it },
        null,
        null,
        null
    ),
)

private fun godFmt(value: Float): String = "%.3f".format(value).trimEnd('0').trimEnd('.')

private fun syncGodRules() {
    Call.setRules(Vars.state.rules)
}

private suspend fun askGodMultiplier(player: Player, field: GodMultiplier, current: Float): Float? {
    val currentText = godFmt(current)
    val raw = with(textInput) {
        textInput(
            player,
            "设置${field.label}",
            "当前值：$currentText\n请输入新的倍率，建议 0.01 ~ 1000。\n取消/留空则不修改。",
            default = currentText,
            lengthLimit = 16,
            isNumeric = false,
            timeoutMillis = 60_000,
        )
    }?.trim()
    val value = raw?.toFloatOrNull()
    if (value == null || value.isNaN() || value.isInfinite() || value < 0f) {
        player.sendMessage("[yellow]已取消或输入无效，倍率必须是不小于0的数字。")
        return null
    }
    return value.coerceAtMost(1000f)
}

private suspend fun askGodTeam(player: Player): Team? {
    val raw = with(textInput) {
        textInput(
            player,
            "选择队伍",
            "请输入队伍ID(0-255)或英文名，例如 crux / sharded / 1。\n可用于调整当前未激活的其它队伍倍率。",
            default = "",
            lengthLimit = 24,
            isNumeric = false,
            timeoutMillis = 60_000,
        )
    }?.trim()
    if (raw.isNullOrBlank()) return null
    raw.toIntOrNull()?.let { id ->
        if (id !in 0..255 || id !in Team.all.indices) {
            player.sendMessage("[red]队伍ID超出范围：$id，请输入 0-255。")
            return null
        }
        return Team.get(id)
    }
    val team = Team.all.firstOrNull {
        it.name.equals(raw, ignoreCase = true) || it.localized().equals(raw, ignoreCase = true)
    }
    if (team == null) player.sendMessage("[red]未找到队伍：$raw")
    return team
}

private fun godTeamCandidates(): List<Team> {
    val teams = linkedSetOf<Team>()
    teams += Vars.state.rules.defaultTeam
    teams += Vars.state.rules.waveTeam
    Team.baseTeams.forEach { team ->
        if (team != Team.derelict) teams += team
    }
    Groups.player.forEach { teams += it.team() }
    Team.all.forEach { team ->
        if (team != Team.derelict && team.active()) teams += team
    }
    return teams.filter { it != Team.derelict }.distinctBy { it.id }.sortedBy { it.id }
}

private fun godTeamName(team: Team): String = "${team.name}[gray](id:${team.id})[]"

private fun clearHiddenBuildItemsCompat(rules: Any): Int {
    val field = runCatching { rules.javaClass.getField("hiddenBuildItems") }.getOrNull() ?: return 0
    val value = field.get(rules) ?: return 0
    val size = runCatching { value.javaClass.getField("size").getInt(value) }
        .getOrElse { (value as? Collection<*>)?.size ?: 0 }
    runCatching { value.javaClass.methods.firstOrNull { it.name == "clear" && it.parameterCount == 0 }?.invoke(value) }
    return size
}

private fun openAllTechLimits(operator: Player, allPlanets: Boolean = false): String {
    val rules = Vars.state.rules
    val planet = rules.planet
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
    if (allPlanets) {
        // 原版注释：rules.planet 为 sun 时启用 mixed tech，两个星球科技互通。
        rules.planet = Planets.sun
    }
    fun shouldResearch(content: UnlockableContent): Boolean =
        allPlanets || content.isOnPlanet(planet)
    Vars.content.blocks().forEach { if (shouldResearch(it)) rules.researched.add(it) }
    Vars.content.units().forEach { if (shouldResearch(it)) rules.researched.add(it) }
    Vars.content.items().forEach { if (shouldResearch(it)) rules.researched.add(it) }
    Vars.content.liquids().forEach { if (shouldResearch(it)) rules.researched.add(it) }
    val researchedAdded = (rules.researched.size - researchedBefore).coerceAtLeast(0)
    syncGodRules()
    val scopeText = if (allPlanets) "全部星球科技/建造限制" else "当前星球(${planet.localizedName})科技/建造限制"
    broadcast(
        "[gold][神权][white]{player.name}[gold] 开放了$scopeText。".with("player" to operator),
        quite = true
    )
    return "已清理禁用方块 $bannedBlocks 个、禁用单位 $bannedUnits 个、隐藏建造物品 $hiddenItems 个，并新增标记已研究内容 $researchedAdded 个。"
}

private suspend fun openGodGlobalMenu(player: Player) {
    if (!godMenuAllowed(player)) {
        player.sendMessage("[red]权限不足：神权菜单仅限4级信任等级/已登录原生admin使用。")
        return
    }
    MenuBuilder<Unit>("神权菜单 - 全局规则倍率") {
        msg = "[cyan]修改当前地图全局规则倍率。修改后会立即同步规则。"
        godMultipliers.forEach { field ->
            option("[gold]${field.label}\n[gray]当前：${godFmt(field.globalGetter())} | ${field.desc}") {
                val value = askGodMultiplier(player, field, field.globalGetter()) ?: run {
                    openGodGlobalMenu(player)
                    return@option
                }
                field.globalSetter(value)
                syncGodRules()
                broadcast("[gold][神权][white]{player.name}[gold] 将全局 ${field.label} 设置为 [white]${godFmt(value)}".with("player" to player), quite = true)
                openGodGlobalMenu(player)
            }
            newRow()
        }
        option("返回") { openGodMenu(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openGodTeamMenu(player: Player, team: Team) {
    if (!godMenuAllowed(player)) {
        player.sendMessage("[red]权限不足：神权菜单仅限4级信任等级/已登录原生admin使用。")
        return
    }
    val fields = godMultipliers.filter { it.teamGetter != null && it.teamSetter != null }
    MenuBuilder<Unit>("神权菜单 - 队伍倍率") {
        msg = "[cyan]队伍：[white]${godTeamName(team)}\n[gray]队伍倍率会与全局倍率共同影响实际效果。"
        fields.forEach { field ->
            val teamValue = field.teamGetter!!.invoke(team)
            val effective = field.effectiveGetter?.invoke(team)
            val effectiveText = effective?.let { " | 实际：${godFmt(it)}" } ?: ""
            option("[gold]${field.label}\n[gray]队伍：${godFmt(teamValue)}$effectiveText") {
                val value = askGodMultiplier(player, field, teamValue) ?: run {
                    openGodTeamMenu(player, team)
                    return@option
                }
                field.teamSetter!!.invoke(team, value)
                syncGodRules()
                broadcast(
                    "[gold][神权][white]{player.name}[gold] 将队伍 [white]${team.name}[gold] 的 ${field.label} 设置为 [white]${godFmt(value)}".with("player" to player),
                    quite = true
                )
                openGodTeamMenu(player, team)
            }
            newRow()
        }
        option("返回队伍列表") { openGodTeamChooseMenu(player) }
        option("返回主菜单") { openGodMenu(player) }
    }.sendTo(player, 60_000)
}

private suspend fun openGodTeamChooseMenu(player: Player) {
    if (!godMenuAllowed(player)) {
        player.sendMessage("[red]权限不足：神权菜单仅限4级信任等级/已登录原生admin使用。")
        return
    }
    val teams = godTeamCandidates()
    val choices: List<Team?> = listOf(null) + teams
    PagedMenuBuilder(choices) { team ->
        if (team == null) {
            option("[accent]按队伍ID/名称输入\n[gray]可输入 0-255 手动选择队伍") {
                val selected = askGodTeam(player)
                if (selected == null) openGodTeamChooseMenu(player)
                else openGodTeamMenu(player, selected)
            }
        } else {
            option("[white]${godTeamName(team)}\n[gray]点击调整该队伍倍率") {
                openGodTeamMenu(player, team)
            }
        }
    }.apply {
        title = "神权菜单 - 选择队伍"
        msg = "[cyan]可调整当前地图中其它队伍/敌对队伍的单位、建筑血量和攻击等倍率；也可输入 0-255 队伍ID手动选择。"
        sendTo(player, 60_000)
    }
}

private suspend fun openGodMenu(player: Player) {
    if (!godMenuAllowed(player)) {
        player.sendMessage("[red]权限不足：神权菜单仅限4级信任等级/已登录原生admin使用。")
        return
    }
    val editorOn = with(funRules) { isSandboxEnabled() }
    val infiniteOn = with(funRules) { isInfiniteFireEnabled() }
    MenuBuilder<Unit>("神权菜单") {
        msg = """
            |[gold]当前地图规则控制台
            |[cyan]建筑血量：[white]${godFmt(Vars.state.rules.blockHealthMultiplier)}  [cyan]建筑伤害：[white]${godFmt(Vars.state.rules.blockDamageMultiplier)}
            |[cyan]单位生命：[white]${godFmt(Vars.state.rules.unitHealthMultiplier)}  [cyan]单位攻击：[white]${godFmt(Vars.state.rules.unitDamageMultiplier)}
            |[cyan]太阳能：[white]${godFmt(Vars.state.rules.solarMultiplier)}  [cyan]拆除返还：[white]${godFmt(Vars.state.rules.deconstructRefundMultiplier)}
            |[cyan]编辑器模式：[white]${if (editorOn) "开启" else "关闭"}  [cyan]无限火力promax：[white]${if (infiniteOn) "开启" else "关闭"}
        """.trimMargin()
        option("全局规则倍率\n[gray]建筑/单位血量、攻击、建造等全局倍率") { openGodGlobalMenu(player) }
        newRow()
        option("队伍规则倍率\n[gray]调整其它队伍/敌对队伍的血量、攻击等倍率") { openGodTeamChooseMenu(player) }
        newRow()
        option("开放当前星球科技\n[gray]只开放当前星球相关科技/建造限制") {
            player.sendMessage("[green]${openAllTechLimits(player, allPlanets = false)}")
            openGodMenu(player)
        }
        newRow()
        option("开放全部科技\n[gray]切换为混合科技，开放双星球/全部内容") {
            player.sendMessage("[green]${openAllTechLimits(player, allPlanets = true)}")
            openGodMenu(player)
        }
        newRow()
        option("${if (editorOn) "关闭" else "开启"}编辑器模式\n[gray]长期切换当前地图编辑器模式") {
            if (editorOn) with(funRules) { disableSandbox(player.plainName()) }
            else with(funRules) { enableSandbox(0L, player.plainName()) }
            openGodMenu(player)
        }
        newRow()
        option("${if (infiniteOn) "关闭" else "开启"}无限火力promax\n[gray]长期切换当前地图无限火力模式") {
            if (infiniteOn) with(funRules) { disableInfiniteFire(player.plainName()) }
            else with(funRules) { enableInfiniteFire(0L, player.plainName()) }
            openGodMenu(player)
        }
        newRow()
        option("返回技能列表") { openSkillMainMenu(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}


onEnable {
    SkillMainMenuRegistry.register(
        SkillMainMenuEntry(
            "godmenu",
            "神权菜单\n[gray]信任4级专用：调整地图倍率、科技限制、编辑器/无限火力",
            visible = { godMenuAllowed(it) },
            action = { openGodMenu(it) },
        )
    )
}

onDisable { SkillMainMenuRegistry.unregister("godmenu") }

// 神权菜单：信任4级专用，不放入“管理员技能”分类。
command("godmenu", "神权菜单".with(), commands = SkillCommands) {
    aliases = listOf("神权菜单", "god", "godmenu")
    attr(ClientOnly)
    skillBody {
        if (!godMenuAllowed(player)) returnReply("[red]权限不足：神权菜单仅限4级信任等级/已登录原生admin使用。".with())
        openGodMenu(player)
    }
}

command("examtime", "管理员技能：考试时间！".with(), commands = SkillCommands) {
    aliases = listOf("考试时间", "全员高考", "examTime")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val participants = Groups.player.toList().map {
            val score = Random.nextInt(0, 751)
            ExamResult(PlayerData[it].id, it.name, score, gaokaoReward(score))
        }.shuffled()
        if (participants.isEmpty()) returnReply("[yellow]当前没有可参加高考的玩家。".with())
        broadcast("[yellow]{player.name}[white]：高考时间！，所有玩家开始参加高考！".with("player" to player), quite = true)
        launch(Dispatchers.game) {
            participants.forEach { result ->
                delay(1_000L)
                broadcast("[yellow]${result.name}[white]在高考中取得了[accent]${result.score}分[white]的傲人成绩！".with(), quite = true)
            }
            val top = participants.sortedByDescending { it.score }.take(3)
            top.forEach { result ->
                with(trustPoint) { addTrustPoints(result.uid, result.reward, "Skill:examtime") }
            }
            val champion = top.first()
            val topNames = top.joinToString(",") { it.name }
            val rewards = top.joinToString("，") { "${it.reward}MDC" }
            broadcast(
                "[gold]考试完毕，[white]${champion.name}[gold]是高考状元！前三：[white]$topNames[gold]，他们的奖励分别为：[white]$rewards[gold]！".with(),
                quite = true
            )
        }
    }
}

command("source", "管理员技能：物品源".with(), commands = SkillCommands) {
    aliases = listOf("物品源")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        setBlockSquare(player, Blocks.itemSource, -1..1)
        broadcastSkill("物品源")
    }
}

command("ecore", "管理员技能：E星核心".with(), commands = SkillCommands) {
    aliases = listOf("E星核心", "核心")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        setBlockSquare(player, Blocks.coreBastion, -1..1)
        broadcastSkill("E星核心")
    }
}

command("invincible", "管理员技能：无敌".with(), commands = SkillCommands) {
    aliases = listOf("无敌")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val duration = 500_000f
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        unit.apply {
            apply(StatusEffects.invincible, duration)
            apply(StatusEffects.overclock, duration)
            apply(StatusEffects.shielded, duration)
            apply(StatusEffects.overdrive, duration)
            apply(StatusEffects.fast, duration)
            apply(StatusEffects.boss, duration)
        }
        broadcastSkill("无敌")
    }
}

command("freeSkillCost", "管理员技能：全场技能消费买单".with(), commands = SkillCommands) {
    aliases = listOf("技能买单", "买单", "freecost")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        SkillCostManager.enableFreeCost(player.plainName())
        broadcast("[gold]全场技能消费由 [white]{player.name}[gold] 买单！本局游戏释放技能不消耗 MDC。".with("player" to player), quite = true)
    }
}

command("doublemdcreward", "管理员技能：本局结算MDC翻倍".with(), commands = SkillCommands) {
    aliases = listOf("结算翻倍", "MDC结算翻倍", "doublemdc", "doubleReward")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        Vars.state.rules.tags.put("@doubleMdcReward", "true")
        broadcast("[gold][管理员技能][white]{player.name}[gold] 已开启本局贡献结算 MDC 翻倍。".with("player" to player), quite = true)
    }
}

command("killallunits", "管理员技能：击杀所有单位".with(), commands = SkillCommands) {
    aliases = listOf("击杀所有单位", "clearunits")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val count = with(funRules) { killAllUnits() }
        broadcast("[red][管理员技能][white]{player.name}[red] 击杀了所有单位，共 [yellow]{count}[red] 个。".with("player" to player, "count" to count), quite = true)
    }
}

command("infinitefire", "管理员技能：无限火力promax".with(), commands = SkillCommands) {
    aliases = listOf("无限火力", "firepower")
    usage = "[on/off]"
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        when (arg.firstOrNull()?.lowercase()) {
            "off", "close", "disable", "0", "false", "关", "关闭" -> {
                val disabled = with(funRules) { disableInfiniteFire(player.plainName()) }
                if (!disabled) returnReply("[yellow]当前没有正在生效的无限火力。".with())
            }
            "on", "open", "enable", "1", "true", "开", "开启", null -> {
                with(funRules) { enableInfiniteFire(120_000L, player.plainName()) }
            }
            else -> returnReply("[red]用法：/skill infinitefire [on/off]".with())
        }
    }
}

command("wallkillerpro", "管理员技能：墙体粉碎者pro".with(), commands = SkillCommands) {
    aliases = listOf("墙体粉碎者pro", "强力破墙")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val removed = smashWalls(player, -2..2)
        player.sendMessage("[green]已粉碎周围5x5墙壁/天然墙：[white]$removed")
        broadcastSkill("墙体粉碎者pro")
    }
}

command("daoshengyi", "管理员技能：道生一....".with(), commands = SkillCommands) {
    aliases = listOf("道生一", "道生一....", "mono雨")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        launch(Dispatchers.game) {
            repeat(20) {
                spawnAround(UnitTypes.mono, player, 1, 32f) { unit ->
                    launch(Dispatchers.game) {
                        delay(20_000L)
                        if (unit.isValid && !unit.dead) unit.kill()
                    }
                }
                delay(1_000L)
            }
        }
        broadcastSkill("道生一....")
    }
}

command("powersource", "管理员技能：现在的发电量是1m！电力，轻而易举啊".with(), commands = SkillCommands) {
    aliases = listOf("电力轻而易举", "电力源", "power-source")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        if (!placeBlockAtPlayer(player, Blocks.powerSource, requireAir = true)) {
            returnReply("[red]脚下已有方块，无法放置 power-source".with())
        }
        broadcastSkill("现在的发电量是1m！电力，轻而易举啊")
    }
}

command("floodon", "管理员技能：开启洪水地图脚本".with(), commands = SkillCommands) {
    aliases = listOf("开启洪水", "floodOn")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val ok = with(funRules) { setFloodMode(true) }
        reply(if (ok) "[green]已尝试开启洪水地图脚本。".with() else "[red]洪水地图脚本加载失败，请查看日志。".with())
    }
}

command("floodoff", "管理员技能：关闭洪水地图脚本".with(), commands = SkillCommands) {
    aliases = listOf("关闭洪水", "floodOff")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val ok = with(funRules) { setFloodMode(false) }
        reply(if (ok) "[green]已尝试关闭洪水地图脚本。".with() else "[red]洪水地图脚本关闭失败，请查看日志。".with())
    }
}

command("lordon", "管理员技能：开启Lord地图脚本".with(), commands = SkillCommands) {
    aliases = listOf("开启lord", "lordOn", "lordofwar")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val ok = with(funRules) { setLordOfWarMode(true) }
        reply(
            if (ok) "[green]已尝试加载 Lord of War 脚本 mapScript/14668。".with()
            else "[red]加载 Lord of War 脚本失败，请查看日志。".with()
        )
    }
}

command("lordoff", "管理员技能：关闭Lord地图脚本".with(), commands = SkillCommands) {
    aliases = listOf("关闭lord", "lordOff")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val ok = with(funRules) { setLordOfWarMode(false) }
        reply(if (ok) "[green]已尝试关闭 Lord of War 脚本。".with() else "[red]关闭 Lord of War 脚本失败，请查看日志。".with())
    }
}

command("addnoskill", "管理员技能：开启当前地图noskill限制".with(), commands = SkillCommands) {
    aliases = listOf("开启noskill", "noSkillOn")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val changed = with(funRules) { addNoSkillsTag() }
        val msg = if (changed) "[yellow][管理员技能][white]{player.name}[yellow] 已开启当前地图 @noSkills 限制。"
        else "[yellow][管理员技能][white]{player.name}[yellow] 当前地图已经存在 @noSkills 标签。"
        broadcast(msg.with("player" to player), quite = true)
    }
}

command("removenoskill", "管理员技能：解除当前地图noskill限制".with(), commands = SkillCommands) {
    aliases = listOf("解除noskill", "noSkillOff")
    attr(ClientOnly)
    skillBody {
        if (!skillAdmin(player)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能使用管理员技能".with())
        val had = with(funRules) { removeNoSkillsTag() }
        val msg = if (had) "[yellow][管理员技能][white]{player.name}[yellow] 已解除当前地图 @noSkills 限制。"
        else "[yellow][管理员技能][white]{player.name}[yellow] 当前地图没有 @noSkills 标签。"
        broadcast(msg.with("player" to player), quite = true)
    }
}
