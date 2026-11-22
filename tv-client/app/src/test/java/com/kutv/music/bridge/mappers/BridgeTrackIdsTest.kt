package com.kutv.music.bridge.mappers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeTrackIdsTest {
    @Test
    fun `registry evicts oldest entries and lifecycle clear removes remaining mappings`() {
        BridgeTrackIds.clear()
        val publicIds = (0 until 1_100).map { BridgeTrackIds.publicId("native-$it") }

        assertThrows(IllegalArgumentException::class.java) { BridgeTrackIds.nativeId(publicIds.first()) }
        assertEquals("native-1099", BridgeTrackIds.nativeId(publicIds.last()))

        BridgeTrackIds.clear()
        assertThrows(IllegalArgumentException::class.java) { BridgeTrackIds.nativeId(publicIds.last()) }
    }

    @Test
    fun `raw hash becomes stable opaque public id and resolves only in native registry`() {
        val rawHash = "A1B2C3D4SECRET_HASH"

        val publicId = BridgeTrackIds.publicId(rawHash)

        assertTrue(publicId.startsWith("tv_"))
        assertFalse(publicId.contains(rawHash))
        assertEquals(publicId, BridgeTrackIds.publicId(rawHash))
        assertEquals(rawHash, BridgeTrackIds.nativeId(publicId))
    }

    @Test
    fun `home payload rewrites track ids without changing shelf ids`() {
        val home = JSONObject()
            .put("hero", JSONObject().put("id", "HASH_HERO"))
            .put("secondary", JSONArray().put(JSONObject().put("id", "HASH_SECONDARY")))
            .put(
                "shelves",
                JSONArray().put(
                    JSONObject()
                        .put("id", "shelf-1")
                        .put("tracks", JSONArray().put(JSONObject().put("id", "HASH_SHELF"))),
                ),
            )

        BridgeTrackIds.sanitizeHome(home)

        assertEquals("shelf-1", home.getJSONArray("shelves").getJSONObject(0).getString("id"))
        assertFalse(home.toString().contains("HASH_"))
    }

    @Test
    fun `disallowed home artwork is redacted without breaking the exact Vue track schema`() {
        val track = JSONObject()
            .put("id", "native-track")
            .put("artwork", "android.resource://local/private-cover")
        val home = JSONObject()
            .put("hero", track)
            .put("secondary", JSONArray())
            .put("shelves", JSONArray())

        BridgeTrackIds.sanitizeHome(home)

        assertTrue(track.has("artwork"))
        assertEquals("", track.getString("artwork"))
    }
}
