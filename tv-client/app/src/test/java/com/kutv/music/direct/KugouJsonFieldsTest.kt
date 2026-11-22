package com.kutv.music.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouJsonFieldsTest {
    @Test fun `credentials reject null markers and wrong types`() {
        listOf<Any>(JSONObject.NULL, "null", " NULL ", false, 42).forEach { invalid ->
            assertNull(parseKugouCredentials(JSONObject().put("userid", "42").put("token", invalid)))
        }
        assertNull(parseKugouCredentials(JSONObject().put("userid", JSONObject.NULL).put("token", "TEST")))
    }

    @Test fun `numeric user ids are accepted only at the upstream QR boundary`() {
        val data = JSONObject().put("userid", 42).put("token", "TEST").put("vip_token", JSONObject.NULL)
        assertNull(parseKugouCredentials(data))
        assertEquals("42", parseKugouCredentials(data, true)?.userId)
        assertNull(parseKugouCredentials(data, true)?.vipToken)
    }
}
