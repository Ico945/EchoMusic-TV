package com.kutv.music.direct

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PlaylistCiphertext(val key: String, val encoded: String)

object KugouCrypto {
    const val PUBLIC_RSA_KEY = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/g
bjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+
UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE
5E221wf/4WLFxwAtRQIDAQAB
-----END PUBLIC KEY-----"""

    const val LITE_PUBLIC_RSA_KEY = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO0
1rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXL
gFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08
/q6ifSioBszvb3QiwIDAQAB
-----END PUBLIC KEY-----"""

    private const val AES_KEY_CHARSET = "1234567890abcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    fun playlistEncrypt(data: Any, key: String? = null): PlaylistCiphertext {
        val plain = when (data) {
            is JSONObject, is JSONArray -> data.toString()
            else -> data.toString()
        }
        val actualKey = key ?: buildString(6) {
            repeat(6) { append(AES_KEY_CHARSET[random.nextInt(AES_KEY_CHARSET.length)]) }
        }
        require(actualKey.matches(Regex("[a-z0-9]{6}"))) { "Invalid playlist key" }
        val (keyBytes, ivBytes) = aesKeyAndIv(actualKey)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return PlaylistCiphertext(
            key = actualKey,
            encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8))),
        )
    }

    fun playlistDecrypt(value: PlaylistCiphertext): Any {
        require(value.key.length == 6) { "Invalid playlist key" }
        val (keyBytes, ivBytes) = aesKeyAndIv(value.key)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val text = cipher.doFinal(Base64.getDecoder().decode(value.encoded)).toString(Charsets.UTF_8)
        return runCatching { JSONObject(text) }.getOrElse {
            runCatching { JSONArray(text) }.getOrDefault(text)
        }
    }

    fun rsaEncryptPkcs1(data: String, pem: String = PUBLIC_RSA_KEY): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey(pem))
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun rsaRawEncrypt(data: String, pem: String = PUBLIC_RSA_KEY): String {
        val key = publicKey(pem)
        val keyLength = (key.modulus.bitLength() + 7) / 8
        val payload = data.toByteArray(Charsets.UTF_8)
        require(payload.size <= keyLength) { "Data length exceeds key size" }
        val padded = ByteArray(keyLength)
        payload.copyInto(padded)
        val message = BigInteger(1, padded)
        val encrypted = message.modPow(key.publicExponent, key.modulus)
        return encrypted.toString(16).padStart(keyLength * 2, '0')
    }

    fun deriveMid(hex32: String): String {
        require(hex32.matches(Regex("^[a-fA-F0-9]{32}$"))) { "mid must be 32 hex chars" }
        return BigInteger(hex32, 16).toString()
    }

    private fun aesKeyAndIv(key: String): Pair<ByteArray, ByteArray> {
        val digest = KugouSigner.md5(key)
        return digest.substring(0, 16).toByteArray(Charsets.UTF_8) to
            digest.substring(16, 32).toByteArray(Charsets.UTF_8)
    }

    private fun publicKey(pem: String): RSAPublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val encoded = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
