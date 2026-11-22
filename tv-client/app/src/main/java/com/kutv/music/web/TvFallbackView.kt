package com.kutv.music.web

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

/**
 * Native safe fallback surface for Bridge/WebView fatal states. It renders
 * only local, fixed copy — no remote content, no scripting — and is the
 * user-visible recovery path for unsupported WebView or protocol mismatch.
 */
object TvFallbackView {
    fun recovery(context: Context, retry: (() -> Unit)?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(11, 16, 24))
        addView(create(context, if (retry == null) "正在恢复页面…" else "页面恢复失败，请重试。"))
        if (retry != null) addView(Button(context).apply {
            text = "重新打开页面"
            textSize = 20f
            setOnClickListener { retry() }
            post { requestFocus() }
        })
    }

    fun create(context: Context, message: String): TextView = TextView(context).apply {
        text = message
        textSize = 20f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(11, 16, 24))
        setPadding(56, 56, 56, 56)
    }
}
