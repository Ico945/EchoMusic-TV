package com.kutv.music.player

import com.kutv.music.video.PlaybackOwnership
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSessionCommandPolicyTest {
    @Test
    fun `MV owner rejects player commands and consumes media buttons`() {
        val ownership = PlaybackOwnership()
        ownership.claimMv(Any())

        assertFalse(MusicSessionCommandPolicy.allowsPlayerCommand(ownership))
        assertTrue(MusicSessionCommandPolicy.consumesMediaButton(ownership))
    }

    @Test
    fun `music owner keeps normal session behavior`() {
        val ownership = PlaybackOwnership()

        assertTrue(MusicSessionCommandPolicy.allowsPlayerCommand(ownership))
        assertFalse(MusicSessionCommandPolicy.consumesMediaButton(ownership))
    }
}
