package com.kutv.music

import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.direct.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in anonymous production home check. Reports schema/counts, never response values or URLs. */
class KugouHomeLiveSmokeTest {
    @Test fun productionHomeRendersRealCardsAndAcceptsDpadFocus() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugou") == "true")
        ProductionWebViewHarness.launch().use { web ->
            assertTrue("Real home did not render; this is not a fixture-backed check",
                web.waitFor("document.querySelector('[data-focus-id=hero-play]') !== null", 20_000L))
            assertTrue(web.bool("document.querySelectorAll('.music-card').length > 0"))
            assertTrue(web.focus("[data-focus-id=nav-home]"))
            web.dispatchKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'hero-play'"))
            web.dispatchKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(web.waitFor("document.activeElement?.dataset.focusId === 'nav-home'"))
            assertTrue(web.bool("document.querySelector('.home-error') === null"))
            assertTrue("Real artwork did not load through the production WebView policy",
                web.waitFor("Array.from(document.querySelectorAll('.hero-art img, .music-card img')).some(img => img.complete && img.naturalWidth > 0)", 15_000L))
        }
    }

    @Test fun anonymousHomeContainsRealTracks() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugou") == "true")
        AppGraph.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val trace = mutableListOf<String>()
        val transport = OkHttpKugouTransport()
        val client = KugouHttpClient(
            deviceProvider = AppGraph.deviceStore::getOrCreate,
            credentialProvider = { null },
            transport = KugouTransport { request ->
                val response = transport.execute(request)
                val path = java.net.URI(request.url).path
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                trace += "$path: http=${response.statusCode}, shape=${json?.let { shape(it, 0) } ?: "non-json"}"
                response
            },
        )
        val registrar = KugouDeviceRegistrar(AppGraph.deviceStore, client)
        val api = KugouDirectApi(client, { null }, AppGraph.deviceStore::getOrCreate,
            ensureDeviceRegistered = { registrar.ensureRegistered() })
        val result = runCatching { api.home() }
        val failure = result.exceptionOrNull()
        val category = if (failure is KugouNetworkException) failure.kind.name else failure?.javaClass?.simpleName
        assertTrue("Home missing: category=$category; ${trace.joinToString("; ")}", result.getOrNull()?.hero != null)
        val feed = result.getOrThrow()
        val tracks = listOfNotNull(feed.hero) + feed.newSongs + feed.daily + feed.history
        assertTrue("Blank track titles: ${tracks.count { it.title.isBlank() }}/${tracks.size}; ${trace.joinToString("; ")}",
            tracks.all { it.title.isNotBlank() })
        assertTrue("Real feed lost artwork mapping", tracks.any { it.artworkUri.isNotBlank() })
        assertTrue("Absent video ids became MV entries", tracks.none { it.mvId in setOf("0", "-1", "000") })
    }

    private fun shape(value: Any?, depth: Int): String = when {
        depth > 4 -> "..."
        value is JSONObject -> value.keys().asSequence().take(30).joinToString(prefix = "{", postfix = "}") {
            "$it:${shape(value.opt(it), depth + 1)}"
        }
        value is JSONArray -> "[${value.length()};${shape(value.opt(0), depth + 1)}]"
        value is Number -> "number"
        value is String -> "string"
        value is Boolean -> "boolean"
        else -> "null"
    }
}
