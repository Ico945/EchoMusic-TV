package com.kutv.music.direct

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * `cryptoAesEncrypt` / `cryptoAesDecrypt` from the KuGou reference (`util/crypto.js`).
 *
 * Key derivation differs from [KugouCrypto.playlistEncrypt]: the temp key is a 16-char
 * random string, `key = md5(tempKey)` (32 hex chars) is used as UTF-8 bytes (32 bytes,
 * i.e. AES-256 in JCE terms), and `iv = key.last(16)`. Output is lower-case hex.
 */
data class AesCiphertext(val key: String, val str: String)

object KugouCryptoExtensions {
    private const val RANDOM_CHARSET = "1234567890abcdefghijklmnopqrstuvwxyz"

    fun randomString(length: Int, random: SecureRandom = SecureRandom()): String =
        buildString(length) {
            repeat(length) { append(RANDOM_CHARSET[random.nextInt(RANDOM_CHARSET.length)]) }
        }

    fun cryptoAesEncrypt(
        data: Any,
        optKey: String? = null,
        random: SecureRandom = SecureRandom(),
    ): AesCiphertext {
        val plain = when (data) {
            is JSONObject, is JSONArray -> data.toString()
            else -> data.toString()
        }
        val tempKey = (optKey ?: randomString(16, random)).lowercase()
        val key = KugouSigner.md5(tempKey)
        val iv = key.substring(key.length - 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return AesCiphertext(
            key = tempKey,
            str = cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).toHex(),
        )
    }

    fun cryptoAesDecrypt(hex: String, tempKey: String): Any {
        val key = KugouSigner.md5(tempKey)
        val iv = key.substring(key.length - 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val text = cipher.doFinal(hex.hexToBytes()).toString(Charsets.UTF_8)
        return runCatching { JSONObject(text) }.getOrElse {
            runCatching { JSONArray(text) }.getOrDefault(text)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex length" }
        return ByteArray(length / 2) { index ->
            ((Character.digit(get(index * 2), 16) shl 4) + Character.digit(get(index * 2 + 1), 16)).toByte()
        }
    }
}
