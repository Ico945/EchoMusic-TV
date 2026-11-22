package com.kutv.music

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchFocusNavigationTest {
    @Test
    fun dpadMovesFromCurrentSearchDestinationToTheAdjacentProductionNavTarget() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.click("[data-focus-id=nav-search]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
            assertTrue(web.focus("[data-focus-id=nav-search]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals("nav-library", web.activeFocusId())
        }
    }
}
