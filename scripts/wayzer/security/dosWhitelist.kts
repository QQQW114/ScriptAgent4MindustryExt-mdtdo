@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化（MdtSettings 键值存储）")

package wayzer.security

import coreLibrary.lib.PermissionApi
import coreMindustry.lib.hasPermission
import mindustry.gen.Groups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DOS 黑名单受信任 IP 白名单（/doswhitelist）。
 *
 * 背景：MindustryX/Arc 内核在 ArcNetProvider 的连接过滤器里，只要来访 IP 命中
 * netServer.admins.dosBlacklist（内存 ObjectSet，无过期、不落盘），就直接踢回
 * “IP marked as a potential DOS attack”。该集合由“3 秒内超过 packetSpamLimit 个包”触发，
 * 官方没有任何清除指令；大 NAT 出口（运营商 CGNAT、校园网、网吧出口）下只要一个客户端
 * 触发反 DOS，整个出口 IP 后面的玩家都会一起进不来。
 *
 * 本脚本维护一份“受信任 IP / CIDR 白名单”：
 * - 白名单从 MdtStorage 的 MdtSettings 键 security.dosWhitelist.entries 异步加载一次，
 *   热路径不读 DB；增删改内存立即生效，落库在 Dispatchers.IO 上串行异步完成；
 * - 定时任务（Dispatchers.game，默认 3000ms，可配并钳制到 1000..60000）遍历
 *   dosBlacklist 的内存快照，命中白名单的 IP 调 unBlacklistDos 自动解除；日志按 IP 限频；
 * - 只解除内核 DOS 黑名单：不解除 ban/kick 列表、不触碰 accountIpGuard/securityGuard
 *   等风控系统、不放宽玩家数量限制。
 *
 * 安全边界（务必只对可信出口使用）：被加入白名单的 IP 会绕过内核反 DOS 拉黑，
 * 因此只应对运营商大NAT/自有机房出口等可信静态 IP 段配置；CIDR 前缀限制为 /8 到 /32，
 * 避免误配 0.0.0.0/0 之类把反 DOS 保护整体关掉。
 */
name = "DOS黑名单可信IP白名单"

// ---------- 配置（均可在 SA 脚本配置里改；下一轮扫描自动生效） ----------

private val enabled by config.key(true, "是否启用：自动解除受信任 IP 的 DOS 黑名单")
private val cleanupIntervalMillis by config.key(
    3_000L, "扫描 dosBlacklist 的间隔(ms)；钳制到 1000..60000，改配置后下一轮自动生效"
)
private val notifyRemovals by config.key(true, "自动解除时是否私聊通知在线且有该指令权限的管理")
private val logCooldownMillis by config.key(60_000L, "同一 IP 的解除日志/通知最小间隔(ms)")

// ---------- 常量与持久化键 ----------

private val DOS_WHITELIST_PERMISSION = "wayzer.admin.dosWhitelist"
private val SETTING_KEY = "security.dosWhitelist.entries"

// CIDR 最低允许 /8：再宽的网段实质等于关闭反 DOS，属于危险误配，add 时直接拒绝。
private val MIN_IPV4_PREFIX = 8
private val MAX_COMMENT_LENGTH = 120
private val MAX_NOTICE_MAP_SIZE = 512

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// ---------- IP 解析与匹配（纯内存、无 IO） ----------

private fun ipv4ToLong(raw: String): Long? {
    val parts = raw.trim().split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        if (part.isEmpty() || part.length > 3) return null
        if (part.any { it !in '0'..'9' }) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        value = (value shl 8) or octet.toLong()
    }
    return value
}

private fun longToIpv4(value: Long): String =
    listOf(
        (value ushr 24) and 0xFF,
        (value ushr 16) and 0xFF,
        (value ushr 8) and 0xFF,
        value and 0xFF,
    ).joinToString(".") { it.toString() }

private fun ipv4Mask(prefix: Int): Long =
    if (prefix <= 0) 0L else (0xFFFF_FFFFL shl (32 - prefix)) and 0xFFFF_FFFFL

/**
 * 归一化 IPv6 字面量：去掉方括号/zone 后缀，交给 InetAddress 解析成展开小写形式
 * （与内核 ArcConnection.address 的 InetAddress.getHostAddress 同口径）。
 * 含冒号的字符串在 Java 里按 IPv6 字面量处理，不会触发 DNS 查询。
 */
private fun normalizeIpv6(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("[") && text.endsWith("]") && text.length > 2) {
        text = text.substring(1, text.length - 1)
    }
    val zoneIndex = text.indexOf('%')
    if (zoneIndex >= 0) text = text.substring(0, zoneIndex)
    if (text.indexOf(':') < 0) return null
    return runCatching { InetAddress.getByName(text).hostAddress?.lowercase() }.getOrNull()
}

/** 解析结果（尚未放入内存白名单）。 */
private class ParsedEntry(
    val display: String,
    val comment: String,
    val ipv6Key: String?,
    val network: Long,
    val prefix: Int,
)

/** 内存中的白名单条目：IPv4 用整数前缀比较，IPv6 按归一化字符串精确匹配。 */
private class WhitelistEntry(parsed: ParsedEntry) {
    val display: String = parsed.display
    val comment: String = parsed.comment
    val ipv6Key: String? = parsed.ipv6Key
    val network: Long = parsed.network
    val prefix: Int = parsed.prefix

    fun matches(rawIp: String): Boolean {
        val key = ipv6Key
        if (key != null) return normalizeIpv6(rawIp) == key
        val ip = ipv4ToLong(rawIp) ?: return false
        val shift = 32 - prefix
        return (ip ushr shift) == (network ushr shift)
    }
}

private fun sanitizeComment(raw: String): String =
    raw.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_COMMENT_LENGTH)

/**
 * 解析一条用户输入/持久化文本；非法条目返回 failure（message 为给玩家看的原因）。
 * 支持：单个 IPv4、IPv4 CIDR（/8../32，主机位自动归零）、单个 IPv6（精确匹配）。
 */
private fun parseWhitelistEntry(input: String, comment: String): Result<ParsedEntry> {
    val text = input.trim()
    if (text.isEmpty()) return Result.failure(IllegalArgumentException("条目为空"))
    if (text.length > 64) return Result.failure(IllegalArgumentException("条目过长"))

    val slash = text.indexOf('/')
    val ipPart: String
    val prefixPart: String?
    if (slash >= 0) {
        ipPart = text.substring(0, slash).trim()
        prefixPart = text.substring(slash + 1).trim()
        if (ipPart.isEmpty() || prefixPart.isEmpty()) {
            return Result.failure(IllegalArgumentException("CIDR 格式应为 ip/前缀，例如 61.143.18.0/24"))
        }
    } else {
        ipPart = text
        prefixPart = null
    }

    if (ipPart.indexOf(':') >= 0) {
        if (prefixPart != null) {
            return Result.failure(IllegalArgumentException("暂不支持 IPv6 CIDR，请填单个 IPv6 地址（精确匹配）"))
        }
        val key = normalizeIpv6(ipPart)
            ?: return Result.failure(IllegalArgumentException("非法 IPv6 地址"))
        return Result.success(ParsedEntry(key, comment, key, 0L, 128))
    }

    val ip = ipv4ToLong(ipPart)
        ?: return Result.failure(IllegalArgumentException("非法 IPv4 地址：$ipPart"))
    val prefix: Int
    if (prefixPart == null) {
        prefix = 32
    } else {
        prefix = prefixPart.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("CIDR 前缀必须是整数"))
        if (prefix !in MIN_IPV4_PREFIX..32) {
            return Result.failure(
                IllegalArgumentException("CIDR 前缀必须在 /$MIN_IPV4_PREFIX 到 /32 之间（过宽网段会关闭反 DOS 保护）")
            )
        }
    }
    // 主机位归零，保证同一网段的不同写法只存一份、便于展示与去重。
    val network = if (prefix == 32) ip else ip and ipv4Mask(prefix)
    val display = if (prefix == 32) longToIpv4(ip) else "${longToIpv4(network)}/$prefix"
    return Result.success(ParsedEntry(display, comment, null, network, prefix))
}

private fun decodePersistedLine(line: String): ParsedEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val textPart = trimmed.substringBefore('|').trim()
    val comment = if (trimmed.indexOf('|') >= 0) sanitizeComment(trimmed.substringAfter('|')) else ""
    return parseWhitelistEntry(textPart, comment).getOrNull()
}

private fun encodePersistedEntry(entry: WhitelistEntry): String =
    if (entry.comment.isEmpty()) entry.display else "${entry.display}|${entry.comment}"

// ---------- 内存状态 ----------

private val LOCK = Any()
private val entries = mutableListOf<WhitelistEntry>()

// 加载完成前被 remove 的条目，避免它们被旧 DB 数据重新恢复（脚本启用瞬间的极小窗口）。
private val pendingRemoved = mutableSetOf<String>()

// 加载完成前执行过 clear：加载时跳过本次 DB 合并，避免旧数据把刚清空的白名单恢复回来。
@Volatile private var clearedBeforeLoad = false

@Volatile private var loaded = false

// 运行统计：命令与扫描都在 Dispatchers.game（游戏线程）执行，不需要加锁。
private var cleanupJob: Job? = null
private var totalRemovals = 0L
private var lastRemovalAt = 0L
private var lastScanErrorLogAt = 0L
private val lastNoticeAtByIp = HashMap<String, Long>()

// ---------- DB 加载（Dispatchers.IO，禁止在游戏线程同步读库） ----------

private val LOAD_LOCK = Any()
@Volatile private var loadInFlight = false
@Volatile private var lastLoadAttemptAt = 0L
@Volatile private var lastLoadWarnAt = 0L

private fun warnLoadThrottled(action: String, error: Throwable?) {
    val now = System.currentTimeMillis()
    if (now - lastLoadWarnAt < 300_000L) return
    lastLoadWarnAt = now
    logger.warning("DOS白名单：$action 失败（5 分钟内最多告警一次，之后自动重试）：${error?.message ?: "未知错误"}")
}

/** 从 MdtSettings 读一次白名单（IO 线程）；与内存里已发生的增删合并，不覆盖。 */
private fun loadStateBlocking() {
    val startedAt = System.currentTimeMillis()
    val raw = try {
        MdtStorage.getSetting(SETTING_KEY)
    } catch (e: Exception) {
        warnLoadThrottled("加载持久化白名单", e)
        return
    }
    var skipped = 0
    val parsed = mutableListOf<ParsedEntry>()
    raw?.lineSequence()?.forEach { line ->
        val decode = decodePersistedLine(line)
        if (decode != null) parsed.add(decode) else if (line.isNotBlank()) skipped++
    }
    var added = 0
    var size = 0
    synchronized(LOCK) {
        if (!clearedBeforeLoad) {
            for (item in parsed) {
                if (item.display in pendingRemoved) continue
                if (entries.any { it.display == item.display }) continue
                entries.add(WhitelistEntry(item))
                added++
            }
        }
        clearedBeforeLoad = false
        pendingRemoved.clear()
        loaded = true
        size = entries.size
    }
    logger.info(
        "DOS白名单：已从数据库加载 $size 条（新增 $added 条，跳过非法 $skipped 条），耗时 ${System.currentTimeMillis() - startedAt}ms"
    )
    // 加载完成后立即补扫一次：覆盖“启用时 DB 尚未加载完”的窗口，也满足“启用时立即执行一次”。
    launch(Dispatchers.game) {
        try {
            scanOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnScanThrottled("加载完成补扫", e)
        }
    }
}

/** 幂等触发一次异步加载；DB 暂不可用时每 60s 自动重试，不阻塞游戏线程。 */
private fun requestLoadIfNeeded() {
    if (loaded) return
    val now = System.currentTimeMillis()
    val started = synchronized(LOAD_LOCK) {
        if (loaded || loadInFlight) return
        if (now - lastLoadAttemptAt < 60_000L) return
        loadInFlight = true
        lastLoadAttemptAt = now
        true
    }
    if (!started) return
    launch(Dispatchers.IO) {
        try {
            loadStateBlocking()
        } catch (e: Exception) {
            warnLoadThrottled("加载持久化白名单", e)
        } finally {
            synchronized(LOAD_LOCK) { loadInFlight = false }
        }
    }
}

// ---------- 异步串行落库（Dispatchers.IO；最后快照覆盖，后写覆盖先写） ----------

private val PERSIST_LOCK = Any()
private var pendingSnapshot: String? = null
private var persistInFlight = false
@Volatile private var lastPersistWarnAt = 0L

private fun warnPersistThrottled(error: Throwable?) {
    val now = System.currentTimeMillis()
    if (now - lastPersistWarnAt < 300_000L) return
    lastPersistWarnAt = now
    logger.warning("DOS白名单：保存白名单失败（内存态不受影响，5 分钟内最多告警一次）：${error?.message ?: "未知错误"}")
}

/** 内存变更后调用：锁内只生成最新全量快照，实际写库由单个 IO 协程串行完成。 */
private fun schedulePersist() {
    val snapshot = synchronized(LOCK) { entries.joinToString("\n") { encodePersistedEntry(it) } }
    val shouldStart = synchronized(PERSIST_LOCK) {
        pendingSnapshot = snapshot
        if (persistInFlight) {
            false
        } else {
            persistInFlight = true
            true
        }
    }
    if (!shouldStart) return
    launch(Dispatchers.IO) {
        while (true) {
            val current = synchronized(PERSIST_LOCK) {
                val value = pendingSnapshot
                pendingSnapshot = null
                if (value == null) persistInFlight = false
                value
            } ?: break
            try {
                MdtStorage.setSetting(SETTING_KEY, current)
            } catch (e: Exception) {
                warnPersistThrottled(e)
            }
        }
    }
}

// ---------- 定时解黑（Dispatchers.game，热路径只读内存） ----------

private fun currentInterval(): Long = cleanupIntervalMillis.coerceIn(1_000L, 60_000L)

private fun currentCooldown(): Long = logCooldownMillis.coerceIn(1_000L, 3_600_000L)

private fun matchingEntry(ip: String): WhitelistEntry? = synchronized(LOCK) {
    entries.firstOrNull { it.matches(ip) }
}

/** 在 Administration 监视器内复制 dosBlacklist 快照（内核 blacklistDos/unBlacklistDos 同为 synchronized）。 */
private fun snapshotDosBlacklist(): List<String> {
    val admins = netServer?.admins ?: return emptyList()
    return synchronized(admins) {
        val out = ArrayList<String>(admins.dosBlacklist.size)
        for (ip in admins.dosBlacklist) out.add(ip)
        out
    }
}

private fun currentBlacklistSize(): Int =
    runCatching { snapshotDosBlacklist().size }.getOrDefault(0)

private fun currentWhitelistHits(): List<String> =
    runCatching { snapshotDosBlacklist().filter { matchingEntry(it) != null } }.getOrDefault(emptyList())

private fun warnScanThrottled(action: String, error: Throwable?) {
    val now = System.currentTimeMillis()
    if (now - lastScanErrorLogAt < 60_000L) return
    lastScanErrorLogAt = now
    logger.warning("DOS白名单：$action 异常（60s 内最多告警一次，循环继续）：${error?.message ?: "未知错误"}")
}

/** 记录一次自动解除：计数、最近时间、限频日志、可选的管理私聊通知。 */
private suspend fun recordRemoval(ip: String, entry: WhitelistEntry) {
    totalRemovals++
    lastRemovalAt = System.currentTimeMillis()
    val now = lastRemovalAt
    val cooldown = currentCooldown()
    val last = lastNoticeAtByIp[ip]
    if (last != null && now - last < cooldown) return
    lastNoticeAtByIp[ip] = now
    if (lastNoticeAtByIp.size > MAX_NOTICE_MAP_SIZE) {
        lastNoticeAtByIp.entries.removeAll { now - it.value > cooldown * 2 }
    }
    val hit = if (entry.comment.isEmpty()) entry.display else "${entry.display}（${entry.comment}）"
    logger.info("白名单自动解除 DOS 黑名单：$ip 命中白名单 $hit，本次启用以来自动解除 $totalRemovals 次")
    if (!notifyRemovals) return
    runCatching {
        val message = "[yellow][DOS白名单] 已自动解除 [white]$ip[] 的 DOS 黑名单（命中 [white]$hit[]）".with()
        for (player in Groups.player) {
            if (player.hasPermission(DOS_WHITELIST_PERMISSION)) player.sendMessage(message)
        }
    }.onFailure { warnScanThrottled("通知在线管理", it) }
}

/** 扫描一轮：只遍历 dosBlacklist 的内存快照，对命中白名单且仍在黑名单里的 IP 调用解黑 API。 */
private suspend fun scanOnce() {
    if (!enabled) return
    if (!loaded) {
        requestLoadIfNeeded()
        return
    }
    val admins = netServer?.admins ?: return
    val blacklisted = try {
        synchronized(admins) {
            val out = ArrayList<String>(admins.dosBlacklist.size)
            for (ip in admins.dosBlacklist) out.add(ip)
            out
        }
    } catch (e: Exception) {
        warnScanThrottled("读取 dosBlacklist", e)
        return
    }
    if (blacklisted.isEmpty()) return
    for (ip in blacklisted) {
        val entry = matchingEntry(ip) ?: continue
        // 集合可能在扫描期间被其他路径改动，解除前再确认一次，避免误统计。
        val stillBlacklisted = try {
            admins.isDosBlacklisted(ip)
        } catch (e: Exception) {
            false
        }
        if (!stillBlacklisted) continue
        try {
            admins.unBlacklistDos(ip)
        } catch (e: Exception) {
            warnScanThrottled("解除 $ip", e)
            continue
        }
        recordRemoval(ip, entry)
    }
}

// ---------- 展示辅助 ----------

private fun formatTime(epochMillis: Long): String =
    if (epochMillis <= 0L) "从未"
    else Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

private fun entryLine(index: Int, entry: WhitelistEntry): String {
    val comment = if (entry.comment.isEmpty()) "" else "（${entry.comment}）"
    return "[white]${index.toString().padStart(2)}. [green]${entry.display}[gray]$comment"
}

private fun usageText(): String =
    "[red]用法：/doswhitelist <list|add|remove|clear|status|check>\n" +
            "[gray]  list[white] 列出白名单与当前命中的黑名单 IP\n" +
            "[gray]  add <ip|ip/前缀> [备注][white] 添加（IPv4、/8../32 CIDR、IPv6 精确）\n" +
            "[gray]  remove <序号|ip|ip/前缀>[white] 移除\n" +
            "[gray]  clear confirm[white] 清空（需二次确认）\n" +
            "[gray]  status[white] 查看启用状态/间隔/解除统计\n" +
            "[gray]  check <ip>[white] 检查某 IP 的黑名单/白名单命中情况"

private fun statusText(): String {
    val snapshot = synchronized(LOCK) { entries.toList() }
    val hits = currentWhitelistHits()
    return """
        |[cyan]DOS 黑名单受信任 IP 白名单
        |[white]启用：[yellow]${if (enabled) "是" else "否"}[white]  数据库状态：[yellow]${if (loaded) "已加载" else "加载中/失败（白名单暂不生效）"}
        |[white]白名单条目：[yellow]${snapshot.size}[white]  扫描间隔：[yellow]${currentInterval()}ms[white]  dosBlacklist 当前：[yellow]${currentBlacklistSize()}
        |[white]当前命中（会被自动解除）：[yellow]${hits.size}${if (hits.isEmpty()) "" else "  " + hits.joinToString("、")}
        |[white]本次启用以来自动解除：[yellow]$totalRemovals[white] 次  最近一次：[yellow]${formatTime(lastRemovalAt)}
        |[white]日志限频：[yellow]${currentCooldown()}ms[white]/IP  管理通知：[yellow]${if (notifyRemovals) "开" else "关"}
        |[gray]边界：只解除 Arc 内核 DOS 黑名单（dosBlacklist），不解除 ban/kick、不放宽人数、不影响其它风控；被白名单的 IP 会绕过内核反 DOS 拉黑，请只对可信大NAT出口使用。
    """.trimMargin()
}

// ---------- 指令 ----------

command("doswhitelist", "管理指令：DOS 黑名单受信任 IP 白名单（大NAT出口被误拉黑时自动解除）") {
    usage = "[list|add <ip[/前缀]> [备注]|remove <序号|ip[/前缀]>|clear confirm|status|check <ip>]"
    aliases = listOf("dos白名单", "ip白名单", "doswl")
    permission = DOS_WHITELIST_PERMISSION
    body {
        when (arg.getOrNull(0)?.trim()?.lowercase()) {
            null, "", "list", "列表" -> {
                val snapshot = synchronized(LOCK) { entries.toList() }
                reply(
                    "[cyan]DOS 黑名单白名单：共 ${snapshot.size} 条（${if (loaded) "DB 已加载" else "DB 加载中/失败，白名单暂不生效"}）"
                        .with()
                )
                if (snapshot.isEmpty()) {
                    reply("[gray]暂无条目。用 [white]/doswhitelist add <ip|ip/前缀> [备注][gray] 添加。".with())
                } else {
                    snapshot.forEachIndexed { index, entry -> reply(entryLine(index + 1, entry).with()) }
                }
                val hits = currentWhitelistHits()
                reply("[yellow]当前命中（会被自动解除）：${hits.size}".with())
                hits.forEach { reply("[white]  - $it".with()) }
                reply(
                    "[gray]本次启用以来自动解除 $totalRemovals 次，最近一次 ${formatTime(lastRemovalAt)}。".with()
                )
            }

            "add", "添加" -> {
                val raw = arg.getOrNull(1)?.trim()
                if (raw.isNullOrEmpty()) returnReply(usageText().with())
                val comment = sanitizeComment(arg.drop(2).joinToString(" "))
                val parsed = parseWhitelistEntry(raw, comment).getOrElse {
                    returnReply("[red]添加失败：${it.message ?: "非法条目"}".with())
                }
                var added = false
                var size = 0
                synchronized(LOCK) {
                    if (entries.none { it.display == parsed.display }) {
                        entries.add(WhitelistEntry(parsed))
                        added = true
                    }
                    if (!loaded) pendingRemoved.remove(parsed.display)
                    size = entries.size
                }
                if (!added) returnReply("[yellow]条目已存在：${parsed.display}".with())
                schedulePersist()
                val commentText = if (comment.isEmpty()) "" else "（$comment）"
                reply("[green]已添加白名单：${parsed.display}[gray]$commentText（当前 $size 条）".with())
            }

            "remove", "移除", "删除" -> {
                val raw = arg.getOrNull(1)?.trim()
                if (raw.isNullOrEmpty()) returnReply(usageText().with())
                val index = raw.toIntOrNull()
                var removed: WhitelistEntry? = null
                var size = 0
                synchronized(LOCK) {
                    if (index != null) {
                        if (index in 1..entries.size) removed = entries.removeAt(index - 1)
                    } else {
                        val target = parseWhitelistEntry(raw, "").getOrNull()
                        if (target != null) {
                            removed = entries.firstOrNull { it.display == target.display }
                                ?.also { entries.remove(it) }
                        }
                    }
                    if (!loaded && removed != null) pendingRemoved.add(removed!!.display)
                    size = entries.size
                }
                val target = removed ?: returnReply("[yellow]未找到条目：$raw（可用 list 查看序号）".with())
                schedulePersist()
                reply("[green]已移除白名单：${target.display}[gray]（当前 $size 条）".with())
            }

            "clear", "清空" -> {
                val confirm = arg.getOrNull(1)?.trim()?.lowercase()
                if (confirm != "confirm" && confirm != "确认" && confirm != "yes") {
                    val count = synchronized(LOCK) { entries.size }
                    returnReply("[yellow]清空白名单需二次确认：/doswhitelist clear confirm（当前 $count 条）".with())
                }
                var count = 0
                synchronized(LOCK) {
                    count = entries.size
                    entries.clear()
                    if (!loaded) {
                        // 加载完成前清空：DB 旧数据不能在加载时被恢复，用标记跳过本次合并。
                        clearedBeforeLoad = true
                        pendingRemoved.clear()
                    }
                }
                schedulePersist()
                reply("[green]已清空白名单（$count 条）。".with())
            }

            "status", "状态" -> reply(statusText().with())

            "check", "检查" -> {
                val raw = arg.getOrNull(1)?.trim()
                if (raw.isNullOrEmpty()) returnReply("[red]用法：/doswhitelist check <ip>".with())
                val admins = netServer?.admins
                val blacklisted = admins?.let {
                    runCatching { it.isDosBlacklisted(raw) }.getOrDefault(false)
                } ?: false
                val entry = matchingEntry(raw)
                val blackText = if (blacklisted) "[red]是" else "[green]否"
                val hitText = if (entry == null) {
                    "[gray]否"
                } else {
                    val commentText = if (entry.comment.isEmpty()) "" else "，${entry.comment}"
                    "[green]是（${entry.display}$commentText）"
                }
                reply("[white]IP [yellow]$raw[]：DOS 黑名单=[$blackText[]]，白名单命中=[$hitText[]]".with())
                if (blacklisted && entry != null) {
                    reply("[green]该 IP 会被自动解除（最多 ${currentInterval()}ms 后）。".with())
                } else if (blacklisted) {
                    reply("[yellow]该 IP 在白名单中未命中，不会被本脚本解除。".with())
                }
                reply("[gray]提示：黑名单判断按内核存储的原始字符串精确匹配。".with())
            }

            else -> reply(usageText().with())
        }
    }
}

// 默认仅 @admin 组可用；控制台拥有全部权限（ConsoleReceiver.hasPermission 恒为 true）。
PermissionApi.registerDefault(DOS_WHITELIST_PERMISSION, group = "@admin")

// ---------- 生命周期 ----------

onEnable {
    cleanupJob?.cancel()
    cleanupJob = launch(Dispatchers.game) {
        while (true) {
            try {
                if (!loaded) requestLoadIfNeeded()
                // 启用时立即扫一次；之后每轮扫描后按最新配置间隔等待。
                scanOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnScanThrottled("自动解黑循环", e)
            }
            delay(currentInterval())
        }
    }
    // 白名单从 DB 异步加载，不阻塞游戏线程；加载完成后会补扫一次。
    requestLoadIfNeeded()
}

onDisable {
    cleanupJob?.cancel()
    cleanupJob = null
}
