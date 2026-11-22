package com.kutv.music.direct

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDeviceRegistrarTest {
    @Test
    fun `rejects failed responses and invalid dfid without persisting registration`() = runBlocking {
        listOf(
            """{"status":0,"data":{"dfid":"UNTRUSTED"}}""",
            """{"status":1,"data":{"dfid":null}}""",
            """{"status":1,"data":{"dfid":"null"}}""",
            """{"status":1,"data":{"dfid":123}}""",
            """{"status":1,"error_code":7,"data":{"dfid":"UNTRUSTED"}}""",
        ).forEach { json ->
            val store = KugouDeviceStore(MemoryDirectKeyValueStore())
            val encoded = KugouCrypto.playlistEncrypt(JSONObject(json), "abc123")
            val registrar = KugouDeviceRegistrar(store, FakeRawExecutor(KugouRawResponse(200, "",
                bodyBytes = Base64.getDecoder().decode(encoded.encoded))), { "abc123" })
            val failure = runCatching { registrar.ensureRegistered() }.exceptionOrNull()
            assertTrue(failure is KugouNetworkException && failure.kind == KugouNetworkErrorKind.DATA)
            assertEquals(null, store.getOrCreate().dfid)
        }
    }

    @Test
    fun `concurrent registration callers share the successful device result`() = runBlocking {
        val store = KugouDeviceStore(MemoryDirectKeyValueStore())
        val encoded = KugouCrypto.playlistEncrypt(JSONObject("""{"status":1,"data":{"dfid":"VALID"}}"""), "abc123")
        var requests = 0
        val registrar = KugouDeviceRegistrar(store, KugouRawRequestExecutor {
            requests++
            delay(20)
            KugouRawResponse(200, "", bodyBytes = Base64.getDecoder().decode(encoded.encoded))
        }, { "abc123" })
        val results = List(8) { async { registrar.ensureRegistered() } }.awaitAll()
        assertEquals(1, requests)
        assertTrue(results.all { it.dfid == "VALID" })
    }

    @Test
    fun `registers with lite rsa android signature and persists decrypted dfid`() = runBlocking {
        val persistence = MemoryDirectKeyValueStore()
        val deviceStore = KugouDeviceStore(persistence) { "a".repeat(64) }
        val encryptedResponse = KugouCrypto.playlistEncrypt(
            JSONObject("""{"status":1,"data":{"dfid":"REAL-DFID"}}"""),
            key = "abc123",
        )
        val executor = FakeRawExecutor(
            KugouRawResponse(
                statusCode = 200,
                body = "",
                bodyBytes = Base64.getDecoder().decode(encryptedResponse.encoded),
            ),
        )
        val registrar = KugouDeviceRegistrar(
            deviceStore = deviceStore,
            executor = executor,
            aesKeyProvider = { "abc123" },
        )

        val registered = registrar.ensureRegistered()

        assertEquals("REAL-DFID", registered.dfid)
        assertEquals("REAL-DFID", deviceStore.getOrCreate().dfid)
        val request = executor.requests.single()
        assertEquals("https://userservice.kugou.com", request.baseUrl)
        assertEquals("/risk/v2/r_register_dev", request.path)
        assertEquals(KugouSignMode.ANDROID, request.signMode)
        assertEquals("application/octet-stream", request.headers["content-type"])
        assertTrue(request.query["p"].toString().matches(Regex("[A-F0-9]{256}")))
    }

    @Test
    fun `existing dfid skips registration`() = runBlocking {
        val store = KugouDeviceStore(MemoryDirectKeyValueStore()) { "b".repeat(64) }
        store.getOrCreate()
        store.updateDfid("EXISTING")
        val executor = FakeRawExecutor(KugouRawResponse(500, ""))

        assertEquals("EXISTING", KugouDeviceRegistrar(store, executor).ensureRegistered().dfid)
        assertTrue(executor.requests.isEmpty())
    }

    private class FakeRawExecutor(
        private val response: KugouRawResponse,
    ) : KugouRawRequestExecutor {
        val requests = mutableListOf<KugouRequest>()
        override suspend fun executeRaw(request: KugouRequest): KugouRawResponse {
            requests += request
            return response
        }
    }
}
