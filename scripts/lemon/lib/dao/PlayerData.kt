package lemon.lib.dao

/**
 * Lightweight compatibility model for Lemon map scripts.
 * Only exposes PlayerData[uuid].profile so imported map scripts can keep their
 * gameplay logic while Lemon achievement rewards are ignored on MDT.
 */
data class Account(val id: String)

class PlayerData private constructor(val uuid: String) {
    val profile: Account? = Account(uuid)

    companion object {
        operator fun get(uuid: String): PlayerData = PlayerData(uuid)
    }
}
