package com.kutv.music

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/** Real WebView and Android D-pad, synthetic video state; no account writes. */
class TvR50RemoteChromeTest {
    @Test fun mvIdlesWhilePollingAndConfirmWakesWithoutPausing() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("""
                (() => {
                  const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
                  window.__r50Toggles=0; window.__r50Playing=true;
                  bridge.postMessage=raw=>{
                    const r=JSON.parse(raw);
                    if(!r.method.startsWith('video.')) return original(raw);
                    if(r.method==='video.toggle'){window.__r50Toggles++;window.__r50Playing=!window.__r50Playing}
                    const payload=r.method==='video.snapshot'
                      ? {durationMs:180000,positionMs:Date.now()%180000,playing:window.__r50Playing,quality:'hd',errorCode:null,open:true,operation:null}
                      : r.method==='video.close'?{accepted:true}:{accepted:true,quality:'hd'};
                    queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})}));
                  };
                  location.hash='#/mv/mv:50?title=Idle-test';
                })()
            """.trimIndent())
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=mv-toggle]')!==null"))
            assertTrue(web.focus("[data-focus-id=mv-toggle]"))
            assertTrue("Polling must not keep chrome visible", web.waitFor("document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle==='true'", 7000))
            assertTrue(web.bool("document.activeElement?.dataset.page==='mv-detail'"))
            assertTrue(web.bool("document.querySelector('.mv-control-layer').inert"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='mv-toggle' && document.querySelector('[data-page=mv-detail]').dataset.mvIdle==='false'"))
            assertTrue(web.bool("window.__r50Toggles===0"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__r50Toggles===1 && !window.__r50Playing"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            val returned = web.waitFor("document.querySelector('[data-page=home]')!==null")
            assertTrue(web.text("JSON.stringify({route:location.hash,focus:document.activeElement?.dataset.focusId,page:document.querySelector('[data-page]')?.dataset.page,dialogs:[...document.querySelectorAll('[role=dialog]')].map(e=>({text:e.textContent.slice(0,100),display:getComputedStyle(e).display}))})"), returned)
        }
    }

    @Test fun removedFmLeavesNoDirectionalHoleInTopNav() {
        ProductionWebViewHarness.launch().use { web ->
            assertFalse(web.bool("document.querySelector('[data-focus-id=nav-fm]')!==null"))
            assertTrue(web.focus("[data-focus-id=nav-search]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='nav-ranking'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='nav-more'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='nav-ranking'"))
            assertTrue(web.bool("document.querySelector('[data-page=home]')!==null"))
        }
    }
}
