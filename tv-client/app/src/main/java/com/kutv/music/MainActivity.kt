package com.kutv.music

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.activity.addCallback
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kutv.music.application.AppSnapshotUseCase
import com.kutv.music.application.HomeUseCase
import com.kutv.music.application.auth.AndroidQrImageRenderer
import com.kutv.music.application.auth.AuthJsonMapper
import com.kutv.music.application.auth.AuthUseCases
import com.kutv.music.application.playback.Media3PlayerPort
import com.kutv.music.application.playback.MusicRepositoryLyricsRepository
import com.kutv.music.application.playback.PlaybackControllerFacade
import com.kutv.music.application.playback.PlaybackErrorRuntime
import com.kutv.music.application.playback.PlaybackQueueRuntimeCommands
import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.BridgeEventSink
import com.kutv.music.bridge.handlers.AppSnapshotHandler
import com.kutv.music.bridge.handlers.AppLifecycleHandler
import com.kutv.music.bridge.handlers.AuthBridgeHandler
import com.kutv.music.bridge.handlers.BrowseBridgeHandler
import com.kutv.music.bridge.handlers.CapabilityBridgeHandler
import com.kutv.music.bridge.handlers.DetailBridgeHandler
import com.kutv.music.bridge.handlers.HomeHandler
import com.kutv.music.bridge.handlers.LibraryBridgeHandler
import com.kutv.music.bridge.handlers.PlayerBridgeHandler
import com.kutv.music.bridge.handlers.PluginBridgeHandler
import com.kutv.music.bridge.handlers.RecognitionBridgeHandler
import com.kutv.music.bridge.handlers.SettingsBridgeHandler
import com.kutv.music.bridge.handlers.ShareBridgeHandler
import com.kutv.music.bridge.handlers.VideoBridgeHandler
import com.kutv.music.bridge.mappers.PlayerJsonMapper
import com.kutv.music.core.model.Track
import com.kutv.music.direct.KugouHttpClient
import com.kutv.music.player.MusicService
import com.kutv.music.player.PlaybackQueueRuntime
import com.kutv.music.video.KugouMvStreamResolver
import com.kutv.music.video.MvPlaybackController
import com.kutv.music.video.MvRouteGate
import com.kutv.music.video.MvStreamResolver
import com.kutv.music.video.TvVideoOverlay
import com.kutv.music.web.TvWebViewHost
import com.kutv.music.web.TvPageRecoveryPolicy
import com.kutv.music.web.TvFallbackView
import com.kutv.music.web.webKeyCodeForAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONObject

open class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var rootView: View? = null
    private var bridgeRouter: BridgeRouter? = null
    private val pageRecovery = TvPageRecoveryPolicy()
    private var foreground = false
    private var destroyingUi = false
    private var wasBackgrounded = false
    private var recoveryPending = false
    private var pageGeneration = 0L
    private var pageHealthJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var musicController: MediaController? = null
    private var playbackFacade: PlaybackControllerFacade? = null
    private var playbackEventsJob: Job? = null
    private var bridgeEventSink: BridgeEventSink? = null
    private var playbackErrorsJob: Job? = null
    private var mvPlaybackController: MvPlaybackController? = null
    private var mvRouteGate: MvRouteGate? = null
    private var videoOverlay: TvVideoOverlay? = null
    private var mvRouteActive = false
    private var authPollJob: Job? = null
    private val authUseCases by lazy {
        AuthUseCases(AppGraph.sessionManager, AndroidQrImageRenderer::render)
    }

    private var recordAudioContinuation: CompletableDeferred<Boolean>? = null
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        recordAudioContinuation?.complete(granted)
        recordAudioContinuation = null
    }

    private suspend fun requestRecordAudio(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        recordAudioContinuation = deferred
        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return deferred.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageRecovery.remember(savedInstanceState?.getString(SAVED_PAGE_URL))
        AppGraph.initialize(applicationContext)
        val homeUseCase = createHomeUseCase()
        val playerHandler = PlayerBridgeHandler { playbackFacade }
        val routeGate = MvRouteGate(
            closeNativeMv = { mvPlaybackController?.close() },
            onMvRouteChanged = ::onMvRouteChanged,
        )
        mvRouteGate = routeGate
        val videoHandler = VideoBridgeHandler({ mvPlaybackController }, routeGate, lifecycleScope)
        val authHandler = AuthBridgeHandler(
            useCases = authUseCases,
            onQrFlowStarted = ::startQrPolling,
            onQrFlowStopped = ::stopQrPolling,
            onLoggedOut = ::clearPlaybackForLogout,
        )
        val contentRepository = AppGraph.tvContentRepository
        val settingsHandler = SettingsBridgeHandler(
            com.kutv.music.application.settings.TvSettingsRepository(
                com.kutv.music.direct.SharedPreferencesDirectKeyValueStore(applicationContext),
            ),
        )
        val capabilityHandler = CapabilityBridgeHandler(
            com.kutv.music.application.capability.TvCapabilityDetector(
                packageManager = com.kutv.music.application.capability.AndroidPackageManagerPort(applicationContext),
                inputMethod = com.kutv.music.application.capability.AndroidInputMethodPort(applicationContext),
                permissionChecker = com.kutv.music.application.capability.AndroidPermissionChecker(applicationContext),
                visualEffects = com.kutv.music.application.capability.AndroidVisualEffectsPort(applicationContext),
            ),
        )
        val pluginHandler = PluginBridgeHandler(
            com.kutv.music.application.plugins.TvPluginRegistry(
                assetReader = { relativePath ->
                    runCatching {
                        assets.open("tv-plugins/$relativePath").bufferedReader().use { it.readText() }
                    }.getOrNull()
                },
                directoryLister = { relativePath ->
                    runCatching { assets.list("tv-plugins/$relativePath")?.toList() }.getOrNull() ?: emptyList()
                },
            ),
        )
        val recognitionUseCase = com.kutv.music.application.recognition.RecognitionUseCase(
            recorder = com.kutv.music.application.recognition.AndroidPcmRecorder(
                permissionGranted = {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                },
            ),
            api = com.kutv.music.direct.recognition.DirectKugouRecognitionApi(
                rawExecutor = AppGraph.httpClient,
                userIdProvider = { AppGraph.credentialStore.loadCredentials()?.userId?.toLongOrNull() ?: 0L },
            ),
            permissionRequester = ::requestRecordAudio,
        )
        val recognitionHandler = RecognitionBridgeHandler(
            useCase = recognitionUseCase,
            scope = lifecycleScope,
            onState = { projection ->
                bridgeEventSink?.emit("recognition.stateChanged", projection)
            },
        )
        val router = BridgeRouter(
            mapOf(
                "app.getSnapshot" to AppSnapshotHandler(AppSnapshotUseCase(homeUseCase, AppGraph.sessionManager)),
                "content.home" to HomeHandler(homeUseCase),
            ) + AppLifecycleHandler(
                exit = ::requestAppExit,
                showIme = ::showTvIme,
                hideIme = ::hideTvIme,
                readLicense = { filename ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assets.open("licenses/$filename").bufferedReader().use { it.readText() }
                    }
                },
            ).handlers() +
                playerHandler.handlers() + videoHandler.handlers() + authHandler.handlers() +
                BrowseBridgeHandler(contentRepository).handlers() +
                DetailBridgeHandler(contentRepository).handlers() +
                LibraryBridgeHandler(contentRepository).handlers() +
                ShareBridgeHandler(contentRepository).handlers() +
                settingsHandler.handlers() +
                capabilityHandler.handlers() +
                recognitionHandler.handlers() +
                pluginHandler.handlers(),
        )
        bridgeRouter = router
        installWebUi()
        connectPlaybackController()
        var imeVisible = false
        // Resolve the current view on each callback: recovery replaces the old WebView.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val dismissed = imeVisible && !visible
            imeVisible = visible
            if (dismissed) webView?.let { target -> target.post {
                if (!isDestroyed && webView === target && !imeVisible) target.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('echo-tv-ime-hidden'))", null,
                )
            } }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        onBackPressedDispatcher.addCallback(this) {
            mvPlaybackController?.close()
            webView?.let(::dispatchWebBack) ?: requestAppExit()
        }
    }

    private fun installWebUi() {
        val router = bridgeRouter ?: return
        val generation = ++pageGeneration
        val webHost = createTvWebHost(router) { url ->
            if (generation == pageGeneration) {
                pageRecovery.remember(url)
                mvRouteGate?.observe(url)
            }
        }
        webView = webHost as? WebView
        rootView = webView?.let { target ->
            val player = ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
            }
            val overlay = TvVideoOverlay(
                context = this,
                playerView = PlayerView(this).apply { this.player = player },
                webView = target,
            )
            videoOverlay = overlay
            mvPlaybackController = MvPlaybackController(
                resolver = createMvStreamResolver(),
                player = player,
                ownership = AppGraph.playbackOwnership,
                admission = AppGraph.mvResolverAdmission,
                onVisibilityChanged = { visible ->
                    if (visible) overlay.showMv() else overlay.hideMv()
                },
            ).apply { setForeground(foreground) }
            overlay
        } ?: webHost
        bridgeEventSink = BridgeEventSink(
            publish = { message -> webView?.let { TvWebViewHost.publish(it, message) } },
        )
        bridgeEventSink?.setMusicPositionEventsEnabled(!mvRouteActive)
        // A replacement bridge needs a full snapshot, even if native music has not changed.
        playbackFacade?.state?.value?.let { bridgeEventSink?.emitPlayerSnapshot(it) }
        setContentView(rootView)
        recoveryPending = false
        if (foreground) resumeWebUi(checkHealth = true)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        mvPlaybackController?.setForeground(true)
        if (recoveryPending) installWebUi() else resumeWebUi(checkHealth = wasBackgrounded)
        wasBackgrounded = false
    }

    override fun onPause() {
        foreground = false
        wasBackgrounded = true
        pageHealthJob?.cancel()
        pageHealthJob = null
        mvPlaybackController?.setForeground(false)
        webView?.onPause()
        // Do not pauseTimers(): it is process-global and can stall bridge promises.
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_PAGE_URL, pageRecovery.lastUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && foreground) webView?.let { target -> target.post {
            if (webView === target && foreground && !destroyingUi) {
                target.requestLayout()
                target.invalidate()
            }
        } }
    }

    private fun resumeWebUi(checkHealth: Boolean) {
        val target = webView ?: return
        target.onResume()
        target.resumeTimers()
        target.requestLayout()
        target.invalidate()
        videoOverlay?.requestLayout()
        videoOverlay?.invalidate()
        // Do not steal DOM focus/scroll on an ordinary return from Home.
        if (checkHealth) checkResumedPage(target)
    }

    private fun checkResumedPage(target: WebView) {
        pageHealthJob?.cancel()
        val generation = pageGeneration
        val ticket = SystemClock.uptimeMillis()
        pageHealthJob = lifecycleScope.launch {
            val healthy = withTimeoutOrNull(PAGE_HEALTH_TIMEOUT_MS) {
                delay(250L) // Give the TV compositor a frame after attaching its surface.
                if (webView !== target || !foreground) return@withTimeoutOrNull false
                var visualReady = false
                target.postVisualStateCallback(ticket, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (webView === target && foreground && generation == pageGeneration) {
                            visualReady = true
                            target.invalidate()
                        }
                    }
                })
                target.evaluateJavascript(
                    """(()=>{
                        window.dispatchEvent(new CustomEvent('echo-tv-input-cancel'));
                        window.dispatchEvent(new CustomEvent('echo-tv-resume'));
                        const painted=()=>{
                            const c=document.querySelector('[data-tv-app="ready"]');
                            const r=c?.getBoundingClientRect();
                            if(r?.width>100 && r?.height>100 && getComputedStyle(c).visibility!=='hidden')
                                window.__echoTvResumePaint=$ticket;
                            else requestAnimationFrame(painted);
                        };
                        requestAnimationFrame(()=>requestAnimationFrame(painted));
                    })()""".trimIndent(), null,
                )
                while (isActive && foreground && webView === target) {
                    delay(200L)
                    val ready = suspendCancellableCoroutine<Boolean> { continuation ->
                        target.evaluateJavascript("window.__echoTvResumePaint === $ticket") { value ->
                            if (continuation.isActive) continuation.resume(value == "true")
                        }
                    }
                    if (ready && visualReady) return@withTimeoutOrNull true
                }
                false
            } == true
            if (webView !== target || generation != pageGeneration || !foreground || destroyingUi) return@launch
            if (healthy) pageRecovery.markHealthy() else {
                android.util.Log.w("KU·TV", "web_foreground_health_failed")
                // A hung renderer can be shared with a fresh WebView. Terminate it first
                // where supported; onRenderProcessGone owns cleanup and reconstruction.
                val terminated = android.os.Build.VERSION.SDK_INT >= 29 &&
                    target.webViewRenderProcess?.terminate() == true
                if (terminated) {
                    delay(RENDERER_EXIT_TIMEOUT_MS)
                    if (webView === target && foreground) recoverWebUi(target, rendererGone = false)
                } else recoverWebUi(target, rendererGone = false)
            }
        }
    }

    private fun onRendererGone(target: WebView) {
        android.util.Log.w("KU·TV", "web_renderer_gone")
        if (webView !== target) {
            TvWebViewHost.dispose(target, rendererGone = true)
            return
        }
        recoverWebUi(target, rendererGone = true)
    }

    private fun recoverWebUi(target: WebView, rendererGone: Boolean) {
        if (webView !== target || destroyingUi) return
        pageHealthJob?.cancel()
        pageHealthJob = null
        disposeWebUi(rendererGone)
        stopQrPolling()
        val automatic = pageRecovery.beginRecovery()
        rootView = TvFallbackView.recovery(this, if (automatic) null else {
            {
                pageRecovery.markHealthy()
                recoveryPending = true
                if (foreground) installWebUi()
            }
        })
        setContentView(rootView)
        recoveryPending = automatic
        // Do not create/launch a renderer while the TV launcher is in front.
        if (foreground && automatic) window.decorView.post {
            if (foreground && recoveryPending && !destroyingUi) installWebUi()
        }
    }

    private fun disposeWebUi(rendererGone: Boolean = false) {
        pageGeneration += 1L
        mvRouteGate?.observe(null)
        mvPlaybackController?.destroy()
        mvPlaybackController = null
        videoOverlay?.hideMv()
        val target = webView
        webView = null
        bridgeEventSink = null
        if (target != null) TvWebViewHost.dispose(target, rendererGone)
        videoOverlay?.removeAllViews()
        videoOverlay = null
        rootView = null
    }

    override fun onDestroy() {
        destroyingUi = true
        pageHealthJob?.cancel()
        pageHealthJob = null
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView, null)
        stopQrPolling()
        disposeWebUi()
        mvRouteGate = null
        bridgeRouter = null
        val facade = playbackFacade
        playbackFacade = null
        playbackEventsJob?.cancel()
        playbackEventsJob = null
        playbackErrorsJob?.cancel()
        playbackErrorsJob = null
        bridgeEventSink = null
        if (facade != null) {
            facade.close()
        } else {
            controllerFuture?.let(MediaController::releaseFuture)
        }
        musicController = null
        controllerFuture = null
        super.onDestroy()
    }

    private suspend fun startQrPolling() {
        stopQrPolling()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        authPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTH_POLL_INTERVAL_MS)
                val projection = authUseCases.pollOnce()
                bridgeEventSink?.emit("auth.stateChanged", AuthJsonMapper.projection(projection))
                if (projection.state != "qrPending" && projection.state != "scanned") break
            }
        }
    }

    private fun stopQrPolling() {
        authPollJob?.cancel()
        authPollJob = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    private suspend fun clearPlaybackForLogout() {
        try {
            PlaybackQueueRuntime.clear()?.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            android.util.Log.w("KU·TV", "playback_logout_clear_failed")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching(AppGraph.playbackResumeStore::clear)
                    .onFailure { android.util.Log.w("KU·TV", "playback_resume_logout_clear_failed") }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun connectPlaybackController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isDestroyed || future.isCancelled) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                val controller = runCatching(future::get).getOrNull() ?: return@addListener
                musicController = controller
                playbackFacade = PlaybackControllerFacade(
                    player = Media3PlayerPort(controller),
                    queueCommands = PlaybackQueueRuntimeCommands(::findPlaybackTrack),
                    lyricsRepository = MusicRepositoryLyricsRepository(AppGraph.musicRepository),
                    scope = lifecycleScope,
                    errorStore = PlaybackErrorRuntime.store,
                )
                playbackFacade?.let(::startPlaybackEvents)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun startPlaybackEvents(facade: PlaybackControllerFacade) {
        playbackEventsJob?.cancel()
        playbackEventsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    facade.state.collect { snapshot ->
                        facade.setPositionUpdatesRequested(snapshot.currentTrack != null && !mvRouteActive)
                        bridgeEventSink?.emitPlayerSnapshot(snapshot)
                    }
                } finally {
                    facade.setPositionUpdatesRequested(false)
                }
            }
        }
        playbackErrorsJob?.cancel()
        playbackErrorsJob = lifecycleScope.launch {
            val mapper = PlayerJsonMapper()
            PlaybackErrorRuntime.store.state.collect { errorState ->
                errorState.error?.let { error ->
                    bridgeEventSink?.emit("app.error", mapper.error(error))
                }
            }
        }
    }

    private fun onMvRouteChanged(active: Boolean) {
        mvRouteActive = active
        bridgeEventSink?.setMusicPositionEventsEnabled(!active)
        playbackFacade?.let { facade ->
            facade.setPositionUpdatesRequested(facade.state.value.currentTrack != null && !active)
        }
    }

    private suspend fun findPlaybackTrack(trackId: String): Track? {
        AppGraph.directApi.track(trackId)?.let { return it }
        val home = AppGraph.musicRepository.bootstrapHome() ?: return null
        return sequenceOf(home.hero)
            .plus(home.secondary.asSequence())
            .plus(home.shelves.asSequence().flatMap { it.tracks.asSequence() })
            .firstOrNull { it.id == trackId }
    }

    private fun dispatchWebBack(target: WebView) {
        val webKeyCode = webKeyCodeForAndroid(KeyEvent.KEYCODE_BACK) ?: return
        val eventTime = SystemClock.uptimeMillis()
        target.dispatchKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, webKeyCode, 0))
        target.dispatchKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, webKeyCode, 0))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @SuppressLint("ImplicitSamInstance") // stopService resolves an explicit component; it does not compare listener identity.
    private fun requestAppExit() {
        runOnUiThread {
            musicController?.pause()
            stopService(Intent(this, MusicService::class.java))
            finishAndRemoveTask()
        }
    }

    private fun showTvIme() {
        val target = webView ?: return
        target.post {
            // Re-requesting native focus when already focused makes Chromium
            // move DOM focus back to the first navigation link.
            if (!target.hasFocus()) target.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideTvIme() {
        val target = webView ?: return
        target.post {
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(target.windowToken, 0)
            target.postDelayed(
                {
                    target.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('echo-tv-ime-hidden'))",
                        null,
                    )
                },
                IME_FOCUS_SETTLE_MS,
            )
        }
    }

    protected open fun createMvStreamResolver(): MvStreamResolver {
        val client = KugouHttpClient(
            deviceProvider = AppGraph.deviceStore::getOrCreate,
            credentialProvider = AppGraph.credentialStore::loadCredentials,
        )
        return KugouMvStreamResolver(
            executor = client,
            deviceProvider = AppGraph.deviceStore::getOrCreate,
            credentialProvider = AppGraph.credentialStore::loadCredentials,
        )
    }

    protected open fun createHomeUseCase(): HomeUseCase = HomeUseCase(AppGraph.musicRepository)

    protected open fun createTvWebHost(router: BridgeRouter, routeObserved: (String?) -> Unit): View =
        TvWebViewHost.createView(this, lifecycleScope, router, routeObserved,
            rendererGone = ::onRendererGone, initialUrl = pageRecovery.lastUrl)

    private companion object {
        const val AUTH_POLL_INTERVAL_MS = 2_000L
        const val IME_FOCUS_SETTLE_MS = 120L
        const val SAVED_PAGE_URL = "tv.local.page"
        const val PAGE_HEALTH_TIMEOUT_MS = 8_000L
        const val RENDERER_EXIT_TIMEOUT_MS = 2_000L
    }
}
