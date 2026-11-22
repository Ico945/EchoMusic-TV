package com.kutv.music

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvPerformanceSmokeTest {
    @Test
    fun routeChurnKeepsFocusLatencyUnderBudgetWithoutDeadEnds() {
        ProductionWebViewHarness.launch().use { web ->
            val routes = listOf("home", "explore", "ranking", "search", "library", "more", "profile", "home")
            val samples = mutableListOf<Long>()
            repeat(3) { round ->
                for (route in routes) {
                    web.evaluate("window.location.hash = \"#/$route\"")
                    assertTrue("route $route should render", web.waitFor("document.querySelector('[data-page=$route]') !== null", 15_000L))
                    assertTrue(
                        "route $route should expose a focusable target",
                        web.waitFor("Array.from(document.querySelectorAll('[data-focus-id]')).some(n => !n.hasAttribute('disabled'))", 15_000L),
                    )
                    // Use a guaranteed adjacent navigation target. A loading
                    // content page can legitimately have no right-hand target;
                    // route/content reachability is covered by TvFocusTraversalTest.
                    assertTrue("route $route should accept seeded focus", web.focus("[data-focus-id=nav-home]"))
                    assertTrue(
                        "route $route should hold seeded focus",
                        web.waitFor("document.activeElement?.dataset?.focusId === 'nav-home'", 8_000L),
                    )
                    // Focus feedback budget: keydown -> activeElement change must stay under 100ms median.
                    val started = SystemClock.elapsedRealtime()
                    val moved = web.bool(
                        "(() => { " +
                            "const before = document.activeElement; " +
                            "window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true })); " +
                            "return document.activeElement !== before && document.activeElement?.dataset?.focusId === 'nav-explore'; " +
                            "})()",
                    )
                    assertTrue("route $route round $round must move focus to the adjacent nav target", moved)
                    samples += SystemClock.elapsedRealtime() - started
                }
            }
            assertTrue("must record at least one focus move", samples.isNotEmpty())
            val median = samples.sorted()[samples.size / 2]
            android.util.Log.i("KU·TV-PERF", "focus_samples=${samples.size} median_ms=$median max_ms=${samples.maxOrNull()}")
            assertTrue("focus feedback median ${median}ms must stay under 100ms", median < 100)
        }
    }
}
