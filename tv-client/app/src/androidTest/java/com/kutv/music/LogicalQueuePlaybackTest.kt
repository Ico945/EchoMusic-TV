package com.kutv.music

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.player.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Native ExoPlayer + MediaSession tests; audio URLs map to debug-only local
 * resources. They neither load nor modify the release account/session. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LogicalQueuePlaybackTest {
    @Test fun releaseWhilePreparingDoesNotReadInconsistentNativeState() = runBlocking {
        repeat(8) {
            withFixture {
                assertTrue(onMain { coordinator.submitAndPlay(listOf(
                    QueueTrack("release", "Fixture", emptyList(), "",
                        source = PlaybackSource.Kugou("release", "FIXTURE", preferredQuality = "128")),
                ), 0) }.await())
                // No READY wait: close while native loading/events may be pending.
            }
        }
    }

    @Test fun pausedQualitySwitchPreservesLatestSeekAndPauseDuringResolution() = runBlocking {
        withFixture {
            start(1)
            onMain { raw.seekTo(700L) }
            val gate = CompletableDeferred<Unit>()
            onMain { qualityGate = gate }
            val switching = onMain { coordinator.setCurrentQuality("b", "320") }
            awaitState { resolvingQuality }
            onMain { raw.seekTo(1200L); raw.pause() }
            gate.complete(Unit)
            assertTrue(switching.await())
            awaitState { raw.playbackState == Player.STATE_READY &&
                controller.mediaMetadata.extras?.getString(QueueMetadataKeys.QUALITY) == "320" }
            onMain {
                assertFalse(raw.playWhenReady)
                assertFalse(controller.playWhenReady)
                assertEquals(1200L, raw.currentPosition)
                assertEquals(4, controller.mediaItemCount)
                assertEquals(1, controller.currentMediaItemIndex)
                assertEquals("b", raw.currentMediaItem?.mediaId)
            }
        }
    }

    @Test fun alreadyPausedQualitySwitchDoesNotResumePlayback() = runBlocking {
        withFixture {
            start(1)
            onMain { raw.pause(); raw.seekTo(800L) }
            assertTrue(onMain { coordinator.setCurrentQuality("b", "320") }.await())
            awaitState { raw.playbackState == Player.STATE_READY &&
                controller.currentMediaItem?.mediaMetadata?.extras?.getString(QueueMetadataKeys.QUALITY) == "320" }
            onMain {
                assertFalse(raw.playWhenReady)
                assertEquals(800L, raw.currentPosition)
                assertEquals("b", controller.currentMediaItem?.mediaId)
            }
        }
    }

    @Test fun playingQualitySwitchKeepsProgressAndDoesNotRestartSong() = runBlocking {
        withFixture {
            start(0)
            awaitState { raw.isPlaying && raw.currentPosition >= 300L }
            val before = onMain { raw.currentPosition }
            assertTrue(onMain { coordinator.setCurrentQuality("a", "320") }.await())
            awaitState { raw.isPlaying && raw.currentPosition > before }
            onMain {
                assertTrue(raw.playWhenReady)
                assertEquals("a", raw.currentMediaItem?.mediaId)
                assertEquals("320", raw.mediaMetadata.extras?.getString(QueueMetadataKeys.QUALITY))
                assertNull(raw.playerError)
            }
        }
    }

    @Test fun controllerSeesFullQueueCanGoBeforeBufferAndSeekUnresolvedTail() = runBlocking {
        withFixture {
            start(1)
            onMain {
                controller.pause()
                assertEquals(listOf("a", "b", "c", "d"), (0 until controller.mediaItemCount).map {
                    controller.getMediaItemAt(it).mediaId })
                assertTrue(raw.mediaItemCount < controller.mediaItemCount)
                assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                assertEquals(0, controller.previousMediaItemIndex)
                controller.seekToPreviousMediaItem()
            }
            awaitState { raw.currentMediaItem?.mediaId == "a" && controller.currentMediaItemIndex == 0 }
            onMain {
                assertFalse(raw.playWhenReady)
                controller.seekTo(3, 900L)
            }
            awaitState { raw.currentMediaItem?.mediaId == "d" && controller.currentMediaItemIndex == 3 && raw.playbackState == Player.STATE_READY }
            onMain {
                assertEquals(900L, raw.currentPosition)
                assertEquals(4, controller.mediaItemCount)
                assertFalse(raw.playWhenReady)
                assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                // Session metadata must never publish media authorization URLs.
                repeat(controller.mediaItemCount) {
                    val item = controller.getMediaItemAt(it)
                    assertNull(item.localConfiguration)
                    assertFalse(item.mediaMetadata.extras?.containsKey(QueueMetadataKeys.SOURCE_HASH) == true)
                }
            }
        }
    }

    @Test fun genericSystemPreviousMatchesOnScreenPreviousAfterRestartThreshold() = runBlocking {
        withFixture {
            start(1)
            onMain { controller.pause(); controller.seekTo(6_000L) }
            awaitState { raw.currentPosition == 6_000L }
            onMain { controller.seekToPrevious() }
            awaitState { raw.currentMediaItem?.mediaId == "a" && controller.currentMediaItemIndex == 0 }
            onMain {
                assertFalse(raw.playWhenReady)
                assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
            }
        }
    }

    @Test fun repeatAllWrapsFullQueueAndAutomaticEndDoesNotLoopBufferedSubset() = runBlocking {
        withFixture {
            start(3)
            onMain { controller.pause(); controller.repeatMode = Player.REPEAT_MODE_ALL }
            awaitState { coordinator.repeatAll && controller.repeatMode == Player.REPEAT_MODE_ALL }
            onMain {
                assertEquals(Player.REPEAT_MODE_OFF, raw.repeatMode)
                controller.seekToNextMediaItem()
            }
            awaitState { raw.currentMediaItem?.mediaId == "a" && controller.currentMediaItemIndex == 0 }
            onMain { assertFalse(raw.playWhenReady); controller.seekToPreviousMediaItem() }
            awaitState { raw.currentMediaItem?.mediaId == "d" && controller.currentMediaItemIndex == 3 && raw.playbackState == Player.STATE_READY }
            onMain { raw.seekTo(raw.duration - 80L); raw.play() }
            awaitState { raw.currentMediaItem?.mediaId == "a" && raw.isPlaying }
            onMain { assertEquals(4, controller.mediaItemCount); assertNull(raw.playerError) }
        }
    }

    @Test fun repeatOffEndsAtLastAndRepeatOneStaysNative() = runBlocking {
        withFixture {
            start(3)
            onMain { raw.seekTo(raw.duration - 60L) }
            awaitState { raw.playbackState == Player.STATE_ENDED }
            onMain { assertEquals("d", raw.currentMediaItem?.mediaId) }
            onMain { controller.repeatMode = Player.REPEAT_MODE_ONE }
            awaitState { raw.repeatMode == Player.REPEAT_MODE_ONE }
            onMain { raw.seekTo(0L); raw.play() }
            awaitState { raw.playbackState == Player.STATE_READY }
            onMain { raw.seekTo(raw.duration - 60L) }
            awaitState { raw.currentPosition < 500L && raw.isPlaying }
            onMain { assertEquals("d", raw.currentMediaItem?.mediaId); assertFalse(coordinator.repeatAll) }
        }
    }

    @Test fun failedAdjacentResolutionDoesNotSkipToAnotherPhysicalItem() = runBlocking {
        withFixture {
            onMain { failingId = "c" }
            start(1)
            onMain { controller.pause(); controller.seekToNextMediaItem() }
            awaitState { failedResolutions >= 2 }
            awaitState { controller.currentMediaItem?.mediaId == "b" }
            onMain {
                assertEquals("b", raw.currentMediaItem?.mediaId)
                assertEquals(1, coordinator.snapshot.value.currentIndex)
                assertFalse(raw.playWhenReady)
                assertEquals(4, controller.mediaItemCount)
                failingId = null
                raw.seekTo(raw.duration - 80L)
                raw.play()
            }
            awaitState { raw.currentMediaItem?.mediaId == "c" && raw.isPlaying }
        }
    }

    private suspend fun withFixture(test: suspend Fixture.() -> Unit) {
        val fixture = onMain { Fixture() }
        try {
            val future = onMain { MediaController.Builder(fixture.context, fixture.session.token).buildAsync() }
            fixture.controller = withContext(Dispatchers.IO) { future.get(8, TimeUnit.SECONDS) }
            fixture.test()
        } finally {
            onMain {
                fixture.releaseController()
                fixture.raw.pause()
                fixture.session.release()
                fixture.wrapper.release()
                fixture.scope.cancel()
            }
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var qualityGate: CompletableDeferred<Unit>? = null
        var resolvingQuality = false
        var failingId: String? = null
        var failedResolutions = 0
        val raw = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec ->
                spec.withUri(Uri.parse("android.resource://${context.packageName}/raw/track_1"))
            },
        )).build().apply { volume = 0f }
        val coordinator = PlaybackQueueCoordinator(object : QueueTrackResolver {
            override suspend fun resolve(track: QueueTrack): ResolvedQueueTrack {
                if (track.id == failingId) { failedResolutions++; throw PlaybackResolutionException("fixture") }
                val quality = (track.source as PlaybackSource.Kugou).preferredQuality
                if (quality == "320") { resolvingQuality = true; qualityGate?.await() }
                return ResolvedQueueTrack(track, "https://webfs.kugou.com/fixture-$quality.mp3", quality)
            }
        }, Media3QueuePlayer(raw), scope)
        val wrapper = LogicalQueueSessionPlayer(raw, coordinator, scope)
        val session = MediaSession.Builder(context, wrapper).setId("logical-queue-fixture").build()
        lateinit var controller: MediaController
        fun releaseController() { if (::controller.isInitialized) controller.release() }
        suspend fun start(index: Int) {
            assertTrue(onMain { coordinator.submitAndPlay(listOf("a", "b", "c", "d").map {
                QueueTrack(it, "Fixture $it", listOf("Fixture Artist"), "",
                    source = PlaybackSource.Kugou(it, "FIXTURE", preferredQuality = "128"),
                    qualities = listOf("128", "320"))
            }, index) }.await())
            awaitState { raw.playbackState == Player.STATE_READY && controller.mediaItemCount == 4 }
        }
        suspend fun awaitState(test: Fixture.() -> Boolean) {
            try {
                withTimeout(10_000L) { while (!onMain { test() }) delay(35L) }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError(onMain {
                    "raw id=${raw.currentMediaItem?.mediaId} state=${raw.playbackState} " +
                        "pos=${raw.currentPosition} duration=${raw.duration} loading=${raw.isLoading} " +
                        "quality=${raw.mediaMetadata.extras?.getString(QueueMetadataKeys.QUALITY)}; " +
                        "controller id=${controller.currentMediaItem?.mediaId} count=${controller.mediaItemCount} " +
                        "quality=${controller.mediaMetadata.extras?.getString(QueueMetadataKeys.QUALITY)}"
                }, timeout)
            }
        }
    }

    companion object {
        private suspend fun <T> onMain(action: () -> T): T = withContext(Dispatchers.Main.immediate) { action() }
    }
}
