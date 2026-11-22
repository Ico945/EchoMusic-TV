package com.kutv.music.direct

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDirectApiPlaybackTest {
    @Test fun `HiRes capability and stream query translate the upstream high alias`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val api = KugouDirectApi(executor = KugouRequestExecutor { request ->
            requests += request
            KugouResponse(200, if (request.path.contains("privilege")) JSONObject("""{"data":[{"hash":"BASE","status":1,"relate_goods":[{"quality":"128","hash":"BASE"},{"quality":"high","hash":"HIGH"}]}]}""")
                else JSONObject("""{"url":"https://webfs.kugou.com/fixture.flac"}"""))
        }, credentialProvider = { KugouCredentials("42", "fixture-token") },
            deviceProvider = { KugouDeviceIdentity("a".repeat(32), "b".repeat(32), "uuid", "dfid") })
        val privilege = api.getPrivilege("BASE")
        assertEquals(listOf("128", "hires"), privilege.qualities)
        assertEquals("HIGH", privilege.qualityHashes["hires"])
        val stream = api.getSongUrl("HIGH", "hires")
        assertEquals("high", requests.last().query["quality"])
        assertEquals("hires", stream.quality); assertEquals("audio/flac", stream.mimeType)
    }
    @Test fun `logged out playback still fails before sending any network request`() = runBlocking {
        var calls = 0
        val api = KugouDirectApi(
            executor = KugouRequestExecutor { calls++; error("No request allowed") },
            credentialProvider = { null },
        )
        val privilegeFailure = runCatching { api.getPrivilege("HASH") }.exceptionOrNull()
        val urlFailure = runCatching { api.getSongUrl("HASH", "128") }.exceptionOrNull()
        assertTrue(privilegeFailure is com.kutv.music.data.DirectLoginRequiredException)
        assertTrue(urlFailure is com.kutv.music.data.DirectLoginRequiredException)
        assertEquals(0, calls)
    }

    @Test fun `invalid primary does not hide a permitted backup`() = runBlocking {
        val stream = apiFor("""{"url":["https://untrusted.example/song.mp3"],"backupUrl":["https://webfs.kugou.com/backup.mp3"]}""")
            .getSongUrl("ABCDEF", "128")
        assertEquals("https://webfs.kugou.com/backup.mp3", stream.url)
    }

    @Test fun `invalid first array candidate does not hide a later valid address`() = runBlocking {
        val stream = apiFor("""{"data":{"url":["null","https://kugou.com.evil.example/song.mp3","http://webfs.kugou.com/song.mp3"]}}""")
            .getSongUrl("ABCDEF", "128")
        assertEquals("http://webfs.kugou.com/song.mp3", stream.url)
    }

    @Test fun `invalid data candidate does not hide a top level backup`() = runBlocking {
        val stream = apiFor("""{"data":{"url":"javascript:alert(1)"},"backup_url":"https://webfs.kugou.com/song.mp3"}""")
            .getSongUrl("ABCDEF", "128")
        assertEquals("https://webfs.kugou.com/song.mp3", stream.url)
    }

    @Test fun `valid primary retains precedence over backups`() = runBlocking {
        val stream = apiFor("""{"data":{"url":" https://webfs.kugou.com/first.mp3 ","backupUrl":"https://webfs.kugou.com/second.mp3"},"url":"https://webfs.kugou.com/third.mp3"}""")
            .getSongUrl("ABCDEF", "128")
        assertEquals("https://webfs.kugou.com/first.mp3", stream.url)
    }

    @Test fun `no valid candidate fails closed without treating metadata as audio`() = runBlocking {
        val failure = runCatching {
            apiFor("""{"url":"file:///etc/passwd","backupUrl":["https://webfs.kugou.com:443/song.mp3","https://user@webfs.kugou.com/song.mp3","http://cdn.kgimg.com/song.mp3"],"metadata":{"url":"https://webfs.kugou.com/not-a-source.mp3"}}""")
                .getSongUrl("ABCDEF", "128")
        }.exceptionOrNull()
        assertTrue(failure is KugouNetworkException)
        assertEquals(KugouNetworkErrorKind.DATA, (failure as KugouNetworkException).kind)
    }

    private fun apiFor(body: String) = KugouDirectApi(
        executor = KugouRequestExecutor { KugouResponse(200, JSONObject(body)) },
        credentialProvider = { KugouCredentials(userId = "42", token = "token") },
        deviceProvider = { KugouDeviceIdentity("a".repeat(32), "b".repeat(32), "uuid", "dfid") },
    )

    @Test
    fun `song url accepts EchoMusic top level url array response`() = runBlocking {
        val device = KugouDeviceIdentity(
            guid = "a".repeat(32),
            mid = "b".repeat(32),
            uuid = "uuid",
            dfid = "dfid",
        )
        val api = KugouDirectApi(
            executor = KugouRequestExecutor {
                KugouResponse(
                    statusCode = 200,
                    body = JSONObject(
                        """{
                            "status":1,
                            "url":["http://webfs.kugou.com/audio.mp3"],
                            "backupUrl":["https://backup.kugou.com/audio.mp3"]
                        }""".trimIndent(),
                    ),
                )
            },
            credentialProvider = { KugouCredentials(userId = "42", token = "token") },
            deviceProvider = { device },
        )

        val stream = api.getSongUrl("ABCDEF", "128")

        assertEquals("http://webfs.kugou.com/audio.mp3", stream.url)
    }

    @Test
    fun `privilege exposes the per quality hashes from relate goods`() = runBlocking {
        val api = KugouDirectApi(
            executor = KugouRequestExecutor {
                KugouResponse(
                    statusCode = 200,
                    body = JSONObject(
                        """{
                            "status":1,
                            "error_code":0,
                            "data":[{
                                "hash":"BASE_HASH",
                                "status":1,
                                "relate_goods":[
                                    {"hash":"HASH_128","quality":"128"},
                                    {"hash":"HASH_320","quality":"320"},
                                    {"hash":"HASH_FLAC","quality":"flac"}
                                ]
                            }]
                        }""".trimIndent(),
                    ),
                )
            },
            credentialProvider = { KugouCredentials(userId = "42", token = "token") },
        )

        val privilege = api.getPrivilege("BASE_HASH")

        assertEquals(true, privilege.playable)
        assertEquals(listOf("128", "320", "flac"), privilege.qualities)
        assertEquals("HASH_128", privilege.qualityHashes["128"])
        assertEquals("HASH_320", privilege.qualityHashes["320"])
        assertEquals("HASH_FLAC", privilege.qualityHashes["flac"])
    }

    @Test
    fun `song url key is signed with the same lowercase hash sent upstream`() = runBlocking {
        var captured: KugouRequest? = null
        val device = KugouDeviceIdentity(
            guid = "a".repeat(32),
            mid = "b".repeat(32),
            uuid = "uuid",
            dfid = "dfid",
        )
        val api = KugouDirectApi(
            executor = KugouRequestExecutor { request ->
                captured = request
                KugouResponse(
                    statusCode = 200,
                    body = JSONObject(
                        """{"status":1,"error_code":0,"data":{"url":"https://webfs.kugou.com/song.mp3"}}""",
                    ),
                )
            },
            credentialProvider = { KugouCredentials(userId = "42", token = "token") },
            deviceProvider = { device },
        )

        api.getSongUrl("ABCDEF", "128")

        val request = checkNotNull(captured)
        assertEquals("abcdef", request.query["hash"])
        assertEquals(KugouSignMode.ANDROID, request.signMode)
        assertEquals(
            KugouSigner.signKey(
                hash = "abcdef",
                mid = KugouCrypto.deriveMid(device.mid),
                userId = "42",
            ),
            request.query["key"],
        )
    }
}
