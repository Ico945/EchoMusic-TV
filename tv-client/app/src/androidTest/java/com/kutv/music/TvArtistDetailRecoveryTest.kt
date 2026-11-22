package com.kutv.music

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production assets and Android key dispatch; synthetic Bridge responses, not live Kugou acceptance. */
@RunWith(AndroidJUnit4::class)
class TvArtistDetailRecoveryTest {
    private fun ProductionWebViewHarness.installArtistProjection(holdDetail: Boolean = false) {
        assertTrue(bool("""
            (() => {
              const bridge = window.EchoTvNative;
              const original = bridge.postMessage.bind(bridge);
              window.__artistCalls = [];
              window.__artistReply = (request, payload, failed = false) => bridge.onmessage({data: JSON.stringify({
                version: 1, type: 'response', id: request.id, ok: !failed,
                ...(failed ? {error: {code: 'NETWORK_ERROR', message: 'Test network failure', retryable: true, scope: 'content'}} : {payload})
              })});
              window.__artistPayload = {
                id: '1', kind: 'artist', title: 'Instrumented Artist', artwork: '', summary: '',
                tabs: ['songs', 'albums', 'mv'],
                hotTracks: [{id: 'tv_artist_hot', title: 'Instrumented Hot Song', artist: 'Artist', album: '', artwork: '', durationMs: 180000}],
                children: {items: [{id: 'album:10', kind: 'album', title: 'Instrumented Album', subtitle: 'Artist', artwork: ''}], nextCursor: null, total: 1}
              };
              bridge.postMessage = (raw) => {
                const request = JSON.parse(raw);
                if (request.method === 'content.detail' && request.payload.kind === 'artist') {
                  window.__artistDetailPending = request;
                  if (!$holdDetail) queueMicrotask(() => window.__artistReply(request, window.__artistPayload));
                } else if (request.method === 'content.detailChildren' && request.payload.kind === 'artist') {
                  window.__artistCalls.push(request.payload);
                  window.__artistChildPending = request;
                  if (window.__artistCalls.length === 1) queueMicrotask(() => window.__artistReply(request, null, true));
                } else if (request.method === 'player.playQueue') {
                  window.__artistQueue = request.payload;
                  queueMicrotask(() => window.__artistReply(request, {}));
                } else original(raw);
              };
              return true;
            })()
        """.trimIndent()))
        evaluate("window.location.hash = '#/artist/artist%3A1'")
        assertTrue(waitFor("document.querySelector('[data-page=artist-detail]') !== null"))
    }

    @Test
    fun canonicalHotSongsAndAlbumsRenderAndConfirmSendsTheCorrectQueue() {
        ProductionWebViewHarness.launch().use { web ->
            web.installArtistProjection()
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=artist-tv_artist_hot]') !== null"))
            assertTrue(web.focus("[data-focus-id=detail-primary]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__artistQueue?.trackIds.join(',') === 'tv_artist_hot' && window.__artistQueue.startIndex === 0"))
            assertTrue(web.focus("[data-focus-id=artist-tab-albums]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=artist-albums-0]')?.textContent.includes('Instrumented Album') === true"))
            assertTrue(web.bool("window.__artistCalls.length === 0"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'artist-albums-0'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId?.startsWith('artist-tab-') === true"))
        }
    }

    @Test
    fun firstPageRetrySurvivesConfirmRepeatsAndDoesNotStealTheLaterTab() {
        ProductionWebViewHarness.launch().use { web ->
            web.installArtistProjection()
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=artist-tab-mv]') !== null"))
            assertTrue(web.focus("[data-focus-id=artist-tab-mv]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=artist-page-action]')?.textContent.includes('重试') === true"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'artist-page-action'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__artistCalls.length === 2 && document.querySelector('[data-focus-id=artist-page-action]')?.getAttribute('aria-busy') === 'true'"))
            assertEquals("artist-page-action", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId?.startsWith('artist-tab-') === true"))
            assertTrue(web.focus("[data-focus-id=artist-tab-songs]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            web.evaluate("window.__artistReply(window.__artistChildPending, {items:[], nextCursor:null, total:0})")
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'artist-tab-songs'"))
            // Wait for the actual result projection, not merely unchanged focus.
            assertTrue(web.focus("[data-focus-id=artist-tab-mv]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=artist-detail]')?.textContent.includes('暂无MV') === true"))
            assertEquals("artist-tab-mv", web.activeFocusId())
            assertTrue(web.bool("window.__artistCalls.length === 2"))
        }
    }

    @Test
    fun backWorksBeforeDetailResponseAndLateResultCannotRefocusTheNewPage() {
        ProductionWebViewHarness.launch().use { web ->
            web.installArtistProjection(holdDetail = true)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'artist-retry'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('[data-page=home]') !== null"))
            assertTrue(web.focus("[data-focus-id=nav-search]"))
            web.evaluate("window.__artistReply(window.__artistDetailPending, window.__artistPayload)")
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'nav-search'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
        }
    }
}
