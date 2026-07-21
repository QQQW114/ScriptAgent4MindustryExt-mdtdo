package wayzer.map

import arc.struct.ObjectMap
import arc.util.Time
import mindustry.Vars
import mindustry.entities.bullet.BulletType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Building
import mindustry.gen.Groups
import mindustry.type.Item
import mindustry.type.Liquid
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.blocks.units.UnitAssembler
import mindustry.world.blocks.units.Reconstructor
import mindustry.world.blocks.units.UnitFactory
import mindustry.world.consumers.Consume
import mindustry.world.consumers.ConsumeItemFilter
import mindustry.world.consumers.ConsumeItems
import mindustry.world.consumers.ConsumeLiquid
import mindustry.world.consumers.ConsumeLiquidFilter
import mindustry.world.consumers.ConsumeLiquids
import mindustry.world.consumers.ConsumePower

name = "临时玩法规则工具"

data class ManualMapScriptResult(
    val scriptId: String,
    val success: Boolean,
    val message: String,
    val restoredScripts: List<String> = emptyList(),
)

private data class InfiniteFireSnapshot(
    val unitAmmo: Boolean?,
    val unitDamageMultiplier: Float,
    val blockDamageMultiplier: Float,
    val teamRules: Map<Int, TeamRuleSnapshot>,
)

private data class SandboxSnapshot(
    val infiniteResources: Boolean,
    val instantBuild: Boolean,
    val editor: Boolean,
    val allowEditRules: Boolean,
    val teamRules: Map<Int, TeamRuleSnapshot>,
)

private data class TeamRuleSnapshot(
    val cheat: Boolean,
    val fillItems: Boolean,
    val infiniteResources: Boolean,
    val infiniteAmmo: Boolean?,
    val unitDamageMultiplier: Float,
    val blockDamageMultiplier: Float,
    val unitFactoryActivationDelay: Float,
    val buildSpeedMultiplier: Float,
)

data class MapScriptStatus(
    val scriptId: String,
    val state: String,
    val failReason: String?,
)

private var infiniteFireSnapshot: InfiniteFireSnapshot? = null
private var sandboxSnapshot: SandboxSnapshot? = null
private var infiniteFireToken = 0
private var sandboxToken = 0
private var infiniteFireLastMaintainMillis = 0L
private var infiniteFireProMax = false
private var infiniteFireModeName = "无限火力"

private val infiniteFireMaintainIntervalMillis = 250L
private val infiniteFireHeatRequirements = mutableMapOf<Turret, Float>()

private val safeMapScriptIdPattern = Regex("[A-Za-z0-9_\\-/]+")

private fun getOptionalBooleanField(target: Any, fieldName: String): Boolean? =
    runCatching { target.javaClass.getField(fieldName).getBoolean(target) }.getOrNull()

private fun setOptionalBooleanField(target: Any, fieldName: String, value: Boolean) {
    runCatching { target.javaClass.getField(fieldName).setBoolean(target, value) }
}

fun normalizeMapScriptId(raw: String): String? {
    var fixed = raw.trim()
        .replace('\\', '/')
        .removeSuffix(".kts")
        .removePrefix("scripts/")
        .trim('/')
    if (fixed.isBlank()) return null
    if (fixed.contains("..") || fixed.contains(':') || !safeMapScriptIdPattern.matches(fixed)) return null
    if (fixed.startsWith("mapScript/")) return fixed
    if (fixed.startsWith("tags/")) return "mapScript/$fixed"
    return "mapScript/$fixed"
}

private suspend fun disableMapScriptOnly(info: ScriptInfo): List<String> {
    val protectedEnabled = ScriptRegistry.allScripts {
        it != info && it.enabled && (!it.id.startsWith("mapScript/") || !it.dependsOn(info))
    }.toList()

    ScriptManager.disableScript(info, "手动关闭地图模式")

    val accidentallyDisabled = protectedEnabled.filter { !it.enabled && it.scriptState != ScriptState.Removed }
    if (accidentallyDisabled.isNotEmpty()) {
        ScriptManager.transactionV2 { enable(accidentallyDisabled) }.printResult()
    }
    return accidentallyDisabled.map { it.id }
}

suspend fun setMapScriptEnabled(raw: String, enabled: Boolean): ManualMapScriptResult {
    val scriptId = normalizeMapScriptId(raw)
        ?: return ManualMapScriptResult(raw, false, "脚本ID不合法，只允许 mapScript 下的相对路径")

    // 允许运维刚复制脚本后直接在游戏内加载。
    ScriptRegistry.scanRoot()
    val info = ScriptRegistry.getScriptInfo(scriptId)
        ?: return ManualMapScriptResult(scriptId, false, "不存在脚本 scripts/$scriptId.kts")

    if (enabled) {
        MindustryDispatcher.safeBlocking {
            ScriptManager.transactionV2 { enable(info) }.printResult()
        }
    } else {
        // 关闭地图模式时只停用目标脚本本身；若 ScriptAgent 递归禁用误伤了依赖/常驻脚本，
        // 立即恢复原本已启用且不依赖该地图脚本的脚本，避免连带关闭 ContentsTweaker 等公共能力。
        val restored = MindustryDispatcher.safeBlocking {
            disableMapScriptOnly(info)
        }
        val message = if (restored.isEmpty()) "已停用"
        else "已停用，并恢复被连带关闭的脚本: ${restored.joinToString()}"
        return ManualMapScriptResult(scriptId, true, message, restored)
    }

    val fail = info.failReason
    return if (enabled && fail != null) {
        ManualMapScriptResult(scriptId, false, "加载失败：$fail")
    } else {
        ManualMapScriptResult(scriptId, true, if (enabled) "已尝试加载并启用" else "已尝试停用")
    }
}

private fun affectedPlayerTeams(): List<Team> {
    val teams = linkedSetOf<Team>()
    teams += Vars.state.rules.defaultTeam
    Groups.player.forEach { teams += it.team() }
    Team.all.forEach { team ->
        if (team != Team.derelict && team.active()) teams += team
    }
    return teams.filter { it != Team.derelict }
}

private fun snapshotTeamRules(teams: List<Team>): Map<Int, TeamRuleSnapshot> =
    teams.associate { team ->
        val rule = Vars.state.rules.teams[team]
        team.id to TeamRuleSnapshot(
            cheat = rule.cheat,
            fillItems = rule.fillItems,
            infiniteResources = rule.infiniteResources,
            infiniteAmmo = getOptionalBooleanField(rule, "infiniteAmmo"),
            unitDamageMultiplier = rule.unitDamageMultiplier,
            blockDamageMultiplier = rule.blockDamageMultiplier,
            unitFactoryActivationDelay = rule.unitFactoryActivationDelay,
            buildSpeedMultiplier = rule.buildSpeedMultiplier,
        )
    }

private fun restoreTeamRules(snapshot: Map<Int, TeamRuleSnapshot>): List<String> {
    val errors = mutableListOf<String>()
    snapshot.forEach { (teamId, old) ->
        runCatching {
            val team = Team.get(teamId) ?: error("队伍不存在")
            val rule = Vars.state.rules.teams[team] ?: error("队伍规则不存在")
            rule.cheat = old.cheat
            rule.fillItems = old.fillItems
            rule.infiniteResources = old.infiniteResources
            old.infiniteAmmo?.let { setOptionalBooleanField(rule, "infiniteAmmo", it) }
            rule.unitDamageMultiplier = old.unitDamageMultiplier
            rule.blockDamageMultiplier = old.blockDamageMultiplier
            rule.unitFactoryActivationDelay = old.unitFactoryActivationDelay
            rule.buildSpeedMultiplier = old.buildSpeedMultiplier
        }.onFailure { err ->
            errors += "team=$teamId ${err.message ?: err.javaClass.simpleName}"
        }
    }
    return errors
}

private fun restoreInfiniteFireHeatRequirements(): List<String> {
    if (infiniteFireHeatRequirements.isEmpty()) return emptyList()
    val errors = mutableListOf<String>()
    infiniteFireHeatRequirements.forEach { (turret, oldHeatRequirement) ->
        runCatching {
            turret.heatRequirement = oldHeatRequirement
        }.onFailure { err ->
            errors += "${turret.name}:${err.message ?: err.javaClass.simpleName}"
        }
    }
    infiniteFireHeatRequirements.clear()
    return errors
}

private fun rememberAndDisableTurretHeatRequirement(turret: Turret) {
    if (turret.heatRequirement <= 0f) return
    if (!infiniteFireHeatRequirements.containsKey(turret)) {
        infiniteFireHeatRequirements[turret] = turret.heatRequirement
    }
    // v158 的部分热量炮塔即使 TeamRule.cheat=true，也会先因 heatReq=0 被 canConsume 拦下。
    // 无限火力期间临时取消该方块类型的热量门槛，结束或重置时恢复。
    turret.heatRequirement = 0f
}

private fun bulletScore(type: BulletType?): Float {
    if (type == null) return 0f
    return type.damage +
            type.splashDamage * 0.75f +
            type.lightning * type.lightningDamage * 0.5f +
            type.fragBullets * (type.fragBullet?.damage ?: 0f) * 0.35f +
            type.ammoMultiplier.coerceAtLeast(0f) * 0.1f
}

private fun bestItemAmmo(ammoTypes: ObjectMap<Item, BulletType>, current: Item?): Item? {
    if (current != null && ammoTypes.containsKey(current)) return current
    var bestItem: Item? = null
    var bestScore = Float.NEGATIVE_INFINITY
    for (entry in ammoTypes.entries()) {
        val score = bulletScore(entry.value)
        if (bestItem == null || score > bestScore) {
            bestItem = entry.key
            bestScore = score
        }
    }
    return bestItem
}

private fun bestLiquidAmmo(ammoTypes: ObjectMap<Liquid, BulletType>, current: Liquid?): Liquid? {
    if (current != null && ammoTypes.containsKey(current)) return current
    var bestLiquid: Liquid? = null
    var bestScore = Float.NEGATIVE_INFINITY
    for (entry in ammoTypes.entries()) {
        val score = bulletScore(entry.value)
        if (bestLiquid == null || score > bestScore) {
            bestLiquid = entry.key
            bestScore = score
        }
    }
    return bestLiquid
}

private fun itemTargetAmount(build: Building, item: Item, requested: Int = 1): Int {
    val accepted = runCatching { build.getMaximumAccepted(item) }.getOrDefault(0)
    return maxOf(requested, accepted, build.block.itemCapacity).coerceAtLeast(1)
}

private fun setItemAtLeast(build: Building, item: Item, amount: Int = 1) {
    if (!build.block.hasItems) return
    val target = amount.coerceAtLeast(1)
    if (build.items.get(item) < target) {
        build.items.set(item, target)
    }
}

private fun liquidTargetAmount(build: Building, requested: Float = 1f): Float {
    return maxOf(requested, build.block.liquidCapacity, 10f)
}

private fun setLiquidAtLeast(build: Building, liquid: Liquid, amount: Float = 1f) {
    if (!build.block.hasLiquids) return
    val target = amount.coerceAtLeast(0.001f)
    if (build.liquids.get(liquid) < target) {
        build.liquids.set(liquid, target)
    }
}

private fun firstFilteredItem(consumer: ConsumeItemFilter): Item? {
    for (item in Vars.content.items()) {
        if (consumer.filter.get(item)) return item
    }
    return null
}

private fun firstFilteredLiquid(build: Building, consumer: ConsumeLiquidFilter): Liquid? {
    val current = build.liquids.current()
    if (build.liquids.currentAmount() > 0.001f && consumer.filter.get(current)) return current
    for (liquid in Vars.content.liquids()) {
        if (consumer.filter.get(liquid)) return liquid
    }
    return null
}

private fun fillConsumerInput(build: Building, consumer: Consume) {
    when (consumer) {
        is ConsumeItems -> consumer.items.forEach { stack ->
            setItemAtLeast(build, stack.item, itemTargetAmount(build, stack.item, stack.amount))
        }

        is ConsumeItemFilter -> firstFilteredItem(consumer)?.let { item ->
            setItemAtLeast(build, item, itemTargetAmount(build, item, 1))
        }

        is ConsumeLiquid -> setLiquidAtLeast(
            build,
            consumer.liquid,
            liquidTargetAmount(build, consumer.amount * 180f)
        )

        is ConsumeLiquids -> consumer.liquids.forEach { stack ->
            setLiquidAtLeast(build, stack.liquid, liquidTargetAmount(build, stack.amount * 180f))
        }

        is ConsumeLiquidFilter -> firstFilteredLiquid(build, consumer)?.let { liquid ->
            setLiquidAtLeast(build, liquid, liquidTargetAmount(build, consumer.amount * 180f))
        }

        is ConsumePower -> {
            if (build.power != null) build.power.status = 1f
            build.shouldConsumePower = true
        }
    }
}

private fun fillConsumerInputs(build: Building) {
    build.block.consumers?.forEach { fillConsumerInput(build, it) }
}

private fun fillItemTurret(build: ItemTurret.ItemTurretBuild) {
    val turret = build.block as? ItemTurret ?: return
    rememberAndDisableTurretHeatRequirement(turret)
    val item = bestItemAmmo(turret.ammoTypes, build.getAmmoContent() as? Item) ?: return
    val maxAdds = (turret.maxAmmo + turret.ammoPerShot + 4).coerceIn(1, 240)
    var added = 0
    while (added < maxAdds && build.acceptItem(null, item)) {
        build.handleItem(null, item)
        added++
    }
}

private fun fillLiquidTurret(build: LiquidTurret.LiquidTurretBuild) {
    val turret = build.block as? LiquidTurret ?: return
    rememberAndDisableTurretHeatRequirement(turret)
    val liquid = bestLiquidAmmo(turret.ammoTypes, build.getAmmoContent() as? Liquid) ?: return
    setLiquidAtLeast(build, liquid, liquidTargetAmount(build, turret.liquidCapacity))
}

private fun fillUnitFactory(build: UnitFactory.UnitFactoryBuild) {
    val factory = build.block as? UnitFactory ?: return
    val planIndex = build.currentPlan
    if (planIndex < 0 || planIndex >= factory.plans.size) return
    val plan = factory.plans[planIndex]
    plan.requirements.forEach { stack ->
        setItemAtLeast(build, stack.item, itemTargetAmount(build, stack.item, stack.amount))
    }
}

private fun fillUnitAssembler(build: UnitAssembler.UnitAssemblerBuild) {
    val plan = build.plan() ?: return
    plan.itemReq.forEach { stack ->
        setItemAtLeast(build, stack.item, itemTargetAmount(build, stack.item, stack.amount))
    }
    plan.liquidReq.forEach { stack ->
        setLiquidAtLeast(build, stack.liquid, liquidTargetAmount(build, stack.amount * 180f))
    }
}

private fun fillReconstructor(build: Reconstructor.ReconstructorBuild) {
    val unit = build.unit() ?: return
    unit.getTotalRequirements().forEach { stack ->
        val accepted = runCatching { build.getMaximumAccepted(stack.item) }.getOrDefault(0)
        if (accepted > 0) setItemAtLeast(build, stack.item, maxOf(stack.amount, accepted))
    }
}

private fun maintainInfiniteFireBuildings() {
    val snapshot = infiniteFireSnapshot ?: return
    val teamIds = snapshot.teamRules.keys
    Groups.build.forEach { build ->
        if (!build.isValid || build.team == Team.derelict || build.team.id !in teamIds) return@forEach

        if ((infiniteFireProMax || build is Turret.TurretBuild) && build.power != null && build.block.hasPower) {
            build.power.status = 1f
            build.shouldConsumePower = true
        }

        if (infiniteFireProMax) fillConsumerInputs(build)

        when (build) {
            is ItemTurret.ItemTurretBuild -> fillItemTurret(build)
            is LiquidTurret.LiquidTurretBuild -> fillLiquidTurret(build)
            is Turret.TurretBuild -> rememberAndDisableTurretHeatRequirement(build.block as? Turret ?: return@forEach)
            is UnitFactory.UnitFactoryBuild -> if (infiniteFireProMax) fillUnitFactory(build)
            is UnitAssembler.UnitAssemblerBuild -> if (infiniteFireProMax) fillUnitAssembler(build)
            is Reconstructor.ReconstructorBuild -> if (infiniteFireProMax) fillReconstructor(build)
        }
    }
}

fun currentMapScriptStatuses(activeOnly: Boolean = true): List<MapScriptStatus> =
    ScriptRegistry.allScripts {
        it.id.startsWith("mapScript/") &&
                (!activeOnly || it.enabled)
    }.sortedBy { it.id }.map {
        MapScriptStatus(
            scriptId = it.id,
            state = it.scriptState.toString(),
            failReason = it.failReason,
        )
    }

fun currentMapScriptStatusText(activeOnly: Boolean = true): String {
    val scripts = currentMapScriptStatuses(activeOnly)
    if (scripts.isEmpty()) return "[yellow]当前没有启用的地图脚本。"
    return buildString {
        appendLine("[cyan]当前地图脚本：[white]${scripts.size}个")
        scripts.forEachIndexed { index, status ->
            append("[gray]${index + 1}. [white]${status.scriptId}[gray] - [yellow]${status.state}")
            status.failReason?.let { append("[red] ($it)") }
            appendLine()
        }
    }.trimEnd()
}

fun killAllUnits(): Int {
    val units = Groups.unit.toList()
    units.forEach { if (it.isValid && !it.dead) it.kill() }
    return units.size
}

fun damageAllBuildings(percent: Float = 0.99f): Int {
    val builds = Groups.build.toList()
    builds.forEach { build ->
        if (build.isValid && build.health > 1f) {
            build.damage((build.health * percent.coerceIn(0f, 1f)).coerceAtLeast(1f))
        }
    }
    return builds.size
}

fun enableInfiniteFire(durationMillis: Long = 120_000L, operator: String = "系统", proMax: Boolean = true) {
    val teams = affectedPlayerTeams()
    if (infiniteFireSnapshot == null) {
        infiniteFireSnapshot = InfiniteFireSnapshot(
            unitAmmo = getOptionalBooleanField(Vars.state.rules, "unitAmmo"),
            unitDamageMultiplier = Vars.state.rules.unitDamageMultiplier,
            blockDamageMultiplier = Vars.state.rules.blockDamageMultiplier,
            teamRules = snapshotTeamRules(teams),
        )
        infiniteFireProMax = proMax
    } else {
        // 已开启期间再次延长时，可能有新队伍/新玩家队伍加入；先补快照再改 TeamRule，避免结束后无法恢复。
        infiniteFireSnapshot?.let { snapshot ->
            val missingTeams = teams.filter { it.id !in snapshot.teamRules.keys }
            if (missingTeams.isNotEmpty()) {
                infiniteFireSnapshot = snapshot.copy(teamRules = snapshot.teamRules + snapshotTeamRules(missingTeams))
            }
        }
        infiniteFireProMax = infiniteFireProMax || proMax
    }
    val base = infiniteFireSnapshot ?: return
    infiniteFireModeName = if (infiniteFireProMax) "无限火力promax" else "标准无限火力"
    infiniteFireToken++
    val token = infiniteFireToken

    // 新旧分支的弹药字段不一致：旧/客户端分支有 unitAmmo + TeamRule.infiniteAmmo，
    // 当前 v158 服务端分支没有这些字段。这里用反射做可选兼容，避免脚本编译期绑定到不存在字段。
    setOptionalBooleanField(Vars.state.rules, "unitAmmo", false)
    if (infiniteFireProMax) {
        Vars.state.rules.unitDamageMultiplier = maxOf(Vars.state.rules.unitDamageMultiplier, base.unitDamageMultiplier * 2f)
        Vars.state.rules.blockDamageMultiplier = maxOf(Vars.state.rules.blockDamageMultiplier, base.blockDamageMultiplier * 2f)
    }
    teams.forEach { team ->
        Vars.state.rules.teams[team].apply {
            setOptionalBooleanField(this, "infiniteAmmo", true)
            if (infiniteFireProMax) {
                cheat = true
                fillItems = true
                infiniteResources = true
                unitDamageMultiplier = maxOf(unitDamageMultiplier, 2f)
                blockDamageMultiplier = maxOf(blockDamageMultiplier, 2f)
                unitFactoryActivationDelay = 0f
            }
        }
    }
    Call.setRules(Vars.state.rules)
    infiniteFireLastMaintainMillis = 0L
    maintainInfiniteFireBuildings()
    val durationText = if (durationMillis <= 0L) "长期" else "${durationMillis / 1000L} 秒"
    broadcast("[purple][{mode}] [white]{operator}[purple] 已开启{mode} [white]{duration}[purple]。".with(
        "mode" to infiniteFireModeName,
        "operator" to operator,
        "duration" to durationText
    ))

    if (durationMillis <= 0L) return
    launch(Dispatchers.game) {
        delay(durationMillis.coerceAtLeast(1_000L))
        if (token != infiniteFireToken) return@launch
        infiniteFireSnapshot?.let {
            it.unitAmmo?.let { old -> setOptionalBooleanField(Vars.state.rules, "unitAmmo", old) }
            Vars.state.rules.unitDamageMultiplier = it.unitDamageMultiplier
            Vars.state.rules.blockDamageMultiplier = it.blockDamageMultiplier
            restoreTeamRules(it.teamRules)
            Call.setRules(Vars.state.rules)
        }
        restoreInfiniteFireHeatRequirements()
        infiniteFireSnapshot = null
        val modeName = infiniteFireModeName
        infiniteFireProMax = false
        infiniteFireModeName = "无限火力"
        broadcast("[green][$modeName] 已恢复当前地图原规则。".with())
    }
}

fun enableStandardInfiniteFire(durationMillis: Long = 120_000L, operator: String = "系统") =
    enableInfiniteFire(durationMillis, operator, proMax = false)

fun isInfiniteFireEnabled(): Boolean = infiniteFireSnapshot != null

fun disableInfiniteFire(operator: String = "系统"): Boolean {
    val snapshot = infiniteFireSnapshot ?: return false
    val modeName = infiniteFireModeName
    infiniteFireToken++
    val restoreErrors = mutableListOf<String>()
    snapshot.unitAmmo?.let { old ->
        runCatching { setOptionalBooleanField(Vars.state.rules, "unitAmmo", old) }
            .onFailure { restoreErrors += "unitAmmo:${it.message ?: it.javaClass.simpleName}" }
    }
    runCatching {
        Vars.state.rules.unitDamageMultiplier = snapshot.unitDamageMultiplier
        Vars.state.rules.blockDamageMultiplier = snapshot.blockDamageMultiplier
    }.onFailure { restoreErrors += "rulesMultiplier:${it.message ?: it.javaClass.simpleName}" }
    restoreErrors += restoreTeamRules(snapshot.teamRules)
    restoreErrors += restoreInfiniteFireHeatRequirements()
    val setRulesError = runCatching { Call.setRules(Vars.state.rules) }.exceptionOrNull()
    if (setRulesError != null) restoreErrors += "setRules:${setRulesError.message ?: setRulesError.javaClass.simpleName}"
    infiniteFireSnapshot = null
    infiniteFireProMax = false
    infiniteFireModeName = "无限火力"
    if (restoreErrors.isEmpty()) {
        broadcast("[green][{mode}] [white]{operator}[green] 已关闭{mode}并恢复原规则。".with("mode" to modeName, "operator" to operator))
    } else {
        logger.warning("关闭$modeName 时部分恢复异常: ${restoreErrors.joinToString("; ")}")
        broadcast(
            "[yellow][{mode}] [white]{operator}[yellow] 已停止{mode}维护，但部分规则恢复出现异常；建议换图或重启以完全恢复。".with(
                "mode" to modeName,
                "operator" to operator,
            )
        )
    }
    return true
}

fun enableSandbox(durationMillis: Long = 30_000L, operator: String = "系统") {
    val teams = affectedPlayerTeams()
    if (sandboxSnapshot == null) {
        sandboxSnapshot = SandboxSnapshot(
            infiniteResources = Vars.state.rules.infiniteResources,
            instantBuild = Vars.state.rules.instantBuild,
            editor = Vars.state.rules.editor,
            allowEditRules = Vars.state.rules.allowEditRules,
            teamRules = snapshotTeamRules(teams),
        )
    } else {
        sandboxSnapshot?.let { snapshot ->
            val missingTeams = teams.filter { it.id !in snapshot.teamRules.keys }
            if (missingTeams.isNotEmpty()) {
                sandboxSnapshot = snapshot.copy(teamRules = snapshot.teamRules + snapshotTeamRules(missingTeams))
            }
        }
    }
    sandboxToken++
    val token = sandboxToken

    Vars.state.rules.infiniteResources = true
    Vars.state.rules.instantBuild = true
    Vars.state.rules.editor = true
    Vars.state.rules.allowEditRules = true
    teams.forEach { team ->
        Vars.state.rules.teams[team].apply {
            cheat = true
            fillItems = true
            infiniteResources = true
            setOptionalBooleanField(this, "infiniteAmmo", true)
            buildSpeedMultiplier = maxOf(buildSpeedMultiplier, 100f)
        }
    }
    Call.setRules(Vars.state.rules)
    val durationText = if (durationMillis <= 0L) "长期" else "${durationMillis / 1000L} 秒"
    broadcast("[gold][编辑器模式] [white]{operator}[gold] 已开启临时编辑器模式 [white]{duration}[gold]。".with(
        "operator" to operator,
        "duration" to durationText
    ))

    if (durationMillis <= 0L) return
    launch(Dispatchers.game) {
        delay(durationMillis.coerceAtLeast(1_000L))
        if (token != sandboxToken) return@launch
        sandboxSnapshot?.let {
            Vars.state.rules.infiniteResources = it.infiniteResources
            Vars.state.rules.instantBuild = it.instantBuild
            Vars.state.rules.editor = it.editor
            Vars.state.rules.allowEditRules = it.allowEditRules
            restoreTeamRules(it.teamRules)
            Call.setRules(Vars.state.rules)
        }
        sandboxSnapshot = null
        broadcast("[green][编辑器模式] 已恢复当前地图原规则。".with())
    }
}

fun isSandboxEnabled(): Boolean = sandboxSnapshot != null

fun disableSandbox(operator: String = "系统"): Boolean {
    val snapshot = sandboxSnapshot ?: return false
    sandboxToken++
    Vars.state.rules.infiniteResources = snapshot.infiniteResources
    Vars.state.rules.instantBuild = snapshot.instantBuild
    Vars.state.rules.editor = snapshot.editor
    Vars.state.rules.allowEditRules = snapshot.allowEditRules
    restoreTeamRules(snapshot.teamRules)
    Call.setRules(Vars.state.rules)
    sandboxSnapshot = null
    broadcast("[green][编辑器模式] [white]{operator}[green] 已关闭编辑器模式并恢复原规则。".with("operator" to operator))
    return true
}

fun addNoSkillsTag(): Boolean {
    val had = Vars.state.rules.tags.keys().contains("@noSkills")
    Vars.state.rules.tags.put("@noSkills", "true")
    // @noSkills 只被服务端技能预检查读取；不需要向客户端同步完整 Rules。
    return !had
}

fun removeNoSkillsTag(): Boolean {
    val had = Vars.state.rules.tags.keys().contains("@noSkills")
    Vars.state.rules.tags.remove("@noSkills")
    // @noSkills 只被服务端技能预检查读取；不需要向客户端同步完整 Rules。
    // 线上偶现使用“解除 noskill”后全员掉线，疑似热同步完整 Rules/内容补丁状态触发客户端断连。
    // 因此这里仅修改服务端 rules.tags，避免无必要的 Call.setRules。
    return had
}

fun addPureModeLevel3BlockTag(): Boolean {
    val tag = "@pureNoLevel3Skills"
    val had = Vars.state.rules.tags.keys().contains(tag)
    Vars.state.rules.tags.put(tag, "true")
    // 仅服务端技能预检查读取，不需要同步完整 Rules。
    return !had
}

fun removePureModeLevel3BlockTag(): Boolean {
    val tag = "@pureNoLevel3Skills"
    val had = Vars.state.rules.tags.keys().contains(tag)
    Vars.state.rules.tags.remove(tag)
    return had
}

fun reactorExplosionsEnabled(): Boolean = Vars.state.rules.reactorExplosions

fun setReactorExplosions(enabled: Boolean, operator: String = "系统"): Boolean {
    val changed = Vars.state.rules.reactorExplosions != enabled
    Vars.state.rules.reactorExplosions = enabled
    Call.setRules(Vars.state.rules)
    broadcast(
        "[yellow][反应堆爆炸] [white]{operator}[yellow] 已{action}当前地图反应堆爆炸。".with(
            "operator" to operator,
            "action" to if (enabled) "开启" else "关闭"
        )
    )
    return changed
}

suspend fun setFloodMode(enabled: Boolean): Boolean {
    if (enabled) Vars.state.rules.tags.put("@flood", "true")
    else {
        Vars.state.rules.tags.remove("@flood")
        Vars.state.rules.tags.remove("@floodV2")
    }
    Call.setRules(Vars.state.rules)
    return setMapScriptEnabled("tags/flood", enabled).success
}

suspend fun setLordOfWarMode(enabled: Boolean): Boolean {
    // Lord of War 当前是 mapScript/14668 的整图专用脚本，不是通用 tag。
    // 是否在运行中手动启停由管理员自行判断；此处不额外阻止。
    return setMapScriptEnabled("14668", enabled).success
}

listen(EventType.Trigger.update) {
    if (infiniteFireSnapshot == null) return@listen
    val now = Time.millis()
    if (now - infiniteFireLastMaintainMillis >= infiniteFireMaintainIntervalMillis) {
        infiniteFireLastMaintainMillis = now
        maintainInfiniteFireBuildings()
    }
}

listen<EventType.ResetEvent> {
    restoreInfiniteFireHeatRequirements()
    infiniteFireSnapshot = null
    sandboxSnapshot = null
    infiniteFireToken = 0
    sandboxToken = 0
    infiniteFireLastMaintainMillis = 0L
    infiniteFireProMax = false
    infiniteFireModeName = "无限火力"
}
