package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Shipped UI + Android D-pad. Playback state is a fixture, not account evidence. */
class TvR53InteractionTest {
    private fun ProductionWebViewHarness.capture(name: String) {
        evaluate("window.__r53Frame=false; requestAnimationFrame(()=>requestAnimationFrame(()=>window.__r53Frame=true))")
        assertTrue(waitFor("window.__r53Frame===true"))
        Thread.sleep(350L)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Test fun progressPreviewsWithoutNativeSpamAndBackCancelsBeforeLeavingPlayer() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            web.emitPlayerSnapshot(queueSize = 4, playing = false)
            web.click("[data-focus-id=mini-open]")
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=player-progress]')"))
            web.evaluate("""
                (()=>{
                  window.__r53Seeks=[];
                  const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
                  bridge.postMessage=raw=>{
                    const r=JSON.parse(raw);
                    if(r.method!=='player.seekTo' && r.method!=='player.seekBy') return original(raw);
                    window.__r53Seeks.push({method:r.method,payload:r.payload});
                    queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload:{accepted:true}})}));
                  };
                })()
            """.trimIndent())
            web.focus("[data-focus-id=player-toggle]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals("player-progress", web.activeFocusId())
            repeat(8) { web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals("[]", web.text("JSON.stringify(window.__r53Seeks)"))
            assertTrue(web.bool("document.querySelector('[data-focus-id=player-progress]').dataset.seekPreview==='true'"))
            web.capture("r53-seek-preview-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.bool("!!document.querySelector('[data-page=now-playing]')"))
            assertTrue(web.bool("document.querySelector('[data-focus-id=player-progress]').dataset.seekPreview==='false'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__r53Seeks.length===1"))
            assertTrue(web.bool("window.__r53Seeks[0].method==='player.seekTo' && !!window.__r53Seeks[0].payload.trackId"))
        }
    }

    @Test fun queueRemoteActionsReturnToSelectedSongAndCurrentJumpDoesNotPlay() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            web.emitPlayerSnapshot(queueSize = 40, playing = false)
            web.click("[data-focus-id=mini-open]")
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=player-queue]')"))
            web.focus("[data-focus-id=player-queue]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=queue-current]')"))
            val first = web.activeFocusId()
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            val next = web.activeFocusId()
            assertNotEquals(first, next)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("queue-next", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(next, web.activeFocusId())
            repeat(12) { web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("queue-current", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(first, web.activeFocusId())
            assertTrue(web.bool("document.querySelector('[data-focus-id=queue-next]').getAttribute('aria-disabled')==='true'"))
            assertTrue(web.bool("(()=>{const r=document.activeElement.getBoundingClientRect(),l=document.querySelector('.player-drawer ol').getBoundingClientRect();return r.top>=l.top && r.bottom<=l.bottom;})()"))
            web.capture("r53-queue-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='player-queue'"))
        }
    }

    @Test fun topNavigationStopsAtEdgesAndRequiresConfirmationToSwitch() {
        ProductionWebViewHarness.launch().use { web ->
            web.focus("[data-focus-id=nav-home]")
            repeat(8) { web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            assertEquals("nav-home", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("nav-explore", web.activeFocusId())
            assertTrue(web.bool("!!document.querySelector('[data-page=home]')"))
            web.focus("[data-focus-id=account]")
            repeat(8) { web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals("account", web.activeFocusId())
        }
    }
}
