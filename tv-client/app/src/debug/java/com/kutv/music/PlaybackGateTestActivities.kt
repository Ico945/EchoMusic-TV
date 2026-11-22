package com.kutv.music

import android.os.SystemClock
import com.kutv.music.application.HomeUseCase
import com.kutv.music.data.ExperienceMusicRepository
import com.kutv.music.video.MvStreamResolver
import com.kutv.music.video.ResolvedMvStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MvHomeTestActivity : MainActivity() {
    override fun createHomeUseCase(): HomeUseCase = HomeUseCase(
        ExperienceMusicRepository("android.resource://$packageName"),
    )

    override fun createMvStreamResolver(): MvStreamResolver = MvStreamResolver { _, quality ->
        Result.success(localMvStream(packageName, quality))
    }
}

class BlockingResolverMainActivity : MainActivity() {
    override fun createHomeUseCase(): HomeUseCase = HomeUseCase(
        ExperienceMusicRepository("android.resource://$packageName"),
    )

    override fun createMvStreamResolver(): MvStreamResolver = BlockingMvResolverProbe.resolver(packageName)
}

object BlockingMvResolverProbe {
    private val startCount = AtomicInteger(0)
    @Volatile
    private var releaseFirst = CountDownLatch(1)

    val starts: Int get() = startCount.get()

    @Synchronized
    fun reset() {
        releaseFirst.countDown()
        releaseFirst = CountDownLatch(1)
        startCount.set(0)
    }

    fun release() {
        releaseFirst.countDown()
    }

    fun awaitStarts(expected: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (starts >= expected) return true
            Thread.sleep(10L)
        }
        return false
    }

    fun resolver(packageName: String): MvStreamResolver = MvStreamResolver { _, quality ->
        val ordinal = startCount.incrementAndGet()
        if (ordinal == 1) {
            val release = releaseFirst
            while (release.count > 0L) {
                try {
                    release.await(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Deliberately non-cooperative: the process admission lease must survive Activity cancellation.
                }
            }
        }
        Result.success(localMvStream(packageName, quality))
    }
}

private fun localMvStream(packageName: String, quality: String) = ResolvedMvStream(
    url = "android.resource://$packageName/raw/track_1",
    mimeType = "audio/mpeg",
    quality = quality,
)
