package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvAccessibilityTest {
    @Test
    fun focusableControlsExposeTextualNamesAndStatesBeyondColor() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-page=home]') !== null"))
            assertTrue(
                web.waitFor(
                    "Array.from(document.querySelectorAll('[data-focus-id]')).filter(n => !n.hasAttribute('disabled')).length >= 3",
                ),
            )
            // Every focusable control must carry an accessible name: aria-label,
            // title, or non-empty text — never a color-only affordance.
            assertTrue(
                web.bool(
                    "Array.from(document.querySelectorAll('[data-focus-id]')).every(n => " +
                        "(n.getAttribute('aria-label') || n.getAttribute('title') || (n.textContent || '').trim()) !== '' && " +
                        "(n.getAttribute('aria-label') || n.getAttribute('title') || (n.textContent || '').trim()) !== null)",
                ),
            )
        }
    }

    @Test
    fun focusIndicatorUsesOutlineNotColorAlone() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=nav-home]') !== null"))
            assertTrue(web.focus("[data-focus-id=nav-home]"))
            // The focused style must add a box-shadow (spec: 2-3px high-contrast outline).
            assertTrue(
                web.waitFor(
                    "(() => { const el = document.activeElement; if (!el || !el.dataset || !el.dataset.focusId) return false; " +
                        "const style = getComputedStyle(el); return style.boxShadow !== 'none' || style.outlineStyle !== 'none'; })()",
                ),
            )
        }
    }
}
