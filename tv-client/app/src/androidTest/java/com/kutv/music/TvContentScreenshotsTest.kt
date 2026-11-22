package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvContentScreenshotsTest {
    private fun capture(web: ProductionWebViewHarness, hash: String, name: String, marker: String) {
        web.evaluate("window.location.hash = \"#$hash\"")
        assertTrue(web.waitFor("document.querySelector('[data-page=$marker]') !== null"))
        Thread.sleep(1_200L)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots")
            .apply { mkdirs() }
        val target = File(directory, "$name.png")
        assertTrue("screenshot $name saved", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(target))
    }

    @Test
    fun captureEveryApprovedContentRouteAtCurrentResolution() {
        ProductionWebViewHarness.launch().use { web ->
            listOf(
                Triple("home", "content-home", "home"),
                Triple("explore", "content-explore", "explore"),
                Triple("fm", "content-fm", "fm"),
                Triple("ranking", "content-ranking", "ranking"),
                Triple("search", "content-search", "search"),
                Triple("library", "content-library", "library"),
                Triple("library/favorites", "content-favorites", "library-favorites"),
                Triple("library/history", "content-history", "library-history"),
                Triple("library/cloud", "content-cloud", "library-cloud"),
                Triple("library/purchased", "content-purchased", "library-purchased"),
                Triple("profile", "content-profile", "profile"),
                Triple("share-resolve", "content-share-resolve", "share-resolve"),
                Triple("playlist/playlist:1", "content-playlist-detail", "playlist-detail"),
                Triple("album/album:1", "content-album-detail", "album-detail"),
                Triple("artist/artist:1", "content-artist-detail", "artist-detail"),
                Triple("song/tv_song_1", "content-song-detail", "song-detail"),
            ).forEach { (hash, name, marker) -> capture(web, "/$hash", name, marker) }
        }
    }
}
