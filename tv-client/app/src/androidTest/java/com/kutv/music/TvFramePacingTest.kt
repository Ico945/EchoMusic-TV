package com.kutv.music

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.application.capability.AndroidVisualEffectsPort
import java.util.Collections
import kotlin.math.ceil
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvFramePacingTest {
    @Test
    fun optionalNavigationCompositingComparison() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvProfileCompositing") == "true")
        ProductionWebViewHarness.launch().use { web ->
            assumeFalse(web.withActivity { AndroidVisualEffectsPort(it).supportsLiveBlur() })
            assertTrue(web.waitFor("document.querySelector('.tv-canvas').classList.contains('tv-no-blur')"))
            val collector = FrameCollector()
            web.withActivity { collector.start(it.window) }
            try {
                // ABBA within one Activity: do not compare separate boots or
                // relax the production frame gate based on a diagnostic run.
                listOf(false, true, true, false).forEachIndexed { index, promoted ->
                    web.evaluate("document.querySelector('.top-nav').style.transform = '${if (promoted) "translateZ(0)" else "none"}'")
                    web.evaluate("document.querySelector('.top-nav').style.willChange = '${if (promoted) "transform" else "auto"}'")
                    val stats = measure(web, collector)
                    Log.i(TAG, "compositing_sample=$index promoted=$promoted frames=${stats.count} " +
                        "p50_ms=${stats.p50Ms} p90_ms=${stats.p90Ms} over_50_ratio=${stats.over50MsRatio}")
                }
            } finally {
                web.evaluate("document.querySelector('.top-nav').style.transform = ''")
                web.evaluate("document.querySelector('.top-nav').style.willChange = ''")
                web.withActivity { collector.stop(it.window) }
            }
        }
    }

    @Test
    fun lowMemoryFocusFeedbackStaysWithinSevereFrameBudget() {
        ProductionWebViewHarness.launch().use { web ->
            val collector = FrameCollector()
            web.withActivity { collector.start(it.window) }
            try {
                val nativeBlur = web.withActivity { AndroidVisualEffectsPort(it).supportsLiveBlur() }
                assumeFalse("requires a device on the production opaque-fallback path", nativeBlur)
                assertTrue(web.waitFor("document.querySelector('.tv-canvas').classList.contains('tv-no-blur')"))
                assertTrue("Production chrome must retain its measured compositing hint", web.bool("getComputedStyle(document.querySelector('.top-nav')).willChange === 'transform'"))
                val stats = measure(web, collector)
                Log.i(
                    TAG,
                    "fallback_frames=${stats.count} p50_ms=${stats.p50Ms} p90_ms=${stats.p90Ms} " +
                        "over_50_ms=${"%.3f".format(stats.over50MsRatio)}",
                )
                assertTrue("must capture at least 60 fallback frames, got ${stats.count}", stats.count >= 60)
                assertTrue("fallback p50 ${stats.p50Ms}ms exceeds 34ms", stats.p50Ms < 34.0)
                assertTrue("fallback p90 ${stats.p90Ms}ms exceeds 50ms", stats.p90Ms < 50.0)
                assertTrue("${stats.over50MsRatio} of fallback frames exceeded 50ms", stats.over50MsRatio < 0.10)
            } finally {
                web.withActivity { collector.stop(it.window) }
            }
        }
    }

    private fun measure(web: ProductionWebViewHarness, collector: FrameCollector): FrameStats {
        runFocusSequence(web, steps = WARMUP_STEPS)
        collector.clear()
        runFocusSequence(web, steps = MEASURED_STEPS)
        SystemClock.sleep(250L)
        return collector.snapshot()
    }

    private fun runFocusSequence(web: ProductionWebViewHarness, steps: Int) {
        web.evaluate(
            """
            (() => {
              const nodes = Array.from(document.querySelectorAll('.top-nav [data-focus-id]'));
              if (nodes.length < 6) return false;
              window.__kutvFramePacingDone = false;
              let step = 0;
              const advance = () => {
                nodes[step % nodes.length].focus();
                step += 1;
                if (step < $steps) window.setTimeout(advance, 90);
                else window.requestAnimationFrame(() => { window.__kutvFramePacingDone = true; });
              };
              advance();
              return true;
            })()
            """.trimIndent(),
        )
        assertTrue(
            "focus animation sequence did not finish",
            web.waitFor("window.__kutvFramePacingDone === true", steps * 120L + 2_000L),
        )
    }

    private class FrameCollector : Window.OnFrameMetricsAvailableListener {
        private val samplesNs = Collections.synchronizedList(mutableListOf<Long>())
        private val thread = HandlerThread("kutv-frame-metrics").apply { start() }

        fun start(window: Window) {
            window.addOnFrameMetricsAvailableListener(this, Handler(thread.looper))
        }

        fun stop(window: Window) {
            window.removeOnFrameMetricsAvailableListener(this)
            thread.quitSafely()
            thread.join(2_000L)
        }

        fun clear() = samplesNs.clear()

        fun snapshot(): FrameStats {
            val sortedMs = synchronized(samplesNs) { samplesNs.map { it / 1_000_000.0 }.sorted() }
            check(sortedMs.isNotEmpty()) { "No completed frames were captured" }
            return FrameStats(
                count = sortedMs.size,
                p50Ms = percentile(sortedMs, 0.50),
                p90Ms = percentile(sortedMs, 0.90),
                over50MsRatio = sortedMs.count { it > 50.0 }.toDouble() / sortedMs.size,
            )
        }

        private fun percentile(samples: List<Double>, fraction: Double): Double =
            samples[(ceil(samples.size * fraction).toInt() - 1).coerceIn(samples.indices)]

        override fun onFrameMetricsAvailable(
            window: Window,
            frameMetrics: FrameMetrics,
            dropCountSinceLastInvocation: Int,
        ) {
            val duration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0L) samplesNs += duration
        }
    }

    private data class FrameStats(
        val count: Int,
        val p50Ms: Double,
        val p90Ms: Double,
        val over50MsRatio: Double,
    )

    private companion object {
        const val TAG = "KU·TV-PERF"
        const val WARMUP_STEPS = 12
        const val MEASURED_STEPS = 70
    }
}
