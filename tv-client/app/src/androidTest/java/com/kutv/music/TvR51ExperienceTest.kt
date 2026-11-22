package com.kutv.music

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/** Android key dispatch and shipped WebView. Fixture playback is not live-account evidence. */
class TvR51ExperienceTest {
    @Test fun rapidMvTabDownHandsFirstPageLoadingFocusToVisibleContent() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("""
                (()=>{
                  const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
                  bridge.postMessage=raw=>{
                    const r=JSON.parse(raw);
                    if(!['content.detail','content.detailChildren'].includes(r.method)) return original(raw);
                    const reply=payload=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})});
                    if(r.method==='content.detail') return queueMicrotask(()=>reply({kind:'artist',id:'artist:r51-loading',title:'Loading artist',artwork:'',summary:'',tabs:['songs','albums','mv'],hotTracks:[],children:{items:[],nextCursor:null,total:0}}));
                    if(r.payload.tab==='mv') {
                      window.__r51FinishMv=()=>reply({items:[{id:'mv:51',kind:'mv',title:'First MV',subtitle:'Artist',artwork:''}],nextCursor:'2',total:30});
                      return;
                    }
                    queueMicrotask(()=>reply({items:[],nextCursor:null,total:0}));
                  };
                  location.hash='#/artist/artist:r51-loading';
                })()
            """.trimIndent())
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=artist-tab-mv]')"))
            assertTrue(web.focus("[data-focus-id=artist-tab-mv]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("typeof window.__r51FinishMv==='function'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='artist-page-action'"))
            web.evaluate("window.__r51FinishMv()")
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='artist-mv-0'"))
            assertTrue(web.bool("document.activeElement.getBoundingClientRect().top >= 0 && document.activeElement.getBoundingClientRect().bottom <= innerHeight"))
        }
    }
    @Test fun bundledLicenseIsReadableWithoutOpeningWebNetworkAccess() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("location.hash='#/about'")
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=about-license-gpl]')"))
            web.focus("[data-focus-id=about-license-gpl]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('.license-reader')?.textContent.includes('GNU GENERAL PUBLIC LICENSE')"))
            assertEquals("about-license-text", web.activeFocusId())
            assertTrue("License geometry must not grow below the content viewport after loading", web.bool("document.querySelector('.license-reader').getBoundingClientRect().bottom <= document.querySelector('.content-surface').getBoundingClientRect().bottom"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.bool("document.querySelector('.license-reader').scrollTop>0"))
        }
    }
    @Test fun keyboardRetainsCanvasWidthAndBackReturnsToSearch() {
        ProductionWebViewHarness.launch().use { web ->
            web.click("[data-focus-id=nav-search]")
            assertTrue(web.waitFor("!!document.querySelector('[data-page=search]')"))
            web.evaluate("window.__r51CanvasWidth=document.querySelector('.tv-canvas').getBoundingClientRect().width")
            assertTrue(web.focus("[data-focus-id=search-input]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=search]').dataset.imeFocused==='true'"))
            Thread.sleep(600)
            assertTrue(web.bool("Math.abs(document.querySelector('.tv-canvas').getBoundingClientRect().width-window.__r51CanvasWidth)<2"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='search-submit'"))
            assertTrue(web.bool("!!document.querySelector('[data-page=search]')"))
        }
    }

    @Test fun albumRetainsChildrenAcrossFivePlayerRoundTrips() {
        ProductionWebViewHarness.launch().use { web ->
            web.emitPlayerSnapshot(queueSize = 2, playing = false)
            web.evaluate("""
                (()=>{
                  const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
                  window.__r51DetailMethods=[];
                  bridge.postMessage=raw=>{
                    const r=JSON.parse(raw);
                    if(!['content.detail','content.detailChildren'].includes(r.method)) return original(raw);
                    window.__r51DetailMethods.push(r.method);
                    const payload=r.method==='content.detail'
                      ? {kind:'album',id:'r51',title:'Return test',artwork:'',artist:'Artist',publishDate:'',introduction:'',tabs:['songs','comments'],tracks:{items:[],nextCursor:null,total:null}}
                      : {items:[1,2,3,4].map(n=>({id:'r51-'+n,title:'Song '+n,artist:'Artist',album:'',artwork:'',durationMs:1000})),nextCursor:null,total:4};
                    queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})}));
                  };
                  location.hash='#/album/album:r51?sourceSection=search';
                })()
            """.trimIndent())
            val loaded = web.waitFor("document.querySelectorAll('[data-focus-group=album]').length===4")
            assertTrue(web.text("JSON.stringify({route:location.hash,methods:window.__r51DetailMethods,text:document.body.innerText.slice(0,3000)})"), loaded)
            repeat(5) {
                assertTrue(web.focus("[data-focus-id=nav-playing]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(web.waitFor("!!document.querySelector('[data-page=now-playing]')"))
                web.dispatchKey(KeyEvent.KEYCODE_BACK)
                assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=album]').length===4"))
                assertTrue(web.bool("document.querySelector('[data-focus-id=nav-search]').getAttribute('aria-current')==='page'"))
            }
        }
    }

    @Test fun playerMoreOpensAndCanNavigateAfterItsModalUnmounts() {
        ProductionWebViewHarness.launch().use { web ->
            web.emitPlayerSnapshot(queueSize = 2, playing = false)
            web.click("[data-focus-id=mini-open]")
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=player-more]')"))
            web.focus("[data-focus-id=player-more]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='song-menu-favorite'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='player-more'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=song-menu-detail]')"))
            web.focus("[data-focus-id=song-menu-detail]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("!!document.querySelector('[data-page=song-detail]')"))
        }
    }

    @Test fun mvQualityMenuKeepsPlaybackVisibleAndBackClosesOnlyMenu() {
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("""
                (()=>{
                  const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
                  bridge.postMessage=raw=>{
                    const r=JSON.parse(raw);
                    if(!r.method.startsWith('video.')) return original(raw);
                    const payload=r.method==='video.snapshot'
                      ? {durationMs:180000,positionMs:1000,playing:true,quality:'sd',availableQualities:['sd','ld'],errorCode:null,open:true,operation:null}
                      : r.method==='video.close'?{accepted:true}:{accepted:true,quality:'sd'};
                    queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})}));
                  };
                  location.hash='#/mv/mv:r51';
                })()
            """.trimIndent())
            assertTrue(web.waitFor("!!document.querySelector('[data-focus-id=mv-quality-menu]')"))
            web.focus("[data-focus-id=mv-progress]")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='mv-toggle'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='mv-quality-menu'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='mv-quality-sd'"))
            Thread.sleep(4500)
            assertTrue(web.bool("document.querySelector('[data-page=mv-detail]').dataset.mvIdle==='false'"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId==='mv-quality-menu'"))
            assertTrue(web.bool("!!document.querySelector('[data-page=mv-detail]') && document.querySelector('.mv-quality-panel').style.display==='none'"))
        }
    }
}
