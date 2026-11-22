package com.kutv.music

import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutv.music.direct.KugouSessionState
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectLoginUiTest {
    @Test
    fun productionQrStatesKeepRemoteFocusAndBackCancelsNativePolling() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.bool("(() => { window.__loginRequests = 0; window.addEventListener('echo-tv-login-requested', () => window.__loginRequests += 1); return true; })()"))
            assertTrue(web.focus("[data-focus-id=account]"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            assertEquals(1, (web.evaluate("window.__loginRequests") as Number).toInt())
            assertTrue(web.waitFor("document.querySelector('.login-dialog') !== null"))
            assertTrue(
                web.waitFor(
                    "['login-qr','login-regenerate'].includes(document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.focusId : '')",
                ),
            )
            assertTrue(web.waitFor("!!document.querySelector('.login-dialog img.qr')", 20_000L))
            // Fixture projections test state-transition focus, not phone scanning.
            web.emit("auth.stateChanged", JSONObject()
                .put("state", "scanned")
                .put("qrImageDataUrl", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1kAAAAASUVORK5CYII=")
                .put("expiresAt", System.currentTimeMillis() + 60_000))
            assertTrue(web.waitFor("!!document.querySelector('.qr.dim') && document.activeElement?.dataset?.focusId === 'login-qr'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("login-cancel", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals("login-qr", web.activeFocusId())
            web.emit("auth.stateChanged", JSONObject().put("state", "expired"))
            assertTrue(web.waitFor("document.activeElement?.dataset?.focusId === 'login-regenerate'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("login-cancel", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals("login-regenerate", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("!document.querySelector('.login-dialog') && document.activeElement?.dataset?.focusId === 'account'"))
            val deadline = SystemClock.uptimeMillis() + 3_000
            while (!nativePollingStopped(web) && SystemClock.uptimeMillis() < deadline) Thread.sleep(25)
            assertTrue("Back must clear native QR job, pending state and secure window flag", nativePollingStopped(web))
        }
    }

    private fun nativePollingStopped(web: ProductionWebViewHarness): Boolean = web.withActivity { activity ->
        val job = MainActivity::class.java.getDeclaredField("authPollJob").apply { isAccessible = true }.get(activity) as? Job
        job == null && AppGraph.sessionManager.state.value !is KugouSessionState.QrPending &&
            (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0
    }
}
