package com.kutv.music.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView

// Constructed only in code because PlayerView and WebView are owned objects;
// XML inflation cannot supply those dependencies.
@SuppressLint("ViewConstructor")
class TvVideoOverlay(
    context: Context,
    val playerView: PlayerView,
    val webView: WebView,
) : FrameLayout(context) {
    var webBackgroundColor: Int = WEB_BACKGROUND
        private set

    init {
        setBackgroundColor(Color.BLACK)
        playerView.apply {
            useController = false
            visibility = View.INVISIBLE
            tag = PLAYER_VIEW_TAG
        }
        webView.tag = WEB_VIEW_TAG
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hideMv()
        tag = ROOT_TAG
    }

    val isMvVisible: Boolean get() = playerView.isVisible

    fun showMv() {
        playerView.visibility = View.VISIBLE
        setWebBackground(Color.TRANSPARENT)
    }

    fun hideMv() {
        playerView.visibility = View.INVISIBLE
        setWebBackground(WEB_BACKGROUND)
    }

    private fun setWebBackground(color: Int) {
        webBackgroundColor = color
        webView.setBackgroundColor(color)
    }

    companion object {
        const val ROOT_TAG = "tv-video-overlay"
        const val PLAYER_VIEW_TAG = "tv-mv-player-view"
        const val WEB_VIEW_TAG = "tv-mv-web-controls"
        val WEB_BACKGROUND: Int = Color.rgb(11, 16, 24)
    }
}
