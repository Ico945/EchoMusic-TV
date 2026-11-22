package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production WebView + physical remote keys. Paused playback is a labelled fixture. */
@RunWith(AndroidJUnit4::class)
class TvNavigationChromeTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun ProductionWebViewHarness.capture(name: String) {
        evaluate("window.__chromeFrame=false; requestAnimationFrame(()=>requestAnimationFrame(()=>window.__chromeFrame=true))")
        assertTrue(waitFor("window.__chromeFrame === true"))
        Thread.sleep(800)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun ProductionWebViewHarness.prepare() {
        assertTrue(waitFor("document.querySelector('[data-top-nav]') !== null"))
        Thread.sleep(1000)
        evaluate("""
            (() => {
              // This suite tests return-focus ownership, not live feed timing.
              // A late network response can remove home-retry during a round
              // trip, so give Home stable labelled content throughout the test.
              const stores=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s;
              const fixture={greeting:'导航验收 · 测试数据',hero:{id:'chrome-home',title:'导航验收歌曲',artist:'测试数据 · 非真实账号',album:'',artwork:'',durationMs:180000},secondary:[],shelves:[]};
              stores.get('tv-app').loadSnapshot=async()=>({route:'home',session:{status:'loggedOut'},bootstrapHome:fixture});
              const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
              window.__chromePlaybackCommands=[];
              bridge.postMessage=raw => {
                const r=JSON.parse(raw);
                if(r.method.startsWith('player.') && r.method!=='player.getSnapshot')
                  window.__chromePlaybackCommands.push(r.method);
                if(!['content.hotSearch','content.home'].includes(r.method)) return original(raw);
                queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({
                  version:1,type:'response',id:r.id,ok:true,
                  payload:r.method==='content.home' ? fixture : {terms:['华语经典','轻音乐','电影原声','周末放松','晚安音乐','旅行','民谣','纯音乐']}
                })}));
              };
              stores.get('tv-content').loadHome(fixture);
            })()
        """.trimIndent())
        emitPlayerSnapshot(playing = false)
        assertTrue(waitFor("document.querySelector('[data-mini-player]') !== null"))
    }

    @Test fun physicalMenuAndConfirmReachLyricsAndBackRestoresAllSevenRootPages() {
        ProductionWebViewHarness.launch().use { web ->
            web.prepare()
            for (route in listOf("home", "explore", "library", "search", "fm", "ranking", "more")) {
                web.evaluate("location.hash='#/$route'")
                assertTrue("Page $route", web.waitFor("document.querySelector('[data-page=$route]') !== null"))
                assertTrue("Content target $route", web.waitFor(
                    "Array.from(document.querySelectorAll('[data-page=$route] [data-focus-id]')).some(n=>!n.disabled && n.offsetWidth>0)", 20_000L))
                val source = web.text("""
                    (() => {
                      const targets=Array.from(document.querySelectorAll('[data-page=$route] [data-focus-id]'));
                      const n=targets.find(n=>!n.disabled && n.offsetWidth>0 && !n.closest('[inert]'));
                      n.focus(); return n.dataset.focusId;
                    })()
                """.trimIndent())
                assertEquals("Source $route", source, web.activeFocusId())
                assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_MENU))
                assertTrue("Menu $route", web.waitFor("document.activeElement?.dataset.focusId==='mini-open'"))
                assertTrue(device.pressDPadCenter())
                assertTrue("Lyrics $route", web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))
                assertTrue(web.bool("!document.querySelector('[data-top-nav]') && !document.querySelector('[data-mini-player]')"))
                assertTrue(web.bool("(() => { const r=document.querySelector('[data-page=now-playing]').getBoundingClientRect(); return Math.abs(r.left)<2 && Math.abs(r.top)<2 && Math.abs(r.width-innerWidth)<2 && Math.abs(r.height-innerHeight)<2; })()"))
                assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_BACK))
                assertTrue("Return $route / $source; actual=" + web.activeFocusId(), web.waitFor(
                    "document.querySelector('[data-page=$route]') !== null && document.activeElement?.dataset.focusId===${JSONObject.quote(source)}"))
            }
            assertEquals("Opening lyrics must not restart, pause or change the queue", "[]", web.text("JSON.stringify(window.__chromePlaybackCommands)"))
        }
    }

    @Test fun fiveKeyFallbackTopNavigationAndDockHaveSeparateVisibleZones() {
        ProductionWebViewHarness.launch().use { web ->
            web.prepare()
            assertTrue(web.focus("[data-focus-id=nav-home]"))
            assertTrue(device.pressDPadRight())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='nav-explore'"))
            assertTrue(web.bool("document.querySelector('[data-page=home]') !== null"))
            assertTrue(device.pressDPadCenter())
            assertTrue(web.waitFor("document.querySelector('[data-page=explore]') !== null"))
            web.evaluate("location.hash='#/search'")
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-hot-7]') !== null"))
            assertTrue(web.focus("[data-focus-id=search-hot-7]"))
            assertTrue(device.pressDPadDown())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='mini-open'"))
            assertTrue(web.bool("""
                (() => {
                  const nav=document.querySelector('[data-top-nav]').getBoundingClientRect();
                  const content=document.querySelector('.content-surface').getBoundingClientRect();
                  const dock=document.querySelector('[data-mini-player]').getBoundingClientRect();
                  return nav.width>innerWidth*.8 && nav.bottom<=content.top && content.bottom<=dock.top
                    && dock.width>innerWidth*.8 && dock.bottom<=innerHeight;
                })()
            """.trimIndent()))
            web.capture("r44-search-dock-fixture.png")
            assertTrue(device.pressDPadUp())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='search-hot-7'"))
            assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_BACK))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='nav-search'"))
            assertTrue(device.pressDPadDown())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='search-hot-7'"))
            assertTrue(web.focus("[data-focus-id=nav-more]"))
            assertTrue(device.pressDPadRight())
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='nav-playing'"))
            assertTrue(device.pressDPadCenter())
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))
            web.capture("r44-lyrics-fixture.png")
            assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_BACK))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='search-hot-7'"))
            assertEquals("[]", web.text("JSON.stringify(window.__chromePlaybackCommands)"))
            web.evaluate("location.hash='#/home'")
            assertTrue(web.waitFor("document.querySelector('[data-page=home]') !== null"))
            assertTrue(web.focus("[data-focus-id=nav-home]"))
            web.capture("r44-topnav-home-fixture.png")
        }
    }
}
