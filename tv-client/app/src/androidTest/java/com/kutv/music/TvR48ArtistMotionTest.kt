package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.application.playback.PlayerSnapshot
import com.kutv.music.bridge.mappers.BridgeTrackIds
import com.kutv.music.bridge.mappers.ContentJsonMapper
import com.kutv.music.bridge.mappers.PlayerJsonMapper
import com.kutv.music.core.model.ContentKind
import com.kutv.music.direct.content.DetailMapper
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native mapper -> shipped WebView -> Android D-pad. No real account mutations or streaming. */
@RunWith(AndroidJUnit4::class)
class TvR48ArtistMotionTest {
    private fun prepare(web: ProductionWebViewHarness) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        val raw = JSONObject().put("total", 45).put("data", JSONArray().apply {
            repeat(45) { index ->
                put(JSONObject().put("album_audio_id", 48001 + index).put("audio_name", "验收歌曲 " + (index + 1))
                    .put("author_name", "测试歌手").put("hash", "fixture-$index").put("timelength", 180000)
                    .put("sizable_cover", "https://" + (if (index % 2 == 0) "imge.kugou.com" else "singerimg.kugou.com") + "/400/fixture.jpg"))
            }
        })
        val tracks = DetailMapper.trackPage(raw).items
        assertEquals(45, tracks.size)
        val mapper = PlayerJsonMapper()
        val snapshots = JSONArray().apply { tracks.indices.forEach { index ->
            put(mapper.snapshot(PlayerSnapshot(currentTrack=tracks[index], queue=tracks, queueIndex=index,
                durationMs=180000, isPlaying=true, quality="128")))
        } }
        // Both hosts must reach the actual native-to-JS contract, not a hand-written approximation.
        assertTrue(snapshots.toString().contains("singerimg.kugou.com"))
        val songs = JSONObject().put("items", JSONArray()).put("tracks", JSONArray().apply {
            tracks.forEach { put(ContentJsonMapper.track(it)) }
        }).put("total", 45).put("nextCursor", JSONObject.NULL)
        fun cards(kind: ContentKind) = ContentJsonMapper.page(DetailMapper.cardPage(
            JSONObject("""{"total":2,"data":[
              {"video_id":48501,"album_id":48301,"album_audio_id":48001,"video_name":"现场 MV","album_name":"精选专辑","author_name":"测试歌手","hdpic":"https://imge.kugou.com/400/fixture.jpg"},
              {"video_id":48502,"album_id":48302,"album_audio_id":48001,"video_name":"官方 MV","album_name":"现场专辑","author_name":"测试歌手","hdpic":"https://imge.kugou.com/400/fixture.jpg"}
            ]}"""), kind, if (kind == ContentKind.MV) "mv:" else "album:"))
        web.evaluate("""
            (()=>{
              const bridge=window.EchoTvNative, original=bridge.postMessage.bind(bridge);
              window.__r48Snapshots=$snapshots; window.__r48Commands=[];
              window.__r48Ids=${JSONArray(tracks.map { BridgeTrackIds.publicId(it.id) })};
              const reply=(r,payload)=>bridge.onmessage({data:JSON.stringify({version:1,type:'response',id:r.id,ok:true,payload})});
              bridge.postMessage=raw=>{
                const r=JSON.parse(raw); let payload;
                if(r.method==='content.detail' && r.payload.kind==='artist') payload={id:'artist:fixture48',kind:'artist',title:'歌手回归验收',artwork:'',summary:'明确标记的测试数据 · 不操作真实账号',tabs:['songs','albums','mv'],hotTracks:[],children:{items:[],total:null,nextCursor:null}};
                else if(r.method==='content.detailChildren' && r.payload.kind==='artist') payload=r.payload.tab==='songs' ? $songs : r.payload.tab==='albums' ? ${cards(ContentKind.ALBUM)} : ${cards(ContentKind.MV)};
                else if(r.method==='player.playQueue') {
                  window.__r48Commands.push(r);
                  queueMicrotask(()=>{
                    reply(r,{accepted:true});
                    bridge.onmessage({data:JSON.stringify({version:1,type:'event',event:'player.snapshot',payload:window.__r48Snapshots[r.payload.startIndex]})});
                  });
                  return;
                }
                else if(r.method.startsWith('player.') && r.method!=='player.getSnapshot') payload={accepted:true};
                else if(r.method==='video.getSnapshot') payload={durationMs:180000,positionMs:0,playing:false,quality:'hd',errorCode:null,open:true,operation:null};
                else if(r.method==='video.close') payload={accepted:true};
                else if(r.method.startsWith('video.')) {window.__r48Commands.push(r);payload={accepted:true,quality:'hd'};}
                else return original(raw);
                queueMicrotask(()=>reply(r,payload));
              };
            })()
        """.trimIndent())
        web.emitPlayerSnapshot(playing=false, queueSize=3)
        web.evaluate("location.hash='#/artist/artist:fixture48'")
        assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=artist]').length===45"))
    }

    private fun capture(name: String) {
        Thread.sleep(250)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        }
        bitmap.recycle()
    }

    @Test fun artistSelectionReplacesOldNativeTitleDockAndQueue() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            assertTrue(web.bool("document.querySelector('[data-mini-player]').textContent.includes('Instrumented Song')"))
            val selected=BridgeTrackIds.publicId("48002")
            assertTrue(web.focus("[data-focus-id=artist-$selected]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            // Accepted playQueue opens the immersive player by existing product policy.
            assertTrue(web.waitFor("document.querySelector('[data-player-hero] h1')?.textContent==='验收歌曲 2'"))
            assertTrue(web.bool("window.__r48Commands[0].payload.startIndex===1 && JSON.stringify(window.__r48Commands[0].payload.trackIds)===JSON.stringify(window.__r48Ids)"))
            assertTrue(web.focus("[data-focus-id=player-queue]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('.player-drawer li').length===45"))
            assertTrue(web.bool("document.querySelector('.player-drawer [aria-current=true]').dataset.focusId==='queue-track-$selected'"))
            assertTrue(web.bool("!document.querySelector('.player-drawer').textContent.includes('Instrumented Song')"))
            capture("r48-artist-queue-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('.player-drawer')===null"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('[data-mini-player]')?.textContent.includes('验收歌曲 2')"))
        }
    }

    @Test fun nativeMappedAlbumsAndDistinctVideosOpenWithCorrectIdentityAndReturnFocus() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            assertTrue(web.focus("[data-focus-id=artist-tab-albums]")); web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=artist-albums]').length===2"))
            assertTrue(web.bool("document.querySelector('[data-page=artist-detail]').textContent.includes('精选专辑')"))
            assertTrue(web.focus("[data-focus-id=artist-tab-mv]")); web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('[data-focus-group=artist-mv]').length===2"))
            capture("r48-artist-mv-fixture.png")
            assertTrue(web.focus("[data-focus-id=artist-mv-0]")); web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail] h1')?.textContent==='现场 MV'"))
            assertTrue(web.bool("location.hash.includes('mv:48501') && document.querySelector('[data-page=mv-detail]').textContent.includes('测试歌手')"))
            assertTrue(web.waitFor("window.__r48Commands.some(r=>r.method==='video.open' && r.payload.mvId==='mv:48501')"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            val restored=web.waitFor("document.activeElement.dataset.focusId==='artist-mv-0'")
            assertTrue(web.text("JSON.stringify({route:location.hash,focus:document.activeElement?.dataset.focusId,page:document.querySelector('[data-page]')?.textContent})"),restored)
        }
    }

    @Test fun noBlurRetainsShortMotionButRepeatedDpadAndReducedMotionBypassIt() {
        ProductionWebViewHarness.launch().use { web ->
            prepare(web)
            web.evaluate("""
                (()=>{
                  const p=document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia;
                  p._s.get('tv-capabilities').capabilities.blur=false;
                  p._s.get('tv-settings').settings.reducedMotion=false;
                })()
            """.trimIndent())
            val first=BridgeTrackIds.publicId("48001")
            val last=BridgeTrackIds.publicId("48041")
            assertTrue(web.focus("[data-focus-id=artist-$first]"))
            assertTrue(web.waitFor("getComputedStyle(document.activeElement).transitionDuration.includes('0.11s')"))
            assertTrue(web.bool("getComputedStyle(document.querySelector('[data-page=artist-detail]')).animationName==='tv-page-enter'"))
            web.evaluate("""
                window.__r48BurstSeen=false;
                window.__r48Observer=new MutationObserver(()=>{
                  if(document.querySelector('.tv-canvas.tv-rapid-input')) window.__r48BurstSeen=true;
                });
                window.__r48Observer.observe(document.querySelector('.tv-canvas'),{attributes:true,attributeFilter:['class']});
            """.trimIndent())
            repeat(40) { web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.ACTION_DOWN,it) }
            web.dispatchKeyAction(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.ACTION_UP)
            assertTrue(web.waitFor("document.activeElement.dataset.focusId==='artist-$last'"))
            assertTrue(web.bool("window.__r48BurstSeen"))
            assertTrue(web.waitFor("!document.querySelector('.tv-canvas.tv-rapid-input')"))
            web.evaluate("window.__r48Observer.disconnect();document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-settings').settings.reducedMotion=true")
            assertTrue(web.waitFor("getComputedStyle(document.activeElement).transitionDuration==='0s'"))
            assertTrue(web.bool("getComputedStyle(document.querySelector('[data-page=artist-detail]')).animationName==='none'"))
            web.evaluate("document.querySelector('#app').__vue_app__.config.globalProperties.${'$'}pinia._s.get('tv-settings').settings.reducedMotion=false")
        }
    }
}
