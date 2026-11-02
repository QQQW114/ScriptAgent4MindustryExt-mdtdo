@file:Depends("coreMindustry/menu", "入服提醒弹窗")
@file:Depends("coreMindustry/utilTextInput", "输入框状态检测")
@file:Depends("wayzer/mdtDatabase", "MDT数据库")
@file:Depends("wayzer/user/accountAuth", "注册/登录菜单")
@file:Depends("wayzer/user/emailBind", "QQ邮箱绑定")

package wayzer.user

import arc.util.Log
import coreMindustry.MenuBuilder
import coreMindustry.MenuV3
import coreMindustry.UtilTextInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData

private val emailBind = contextScript<wayzer.user.EmailBind>()
private val accountAuth = contextScript<wayzer.user.AccountAuth>()
private val textInput = contextScript<UtilTextInput>()

private val promptDelaySeconds by config.key(10, "进入服务器后等待多久弹窗提醒（秒）")

private fun accountIdOf(player: Player): Int? =
    PlayerData[player].id.substringAfter("account:", "").toIntOrNull()

/** 玩家当前是否有任何弹窗/输入框（MenuV3、旧式 MenuBuilder、textInput）——有则不打扰。 */
private fun hasOpenPopup(player: Player): Boolean =
    MenuV3.isAnyMenuOpen(player) ||
        MenuBuilder.isAnyMenuOpen(player) ||
        with(textInput) { hasActiveTextInput(player) }

private fun dismissKey(key: String) {
    runCatching { MdtStorage.setSetting(key, "1") }
        .onFailure { Log.err("[emailOnboarding] 保存“不再提示”失败 key=$key: ${it.message}") }
}

private fun isDismissed(key: String): Boolean =
    runCatching { MdtStorage.getSetting(key) == "1" }.getOrDefault(false)

private suspend fun showBindPrompt(player: Player, accountId: Int) {
    Log.info("[emailOnboarding] 显示绑定邮箱提醒 account=$accountId player=${player.plainName()}")
    lateinit var menu: MenuV3
    runCatching {
        menu = MenuV3(player) {
            title = "QQ 邮箱绑定提醒"
            onCancel = { close() }
            msg = """
                |[cyan]你的账号还没有绑定 QQ 邮箱。
                |
                |[gray]未绑定期间[white]信任等级最高 2 级[]（仍可正常游玩，[white]资历等级不受影响[]）。
                |[green]绑定后恢复原信任等级，并奖励 [gold]1000 MDC[]。
                |[gray]也可以输入 [white]/bindemail[] 或查看 [white]/help[] 玩家指令。
            """.trimMargin()
            option("[green]立即绑定 QQ 邮箱") {
                menu.close()
                try {
                    emailBind.bindEmailFlow(player)
                } catch (t: Throwable) {
                    Log.err("[emailOnboarding] 绑定流程异常 account=$accountId: ${t.message}")
                    player.sendMessage("[red]绑定流程异常，请稍后重试或联系管理员。")
                }
            }
            newRow()
            option("[yellow]不再提示") {
                menu.close()
                dismissKey("email.prompt.bind.dismissed.$accountId")
                player.sendMessage("[gray]已关闭邮箱绑定提醒；绑定邮箱可随时输入 /bindemail。")
            }
            option("关闭") { menu.close() }
        }.send()
    }.onFailure {
        Log.err("[emailOnboarding] 打开绑定弹窗失败 account=$accountId: ${it.message}")
        // 兜底：弹窗不可用时用聊天提示（不阻塞游戏）
        player.sendMessage("[yellow]你的账号还没有绑定 QQ 邮箱（未绑定信任等级最高 2 级），输入 [white]/bindemail[] 可绑定并领取 1000 MDC。")
    }
}

private suspend fun showRegisterPrompt(player: Player) {
    Log.info("[emailOnboarding] 显示注册引导 player=${player.plainName()} uuid=${player.uuid()}")
    val key = "email.prompt.register.dismissed.${player.uuid()}"
    lateinit var menu: MenuV3
    runCatching {
        menu = MenuV3(player) {
            title = "MDT 账号注册引导"
            onCancel = { close() }
            msg = """
                |[cyan]你还没有注册 MDT 账号。
                |
                |[gray]注册后可解锁[white]完整服务器功能[]：等级提升、技能系统、MDC 经济、成就与更多玩法。
                |[gray]注册必须绑定 [white]QQ 邮箱（邮箱验证码）[]，否则无法完成注册。
                |[gray]基本流程：[white]/captcha[] 获取注册验证码 → [white]/register[] → 输入密码、QQ 邮箱、邮箱验证码。
                |[gray]已有账号可打开账号菜单后使用 [white]/login[]；也可随时输入 [white]/help[] 查看玩家指令。
            """.trimMargin()
            option("[green]注册 / 登录账号") {
                menu.close()
                try {
                    accountAuth.openAccountMenu(player)
                } catch (t: Throwable) {
                    Log.err("[emailOnboarding] 打开账号菜单异常 uuid=${player.uuid()}: ${t.message}")
                    player.sendMessage("[red]账号菜单打开失败，请直接输入 [white]/register[] 或 [white]/account[]。")
                }
            }
            newRow()
            option("[yellow]不再提示") {
                menu.close()
                dismissKey(key)
                player.sendMessage("[gray]已关闭注册提醒；随时可输入 /register 或 /account 注册/登录。")
            }
            option("关闭") { menu.close() }
        }.send()
    }.onFailure {
        Log.err("[emailOnboarding] 打开注册弹窗失败 uuid=${player.uuid()}: ${it.message}")
        player.sendMessage("[yellow]你还没有注册 MDT 账号：输入 [white]/register[] 注册（需 QQ 邮箱验证码），或 [white]/account[] 登录。")
    }
}

listen<EventType.PlayerJoin> { event ->
    val player = event.player
    launch(Dispatchers.game) {
        delay(promptDelaySeconds * 1000L)
        if (!Groups.player.contains(player)) return@launch
        try {
            if (hasOpenPopup(player)) return@launch
            val data = PlayerData[player]
            if (!data.authed) {
                val key = "email.prompt.register.dismissed.${player.uuid()}"
                if (!isDismissed(key)) showRegisterPrompt(player)
            } else {
                val accountId = accountIdOf(player) ?: return@launch
                if (with(emailBind) { isEmailBound(accountId) }) return@launch
                if (with(emailBind) { isStaffUid(data.id) }) return@launch
                val key = "email.prompt.bind.dismissed.$accountId"
                if (!isDismissed(key)) showBindPrompt(player, accountId)
            }
        } catch (t: Throwable) {
            Log.err("[emailOnboarding] 入服提醒异常: ${t.message}")
        }
    }
}

listen<EventType.PlayerLeave> { event ->
    // 玩家离开时清理 MenuV3 会话，避免 activeSessions/弹窗状态残留。
    MenuV3.closeAll(event.player)
}
