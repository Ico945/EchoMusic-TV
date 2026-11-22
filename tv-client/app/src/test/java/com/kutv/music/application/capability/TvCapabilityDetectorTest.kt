package com.kutv.music.application.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCapabilityDetectorTest {
    @Test
    fun `no microphone reports unavailable even when permission is granted`() {
        val detector = TvCapabilityDetector(
            packageManager = FakePackageManager(hasMicrophone = false),
            inputMethod = FakeInputMethod(hasVoiceInput = true),
            permissionChecker = FakePermissionChecker(recordAudio = true),
        )

        assertEquals("unavailable", detector.snapshot().microphone)
    }

    @Test
    fun `microphone present without permission reports permission required`() {
        val detector = TvCapabilityDetector(
            packageManager = FakePackageManager(hasMicrophone = true),
            inputMethod = FakeInputMethod(hasVoiceInput = false),
            permissionChecker = FakePermissionChecker(recordAudio = false),
        )

        val snapshot = detector.snapshot()
        assertEquals("permission-required", snapshot.microphone)
        assertFalse(snapshot.voiceInput)
        assertTrue(snapshot.blur)
    }

    @Test
    fun `microphone with permission reports available and forwards voice input`() {
        val detector = TvCapabilityDetector(
            packageManager = FakePackageManager(hasMicrophone = true),
            inputMethod = FakeInputMethod(hasVoiceInput = true),
            permissionChecker = FakePermissionChecker(recordAudio = true),
        )

        val snapshot = detector.snapshot()
        assertEquals("available", snapshot.microphone)
        assertTrue(snapshot.voiceInput)
        assertTrue(snapshot.blur)
    }

    @Test
    fun `low capability devices disable live blur without disabling browsing`() {
        val detector = TvCapabilityDetector(
            packageManager = FakePackageManager(hasMicrophone = false),
            inputMethod = FakeInputMethod(hasVoiceInput = false),
            permissionChecker = FakePermissionChecker(recordAudio = false),
            visualEffects = VisualEffectsPort { false },
        )

        val snapshot = detector.snapshot()
        assertFalse(snapshot.blur)
        assertEquals("unavailable", snapshot.microphone)
    }

    @Test
    fun `live blur policy rejects pre Android 12 devices`() {
        assertFalse(
            LiveBlurPolicy.supports(
                sdkInt = 30,
                isLowRamDevice = false,
                totalMemoryBytes = LiveBlurPolicy.MIN_TOTAL_MEMORY_BYTES * 2,
            ),
        )
    }

    @Test
    fun `live blur policy rejects low ram flag even with ample memory`() {
        assertFalse(
            LiveBlurPolicy.supports(
                sdkInt = 31,
                isLowRamDevice = true,
                totalMemoryBytes = LiveBlurPolicy.MIN_TOTAL_MEMORY_BYTES * 2,
            ),
        )
    }

    @Test
    fun `live blur policy rejects under two GiB TVs missed by low ram flag`() {
        assertFalse(
            LiveBlurPolicy.supports(
                sdkInt = 34,
                isLowRamDevice = false,
                totalMemoryBytes = LiveBlurPolicy.MIN_TOTAL_MEMORY_BYTES - 1,
            ),
        )
    }

    @Test
    fun `live blur policy accepts capable Android 12 TVs`() {
        assertTrue(
            LiveBlurPolicy.supports(
                sdkInt = 31,
                isLowRamDevice = false,
                totalMemoryBytes = LiveBlurPolicy.MIN_TOTAL_MEMORY_BYTES,
            ),
        )
    }

    private class FakePackageManager(private val hasMicrophone: Boolean) : PackageManagerPort {
        override fun hasSystemFeature(feature: String): Boolean =
            feature == "android.hardware.microphone" && hasMicrophone
    }

    private class FakeInputMethod(private val hasVoiceInput: Boolean) : InputMethodPort {
        override fun hasVoiceInput(): Boolean = hasVoiceInput
    }

    private class FakePermissionChecker(private val recordAudio: Boolean) : PermissionChecker {
        override fun isGranted(permission: String): Boolean =
            permission == "android.permission.RECORD_AUDIO" && recordAudio
    }
}
