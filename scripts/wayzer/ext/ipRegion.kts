@file:Import("org.lionsoul:ip2region:2.7.0", mavenDepends = true)

package wayzer.ext

import org.lionsoul.ip2region.xdb.Searcher
import wayzer.lib.PlayerData
import java.io.File
import java.net.URL

name = "IP地区识别"

private val regionLookupEnabled by config.key(true, "是否启用IP地区识别")
private val autoDownloadDb by config.key(true, "缺少ip2region数据库时是否自动下载")
private val downloadUrl by config.key(
    "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
    "ip2region xdb 自动下载地址"
)
private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private var searcher: Searcher? = null
private var activeDbPath: String? = null

private val dbFileNames = listOf(
    "ip2region.xdb",
    "ip2region_v4.xdb",
)

private fun normalizeIp(raw: String?): String =
    raw?.let { ipv4Regex.find(it)?.value ?: it.trim().substringBefore('%') }
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

private fun isLocalOrPrivateIp(ip: String): Boolean {
    if (ip == "unknown" || ip == "localhost" || ip == "0:0:0:0:0:0:0:1" || ip == "::1") return true
    val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return ip.startsWith("steam:")
    val a = parts[0]
    val b = parts[1]
    return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
}

private fun search(ipRaw: String): List<String>? {
    if (!regionLookupEnabled) return null
    val ip = normalizeIp(ipRaw)
    if (isLocalOrPrivateIp(ip)) return listOf("本地网络", "0", "本地网络", "0", "0")
    val s = searcher ?: return null
    return runCatching { s.search(ip)?.split("|") }.getOrNull()
}

private fun dataDirFile(name: String): File =
    dataDirectory.child("scripts").child("data").child(name).file()

private fun findExistingDbFile(): File? =
    dbFileNames.map(::dataDirFile).firstOrNull { it.exists() && it.length() > 0 }

private fun downloadDbIfNeeded(): File? {
    findExistingDbFile()?.let { return it }
    if (!autoDownloadDb || downloadUrl.isBlank()) return null

    val target = dataDirFile("ip2region_v4.xdb")
    return runCatching {
        target.parentFile?.mkdirs()
        val conn = URL(downloadUrl).openConnection().apply {
            connectTimeout = 8_000
            readTimeout = 30_000
        }
        logger.info("[IP地区识别] 未找到本地 xdb，开始下载：$downloadUrl")
        conn.getInputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() <= 0) error("downloaded file is empty")
        logger.info("[IP地区识别] xdb 下载完成：${target.path} (${target.length()} bytes)")
        target
    }.getOrElse {
        logger.warning("[IP地区识别] 自动下载 xdb 失败：${it.message}")
        null
    }
}

private fun loadSearcher(): Boolean {
    val dbFile = downloadDbIfNeeded()
    if (dbFile == null) {
        val paths = dbFileNames.joinToString(" 或 ") { dataDirFile(it).path }
        logger.warning("[IP地区识别] 未找到 $paths，地区显示将回退为“未知地区”。可放入 xdb 后热重载本脚本。")
        return false
    }
    return runCatching {
        val buffer = Searcher.loadContentFromFile(dbFile.path)
        searcher?.close()
        searcher = Searcher.newWithBuffer(buffer)
        activeDbPath = dbFile.path
        logger.info("[IP地区识别] ip2region 数据库初始化完成：${dbFile.path}")
        true
    }.getOrElse {
        logger.warning("[IP地区识别] 初始化失败：${it.message}，地区显示将回退为“未知地区”。")
        searcher = null
        activeDbPath = null
        false
    }
}

fun getRegionByIP(ipRaw: String): String {
    val parts = search(ipRaw) ?: return "未知地区"
    val country = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "0" } ?: return "未知地区"
    val province = listOfNotNull(parts.getOrNull(1), parts.getOrNull(2))
        .firstOrNull { it.isNotBlank() && it != "0" && it != country }
    // ip2region.xdb 常见格式有两种：
    // v3: 国家|区域|省份|城市|ISP
    // v4: 国家|省份/州|城市|ISP|国家码
    // 欢迎消息预期为“中国玩家显示省份，海外玩家显示国家”。
    return if (country == "中国" || country.equals("China", ignoreCase = true)) {
        province ?: country
    } else {
        country
    }
}

fun getCountryByIP(ipRaw: String): String {
    val parts = search(ipRaw) ?: return "未知地区"
    return parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "未知地区"
}

onEnable {
    loadSearcher()
    onDisable { searcher?.close() }
}

registerVarForType<Player>().registerChild("regionName", "中文地区名") {
    getRegionByIP(it.con?.address ?: it.ip())
}

registerVarForType<Player>().registerChild("countryName", "中文国家名") {
    getCountryByIP(it.con?.address ?: it.ip())
}

command("ipregion", "管理指令: 查询玩家IP地区") {
    usage = "[reload|status|ip <IP>|玩家名/三位ID]"
    requirePermission("wayzer.admin.ipregion")
    body {
        when (arg.firstOrNull()?.lowercase()) {
            "reload" -> {
                val ok = loadSearcher()
                returnReply((if (ok) "[green]IP地区数据库已重载：${activeDbPath}" else "[red]IP地区数据库重载失败，详见控制台日志").with())
            }
            "status" -> {
                returnReply(
                    """
                    |[cyan]IP地区识别状态:
                    |[white]启用: [yellow]$regionLookupEnabled
                    |[white]数据库: [yellow]${activeDbPath ?: "未加载"}
                    |[white]自动下载: [yellow]$autoDownloadDb
                    """.trimMargin().with()
                )
            }
            "ip" -> {
                val rawIp = arg.getOrNull(1) ?: returnReply("[red]请输入IP".with())
                returnReply("[cyan]IP地区：[yellow]${getCountryByIP(rawIp)} / ${getRegionByIP(rawIp)}[] [gray](${normalizeIp(rawIp)})".with())
            }
        }
        val target = arg.firstOrNull()?.let { id ->
            PlayerData.findByShortId(id)?.player
                ?: Groups.player.find { it.name.contains(id, ignoreCase = true) || it.plainName().contains(id, ignoreCase = true) }
                ?: returnReply("[red]未找到玩家：$id".with())
        } ?: player ?: returnReply("[red]控制台请指定玩家".with())
        val ip = target.con?.address ?: target.ip()
        reply("[cyan]${target.plainName()}[] IP地区：[yellow]${getCountryByIP(ip)} / ${getRegionByIP(ip)}[] [gray]($ip)".with())
    }
}

PermissionApi.registerDefault("wayzer.admin.ipregion", group = "@admin")
