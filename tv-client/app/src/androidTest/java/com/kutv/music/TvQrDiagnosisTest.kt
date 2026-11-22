package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutv.music.application.auth.AndroidQrImageRenderer
import com.kutv.music.application.auth.AuthUseCases
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvQrDiagnosisTest {
    @Test
    fun beginQrWithFakeRendererReportsTheRealFailure() = runBlocking {
        ProductionWebViewHarness.launch().use {
            val result = runCatching {
                AuthUseCases(AppGraph.sessionManager, { "data:image/png;base64,AAAA" }).beginQr()
            }
            result.fold(
                onSuccess = {
                    assertTrue(it.qrImageDataUrl!!.startsWith("data:image/png;base64"))
                },
                onFailure = { error ->
                    fail("beginQr failed: ${error.javaClass.name}: ${error.message} cause=${error.cause?.javaClass?.name}: ${error.cause?.message}")
                },
            )
        }
    }

    @Test
    fun realQrRendererProducesAPngDataUrlForTheOfficialQr() {
        val rendered = AndroidQrImageRenderer.render("https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&key=TESTKEY123")
        assertTrue(rendered.startsWith("data:image/png;base64,"))
        assertTrue(rendered.length > 100)
    }

    @Test
    fun fullAuthFlowWithRealRendererSucceedsOrReportsTheExactFailure() = runBlocking {
        ProductionWebViewHarness.launch().use {
            val result = runCatching {
                AuthUseCases(AppGraph.sessionManager, AndroidQrImageRenderer::render).beginQr()
            }
            result.fold(
                onSuccess = {
                    assertTrue(it.qrImageDataUrl!!.startsWith("data:image/png;base64,"))
                    assertTrue(it.state == "qrPending")
                },
                onFailure = { error ->
                    fail("full beginQr failed: ${error.javaClass.name}: ${error.message} cause=${error.cause?.javaClass?.name}: ${error.cause?.message}")
                },
            )
        }
    }

    @Test
    fun fullBridgeRouterPathReportsTheExactFailure() = runBlocking {
        ProductionWebViewHarness.launch().use {
            val handler = com.kutv.music.bridge.handlers.AuthBridgeHandler(
                AuthUseCases(AppGraph.sessionManager, AndroidQrImageRenderer::render),
            )
            val router = com.kutv.music.bridge.BridgeRouter(
                mapOf("auth.beginQr" to handler.handlers().getValue("auth.beginQr")),
            )
            val raw = """{"version":1,"type":"request","id":"r1","method":"auth.beginQr","payload":{}}"""
            val response = org.json.JSONObject(router.handle(raw))
            if (!response.getBoolean("ok")) {
                val error = response.getJSONObject("error")
                fail("router returned error: code=${error.getString("code")} message=${error.getString("message")} scope=${error.getString("scope")}")
            }
            val payload = response.getJSONObject("payload")
            assertTrue(payload.getString("qrImageDataUrl").startsWith("data:image/png;base64,"))
        }
    }

    @Test
    fun authBridgeHandlerWithRealRendererReportsTheExactFailure() = runBlocking {
        ProductionWebViewHarness.launch().use {
            val handler = com.kutv.music.bridge.handlers.AuthBridgeHandler(
                AuthUseCases(AppGraph.sessionManager, AndroidQrImageRenderer::render),
            )
            val result = runCatching {
                handler.handlers().getValue("auth.beginQr").handle(org.json.JSONObject())
            }
            result.fold(
                onSuccess = { payload ->
                    assertTrue(payload.has("qrImageDataUrl"))
                    assertTrue(payload.getString("qrImageDataUrl").startsWith("data:image/png;base64,"))
                },
                onFailure = { error ->
                    fail("handler beginQr failed: ${error.javaClass.name}: ${error.message} cause=${error.cause?.javaClass?.name}: ${error.cause?.message}")
                },
            )
        }
    }
}
