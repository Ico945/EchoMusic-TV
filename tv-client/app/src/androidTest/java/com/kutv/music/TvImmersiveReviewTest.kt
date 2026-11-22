package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production layout/input with explicitly synthetic data; not real-login evidence. */
@RunWith(AndroidJUnit4::class)
class TvImmersiveReviewTest {
    private fun ProductionWebViewHarness.capture(name: String) {
        Thread.sleep(500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun dailyErrorRetryAndOwningNavigationRemainUsableWithRemote() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("""
                (() => {
                  const bridge = window.EchoTvNative;
                  const original = bridge.postMessage.bind(bridge);
                  window.__dailyCalls = 0;
                  bridge.postMessage = raw => {
                    const request = JSON.parse(raw);
                    if (request.method !== 'content.dailyRecommendations') return original(raw);
                    const failed = ++window.__dailyCalls === 1;
                    queueMicrotask(() => bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:request.id,ok:!failed,
                      ...(failed ? {error:{code:'NETWORK_ERROR',message:'fixture',retryable:true,scope:'content'}} :
                        {payload:{items:[{id:'daily-fixture',title:'每日推荐交互验收',artist:'示例内容',album:'非真实推荐',artwork:'',durationMs:180000}]}})
                    })}));
                  };
                  window.location.hash = '#/daily-recommendations';
                })()
            """.trimIndent())
            assertTrue(web.waitFor("document.querySelector('[data-page=daily-recommendations]')?.textContent.includes('NETWORK_ERROR') === true"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'daily-retry'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=daily-daily-fixture]') !== null"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'daily-play-all'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'nav-home'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'daily-play-all' && location.hash.includes('daily-recommendations')"))
        }
    }

    @Test fun lyricsQueueFullBleedAndOnePressIdleToggleFollowTvContract() {
        ProductionWebViewHarness.launch().use { web ->
            // This is a synthetic playing snapshot, not a real audio session.
            // Late native empty/paused snapshots must not replace its state.
            web.isolatePlayerUiFixture()
            web.evaluate("window.location.hash = '#/now-playing'")
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))
            web.emitPlayerSnapshot(queueSize = 20)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=player-toggle]') !== null"))
            web.evaluate("""
                (() => {
                  const player = document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-player');
                  window.__idleToggles = 0;
                  player.toggle = async () => { window.__idleToggles++; };
                  player.queueIndex = 12;
                  player.currentTrack = player.queue[12];
                })()
            """.trimIndent())
            assertTrue(web.focus("[data-focus-id=player-toggle]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'player-progress'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'player-lyrics-panel'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'player-toggle' && document.querySelector('[data-page=now-playing]') !== null"))
            assertTrue(web.bool("(() => { const r=document.querySelector('[data-page=now-playing]').getBoundingClientRect(); return Math.abs(r.left)<2 && Math.abs(r.top)<2 && Math.abs(r.width-innerWidth)<2 && Math.abs(r.height-innerHeight)<2; })()"))
            web.capture("r41-player-lyrics-fixture.png")
            assertTrue(web.focus("[data-focus-id=player-queue]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'queue-track-tv_instrumented_12'"))
            assertTrue(web.waitFor("(() => { const r=document.activeElement.getBoundingClientRect(); const list=document.querySelector('.player-drawer ol').getBoundingClientRect(); return r.top>=list.top && r.bottom<=list.bottom; })()"))
            assertTrue(web.bool("(() => { const r=document.querySelector('.player-drawer').getBoundingClientRect(); return r.height > innerHeight*.75 && r.left>innerWidth*.5; })()"))
            web.capture("r41-player-queue-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'player-queue'"))
            val idled = web.waitFor("document.querySelector('[data-page=now-playing]')?.dataset.playerIdle === 'true'", 12_000L)
            assertTrue(web.text("JSON.stringify({focus:document.activeElement?.dataset.focusId,page:document.querySelector('[data-page=now-playing]')?.dataset,dialog:!!document.querySelector('[aria-modal=true]'),text:document.querySelector('[data-page=now-playing]')?.innerText.slice(-600)})"), idled)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__idleToggles === 1 && document.activeElement?.dataset.focusId === 'player-toggle'"))
        }
    }
}
