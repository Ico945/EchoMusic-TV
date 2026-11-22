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

/** Real production DOM and Android keys, but isolated command responses. Never mutates an account. */
@RunWith(AndroidJUnit4::class)
class TvInteractionPolishTest {
    private fun prepare(web: ProductionWebViewHarness, favorite: Boolean = false) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        web.evaluate("location.hash='#/search'")
        assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
        assertTrue(web.bool("""
            (() => {
              const pinia=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia;
              const search=pinia._s.get('tv-search');
              search.query='交互验收 · 测试数据'; search.submittedQuery=search.query; search.category='song'; search.submitted=true;
              search.loading=false; search.error=false; search.requiresLogin=false;
              search.results={items:[1,2,3].map(n=>({id:'polish'+n,kind:'song',title:'遥控验收歌曲 '+n,subtitle:'测试数据 · 不操作真实账号',artwork:''})),nextCursor:null,total:3};
              const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
              window.__polishCommands=[];
              bridge.postMessage=raw=>{
                const r=JSON.parse(raw);
                if(!['library.favorite','player.playNext'].includes(r.method)) return original(raw);
                window.__polishCommands.push({method:r.method,payload:r.payload});
                const payload=r.method==='library.favorite' ? {kind:r.payload.kind,id:r.payload.id,favorite:r.payload.favorite} : {accepted:true};
                queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})}));
              };
              pinia._s.get('tv-detail').favorite['song:polish1']=$favorite;
              return true;
            })()
        """.trimIndent()))
        assertTrue(web.waitFor("document.querySelector('[data-focus-id=search-song-polish1]') !== null"))
        assertTrue(web.focus("[data-focus-id=search-song-polish1]"))
    }

    private fun openMenu(web: ProductionWebViewHarness) {
        web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
        assertTrue(web.waitFor("document.querySelector('.song-menu') !== null"))
        web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
        assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='song-menu-favorite'"))
    }

    private fun capture(web: ProductionWebViewHarness, name: String) {
        web.evaluate("window.__polishFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__polishFrame=true))")
        assertTrue(web.waitFor("window.__polishFrame===true"))
        Thread.sleep(700)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun remoteMenuTrapsFocusAndExecutesExactlyOneNextTrackRequest() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            capture(web, "r45-search-fixture.png")
            openMenu(web)
            capture(web, "r45-song-menu-fixture.png")
            assertTrue(web.bool("!document.querySelector('[data-focus-id=song-menu-add-to-playlist]')"))
            repeat(3) {
                listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { key ->
                    web.dispatchKey(key)
                    assertTrue(web.bool("document.querySelector('.song-menu').contains(document.activeElement)"))
                }
            }
            assertTrue(web.focus("[data-focus-id=song-menu-favorite]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("song-menu-play-next", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__polishCommands.length===1 && !document.querySelector('.song-menu')"))
            assertTrue(web.bool("window.__polishCommands[0].method==='player.playNext' && window.__polishCommands[0].payload.trackId==='polish1'"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='search-song-polish1'"))
        }
    }

    @Test fun removingAFavoriteRequiresASeparateConfirmationAndBackKeepsIt() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web, favorite = true)
            openMenu(web)
            assertTrue(web.bool("document.querySelector('[data-focus-id=song-menu-favorite]').textContent.includes('取消收藏')"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='song-menu-cancel-remove'"))
            capture(web, "r45-favorite-confirm-fixture.png")
            assertEquals("0", web.text("window.__polishCommands.length"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='song-menu-favorite'"))
            assertEquals("0", web.text("window.__polishCommands.length"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='song-menu-cancel-remove'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("song-menu-confirm-remove", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__polishCommands.length===1 && !document.querySelector('.song-menu')"))
            assertTrue(web.bool("window.__polishCommands[0].method==='library.favorite' && window.__polishCommands[0].payload.favorite===false"))
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='search-song-polish1'"))
        }
    }

    @Test fun lyricsWakeWithDirectionOnlyAndDisplaySettingsReachThePlayer() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.emitPlayerSnapshot(playing = true)
            web.evaluate("location.hash='#/now-playing'")
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))
            web.evaluate("""
                (()=>{
                  const s=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s;
                  const settings=s.get('tv-settings'); settings.settings.lyricFontScale=140; settings.settings.lyricBackground='gradient'; settings.settings.reducedMotion=true;
                  window.__polishMedia=[];
                  const player=s.get('tv-player'); ['previous','next'].forEach(name=>player[name]=async()=>window.__polishMedia.push(name));
                })()
            """.trimIndent())
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]').dataset.background==='gradient'"))
            assertTrue(web.bool("getComputedStyle(document.querySelector('.tv-canvas')).getPropertyValue('--tv-lyric-scale').trim()==='1.4'"))
            assertTrue(web.bool("document.querySelector('.tv-canvas').classList.contains('tv-reduced-motion')"))
            capture(web, "r45-lyrics-large-fixture.png")
            assertTrue(web.waitFor("document.querySelector('[data-player-idle=true]') !== null", 15_000))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.querySelector('[data-player-idle=false]') !== null"))
            assertEquals("[]", web.text("JSON.stringify(window.__polishMedia)"))
            assertTrue(web.bool("document.activeElement?.dataset.focusId.startsWith('player-')"))
        }
    }
}
