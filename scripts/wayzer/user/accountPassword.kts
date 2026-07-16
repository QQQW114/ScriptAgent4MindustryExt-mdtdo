package wayzer.user

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * MDT 账号密码工具。
 *
 * 只负责明文密码 <-> 安全哈希的转换与校验，不保存数据、不处理登录流程。
 * 存储格式：pbkdf2$迭代次数$saltBase64$hashBase64
 */

private val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
private val HASH_PREFIX = "pbkdf2"
private val HASH_SEPARATOR = "\$"
private val DEFAULT_ITERATIONS = 120_000
private val SALT_BYTES = 16
private val KEY_BITS = 256
private val secureRandom = SecureRandom()
private val base64Encoder = Base64.getEncoder()
private val base64Decoder = Base64.getDecoder()

private fun derivePassword(plain: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(plain.toCharArray(), salt, iterations, KEY_BITS)
    return try {
        SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

fun hashPassword(plain: String): String {
    val salt = ByteArray(SALT_BYTES)
    secureRandom.nextBytes(salt)
    val hash = derivePassword(plain, salt, DEFAULT_ITERATIONS)
    return listOf(
        HASH_PREFIX,
        DEFAULT_ITERATIONS.toString(),
        base64Encoder.encodeToString(salt),
        base64Encoder.encodeToString(hash),
    ).joinToString(HASH_SEPARATOR)
}

fun verifyPassword(plain: String, encoded: String): Boolean {
    return runCatching {
        val parts = encoded.split(HASH_SEPARATOR)
        if (parts.size != 4 || parts[0] != HASH_PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = base64Decoder.decode(parts[2])
        val expected = base64Decoder.decode(parts[3])
        val actual = derivePassword(plain, salt, iterations)
        MessageDigest.isEqual(expected, actual)
    }.getOrDefault(false)
}

fun looksLikeMdtPasswordHash(encoded: String): Boolean = encoded.startsWith(HASH_PREFIX + HASH_SEPARATOR)

