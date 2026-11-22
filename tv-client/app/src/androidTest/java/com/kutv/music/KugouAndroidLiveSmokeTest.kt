package com.kutv.music

import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.direct.KugouDeviceIdentity
import com.kutv.music.direct.KugouDirectAuth
import com.kutv.music.direct.KugouHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KugouAndroidLiveSmokeTest {
    @Test
    fun createsOfficialQrFromAndroidNetworkStack() = runBlocking {
        val enabled = InstrumentationRegistry.getArguments().getString("kutvLiveKugou") == "true"
        assumeTrue("Real KuGou smoke test is opt-in", enabled)
        val client = KugouHttpClient(
            deviceProvider = {
                KugouDeviceIdentity(
                    guid = "a".repeat(32),
                    mid = "b".repeat(32),
                    uuid = "android-live-smoke",
                )
            },
            credentialProvider = { null },
        )

        val qr = KugouDirectAuth(client).createQr()

        assertTrue(qr.key.isNotBlank())
        assertTrue(qr.qrUrl.startsWith("https://h5.kugou.com/apps/loginQRCode/"))
    }
}
