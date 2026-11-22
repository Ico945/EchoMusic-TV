package com.kutv.music.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDeviceStoreTest {
    @Test
    fun `legacy literal null dfid never counts as registered`() {
        val persistence = MemoryDirectKeyValueStore()
        val store = KugouDeviceStore(persistence) { "a".repeat(64) }
        val before = store.getOrCreate()
        val json = org.json.JSONObject(persistence.get("kugou.device.v1")!!).put("dfid", "null")
        persistence.put("kugou.device.v1", json.toString())
        val restored = KugouDeviceStore(persistence).getOrCreate()
        assertEquals(null, restored.dfid)
        assertEquals(before.guid, restored.guid)
        assertEquals(before.mid, restored.mid)
    }

    @Test
    fun `identity survives recreation and never uses hardware mac`() {
        val persistence = MemoryDirectKeyValueStore()
        val first = KugouDeviceStore(persistence) { "a".repeat(64) }.getOrCreate()
        val second = KugouDeviceStore(persistence) { "b".repeat(64) }.getOrCreate()

        assertEquals(first, second)
        assertEquals(KugouDeviceIdentity.PRIVACY_SAFE_MAC, first.mac)
        assertTrue(first.guid.matches(Regex("[a-f0-9]{32}")))
        assertTrue(first.mid.matches(Regex("[a-f0-9]{32}")))
        assertEquals(KugouSigner.md5(first.guid), first.mid)
        assertTrue(first.uuid.isNotBlank())
    }

    @Test
    fun `legacy unrelated mid is migrated and dfid is cleared for registration`() {
        val persistence = MemoryDirectKeyValueStore(
            mutableMapOf(
                "kugou.device.v1" to """{
                    "version":1,
                    "guid":"${"a".repeat(32)}",
                    "mid":"${"b".repeat(32)}",
                    "uuid":"legacy-uuid",
                    "dfid":"OLD-DFID"
                }""".trimIndent(),
            ),
        )

        val migrated = KugouDeviceStore(persistence).getOrCreate()

        assertEquals("a".repeat(32), migrated.guid)
        assertEquals(KugouSigner.md5(migrated.guid), migrated.mid)
        assertEquals("legacy-uuid", migrated.uuid)
        assertEquals(null, migrated.dfid)
        assertEquals(migrated, KugouDeviceStore(persistence).getOrCreate())
    }

    @Test
    fun `corrupt partial identity is replaced atomically`() {
        val persistence = MemoryDirectKeyValueStore(
            mutableMapOf("kugou.device.v1" to "{\"guid\":\"partial\"}"),
        )
        val identity = KugouDeviceStore(persistence) { "c".repeat(64) }.getOrCreate()

        assertNotEquals("partial", identity.guid)
        assertEquals(identity, KugouDeviceStore(persistence).getOrCreate())
    }

    @Test
    fun `dfid update preserves all installation identifiers`() {
        val persistence = MemoryDirectKeyValueStore()
        val store = KugouDeviceStore(persistence) { "d".repeat(64) }
        val before = store.getOrCreate()
        store.updateDfid("DFID-1")
        val after = store.getOrCreate()

        assertEquals(before.guid, after.guid)
        assertEquals(before.mid, after.mid)
        assertEquals(before.uuid, after.uuid)
        assertEquals("DFID-1", after.dfid)
    }
}
