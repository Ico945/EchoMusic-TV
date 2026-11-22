package com.kutv.music.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAssetPathPolicyTest {
    @Test
    fun allowsOnlyThePackagedTvOrigin() {
        assertTrue(TvAssetPathPolicy.isAllowed("https://appassets.androidplatform.net/assets/tv-ui/tv.html"))
        assertFalse(TvAssetPathPolicy.isAllowed("https://example.com/index.html"))
        assertFalse(TvAssetPathPolicy.isAllowed("file:///android_asset/tv-ui/index.html"))
    }

    @Test
    fun acceptsAQueryOnThePackagedTvAsset() {
        assertTrue(
            TvAssetPathPolicy.isAllowed(
                "https://appassets.androidplatform.net/assets/tv-ui/tv.html?locale=zh-CN&v=1",
            ),
        )
    }

    @Test
    fun rejectsPathsThatEscapeOrNormalizeThePackagedSubtree() {
        listOf(
            "https://appassets.androidplatform.net/assets/tv-ui/../secret.html",
            "https://appassets.androidplatform.net/assets/tv-ui/%2e%2e/secret.html",
            "https://appassets.androidplatform.net/assets/tv-ui/./tv.html",
            "https://appassets.androidplatform.net/assets/tv-ui\\secret.html",
        ).forEach { url ->
            assertFalse(url, TvAssetPathPolicy.isAllowed(url))
        }
    }

    @Test
    fun rejectsAuthoritiesOtherThanTheExactPackagedOrigin() {
        listOf(
            "https://appassets.androidplatform.net:443/assets/tv-ui/tv.html",
            "https://appassets.androidplatform.net:/assets/tv-ui/tv.html",
            "https://user@appassets.androidplatform.net/assets/tv-ui/tv.html",
            "https://appassets.androidplatform.net.evil.example/assets/tv-ui/tv.html",
        ).forEach { url ->
            assertFalse(url, TvAssetPathPolicy.isAllowed(url))
        }
    }
}
