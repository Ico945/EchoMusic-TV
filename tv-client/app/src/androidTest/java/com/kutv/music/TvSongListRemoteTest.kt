package com.kutv.music

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android KeyEvents against the real production SearchPage/SongList.
 * Only content and the playback command sink are deterministic test fixtures;
 * the native event adapter, Vue handlers, hold timer and menu are unchanged.
 */
@RunWith(AndroidJUnit4::class)
class TvSongListRemoteTest {
    private val first = "[data-focus-id=search-song-remote1]"
    private val second = "[data-focus-id=search-song-remote2]"
    private val menu = "document.querySelector('.song-menu') !== null"

    private fun prepare(web: ProductionWebViewHarness, count: Int = 2) {
        web.evaluate("window.location.hash = '#/search'")
        assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
        assertTrue(web.bool("""
            (() => {
              const pinia = document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia;
              const search = pinia._s.get('tv-search');
              search.query = 'remote'; search.category = 'song'; search.submitted = true;
              search.loading = false; search.error = false;
              search.results = { items: Array.from({length:$count},(_,i) => i+1).map(n => ({
                id: 'remote'+n, kind: 'song', title: 'Remote Song '+n, subtitle: 'Test', artwork: ''
              })), nextCursor: null, total: 2 };
              window.__remotePlays = [];
              pinia._s.get('tv-player').playQueue = async (ids, index) => {
                window.__remotePlays.push({ ids, index });
              };
              return true;
            })()
        """.trimIndent()))
        assertTrue(web.waitFor("document.querySelector('$first') !== null"))
        assertTrue(web.focus(first))
    }

    @Test fun shortPressVariantsPlayExactlyOnceAndNeverOpenLongPressMenu() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT).forEachIndexed { index, key ->
                web.dispatchKey(key)
                assertTrue(web.waitFor("window.__remotePlays.length === ${index + 1}"))
            }
            Thread.sleep(800)
            assertEquals("5", web.text("window.__remotePlays.length"))
            assertTrue(web.bool("!($menu)"))
            assertTrue(web.bool("window.__remotePlays.every(p => p.index === 0 && p.ids.join(',') === 'remote1,remote2')"))
        }
    }

    @Test fun mediaKeyReleasesAndRepeatsDoNotDuplicateCommands() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            assertTrue(web.bool("""
                (() => {
                  const player = document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-player');
                  window.__remoteMedia = [];
                  ['toggle', 'previous', 'next'].forEach(name => {
                    player[name] = async () => { window.__remoteMedia.push(name); };
                  });
                  return true;
                })()
            """.trimIndent()))
            listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_NEXT).forEach { key ->
                web.dispatchKeyAction(key, KeyEvent.ACTION_DOWN)
                web.dispatchKeyAction(key, KeyEvent.ACTION_DOWN, 1)
                web.dispatchKeyAction(key, KeyEvent.ACTION_UP)
            }
            assertTrue(web.waitFor("window.__remoteMedia.join(',') === 'toggle,previous,next'"))
            Thread.sleep(800)
            assertEquals("3", web.text("window.__remoteMedia.length"))
            assertEquals("0", web.text("window.__remotePlays.length"))
            assertTrue(web.bool("!($menu)"))
        }
    }

    @Test fun directionRepeatsFollowTheNewFocusWithoutActivatingSongs() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web, 4)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-song-remote2'"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 1)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-song-remote3'"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 2)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-song-remote4'"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
            assertEquals("0", web.text("window.__remotePlays.length"))
        }
    }

    @Test fun longPressRepeatsOpenOneMenuAndBackRestoresTheRow() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1)
            assertTrue(web.waitFor(menu))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 2)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            assertEquals("0", web.text("window.__remotePlays.length"))
            assertEquals("1", web.text("document.querySelectorAll('.song-menu').length"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("!($menu)"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-song-remote1'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__remotePlays.length === 1"))
        }
    }

    @Test fun movingFocusWhileHeldDoesNotPlayEitherRowOrOpenItsMenu() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-song-remote2'"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            Thread.sleep(800)
            assertEquals("0", web.text("window.__remotePlays.length"))
            assertTrue(web.bool("!($menu)"))
            assertTrue(web.focus(second))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__remotePlays.length === 1 && window.__remotePlays[0].index === 1"))
        }
    }

    @Test fun canceledReleaseAndNativeWindowFocusLossCancelPendingActions() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED)
            Thread.sleep(800)
            assertEquals("0", web.text("window.__remotePlays.length"))
            assertTrue(web.bool("!($menu)"))
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.withActivity { web.webView.onWindowFocusChanged(false) }
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            Thread.sleep(800)
            assertEquals("0", web.text("window.__remotePlays.length"))
            assertTrue(web.bool("!($menu)"))
            web.withActivity { web.webView.onWindowFocusChanged(true) }
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__remotePlays.length === 1"))
        }
    }

    @Test fun activityRecreationDropsHeldKeyAndIgnoresTheOrphanRelease() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            web.recreate()
            prepare(web)
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            Thread.sleep(800)
            assertEquals("0", web.text("window.__remotePlays.length"))
            assertTrue(web.bool("!($menu)"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__remotePlays.length === 1"))
        }
    }
}
