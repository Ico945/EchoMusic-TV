package com.kutv.music.web

import android.view.KeyEvent

internal fun webKeyCodeForAndroid(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_BACK -> KeyEvent.KEYCODE_ESCAPE
    else -> null
}

internal fun webKeyForAndroid(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
    KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
    KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    -> "Enter"
    KeyEvent.KEYCODE_ESCAPE,
    -> "Escape"
    KeyEvent.KEYCODE_MENU -> "ContextMenu"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MediaPlayPause"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MediaTrackPrevious"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "MediaTrackNext"
    else -> null
}
