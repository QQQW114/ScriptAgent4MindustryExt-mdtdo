package wayzer.lib

import coreLibrary.lib.util.ServiceRegistry

/**
 * 服务器级功能设置的跨脚本只读/管理接口。
 *
 * 设置由 wayzer/user/serverFeatureSettings.kts 持久化到 MdtSettings；业务脚本只通过
 * 此接口读取当前缓存，避免在菜单、技能和结算热路径中反复访问数据库。
 */
interface ServerFeatureSettings {
    fun mdcSettlementMultiplier(): Double
    fun forumEnabled(): Boolean
    fun registrationOnlineRequirementEnabled(): Boolean
    fun socialActionsEnabled(): Boolean
    /** 历史方法名；当前值同时是已绑定玩家的默认信任与资历等级下限。 */
    fun defaultBoundTrustLevelCode(): String

    fun setMdcSettlementMultiplier(value: Double): Double
    fun setForumEnabled(enabled: Boolean): Boolean
    fun setRegistrationOnlineRequirementEnabled(enabled: Boolean): Boolean
    fun setSocialActionsEnabled(enabled: Boolean): Boolean
    /** 历史方法名；设置时会同时批量处理信任与资历资料。 */
    fun setDefaultBoundTrustLevelCode(levelCode: String): MdtStorage.BoundTrustLevelUpgradeResult

    fun statusText(): String

    companion object : ServiceRegistry<ServerFeatureSettings>()
}
