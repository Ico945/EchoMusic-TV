package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvFocusTraversalTest {
    private fun ProductionWebViewHarness.goToHash(hash: String) {
        evaluate("window.location.hash = \"#$hash\"")
    }

    private fun ProductionWebViewHarness.dispatchKey(key: String): Boolean = bool(
        "(() => { " +
            "const target = document.activeElement || document.body; " +
            "const event = new KeyboardEvent('keydown', { key: '$key', bubbles: true, cancelable: true }); " +
            "return target.dispatchEvent(event); " +
            "})()",
    )

    @Test
    fun topNavigationPrioritizesListeningDiscoveryLibraryAndSearch() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(
                web.waitFor(
                    "Array.from(document.querySelectorAll('[data-nav-id]')).map(n => n.dataset.navId).join(',') === " +
                        "'home,explore,library,search,ranking,more'",
                ),
            )
        }
    }

    @Test
    fun primaryRoutesHaveNoFocusDeadEndsUnderDpadTraversal() {
        ProductionWebViewHarness.launch().use { web ->
            listOf(
                "home" to "home",
                "explore" to "explore",
                "fm" to "fm",
                "ranking" to "ranking",
                "search" to "search",
                "library" to "library",
                "profile" to "profile",
            ).forEach { (hash, marker) ->
                web.goToHash("/$hash")
                assertTrue(web.waitFor("document.querySelector('[data-page=$marker]') !== null"))
                assertTrue(web.waitFor("Array.from(document.querySelectorAll('[data-focus-id]')).some(n => !n.hasAttribute('disabled'))"))
                // The page settles its focus asynchronously (after its data request);
                // wait for a real focus target before pressing D-pad.
                assertTrue(
                    "route $hash must settle a focus target",
                    web.waitFor("!!(document.activeElement && document.activeElement.dataset && document.activeElement.dataset.focusId)", 12_000L),
                )
                repeat(8) {
                    web.dispatchKey("ArrowRight")
                }
                assertTrue(
                    "route $hash must keep a valid focus after traversal",
                    web.waitFor("!!(document.activeElement && document.activeElement.dataset && document.activeElement.dataset.focusId)"),
                )
            }
        }
    }

    @Test
    fun everyCurrentNonVideoRouteSettlesAnEligibleFocusTargetAndKeepsItDuringDpadTraversal() {
        val routes = listOf(
            "/home" to "home",
            "/explore" to "explore",
            "/daily-recommendations" to "daily-recommendations",
            "/fm" to "fm",
            "/ranking" to "ranking",
            "/ranking/ranking%3A1" to "ranking",
            "/search" to "search",
            "/playlist/playlist%3A1" to "playlist-detail",
            "/album/album%3A1" to "album-detail",
            "/artist/artist%3A1" to "artist-detail",
            "/song/tv_song_1" to "song-detail",
            "/now-playing" to "now-playing",
            "/library" to "library",
            "/library/favorites" to "library-favorites",
            "/library/history" to "library-history",
            "/library/cloud" to "library-cloud",
            "/library/purchased" to "library-purchased",
            "/profile" to "profile",
            "/share-resolve" to "share-resolve",
            "/more" to "more",
            "/settings" to "settings",
            "/recognize" to "recognize",
            "/plugin" to "plugin",
            "/about" to "about",
        )
        val eligibleFocus = "(() => {" +
            "const n=document.activeElement;" +
            "if(!n?.dataset?.focusId||n.disabled||n.hidden||n.closest('[inert]'))return false;" +
            "const s=getComputedStyle(n);" +
            "return s.display!=='none'&&s.visibility!=='hidden'&&n.getAttribute('aria-disabled')!=='true';" +
            "})()"

        ProductionWebViewHarness.launch().use { web ->
            routes.forEach { (hash, marker) ->
                web.evaluate("window.location.hash = '#$hash'")
                assertTrue("route $hash must render", web.waitFor("document.querySelector('[data-page=$marker]') !== null", 12_000L))
                assertTrue("route $hash must settle eligible focus", web.waitFor(eligibleFocus, 12_000L))
                listOf("ArrowRight", "ArrowDown", "ArrowLeft", "ArrowUp").forEach { key ->
                    web.dispatchKey(key)
                    assertTrue("route $hash lost focus after $key", web.waitFor(eligibleFocus))
                }
            }
        }
    }
}
