package com.kutv.music

import android.view.KeyEvent
import com.kutv.music.application.capability.AndroidVisualEffectsPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBlurFallbackTest {
    @Test fun nativeCapabilitySettlesAndOpaquePlayerKeepsFocusAndWakeBehavior() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            val nativeBlur = web.withActivity { AndroidVisualEffectsPort(it).supportsLiveBlur() }
            assertTrue("startup must resolve the actual native visual capability", web.waitFor(
                "document.querySelector('.tv-canvas').classList.contains('tv-no-blur') === ${!nativeBlur}"))
            web.emitPlayerSnapshot(playing = true)
            assertTrue(web.waitFor("!!document.querySelector('[data-mini-player]')"))
            assertTrue(web.click("[data-focus-id=mini-open]"))
            assertTrue(web.waitFor("!!document.querySelector('[data-page=now-playing]')"))
            // Explicit low-capability CSS projection. This emulator is not a
            // low-RAM TCL; fixture snapshots do not prove real audio playback.
            web.evaluate("document.querySelector('.tv-canvas').classList.add('tv-no-blur')")
            assertTrue(web.bool("""
                (() => {
                    const page=document.querySelector('[data-page=now-playing]');
                    const backdrop=getComputedStyle(page,'::before');
                    return backdrop.display==='none' && backdrop.filter==='none' &&
                      Array.from(page.querySelectorAll('.ambient')).every(n => {
                        const s=getComputedStyle(n);
                        return s.display==='none' && s.filter==='none' && s.animationName==='none';
                      }) && getComputedStyle(page.querySelector('.lyric-panel')).display!=='none' &&
                      getComputedStyle(page.querySelector('.artwork-wrap')).filter==='none' &&
                      getComputedStyle(page.querySelector('.player-controls')).backdropFilter==='none';
                })()
            """.trimIndent()))
            assertTrue(web.focus("[data-focus-id=player-queue]"))
            assertTrue(web.waitFor("new DOMMatrix(getComputedStyle(document.activeElement).transform).a >= 1.049"))
            val idle = web.waitFor("document.querySelector('[data-page=now-playing]').dataset.playerIdle === 'true'", 12_000)
            assertTrue(web.text("JSON.stringify({focus:document.activeElement?.dataset.focusId,body:document.querySelector('[data-page=now-playing]').innerText,idle:document.querySelector('[data-page=now-playing]').dataset.playerIdle})"), idle)
            assertTrue(web.bool("""
                ['.player-header','.bottom-controls','.track-copy'].every(selector =>
                  getComputedStyle(document.querySelector(selector)).filter==='none')
            """.trimIndent()))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'player-queue'"))
            assertEquals(false, web.bool("!!document.querySelector('.player-drawer')"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            assertTrue(web.waitFor("!!document.querySelector('.player-drawer')"))
            assertTrue(web.bool("getComputedStyle(document.querySelector('.player-drawer')).backdropFilter==='none'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'player-queue'"))
        }
    }
}
