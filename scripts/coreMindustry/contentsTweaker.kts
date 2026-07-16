@file:Import("https://www.jitpack.io/", mavenRepository = true)
@file:Import("com.github.way-zer:ContentsTweaker:v3.1.2", mavenDependsSingle = true)

package coreMindustry

import arc.Events
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.serialization.Jval
import mindustry.Vars
import mindustry.type.ItemStack
import mindustry.type.LiquidStack
import mindustry.game.EventType
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
