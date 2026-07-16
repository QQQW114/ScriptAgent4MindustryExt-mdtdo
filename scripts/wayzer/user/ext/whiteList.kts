@file:Depends("wayzer/user/accountAuth", "MDT账号注册登录")
@file:Depends("wayzer/user/accountGuestControl", "未登录玩家观战投票")

package wayzer.user.ext

/**
 * 旧统一登录/白名单脚本兼容入口。
 *
 * 原脚本依赖 mindustry.top 外部统一登录与 RPC/KV 缓存；本项目已替换为 MDT 自建 QQ+密码账号系统：
 * - 注册/登录/修改密码：wayzer/user/accountAuth.kts
 * - 今日未登录玩家强制观战投票：wayzer/user/accountGuestControl.kts
 *
 * 保留本文件是为了避免旧配置或脚本列表引用 `wayzer/user/ext/whiteList` 时加载失败；
 * 不再在这里注册 `/login`，避免与新账号系统冲突。
 */

name = "旧白名单兼容入口（已替换为MDT账号系统）"
