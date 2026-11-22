package com.kutv.music.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouCryptoTest {
    @Test
    fun `raw rsa is fixed length and matches TypeScript oracle`() {
        val encrypted = KugouCrypto.rsaRawEncrypt("""{"token":"","clienttime":1}""")

        assertEquals(256, encrypted.length)
        assertEquals(
            "495dbebc341ca78be013a1f2977b89cbd676144f18bcdc1f47c2b615e4625063" +
                "9e96e35683e4b71d6e10827dbbd2ddd7ea1829924e6b2bf72b8a16eb16825dde" +
                "55e0ca21febe28d0c73dc57459d59d4cb3a699524908073959204be22d3408b3" +
                "a64a1d4f71a444ba2e85c46286deb4eb7ab67641f6229307017e73489530a354",
            encrypted,
        )
    }

    @Test
    fun `playlist aes round trips json and uses random key`() {
        val first = KugouCrypto.playlistEncrypt(JSONObject("""{"a":1}"""))
        val second = KugouCrypto.playlistEncrypt(JSONObject("""{"a":1}"""))

        assertTrue(first.key.matches(Regex("[a-z0-9]{6}")))
        assertEquals(1, (KugouCrypto.playlistDecrypt(first) as JSONObject).getInt("a"))
        assertNotEquals(first, second)
    }

    @Test
    fun `pkcs1 output and derived mid have protocol lengths`() {
        assertEquals(256, KugouCrypto.rsaEncryptPkcs1("hello").length)
        assertEquals(
            "226854911280625642308916404954512140970",
            KugouCrypto.deriveMid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
    }

    @Test
    fun `invalid mid is rejected`() {
        val error = runCatching { KugouCrypto.deriveMid("zz") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
