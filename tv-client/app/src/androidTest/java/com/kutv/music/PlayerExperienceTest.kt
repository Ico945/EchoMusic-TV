package com.kutv.music

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerExperienceTest {
    @Test
    fun exactBridgeSnapshotDrivesLyricsControlsAndQueueDrawer() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            web.emitPlayerSnapshot(queueSize = 2)
            assertTrue(web.waitFor("document.querySelector('[data-mini-player]') !== null"))
            assertTrue(web.click("[data-focus-id=mini-open]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))
            assertTrue(web.text("document.querySelector('[data-page=now-playing]').innerText"), web.waitFor("document.querySelector('[data-lyric-active=true]')?.textContent.includes('Active line')"))
            assertTrue(web.click("[data-focus-id=player-queue]"))
            assertTrue(web.waitFor("document.querySelector('[aria-label=\"播放队列\"]') !== null"))
            assertEquals(2, (web.evaluate("document.querySelectorAll('[data-focus-id^=queue-track-]').length") as Number).toInt())
        }
    }

    @Test
    fun continuousSnapshotsAllowIdleAndNativeDirectionOnlyWakesTheLastControl() {
        ProductionWebViewHarness.launch().use { web ->
            web.isolatePlayerUiFixture()
            web.emitPlayerSnapshot(queueSize = 2)
            assertTrue(web.waitFor("document.querySelector('[data-mini-player]') !== null"))
            assertTrue(web.click("[data-focus-id=mini-open]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))

            assertFalse(web.bool("document.querySelector('[data-mini-player]') !== null"))
            assertEquals(0, (web.evaluate("document.querySelectorAll('[data-nav-id]').length") as Number).toInt())
            assertTrue(web.focus("[data-focus-id=player-queue]"))
            // Match the native facade's 500ms full snapshots. These are fixture
            // projections for UI timing, not evidence of account audio playback.
            repeat(22) { tick ->
                web.emitPlayerSnapshot(queueSize = 2, positionMs = tick * 500L)
                Thread.sleep(500L)
            }
            assertTrue("snapshots must not postpone the real 10s deadline: " + web.text("document.querySelector('[data-page=now-playing]').innerText"),
                web.bool("document.querySelector('[data-page=now-playing]')?.dataset.playerIdle === 'true'"))
            assertTrue(web.bool("document.activeElement === document.querySelector('[data-page=now-playing]')"))

            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP)

            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]')?.dataset.playerIdle === 'false'"))
            assertEquals("player-queue", web.activeFocusId())
            assertFalse("first direction is wake only", web.bool("!!document.querySelector('[aria-label=\"播放队列\"]')"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            assertTrue(web.waitFor("!!document.querySelector('[aria-label=\"播放队列\"]')"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset?.focusId === 'player-queue'"))
            web.emitPlayerSnapshot(playing = false)
            Thread.sleep(11_000L)
            assertFalse("paused controls remain visible", web.bool("document.querySelector('[data-page=now-playing]')?.dataset.playerIdle === 'true'"))
        }
    }
}
