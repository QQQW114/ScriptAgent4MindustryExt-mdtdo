@file:Import("https://www.jitpack.io/", mavenRepository = true)
@file:Import("com.github.way-zer:ContentsTweaker:v3.1.2", mavenDependsSingle = true)

package coreMindustry

import arc.Events
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.serialization.Jval
import mindustry.Vars
import mindustry.ctype.Content
import mindustry.ctype.UnlockableContent
import mindustry.game.MapObjectives
import mindustry.gen.Groups
import mindustry.gen.Payloadc
import mindustry.type.Item
import mindustry.type.ItemStack
import mindustry.type.Liquid
import mindustry.type.LiquidStack
import mindustry.type.StatusEffect
import mindustry.type.UnitType
import mindustry.type.Weather
import mindustry.game.EventType
import mindustry.world.Block
import mindustry.world.blocks.environment.Floor
import mindustry.content.Blocks
import mindustry.world.modules.LiquidModule
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap


var patches: String?
    get() = state.map.tags.get("ContentsPatch")
    set(v) {
        state.map.tags.put("ContentsPatch", v)
        //back compatibility
        state.rules.tags.put("ContentsPatch", v!!)
    }
var patchList: List<String>
    get() = patches?.split(";").orEmpty()
    set(v) {
        patches = v.joinToString(";")
    }

val contentPatches = Seq<String>() //cp in maps may not load here
private var defaultContentStateBaseline: ContentStateSnapshot? = null

data class LoadedPatchInfo(
    val patch: String,
    val name: String?,
    val error: Boolean,
    val warnings: List<String>,
)

data class LoadedDataAssetInfo(
    val index: Int,
    val type: String,
    val path: String,
    val name: String,
    val fullPath: String,
    val contentType: String?,
    val loadedContent: String?,
    val error: Boolean,
    val warnings: List<String>,
    val cached: Boolean?,
    val hash: String?,
    val preview: String?,
)

data class ContentObjectSnapshot(
    val target: Any,
    val fields: Map<Field, Any?>,
    val nested: List<ContentObjectSnapshot> = emptyList(),
)

data class ContentStateSnapshot(
    val label: String,
    val createdAt: Long,
    val entries: List<ContentObjectSnapshot>,
)

/**
 * v159 DataPatcher 在 reload/load 前会注销并重新创建全部 ContentAsset 内容对象。
 * 场上单位、建筑或玩家附身仍引用旧对象时，紧随其后的完整世界同步会写出客户端已无法解析的内容 ID。
 * 因此每次运行期 Data Asset 重载前，都必须先清理旧动态内容的世界引用。
 */
data class DynamicContentCleanupReport(
    val contentCount: Int,
    var detachedPlayers: Int = 0,
    var removedUnits: Int = 0,
    var removedBuildings: Int = 0,
    var removedEnvironmentBlocks: Int = 0,
    var replacedFloors: Int = 0,
    var clearedOverlays: Int = 0,
    var removedBullets: Int = 0,
    var removedPuddles: Int = 0,
    var removedWeather: Int = 0,
    var clearedUnitItems: Int = 0,
    var clearedUnitStatuses: Int = 0,
    var clearedUnitPayloads: Int = 0,
    var clearedBuildItems: Int = 0,
    var clearedBuildLiquids: Int = 0,
    var clearedBuildPayloads: Int = 0,
    var clearedBuildConfigs: Int = 0,
    var clearedBuildPlans: Int = 0,
    var clearedRuleRefs: Int = 0,
    val failures: MutableList<String> = mutableListOf(),
    var suppressedFailures: Int = 0,
) {
    fun addFailure(message: String) {
        // 清理失败可能同时命中大量地图格/实体；限制详细项，避免防护逻辑自身放大内存压力。
        if (failures.size < 64) failures += message else suppressedFailures++
    }

    fun failureCount(): Int = failures.size + suppressedFailures

    fun changed(): Int = detachedPlayers + removedUnits + removedBuildings + removedEnvironmentBlocks + replacedFloors +
        clearedOverlays + removedBullets + removedPuddles + removedWeather + clearedUnitItems + clearedUnitStatuses +
        clearedUnitPayloads + clearedBuildItems + clearedBuildLiquids + clearedBuildPayloads + clearedBuildConfigs +
        clearedBuildPlans + clearedRuleRefs

    fun summary(): String =
        "contents=$contentCount, players=$detachedPlayers, units=$removedUnits, builds=$removedBuildings, env=$removedEnvironmentBlocks, " +
            "floors=$replacedFloors, overlays=$clearedOverlays, bullets=$removedBullets, puddles=$removedPuddles, weather=$removedWeather, " +
            "unitItems=$clearedUnitItems, statuses=$clearedUnitStatuses, unitPayloads=$clearedUnitPayloads, " +
            "buildItems=$clearedBuildItems, buildLiquids=$clearedBuildLiquids, buildPayloads=$clearedBuildPayloads, " +
            "configs=$clearedBuildConfigs, plans=$clearedBuildPlans, rules=$clearedRuleRefs, failures=${failureCount()}"
}

private val contentSnapshotSkipNames = setOf(
    // 运行时/渲染缓存，不属于 CP 数据语义；恢复这些字段收益很低，反而更容易碰到客户端/服务端差异。
    "region", "customShadowRegion", "teamRegion", "teamRegions", "variantRegions", "variantShadowRegions",
    "generatedIcons", "barMap", "buildType", "configurations", "lastConfig", "subclass", "selectScroll",
)

private val contentSnapshotFieldNames = setOf(
    // Content/UnlockableContent 基础展示与科技字段。
    "localizedName", "description", "details", "hideDetails", "alwaysUnlocked", "unlocked",
    // 常见方块基础属性。
    "requirements", "buildVisibility", "category", "size", "health", "armor", "solid", "solidifes",
    "update", "destructible", "configurable", "rotate", "canOverdrive", "targetable", "underBullets",
    "hasItems", "hasLiquids", "hasPower", "outputsPower", "consumesPower", "connectedPower",
    "itemCapacity", "liquidCapacity",
    // 工厂/液体/热量/电力相关字段，是 CP 污染最容易影响局内行为的部分。
    "craftTime", "outputItem", "outputItems", "outputLiquid", "outputLiquids", "liquidOutputDirections",
    "dumpExtraLiquid", "ignoreLiquidFullness", "consumes", "consumeBuilder", "consPower",
    "powerProduction", "heatOutput", "heatRequirement", "maxEfficiency", "baseEfficiency",
    // 炮塔/弹药/目标字段。ammoTypes 必须深拷贝 ObjectMap，避免卸载后炮塔弹药表残留。
    "ammoTypes", "shootType", "range", "minRange", "reload", "reloadTime", "shots", "burstSpacing",
    "inaccuracy", "velocityRnd", "recoil", "coolant", "coolantMultiplier", "shoot", "shootCone",
    "rotateSpeed", "targetAir", "targetGround", "maxAmmo", "ammoPerShot",
    // 物品/液体/状态效果常见可调字段。
    "color", "hardness", "cost", "flammability", "explosiveness", "charge", "radioactivity",
    "viscosity", "temperature", "heatCapacity", "effect",
    "damage", "healthMultiplier", "speedMultiplier", "reloadMultiplier", "damageMultiplier",
    "buildSpeedMultiplier", "dragMultiplier", "permanent",
)

private fun snapshotFieldsOf(target: Any): List<Field> {
    val out = mutableListOf<Field>()
    val consumerTarget = isConsumerLike(target)
    var clazz: Class<*>? = target.javaClass
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredFields.forEach { field ->
            val modifiers = field.modifiers
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic) return@forEach
            if (field.name in contentSnapshotSkipNames) return@forEach
            if (!consumerTarget && field.name !in contentSnapshotFieldNames) return@forEach
            runCatching { field.isAccessible = true }.getOrNull()
            out += field
        }
        clazz = clazz.superclass
    }
    return out
}

private fun isConsumerLike(value: Any): Boolean =
    value.javaClass.name.startsWith("mindustry.world.consumers.")

private fun cloneStackValue(value: Any?): Any? = when (value) {
    is ItemStack -> ItemStack(value.item, value.amount)
    is LiquidStack -> LiquidStack(value.liquid, value.amount)
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun newObjectMapLike(value: ObjectMap<*, *>): ObjectMap<Any?, Any?> =
    runCatching {
        value.javaClass.getDeclaredConstructor().newInstance() as ObjectMap<Any?, Any?>
    }.getOrElse { ObjectMap() }

private fun copySnapshotValue(value: Any?, nested: MutableList<ContentObjectSnapshot>, visited: IdentityHashMap<Any, Boolean>): Any? {
    cloneStackValue(value)?.let { return it }
    if (value == null) return null
    if (isConsumerLike(value) && !visited.containsKey(value)) {
        nested += captureObjectSnapshot(value, visited)
        return value
    }
    val clazz = value.javaClass
    if (clazz.isArray) {
        val length = Array.getLength(value)
        val component = clazz.componentType
        val copy = Array.newInstance(component, length)
        for (i in 0 until length) {
            val element = Array.get(value, i)
            Array.set(copy, i, copySnapshotValue(element, nested, visited))
        }
        return copy
    }
    if (value is Seq<*>) {
        val copy = Seq<Any?>()
        value.forEach { element -> copy.add(copySnapshotValue(element, nested, visited)) }
        return copy
    }
    if (value is ObjectMap<*, *>) {
        val copy = newObjectMapLike(value)
        value.entries().forEach { entry ->
            copy.put(entry.key, copySnapshotValue(entry.value, nested, visited))
        }
        return copy
    }
    return value
}

private fun copyRestoreValue(value: Any?): Any? {
    cloneStackValue(value)?.let { return it }
    if (value == null) return null
    val clazz = value.javaClass
    if (clazz.isArray) {
        val length = Array.getLength(value)
        val component = clazz.componentType
        val copy = Array.newInstance(component, length)
        for (i in 0 until length) Array.set(copy, i, copyRestoreValue(Array.get(value, i)))
        return copy
    }
    if (value is Seq<*>) {
        val copy = Seq<Any?>()
        value.forEach { copy.add(copyRestoreValue(it)) }
        return copy
    }
    if (value is ObjectMap<*, *>) {
        val copy = newObjectMapLike(value)
        value.entries().forEach { entry -> copy.put(entry.key, copyRestoreValue(entry.value)) }
        return copy
    }
    return value
}

private fun captureObjectSnapshot(target: Any, visited: IdentityHashMap<Any, Boolean>): ContentObjectSnapshot {
    visited[target] = true
    val nested = mutableListOf<ContentObjectSnapshot>()
    val fields = linkedMapOf<Field, Any?>()
    snapshotFieldsOf(target).forEach { field ->
        val value = runCatching { field.get(target) }.getOrNull()
        fields[field] = copySnapshotValue(value, nested, visited)
    }
    return ContentObjectSnapshot(target, fields, nested)
}

private fun restoreObjectSnapshot(snapshot: ContentObjectSnapshot): Int {
    var restored = 0
    snapshot.nested.forEach { restored += restoreObjectSnapshot(it) }
    snapshot.fields.forEach { (field, value) ->
        runCatching {
            field.set(snapshot.target, copyRestoreValue(value))
            restored++
        }.onFailure {
            logger.warning("内容快照字段恢复失败 ${snapshot.target.javaClass.simpleName}.${field.name}: ${it.message}")
        }
    }
    return restored
}

private fun contentSnapshotTargets(): List<Any> = buildList {
    // 主要保护 Block 单例：CP 污染最容易表现为工厂产出/耗电/电网连接/炮塔弹药残留异常。
    // UnitType 的武器/能力图很大，且杂交系统已有独立回滚链路；这里不纳入常规卸载快照，避免启动/卸载时长卡顿。
    runCatching { addAll(Vars.content.blocks()) }
    runCatching { addAll(Vars.content.items()) }
    runCatching { addAll(Vars.content.liquids()) }
    runCatching { addAll(Vars.content.statusEffects()) }
}

fun captureContentStateSnapshot(label: String = "content"): ContentStateSnapshot {
    val visited = IdentityHashMap<Any, Boolean>()
    val entries = contentSnapshotTargets().map { captureObjectSnapshot(it, visited) }
    return ContentStateSnapshot(label, System.currentTimeMillis(), entries)
}

fun restoreContentStateSnapshot(snapshot: ContentStateSnapshot, reason: String = snapshot.label): Int {
    var restored = 0
    snapshot.entries.forEach { restored += restoreObjectSnapshot(it) }
    logger.info("已恢复内容快照: ${snapshot.label} reason=$reason entries=${snapshot.entries.size} fields=$restored")
    return restored
}

fun captureContentStateSnapshotAny(label: String = "content"): Any =
    captureContentStateSnapshot(label)

fun restoreContentStateSnapshotAny(snapshot: Any?, reason: String = "content"): Int {
    val typed = snapshot as? ContentStateSnapshot ?: return 0
    return restoreContentStateSnapshot(typed, reason)
}

private fun ensureDefaultContentStateBaseline(): ContentStateSnapshot {
    defaultContentStateBaseline?.let { return it }
    return captureContentStateSnapshot("ContentsTweaker原始内容基线").also {
        defaultContentStateBaseline = it
        logger.info("ContentsTweaker已记录原始内容基线 entries=${it.entries.size}")
    }
}

private fun restoreDefaultContentStateBaseline(reason: String) {
    val snapshot = ensureDefaultContentStateBaseline()
    restoreContentStateSnapshot(snapshot, "CP重放前恢复:$reason")
}

private fun getterName(name: String): String =
    "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun memberValue(target: Any?, name: String): Any? {
    if (target == null) return null
    val clazz = target.javaClass
    clazz.fields.firstOrNull { it.name == name }?.let { field ->
        return runCatching { field.get(target) }.getOrNull()
    }
    clazz.methods.firstOrNull { it.parameterCount == 0 && (it.name == name || it.name == getterName(name)) }?.let { method ->
        return runCatching { method.invoke(target) }.getOrNull()
    }
    return null
}

private fun callNoArg(target: Any?, name: String): Any? {
    if (target == null) return null
    return target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let { method -> runCatching { method.invoke(target) }.getOrNull() }
}

private fun gameStateMember(name: String): Any? = memberValue(Vars.state, name)

private fun nativeDataManager(): Any? {
    val data = gameStateMember("data") ?: return null
    val hasPatchApi = data.javaClass.methods.any { it.name == "getPatches" && it.parameterCount == 0 } &&
        data.javaClass.methods.any { it.name == "reloadPatches" && it.parameterCount == 1 }
    return if (hasPatchApi) data else null
}

private data class DynamicContentTargets(
    val contents: Set<Content>,
    val unlockable: Set<UnlockableContent>,
    val blocks: Set<Block>,
    val units: Set<UnitType>,
    val items: Set<Item>,
    val liquids: Set<Liquid>,
    val statuses: Set<StatusEffect>,
    val weather: Set<Weather>,
)

private val liquidModuleLiquidsField by lazy {
    LiquidModule::class.java.getDeclaredField("liquids").apply { trySetAccessible() }
}

private fun liquidModuleCapacity(module: LiquidModule): Int =
    (liquidModuleLiquidsField.get(module) as? FloatArray)?.size
        ?: error("LiquidModule.liquids 不是 float[]")

private fun currentDynamicContentTargets(): DynamicContentTargets {
    val data = nativeDataManager()
    val assets = callNoArg(data, "getContent") as? Iterable<*>
    val contents = (assets ?: emptyList<Any?>())
        .mapNotNull { asset -> memberValue(asset, "content") as? Content }
        .filterNot { it.removed }
        .toSet()
    return DynamicContentTargets(
        contents = contents,
        unlockable = contents.filterIsInstance<UnlockableContent>().toSet(),
        blocks = contents.filterIsInstance<Block>().toSet(),
        units = contents.filterIsInstance<UnitType>().toSet(),
        items = contents.filterIsInstance<Item>().toSet(),
        liquids = contents.filterIsInstance<Liquid>().toSet(),
        statuses = contents.filterIsInstance<StatusEffect>().toSet(),
        weather = contents.filterIsInstance<Weather>().toSet(),
    )
}

private fun objectiveReferencesDynamicContent(objective: MapObjectives.MapObjective, targets: DynamicContentTargets): Boolean {
    var clazz: Class<*>? = objective.javaClass
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredFields.forEach { field ->
            if (Modifier.isStatic(field.modifiers) || !Content::class.java.isAssignableFrom(field.type)) return@forEach
            runCatching { field.isAccessible = true }
            if (runCatching { field.get(objective) as? Content }.getOrNull() in targets.contents) return true
        }
        clazz = clazz.superclass
    }
    return false
}

private fun configReferencesDynamicContent(config: Any?, targets: DynamicContentTargets): Boolean = when (config) {
    null -> false
    is Content -> config in targets.contents
    is ItemStack -> config.item in targets.items
    is Iterable<*> -> config.any { configReferencesDynamicContent(it, targets) }
    is kotlin.Array<*> -> config.any { configReferencesDynamicContent(it, targets) }
    else -> false
}

private fun clearRuleDynamicContentRefs(targets: DynamicContentTargets, report: DynamicContentCleanupReport) {
    val rules = Vars.state.rules

    val spawnBefore = rules.spawns.size
    rules.spawns.removeAll { group ->
        group.type in targets.units ||
            group.effect in targets.statuses ||
            group.items?.item in targets.items ||
            group.payloads?.any { it in targets.units } == true
    }
    report.clearedRuleRefs += spawnBefore - rules.spawns.size

    val loadoutBefore = rules.loadout.size
    rules.loadout.removeAll { it.item in targets.items }
    report.clearedRuleRefs += loadoutBefore - rules.loadout.size

    val weatherBefore = rules.weather.size
    rules.weather.removeAll { it.weather in targets.weather }
    report.clearedRuleRefs += weatherBefore - rules.weather.size

    targets.blocks.forEach { block ->
        if (rules.blockLimits.remove(block, Int.MIN_VALUE) != Int.MIN_VALUE) report.clearedRuleRefs++
        if (rules.bannedBlocks.remove(block)) report.clearedRuleRefs++
        if (rules.revealedBlocks.remove(block)) report.clearedRuleRefs++
    }
    targets.units.forEach { if (rules.bannedUnits.remove(it)) report.clearedRuleRefs++ }
    targets.items.forEach { if (rules.hiddenBuildItems.remove(it)) report.clearedRuleRefs++ }
    targets.unlockable.forEach { if (rules.researched.remove(it)) report.clearedRuleRefs++ }

    if (rules.objectives.any() && rules.objectives.any { objective -> objectiveReferencesDynamicContent(objective, targets) }) {
        report.clearedRuleRefs += rules.objectives.all.size
        rules.objectives.clear()
    }
}

private fun clearTeamDynamicContentRefs(targets: DynamicContentTargets, report: DynamicContentCleanupReport) {
    val teams = (Vars.state.teams.active.toList() + Vars.state.teams.present.toList()).distinct()
    teams.forEach { data ->
        var index = data.plans.size - 1
        while (index >= 0) {
            if (data.plans.get(index).block in targets.blocks) {
                data.plans.removeIndex(index)
                report.clearedBuildPlans++
            }
            index--
        }
        targets.blocks.forEach { data.buildingTypes.remove(it) }
    }
    Vars.state.teams.bosses.removeAll { it.type in targets.units }
}

private fun verifyDynamicContentCleared(targets: DynamicContentTargets, report: DynamicContentCleanupReport) {
    if (Groups.player.any { it.unit()?.type in targets.units }) report.addFailure("仍有玩家附身旧DP单位")
    if (Groups.unit.any { it.type in targets.units }) report.addFailure("仍有旧DP单位")
    if (Groups.build.any { it.block in targets.blocks }) report.addFailure("仍有旧DP建筑")
    if (Groups.puddle.any { it.liquid in targets.liquids }) report.addFailure("仍有旧DP液体洼地")
    if (Groups.weather.any { it.weather in targets.weather }) report.addFailure("仍有旧DP天气")
    if (Groups.bullet.size() > 0) report.addFailure("仍有未清理子弹")

    var invalidTileReported = false
    Vars.world.tiles?.forEach { tile ->
        if (!invalidTileReported &&
            (tile.block() in targets.blocks || tile.floor() in targets.blocks || tile.overlay() in targets.blocks)
        ) {
            report.addFailure("地图仍引用旧DP方块@${tile.x},${tile.y}")
            invalidTileReported = true
        }
    }
}

/**
 * 在 DataManager.load/reloadPatches 之前调用。清理失败会阻止内容注销；即使已经局部清场，
 * 旧 Content 仍保持注册状态，不会进入“场上实体引用失效内容”的崩服/全员掉线状态。
 */
fun prepareForDataAssetReload(reason: String): DynamicContentCleanupReport {
    val targets = currentDynamicContentTargets()
    val report = DynamicContentCleanupReport(targets.contents.size)
    if (targets.contents.isEmpty()) return report

    val players = Groups.player.toList()
    players.forEach { player ->
        val unit = player.unit()
        if (unit != null && unit.type in targets.units) {
            runCatching { player.clearUnit() }
                .onSuccess { report.detachedPlayers++ }
                .onFailure { report.addFailure("解除玩家${player.plainName()}旧DP单位失败: ${it.message}") }
        }
        if (player.selectedBlock() in targets.blocks) {
            runCatching { player.selectedBlock(null) }
                .onFailure { report.addFailure("清理玩家${player.plainName()}选中方块失败: ${it.message}") }
        }
    }

    Groups.unit.toList().forEach { unit ->
        if (unit.type in targets.units) {
            runCatching { unit.remove() }
                .onSuccess { report.removedUnits++ }
                .onFailure { report.addFailure("移除旧DP单位${unit.type.name}#${unit.id}失败: ${it.message}") }
            return@forEach
        }

        if (unit.stack().item in targets.items) {
            runCatching { unit.clearItem() }
                .onSuccess { report.clearedUnitItems++ }
                .onFailure { report.addFailure("清理单位物品#${unit.id}失败: ${it.message}") }
        }
        if (targets.statuses.any { unit.hasEffect(it) }) {
            runCatching {
                targets.statuses.forEach { status -> if (unit.hasEffect(status)) unit.unapply(status) }
            }.onSuccess { report.clearedUnitStatuses++ }
                .onFailure { report.addFailure("清理单位状态#${unit.id}失败: ${it.message}") }
        }
        (unit as? Payloadc)?.payloads()?.let { payloads ->
            if (!payloads.isEmpty) {
                runCatching {
                    for (i in payloads.size - 1 downTo 0) {
                        payloads.get(i).remove()
                        payloads.remove(i)
                    }
                }.onSuccess { report.clearedUnitPayloads++ }
                    .onFailure { report.addFailure("清理单位载荷#${unit.id}失败: ${it.message}") }
            }
        }
        if (targets.blocks.isNotEmpty() && unit.plans().any { it.block in targets.blocks }) {
            runCatching { unit.clearBuilding() }
                .onSuccess { report.clearedBuildPlans++ }
                .onFailure { report.addFailure("清理单位建造计划#${unit.id}失败: ${it.message}") }
        }
    }

    Groups.bullet.toList().forEach { bullet ->
        runCatching { bullet.remove() }
            .onSuccess { report.removedBullets++ }
            .onFailure { report.addFailure("清理子弹#${bullet.id}失败: ${it.message}") }
    }

    Groups.build.toList().forEach { build ->
        if (build.block in targets.blocks) {
            // 成功加载后会统一完整重同步；清场阶段只改服务端世界，避免大量 setNet 包放大上行压力。
            runCatching { build.tile.setBlock(Blocks.air) }
                .onSuccess { report.removedBuildings++ }
                .onFailure { report.addFailure("移除旧DP建筑${build.block.name}@${build.tileX()},${build.tileY()}失败: ${it.message}") }
            return@forEach
        }

        build.items?.let { items ->
            var changed = false
            targets.items.forEach itemLoop@{ item ->
                // 旧存档/地图逻辑可能刚把按旧物品数反序列化的模块重新加入世界。
                // 超出数组长度的动态物品不可能实际存储在该模块中，直接按 0 处理，避免清理器自身越界。
                if (item.id.toInt() !in 0 until items.length()) return@itemLoop
                if (items.get(item) > 0) {
                    items.set(item, 0)
                    changed = true
                }
            }
            if (changed) report.clearedBuildItems++
        }
        build.liquids?.let { liquids ->
            val currentIsDynamic = liquids.current() in targets.liquids
            val capacity = runCatching { liquidModuleCapacity(liquids) }.getOrElse { error ->
                report.addFailure("读取建筑液体模块容量@${build.tileX()},${build.tileY()}失败: ${error.message}")
                0
            }
            val changed = currentIsDynamic || targets.liquids.any { liquid ->
                liquid.id.toInt() in 0 until capacity && liquids.get(liquid) > 0.0001f
            }
            if (changed) {
                liquids.clear()
                liquids.stopFlow()
                if (currentIsDynamic && Vars.content.liquids().size > 0) {
                    // clear() 不会重置 current；在旧动态 Liquid 注销前切回稳定的原版对象。
                    liquids.add(Vars.content.liquid(0), 0f)
                }
                report.clearedBuildLiquids++
            }
        }

        // v159 中并非所有 Building 实现都会返回 PayloadSeq；
        // 普通建筑可能返回 null，不能把它当作卸载失败。
        val payloads = build.getPayloads()
        val payloadCount = payloads?.total() ?: 0
        if (payloads != null && payloadCount > 0) {
            runCatching { payloads.clear() }
                .onSuccess { report.clearedBuildPayloads += payloadCount }
                .onFailure { report.addFailure("清理建筑载荷计数@${build.tileX()},${build.tileY()}失败: ${it.message}") }
        }
        var payloadGuard = 0
        while (build.getPayload() != null && payloadGuard++ < 64) {
            val payload = runCatching { build.takePayload() }.getOrElse {
                report.addFailure("取出建筑载荷@${build.tileX()},${build.tileY()}失败: ${it.message}")
                null
            } ?: break
            runCatching { payload.remove() }
                .onSuccess { report.clearedBuildPayloads++ }
                .onFailure { report.addFailure("移除建筑载荷@${build.tileX()},${build.tileY()}失败: ${it.message}") }
        }
        if (build.getPayload() != null) report.addFailure("建筑载荷清理未完成@${build.tileX()},${build.tileY()}")

        val config = runCatching { build.config() }.getOrNull()
        if (configReferencesDynamicContent(config, targets)) {
            runCatching { build.configureAny(null) }
                .onSuccess { report.clearedBuildConfigs++ }
                .onFailure { report.addFailure("清理建筑配置@${build.tileX()},${build.tileY()}失败: ${it.message}") }
        }
    }

    Groups.puddle.toList().filter { it.liquid in targets.liquids }.forEach { puddle ->
        runCatching { puddle.remove() }
            .onSuccess { report.removedPuddles++ }
            .onFailure { report.addFailure("移除旧DP液体洼地失败: ${it.message}") }
    }
    Groups.weather.toList().filter { it.weather in targets.weather }.forEach { weather ->
        runCatching { weather.remove() }
            .onSuccess { report.removedWeather++ }
            .onFailure { report.addFailure("移除旧DP天气失败: ${it.message}") }
    }

    Vars.world.tiles?.forEach { tile ->
        if (tile.build == null && tile.block() in targets.blocks && tile.block() !== Blocks.air) {
            runCatching { tile.setBlock(Blocks.air) }
                .onSuccess { report.removedEnvironmentBlocks++ }
                .onFailure { report.addFailure("移除旧DP环境方块@${tile.x},${tile.y}失败: ${it.message}") }
        }
        if (tile.floor() in targets.blocks) {
            runCatching { tile.setFloor(Blocks.stone as Floor) }
                .onSuccess { report.replacedFloors++ }
                .onFailure { report.addFailure("替换旧DP地板@${tile.x},${tile.y}失败: ${it.message}") }
        }
        if (tile.overlay() in targets.blocks) {
            runCatching { tile.clearOverlay() }
                .onSuccess { report.clearedOverlays++ }
                .onFailure { report.addFailure("清理旧DP覆盖层@${tile.x},${tile.y}失败: ${it.message}") }
        }
    }

    clearRuleDynamicContentRefs(targets, report)
    clearTeamDynamicContentRefs(targets, report)
    verifyDynamicContentCleared(targets, report)

    logger.info("DataAsset动态内容运行态清理 ($reason): ${report.summary()}")
    if (report.failureCount() > 0) {
        report.failures.take(8).forEach { logger.warning("DataAsset动态内容清理失败: $it") }
        if (report.suppressedFailures > 0) logger.warning("DataAsset动态内容清理另有 ${report.suppressedFailures} 条重复/超限失败未展开")
        throw IllegalStateException(
            "旧DP运行态引用清理未完成，已阻止内容注销：${report.failures.take(4).joinToString("；")}"
        )
    }
    return report
}

private fun legacyPatcher(): Any? = gameStateMember("patcher")

private fun patchSeqObject(): Iterable<*>? {
    nativeDataManager()?.let { data ->
        (callNoArg(data, "getPatches") as? Iterable<*>)?.let { return it }
    }
    legacyPatcher()?.let { patcher ->
        (memberValue(patcher, "patches") as? Iterable<*>)?.let { return it }
    }
    return null
}

fun loadedPatchInfos(): List<LoadedPatchInfo> {
    val patches = patchSeqObject() ?: return emptyList()
    return patches.mapNotNull { patch ->
        if (patch == null) return@mapNotNull null
        val raw = memberValue(patch, "patch") as? String ?: patch.toString()
        LoadedPatchInfo(
            patch = raw,
            name = (memberValue(patch, "name") as? String)?.takeIf { it.isNotBlank() },
            error = (memberValue(patch, "error") as? Boolean)
                ?: (memberValue(patch, "errored") as? Boolean)
                ?: false,
            warnings = (memberValue(patch, "warnings") as? Iterable<*>)
                ?.map { it.toString() }
                .orEmpty(),
        )
    }
}

/**
 * v159 DataManager 的完整 Data Assets 视图。
 *
 * 使用反射读取是为了把 v159 专属 API 集中在兼容层；旧端没有 allAssets 时安全返回空列表，
 * 而 v159 会同时列出 patch/content/bundle/image/sound/music，不再只看 PatchAsset。
 */
fun loadedDataAssetInfos(): List<LoadedDataAssetInfo> {
    val data = nativeDataManager() ?: return emptyList()
    val assets = callNoArg(data, "getAllAssets") as? Iterable<*> ?: return emptyList()
    return assets.mapIndexedNotNull { index, asset ->
        if (asset == null) return@mapIndexedNotNull null
        val type = callNoArg(asset, "getType")?.toString()?.lowercase()
            ?: asset.javaClass.simpleName.removeSuffix("Asset").lowercase()
        val path = (memberValue(asset, "path") as? String).orEmpty()
        val name = (memberValue(asset, "name") as? String).orEmpty()
        val fullPath = (callNoArg(asset, "getFullPath") as? String).orEmpty().ifBlank { path }
        val warnings = (memberValue(asset, "warnings") as? Iterable<*>)
            ?.map { it.toString() }
            .orEmpty()
        val error = (memberValue(asset, "error") as? Boolean)
            ?: (memberValue(asset, "errored") as? Boolean)
            ?: false
        val preview = ((memberValue(asset, "patch") as? String)
            ?: (memberValue(asset, "data") as? String))
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(2000)
        LoadedDataAssetInfo(
            index = index + 1,
            type = type,
            path = path,
            name = name,
            fullPath = fullPath,
            contentType = if (type == "content") memberValue(asset, "type")?.toString() else null,
            loadedContent = if (type == "content") memberValue(asset, "content")?.toString() else null,
            error = error,
            warnings = warnings,
            cached = callNoArg(asset, "isCached") as? Boolean,
            hash = (memberValue(asset, "stringHash") as? String)?.takeIf { it.isNotBlank() },
            preview = preview?.takeIf { it.isNotBlank() },
        )
    }
}

fun loadedDataAssetCounts(): Map<String, Int> =
    loadedDataAssetInfos().groupingBy { it.type }.eachCount().toSortedMap()

fun currentPatchStrings(): List<String> = loadedPatchInfos().map { it.patch }

fun currentPatchCount(): Int = loadedPatchInfos().size

fun patchInfoFor(rawPatch: String): LoadedPatchInfo? =
    loadedPatchInfos().firstOrNull { it.patch == rawPatch }

private fun seqOfStrings(patches: Iterable<String>): Seq<String> {
    val seq = Seq<String>()
    patches.forEach { seq.add(it) }
    return seq
}

private fun tryFireLegacyPatchEvent(seq: Seq<String>) {
    val eventClass = EventType::class.java.declaredClasses
        .firstOrNull { it.simpleName == "ContentPatchLoadEvent" }
        ?: return
    val ctor = eventClass.constructors.firstOrNull { ctor ->
        ctor.parameterTypes.size == 1 && Seq::class.java.isAssignableFrom(ctor.parameterTypes[0])
    } ?: return
    runCatching {
        val event = ctor.newInstance(seq)
        Events.fire(event)
    }.onFailure {
        logger.warning("ContentPatchLoadEvent 兼容触发失败: ${it.message}")
    }
}

private fun applyLegacyPatches(patches: List<String>): Boolean {
    val patcher = legacyPatcher() ?: return false
    val apply = patcher.javaClass.methods.firstOrNull { method ->
        method.name == "apply" && method.parameterTypes.size == 1 && Seq::class.java.isAssignableFrom(method.parameterTypes[0])
    } ?: return false
    val seq = seqOfStrings(patches)
    tryFireLegacyPatchEvent(seq)
    apply.invoke(patcher, seq)
    return true
}

private fun applyNativeDataPatches(patches: List<String>): Boolean {
    val data = nativeDataManager() ?: return false
    val patchAssetClass = runCatching { Class.forName("mindustry.mod.data.PatchAsset") }.getOrNull()
        ?: return false
    val ctor = patchAssetClass.constructors.firstOrNull { ctor ->
        ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == String::class.java
    } ?: return false
    val reload = data.javaClass.methods.firstOrNull { method ->
        method.name == "reloadPatches" && method.parameterTypes.size == 1 && Seq::class.java.isAssignableFrom(method.parameterTypes[0])
    } ?: return false
    val seq = Seq<Any>()
    patches.forEach { seq.add(ctor.newInstance(it)) }
    reload.invoke(data, seq)
    return true
}

fun applyPatchStrings(patches: Iterable<String>) {
    val list = patches.toList()
    prepareForDataAssetReload("属性Patch重载 patches=${list.size}")
    restoreDefaultContentStateBaseline("patches=${list.size}")
    val applied = runCatching { applyNativeDataPatches(list) }.getOrElse {
        logger.warning("DataAsset CP应用失败，尝试旧版 patcher: ${it.message}")
        false
    } || runCatching { applyLegacyPatches(list) }.getOrElse {
        logger.warning("旧版 ContentPatcher CP应用失败: ${it.message}")
        false
    }
    if (!applied) error("当前 Mindustry 版本未提供可用的 CP/DataAsset patcher")
}

@JvmName("addPatchV3")
fun addPatch(name: String, patch: String) {
    //logger.info("Adding patch $name")
    if (!name.startsWith("$")) {
        state.map.tags.put("CT@$name", patch)
        patchList = patchList.toMutableList().apply {
            remove(name); add(name)//put last
        }
    }

    val raw = patch
        .replace("+=", "+")
        .replace("#", "arg")
        .replace(Regex("""(:)([\u4e00-\u9fa5][^,\}\]]*)""")) { m ->
            val sep = m.groupValues[1]
            val text = m.groupValues[2].trim()
            "$sep\"$text\""
        }
        .replace(Regex("(?<=\\{|,|\\s)([a-zA-Z0-9_-]+):"), "\"$1\":")
        .replace(Regex(":\\s*([a-zA-Z_-]+)(?=\\s*[},])")) { m ->
            ":\"${m.groupValues[1]}\""
        }

    val readPatch = Jval.read(raw).toString(Jval.Jformat.plain)
    contentPatches.add(readPatch)
    applyPatchStrings(currentPatchStrings().filterNot { it == readPatch } + readPatch)
}
@JvmName("addPatch")
fun addPatchOld(name: String, patch: String): String {
    addPatch(name, patch)
    return name
}
export(::addPatch)
onEnable {
    ensureDefaultContentStateBaseline()
}

listen<EventType.ResetEvent> {
    //logger.info("reset")
    contentPatches.clear()
    runCatching { applyPatchStrings(emptyList()) }
        .onFailure { logger.warning("重置时清理 CP 失败: ${it.message}") }
}

listen<EventType.WorldLoadBeginEvent> {
    state.map.tags.get("ContentsPatch")?.split(";")?.forEach { name ->
        if (name.isBlank()) return@forEach
        val patch = state.map.tags.get("CT@$name") ?: return@forEach
        addPatch(name, patch)
    }
}
