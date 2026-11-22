package com.kutv.music

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in 4K display-mode checks; the platform may cap UI to 1080p. Never calls that native 4K UI. */
class Tv4kViewportTest {
    @Test fun realHomeFitsFourKAndFocusedLastCardClearsMiniPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutv4k") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        assertEquals("This gate requires a 4K physical display mode", 3840, mode.physicalWidth)
        assertEquals(2160, mode.physicalHeight)
        // Read the running framework's policy, not a hardcoded permissive
        // 1080p fallback. Google TV deliberately caps some UI configurations.
        @Suppress("DiscouragedApi")
        val capId = context.resources.getIdentifier("config_maxUiWidth", "integer", "android")
        val cap = if (capId == 0) 0 else context.resources.getInteger(capId)
        val expectedUiWidth = if (cap > 0) minOf(3840, cap) else 3840
        val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals("UI framebuffer must match the runtime framework policy", expectedUiWidth, screen.width)
            assertEquals(expectedUiWidth * 9 / 16, screen.height)
        } finally { screen.recycle() }
        ProductionWebViewHarness.launch().use { web ->
            assertTrue("Real home did not load", web.waitFor("document.querySelectorAll('.music-card').length >= 10", 15_000))
            assertTrue("Canvas does not fit the current WebView viewport", web.bool("""
                (() => { const r=document.querySelector('.tv-canvas').getBoundingClientRect();
                return Math.abs(r.left)<2 && Math.abs(r.top)<2 &&
                    Math.abs(r.right-innerWidth)<2 && Math.abs(r.bottom-innerHeight)<2 &&
                    Math.abs(r.width/r.height-16/9)<.01; })()
            """.trimIndent()))
            capture("kutv-4k-home")
            web.emitPlayerSnapshot(playing = false)
            assertTrue(web.waitFor("document.querySelector('[data-mini-player]') !== null"))
            val lastCard = web.text("Array.from(document.querySelectorAll('.music-card')).at(-1).dataset.focusId")
            assertTrue(web.focus("[data-focus-id=${org.json.JSONObject.quote(lastCard)}]"))
            assertTrue("Focused last card overlaps MiniPlayer or gets clipped", web.waitFor("""
                (() => { const n=document.activeElement, p=document.querySelector('[data-mini-player]');
                if(!n?.classList.contains('music-card') || !p) return false;
                const r=n.getBoundingClientRect(), m=p.getBoundingClientRect();
                const c=document.querySelector('.content-surface').getBoundingClientRect();
                return r.top>=c.top-1 && r.bottom<=m.top-8 && r.left>=c.left-1 && r.right<=c.right+1; })()
            """.trimIndent()))
            assertTrue("Strong focus scale missing", web.waitFor("""
                (() => { const s=new DOMMatrix(getComputedStyle(document.activeElement).transform).a;
                return s>=1.049 && s<=1.081; })()
            """.trimIndent()))
            capture("kutv-4k-last-card-mini")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("D-Pad must leave the last card for a lower action", web.waitFor(
                "!!document.activeElement?.dataset.focusId && document.activeElement.dataset.focusId !== ${org.json.JSONObject.quote(lastCard)}"))
            assertTrue("Bottom action remains hidden under MiniPlayer", web.waitFor("""
                (() => { const n=document.activeElement;
                if(n?.closest('[data-mini-player]')) return true;
                const r=n?.getBoundingClientRect(), m=document.querySelector('[data-mini-player]').getBoundingClientRect();
                return !!r && r.bottom<=m.top-8 && r.top>=0; })()
            """.trimIndent()))
            android.os.SystemClock.sleep(200) // Capture after the 120ms focus transition, not during it.
            capture("kutv-4k-bottom-action-mini")
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
