package com.kutv.music.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouProtocolFixtureTest {
    @Test
    fun `confirmed qr requires userid and token`() {
        assertEquals(
            KugouQrState.Waiting,
            mapQrPayload(JSONObject("""{"status":4,"userid":"42"}""")),
        )

        val confirmed = mapQrPayload(
            JSONObject("""{"status":4,"userid":"42","token":"t","vip_type":1}"""),
        )

        assertTrue(confirmed is KugouQrState.Confirmed)
        confirmed as KugouQrState.Confirmed
        assertEquals("42", confirmed.credentials.userId)
        assertEquals("t", confirmed.credentials.token)
        assertEquals(1, confirmed.credentials.vipType)
    }

    @Test
    fun `fixture covers all documented qr states and unknown fails closed`() {
        val text = checkNotNull(javaClass.getResource("/kugou/protocol-fixtures.json"))
            .readText()
        val fixtures = JSONObject(text).getJSONArray("qr")

        val actual = buildList {
            repeat(fixtures.length()) { index ->
                add(mapQrPayload(fixtures.getJSONObject(index).getJSONObject("payload")))
            }
        }

        assertEquals(KugouQrState.Expired, actual[0])
        assertEquals(KugouQrState.Waiting, actual[1])
        assertEquals(KugouQrState.Scanned, actual[2])
        assertEquals(KugouQrState.Waiting, actual[3])
        assertTrue(actual[4] is KugouQrState.Confirmed)
        assertEquals(KugouQrState.Waiting, actual[5])
    }

    @Test
    fun `nested data payload is accepted without trusting top-level status`() {
        val state = mapQrPayload(
            JSONObject(
                """{"status":1,"data":{"status":4,"userid":"7","token":"nested"}}""",
            ),
        )

        assertTrue(state is KugouQrState.Confirmed)
        assertEquals("7", (state as KugouQrState.Confirmed).credentials.userId)
    }
}
