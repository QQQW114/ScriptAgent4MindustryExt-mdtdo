package lemon.user

import lemon.lib.dao.Account

/**
 * Lemon achievement compatibility shim for imported map scripts.
 * MDT has its own achievement/trust system, so legacy Lemon map achievements
 * are accepted as no-op instead of loading Lemon's full user database stack.
 */
fun finishAchievement(account: Account, name: String, exp: Int, broadcast: Boolean = false) {
    // no-op by design
}
export(::finishAchievement)
