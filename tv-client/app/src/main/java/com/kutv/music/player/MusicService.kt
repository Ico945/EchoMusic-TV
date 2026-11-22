package com.kutv.music.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.kutv.music.AppGraph
import com.kutv.music.application.playback.AndroidEqualizerController
import com.kutv.music.application.playback.AudioEffectRuntime
import com.kutv.music.application.playback.PlaybackErrorRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Media3 播放服务（阶段 1.5 · A2：控制器策略收敛到 SessionAccessPolicy 纯函数）。
 * 后台播放 + 系统媒体控制；媒体库浏览（onGetLibraryRoot/onGetChildren）阶段 2 由 BFF 数据驱动。
 * onGetSession 识别：自身 / 可信控制器 / Legacy 白名单；拒绝未验证第三方（媒体键由 own/isTrusted 覆盖）。
 * 播放队列统一由 PlaybackQueueRuntime 转发给 PlaybackQueueCoordinator 服务层管理，
 * coordinator 负责解析、预取、错误恢复并直接驱动 player，避免占位媒体覆盖真实音源。
 */
@androidx.media3.common.util.UnstableApi
class MusicService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var queueCoordinator: PlaybackQueueCoordinator? = null
    private var playbackScope: CoroutineScope? = null
    private var playbackResumeIoScope: CoroutineScope? = null
    private var playbackResumeWriter: PlaybackResumeWriter? = null
    private var playbackResumeSnapshotJob: Job? = null
    private var playbackResumeTickerJob: Job? = null
    private var playbackResumeRetryJob: Job? = null
    private var playbackPlayer: Player? = null
    @Volatile
    private var playbackResumeReady = false
    private var audioEffects: AndroidEqualizerController? = null
    private val ownershipEndpointToken = Any()

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        playbackPlayer = player
        val effects = AndroidEqualizerController()
        audioEffects = effects
        AudioEffectRuntime.install(effects)
        effects.attach(player.audioSessionId)
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ownership = AppGraph.playbackOwnership
        ownership.attachMusicEndpoint(ownershipEndpointToken) {
            runCatching {
                player.pause()
                !player.playWhenReady
            }.getOrDefault(false)
        }
        val coordinator = PlaybackQueueCoordinator(
            resolver = AppGraph.playbackResolver,
            player = OwnershipAwareQueuePlayer(Media3QueuePlayer(player), ownership),
            scope = serviceScope,
        )
        playbackScope = serviceScope
        queueCoordinator = coordinator
        MusicSleepTimerRuntime.attach(MusicSleepTimer(serviceScope, {
            coordinator.cancelPendingSelection()
            player.pause()
        }))
        val resumeStore = AppGraph.playbackResumeStore
        val resumeIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        playbackResumeIoScope = resumeIoScope
        playbackResumeWriter = PlaybackResumeWriter(
            store = resumeStore,
            scope = resumeIoScope,
            onFailure = {
                android.util.Log.w("KU·TV", "playback_resume_write_failed")
            },
        )
        PlaybackQueueRuntime.attach(
            commands = coordinator,
            scope = serviceScope,
            onError = { error ->
                PlaybackErrorRuntime.store.reportQueue(error)
                runCatching {
                    android.util.Log.w(
                        "KU·TV",
                        PlaybackLogMessage.queueFailure(error.action),
                    )
                }
            },
            allowsMusicPlayback = { ownership.allowsMusicPlayback },
        )
        val sessionPlayer = LogicalQueueSessionPlayer(player, coordinator, serviceScope) {
            ownership.allowsMusicPlayback
        }
        sessionPlayer.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                submitPlaybackResume(player, coordinator)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                effects.attach(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                submitPlaybackResume(player, coordinator)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                submitPlaybackResume(player, coordinator)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                submitPlaybackResume(player, coordinator)
            }
        })
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            sessionPlayer,
            object : MediaLibrarySession.Callback {
                @androidx.media3.common.util.UnstableApi
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    // 授权面：own（应用内/MediaButtonReceiver）/ isTrusted（系统 binder，不可伪造）/ 平台白名单
                    return if (isTrustedController(controller)) {
                        MediaSession.ConnectionResult.accept(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                        )
                    } else {
                        MediaSession.ConnectionResult.reject()
                    }
                }

                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int = if (MusicSessionCommandPolicy.allowsPlayerCommand(ownership)) {
                    SessionResult.RESULT_SUCCESS
                } else {
                    SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    mediaButtonEvent: Intent,
                ): Boolean = MusicSessionCommandPolicy.consumesMediaButton(ownership)
            },
        ).build()
        val storedResume = runCatching(resumeStore::load).getOrElse {
            android.util.Log.w("KU·TV", "playback_resume_read_failed")
            null
        }
        val restoreRequest = if (storedResume != null) {
            // DirectApi keeps the source binding used by lyrics and retry in memory.
            // Rehydrate it before MediaController/Facade observes the restored item.
            AppGraph.directApi.rememberTracks(storedResume.queue.map(QueueTrack::toDomainTrack))
            sessionPlayer.repeatMode = storedResume.repeatMode
            coordinator.restorePaused(
                queue = storedResume.queue,
                startIndex = storedResume.currentIndex,
                positionMs = storedResume.positionMs,
            )
        } else null
        playbackResumeSnapshotJob = serviceScope.launch {
            val initialRestoreSucceeded = try {
                restoreRequest?.await() ?: true
            } catch (_: PlaybackCommandSupersededException) {
                false
            }
            playbackResumeReady = true
            if (!initialRestoreSucceeded && storedResume != null) {
                playbackResumeRetryJob = serviceScope.launch {
                    retryPlaybackResume(player, coordinator, storedResume)
                }
            }
            coordinator.snapshot.collect {
                submitPlaybackResume(player, coordinator)
            }
        }
        playbackResumeTickerJob = serviceScope.launch {
            restoreRequest?.join()
            while (true) {
                delay(PLAYBACK_RESUME_INTERVAL_MS)
                submitPlaybackResume(player, coordinator)
            }
        }
    }

    override fun onDestroy() {
        MusicSleepTimerRuntime.detach()
        playbackResumeSnapshotJob?.cancel()
        playbackResumeSnapshotJob = null
        playbackResumeTickerJob?.cancel()
        playbackResumeTickerJob = null
        playbackResumeRetryJob?.cancel()
        playbackResumeRetryJob = null
        val finalResume = playbackPlayer?.let { player ->
            queueCoordinator?.let { coordinator ->
                createPlaybackResumeState(player, coordinator.snapshot.value)
            }
        }
        playbackResumeWriter?.closeAndSave(finalResume)
        playbackResumeWriter = null
        playbackResumeIoScope?.cancel()
        playbackResumeIoScope = null
        playbackPlayer = null
        playbackResumeReady = false
        queueCoordinator?.let(PlaybackQueueRuntime::detach)
        AppGraph.playbackOwnership.detachMusicEndpoint(ownershipEndpointToken)
        queueCoordinator = null
        playbackScope?.cancel()
        playbackScope = null
        audioEffects?.let(AudioEffectRuntime::release)
        audioEffects = null
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    private fun submitPlaybackResume(player: Player, coordinator: PlaybackQueueCoordinator) {
        if (!playbackResumeReady) return
        playbackResumeWriter?.submit(createPlaybackResumeState(player, coordinator.snapshot.value))
    }

    private fun createPlaybackResumeState(
        player: Player,
        snapshot: PlaybackQueueSnapshot,
    ): PlaybackResumeState? {
        if (snapshot.queue.isEmpty() || snapshot.currentIndex !in snapshot.queue.indices) return null
        val duration = snapshot.queue[snapshot.currentIndex].durationMs
        val position = snapshot.effectivePositionMs(
            playerPositionMs = player.currentPosition.coerceAtLeast(0L),
            hasPhysicalMediaItem = player.mediaItemCount > 0,
        )
            .let { value -> if (duration > 0L) value.coerceAtMost(duration) else value }
        return PlaybackResumeState(
            queue = snapshot.queue,
            currentIndex = snapshot.currentIndex,
            positionMs = position,
            repeatMode = mediaLibrarySession?.player?.repeatMode ?: player.repeatMode,
            savedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun retryPlaybackResume(
        player: Player,
        coordinator: PlaybackQueueCoordinator,
        storedResume: PlaybackResumeState,
    ) {
        var retryDelayMs = PLAYBACK_RESTORE_RETRY_INITIAL_MS
        while (currentCoroutineContext().isActive) {
            delay(retryDelayMs)
            val snapshot = coordinator.snapshot.value
            if (!shouldRetryPlaybackRestore(storedResume, snapshot, player.mediaItemCount > 0)) return
            val restored = try {
                coordinator.restorePaused(
                    queue = storedResume.queue,
                    startIndex = storedResume.currentIndex,
                    positionMs = storedResume.positionMs,
                ).await()
            } catch (_: PlaybackCommandSupersededException) {
                return
            }
            if (restored) {
                PlaybackErrorRuntime.store.state.value.error
                    ?.takeIf { it.code == "PLAYBACK_RESTORE_FAILED" }
                    ?.let { PlaybackErrorRuntime.store.clear(it.errorId) }
                return
            }
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(PLAYBACK_RESTORE_RETRY_MAX_MS)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean {
        val pkg = controller.packageName
        return pkg == packageName || controller.isTrusted || pkg in SessionAccessPolicy.SYSTEM_MEDIA_PACKAGES
    }

    /** 官方媒体键占位：hint controller_info_type = ACTION_MEDIA_BUTTON（Media3 真实键值） */
    private fun isMediaButtonPlaceholder(controller: MediaSession.ControllerInfo): Boolean {
        val hints = controller.connectionHints ?: return false
        // P1-1（第十六轮）：使用 Media3 真实键值 androidx.media3.session.hint.controller_info_type；
        // 值为 Intent.ACTION_MEDIA_BUTTON（不再把值当键 containsKey）
        return hints.getString(HINT_KEY_CONTROLLER_INFO_TYPE) == ACTION_MEDIA_BUTTON
    }

    @androidx.media3.common.util.UnstableApi
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // P1-1（第十五轮）：「选择 Session」与「授权控制器」分离——
        // 1) 媒体键占位（系统填充 hints）必须返回 Session（官方：onGetSession 不能返回 null）；
        //    该请求是否获得命令权限由 onConnect 按 isTrusted 判定（hints 可伪造，不参与授权）
        if (isMediaButtonPlaceholder(controllerInfo)) return mediaLibrarySession
        // 2) 常规控制器：策略放行才给 Session（own / isTrusted / 平台白名单）
        val allowed = SessionAccessPolicy.isAllowed(
            ownPackage = packageName,
            pkg = controllerInfo.packageName,
            isTrusted = controllerInfo.isTrusted,
            controllerVersion = controllerInfo.controllerVersion,
        )
        return if (allowed) mediaLibrarySession else null
    }

    private companion object {
        const val PLAYBACK_RESUME_INTERVAL_MS = 5_000L
        const val PLAYBACK_RESTORE_RETRY_INITIAL_MS = 5_000L
        const val PLAYBACK_RESTORE_RETRY_MAX_MS = 60_000L
        /** Media3 真实键值（MediaSessionService 文档）：androidx.media3.session.hint.controller_info_type */
        const val HINT_KEY_CONTROLLER_INFO_TYPE = "androidx.media3.session.hint.controller_info_type"
        /** 媒体键占位值（Intent.ACTION_MEDIA_BUTTON） */
        const val ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"
    }
}

internal fun shouldRetryPlaybackRestore(
    stored: PlaybackResumeState,
    current: PlaybackQueueSnapshot,
    hasPhysicalMediaItem: Boolean,
): Boolean = !hasPhysicalMediaItem &&
    current.queue == stored.queue &&
    current.currentIndex == stored.currentIndex &&
    current.pendingRestorePositionMs == stored.positionMs
