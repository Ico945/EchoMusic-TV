package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvContentParityTest {
    private fun ProductionWebViewHarness.goToHash(hash: String): Boolean {
        val target = "\"#$hash\""
        evaluate("window.location.hash = $target")
        return true
    }

    private fun ProductionWebViewHarness.assertReachableRoute(hash: String, pageMarker: String) {
        assertTrue(goToHash(hash))
        assertTrue("route $hash should render", waitFor("document.querySelector('[data-page=$pageMarker]') !== null"))
        assertTrue(
            "route $hash must expose a focusable target",
            waitFor(
                "Array.from(document.querySelectorAll('[data-focus-id]')).some((node) => !node.hasAttribute('disabled'))",
            ),
        )
        assertTrue(
            "route $hash must have no unreachable active focus",
            bool(
                "document.activeElement === document.body || " +
                    "!!(document.activeElement && document.activeElement.dataset && document.activeElement.dataset.focusId)",
            ),
        )
    }

    @Test
    fun everyApprovedContentRouteIsReachableAndFocusable() {
        ProductionWebViewHarness.launch().use { web ->
            listOf(
                "explore" to "explore",
                "fm" to "fm",
                "ranking" to "ranking",
                "search" to "search",
                "library" to "library",
                "library/favorites" to "library-favorites",
                "library/history" to "library-history",
                "library/cloud" to "library-cloud",
                "library/purchased" to "library-purchased",
                "profile" to "profile",
                "share-resolve" to "share-resolve",
            ).forEach { (hash, marker) ->
                web.assertReachableRoute("/$hash", marker)
            }
        }
    }

    @Test
    fun detailRoutesRenderTheirSharedSkeletons() {
        ProductionWebViewHarness.launch().use { web ->
            listOf(
                "/playlist/playlist:1" to "playlist-detail",
                "/album/album:1" to "album-detail",
                "/artist/artist:1" to "artist-detail",
                "/song/tv_song_1" to "song-detail",
                "/ranking/rank:1" to "ranking",
            ).forEach { (hash, marker) ->
                web.assertReachableRoute(hash, marker)
            }
        }
    }
}
