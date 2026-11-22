package com.kutv.music.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class KugouLyricsTest {
    @Test
    fun `parses LRC timestamps and fills end times`() {
        val lines = KugouLyrics.parseTimed(
            """
            [00:01.00]First
            [00:03.250]Second
            """.trimIndent(),
        )

        assertEquals(1_000L, lines[0].startMs)
        assertEquals(3_250L, lines[0].endMs)
        assertEquals("Second", lines[1].text)
        assertEquals(6_250L, lines[1].endMs)
    }

    @Test
    fun `parses KRC start duration tags`() {
        val lines = KugouLyrics.parseTimed("[1200,800]Line")
        assertEquals(1_200L, lines.single().startMs)
        assertEquals(2_000L, lines.single().endMs)
    }
}
