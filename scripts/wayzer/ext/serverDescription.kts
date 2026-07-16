@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.ext

import mindustry.net.Administration.Config
import wayzer.lib.MdtStorage
import java.time.Duration
import java.util.Base64

name = "MDT服务器介绍轮播"

private val rotateInterval by config.key(Duration.ofMinutes(5)!!, "服务器列表介绍自动切换间隔")

private val DESC_ITEMS_KEY = "serverDesc.items"
private val DESC_ENABLED_KEY = "serverDesc.enabled"
private val DESC_SEEDED_KEY = "serverDesc.seeded.v1"
private val DESC_BASE_KEY = "serverDesc.base"
private val DESC_CURRENT_ID_KEY = "serverDesc.currentId"
private val DESC_LOCKED_ID_KEY = "serverDesc.lockedId"
private val DESC_LIST_PAGE_SIZE = 8
private val DESC_MAX_BYTES = 100
private val descCacheLock = Any()
private var descItemsCache: List<DescItem>? = null
private var rotationEnabledCache: Boolean? = null
private var baseDescriptionCache: String? = null
private var currentAppliedIdCache: Int? = null
private var lockedDescriptionIdCache: Int? = null
private var currentAppliedIdLoaded = false
private var lockedDescriptionIdLoaded = false

private data class DescItem(
    val id: Int,
    val text: String,
    val enabled: Boolean = true,
)

private fun encodePart(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodePart(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

private fun normalizeDesc(text: String): String =
    text.replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

private fun limitUtf8(text: String, maxBytes: Int = DESC_MAX_BYTES): String {
    var used = 0
    val builder = StringBuilder()
    for (ch in text) {
        val bytes = ch.toString().toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        builder.append(ch)
        used += bytes
    }
    return builder.toString()
}

private fun sanitizeDesc(text: String): String =
    limitUtf8(normalizeDesc(text))

private fun serializeDesc(item: DescItem): String =
    listOf(item.id.toString(), if (item.enabled) "1" else "0", encodePart(item.text)).joinToString("|")

private fun deserializeDesc(line: String): DescItem? = runCatching {
    val parts = line.split('|', limit = 3)
    if (parts.size != 3) return@runCatching null
    DescItem(
        id = parts[0].toInt(),
        enabled = parts[1] == "1",
        text = sanitizeDesc(decodePart(parts[2])),
    ).takeIf { it.text.isNotBlank() }
}.getOrNull()

private fun loadDescriptions(): List<DescItem> = synchronized(descCacheLock) {
    descItemsCache?.let { return@synchronized it }
    val loaded = MdtStorage.getSetting(DESC_ITEMS_KEY)
        .orEmpty()
        .lineSequence()
        .mapNotNull(::deserializeDesc)
        .sortedBy { it.id }
        .toList()
    descItemsCache = loaded
    loaded
}

private fun saveDescriptions(items: List<DescItem>) {
    val sorted = items.sortedBy { it.id }
    synchronized(descCacheLock) { descItemsCache = sorted }
    MdtStorage.setSetting(
        DESC_ITEMS_KEY,
        sorted.joinToString("\n", transform = ::serializeDesc)
    )
}

private fun nextDescId(items: List<DescItem>): Int = (items.maxOfOrNull { it.id } ?: 0) + 1

private fun rotationEnabled(): Boolean = synchronized(descCacheLock) {
    rotationEnabledCache ?: (MdtStorage.getSetting(DESC_ENABLED_KEY) != "false").also {
        rotationEnabledCache = it
    }
}

private fun setRotationEnabled(enabled: Boolean) {
    synchronized(descCacheLock) { rotationEnabledCache = enabled }
    MdtStorage.setSetting(DESC_ENABLED_KEY, enabled.toString())
}

private fun configDescription(): String = Config.desc.string()

private fun visibleConfigDescription(): String {
    val current = configDescription()
    return if (current == "off") "" else current
}

private fun baseDescription(): String = synchronized(descCacheLock) {
    baseDescriptionCache?.let { return@synchronized it }
    val base = MdtStorage.getSetting(DESC_BASE_KEY)
        ?.let(::sanitizeDesc)
        ?.takeIf { it.isNotBlank() }
        ?: sanitizeDesc(visibleConfigDescription()).also {
            if (it.isNotBlank()) MdtStorage.setSetting(DESC_BASE_KEY, it)
        }
    baseDescriptionCache = base
    base
}

private fun defaultDescriptions(): List<String> {
    val base = baseDescription()
    return listOfNotNull(
        base.takeIf { it.isNotBlank() },
        "---MDT DO!===",
        "---MDT DO===\n，[cyan]服务器内输入 /wiki 查看规则与QQ群！",
        "服务器内双击任意玩家可打开其信息面板（包括你自己！）",
        "[blue]看看/wiki谢谢喵，看看帖子谢谢喵",
    ).map(::sanitizeDesc).filter { it.isNotBlank() }.distinct()
}

private fun seedDefaultDescriptions(force: Boolean = false): Int {
    if (!force && MdtStorage.getSetting(DESC_SEEDED_KEY) == "true") return 0
    val existing = loadDescriptions().toMutableList()
    val existedTexts = existing.map { it.text }.toMutableSet()
    var nextId = nextDescId(existing)
    var added = 0
    defaultDescriptions().forEach { text ->
        if (!existedTexts.add(text)) return@forEach
        existing += DescItem(nextId++, text, enabled = true)
        added++
    }
    saveDescriptions(existing)
    MdtStorage.setSetting(DESC_SEEDED_KEY, "true")
    return added
}

private fun currentAppliedId(): Int? = synchronized(descCacheLock) {
    if (!currentAppliedIdLoaded) {
        currentAppliedIdCache = MdtStorage.getSetting(DESC_CURRENT_ID_KEY)?.toIntOrNull()
        currentAppliedIdLoaded = true
    }
    currentAppliedIdCache
}

private fun lockedDescriptionId(): Int? = synchronized(descCacheLock) {
    if (!lockedDescriptionIdLoaded) {
        lockedDescriptionIdCache = MdtStorage.getSetting(DESC_LOCKED_ID_KEY)?.toIntOrNull()
        lockedDescriptionIdLoaded = true
    }
    lockedDescriptionIdCache
}

private fun setLockedDescriptionId(id: Int?) {
    synchronized(descCacheLock) {
        lockedDescriptionIdCache = id
        lockedDescriptionIdLoaded = true
    }
    MdtStorage.setSetting(DESC_LOCKED_ID_KEY, id?.toString())
}

private fun lockedDescription(): DescItem? {
    val id = lockedDescriptionId() ?: return null
    return loadDescriptions().firstOrNull { it.id == id }
}

private fun applyDescriptionText(text: String, itemId: Int? = null): String {
    val safe = sanitizeDesc(text)
    Config.desc.set(if (safe.isBlank()) "off" else safe)
    if (itemId != null) {
        synchronized(descCacheLock) {
            currentAppliedIdCache = itemId
            currentAppliedIdLoaded = true
        }
        MdtStorage.setSetting(DESC_CURRENT_ID_KEY, itemId.toString())
    }
    logger.info("服务器介绍已切换为: ${if (safe.isBlank()) "off" else safe}")
    return safe
}

private fun restoreBaseDescription(): String =
    applyDescriptionText(baseDescription())

private fun pickNextDescription(): DescItem? {
    val candidates = loadDescriptions().filter { it.enabled && it.text.isNotBlank() }
    if (candidates.isEmpty()) return null
    val currentId = currentAppliedId()
    val currentIndex = candidates.indexOfFirst { it.id == currentId }
    return candidates[Math.floorMod(currentIndex + 1, candidates.size)]
}

private fun applyNextDescription(): DescItem? {
    val next = pickNextDescription() ?: return null
    applyDescriptionText(next.text, next.id)
    return next
}

private fun applyLockedOrNextDescription(): DescItem? {
    lockedDescription()?.let { locked ->
        applyDescriptionText(locked.text, locked.id)
        return locked
    }
    return applyNextDescription()
}

private fun upsertDescription(item: DescItem): DescItem {
    val items = loadDescriptions().toMutableList()
    val saved = if (item.id <= 0) item.copy(id = nextDescId(items)) else item
    val index = items.indexOfFirst { it.id == saved.id }
    if (index >= 0) items[index] = saved else items += saved
    saveDescriptions(items)
    return saved
}

private fun compactText(text: String, limit: Int = 36): String {
    val oneLine = normalizeDesc(text)
    return if (oneLine.length <= limit) oneLine else oneLine.take(limit) + "..."
}

private fun adminList(pageInput: Int): String {
    val items = loadDescriptions()
    if (items.isEmpty()) return "[yellow]当前没有服务器介绍"
    val totalPage = ((items.size + DESC_LIST_PAGE_SIZE - 1) / DESC_LIST_PAGE_SIZE).coerceAtLeast(1)
    val page = pageInput.coerceIn(1, totalPage)
    val start = (page - 1) * DESC_LIST_PAGE_SIZE
    val current = currentAppliedId()
    val locked = lockedDescriptionId()
    val body = items.drop(start).take(DESC_LIST_PAGE_SIZE).joinToString("\n") { item ->
        val state = if (item.enabled) "[green]启用" else "[gray]停用"
        val active = if (item.id == current) " [accent]当前" else ""
        val lock = if (item.id == locked) " [pink]锁定" else ""
        "[cyan]#${item.id} $state$active$lock [lightgray]${compactText(item.text)}"
    }
    return "[gold]服务器介绍列表 $page/$totalPage\n$body"
}

private fun findDescription(id: Int): DescItem? = loadDescriptions().firstOrNull { it.id == id }

onEnable {
    val added = seedDefaultDescriptions()
    if (added > 0) logger.info("服务器介绍轮播已导入默认介绍 $added 条")
    if (rotationEnabled()) {
        applyLockedOrNextDescription()
    }
    launch(Dispatchers.game) {
        while (true) {
            delay(rotateInterval.toMillis())
            if (!rotationEnabled()) continue
            applyLockedOrNextDescription()
        }
    }
}

command("descadmin", "管理指令：管理服务器列表介绍轮播") {
    usage = "<list|add|set|remove|enable|disable|apply|lock|unlock|next|on|off|status|seed> ..."
    permission = "wayzer.admin.serverDescription"
    aliases = listOf("serverdesc", "descriptionadmin", "介绍管理")
    body {
        if (arg.isEmpty()) returnReply(adminList(1).with())
        when (arg[0].lowercase()) {
            "list", "列表" -> {
                val page = arg.getOrNull(1)?.toIntOrNull() ?: 1
                returnReply(adminList(page).with())
            }
            "add", "添加" -> {
                val text = sanitizeDesc(arg.drop(1).joinToString(" "))
                if (text.isBlank()) returnReply("[red]用法：/descadmin add <介绍内容>".with())
                val saved = upsertDescription(DescItem(0, text, enabled = true))
                returnReply("[green]已添加服务器介绍 #[white]${saved.id}[green]：[white]${saved.text}".with())
            }
            "set", "修改" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/descadmin set <id> <介绍内容>".with())
                val text = sanitizeDesc(arg.drop(2).joinToString(" "))
                if (text.isBlank()) returnReply("[red]用法：/descadmin set <id> <介绍内容>".with())
                val old = findDescription(id)
                val saved = upsertDescription(DescItem(id, text, enabled = old?.enabled ?: true))
                if (currentAppliedId() == id || lockedDescriptionId() == id) applyDescriptionText(saved.text, saved.id)
                returnReply("[green]已修改服务器介绍 #[white]${saved.id}".with())
            }
            "remove", "del", "delete", "删除" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/descadmin remove <id>".with())
                val items = loadDescriptions().toMutableList()
                val removed = items.removeIf { it.id == id }
                if (removed) {
                    saveDescriptions(items)
                    if (lockedDescriptionId() == id) setLockedDescriptionId(null)
                    if (currentAppliedId() == id) applyNextDescription() ?: restoreBaseDescription()
                }
                returnReply((if (removed) "[green]已删除服务器介绍 #$id" else "[yellow]未找到服务器介绍 #$id").with())
            }
            "enable", "启用" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/descadmin enable <id>".with())
                val oldItems = loadDescriptions()
                if (oldItems.none { it.id == id }) returnReply("[yellow]未找到服务器介绍 #$id".with())
                saveDescriptions(oldItems.map { if (it.id == id) it.copy(enabled = true) else it })
                returnReply("[green]已启用服务器介绍 #$id".with())
            }
            "disable", "停用" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/descadmin disable <id>".with())
                val oldItems = loadDescriptions()
                if (oldItems.none { it.id == id }) returnReply("[yellow]未找到服务器介绍 #$id".with())
                saveDescriptions(oldItems.map { if (it.id == id) it.copy(enabled = false) else it })
                if (currentAppliedId() == id && lockedDescriptionId() != id) applyNextDescription() ?: restoreBaseDescription()
                returnReply("[green]已停用服务器介绍 #$id".with())
            }
            "apply", "应用" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/descadmin apply <id>".with())
                val item = findDescription(id) ?: returnReply("[yellow]未找到服务器介绍 #$id".with())
                applyDescriptionText(item.text, item.id)
                returnReply("[green]已应用服务器介绍 #[white]${item.id}[green]：[white]${item.text}".with())
            }
            "lock", "锁定" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/descadmin lock <id>".with())
                val item = findDescription(id) ?: returnReply("[yellow]未找到服务器介绍 #$id".with())
                setLockedDescriptionId(item.id)
                applyDescriptionText(item.text, item.id)
                returnReply("[green]已锁定服务器介绍 #[white]${item.id}[green]：[white]${item.text}".with())
            }
            "unlock", "解锁", "解除锁定" -> {
                val old = lockedDescriptionId()
                setLockedDescriptionId(null)
                returnReply((if (old == null) "[yellow]当前没有锁定服务器介绍" else "[green]已解除服务器介绍锁定 #$old").with())
            }
            "next", "下一条" -> {
                lockedDescription()?.let { item ->
                    returnReply("[yellow]服务器介绍已锁定 #${item.id}，如需切换请先使用 /descadmin unlock".with())
                }
                val item = applyNextDescription() ?: returnReply("[yellow]没有可用的服务器介绍".with())
                returnReply("[green]已切换到服务器介绍 #[white]${item.id}[green]：[white]${item.text}".with())
            }
            "on", "开启" -> {
                setRotationEnabled(true)
                val item = applyLockedOrNextDescription()
                returnReply(
                    (if (item == null) "[green]已开启服务器介绍轮播，但当前没有可用介绍"
                    else "[green]已开启服务器介绍轮播，并切换到 #[white]${item.id}").with()
                )
            }
            "off", "关闭" -> {
                setRotationEnabled(false)
                val restored = restoreBaseDescription()
                returnReply("[green]已关闭服务器介绍轮播，已恢复基础介绍：[white]${restored.ifBlank { "off" }}".with())
            }
            "status", "状态" -> {
                val locked = lockedDescriptionId()
                val lockedText = locked?.let { id ->
                    findDescription(id)?.let { "#${it.id} ${compactText(it.text)}" } ?: "#$id（已丢失）"
                } ?: "无"
                returnReply(
                    """
                    |[gold]服务器介绍轮播状态
                    |[white]轮播: [yellow]${rotationEnabled()}
                    |[white]锁定: [yellow]$lockedText
                    |[white]间隔: [yellow]${rotateInterval.toMinutes()} 分钟
                    |[white]当前: [yellow]${visibleConfigDescription().ifBlank { "off" }}
                    |[white]基础介绍: [yellow]${baseDescription().ifBlank { "off" }}
                    """.trimMargin().with()
                )
            }
            "seed", "导入默认" -> {
                val added = seedDefaultDescriptions(force = true)
                returnReply("[green]已重新导入默认服务器介绍，新增 [white]$added[green] 条".with())
            }
            else -> returnReply("[red]未知操作。用法：/descadmin list|add|set|remove|enable|disable|apply|lock|unlock|next|on|off|status|seed".with())
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.serverDescription", group = "@admin")
