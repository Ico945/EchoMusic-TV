package com.kutv.music.web

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRemoteKeyMapperTest {
    @Test
    fun `maps Android back to escape for the web shell`() {
        assertEquals(KeyEvent.KEYCODE_ESCAPE, webKeyCodeForAndroid(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `maps D-pad and media keys to the exact production web key contract`() {
        assertEquals("ArrowLeft", webKeyForAndroid(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("ArrowRight", webKeyForAndroid(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("ArrowUp", webKeyForAndroid(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("ArrowDown", webKeyForAndroid(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Enter", webKeyForAndroid(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("Enter", webKeyForAndroid(KeyEvent.KEYCODE_ENTER))
        assertEquals("Enter", webKeyForAndroid(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals("Enter", webKeyForAndroid(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("Enter", webKeyForAndroid(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals("ContextMenu", webKeyForAndroid(KeyEvent.KEYCODE_MENU))
        assertEquals("MediaPlayPause", webKeyForAndroid(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals("MediaTrackPrevious", webKeyForAndroid(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals("MediaTrackNext", webKeyForAndroid(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(webKeyForAndroid(KeyEvent.KEYCODE_BACK))
        assertNull(webKeyForAndroid(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
