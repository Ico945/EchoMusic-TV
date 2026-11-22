package com.kutv.music.security

/**
 * Bridge payload policy shared by the parser and the event sink: hard caps on
 * message size and JSON nesting depth. The size cap (256 KiB) is enforced by
 * [com.kutv.music.bridge.BridgeParser]; this class owns the depth limit so both
 * sides of the transport share one definition.
 */
object BridgePayloadPolicy {
    const val MAX_MESSAGE_BYTES = 256 * 1024
    const val MAX_JSON_DEPTH = 32

    /**
     * Returns true when the raw JSON text nests no deeper than [MAX_JSON_DEPTH].
     * The scan is string-literal aware and treats a malformed payload as safe
     * to hand to the JSON parser, which reports the syntax error itself.
     */
    fun withinDepthLimit(raw: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in raw) {
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' || char == '[' -> {
                    depth += 1
                    if (depth > MAX_JSON_DEPTH) return false
                }
                char == '}' || char == ']' -> depth -= 1
            }
        }
        return true
    }
}
