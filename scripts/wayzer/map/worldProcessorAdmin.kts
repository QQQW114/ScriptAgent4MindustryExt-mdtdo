@file:Depends("coreMindustry/contentsTweaker", "读取当前已加载CP")
@file:Depends("coreMindustry/menu", "CP管理菜单")
@file:Depends("wayzer/reGrief/worldResyncCoordinator", "世界重同步串行协调")

package wayzer.map

import arc.util.serialization.Jval
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreLibrary.lib.PermissionApi
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.world.blocks.defense.turrets.ContinuousTurret
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

name = "世界处理器与CP管理"

private val contentsTweaker = contextScript<coreMindustry.ContentsTweaker>()
private val worldResync = contextScript<wayzer.reGrief.WorldResyncCoordinator>()
private val cpNameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
private var cpAdminContentBaseline: Any? = null

private fun ensureCpAdminContentBaseline(): Any {
    cpAdminContentBaseline?.let { return it }
    return with(contentsTweaker) { captureContentStateSnapshotAny("CP管理脚本启用基线") }
        .also {
            cpAdminContentBaseline = it
            logger.info("CP管理已记录内容基线")
        }
}

private data class LoadedCpInfo(
    val index: Int,
    val name: String?,
    val patchName: String?,
    val jsonName: String?,
    val raw: String,
    val error: Boolean,
    val warnings: List<String>,
)

private fun loadedCpPatches(): List<LoadedCpInfo> {
    val infos = mutableListOf<LoadedCpInfo>()
    var index = 0
    for (patch in with(contentsTweaker) { loadedPatchInfos() }) {
        index++
        val raw = patch.patch
        val patchName = patch.name
        val jsonName = cpNameRegex.find(raw)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val name = listOfNotNull(patchName, jsonName)
            .distinct()
            .joinToString(" / ")
            .ifBlank { null }
        infos += LoadedCpInfo(
            index = index,
            name = name,
            patchName = patchName,
            jsonName = jsonName,
            raw = raw,
            error = patch.error,
            warnings = patch.warnings,
        )
    }
    return infos
}

private fun cpPreview(patch: String, limit: Int = 90): String =
    patch.replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)

private fun knownCpTagNames(): List<String> =
    with(contentsTweaker) { patchList }.filter { it.isNotBlank() }

private fun currentAppliedPatchStrings(): List<String> = with(contentsTweaker) { currentPatchStrings() }

private fun normalizeCpPatch(patch: String): String? = runCatching {
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
    Jval.read(raw).toString(Jval.Jformat.plain)
}.getOrNull()

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
            .onFailure { logger.warning("CP管理炮塔弹药保护：移除异常炮塔失败 ${build.block.name}: ${it.message}") }
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
        logger.warning("CP管理炮塔弹药保护：清理 $fixed 个无效弹药炮塔${if (reason.isBlank()) "" else " ($reason)"}")
    }
    return fixed
}

private fun applyCpPatches(patches: List<String>, reason: String) {
    cpAdminContentBaseline?.let { baseline ->
        with(contentsTweaker) { restoreContentStateSnapshotAny(baseline, "CP管理重放前恢复:$reason") }
    }
    with(contentsTweaker) { applyPatchStrings(patches) }
    sanitizeInvalidTurretAmmo(reason)
}

private suspend fun sendWorldDataCompat(player: mindustry.gen.Player) {
    with(worldResync) { resyncWorldAndAssets(player, "CP管理变更") }
}

private fun syncWorldAfterCpChange(reason: String) {
    val players = Groups.player.toList().filter { it.con != null && !it.isLocal }
    if (players.isEmpty()) return
    launch(Dispatchers.game) {
        players.forEachIndexed { index, p ->
            if (index > 0) delay(350L)
            runCatching {
                if (p.con != null) {
                    sendWorldDataCompat(p)
                }
            }.onFailure {
                logger.warning("CP管理同步玩家 ${p.plainName()} 失败($reason): ${it.message}")
            }
        }
    }
}

private data class CpActionResult(
    val success: Boolean,
    val message: String,
)

private fun removeCpFromTweakerRuntime(rawPatch: String) {
    with(contentsTweaker) {
        val keep = contentPatches.toList().filterNot { it == rawPatch }
        contentPatches.clear()
        keep.forEach { contentPatches.add(it) }
    }
}

private fun disableCpTags(info: LoadedCpInfo): List<String> {
    val candidates = setOfNotNull(info.patchName, info.jsonName, info.name).filter { it.isNotBlank() }.toSet()
    val removed = mutableListOf<String>()
    val currentPatchList = with(contentsTweaker) { patchList }
    val keep = currentPatchList.filter { name ->
        val tagPatch = state.map.tags.get("CT@$name")
        val normalized = tagPatch?.let { patch -> normalizeCpPatch(patch) }
        val matched = name in candidates || normalized == info.raw
        if (matched) {
            removed += name
            state.map.tags.remove("CT@$name")
        }
        !matched
    }
    with(contentsTweaker) { patchList = keep }
    return removed
}

private fun unloadCp(info: LoadedCpInfo, disableTags: Boolean, operator: String?): CpActionResult {
    val current = currentAppliedPatchStrings()
    if (info.index !in 1..current.size || current.getOrNull(info.index - 1) != info.raw) {
        return CpActionResult(false, "CP列表已变化，请刷新菜单后重试。")
    }

    val previousRuntime = with(contentsTweaker) { contentPatches.toList() }
    val previousPatchList = with(contentsTweaker) { patchList }
    val previousTags = previousPatchList.associateWith { state.map.tags.get("CT@$it") }
    val remaining = current.filterIndexed { i, _ -> i != info.index - 1 }
    return runCatching {
        removeCpFromTweakerRuntime(info.raw)
        val removedNames = if (disableTags) disableCpTags(info) else emptyList()
        applyCpPatches(remaining, "管理员${if (disableTags) "禁用" else "临时卸载"}CP")
        syncWorldAfterCpChange("管理员${if (disableTags) "禁用" else "临时卸载"}CP")
        val nameText = info.name ?: "CP#${info.index}"
        logger.info("管理员 ${operator ?: "未知"} ${if (disableTags) "禁用并卸载" else "临时卸载"} CP: $nameText")
        val tagText = if (removedNames.isNotEmpty()) "，已移除记录：${removedNames.joinToString("、")}" else ""
        CpActionResult(true, "[green]已${if (disableTags) "禁用并卸载" else "临时卸载"}：[white]$nameText$tagText\n[gray]已分批同步世界数据；若客户端仍显示异常，可手动 /sync 或重进。")
    }.getOrElse {
        runCatching {
            with(contentsTweaker) {
                contentPatches.clear()
                previousRuntime.forEach { patch -> contentPatches.add(patch) }
                patchList = previousPatchList
            }
            previousTags.forEach { (name, patch) ->
                if (patch == null) state.map.tags.remove("CT@$name") else state.map.tags.put("CT@$name", patch)
            }
            applyCpPatches(current, "管理员CP操作失败回滚")
        }.onFailure { rollback ->
            logger.warning("CP管理回滚失败: ${rollback.message}")
        }
        CpActionResult(false, "[red]操作失败：[white]${it.message ?: it.javaClass.simpleName}")
    }
}

private fun cpListText(): String {
    val patches = loadedCpPatches()
    if (patches.isEmpty()) return "[yellow]当前没有已加载CP/数据包。"

    val tagNames = knownCpTagNames()
    return buildString {
        appendLine("[cyan]当前已加载CP/数据包：[white]${patches.size} 个")
        if (tagNames.isNotEmpty()) {
            appendLine("[gray]地图/ContentsTweaker记录名：[white]${tagNames.joinToString("、")}")
        }
        patches.forEach { info ->
            val nameText = info.name ?: "CP#${info.index}"
            val status = when {
                info.error -> "[red]失败"
                info.warnings.isNotEmpty() -> "[yellow]警告${info.warnings.size}"
                else -> "[green]正常"
            }
            appendLine("[gray]${info.index}. [gold]$nameText[gray] [$status[gray]] - ${cpPreview(info.raw)}")
        }
        appendLine("[gray]游戏内输入 /cp 可打开管理菜单；控制台可用 /cp <编号> 查看详情。")
    }.trimEnd()
}

private fun cpDetailText(index: Int): String {
    val info = loadedCpPatches().getOrNull(index - 1)
        ?: return "[red]没有编号为 [white]$index[red] 的已加载CP/数据包。"
    val nameText = info.name ?: "CP#${info.index}"
    val status = when {
        info.error -> "[red]失败"
        info.warnings.isNotEmpty() -> "[yellow]有警告"
        else -> "[green]正常"
    }
    val warnings = if (info.warnings.isEmpty()) {
        "[green]无"
    } else {
        info.warnings.take(8).joinToString("\n") { "[yellow]- [white]$it" } +
                if (info.warnings.size > 8) "\n[gray]... 还有 ${info.warnings.size - 8} 条警告" else ""
    }
    return """
        |[cyan]CP详情 #[white]${info.index}
        |[cyan]名称：[gold]$nameText
        |[cyan]状态：[white]$status
        |[cyan]警告：
        |$warnings
        |[cyan]内容预览：
        |[gray]${cpPreview(info.raw, 1200)}
    """.trimMargin()
}

private fun cpMenuMsg(): String {
    val patches = loadedCpPatches()
    val tagNames = knownCpTagNames()
    return buildString {
        appendLine("[cyan]当前已加载CP/数据包：[white]${patches.size} 个")
        if (tagNames.isNotEmpty()) appendLine("[gray]ContentsTweaker记录名：[white]${tagNames.joinToString("、")}")
        appendLine("[yellow]临时卸载[gray]：只移出当前已应用CP，重载地图/脚本后可能恢复。")
        appendLine("[red]禁用并卸载[gray]：同时移除当前地图的CP记录，当前地图生命周期内不再自动恢复。")
    }.trimEnd()
}

private suspend fun openCpConfirmMenu(player: Player, info: LoadedCpInfo, disableTags: Boolean) {
    val nameText = info.name ?: "CP#${info.index}"
    MenuBuilder<Unit>("确认${if (disableTags) "禁用并卸载" else "临时卸载"}CP") {
        msg = """
            |[yellow]目标：[white]$nameText
            |[gray]编号：${info.index}
            |[gray]预览：${cpPreview(info.raw, 260)}
            |
            |${if (disableTags) "[red]确认后会从当前已应用CP与地图CP记录中移除。" else "[yellow]确认后仅从当前已应用CP中移除。"}
        """.trimMargin()
        option("[red]确认执行") {
            val result = unloadCp(info, disableTags, player.name)
            player.sendMessage(result.message)
            openCpListMenu(player)
        }
        option("返回详情") { openCpDetailMenu(player, info.index) }
        newRow()
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openCpDetailMenu(player: Player, index: Int) {
    val info = loadedCpPatches().getOrNull(index - 1)
    if (info == null) {
        player.sendMessage("[red]没有编号为 [white]$index[red] 的已加载CP/数据包。")
        openCpListMenu(player)
        return
    }
    MenuBuilder<Unit>("CP详情 #${info.index}") {
        msg = cpDetailText(info.index)
        option("[yellow]临时卸载") { openCpConfirmMenu(player, info, disableTags = false) }
        option("[red]禁用并卸载") { openCpConfirmMenu(player, info, disableTags = true) }
        newRow()
        option("返回列表") { openCpListMenu(player) }
        option("刷新详情") { openCpDetailMenu(player, info.index) }
        newRow()
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openCpListMenu(player: Player) {
    val patches = loadedCpPatches()
    if (patches.isEmpty()) {
        MenuBuilder<Unit>("CP/数据包管理") {
            msg = "[yellow]当前没有已加载CP/数据包。"
            option("刷新") { openCpListMenu(player) }
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }
    PagedMenuBuilder(patches) { info ->
        val nameText = info.name ?: "CP#${info.index}"
        val status = when {
            info.error -> "[red]失败"
            info.warnings.isNotEmpty() -> "[yellow]警告${info.warnings.size}"
            else -> "[green]正常"
        }
        option("[gray]${info.index}. [gold]$nameText [gray]($status[gray])") {
            openCpDetailMenu(player, info.index)
        }
    }.apply {
        title = "CP/数据包管理"
        msg = cpMenuMsg()
        sendTo(player, 60_000)
    }
}

private fun worldProcessorStatus(): String =
    if (state.rules.disableWorldProcessors) "[red]已关闭" else "[green]已开启"

private fun worldProcessorEditStatus(): String =
    if (state.rules.allowEditWorldProcessors) "[yellow]允许所有玩家编辑" else "[green]仅编辑器/地图测试环境可编辑"

private fun setWorldProcessorEnabled(enabled: Boolean, operator: String?, announce: Boolean = true): String {
    val disabled = !enabled
    val changed = state.rules.disableWorldProcessors != disabled
    state.rules.disableWorldProcessors = disabled
    Call.setRules(state.rules)
    val message = if (enabled) {
        "[green]世界处理器已被管理员开启"
    } else {
        "[red]世界处理器已被管理员关闭"
    }
    if (announce) broadcast(message.with())
    else operator?.let { logger.info("世界处理器静默${if (enabled) "开启" else "关闭"} by $it") }
    if (!changed) {
        operator?.let { logger.info("世界处理器状态未变化: enabled=$enabled by $it") }
    }
    return message
}

private fun setWorldProcessorEditAllowed(allowed: Boolean, operator: String?, announce: Boolean = true): String {
    val changed = state.rules.allowEditWorldProcessors != allowed
    state.rules.allowEditWorldProcessors = allowed
    Call.setRules(state.rules)
    val message = if (allowed) {
        "[yellow]世界处理器编辑已被管理员开启：所有玩家现在都可以编辑世界处理器。"
    } else {
        "[green]世界处理器编辑已被管理员关闭。"
    }
    if (announce) broadcast(message.with())
    else operator?.let { logger.info("世界处理器编辑静默${if (allowed) "开启" else "关闭"} by $it") }
    if (!changed) {
        operator?.let { logger.info("世界处理器编辑状态未变化: allow=$allowed by $it") }
    }
    return message
}

command("cp", "管理指令：列出当前已经加载的CP/数据包") {
    usage = "[编号|unload <编号>|disable <编号>]"
    aliases = listOf("contentpatch", "内容包", "数据包")
    permission = "wayzer.admin.worldProcessor"
    body {
        val p = player
        when (arg.getOrNull(0)?.lowercase()) {
            null, "menu", "菜单" -> {
                if (p != null) openCpListMenu(p) else reply(cpListText().with())
            }
            "unload", "卸载", "临时卸载" -> {
                val index = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]请输入要临时卸载的CP编号。".with())
                val info = loadedCpPatches().getOrNull(index - 1) ?: returnReply("[red]没有编号为 [white]$index[red] 的已加载CP/数据包。".with())
                val result = unloadCp(info, disableTags = false, operator = p?.name ?: "控制台")
                reply(result.message.with())
            }
            "disable", "禁用", "remove", "移除" -> {
                val index = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]请输入要禁用并卸载的CP编号。".with())
                val info = loadedCpPatches().getOrNull(index - 1) ?: returnReply("[red]没有编号为 [white]$index[red] 的已加载CP/数据包。".with())
                val result = unloadCp(info, disableTags = true, operator = p?.name ?: "控制台")
                reply(result.message.with())
            }
            else -> {
                val index = arg.getOrNull(0)?.toIntOrNull()
                if (index == null) replyUsage() else {
                    if (p != null) openCpDetailMenu(p, index) else reply(cpDetailText(index).with())
                }
            }
        }
    }
}

command("worldprocessor", "管理指令：控制世界处理器并查看CP") {
    aliases = listOf("wp", "世界处理器")
    usage = "<status|on|off|edit on|edit off|cp>"
    permission = "wayzer.admin.worldProcessor"
    body {
        when (arg.getOrNull(0)?.lowercase()) {
            null, "status", "状态" -> reply(
                """
                    |[cyan]世界处理器运行状态：[white]{status}
                    |[cyan]世界处理器编辑权限：[white]{editStatus}
                    |[gray]提示：原版规则 allowEditWorldProcessors 是全局开关，开启后不是只允许管理员，而是允许所有玩家编辑世界处理器。
                """.trimMargin().with("status" to worldProcessorStatus(), "editStatus" to worldProcessorEditStatus())
            )
            "on", "enable", "open", "开启", "启用", "开" -> setWorldProcessorEnabled(true, player?.name ?: "控制台")
            "off", "disable", "close", "关闭", "禁用", "关" -> setWorldProcessorEnabled(false, player?.name ?: "控制台")
            "edit", "编辑" -> when (arg.getOrNull(1)?.lowercase()) {
                "on", "enable", "open", "开启", "启用", "开", "allow" -> setWorldProcessorEditAllowed(true, player?.name ?: "控制台")
                "off", "disable", "close", "关闭", "禁用", "关", "deny" -> setWorldProcessorEditAllowed(false, player?.name ?: "控制台")
                null, "status", "状态" -> reply("[cyan]世界处理器编辑权限：[white]{editStatus}".with("editStatus" to worldProcessorEditStatus()))
                else -> replyUsage()
            }
            "cp", "cps", "list", "列表", "数据包" -> {
                val index = arg.getOrNull(1)?.toIntOrNull()
                reply((if (index == null) cpListText() else cpDetailText(index)).with())
            }
            else -> replyUsage()
        }
    }
}

command("worldprocessorquiet", "管理指令：静默控制世界处理器，不全局播报") {
    aliases = listOf("wpq", "静默世处", "静默世界处理器")
    usage = "<status|on|off|edit on|edit off>"
    permission = "wayzer.admin.worldProcessor"
    body {
        val operator = player?.name ?: "控制台"
        when (arg.getOrNull(0)?.lowercase()) {
            null, "status", "状态" -> reply(
                """
                    |[cyan]世界处理器运行状态：[white]{status}
                    |[cyan]世界处理器编辑权限：[white]{editStatus}
                    |[gray]此指令修改状态时仅回复操作者/控制台，不会全局播报。
                """.trimMargin().with("status" to worldProcessorStatus(), "editStatus" to worldProcessorEditStatus())
            )
            "on", "enable", "open", "开启", "启用", "开" -> reply(setWorldProcessorEnabled(true, operator, announce = false).with())
            "off", "disable", "close", "关闭", "禁用", "关" -> reply(setWorldProcessorEnabled(false, operator, announce = false).with())
            "edit", "编辑" -> when (arg.getOrNull(1)?.lowercase()) {
                "on", "enable", "open", "开启", "启用", "开", "allow" -> reply(setWorldProcessorEditAllowed(true, operator, announce = false).with())
                "off", "disable", "close", "关闭", "禁用", "关", "deny" -> reply(setWorldProcessorEditAllowed(false, operator, announce = false).with())
                null, "status", "状态" -> reply("[cyan]世界处理器编辑权限：[white]{editStatus}".with("editStatus" to worldProcessorEditStatus()))
                else -> replyUsage()
            }
            else -> replyUsage()
        }
    }
}

onEnable {
    ensureCpAdminContentBaseline()
}

PermissionApi.registerDefault("wayzer.admin.worldProcessor", group = "@admin")
