@file:Depends("wayzer/maps")

package wayzer.map

import arc.files.Fi
import arc.util.Strings
import arc.util.serialization.JsonReader
import arc.util.serialization.JsonValue
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CancellationException
import mindustry.game.Gamemode
import mindustry.io.MapIO
import wayzer.MapInfo
import wayzer.MapProvider
import wayzer.MapRegistry
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.Proxy
import java.net.URLEncoder
import java.time.Duration
import java.util.logging.Level

val webRoot by config.key("https://api.mindustry.top", "Mindustry资源站Api")
val httpConnectTimeoutMillis by config.key(8_000, "资源站连接超时(ms)")
val httpReadTimeoutMillis by config.key(15_000, "资源站读取超时(ms)")
val httpRetryDelayMillis by config.key(1_500L, "资源站失败重试间隔(ms)")
var resourceProxyEnabled by config.key(false, "是否让资源站请求走本机代理")
var resourceProxyHost by config.key("127.0.0.1", "资源站代理地址，通常为127.0.0.1")
var resourceProxyPort by config.key(7890, "资源站代理端口，例如 Clash 7890、V2Ray 10808")
var resourceProxyType by config.key("HTTP", "资源站代理类型：HTTP 或 SOCKS")

fun parseJson(json: String): JsonValue {
    return JsonReader().parse(json)
}

fun JsonValue.toStringMap(): Map<String, String> {
    check(isObject)
    val map = mutableMapOf<String, String>()
    forEach {
        if (it.isString) {
            map[it.name()] = it.asString()
        }
    }
    return map
}

private fun normalizedProxyType(): Proxy.Type =
    when (resourceProxyType.trim().uppercase()) {
        "SOCKS", "SOCKS5" -> Proxy.Type.SOCKS
        else -> Proxy.Type.HTTP
    }

private fun resourceProxy(): Proxy =
    Proxy(normalizedProxyType(), InetSocketAddress(resourceProxyHost, resourceProxyPort))

private fun httpGetBlocking(url: String): ByteArray {
    val urlObj = URI(url).toURL()
    val rawConnection = if (resourceProxyEnabled) urlObj.openConnection(resourceProxy()) else urlObj.openConnection()
    val connection = (rawConnection as HttpURLConnection).apply {
        connectTimeout = httpConnectTimeoutMillis
        readTimeout = httpReadTimeoutMillis
        requestMethod = "GET"
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SA4Mindustry-MDT/MapFetcher")
        setRequestProperty("Accept", "*/*")
    }
    return try {
        val code = connection.responseCode
        if (code !in 200..299) {
            val error = runCatching {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty().take(200)
            throw IOException("HTTP $code ${connection.responseMessage.orEmpty()} ${error}".trim())
        }
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

suspend fun httpGet(url: String, retry: Int = 3) = withContext(Dispatchers.IO) {
    var last: Throwable? = null
    repeat(retry + 1) { attempt ->
        try {
            @Suppress("BlockingMethodInNonBlockingContext")
            return@withContext runInterruptible { httpGetBlocking(url) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            last = e
            if (attempt < retry) delay(httpRetryDelayMillis)
        }
    }
    throw last ?: IllegalStateException("HTTP request failed: $url")
}

private fun resourceProxyStatusText(): String =
    if (resourceProxyEnabled) {
        "[green]已启用[] ${normalizedProxyType().name.lowercase()}://$resourceProxyHost:$resourceProxyPort"
    } else {
        "[yellow]未启用[]（当前端口配置：${resourceProxyType.uppercase()} $resourceProxyHost:$resourceProxyPort）"
    }

private fun setResourceProxyPort(port: Int, type: String? = null) {
    require(port in 1..65535) { "端口必须在 1..65535" }
    resourceProxyPort = port
    if (!type.isNullOrBlank()) {
        val normalized = when (type.trim().lowercase()) {
            "http", "https" -> "HTTP"
            "socks", "socks5" -> "SOCKS"
            else -> throw IllegalArgumentException("代理类型只能是 HTTP 或 SOCKS")
        }
        resourceProxyType = normalized
    }
}

MapRegistry.register(this, object : MapProvider() {
    val searchCache = CacheBuilder.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .build<String, List<MapInfo>>()!!

    override fun toString(): String = "ResourceSite"

    override suspend fun searchMaps(search: String?): Collection<MapInfo> {
        val provider = this
        val mappedSearch = when (search) {
            "all", "display", "site", null -> ""
            "pvp", "attack", "survive" -> "@mode:${Strings.capitalize(search)}"
            else -> search
        }
        searchCache.getIfPresent(mappedSearch)?.let { return it }
        try {
            @Suppress("BlockingMethodInNonBlockingContext")
            val data =
                httpGet("$webRoot/maps/list?prePage=100&search=${URLEncoder.encode(mappedSearch, "utf-8")}", retry = 2)
            val maps = parseJson(data.toString(Charsets.UTF_8))
                .map { info ->
                    val id = info.getInt("id", -1)
                    val mode = info.getString("mode", "unknown")
                    MapInfo(
                        provider, id,
                        Gamemode.all.find { it.name.equals(mode, ignoreCase = true) } ?: Gamemode.survival,
                        meta = info.toStringMap().apply {
                            (this as MutableMap).put("description", remove("desc").orEmpty())
                        }
                    )
                }
            searchCache.put(mappedSearch, maps)
            return maps
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Fail to searchMap($search)", e)
            return emptyList()
        }
    }

    override suspend fun findById(id: Int, reply: ((VarString) -> Unit)?): MapInfo? {
        if (id !in 10000..99999) return null
        try {
            val info = httpGet("$webRoot/maps/$id.json", retry = 2)
                .let { parseJson(it.toString(Charsets.UTF_8)) }
            val mode = info.getString("mode", "unknown")
            return MapInfo(
                this, id,
                Gamemode.all.find { it.name.equals(mode, ignoreCase = true) } ?: Gamemode.survival,
                meta = info.get("tags").toStringMap(),
            )
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Fail to findById($id)", e)
            return null
        }
    }

    override suspend fun lazyGetMap(info: MapInfo): mindustry.maps.Map {
        val bs = httpGet("$webRoot/maps/${info.id}.msav", retry = 4)
        val fi = object : Fi("BYTES.msav") {
            override fun read(): InputStream {
                return ByteArrayInputStream(bs)
            }
        }
        return MapIO.createMap(fi, true)
    }
})

command("resourceproxy", "管理指令：设置资源站本机代理") {
    usage = "status|on|off|set <端口> [http|socks]|host <地址>|test"
    aliases = listOf("mapproxy", "资源站代理", "地图站代理")
    permission = "wayzer.map.resourceProxy"
    body {
        when (arg.getOrNull(0)?.lowercase()) {
            null, "status", "状态" -> {
                reply(("[cyan]资源站地址：[]$webRoot\n[cyan]代理状态：[]" + resourceProxyStatusText()).with())
            }

            "on", "enable", "开启", "启用" -> {
                resourceProxyEnabled = true
                reply(("[green]已启用资源站代理：[]" + resourceProxyStatusText()).with())
            }

            "off", "disable", "关闭", "禁用" -> {
                resourceProxyEnabled = false
                reply("[green]已关闭资源站代理，后续资源站请求将直连。".with())
            }

            "set", "port", "设置", "端口" -> {
                val port = arg.getOrNull(1)?.toIntOrNull()
                    ?: returnReply("[red]请输入端口，例如：/resourceproxy set 7890 或 /resourceproxy set 10808 socks".with())
                try {
                    setResourceProxyPort(port, arg.getOrNull(2))
                    resourceProxyEnabled = true
                    reply(("[green]已设置并启用资源站代理：[]" + resourceProxyStatusText()).with())
                } catch (e: Exception) {
                    reply("[red]设置失败：${e.message}".with())
                }
            }

            "host", "地址" -> {
                val host = arg.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() }
                    ?: returnReply("[red]请输入代理地址，例如：/resourceproxy host 127.0.0.1".with())
                resourceProxyHost = host
                reply(("[green]已设置资源站代理地址：[]" + resourceProxyStatusText()).with())
            }

            "test", "测试" -> {
                try {
                    val bytes = httpGet("$webRoot/maps/list?prePage=1&search=", retry = 0)
                    reply(("[green]资源站连接测试成功：[]${bytes.size} bytes\n[cyan]代理状态：[]" + resourceProxyStatusText()).with())
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Resource site proxy test failed", e)
                    reply("[red]资源站连接测试失败：${e.message}\n[cyan]代理状态：${resourceProxyStatusText()}".with())
                }
            }

            else -> {
                reply(
                    """
                    |[cyan]/resourceproxy status[] 查看状态
                    |[cyan]/resourceproxy on/off[] 启用/关闭代理
                    |[cyan]/resourceproxy set 7890 [http|socks][] 设置本机代理端口并启用
                    |[cyan]/resourceproxy host 127.0.0.1[] 设置代理地址
                    |[cyan]/resourceproxy test[] 测试资源站连接
                    |[gray]说明：插件默认仍直连 $webRoot；启用后仅资源站请求走该本机代理。网页入口 https://www.mindustry.top/map 是给浏览器用的，换图接口走 $webRoot。
                    """.trimMargin().with()
                )
            }
        }
    }
}

PermissionApi.registerDefault("wayzer.map.resourceProxy", group = "@admin")
