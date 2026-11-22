package com.kutv.music

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.direct.*
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in diagnosis, not a registration success gate. Never logs identities/keys. */
class KugouRegistrationWireDiagnosticTest {
    @Test fun compareContentTypesOnTheSameSignedRegistrationRequest() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvRegistrationWireDiagnosis") == "true")
        AppGraph.initialize(instrumentation.targetContext)
        val identity = AppGraph.deviceStore.getOrCreate()
        val copy = JSONObject().put("version", 1).put("guid", identity.guid)
            .put("mid", identity.mid).put("uuid", identity.uuid).put("dfid", JSONObject.NULL)
        val store = KugouDeviceStore(MemoryDirectKeyValueStore(mutableMapOf("kugou.device.v1" to copy.toString())))
        val aesKey = "abc123" // Test-only cipher input; no account credentials used.
        var captured: KugouRawRequest? = null
        val captureClient = KugouHttpClient(store::getOrCreate, { null }, KugouTransport {
            captured = it
            KugouRawResponse(503, "") // Capture only: no network and no identity committed.
        })
        val captureFailure = runCatching { KugouDeviceRegistrar(store, captureClient) { aesKey }.ensureRegistered() }.exceptionOrNull()
        assertEquals(KugouNetworkErrorKind.HTTP, (captureFailure as? KugouNetworkException)?.kind)
        val original = checkNotNull(captured)
        assertNotNull(original.body)
        assertEquals(null, store.getOrCreate().dfid)
        val transport = OkHttpKugouTransport()
        val types = listOf("application/octet-stream", "application/x-www-form-urlencoded",
            "application/x-www-form-urlencoded", "application/octet-stream")
        types.forEachIndexed { index, contentType ->
            // Four bounded ABBA probes; same installation, signed query, body and key.
            // No IP/identity rotation, retries, account lookup or production persistence.
            val request = original.copy(headers = original.headers
                .filterKeys { !it.equals("content-type", true) } + ("content-type" to contentType))
            val result = runCatching {
                withTimeoutOrNull(15_000L) { transport.execute(request) }
                    ?: throw java.io.IOException("Diagnostic deadline")
            }.onFailure { if (it is CancellationException) throw it }
            val raw = result.getOrNull()
            val decoded = raw?.bodyBytes?.let { bytes -> runCatching {
                KugouCrypto.playlistDecrypt(PlaylistCiphertext(aesKey, Base64.getEncoder().encodeToString(bytes)))
            }.getOrNull() } as? JSONObject
            val data = decoded?.opt("data")
            val nested = data as? JSONObject
            fun validDfid(value: Any?) = value is String && value.isNotBlank() && !value.equals("null", true)
            val summary = JSONObject().put("probe", index + 1).put("contentType", contentType)
                .put("http", raw?.statusCode ?: -1).put("bytes", raw?.bodyBytes?.size ?: 0)
                .put("transportFailure", result.exceptionOrNull()?.javaClass?.simpleName ?: "none")
                .put("decryptedObject", decoded != null).put("status", decoded?.optInt("status", -1) ?: -1)
                .put("errorCode", decoded?.optInt("error_code", -1) ?: -1)
                .put("dataType", if (data == null || data === JSONObject.NULL) "null-or-missing" else data.javaClass.simpleName)
                .put("dataObjectKeys", nested?.length() ?: -1)
                .put("hasValidDfid", validDfid(decoded?.opt("dfid")) || validDfid(nested?.opt("dfid")))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "REGISTRATION_WIRE $summary\n") })
            if (index < types.lastIndex) delay(1_000)
        }
        assertEquals(null, store.getOrCreate().dfid)
        assertEquals(identity, AppGraph.deviceStore.getOrCreate())
    }
}
