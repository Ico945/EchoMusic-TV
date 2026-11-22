package com.kutv.music

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic pagination in the shipped WebView, using the r48 production harness. */
@RunWith(AndroidJUnit4::class)
class TvR49PaginationFocusTest {
    private fun prepare(web: ProductionWebViewHarness) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        web.evaluate("""
            (()=>{
              const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
              const track=n=>({id:'r49-'+n,title:'分页测试 '+n,artist:'测试歌手',album:'',artwork:'',durationMs:180000});
              const first={items:[],tracks:Array.from({length:30},(_,i)=>track(i+1)),nextCursor:'2',total:32};
              const last={items:[],tracks:[track(31),track(32)],nextCursor:null,total:32};
              const reply=(r,payload)=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})});
              window.__r49PaginationRequests=[];
              window.__r49PaginationPending=null;
              window.__r49PaginationFinish=fail=>{
                const r=window.__r49PaginationPending;
                if(!r) throw new Error('No pending pagination request');
                window.__r49PaginationPending=null;
                if(fail) bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:false,error:{code:'NETWORK_TIMEOUT',message:'Pagination fixture offline'}})});
                else reply(r,last);
              };
              bridge.postMessage=raw=>{
                const r=JSON.parse(raw);
                if(r.method==='content.detail' && r.payload.kind==='artist') {
                  queueMicrotask(()=>reply(r,{id:'artist:pagination49',kind:'artist',title:'分页焦点验收',artwork:'',summary:'确定性测试数据',tabs:['songs','albums','mv'],hotTracks:[],children:{items:[],nextCursor:null,total:0}}));
                } else if(r.method==='content.detailChildren' && r.payload.kind==='artist' && r.payload.tab==='songs') {
                  if(r.payload.cursor) {window.__r49PaginationRequests.push(r);window.__r49PaginationPending=r;}
                  else queueMicrotask(()=>reply(r,first));
                } else return original(raw);
              };
            })()
        """.trimIndent())
        web.emitPlayerSnapshot(playing=false, queueSize=3)
        web.evaluate("location.hash='#/artist/artist:pagination49'")
        assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=artist]').length===30"))
        assertTrue(web.focus("[data-focus-id=artist-page-action]"))
        assertTrue(web.waitFor("document.querySelector('.content-surface').scrollTop>500"))
    }

    private fun begin(web: ProductionWebViewHarness) {
        web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(web.waitFor("window.__r49PaginationPending!==null"))
        assertEquals("artist-page-action",web.activeFocusId())
        assertTrue(web.bool("document.activeElement.getAttribute('aria-busy')==='true' && !document.activeElement.disabled"))
    }

    @Test fun delayedFailureRetryFinalPageAndDockUpKeepTheBottomPosition() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-r49-30'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            begin(web)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.bool("window.__r49PaginationRequests.length===1"))
            web.evaluate("window.__r49PaginationFinish(true)")
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-page-action' && document.activeElement.textContent.includes('重试')"))
            begin(web)
            assertTrue(web.bool("window.__r49PaginationRequests.length===2 && window.__r49PaginationRequests.every(r=>r.payload.cursor==='2')"))
            web.evaluate("window.__r49PaginationFinish(false)")
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-r49-31'"))
            assertTrue(web.bool("document.querySelector('[data-focus-id=artist-page-action]')===null && document.querySelectorAll('[data-focus-group=artist]').length===32"))
            assertTrue(web.bool("document.querySelector('.content-surface').scrollTop>500"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-r49-32'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='mini-open'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-r49-32'"))
        }
    }

    @Test fun movingToPlayerDuringLoadDoesNotStealFocusAndUpFindsNearbyContent() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            begin(web)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='mini-open'"))
            web.evaluate("window.__r49PaginationFinish(false)")
            assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=artist]').length===32 && !document.querySelector('[data-focus-id=artist-page-action]')"))
            assertEquals("mini-open",web.activeFocusId())
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement.dataset.focusGroup==='artist' && Number(document.activeElement.dataset.focusId.split('-').pop())>=29"))
            assertTrue(web.bool("document.querySelector('.content-surface').scrollTop>500"))
        }
    }
}
