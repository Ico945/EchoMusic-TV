package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in network smoke, no bridge mocks and no account credentials. */
@RunWith(AndroidJUnit4::class)
class RankingLiveSmokeTest {
    @Test fun anonymousSearchRequestsLoginInsteadOfShowingFalseEmptyResults() {
        assumeTrue("Pass -e liveNetwork true for this public network check", InstrumentationRegistry.getArguments().getString("liveNetwork") == "true")
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("window.location.hash = '#/search'")
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-input]') !== null"))
            web.evaluate("(() => { const input = document.querySelector('[data-focus-id=search-input]'); input.value = '周杰伦'; input.dispatchEvent(new Event('input', {bubbles:true})); })()")
            assertTrue(web.focus("[data-focus-id=search-submit]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue("Live search authentication gate", web.waitFor("document.querySelector('[data-focus-id=search-login]') !== null", 40_000L))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-login'"))
            assertTrue(web.bool("document.querySelectorAll('[data-focus-group=search-song]').length === 0 && !document.querySelector('[data-page=search]').textContent.includes('没有找到相关内容')"))
            capture("r43-search-login-live.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-tab-song'"))
        }
    }

    private fun capture(name: String) {
        Thread.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun publicChartsLoadActualArtworkAndTwoDifferentPagesOfSongs() {
        assumeTrue("Pass -e liveNetwork true for this public network check", InstrumentationRegistry.getArguments().getString("liveNetwork") == "true")
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("window.location.hash = '#/ranking'")
            assertTrue("Live ranking cards", web.waitFor("document.querySelectorAll('.ranking-card').length > 3", 40_000L))
            assertTrue("Live cover decoded", web.waitFor("Array.from(document.querySelectorAll('.ranking-card img')).some(img => img.complete && img.naturalWidth > 0)", 20_000L))
            assertTrue(web.focus("[data-focus-id=ranking-list-0]"))
            capture("r43-ranking-live.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue("Live first 30 songs", web.waitFor("document.querySelectorAll('[data-focus-group=ranking]').length === 30", 40_000L))
            assertTrue("Detail keeps focus inside its playback action", web.waitFor("document.activeElement?.dataset.focusId === 'ranking-play-all'"))
            capture("r43-ranking-detail-live.png")
            assertTrue(web.focus("[data-focus-id=pagination-load-more]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue("Live second page appends unique songs", web.waitFor("document.querySelectorAll('[data-focus-group=ranking]').length === 60", 40_000L))
            assertTrue(web.bool("(() => { const store = document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-browse'); return store.rankingNextCursor === '3' && new Set(store.rankingTracks.map(t=>t.id)).size === 60 && store.rankingTracks[0].durationMs > 0; })()"))
        }
    }
}
