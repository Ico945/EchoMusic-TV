package com.kutv.music.web

import android.content.Context
import android.view.KeyEvent
import android.webkit.WebView
import org.json.JSONObject

/** Consumes the finite TV remote key contract before WebView's native focus engine can move DOM focus. */
internal class TvRemoteWebView(context: Context) : WebView(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = webKeyForAndroid(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.isCanceled) {
            cancelRemoteInput()
            return true
        }
        val type = when (event.action) {
            KeyEvent.ACTION_DOWN -> "keydown"
            KeyEvent.ACTION_UP -> "keyup"
            else -> return true
        }
        // Only activation keys retain their original recipient. Direction repeats
        // must follow the focus they just moved; canceled/orphan holds stay ignored.
        evaluateJavascript(
            """
            (() => {
              const held = window.__echoTvHeldKeys || (window.__echoTvHeldKeys = new Map());
              const id = ${event.keyCode};
              const current = document.querySelector('[data-tv-ime-active="true"]') || document.activeElement || window;
              let target;
              if ('$type' === 'keydown') {
                if (${event.repeatCount > 0}) {
                  if (!held.has(id)) return;
                  target = ${key.startsWith("Arrow")} ? current : held.get(id);
                  if (!target || target !== current) return;
                } else {
                  target = current;
                  held.set(id, target);
                }
              } else {
                target = held.get(id);
                held.delete(id);
                if (!target || target !== current) return;
              }
              if (target !== window && !target.isConnected) return;
              target.dispatchEvent(new KeyboardEvent('$type', {
                key: ${JSONObject.quote(key)}, repeat: ${event.repeatCount > 0}, bubbles: true, cancelable: true
              }));
            })()
            """.trimIndent(),
            null,
        )
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelRemoteInput()
    }

    private fun cancelRemoteInput() {
        evaluateJavascript(
            "window.__echoTvHeldKeys?.clear(); window.dispatchEvent(new Event('echo-tv-input-cancel'));",
            null,
        )
    }
}
