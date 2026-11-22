package com.kutv.music

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.core.model.LibrarySection
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in production endpoints; no response injection and no credential export. */
class TvContentExpansionLiveTest {
    @Test fun realPublicCollectionsAndRemoteCategoryNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugou") == "true")
        ProductionWebViewHarness.launch().use { web ->
            assertTrue("Real home collections unavailable", web.waitFor("document.querySelectorAll('.collection-shelf .media-card').length > 0", 25_000L))
            assertTrue(web.waitFor("Array.from(document.querySelectorAll('.home-page img')).filter(img => img.complete && img.naturalWidth > 0).length >= 3", 15_000L))
            capture(web, "r39-home-live.png")
            web.evaluate("location.hash = '#/explore'")
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=explore-newPlaylists-0]') !== null", 20_000L))
            assertTrue(web.focus("[data-focus-id=explore-newPlaylists-0]"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'nav-explore'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("Top navigation must restore the last content card", web.waitFor("document.activeElement?.dataset.focusId === 'explore-newPlaylists-0'"))
            assertTrue(web.focus("[data-focus-id=explore-tab-newPlaylists]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=explore-tab-newPlaylists]')?.getAttribute('aria-pressed') === 'true'"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'explore-newPlaylists-0'"))
            assertTrue(web.waitFor("Array.from(document.querySelectorAll('.explore-page img')).some(img => img.complete && img.naturalWidth > 0)", 15_000L))
            capture(web, "r39-discover-playlists-live.png")
            assertTrue(web.click("[data-focus-id=explore-tab-artists]"))
            assertTrue("Artist shelf is no longer a placeholder", web.waitFor("document.querySelectorAll('[data-focus-group=explore-artists]').length > 0", 15_000L))
            assertTrue("Real artist artwork must survive mapping and the production policy", web.waitFor("Array.from(document.querySelectorAll('.explore-page img')).some(img => img.complete && img.naturalWidth > 0)", 15_000L))
            assertTrue(web.focus("[data-focus-id=explore-artists-0]"))
            capture(web, "r39-discover-artists-live.png")
        }
    }

    @Test fun signedInAccountLibraryIsReadableWithoutMovingCredentials() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveLibrary") == "true")
        AppGraph.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue("This isolated debug install needs its own real QR login", AppGraph.credentialStore.loadCredentials() != null)
        for (section in listOf(LibrarySection.CREATED_PLAYLISTS, LibrarySection.FAVORITE_PLAYLISTS, LibrarySection.FAVORITE_SONGS)) {
            val result = AppGraph.tvContentRepository.librarySection(section, null)
            assertTrue("Account section ${section.name} failed: ${result.exceptionOrNull()?.javaClass?.simpleName}", result.isSuccess)
            val page = result.getOrThrow()
            android.util.Log.i("KUTV_LIBRARY_LIVE", "${section.name}: mapped=${page.items.size}, hasMore=${page.nextCursor != null}")
        }
    }

    private fun capture(web: ProductionWebViewHarness, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        web.evaluate("window.__captureReady = false; requestAnimationFrame(() => requestAnimationFrame(() => window.__captureReady = true))")
        assertTrue(web.waitFor("window.__captureReady === true"))
        instrumentation.waitForIdleSync()
        Thread.sleep(400L)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
