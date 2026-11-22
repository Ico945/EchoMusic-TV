package com.kutv.music

import com.kutv.music.direct.KugouDeviceStore
import com.kutv.music.direct.MemoryDirectKeyValueStore
import com.kutv.music.direct.KugouQrState
import com.kutv.music.direct.mapQrPayload
import com.kutv.music.direct.parseKugouCredentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android's JSONObject implementation must not turn JSON null into a real DFID. */
class KugouDevicePersistenceTest {
    @Test fun persistedCredentialParserRejectsNullWithoutCoercingTypes() {
        listOf<Any>(JSONObject.NULL, "null", false, 42).forEach { token ->
            val data = JSONObject().put("userid", "42").put("token", token)
            assertNull(parseKugouCredentials(data))
        }
        val data = JSONObject().put("userid", "42").put("token", "TEST-TOKEN").put("vip_token", JSONObject.NULL)
        assertEquals("TEST-TOKEN", parseKugouCredentials(data)?.token)
        assertNull(parseKugouCredentials(data)?.vipToken)
    }

    @Test fun nullQrCredentialsNeverConfirmAuthentication() {
        listOf(
            """{"status":4,"userid":42,"token":null}""",
            """{"status":4,"userid":null,"token":"TEST-TOKEN"}""",
            """{"status":4,"userid":42,"token":false}""",
        ).forEach { raw ->
            assertEquals(KugouQrState.Waiting, mapQrPayload(JSONObject(raw)))
        }
    }

    @Test fun absentVipTokenRemainsNullInConfirmedQr() {
        val result = mapQrPayload(JSONObject("""{"status":4,"userid":42,"token":"TEST-TOKEN","vip_token":null}"""))
        assertNull((result as KugouQrState.Confirmed).credentials.vipToken)
    }

    @Test fun absentDfidStaysAbsentAfterAndroidJsonRoundTrip() {
        val persistence = MemoryDirectKeyValueStore()
        val store = KugouDeviceStore(persistence) { "a".repeat(64) }
        val first = store.getOrCreate()
        val restored = KugouDeviceStore(persistence).getOrCreate()
        assertNull(restored.dfid)
        assertEquals(first, restored)
        store.updateDfid("REGISTERED")
        store.updateDfid(null)
        assertNull(KugouDeviceStore(persistence).getOrCreate().dfid)
    }
}
