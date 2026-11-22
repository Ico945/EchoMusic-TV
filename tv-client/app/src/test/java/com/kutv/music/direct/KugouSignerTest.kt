package com.kutv.music.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class KugouSignerTest {
    @Test
    fun `android signature matches TypeScript oracle`() {
        assertEquals(
            "71e9c37236f122fe58a62aca2bcf67bc",
            KugouSigner.signatureAndroid(
                linkedMapOf("b" to "1", "a" to "2"),
                "body-data",
            ),
        )
    }

    @Test
    fun `web and register signatures match TypeScript oracle`() {
        val query = linkedMapOf("b" to "1", "a" to "2")
        assertEquals("2b903f2ffc0f48291cb4f60133a92c6c", KugouSigner.signatureWeb(query))
        assertEquals("3be0f2ebde7da28161927749ab76ba88", KugouSigner.signatureRegister(query))
    }

    @Test
    fun `query values follow JavaScript JSON serialization contract`() {
        assertEquals(
            "a3d6e578ce8180485d1e09a0bac5905c",
            KugouSigner.signatureAndroid(mapOf("list" to listOf("x"))),
        )
        assertEquals("null", KugouSigner.stringifyQueryValue(null))
    }

    @Test
    fun `playback and parameter keys match TypeScript oracle`() {
        assertEquals(
            "b80afa112972aea0f99ef0f3f3fd6fef",
            KugouSigner.signKey("ABCDEF", "123456789", "42"),
        )
        assertEquals(
            "e84cd8fadc11f5830266100b6bcc2c27",
            KugouSigner.signParamsKey(12345),
        )
    }

    @Test
    fun `sign appends only a signature and leaves caller query immutable`() {
        val query = linkedMapOf<String, Any?>("a" to "2")
        val signed = KugouSigner.sign(
            KugouRequest.get(
                baseUrl = "https://gateway.kugou.com",
                path = "/endpoint",
                query = query,
            ),
        )

        assertEquals(mapOf("a" to "2"), query)
        assertEquals("2", signed.query["a"])
        assertEquals(32, signed.query["signature"].toString().length)
    }
}
