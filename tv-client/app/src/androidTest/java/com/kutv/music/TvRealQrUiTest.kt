package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvRealQrUiTest {
    @Test
    fun openingLoginShowsARealQrImage() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=account]') !== null"))
            assertTrue(web.click("[data-focus-id=account]"))
            assertTrue(web.waitFor("document.querySelector('.login-dialog') !== null"))
            // The real QR must render as an image; when the environment or the
            // flow fails, the message carries the live dialog text.
            val qrRendered = web.waitFor("!!document.querySelector('.login-dialog img.qr')", 20_000L)
            val state = web.evaluate(
                "(() => { const d = document.querySelector('.login-dialog'); return d ? d.textContent : ''; })()",
            ) as String
            assertTrue("real QR expected, dialog reads: $state", qrRendered)
        }
    }
}
