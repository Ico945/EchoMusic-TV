package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real shipped WebView and Android keys; all playback commands use labelled fixtures. */
@RunWith(AndroidJUnit4::class)
class TvR46FeedbackTest {
    private fun prepare(web: ProductionWebViewHarness) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        web.evaluate("""
            (()=>{
              const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
              window.__r46Commands=[];
              window.__r46Tracks=Array.from({length:400},(_,n)=>({id:'r46_'+n,title:'遥控验收歌曲 '+(n+1),artist:'测试数据 · 不操作真实账号',album:'',artwork:'',durationMs:180000,qualities:['128']}));
              bridge.postMessage=raw=>{
                const r=JSON.parse(raw); let payload;
                if(r.method==='content.hotSearch') payload={terms:['轻音乐','电影原声']};
                else if(r.method==='content.detail' && r.payload.kind==='artist') payload={id:'artist:fixture',kind:'artist',title:'歌手详情验收',artwork:'',summary:'测试数据 · 400 首歌曲',tabs:['songs','albums','mv'],hotTracks:[],children:{items:[],total:null,nextCursor:null}};
                else if(r.method==='content.detailChildren' && r.payload.kind==='artist') payload={items:[],tracks:window.__r46Tracks,nextCursor:null,total:400};
                else if(r.method.startsWith('player.') && r.method!=='player.getSnapshot') {window.__r46Commands.push({method:r.method,payload:r.payload});payload={accepted:true};}
                else return original(raw);
                queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})}));
              };
            })()
        """.trimIndent())
        web.emitPlayerSnapshot(playing = false, queueSize = 3)
        assertTrue(web.waitFor("document.querySelector('[data-focus-id=mini-next]') !== null"))
    }

    private fun capture(web: ProductionWebViewHarness, name: String) {
        web.evaluate("window.__r46Frame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__r46Frame=true))")
        assertTrue(web.waitFor("window.__r46Frame===true"))
        Thread.sleep(500)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val revision = Regex("r\\d+").find(BuildConfig.VERSION_NAME)?.value ?: "current"
        File(instrumentation.targetContext.getExternalFilesDir(null), name.replaceFirst("r46", revision)).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        }
        bitmap.recycle()
    }

    @Test fun tabsRequireConfirmationAndRapidMovementDoesNotActivateAnotherTab() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.evaluate("location.hash='#/search'")
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-hot-1]') !== null"))
            assertTrue(web.focus("[data-focus-id=nav-search]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("nav-library",web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.bool("location.hash==='#/search' && document.activeElement.closest('[data-page=search]')!==null"))
            repeat(5) {
                assertTrue(web.focus("[data-focus-id=nav-library]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(web.waitFor("location.hash==='#/library' && document.activeElement.closest('[data-page=library]')!==null"))
                assertTrue(web.focus("[data-focus-id=nav-search]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(web.waitFor("location.hash==='#/search'"))
            }
            assertTrue(web.focus("[data-focus-id=nav-search]"))
            repeat(20) {
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT)
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            assertTrue(web.waitFor("location.hash==='#/search' && document.activeElement.dataset.focusId==='nav-search'"))
            capture(web,"r46-tabs-fixture.png")
        }
    }

    @Test fun dockHasFiveLinkedControlsAndQualityRemainsNativeAuthoritative() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.evaluate("location.hash='#/search'")
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-hot-1]') !== null"))
            assertTrue(web.focus("[data-focus-id=search-hot-1]"))
            web.dispatchKey(KeyEvent.KEYCODE_MENU)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='mini-open'"))
            for(id in listOf("mini-previous","mini-toggle","mini-next","mini-details")) {
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(web.waitFor("document.activeElement.dataset.focusId==='$id'"))
            }
            assertTrue(web.focus("[data-focus-id=mini-next]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__r46Commands.length===1"))
            assertEquals("player.next",web.text("window.__r46Commands[0].method"))
            assertTrue(web.bool("Array.from(document.querySelectorAll('[data-mini-player] button')).every(n=>{const r=n.getBoundingClientRect();return r.left>=0 && r.right<=innerWidth && r.width>0})"))
            capture(web,"r46-dock-fixture.png")
            web.evaluate("""
                (()=>{
                  const p=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-player');
                  p.currentTrack.qualities=['128','320','flac','hires'];p.quality='320';
                  location.hash='#/now-playing';
                })()
            """.trimIndent())
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=player-quality]')!==null"))
            assertTrue(web.focus("[data-focus-id=player-quality]"));web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='quality-320'"))
            assertTrue(web.focus("[data-focus-id=quality-flac]"));web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__r46Commands.length===2"))
            assertTrue(web.bool("window.__r46Commands[1].method==='player.setQuality' && window.__r46Commands[1].payload.quality==='flac'"))
            assertTrue(web.bool("document.querySelector('[data-focus-id=quality-320]').getAttribute('aria-pressed')==='true'"))
            capture(web,"r46-quality-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='player-quality'"))
        }
    }

    @Test fun artistSongsLoadAndRepeatedDpadKeepsTheLongListVisible() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.evaluate("location.hash='#/artist/artist:fixture'")
            assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=artist]').length===400"))
            assertTrue(web.bool("!document.querySelector('[data-page=artist-detail]').textContent.includes('暂无歌曲')"))
            assertTrue(web.focus("[data-focus-id=artist-r46_0]"))
            capture(web,"r46-artist-fixture.png")
            repeat(40) { web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.ACTION_DOWN,it) }
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.ACTION_UP)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-r46_40'"))
            assertTrue(web.bool("getComputedStyle(document.querySelector('.content-surface')).scrollBehavior==='auto'"))
            assertTrue(web.bool("(()=>{const r=document.activeElement.getBoundingClientRect(),s=document.querySelector('.content-surface').getBoundingClientRect();return r.top>=s.top && r.bottom<=s.bottom})()"))
            assertTrue(web.focus("[data-focus-id=artist-r46_399]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='mini-open'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-r46_399'"))
            assertEquals("0",web.text("window.__r46Commands.length"))
        }
    }
}
