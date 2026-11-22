package com.kutv.music

import android.util.Log
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measures rendered page padding in Android dp, not unscaled design CSS pixels. */
class TvContentInsetTest {
    @Test fun everyScrollingRouteReservesAtLeast120AndroidDp() {
        ProductionWebViewHarness.launch().use { web ->
            val routes = listOf(
                "home" to "home", "explore" to "explore", "fm" to "fm",
                "ranking" to "ranking", "ranking/1" to "ranking",
                "search" to "search", "library" to "library",
                "library/favorites" to "library-favorites", "library/history" to "library-history",
                "library/cloud" to "library-cloud", "library/purchased" to "library-purchased",
                "profile" to "profile", "share-resolve" to "share-resolve",
                "playlist/playlist:1" to "playlist-detail", "album/album:1" to "album-detail",
                "artist/artist:1" to "artist-detail", "song/tv_song_1" to "song-detail",
                "more" to "more", "settings" to "settings", "about" to "about",
                "daily-recommendations" to "daily-recommendations",
                "recognize" to "recognize", "plugin" to "plugin",
            )
            val (nativeWidth, density) = web.withActivity {
                web.webView.width to it.resources.displayMetrics.density
            }
            assertTrue(nativeWidth > 0 && density > 0)
            routes.forEach { (route, marker) ->
                web.evaluate("location.hash = ${JSONObject.quote("#/$route")}")
                assertTrue("Missing page: $marker", web.waitFor(
                    "document.querySelector('.content-surface > [data-page=${JSONObject.quote(marker)}]') !== null"))
                val metrics = web.evaluate("""
                    (() => { const p=document.querySelector('.content-surface > [data-page]');
                    const c=document.querySelector('.tv-canvas');
                    return { padding:parseFloat(getComputedStyle(p).paddingBottom),
                      scale:c.getBoundingClientRect().width/c.offsetWidth, viewport:innerWidth }; })()
                """.trimIndent()) as JSONObject
                val dp = metrics.getDouble("padding") * metrics.getDouble("scale") *
                    nativeWidth / metrics.getDouble("viewport") / density
                Log.i("TvContentInset", "page=$marker paddingDp=$dp density=$density metrics=$metrics")
                assertTrue("$marker bottom inset is ${dp}dp; at least 120dp required ($metrics)", dp >= 119.99)
            }
        }
    }
}
