package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellNavigationTest {
    @Test
    fun productionShellExposesEveryCurrentDestinationAndAccountControl() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-page=home]') !== null"))
            assertEquals(
                "[\"home\",\"explore\",\"fm\",\"ranking\",\"library\",\"search\",\"more\"]",
                web.text("JSON.stringify([...document.querySelectorAll('[data-nav-id]')].map(node => node.dataset.navId))"),
            )
            listOf("home", "explore", "fm", "ranking", "library", "search", "more").forEach { page ->
                assertTrue(web.click("[data-focus-id=nav-$page]"))
                assertTrue(page, web.waitFor("document.querySelector('[data-page=$page]') !== null"))
            }
            assertTrue(web.bool("document.querySelector('[data-focus-id=account]')?.textContent.trim() === '登录'"))
        }
    }

    @Test
    fun miniPlayerAppearsOnlyAfterAnExactNativePlayerSnapshot() {
        ProductionWebViewHarness.launch().use { web ->
            assertFalse(web.bool("document.querySelector('[data-mini-player]') !== null"))
            web.emitPlayerSnapshot()
            assertTrue(web.waitFor("document.querySelector('[data-mini-player]') !== null"))
            assertTrue(web.bool("document.querySelector('[data-mini-player]')?.textContent.includes('Instrumented Song')"))
        }
    }
}
