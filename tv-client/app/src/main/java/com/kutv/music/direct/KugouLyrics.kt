package com.kutv.music.direct

import com.kutv.music.core.model.TimedLyricLine
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

object KugouLyrics {
    private val xorKey = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105,
    )

    fun parseTimed(text: String): List<TimedLyricLine> {
        val parsed = mutableListOf<TimedLyricLine>()
        text.lineSequence().forEach { rawLine ->
            val tags = Regex("\\[([^]]*)]").findAll(rawLine).map { it.groupValues[1].trim() }.toList()
            val content = rawLine.replace(Regex("\\[[^]]*]"), "").trim()
            if (content.isBlank()) return@forEach
            tags.forEach { tag ->
                val krc = Regex("^(\\d+)\\s*,\\s*(\\d+)$").matchEntire(tag)
                if (krc != null) {
                    val start = krc.groupValues[1].toLong()
                    val duration = krc.groupValues[2].toLong()
                    parsed += TimedLyricLine(start, start + duration, content)
                    return@forEach
                }
                val lrc = Regex("^(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?$").matchEntire(tag)
                    ?: return@forEach
                val fraction = lrc.groupValues[3].takeIf(String::isNotEmpty)
                    ?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                val start = lrc.groupValues[1].toLong() * 60_000L +
                    lrc.groupValues[2].toLong() * 1_000L + fraction
                parsed += TimedLyricLine(start, 0L, content)
            }
        }
        val sorted = parsed.sortedWith(compareBy<TimedLyricLine> { it.startMs }.thenBy { it.text })
        return sorted.mapIndexed { index, line ->
            if (line.endMs > line.startMs) line else line.copy(
                endMs = sorted.getOrNull(index + 1)?.startMs ?: line.startMs + 3_000L,
            )
        }
    }

    fun decodeKrcBase64(content: String): String {
        val bytes = runCatching { Base64.getDecoder().decode(content) }.getOrElse { return "" }
        if (bytes.size <= 4) return ""
        val payload = ByteArray(bytes.size - 4) { index ->
            (bytes[index + 4].toInt() xor xorKey[index % xorKey.size].toInt()).toByte()
        }
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(payload)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
}
