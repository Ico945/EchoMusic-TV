package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Real production WebView/D-pad, explicitly synthetic player data. */
class TvR54MotionTest {
    private fun ProductionWebViewHarness.capture(name: String) {
        Thread.sleep(300L)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Test fun rapidFocusKeepsShortMotionAndReducedMotionStillWins() {
        ProductionWebViewHarness.launch().use { web ->
            web.focus("[data-focus-id=nav-home]")
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, repeatCount = 1)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)
            assertTrue(web.bool("document.querySelector('.tv-canvas').classList.contains('tv-rapid-input')"))
            assertTrue(web.bool("(()=>{const t=parseFloat(getComputedStyle(document.activeElement).transitionDuration);return t>0 && t<=.061})()"))
            assertEquals("nav-library", web.activeFocusId())
            assertTrue(web.bool("!!document.querySelector('[data-page=home]')"))
            web.evaluate("document.querySelector('.tv-canvas').classList.add('tv-reduced-motion')")
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, repeatCount = 1)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP)
            assertTrue(web.bool("parseFloat(getComputedStyle(document.activeElement).transitionDuration)===0"))
            assertEquals("nav-home", web.activeFocusId())
        }
    }

    @Test fun lyricHighlightKeepsLineGeometryAndIdleKeepsStageLayout() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            web.emitPlayerSnapshot(playing = true, queueSize = 20)
            web.click("[data-focus-id=mini-open]")
            assertTrue(web.waitFor("!!document.querySelector('.lyric-scroll')"))
            web.evaluate("""
                (()=>{
                  const player=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-player');
                  player.lyrics=Array.from({length:30},(_,i)=>({startMs:i*1000,endMs:(i+1)*1000-1,text:'动效验收 第 '+(i+1)+' 句歌词'}));
                  window.__r54Player=player;
                })()
            """.trimIndent())
            Thread.sleep(350L)
            web.evaluate("window.__r54Rows=Array.from(document.querySelectorAll('.lyric-scroll p')).map(n=>[n.offsetTop,n.offsetHeight]);window.__r54Padding=getComputedStyle(document.querySelector('.player-stage')).padding;window.__r54Player.positionMs=6500")
            assertTrue(web.waitFor("document.querySelector('[data-lyric-index=\"6\"]')?.dataset.lyricActive==='true'"))
            Thread.sleep(250L)
            assertTrue(web.bool("JSON.stringify(window.__r54Rows)===JSON.stringify(Array.from(document.querySelectorAll('.lyric-scroll p')).map(n=>[n.offsetTop,n.offsetHeight]))"))
            web.capture("r54-lyrics-fixture.png")
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]')?.dataset.playerIdle==='true'", 12_000L))
            assertTrue(web.bool("window.__r54Padding===getComputedStyle(document.querySelector('.player-stage')).padding"))
            assertTrue(web.bool("!getComputedStyle(document.querySelector('.player-stage')).transitionProperty.includes('padding')"))
            web.capture("r54-immersive-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]')?.dataset.playerIdle==='false'"))
        }
    }

    @Test fun closingQueueReleasesFocusBeforeItsExitAndCanReopenImmediately() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            web.emitPlayerSnapshot(playing = false, queueSize = 40)
            web.click("[data-focus-id=mini-open]")
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=player-queue]')"))
            web.focus("[data-focus-id=player-queue]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='queue-track-tv_instrumented_0'"))
            repeat(8) { web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            Thread.sleep(200L)
            val stopped = web.text("document.querySelector('.player-drawer ol').scrollTop")
            Thread.sleep(200L)
            assertEquals(stopped, web.text("document.querySelector('.player-drawer ol').scrollTop"))
            assertTrue(web.bool("(()=>{const r=document.activeElement.getBoundingClientRect(),s=document.querySelector('.player-drawer ol').getBoundingClientRect();return r.top>=s.top-1 && r.bottom<=s.bottom+1})()"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='player-queue'"))
            assertTrue(web.bool("Array.from(document.querySelectorAll('.tv-overlay-leave-active')).every(n=>n.hasAttribute('inert'))"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='queue-track-tv_instrumented_0' && !document.activeElement.closest('[inert]')"))
            Thread.sleep(250L)
            assertTrue(web.bool("document.querySelectorAll('.player-drawer').length===1"))
        }
    }
}
