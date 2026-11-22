package com.kutv.music.web

import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvWebViewSecurityTest {
    @Test
    fun hostEnablesOnlyTheRequiredWebCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = TvWebViewHost.create(instrumentation.targetContext)
            try {
                with(webView.settings) {
                    assertTrue(javaScriptEnabled)
                    assertTrue(domStorageEnabled)
                    assertFalse(allowFileAccess)
                    assertFalse(allowContentAccess)
                    assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                    assertFalse(supportZoom())
                }
            } finally {
                webView.destroy()
            }
        }
    }

    @Test
    fun hostUsesTheLocalUrlAndAssetClientBlocksExternalNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val webView = WebView(context)
            val client = TvWebViewHost.createClient(context)

            try {
                assertEquals(
                    "https://appassets.androidplatform.net/assets/tv-ui/tv.html",
                    TvWebViewHost.LOCAL_URL,
                )
                assertTrue(client is TvAssetClient)
                assertFalse(client.shouldOverrideUrlLoading(webView, requestFor(TvWebViewHost.LOCAL_URL)))
                assertTrue(
                    client.shouldOverrideUrlLoading(
                        webView,
                        requestFor("https://appassets.androidplatform.net/assets/tv-ui/%2e%2e/secret.html"),
                    ),
                )
                assertTrue(client.shouldOverrideUrlLoading(webView, requestFor("https://example.com/")))
            } finally {
                webView.destroy()
            }
        }
    }

    @Test
    fun bridgeWebViewConfiguresAndInstallsBeforeLoadingTheLocalPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val phases = mutableListOf<String>()
            val webView = TvWebViewHost.createForBridge(
                instrumentation.targetContext,
                installListener = {
                    assertTrue(it.settings.javaScriptEnabled)
                    assertFalse(it.settings.allowFileAccess)
                    phases += "listener"
                },
                loadPage = { phases += "load" },
            )

            try {
                assertEquals(listOf("listener", "load"), phases)
            } finally {
                webView.destroy()
            }
        }
    }

    @Test
    fun disposeDetachesTheHostedWebViewFromItsParent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val parent = FrameLayout(context)
            val webView = TvWebViewHost.create(context)
            parent.addView(webView)

            TvWebViewHost.dispose(webView)

            assertEquals(0, parent.childCount)
            assertFalse(webView.parent is ViewGroup)
        }
    }

    private fun requestFor(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
