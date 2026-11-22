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

/** Shipped SVG components in the real WebView. No account or audio writes. */
@RunWith(AndroidJUnit4::class)
class TvIconPolishTest {
    private fun prepare(web: ProductionWebViewHarness) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        web.evaluate("""
            (()=>{
              const b=window.EchoTvNative, original=b.postMessage.bind(b);
              window.__iconCommands=[];
              b.postMessage=raw=>{
                const r=JSON.parse(raw);
                if(!r.method.startsWith('player.') || r.method==='player.getSnapshot')return original(raw);
                window.__iconCommands.push(r.method);
                queueMicrotask(()=>b.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload:{accepted:true}})}));
              };
            })()
        """.trimIndent())
        web.emitPlayerSnapshot(playing = false, queueSize = 3)
        web.evaluate("""
            (()=>{
              const p=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-player');
              p.currentTrack.qualities=['128','320','flac','hires'];p.quality='320';
              p.currentTrack.title='图标与遥控验收';p.currentTrack.artists=['测试数据 · 不操作真实账号'];
              location.hash='#/now-playing';
            })()
        """.trimIndent())
        assertTrue(web.waitFor("document.querySelector('[data-focus-id=player-toggle]')!==null"))
    }

    private fun capture(web: ProductionWebViewHarness, name: String) {
        web.evaluate("window.__iconFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__iconFrame=true))")
        assertTrue(web.waitFor("window.__iconFrame===true"))
        Thread.sleep(400)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrument.uiAutomation.takeScreenshot())
        File(instrument.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun transportAndSecondaryIconsFitAndKeepRemoteLabels() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            assertTrue(web.focus("[data-focus-id=player-toggle]"))
            assertTrue(web.bool("""
                (()=>{
                  const controls=[...document.querySelectorAll('.control-row button')];
                  const ids=controls.map(b=>b.dataset.focusId);
                  return controls.length===7 && controls.every(b=>{
                    const s=b.querySelector('svg'),r=b.getBoundingClientRect();
                    return s && s.getAttribute('aria-hidden')==='true' && s.getAttribute('focusable')==='false'
                      && !s.hasAttribute('tabindex') && b.getAttribute('aria-label') && r.left>=0 && r.right<=innerWidth;
                  }) && ['previous','play','next'].every(name=>document.querySelector('.control-row [data-icon='+name+']').getAttribute('data-icon-paint')==='solid');
                })()
            """.trimIndent()))
            capture(web, "r47-player-icons.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("player-next", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("player-repeat", web.activeFocusId())
            assertTrue(web.bool("document.querySelector('[data-focus-id=player-repeat] [data-icon=ordered]')!==null"))
            assertTrue(web.focus("[data-focus-id=mode-cover]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]').dataset.mode==='cover'"))
            assertTrue(web.bool("document.querySelector('[data-focus-id=mode-cover] [data-icon=cover]')!==null"))
            assertEquals("0", web.text("window.__iconCommands.length"))
        }
    }

    @Test fun drawerGridIsStableWhenSelectionChangesAndBackRestoresSource() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            assertTrue(web.focus("[data-focus-id=player-quality]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='quality-320'"))
            assertTrue(web.bool("""
                (()=>{
                  const buttons=[...document.querySelectorAll('.choices button')];
                  window.__choiceGeometry=()=>JSON.stringify([...document.querySelectorAll('.choices button')].map(b=>[b.offsetLeft,b.offsetTop,b.offsetWidth,b.offsetHeight]));
                  window.__choiceBefore=window.__choiceGeometry();
                  return buttons.length===4 && buttons[0].offsetTop===buttons[1].offsetTop
                    && buttons[2].offsetTop===buttons[3].offsetTop && buttons[2].offsetTop>buttons[0].offsetTop
                    && buttons.every(b=>b.scrollWidth<=b.clientWidth);
                })()
            """.trimIndent()))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("quality-128", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("quality-flac", web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("quality-hires", web.activeFocusId())
            web.evaluate("document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-player').quality='flac'")
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=quality-flac] [data-icon=check]')!==null"))
            assertTrue(web.bool("window.__choiceBefore===window.__choiceGeometry()"))
            capture(web, "r47-quality-icons.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='player-quality'"))
            assertTrue(web.focus("[data-focus-id=player-sound-effect]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=sound-effect-vocal]')!==null"))
            assertTrue(web.focus("[data-focus-id=sound-effect-vocal]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=sound-effect-vocal] [data-icon=check]')!==null"))
            assertTrue(web.bool("[...document.querySelectorAll('.choices button')].every(b=>b.scrollWidth<=b.clientWidth)"))
            capture(web, "r47-sound-icons.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='player-sound-effect'"))
            assertEquals("1",web.text("window.__iconCommands.length"))
        }
    }
}
