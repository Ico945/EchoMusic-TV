package com.kutv.music

import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvRemoteInputAndBackTest {
    @Test
    fun allSupportedRemoteConfirmVariantsActivateTheFocusedProductionControl() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(
                web.bool(
                    "(() => {" +
                        "const probe=document.createElement('button');" +
                        "probe.dataset.focusId='remote-confirm-probe';" +
                        "probe.style.position='fixed'; probe.style.left='120px'; probe.style.top='120px';" +
                        "probe.addEventListener('click',()=>window.__remoteConfirmCount=(window.__remoteConfirmCount||0)+1);" +
                        "document.body.append(probe); probe.focus(); window.__remoteConfirmCount=0;" +
                        "return document.activeElement===probe;" +
                        "})()",
                ),
            )
            listOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_SELECT,
            ).forEachIndexed { index, keyCode ->
                web.dispatchKey(keyCode)
                assertTrue(
                    "confirm keyCode=$keyCode must activate exactly once",
                    web.waitFor("window.__remoteConfirmCount === ${index + 1}"),
                )
            }
        }
    }

    @Test
    fun searchFocusMakesTheProductionWebViewAnActiveAndroidImeTarget() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.click("[data-focus-id=nav-search]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
            assertTrue(web.focus("[data-focus-id=search-input]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'true'"))

            val deadline = SystemClock.uptimeMillis() + 5_000L
            var active = false
            while (!active && SystemClock.uptimeMillis() < deadline) {
                active = web.withActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java)?.isActive(web.webView) == true
                }
                if (!active) Thread.sleep(50L)
            }
            assertTrue("focused TV input must activate Android InputMethodManager", active)
        }
    }

    @Test
    fun nativeNumpadEnterReachesTheFocusedSearchInputAndSubmitsItsQuery() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.click("[data-focus-id=nav-search]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
            assertTrue(
                web.bool(
                    "(() => {" +
                        "const input=document.querySelector('[data-focus-id=search-input]');" +
                        "const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;" +
                        "setter.call(input,'若把你');" +
                        "input.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "input.focus();" +
                        "return document.activeElement===input;" +
                        "})()",
                ),
            )

            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'true'"))

            web.dispatchKey(KeyEvent.KEYCODE_NUMPAD_ENTER)

            assertTrue(
                "native Enter must invoke the focused input's search handler",
                web.waitFor("document.querySelector('[data-page=search]')?.dataset.searchSubmitted === 'true'", 12_000L),
            )
        }
    }

    @Test
    fun backClosesImeBeforeLeavingSearchAndRestoresVisibleFocus() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.click("[data-focus-id=nav-search]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
            assertTrue(web.focus("[data-focus-id=search-input]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(
                "search input focus must register the Web IME Back layer",
                web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'true'"),
            )

            web.dispatchKey(KeyEvent.KEYCODE_BACK)

            assertTrue(web.waitFor("document.querySelector('.tv-shell')?.dataset.backDispatchCount === '1'"))
            assertEquals("ime", web.text("document.querySelector('.tv-shell')?.dataset.lastBackLayer || ''"))
            assertTrue(web.waitFor("document.querySelector('[data-page=search]') !== null"))
            assertTrue(web.waitFor("document.querySelector('[data-page=search]')?.dataset.imeFocused === 'false'"))
            assertTrue(
                "IME close must retain a visible search control focus",
                web.waitFor("['search-input','search-submit'].includes(document.activeElement?.dataset?.focusId)"),
            )
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'nav-search' && document.querySelector('[data-page=search]') !== null"))
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('[data-page=home]') !== null"))
        }
    }
}
