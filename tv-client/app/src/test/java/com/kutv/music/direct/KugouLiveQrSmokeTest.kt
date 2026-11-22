package com.kutv.music.direct

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KugouLiveQrSmokeTest {
    @Test
    fun `real upstream creates official qr`() = runBlocking {
        assumeTrue(System.getenv("KUTV_LIVE_KUGOU") == "1")
        val client = KugouHttpClient(
            deviceProvider = {
                KugouDeviceIdentity(
                    guid = "a".repeat(32),
                    mid = "b".repeat(32),
                    uuid = "live-smoke",
                )
            },
            credentialProvider = { null },
        )

        val result = KugouDirectAuth(client).createQr()

        assertTrue(result.key.isNotBlank())
        assertTrue(result.qrUrl.startsWith("https://h5.kugou.com/apps/loginQRCode/"))
    }

    @Test
    fun `real upstream registers anonymous device`() = runBlocking {
        assumeTrue(System.getenv("KUTV_LIVE_KUGOU") == "1")
        val deviceStore = KugouDeviceStore(MemoryDirectKeyValueStore()) { "c".repeat(64) }
        val client = KugouHttpClient(
            deviceProvider = deviceStore::getOrCreate,
            credentialProvider = { null },
        )
        val registered = KugouDeviceRegistrar(deviceStore, client).ensureRegistered()
        assertTrue(!registered.dfid.isNullOrBlank())
    }
}
