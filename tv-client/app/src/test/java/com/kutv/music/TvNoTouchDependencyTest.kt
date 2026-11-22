package com.kutv.music

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the native TV shell remote-first. Touch and gesture APIs must not become
 * a hidden requirement underneath the WebView interaction contract.
 */
class TvNoTouchDependencyTest {
    private val forbiddenTouchApi = Regex(
        """\b(?:MotionEvent|GestureDetector|ScaleGestureDetector|setOnTouchListener|onTouchEvent|onInterceptTouchEvent|dispatchTouchEvent|pointerInput|awaitPointerEventScope|detectTapGestures|detectDragGestures|detectTransformGestures)\b""",
    )

    @Test
    fun `production Android source has no touch or gesture dependency`() {
        val sourceRoot = File(requireNotNull(System.getProperty("tv.main.source")))
        check(sourceRoot.isDirectory) { "Missing Android production source: $sourceRoot" }

        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { file ->
                forbiddenTouchApi.find(file.readText())?.let { match ->
                    "${file.relativeTo(sourceRoot).invariantSeparatorsPath}: ${match.value}"
                }
            }
            .toList()

        assertTrue(
            "Touch-only Android APIs are forbidden in the TV production shell:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
