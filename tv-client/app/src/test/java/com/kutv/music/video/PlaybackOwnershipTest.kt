package com.kutv.music.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOwnershipTest {
    @Test
    fun `only the active Activity token can return ownership to music`() {
        var pauses = 0
        val ownership = PlaybackOwnership { pauses += 1; true }
        val oldActivity = Any()
        val recreatedActivity = Any()

        assertTrue(ownership.claimMv(oldActivity))
        assertFalse(ownership.claimMv(recreatedActivity))
        assertFalse(ownership.releaseMv(recreatedActivity))
        assertEquals(PlaybackOwner.MV, ownership.value)
        assertFalse(ownership.allowsMusicPlayback)

        assertTrue(ownership.releaseMv(oldActivity))
        assertFalse(ownership.releaseMv(oldActivity))
        assertTrue(ownership.allowsMusicPlayback)
        assertEquals(1, pauses)
    }

    @Test
    fun `process music endpoint is attached and detached by identity`() {
        val ownership = PlaybackOwnership { false }
        val staleService = Any()
        val currentService = Any()
        ownership.attachMusicEndpoint(staleService) { false }
        ownership.attachMusicEndpoint(currentService) { true }
        ownership.detachMusicEndpoint(staleService)

        assertTrue(ownership.claimMv(Any()))
    }
}
