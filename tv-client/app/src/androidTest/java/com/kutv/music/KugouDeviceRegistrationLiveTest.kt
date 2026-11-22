package com.kutv.music

import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.direct.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/** Isolates persistence but uses the installation's existing identity; never logs DFID or keys. */
class KugouDeviceRegistrationLiveTest {
    @Test fun registersExistingInstallationIdentity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugouRegistration") == "true")
        AppGraph.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = AppGraph.deviceStore.getOrCreate()
        val copy = JSONObject().put("version", 1).put("guid", identity.guid)
            .put("mid", identity.mid).put("uuid", identity.uuid).put("dfid", JSONObject.NULL)
        val store = KugouDeviceStore(MemoryDirectKeyValueStore(mutableMapOf("kugou.device.v1" to copy.toString())))
        val aesKey = "abc123" // Test-only deterministic cipher key, not an account credential.
        var diagnostic = "no request"
        val transport = OkHttpKugouTransport()
        val client = KugouHttpClient(store::getOrCreate, { null }, KugouTransport { request ->
            val raw = transport.execute(request)
            val bytes = raw.bodyBytes ?: byteArrayOf()
            val decoded = runCatching { KugouCrypto.playlistDecrypt(PlaylistCiphertext(aesKey,
                Base64.getEncoder().encodeToString(bytes))) }
            val json = decoded.getOrNull() as? JSONObject
            val data = json?.optJSONObject("data")
            val rootDfid = json?.opt("dfid")
            diagnostic = "http=${raw.statusCode}; bytes=${bytes.size}; cipherFailure=${decoded.exceptionOrNull()?.javaClass?.simpleName}; " +
                "status=${json?.optInt("status", -1)}; errorCode=${json?.optInt("error_code", 0)}; " +
                "hasData=${data != null}; dfidType=${data?.opt("dfid")?.javaClass?.simpleName}; " +
                "rootDfidType=${rootDfid?.javaClass?.simpleName}; rootDfidLength=${(rootDfid as? String)?.length}; " +
                "knownFields=${listOf("status", "error_code", "dfid", "data", "msg", "message").filter { json?.has(it) == true }}; " +
                "verificationHeader=${raw.headers.keys.any { it.equals("ssa-code", true) }}"
            raw
        })
        val result = runCatching { KugouDeviceRegistrar(store, client) { aesKey }.ensureRegistered() }
        assertTrue("Device registration failed: $diagnostic", result.getOrNull()?.dfid?.let { it.isNotBlank() && it != "null" } == true)
    }
}
