@file:Depends("coreMindustry/menu", "外部CP菜单")
@file:Depends("coreMindustry/contentsTweaker", "CP热重载")
@file:Depends("wayzer/vote", "投票系统")

package wayzer.map

import arc.struct.Seq
import arc.util.serialization.Jval
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreLibrary.lib.PermissionApi
import coreMindustry.lib.broadcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.EventType
import mindustry.gen.Building
import mindustry.world.blocks.defense.turrets.ContinuousTurret
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.blocks.power.BeamNode
import mindustry.world.blocks.power.PowerGraph
import mindustry.world.modules.ItemModule
import mindustry.world.modules.LiquidModule
import mindustry.world.modules.PowerModule
import wayzer.VoteService
import java.io.File
import java.time.Instant
import kotlin.math.ceil

name = "外部CP热重载"

private val contentsTweaker = contextScript<coreMindustry.ContentsTweaker>()
private val externalCpDirName by config.key("external-cp", "外部CP目录名，位于 config/scripts/ 下")
private val slowExternalCpBytes by config.key(2_000_000L, "外部CP慢同步阈值，超过后不拒绝但使用更长同步间隔")
private val hardExternalCpBytes by config.key(64_000_000L, "外部CP硬读取上限，0为不限制")
private val worldSyncDelayMillis by config.key(350L, "外部CP变更后分批同步玩家间隔(ms)")
private val largeWorldSyncDelayMillis by config.key(1500L, "大文件外部CP变更后分批同步玩家间隔(ms)")
private val supportedExternalCpExtensions = setOf("json", "hjson")

private data class ExternalCpFile(
    val index: Int,
    val file: File,
    val name: String,
    val displayName: String,
    val bytes: Long,
    val modifiedAt: Long,
)

private data class LoadedExternalCp(
    val fileName: String,
    val displayName: String,
    val patch: String,
    val loadedAt: Instant,
    val operator: String,
    val warnings: List<String>,
)

private data class ExternalCpResult(
    val success: Boolean,
    val message: String,
)

private data class ExternalCpReadResult(
    val patch: String,
    val parseMode: String,
    val slowSync: Boolean,
    val syncDelayMillis: Long,
    val notice: String = "",
)

private val loadedExternalCps = linkedMapOf<String, LoadedExternalCp>()
private var serverContentBaseline: Any? = null
private var externalContentBaseline: Any? = null

private fun externalCpDir(): File = File(Vars.dataDirectory.file(), "scripts/$externalCpDirName")

private fun ensureExternalCpDir(): File = externalCpDir().also { dir ->
    if (!dir.exists() && !dir.mkdirs()) {
        logger.warning("外部CP目录创建失败：${dir.absolutePath}")
    }
}

private fun fileKey(fileName: String): String = fileName.lowercase()

private fun isSupportedExternalCpFile(file: File): Boolean =
    file.extension.lowercase() in supportedExternalCpExtensions

private fun isSafeExternalCpFile(file: File): Boolean = runCatching {
    val root = ensureExternalCpDir().canonicalFile.toPath()
    val target = file.canonicalFile.toPath()
    target.startsWith(root) && file.isFile && isSupportedExternalCpFile(file)
}.getOrDefault(false)

private fun listExternalCpFiles(): List<ExternalCpFile> {
    val dir = ensureExternalCpDir()
    val files = dir.listFiles { file -> file.isFile && isSupportedExternalCpFile(file) }
        ?.filter(::isSafeExternalCpFile)
        ?.sortedWith(compareBy<File> { it.nameWithoutExtension.lowercase() }.thenBy { it.name.lowercase() })
        .orEmpty()
    return files.mapIndexed { index, file ->
        ExternalCpFile(
            index = index + 1,
            file = file,
            name = file.name,
            displayName = file.nameWithoutExtension,
            bytes = file.length(),
            modifiedAt = file.lastModified(),
        )
    }
}

private fun resolveExternalCpFile(input: String?): ExternalCpFile? {
    val text = input?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val files = listExternalCpFiles()
    text.toIntOrNull()?.let { index -> files.getOrNull(index - 1)?.let { return it } }
    val candidates = buildList {
        add(text)
        if (File(text).extension.isBlank()) {
            supportedExternalCpExtensions.forEach { add("$text.$it") }
        }
    }
    return files.firstOrNull { cp ->
        candidates.any { cp.name.equals(it, ignoreCase = true) } || cp.displayName.equals(text, ignoreCase = true)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2fMB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private fun cpPreview(patch: String, limit: Int = 120): String =
    patch.replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)

private fun safeMessage(text: String, limit: Int = 900): String =
    text.replace("{", "［")
        .replace("}", "］")
        .replace("\r\n", "\n")
        .take(limit)

private fun briefError(t: Throwable): String =
    safeMessage("${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}")

private fun compatibilityJsonPatch(rawPatch: String): String =
    rawPatch
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

private fun normalizeCpPatch(rawPatch: String): Pair<String, String> {
    val raw = rawPatch.trim().removePrefix("\uFEFF")
    val errors = mutableListOf<String>()

    runCatching {
        return Jval.read(raw).toString(Jval.Jformat.plain) to "Jval原生JSON/HJSON"
    }.onFailure { errors += "原生JSON/HJSON解析失败：${briefError(it)}" }

    runCatching {
        return Jval.read(compatibilityJsonPatch(raw)).toString(Jval.Jformat.plain) to "旧CP兼容预处理"
    }.onFailure { errors += "兼容预处理解析失败：${briefError(it)}" }

    throw IllegalArgumentException("CP格式解析失败：${errors.joinToString("；")}")
}

private fun slowSyncNotice(cp: ExternalCpFile): String {
    val threshold = slowExternalCpBytes.coerceAtLeast(1L)
    return if (cp.bytes > threshold) {
        "[yellow]文件较大（${formatBytes(cp.bytes)}），将使用更长间隔缓慢同步，请耐心等待。"
    } else ""
}

private fun cpSizeStatus(cp: ExternalCpFile): String =
    formatBytes(cp.bytes) + if (cp.bytes > slowExternalCpBytes.coerceAtLeast(1L)) " / 慢同步" else ""

private fun stripSupportedExtension(name: String): String {
    var out = name
    supportedExternalCpExtensions.forEach { ext ->
        if (out.endsWith(".$ext", ignoreCase = true)) out = out.dropLast(ext.length + 1)
    }
    return out
}

private fun readExternalCpPatch(cp: ExternalCpFile): ExternalCpReadResult {
    if (!isSafeExternalCpFile(cp.file)) {
        throw IllegalArgumentException("文件路径不安全或不是受支持的CP文件：${cp.name}；支持 ${supportedExternalCpExtensions.joinToString("/")}")
    }
    val hardLimit = hardExternalCpBytes
    if (hardLimit > 0 && cp.bytes > hardLimit) {
        throw IllegalArgumentException("文件过大：${formatBytes(cp.bytes)}，超过硬上限 ${formatBytes(hardLimit)}；如确需加载请调高 hardExternalCpBytes")
    }
    val raw = cp.file.readText(Charsets.UTF_8)
    val (patch, mode) = try {
        normalizeCpPatch(raw)
    } catch (t: Throwable) {
        throw IllegalArgumentException("解析 ${cp.name} 失败：${t.message ?: t.javaClass.simpleName}", t)
    }
    val slow = cp.bytes > slowExternalCpBytes.coerceAtLeast(1L)
    return ExternalCpReadResult(
        patch = patch,
        parseMode = mode,
        slowSync = slow,
        syncDelayMillis = if (slow) largeWorldSyncDelayMillis.coerceAtLeast(worldSyncDelayMillis) else worldSyncDelayMillis,
        notice = slowSyncNotice(cp),
    )
}
private fun currentAppliedPatchStrings(): List<String> = with(contentsTweaker) { currentPatchStrings() }

private fun ensureServerContentBaseline(): Any {
    serverContentBaseline?.let { return it }
    return with(contentsTweaker) { captureContentStateSnapshotAny("外部CP脚本启用基线") }
        .also { snapshot ->
            serverContentBaseline = snapshot
            logger.info("外部CP已记录服务器内容基线")
        }
}

private fun ensureExternalContentBaseline(reason: String): Any {
    externalContentBaseline?.let { return it }
    return with(contentsTweaker) { captureContentStateSnapshotAny("外部CP加载前基线:$reason") }
        .also { snapshot ->
            externalContentBaseline = snapshot
            logger.info("外部CP已记录加载前内容基线($reason)")
        }
}

private fun turretHasNoValidAmmoType(turret: Turret): Boolean = when (turret) {
    is ItemTurret -> turret.ammoTypes.entries().none { it.value != null }
    is LiquidTurret -> turret.ammoTypes.entries().none { it.value != null }
    is PowerTurret -> turret.shootType == null
    is ContinuousTurret -> turret.shootType == null
    else -> false
}

private fun sanitizeInvalidTurretAmmo(build: Turret.TurretBuild): Boolean {
    val turret = build.block as? Turret
    var changed = false
    var total = 0

    if (!build.ammo.isEmpty) {
        var i = build.ammo.size - 1
        while (i >= 0) {
            val entry = build.ammo.get(i)
            val bullet = runCatching { entry.type() }.getOrNull()
            if (entry.amount <= 0 || bullet == null) {
                build.ammo.remove(i)
                changed = true
            } else {
                total += entry.amount.coerceAtLeast(0)
            }
            i--
        }
    }

    if (changed) build.totalAmmo = total.coerceAtMost(turret?.maxAmmo ?: total)

    val unsafeNullAmmo = runCatching { build.hasAmmo() && build.peekAmmo() == null }.getOrDefault(false)
    val noValidAmmoType = turret?.let(::turretHasNoValidAmmoType) ?: false
    if (unsafeNullAmmo || noValidAmmoType) {
        runCatching { build.tile.setNet(Blocks.air) }
            .onFailure { logger.warning("外部CP炮塔弹药保护：移除异常炮塔失败 ${build.block.name}: ${it.message}") }
        return true
    }

    return changed
}

private fun sanitizeInvalidTurretAmmo(reason: String = ""): Int {
    var fixed = 0
    Groups.build.toList().forEach { build ->
        val turret = build as? Turret.TurretBuild ?: return@forEach
        if (sanitizeInvalidTurretAmmo(turret)) fixed++
    }
    if (fixed > 0) {
        logger.warning("外部CP炮塔弹药保护：清理 $fixed 个无效弹药炮塔${if (reason.isBlank()) "" else " ($reason)"}")
    }
    return fixed
}

private data class CpWorldRepairStats(
    var powerModules: Int = 0,
    var detachedPowerModules: Int = 0,
    var itemModules: Int = 0,
    var liquidModules: Int = 0,
    var buildingHealthStats: Int = 0,
    var unitStats: Int = 0,
    var fixedPowerFlags: Int = 0,
    var invalidPowerLinks: Int = 0,
    var invalidPowerConsumers: Int = 0,
    var beamNodesReset: Int = 0,
    var stalePowerGraphsCleared: Int = 0,
    var powerGraphsRebuilt: Int = 0,
) {
    fun total(): Int = powerModules + detachedPowerModules + itemModules + liquidModules + buildingHealthStats + unitStats +
        fixedPowerFlags + invalidPowerLinks + invalidPowerConsumers + beamNodesReset + stalePowerGraphsCleared + powerGraphsRebuilt
}

private data class ExistingBuildStatSnapshot(
    val blockHealth: Float,
    val maxHealth: Float,
)

private data class ExistingUnitStatSnapshot(
    val typeHealth: Float,
    val maxHealth: Float,
    val typeArmor: Float,
    val armor: Float,
    val typeHitSize: Float,
    val hitSize: Float,
    val typeDrag: Float,
    val drag: Float,
)

private fun captureExistingBuildStats(): Map<Int, ExistingBuildStatSnapshot> =
    Groups.build.toList().associate { build ->
        build.pos() to ExistingBuildStatSnapshot(
            blockHealth = build.block.health.toFloat(),
            maxHealth = build.maxHealth,
        )
    }

private fun captureExistingUnitStats(): Map<Int, ExistingUnitStatSnapshot> =
    Groups.unit.toList().associate { unit ->
        unit.id to ExistingUnitStatSnapshot(
            typeHealth = unit.type.health,
            maxHealth = unit.maxHealth,
            typeArmor = unit.type.armor,
            armor = unit.armor,
            typeHitSize = unit.type.hitSize,
            hitSize = unit.hitSize,
            typeDrag = unit.type.drag,
            drag = unit.drag,
        )
    }

private fun nearlyEqual(a: Float, b: Float, epsilon: Float = 0.01f): Boolean =
    kotlin.math.abs(a - b) <= epsilon

private fun scaledHealthAfterMaxChange(currentHealth: Float, oldMax: Float, newMax: Float): Float {
    if (oldMax <= 0f || currentHealth.isNaN() || currentHealth.isInfinite()) return newMax
    val ratio = currentHealth / oldMax
    if (ratio.isNaN() || ratio.isInfinite()) return newMax
    return (newMax * ratio).coerceAtLeast(0f)
}

private fun validPowerBuild(build: Building?): Boolean =
    build != null && build.isValid && build.tile != null && build.block.hasPower && build.power != null

private fun removeInvalidPowerGraphEntries(graph: PowerGraph, stats: CpWorldRepairStats) {
    fun removeInvalid(seq: Seq<Building>, consumerList: Boolean = false, invalid: (Building?) -> Boolean) {
        var i = seq.size - 1
        while (i >= 0) {
            val build = seq.get(i)
            if (invalid(build)) {
                seq.remove(i)
                if (consumerList) stats.invalidPowerConsumers++
            }
            i--
        }
    }

    removeInvalid(graph.all) { build -> !validPowerBuild(build) || build?.power?.graph != graph }
    removeInvalid(graph.producers) { build ->
        !validPowerBuild(build) || build?.power?.graph != graph || !build.block.outputsPower
    }
    removeInvalid(graph.consumers, consumerList = true) { build ->
        !validPowerBuild(build) || build?.power?.graph != graph || !build.block.consumesPower || build.block.consPower == null
    }
    removeInvalid(graph.batteries) { build ->
        !validPowerBuild(build) || build?.power?.graph != graph || !build.block.outputsPower || !build.block.consumesPower || build.block.consPower == null
    }
}

private fun repairExistingBuildingModulesAfterCp(
    reason: String = "",
    buildStatsBeforePatch: Map<Int, ExistingBuildStatSnapshot> = emptyMap(),
    unitStatsBeforePatch: Map<Int, ExistingUnitStatSnapshot> = emptyMap(),
): CpWorldRepairStats {
    val stats = CpWorldRepairStats()
    val builds = Groups.build.toList()
    val oldGraphs = linkedSetOf<PowerGraph>()
    builds.forEach { build -> build.power?.graph?.let { oldGraphs += it } }
    Groups.powerGraph.toList().forEach { updater -> updater.graph()?.let { oldGraphs += it } }
    val fixedPowerFlagBlocks = mutableSetOf<String>()

    // CP加载/卸载/回滚可能改动 Block.consPower，却没有同步 consumesPower 标记；原版 PowerGraph.distributePower
    // 会直接读取 consPower.buffered。先修正该不一致，否则旧图或重建图下一tick都可能 NPE 崩服。
    Vars.content.blocks().forEach { block ->
        val shouldConsumePower = block.consPower != null
        if (block.consumesPower != shouldConsumePower) {
            block.consumesPower = shouldConsumePower
            if (fixedPowerFlagBlocks.add(block.name)) stats.fixedPowerFlags++
        }
    }

    // CP热重载会修改Block的hasPower/hasItems/hasLiquids等字段，但已存在建筑的模块是在建筑创建时生成的。
    // 若某个建筑被CP改成有电力模块，而旧Building.power仍为null，BeamNode会在下一tick连接它时NPE崩服。
    builds.forEach { build ->
        if (!build.block.hasPower && build.power != null) {
            build.power.graph?.removeList(build)
            build.power.links?.clear()
            build.power = null
            stats.detachedPowerModules++
        }
        if (build.block.hasPower && build.power == null) {
            build.power = PowerModule()
            stats.powerModules++
        }
        if (build.block.hasItems && build.items == null) {
            build.items = ItemModule()
            stats.itemModules++
        }
        if (build.block.hasLiquids && build.liquids == null) {
            build.liquids = LiquidModule()
            stats.liquidModules++
        }

        // 已存在建筑的maxHealth同样是在创建时从block.health拷贝的；CP改炮台/建筑血量后，不拆重建不会同步。
        // 为避免破坏地图脚本/技能刻意改过的建筑血量，只同步“看起来仍是原版/上一个CP默认血量”的建筑，
        // 即：热重载前 build.maxHealth ~= 热重载前 block.health。血量按百分比缩放，保留受损程度。
        val before = buildStatsBeforePatch[build.pos()]
        val newMaxHealth = build.block.health.toFloat()
        if (before != null &&
            !nearlyEqual(before.blockHealth, newMaxHealth) &&
            nearlyEqual(before.maxHealth, before.blockHealth) &&
            !nearlyEqual(build.maxHealth, newMaxHealth)
        ) {
            build.health = scaledHealthAfterMaxChange(build.health, build.maxHealth, newMaxHealth)
            build.maxHealth = newMaxHealth
            stats.buildingHealthStats++
        }
    }

    Groups.unit.toList().forEach { unit ->
        val before = unitStatsBeforePatch[unit.id] ?: return@forEach
        val type = unit.type
        val typeChanged = !nearlyEqual(before.typeHealth, type.health) ||
            !nearlyEqual(before.typeArmor, type.armor) ||
            !nearlyEqual(before.typeHitSize, type.hitSize) ||
            !nearlyEqual(before.typeDrag, type.drag)
        val unitLooksDefault = nearlyEqual(before.maxHealth, before.typeHealth) &&
            nearlyEqual(before.armor, before.typeArmor) &&
            nearlyEqual(before.hitSize, before.typeHitSize) &&
            nearlyEqual(before.drag, before.typeDrag)

        if (typeChanged && unitLooksDefault) {
            val oldMaxHealth = unit.maxHealth
            if (!nearlyEqual(oldMaxHealth, type.health)) {
                unit.health = scaledHealthAfterMaxChange(unit.health, oldMaxHealth, type.health)
                unit.maxHealth = type.health
            }
            unit.armor = type.armor
            unit.hitSize = type.hitSize
            unit.drag = type.drag
            stats.unitStats++
        }
    }

    // BeamNode内部还缓存了四个方向的Building引用。CP切换后这些引用可能指向“现在不再有power模块”的旧建筑，
    // 因此先断开梁节点缓存，随后用updateDirections重新搜索，避免下一tick进入BeamNode.updateDirections时崩溃。
    builds.forEach { build ->
        val beam = build as? BeamNode.BeamNodeBuild ?: return@forEach
        val power = beam.power
        if (power != null) {
            var i = power.links.size - 1
            while (i >= 0) {
                val other = Vars.world.build(power.links.get(i))
                other?.power?.links?.removeValue(beam.pos())
                i--
            }
            if (power.links.size > 0) {
                stats.invalidPowerLinks += power.links.size
                power.links.clear()
            }
        }
        for (i in beam.links.indices) beam.links[i] = null
        for (i in beam.dests.indices) beam.dests[i] = null
        beam.lastChange = -2
        stats.beamNodesReset++
    }

    // 清理普通电力链接中指向无效/无power模块/跨队的目标，避免回滚或再次热重载后留下坏链。
    builds.forEach { build ->
        val power = build.power ?: return@forEach
        var i = power.links.size - 1
        while (i >= 0) {
            val other = Vars.world.build(power.links.get(i))
            val invalid = other == null || other.power == null || !other.block.hasPower || other.team != build.team
            if (invalid) {
                power.links.removeIndex(i)
                other?.power?.links?.removeValue(build.pos())
                stats.invalidPowerLinks++
            }
            i--
        }
    }

    // 重新分配电网。只在CP加载/卸载时执行，避免已有PowerGraph里残留旧Block分类。
    // 旧 PowerGraphUpdater 即使不再被建筑引用，也会继续被 Groups.powerGraph 更新；因此先整体清掉旧图实体，
    // 再让所有仍有电力模块的建筑从干净的新图开始 reflow。
    builds.forEach { build ->
        val power = build.power ?: return@forEach
        if (build.block.hasPower) {
            power.init = false
            power.graph = null
        }
    }
    oldGraphs.forEach { graph ->
        runCatching {
            graph.clear()
            stats.stalePowerGraphsCleared++
        }
    }
    builds.forEach { build ->
        val power = build.power ?: return@forEach
        if (build.block.hasPower && !power.init) {
            PowerGraph().reflow(build)
            stats.powerGraphsRebuilt++
        }
    }
    Groups.powerGraph.toList().forEach { updater ->
        updater.graph()?.let { removeInvalidPowerGraphEntries(it, stats) }
    }

    val beamFailures = mutableListOf<String>()
    builds.forEach { build ->
        val beam = build as? BeamNode.BeamNodeBuild ?: return@forEach
        if (beam.power == null) return@forEach
        runCatching { beam.updateDirections() }
            .onFailure { error ->
                beamFailures += "${beam.block.name}@${beam.tileX()},${beam.tileY()}: ${error.message ?: error.javaClass.simpleName}"
            }
    }
    if (beamFailures.isNotEmpty()) {
        throw IllegalStateException("CP后梁节点电力校验失败：${beamFailures.take(3).joinToString("；")}${if (beamFailures.size > 3) " 等${beamFailures.size}处" else ""}")
    }

    if (stats.total() > 0) {
        logger.info(
            "外部CP建筑模块兼容修复${if (reason.isBlank()) "" else " ($reason)"}: " +
                "power=${stats.powerModules}, detachedPower=${stats.detachedPowerModules}, items=${stats.itemModules}, liquids=${stats.liquidModules}, " +
                "buildHealth=${stats.buildingHealthStats}, unitStats=${stats.unitStats}, flags=${stats.fixedPowerFlags}, " +
                "links=${stats.invalidPowerLinks}, consumers=${stats.invalidPowerConsumers}, beams=${stats.beamNodesReset}, " +
                "oldGraphs=${stats.stalePowerGraphsCleared}, graphs=${stats.powerGraphsRebuilt}"
        )
    }
    return stats
}

private fun applyPatchStringsAndSanitize(patches: List<String>, reason: String) {
    val buildStatsBeforePatch = captureExistingBuildStats()
    val unitStatsBeforePatch = captureExistingUnitStats()
    with(contentsTweaker) { applyPatchStrings(patches) }
    repairExistingBuildingModulesAfterCp(reason, buildStatsBeforePatch, unitStatsBeforePatch)
    sanitizeInvalidTurretAmmo(reason)
}

private fun applyPatchStringsFromBaselineAndSanitize(
    patches: List<String>,
    reason: String,
    baseline: Any?,
) {
    val buildStatsBeforePatch = captureExistingBuildStats()
    val unitStatsBeforePatch = captureExistingUnitStats()
    if (baseline != null) {
        with(contentsTweaker) { restoreContentStateSnapshotAny(baseline, "外部CP重放前恢复:$reason") }
    }
    with(contentsTweaker) { applyPatchStrings(patches) }
    repairExistingBuildingModulesAfterCp(reason, buildStatsBeforePatch, unitStatsBeforePatch)
    sanitizeInvalidTurretAmmo(reason)
}

private fun applyPatchStringsFromExternalBaselineAndSanitize(patches: List<String>, reason: String) {
    val baseline = externalContentBaseline ?: ensureExternalContentBaseline(reason)
    applyPatchStringsFromBaselineAndSanitize(patches, reason, baseline)
}

private fun sendWorldDataCompat(player: mindustry.gen.Player) {
    val con = player.con ?: return
    Call.worldDataBegin(con)
    val sendWorldAndAssets = Vars.netServer.javaClass.methods.firstOrNull { method ->
        method.name == "sendWorldAndAssets" && method.parameterTypes.size == 1
    }
    if (sendWorldAndAssets != null) {
        sendWorldAndAssets.invoke(Vars.netServer, player)
    } else {
        Vars.netServer.sendWorldData(player)
    }
}

private fun syncWorldAfterExternalCpChange(reason: String, delayMillis: Long = worldSyncDelayMillis) {
    val players = Groups.player.toList().filter { it.con != null && !it.isLocal }
    if (players.isEmpty()) return
    launch(Dispatchers.game) {
        players.forEachIndexed { index, player ->
            if (index > 0) delay(delayMillis.coerceAtLeast(0L))
            runCatching {
                if (player.con != null) {
                    sendWorldDataCompat(player)
                }
            }.onFailure {
                logger.warning("外部CP同步玩家 ${player.plainName()} 失败($reason): ${it.message}")
            }
        }
    }
}

private fun removeRuntimeExternalPatch(patch: String) {
    with(contentsTweaker) {
        val keep = contentPatches.toList().filterNot { it == patch }
        contentPatches.clear()
        keep.forEach { contentPatches.add(it) }
    }
}

private fun restoreTweakerRuntime(previous: List<String>) {
    with(contentsTweaker) {
        contentPatches.clear()
        previous.forEach { contentPatches.add(it) }
    }
}

private fun applyExternalCp(cp: ExternalCpFile, operator: String): ExternalCpResult {
    val key = fileKey(cp.name)
    val readResult = try {
        readExternalCpPatch(cp)
    } catch (t: Throwable) {
        logger.warning("外部CP读取失败 ${cp.name}: ${t.stackTraceToString()}")
        return ExternalCpResult(false, "[red]读取外部CP失败：[white]${safeMessage(t.message ?: t.javaClass.simpleName)}")
    }
    val readPatch = readResult.patch

    val previousActive = currentAppliedPatchStrings()
    val previousRuntime = with(contentsTweaker) { contentPatches.toList() }
    val previousLoaded = LinkedHashMap(loadedExternalCps)
    val oldPatch = loadedExternalCps[key]?.patch
    val nextActive = previousActive.filterNot { oldPatch != null && it == oldPatch } + readPatch

    return runCatching {
        ensureExternalContentBaseline("加载:${cp.name}")
        if (oldPatch != null) removeRuntimeExternalPatch(oldPatch)
        with(contentsTweaker) { contentPatches.add(readPatch) }
        applyPatchStringsFromExternalBaselineAndSanitize(nextActive, "外部CP加载:${cp.name}")
        val patchSet = with(contentsTweaker) { patchInfoFor(readPatch) }
        if (patchSet == null || patchSet.error) {
            val warnings = patchSet?.warnings?.joinToString("; ").orEmpty().ifBlank { "补丁未进入当前patcher或解析失败" }
            throw IllegalStateException("外部CP解析失败：${cp.name}：$warnings")
        }
        val warnings = patchSet.warnings
        loadedExternalCps[key] = LoadedExternalCp(cp.name, cp.displayName, readPatch, Instant.now(), operator, warnings)
        syncWorldAfterExternalCpChange("加载:${cp.name}", readResult.syncDelayMillis)
        logger.info("外部CP已${if (oldPatch == null) "加载" else "热重载"}: ${cp.name} by $operator parse=${readResult.parseMode} slow=${readResult.slowSync} warnings=${warnings.size}")
        val warnText = if (warnings.isEmpty()) "" else "\n[yellow]该CP加载后有 ${warnings.size} 条警告，可在管理菜单查看。"
        val slowText = readResult.notice.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
        ExternalCpResult(true, "[green]外部CP已${if (oldPatch == null) "加载" else "热重载"}：[gold]${cp.displayName}[]$warnText$slowText\n[gray]解析模式：${readResult.parseMode}；已分批同步世界数据；若客户端显示异常，可手动 /sync 或重进。")
    }.getOrElse { error ->
        runCatching {
            restoreTweakerRuntime(previousRuntime)
            loadedExternalCps.clear()
            loadedExternalCps.putAll(previousLoaded)
            val baseline = externalContentBaseline ?: serverContentBaseline
            applyPatchStringsFromBaselineAndSanitize(previousActive, "外部CP失败回滚:${cp.name}", baseline)
            syncWorldAfterExternalCpChange("加载失败回滚:${cp.name}", readResult.syncDelayMillis)
        }.onFailure { rollback ->
            logger.warning("外部CP回滚失败 ${cp.name}: ${rollback.stackTraceToString()}")
        }
        logger.warning("外部CP加载失败 ${cp.name}: ${error.stackTraceToString()}")
        ExternalCpResult(false, "[red]外部CP加载失败：[white]${safeMessage(error.message ?: error.javaClass.simpleName)}\n[gray]阶段：应用/校验CP；解析模式：${readResult.parseMode}；已尝试回滚到加载前状态。")
    }
}

private fun unloadExternalCp(key: String, operator: String): ExternalCpResult {
    val record = loadedExternalCps[key]
        ?: return ExternalCpResult(false, "[yellow]该外部CP当前未加载。")
    val previousActive = currentAppliedPatchStrings()
    val previousRuntime = with(contentsTweaker) { contentPatches.toList() }
    val previousLoaded = LinkedHashMap(loadedExternalCps)
    return runCatching {
        removeRuntimeExternalPatch(record.patch)
        loadedExternalCps.remove(key)
        applyPatchStringsFromExternalBaselineAndSanitize(previousActive.filterNot { it == record.patch }, "外部CP卸载:${record.fileName}")
        if (loadedExternalCps.isEmpty()) externalContentBaseline = null
        syncWorldAfterExternalCpChange("卸载:${record.fileName}")
        logger.info("外部CP已卸载: ${record.fileName} by $operator")
        ExternalCpResult(true, "[green]已卸载外部CP：[gold]${record.displayName}\n[gray]已分批同步世界数据。")
    }.getOrElse { error ->
        runCatching {
            restoreTweakerRuntime(previousRuntime)
            loadedExternalCps.clear()
            loadedExternalCps.putAll(previousLoaded)
            val baseline = externalContentBaseline ?: serverContentBaseline
            applyPatchStringsFromBaselineAndSanitize(previousActive, "外部CP卸载失败回滚:${record.fileName}", baseline)
        }.onFailure { rollback -> logger.warning("外部CP卸载回滚失败: ${rollback.message}") }
        ExternalCpResult(false, "[red]卸载失败：[white]${error.message ?: error.javaClass.simpleName}")
    }
}

private fun unloadAllExternalCps(operator: String): ExternalCpResult {
    val records = loadedExternalCps.values.toList()
    if (records.isEmpty()) return ExternalCpResult(false, "[yellow]当前没有已加载的外部CP。")
    val previousActive = currentAppliedPatchStrings()
    val previousRuntime = with(contentsTweaker) { contentPatches.toList() }
    val previousLoaded = LinkedHashMap(loadedExternalCps)
    val patches = records.mapTo(hashSetOf()) { it.patch }
    return runCatching {
        with(contentsTweaker) {
            val keep = contentPatches.toList().filterNot { it in patches }
            contentPatches.clear()
            keep.forEach { contentPatches.add(it) }
        }
        loadedExternalCps.clear()
        applyPatchStringsFromExternalBaselineAndSanitize(previousActive.filterNot { it in patches }, "外部CP全部卸载")
        externalContentBaseline = null
        syncWorldAfterExternalCpChange("全部卸载")
        logger.info("外部CP已全部卸载 by $operator count=${records.size}")
        ExternalCpResult(true, "[green]已卸载全部外部CP：[gold]${records.size}[green] 个。")
    }.getOrElse { error ->
        runCatching {
            restoreTweakerRuntime(previousRuntime)
            loadedExternalCps.clear()
            loadedExternalCps.putAll(previousLoaded)
            val baseline = externalContentBaseline ?: serverContentBaseline
            applyPatchStringsFromBaselineAndSanitize(previousActive, "外部CP全部卸载失败回滚", baseline)
        }.onFailure { rollback -> logger.warning("外部CP全部卸载回滚失败: ${rollback.message}") }
        ExternalCpResult(false, "[red]全部卸载失败：[white]${error.message ?: error.javaClass.simpleName}")
    }
}

private fun externalCpListText(): String {
    val files = listExternalCpFiles()
    val dir = ensureExternalCpDir()
    if (files.isEmpty()) {
        return """
            |[yellow]当前没有可用外部CP文件。
            |[gray]请将 .json / .hjson CP 文件放入：
            |[white]${dir.absolutePath}
        """.trimMargin()
    }
    return buildString {
        appendLine("[cyan]外部CP目录：[white]${dir.absolutePath}")
        appendLine("[cyan]可用CP：[white]${files.size} 个  [cyan]已加载：[white]${loadedExternalCps.size} 个")
        files.forEach { cp ->
            val loaded = loadedExternalCps[fileKey(cp.name)]
            val state = if (loaded != null) "[green]已加载" else "[gray]未加载"
            appendLine("[gray]${cp.index}. [gold]${cp.displayName} [gray](${cpSizeStatus(cp)} / $state[gray])")
        }
    }.trimEnd()
}

private fun loadedExternalCpText(): String {
    if (loadedExternalCps.isEmpty()) return "[yellow]当前没有已加载的外部CP。"
    return buildString {
        appendLine("[cyan]当前已加载外部CP：[white]${loadedExternalCps.size} 个")
        loadedExternalCps.values.forEachIndexed { index, record ->
            val warn = if (record.warnings.isEmpty()) "[green]正常" else "[yellow]警告${record.warnings.size}"
            appendLine("[gray]${index + 1}. [gold]${record.displayName} [gray]($warn[gray]) by [white]${record.operator}")
        }
    }.trimEnd()
}

private suspend fun openExternalCpDetailMenu(player: Player, cp: ExternalCpFile) {
    val loaded = loadedExternalCps[fileKey(cp.name)]
    MenuBuilder<Unit>("外部CP：${cp.displayName}") {
        val warnings = loaded?.warnings.orEmpty()
        msg = buildString {
            appendLine("[cyan]文件：[white]${cp.name}")
            appendLine("[cyan]大小：[white]${cpSizeStatus(cp)}")
            appendLine("[cyan]状态：[white]${if (loaded == null) "[gray]未加载" else "[green]已加载 by ${loaded.operator}"}")
            appendLine("[cyan]路径：[gray]${cp.file.absolutePath}")
            slowSyncNotice(cp).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            if (loaded != null) {
                appendLine("[cyan]加载时间：[white]${loaded.loadedAt}")
                if (warnings.isEmpty()) appendLine("[cyan]警告：[green]无")
                else {
                    appendLine("[cyan]警告：[yellow]${warnings.size} 条")
                    warnings.take(6).forEach { appendLine("[yellow]- [white]$it") }
                    if (warnings.size > 6) appendLine("[gray]... 还有 ${warnings.size - 6} 条")
                }
                appendLine("[cyan]CP预览：[gray]${cpPreview(loaded.patch, 600)}")
            }
        }
        option(if (loaded == null) "[green]管理加载" else "[yellow]管理热重载") {
            val result = applyExternalCp(cp, player.name)
            player.sendMessage(result.message)
            openExternalCpDetailMenu(player, cp)
        }
        option("[cyan]发起投票加载/热重载") { startExternalCpLoadVote(player, cp) }
        if (loaded != null) {
            newRow()
            option("[red]卸载") {
                val result = unloadExternalCp(fileKey(cp.name), player.name)
                player.sendMessage(result.message)
                openExternalCpDetailMenu(player, cp)
            }
            option("[red]发起投票卸载") { startExternalCpUnloadVote(player, fileKey(cp.name), loaded.displayName) }
        }
        newRow()
        option("返回列表") { openExternalCpMenu(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openExternalCpMenu(player: Player) {
    val files = listExternalCpFiles()
    if (files.isEmpty()) {
        MenuBuilder<Unit>("外部CP热重载") {
            msg = externalCpListText()
            option("刷新") { openExternalCpMenu(player) }
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }
    object : PagedMenuBuilder<ExternalCpFile>(files, prePage = 6) {
        override suspend fun renderItem(item: ExternalCpFile) {
            val loaded = loadedExternalCps[fileKey(item.name)]
            val stateText = if (loaded == null) "[gray]未加载" else "[green]已加载"
            option("[gold]${item.displayName}\n[gray]${cpSizeStatus(item)} | $stateText") {
                openExternalCpDetailMenu(player, item)
            }
        }

        override suspend fun build() {
            title = "外部CP热重载"
            msg = """
                |[cyan]目录：[white]${ensureExternalCpDir().absolutePath}
                |[gray]管理可直接加载/卸载；普通玩家可通过 /vote cp load/unload <文件名|编号> 发起投票加载或卸载。
                |[gray]支持 .json / .hjson；超过 ${formatBytes(slowExternalCpBytes)} 将慢同步而非拒绝加载。
                |[yellow]外部CP仅当前局运行态有效，换图/Reset 后会清空记录。
            """.trimMargin()
            if (loadedExternalCps.isNotEmpty()) {
                option("[red]卸载全部已加载\n[gray]当前 ${loadedExternalCps.size} 个") {
                    val result = unloadAllExternalCps(player.name)
                    player.sendMessage(result.message)
                    openExternalCpMenu(player)
                }
                newRow()
            }
            super.build()
        }
    }.sendTo(player, 60_000)
}

private suspend fun openVoteExternalCpMenu(player: Player) {
    val files = listExternalCpFiles()
    if (files.isEmpty()) {
        player.sendMessage(externalCpListText())
        return
    }
    object : PagedMenuBuilder<ExternalCpFile>(files, prePage = 6) {
        override suspend fun renderItem(item: ExternalCpFile) {
            val loaded = loadedExternalCps[fileKey(item.name)]
            val stateText = if (loaded == null) "[gray]未加载，点击投票加载" else "[green]已加载，点击投票卸载"
            option("[gold]${item.displayName}\n[gray]${cpSizeStatus(item)} | $stateText") {
                if (loaded == null) startExternalCpLoadVote(player, item)
                else startExternalCpUnloadVote(player, fileKey(item.name), item.displayName)
            }
        }

        override suspend fun build() {
            title = "投票加载/卸载外部CP"
            msg = "[gray]选择 scripts/$externalCpDirName 下的 JSON/HJSON CP；未加载项投票加载，已加载项投票卸载。加载/卸载均需 70% 同意；大文件会慢同步。"
            if (loadedExternalCps.isNotEmpty()) {
                option("[red]投票卸载全部已加载\n[gray]当前 ${loadedExternalCps.size} 个") {
                    startExternalCpUnloadAllVote(player)
                }
                newRow()
            }
            super.build()
        }
    }.sendTo(player, 60_000)
}

private fun externalCpVoteRequireNum(all: Int): Int = ceil(all * 0.70).toInt()

private suspend fun startExternalCpLoadVote(starter: Player, cp: ExternalCpFile) {
    VoteService.start(
        starter,
        "加载外部CP ${cp.displayName}".with(),
        extDesc = """
            |[cyan]CP文件：[white]${cp.name}
            |[cyan]大小：[white]${cpSizeStatus(cp)}
            |[cyan]通过要求：[white]70% 同意
            |[yellow]投票通过后会热重载当前游戏内容，并分批同步世界数据。
            |${slowSyncNotice(cp).ifBlank { "[gray]文件大小正常，将使用标准同步间隔。" }}
            |[gray]若客户端显示异常，可手动 /sync 或重进。
        """.trimMargin(),
        supportSingle = true,
        requireNum = ::externalCpVoteRequireNum,
    ) {
        val result = applyExternalCp(cp, "投票:${starter.name}")
        broadcast(result.message.with())
    }
}

private suspend fun startExternalCpUnloadVote(starter: Player, key: String, displayName: String) {
    if (loadedExternalCps[key] == null) {
        starter.sendMessage("[yellow]该外部CP当前未加载，无需卸载。")
        return
    }
    VoteService.start(
        starter,
        "卸载外部CP $displayName".with(),
        extDesc = """
            |[cyan]CP文件：[white]$displayName
            |[cyan]通过要求：[white]70% 同意
            |[yellow]投票通过后会从当前局运行态卸载该外部CP，并分批同步世界数据。
            |[gray]若客户端显示异常，可手动 /sync 或重进。
        """.trimMargin(),
        supportSingle = true,
        requireNum = ::externalCpVoteRequireNum,
    ) {
        val result = unloadExternalCp(key, "投票:${starter.name}")
        broadcast(result.message.with())
    }
}

private suspend fun startExternalCpUnloadAllVote(starter: Player) {
    if (loadedExternalCps.isEmpty()) {
        starter.sendMessage("[yellow]当前没有已加载的外部CP。")
        return
    }
    VoteService.start(
        starter,
        "卸载全部外部CP(${loadedExternalCps.size}个)".with(),
        extDesc = """
            |[cyan]当前已加载：[white]${loadedExternalCps.values.joinToString("、") { it.displayName }}
            |[cyan]通过要求：[white]70% 同意
            |[yellow]投票通过后会卸载所有通过外部CP系统加载的CP，并分批同步世界数据。
        """.trimMargin(),
        supportSingle = true,
        requireNum = ::externalCpVoteRequireNum,
    ) {
        val result = unloadAllExternalCps("投票:${starter.name}")
        broadcast(result.message.with())
    }
}

fun VoteService.registerExternalCpVote() {
    addSubVote("加载/卸载外部CP/数据包", "[load|unload] [文件名|编号|all|list]", "cp", "externalcp", "ecp", "外部cp", "加载cp", "热重载cp") {
        val first = arg.firstOrNull()
        if (first == null || first.equals("menu", true) || first == "菜单" || first.equals("list", true) || first == "列表") {
            openVoteExternalCpMenu(player!!)
            return@addSubVote
        }
        if (first.equals("unload", true) || first.equals("remove", true) || first == "卸载" || first == "移除") {
            val target = arg.getOrNull(1) ?: returnReply("[red]用法：/vote cp unload <文件名|编号|all>".with())
            if (target.equals("all", true) || target == "全部") {
                startExternalCpUnloadAllVote(player!!)
                return@addSubVote
            }
            val cp = resolveExternalCpFile(target)
            val key = cp?.let { fileKey(it.name) }
                ?: loadedExternalCps.keys.firstOrNull {
                    it.equals(target, ignoreCase = true) || stripSupportedExtension(it).equals(target, ignoreCase = true)
                }
                ?: returnReply("[red]未找到已加载外部CP：[white]$target\n${loadedExternalCpText()}".with())
            val displayName = loadedExternalCps[key]?.displayName ?: cp?.displayName ?: stripSupportedExtension(key)
            startExternalCpUnloadVote(player!!, key, displayName)
            return@addSubVote
        }
        val loadTarget = if (first.equals("load", true) || first.equals("reload", true) || first == "加载" || first == "重载" || first == "热重载") {
            arg.getOrNull(1) ?: returnReply("[red]用法：/vote cp load <文件名|编号>".with())
        } else first
        val cp = resolveExternalCpFile(loadTarget)
            ?: returnReply("[red]未找到外部CP：[white]$loadTarget\n${externalCpListText()}".with())
        startExternalCpLoadVote(player!!, cp)
    }
}
command("externalcp", "管理指令：外部JSON/HJSON CP热重载") {
    aliases = listOf("ecp", "外部cp", "热重载cp")
    usage = "[list|loaded|load <文件名/编号>|unload <文件名/编号|all>|dir]"
    permission = "wayzer.admin.externalCp"
    body {
        val p = player
        when (arg.getOrNull(0)?.lowercase()) {
            null, "menu", "菜单" -> {
                if (p != null) openExternalCpMenu(p) else reply(externalCpListText().with())
            }
            "list", "列表" -> reply(externalCpListText().with())
            "loaded", "已加载", "status", "状态" -> reply(loadedExternalCpText().with())
            "dir", "目录" -> reply("[cyan]外部CP目录：[white]${ensureExternalCpDir().absolutePath}".with())
            "load", "reload", "加载", "重载", "热重载" -> {
                val target = arg.getOrNull(1) ?: returnReply("[red]请输入要加载的外部CP文件名或编号。".with())
                val cp = resolveExternalCpFile(target)
                    ?: returnReply("[red]未找到外部CP：[white]$target\n${externalCpListText()}".with())
                val result = applyExternalCp(cp, p?.name ?: "控制台")
                reply(result.message.with())
            }
            "unload", "remove", "卸载", "移除" -> {
                val target = arg.getOrNull(1) ?: returnReply("[red]请输入要卸载的外部CP文件名/编号，或 all。".with())
                val result = if (target.equals("all", true) || target == "全部") {
                    unloadAllExternalCps(p?.name ?: "控制台")
                } else {
                    val cp = resolveExternalCpFile(target)
                    val key = cp?.let { fileKey(it.name) }
                        ?: loadedExternalCps.keys.firstOrNull { it.equals(target, ignoreCase = true) || stripSupportedExtension(it).equals(target, ignoreCase = true) }
                        ?: returnReply("[red]未找到已加载外部CP：[white]$target\n${loadedExternalCpText()}".with())
                    unloadExternalCp(key, p?.name ?: "控制台")
                }
                reply(result.message.with())
            }
            else -> replyUsage()
        }
    }
}

listen<EventType.ResetEvent> {
    // ContentsTweaker 自身会在 Reset 时清空补丁并从原始内容基线恢复；这里仅清理外部 CP 运行态，
    // 避免换图时重复恢复内容快照造成额外主线程开销。
    loadedExternalCps.clear()
    externalContentBaseline = null
}

onEnable {
    ensureExternalCpDir()
    ensureServerContentBaseline()
    VoteService.registerExternalCpVote()
}

onDisable {
    val records = loadedExternalCps.values.toList()
    if (records.isNotEmpty()) {
        val patches = records.mapTo(hashSetOf()) { it.patch }
        runCatching {
            val remaining = currentAppliedPatchStrings().filterNot { it in patches }
            with(contentsTweaker) {
                val keep = contentPatches.toList().filterNot { it in patches }
                contentPatches.clear()
                keep.forEach { contentPatches.add(it) }
            }
            val baseline = externalContentBaseline ?: serverContentBaseline
            applyPatchStringsFromBaselineAndSanitize(remaining, "外部CP脚本卸载清理", baseline)
        }.onFailure {
            logger.warning("外部CP脚本卸载清理失败: ${it.message}")
        }
        loadedExternalCps.clear()
        externalContentBaseline = null
    }
}

PermissionApi.registerDefault("wayzer.admin.externalCp", group = "@admin")
