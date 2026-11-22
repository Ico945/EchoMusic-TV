package com.kutv.music.web

import org.junit.Assert.*
import org.junit.Test

class TvPageRecoveryPolicyTest {
    @Test fun `remembers only the packaged document and public hash route`() {
        val policy = TvPageRecoveryPolicy()
        val route = TvWebViewHost.LOCAL_URL + "#/artist/artist%3A42?sourceRoute=home"
        policy.remember(route)
        assertEquals(route, policy.lastUrl)
        policy.remember(TvWebViewHost.LOCAL_URL + "#/mv/mv%3A42")
        assertEquals(route, policy.lastUrl)
        listOf(null, "https://example.com/#/home", "file:///android_asset/tv.html",
            TvWebViewHost.LOCAL_URL + "?token=secret", TvWebViewHost.LOCAL_URL + "#//example.com",
            TvWebViewHost.LOCAL_URL.replace("tv.html", "secret.html"),
            TvWebViewHost.LOCAL_URL.replace(".net/", ".net:443/"),
            TvWebViewHost.LOCAL_URL + "#/" + "x".repeat(4096)).forEach {
            assertFalse(TvPageRecoveryPolicy.isRestorable(it))
            policy.remember(it)
            assertEquals(route, policy.lastUrl)
        }
    }

    @Test fun `automatic recovery is bounded until a healthy frame or manual retry`() {
        val policy = TvPageRecoveryPolicy()
        repeat(3) { assertTrue(policy.beginRecovery()) }
        repeat(3) { assertFalse(policy.beginRecovery()) }
        policy.markHealthy()
        assertTrue(policy.beginRecovery())
    }
}
