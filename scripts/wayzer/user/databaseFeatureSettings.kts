@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.user

import wayzer.lib.DatabaseFeature
import wayzer.lib.DatabaseFeatureChangedEvent
import wayzer.lib.DatabaseFeatureSettings as DatabaseFeatureSettingsApi
import wayzer.lib.MdtStorage
import java.util.concurrent.ConcurrentHashMap

name = "数据库业务功能状态"

// 总开关不能只存到 MdtSettings：总开关关闭后仍必须能在不依赖业务表读写的前提下恢复。
// ScriptAgent config.key 会保存到脚本配置，运行中修改后可跨重启保留。
var databaseBusinessFeaturesEnabled by config.key(
    true,
    "数据库业务功能总开关；仅暂停可选玩家业务，账号/权限/封禁/风控/性能保护始终保留",
)

private fun settingKey(feature: DatabaseFeature): String =
    "serverFeatures.database.${feature.code}.enabled"

private class DatabaseFeatureSettingsService(
    private val loadMasterSetting: () -> Boolean,
    private val saveMasterSetting: (Boolean) -> Unit,
    private val emitChanges: (List<DatabaseFeatureChangedEvent>) -> Unit,
) : DatabaseFeatureSettingsApi {
    @Volatile private var master = loadMasterSetting()
    private val configured = ConcurrentHashMap<DatabaseFeature, Boolean>().apply {
        DatabaseFeature.entries.forEach { put(it, true) }
    }

    fun loadFromDatabase() {
        master = loadMasterSetting()
        val keys = DatabaseFeature.entries.associateWith(::settingKey)
        val values = MdtStorage.getSettings(keys.values)
        val normalized = linkedMapOf<String, String?>()
        keys.forEach { (feature, key) ->
            val enabled = values[key]?.toBooleanStrictOrNull() ?: true
            configured[feature] = enabled
            if (values[key] != enabled.toString()) normalized[key] = enabled.toString()
        }
        if (normalized.isNotEmpty()) MdtStorage.setSettings(normalized)
    }

    override fun masterEnabled(): Boolean = master

    override fun configuredEnabled(feature: DatabaseFeature): Boolean = configured[feature] ?: true

    private fun effectiveSnapshot(): Map<DatabaseFeature, Boolean> =
        DatabaseFeature.entries.associateWith(::enabled)

    private fun emitEffectiveChanges(before: Map<DatabaseFeature, Boolean>, changedAtMillis: Long) {
        val changes = DatabaseFeature.entries.mapNotNull { feature ->
            val old = before[feature] ?: true
            val new = enabled(feature)
            if (old == new) null else DatabaseFeatureChangedEvent(feature, old, new, changedAtMillis)
        }
        if (changes.isNotEmpty()) emitChanges(changes)
    }

    override fun setMasterEnabled(enabled: Boolean): Boolean {
        if (master == enabled) return master
        val before = effectiveSnapshot()
        val changedAt = System.currentTimeMillis()
        saveMasterSetting(enabled)
        master = enabled
        emitEffectiveChanges(before, changedAt)
        return master
    }

    override fun setConfiguredEnabled(feature: DatabaseFeature, enabled: Boolean): Boolean {
        if (configuredEnabled(feature) == enabled) return enabled
        val before = effectiveSnapshot()
        val changedAt = System.currentTimeMillis()
        MdtStorage.setSetting(settingKey(feature), enabled.toString())
        configured[feature] = enabled
        emitEffectiveChanges(before, changedAt)
        return enabled
    }

    private fun stateText(feature: DatabaseFeature): String {
        val configuredEnabled = configuredEnabled(feature)
        return when {
            enabled(feature) -> "[green]开启"
            !master && configuredEnabled -> "[gray]总开关暂停[light_gray]（子项原为开启）"
            else -> "[red]关闭"
        }
    }

    override fun statusText(): String = buildString {
        append("[cyan]数据库业务总开关：[white]")
        append(if (master) "[green]开启" else "[red]关闭")
        DatabaseFeature.entries.forEach { feature ->
            append("\n[cyan]")
            append(feature.displayName)
            append("：[white]")
            append(stateText(feature))
        }
        append("\n[gray]账号、信任权限、封禁、禁言、IP风控与性能保护不受此总开关影响。")
    }
}

private val databaseFeatureSettings = DatabaseFeatureSettingsService(
    loadMasterSetting = { databaseBusinessFeaturesEnabled },
    saveMasterSetting = { databaseBusinessFeaturesEnabled = it },
    emitChanges = { changes ->
        launch { changes.forEach { it.emitAsync() } }
    },
)

onEnable {
    runCatching { databaseFeatureSettings.loadFromDatabase() }
        .onFailure { logger.warning("数据库业务功能设置读取失败，暂用默认开启状态：${it.message}") }
    DatabaseFeatureSettingsApi.provide(this, databaseFeatureSettings)
    logger.info("数据库业务功能设置已加载：${databaseFeatureSettings.statusText().replace('\n', ' ')}")
}
