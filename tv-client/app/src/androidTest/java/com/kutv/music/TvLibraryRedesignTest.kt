package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** New production UI with explicitly synthetic account/content, never real login acceptance. */
@RunWith(AndroidJUnit4::class)
class TvLibraryRedesignTest {
    private fun ProductionWebViewHarness.installLibraryProjection(failFirst: Boolean = false, paged: Boolean = false) {
        evaluate("""
            (() => {
              const bridge = window.EchoTvNative;
              const original = bridge.postMessage.bind(bridge);
              window.__libraryCalls = [];
              const reply = (request, payload, failed = false) => queueMicrotask(() => bridge.onmessage({data:JSON.stringify({
                version:1,type:'response',id:request.id,ok:!failed,
                ...(failed ? {error:{code:'NETWORK_ERROR',message:'Fixture offline',retryable:true,scope:'content'}} : {payload})
              })}));
              const names = ['夜晚，慢一点', '给生活留一首歌', '周末的房间', '城市漫游', '听见夏天', '独处的片刻', '漫长的告白', '出发，在路上'];
              const cards = Array.from({length:$paged ? 35 : names.length},(_,i) => ({id:'fixture-list-'+i,detailId:'playlist:library_'+(100+i),kind:'playlist',title:names[i % names.length]+' '+i,subtitle:'交互验收 · 示例歌单',artwork:''}));
              const track = {id:'tv_fixture_track',title:'让音乐填满这一刻',artist:'视觉验收',album:'示例内容 · 非真实推荐',artwork:'',durationMs:180000};
              const home = {greeting:'晚上好',hero:track,secondary:[],shelves:[{id:'fixture-shelf',title:'此刻，想听这些',capability:'play',tracks:names.map((title,i) => ({...track,id:'tv_fixture_'+i,title}))}]};
              bridge.postMessage = raw => {
                const request = JSON.parse(raw);
                if (request.method === 'app.getSnapshot') reply(request,{route:'home',session:{status:'loggedIn',displayName:'视觉验收'},bootstrapHome:home});
                else if (request.method === 'content.home') reply(request,home);
                else if (request.method === 'library.section' || request.method === 'library.playlists') {
                  window.__libraryCalls.push({method:request.method,...request.payload});
                  const page = Number(request.payload.cursor || 1);
                  const items = request.payload.section === 'favoriteSongs'
                    ? [{id:track.id,kind:'song',title:track.title,subtitle:track.artist,artwork:''}]
                    : $paged ? cards.slice((page-1)*30,page*30) : cards;
                  reply(request,{items,nextCursor:$paged && page===1 ? '2' : null,total:null,
                    librarySync:{page,received:items.length,playlists:items.length,created:0,collected:items.length,albums:0,missingCreator:items.length}},$failFirst && window.__libraryCalls.length === 1);
                } else if (request.method === 'content.detail' && $paged) {
                  reply(request,{id:request.payload.id.replace(/^playlist:/,''),kind:'playlist',title:'我的歌单',artwork:'',playCount:0,tags:[],introduction:'',trackCount:1,tabs:['songs'],tracks:{items:[track],nextCursor:null,total:1}});
                } else if (request.method === 'player.playQueue' && $paged) {
                  window.__libraryPlayRequest=request.payload;
                  reply(request,{accepted:true});
                } else original(raw);
              };
              window.location.hash = '#/search';
            })()
        """.trimIndent())
        assertTrue(waitFor("document.querySelector('[data-page=search]') !== null"))
        evaluate("window.location.hash = '#/home'")
        assertTrue(waitFor("document.querySelector('[data-focus-id=hero-play]')?.textContent.includes('聆听') === true && document.querySelector('.hero-copy')?.textContent.includes('示例内容') === true"))
    }

    private fun ProductionWebViewHarness.screenshot(name: String) {
        evaluate("window.__captureReady = false; requestAnimationFrame(() => requestAnimationFrame(() => { window.__captureReady = true; }))")
        assertTrue(waitFor("window.__captureReady === true"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(400L) // Allow the compositor to present the newly focused page.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    fun remoteMovesBetweenLargeNavigationTabsGridRowsAndActions() {
        ProductionWebViewHarness.launch().use { web ->
            web.installLibraryProjection()
            assertTrue(web.focus("[data-focus-id=hero-play]"))
            web.screenshot("r41-home-fixture.png")
            assertTrue(web.focus("[data-focus-id=nav-library]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('.playlist-card').length === 8"))
            assertTrue(web.focus("[data-focus-id=nav-library]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-tab-allPlaylists'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-card-fixture-list-0'"))
            web.screenshot("r41-library-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-card-fixture-list-1'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-card-fixture-list-5'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-card-fixture-list-1'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-tab-allPlaylists'"))
            assertTrue(web.bool("window.__libraryCalls[0].method === 'library.playlists'"))
            assertTrue(web.bool("document.querySelector('[data-focus-id=profile-logout]') === null"))
        }
    }

    @Test
    fun failedSyncIsNotEmptyAndConfirmRetriesWithoutMouse() {
        ProductionWebViewHarness.launch().use { web ->
            web.installLibraryProjection(failFirst = true)
            assertTrue(web.focus("[data-focus-id=nav-library]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=library]')?.textContent.includes('歌单同步失败') === true"))
            assertTrue(web.bool("!document.querySelector('[data-page=library]').textContent.includes('还没有收藏歌单')"))
            assertTrue(web.focus("[data-focus-id=library-tab-allPlaylists]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'library-action'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('.playlist-card').length === 8"))
            assertTrue(web.bool("window.__libraryCalls.length === 2"))
        }
    }

    @Test
    fun paginatedPlaylistPlayAndBackRetainsTheSameVisibleCardWithoutReload() {
        ProductionWebViewHarness.launch().use { web ->
            web.installLibraryProjection(paged = true)
            assertTrue(web.focus("[data-focus-id=nav-library]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('.playlist-card').length === 30"))
            assertTrue(web.focus("[data-focus-id=library-action]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelectorAll('.playlist-card').length === 35 && document.activeElement?.dataset.focusId === 'library-card-fixture-list-30'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            val detailReady = web.waitFor("document.querySelector('[data-page=playlist-detail]') && document.activeElement?.dataset.focusId === 'detail-primary'")
            assertTrue("detail ready=$detailReady focus=${web.activeFocusId()}", detailReady)
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'playlist-tv_fixture_track'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__libraryPlayRequest?.trackIds?.[0] === 'tv_fixture_track'"))
            assertTrue(web.waitFor("document.querySelector('[data-page=now-playing]') !== null"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('[data-page=playlist-detail]') !== null && document.activeElement?.dataset.focusId === 'playlist-tv_fixture_track'"))
            web.screenshot("r41-personal-playlist-fixture.png")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelectorAll('.playlist-card').length === 35 && document.activeElement?.dataset.focusId === 'library-card-fixture-list-30'"))
            assertTrue(web.bool("window.__libraryCalls.length === 2"))
            assertTrue(web.waitFor("(() => { const r = document.activeElement.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; })()"))
            web.screenshot("r41-library-return-fixture.png")
            assertTrue(web.focus("[data-focus-id=library-liked-entry]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'favorites-tab-songs' && document.querySelector('[data-focus-id=favorites-songs-tv_fixture_track]') !== null"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'favorites-songs-tv_fixture_track'"))
            web.evaluate("window.__libraryPlayRequest=null")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("window.__libraryPlayRequest?.trackIds?.[0] === 'tv_fixture_track'"))
        }
    }
}
