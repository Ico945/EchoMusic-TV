package com.kutv.music.direct

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class KugouTransportCancellationTest {
    @Test fun `transport preserves signed text and binary body bytes`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OkHttpKugouTransport()
            val bodies = listOf("{\"title\":\"中文\"}".toByteArray(), byteArrayOf(0, -1, 1, 13, 10))
            for ((index, bytes) in bodies.withIndex()) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
                val response = transport.execute(KugouRawRequest(server.url("/raw").toString(), "POST",
                    mapOf("content-type" to "application/octet-stream", "x-router" to "test"),
                    if (index == 0) bytes.toString(Charsets.UTF_8) else null, 1000, 1000,
                    if (index == 1) bytes else null))
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertArrayEquals(bytes, sent.body.readByteArray())
                assertArrayEquals(bytes, response.bodyBytes)
                assertEquals("application/octet-stream", sent.getHeader("Content-Type"))
                assertEquals("test", sent.getHeader("x-router"))
            }
        }
    }

    @Test fun `transport does not follow redirects or retry failed requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/must-not-follow")))
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val transport = OkHttpKugouTransport()
            val request = KugouRawRequest(server.url("/start").toString(), "GET", emptyMap(), null, 1000, 1000)
            assertEquals(302, transport.execute(request).statusCode)
            assertEquals(1, server.requestCount)
            assertEquals(503, transport.execute(request).statusCode)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `cancel interrupts a stalled response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow").setBodyDelay(3, TimeUnit.SECONDS))
            val transport = OkHttpKugouTransport()
            val job = launch {
                transport.execute(KugouRawRequest(server.url("/slow").toString(), "GET", emptyMap(), null, 5000, 5000))
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            delay(50)
            val start = System.nanoTime()
            job.cancelAndJoin()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Cancellation took ${elapsed}ms", elapsed < 1000)
        }
    }
}
