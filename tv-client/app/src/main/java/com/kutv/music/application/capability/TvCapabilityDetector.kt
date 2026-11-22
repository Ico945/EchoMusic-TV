package com.kutv.music.application.capability

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

data class CapabilitySnapshot(
    val microphone: String,
    val voiceInput: Boolean,
    val blur: Boolean,
)

fun interface PackageManagerPort {
    fun hasSystemFeature(feature: String): Boolean
}

fun interface InputMethodPort {
    fun hasVoiceInput(): Boolean
}

fun interface PermissionChecker {
    fun isGranted(permission: String): Boolean
}

fun interface VisualEffectsPort {
    fun supportsLiveBlur(): Boolean
}

class TvCapabilityDetector(
    private val packageManager: PackageManagerPort,
    private val inputMethod: InputMethodPort,
    private val permissionChecker: PermissionChecker,
    private val visualEffects: VisualEffectsPort = VisualEffectsPort { true },
) {
    fun snapshot(): CapabilitySnapshot {
        val hasMicrophone = packageManager.hasSystemFeature(FEATURE_MICROPHONE)
        val microphone = when {
            !hasMicrophone -> MICROPHONE_UNAVAILABLE
            !permissionChecker.isGranted(PERMISSION_RECORD_AUDIO) -> MICROPHONE_PERMISSION_REQUIRED
            else -> MICROPHONE_AVAILABLE
        }
        return CapabilitySnapshot(
            microphone = microphone,
            voiceInput = inputMethod.hasVoiceInput(),
            blur = visualEffects.supportsLiveBlur(),
        )
    }

    companion object {
        const val MICROPHONE_AVAILABLE = "available"
        const val MICROPHONE_PERMISSION_REQUIRED = "permission-required"
        const val MICROPHONE_UNAVAILABLE = "unavailable"
        const val FEATURE_MICROPHONE = "android.hardware.microphone"
        const val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}

class AndroidPackageManagerPort(private val context: Context) : PackageManagerPort {
    override fun hasSystemFeature(feature: String): Boolean =
        context.packageManager.hasSystemFeature(feature)
}

/**
 * Best-effort "voice input" probe. Android exposes no stable public API for
 * "does the enabled keyboard support voice dictation", so an enabled IME with
 * at least one input subtype is used as the practical proxy. The real gate for
 * recognition stays [TvCapabilityDetector.FEATURE_MICROPHONE]; this value only
 * informs the search/dictation fallback in the Vue surface.
 */
class AndroidInputMethodPort(private val context: Context) : InputMethodPort {
    override fun hasVoiceInput(): Boolean {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        return runCatching { manager.enabledInputMethodList }.getOrNull().orEmpty()
            .any { info -> info.packageName.isNotBlank() && info.subtypeCount > 0 }
    }
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

class AndroidVisualEffectsPort(context: Context) : VisualEffectsPort {
    private val appContext = context.applicationContext

    override fun supportsLiveBlur(): Boolean {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        if (runCatching { activityManager.getMemoryInfo(memoryInfo) }.isFailure) return false
        return LiveBlurPolicy.supports(
            sdkInt = Build.VERSION.SDK_INT,
            isLowRamDevice = activityManager.isLowRamDevice,
            totalMemoryBytes = memoryInfo.totalMem,
        )
    }
}

/**
 * Backdrop blur is a full-surface GPU operation in the production WebView.
 * Some TV firmware does not classify 1–2 GB hardware as a low-RAM device, so
 * [ActivityManager.isLowRamDevice] alone is too optimistic. Keep live blur for
 * API 31+ devices with at least 2 GiB of kernel-accessible RAM; all others use
 * the opaque Liquid Glass fallback while retaining focus scale, ring and text.
 */
internal object LiveBlurPolicy {
    internal const val MIN_TOTAL_MEMORY_BYTES = 2L * 1024L * 1024L * 1024L

    fun supports(sdkInt: Int, isLowRamDevice: Boolean, totalMemoryBytes: Long): Boolean =
        sdkInt >= Build.VERSION_CODES.S &&
            !isLowRamDevice &&
            totalMemoryBytes >= MIN_TOTAL_MEMORY_BYTES
}
