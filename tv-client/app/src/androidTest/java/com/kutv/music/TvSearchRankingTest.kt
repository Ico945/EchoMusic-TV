package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.uiautomator.UiDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production WebView and physical D-pad dispatch; fixture data is explicitly labelled. */
@RunWith(AndroidJUnit4::class)
class TvSearchRankingTest {
    private fun capture(name: String) {
        Thread.sleep(500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun actualSystemImeBackRestoresSearchNavigationWithoutSubmitting() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ProductionWebViewHarness.launch().use { web ->
            // r46 does not steal a different tab's focus on programmatic route
            // changes. Enter Search through the actual user confirmation path.
            assertTrue(web.focus("[data-focus-id=nav-search]"))
            assertTrue(device.pressDPadCenter())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-input' && document.activeElement.readOnly"))
            fun imeShown(): Boolean {
                var shown = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    shown = ViewCompat.getRootWindowInsets(web.webView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                return shown
            }
            assertTrue("Focusing input does not open the system IME", !imeShown())
            assertTrue(device.pressDPadCenter())
            assertTrue(web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'true'"))
            val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
            while (!imeShown() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Actual system keyboard opened", imeShown())
            device.waitForIdle()
            device.executeShellCommand("input text music")
            val typed = web.waitFor("document.querySelector('[data-focus-id=search-input]').value === 'music'")
            if (!typed) capture("r43-search-system-ime-diagnostic.png")
            assertTrue("Typed value: " + web.text("document.querySelector('[data-focus-id=search-input]').value"), typed)
            assertTrue(device.pressBack())
            assertTrue("System Back releases edit mode and restores submit focus", web.waitFor("document.activeElement?.dataset.focusId === 'search-submit' && document.querySelector('[data-focus-id=search-input]').readOnly"))
            assertTrue(web.bool("document.querySelector('[data-page=search]')?.dataset.searchSubmitted === 'false'"))
            assertTrue(device.pressDPadDown())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-tab-song'"))
            capture("r43-search-system-ime.png")
        }
    }

    @Test fun searchRetryCategoriesImeAndBackUseRemoteFocusOwnership() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("""
                (() => {
                  const bridge = window.EchoTvNative, original = bridge.postMessage.bind(bridge);
                  window.__searchCalls = 0;
                  bridge.postMessage = raw => {
                    const request = JSON.parse(raw);
                    if (!['content.search','content.hotSearch'].includes(request.method)) return original(raw);
                    const failed = request.method === 'content.search' && ++window.__searchCalls === 1;
                    const category = request.payload.category;
                    const payload = request.method === 'content.hotSearch' ? {terms:['华语经典','轻音乐','电影原声','周末放松']} :
                      {category,items:[{id:category === 'album' ? 'album:fixture' : 'search-fixture',title:'搜索交互验收',kind:category === 'album' ? 'album':'song',subtitle:'示例内容 · 非真实搜索',artwork:''}],nextCursor:null,total:1};
                    setTimeout(() => bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:request.id,ok:!failed,
                      ...(failed ? {error:{code:'NETWORK_ERROR',message:'fixture',retryable:true,scope:'content'}} : {payload})
                    })}), 700);
                  };
                  window.location.hash = '#/search';
                })()
            """.trimIndent())
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-hot-0]') !== null"))
            assertTrue(web.focus("[data-focus-id=search-hot-0]"))
            capture("r43-search-discovery-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-skeleton=songs]') !== null"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-retry'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-song-search-fixture'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-tab-song'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-album-0]') !== null"))
            assertTrue(web.bool("document.activeElement?.dataset.focusId === 'search-tab-album'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'search-album-0'"))
            assertTrue(web.focus("[data-focus-id=search-input]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'true'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'false'"))
            assertTrue(web.bool("document.querySelector('[data-page=search]')?.dataset.searchSubmitted === 'true'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            val searchReturned = web.waitFor("document.querySelector('[data-page=search]')?.dataset.searchSubmitted === 'false' && document.activeElement?.dataset.focusId === 'search-input'")
            assertTrue(web.text("JSON.stringify({focus:document.activeElement?.dataset.focusId,submitted:document.querySelector('[data-page=search]')?.dataset.searchSubmitted,ime:document.querySelector('[data-page=search]')?.dataset.imeFocused,back:document.querySelector('.tv-shell')?.dataset.lastBackLayer})"), searchReturned)
            assertTrue(web.focus("[data-focus-id=nav-home]"))
            web.evaluate("window.dispatchEvent(new Event('echo-tv-ime-hidden'))")
            assertTrue(web.bool("document.activeElement?.dataset.focusId === 'nav-home'"))
        }
    }

    @Test fun rankingSkeletonRetryAndBackRestoreTheSourceCard() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("""
                (() => {
                  const bridge = window.EchoTvNative, original = bridge.postMessage.bind(bridge);
                  window.__chartCalls = 0;
                  bridge.postMessage = raw => {
                    const request = JSON.parse(raw);
                    if (!['content.explore','content.ranking'].includes(request.method)) return original(raw);
                    const failed = request.method === 'content.ranking' && ++window.__chartCalls === 1;
                    const payload = request.method === 'content.explore' ?
                      {newSongs:[],newAlbums:[],newPlaylists:[],artists:[],rankings:Array.from({length:8},(_,i)=>({id:'fixture-'+i,title:'榜单交互验收 '+(i+1),artwork:''}))} :
                      {items:Array.from({length:5},(_,i)=>({id:'chart-song-'+i,title:'榜单示例歌曲 '+(i+1),artist:'交互验收',album:'非真实榜单',artwork:'',durationMs:180000})),nextCursor:null,total:5};
                    setTimeout(() => bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:request.id,ok:!failed,
                      ...(failed ? {error:{code:'NETWORK_ERROR',message:'fixture',retryable:true,scope:'content'}} : {payload})
                    })}), 700);
                  };
                  const store = document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-browse');
                  // Home starts an explore prefetch before this fixture installs.
                  // Let that single-flight settle, then load through the intercepted
                  // real action. Flipping loaded alone does not cancel its promise.
                  window.__chartFixtureReady = false;
                  void store.loadExplore().then(() => store.loadExplore()).then(() => {
                    window.__chartFixtureReady = true;
                  });
                })()
            """.trimIndent())
            assertTrue(web.waitFor("window.__chartFixtureReady===true", timeoutMs = 20_000))
            assertTrue(web.focus("[data-focus-id=nav-ranking]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            val rankingReady = web.waitFor("document.querySelectorAll('[data-focus-group=ranking-list]').length === 8")
            val diagnostic = if (rankingReady) "" else web.text("JSON.stringify({route:location.hash,count:document.querySelectorAll('[data-focus-group=ranking-list]').length,loaded:document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-browse')?.loaded,shelves:document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-browse')?.shelves.map(s=>({id:s.id,status:s.status,count:s.rankings.length})),calls:window.__chartCalls})")
            assertTrue("Ranking fixture: $diagnostic", rankingReady)
            assertTrue(web.focus("[data-focus-id=ranking-list-1]"))
            assertTrue(web.bool("document.querySelectorAll('.nav-glyph svg').length === 6 && document.querySelectorAll('.ranking-card [data-icon=chart]').length === 8"))
            capture("r43-ranking-fallback-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-skeleton=songs]') !== null"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'ranking-retry' && document.querySelector('.empty-state')?.textContent.includes('榜单歌曲加载失败') === true"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'ranking-chart-song-0'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'ranking-play-all'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'ranking-list-1'"))
            web.evaluate("window.location.hash = '#/more'")
            assertTrue(web.waitFor("document.querySelectorAll('.entry-glyph svg').length === 4"))
            assertTrue(web.focus("[data-focus-id=more-settings]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'more-recognize'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'more-about'"))
            capture("r43-more-fixture.png")
        }
    }
}
