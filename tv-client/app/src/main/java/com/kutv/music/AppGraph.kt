package com.kutv.music

import android.content.Context
import com.kutv.music.application.auth.AndroidQrImageRenderer
import com.kutv.music.application.content.SharePresentationUseCase
import com.kutv.music.data.KugouDirectMusicRepository
import com.kutv.music.data.KugouTvContentRepository
import com.kutv.music.data.MusicRepository
import com.kutv.music.data.content.KugouBrowseDomain
import com.kutv.music.data.content.KugouDetailDomain
import com.kutv.music.data.content.KugouLibraryDomain
import com.kutv.music.direct.EncryptedKugouCredentialStore
import com.kutv.music.direct.KugouCredentialStore
import com.kutv.music.direct.KugouCrypto
import com.kutv.music.direct.KugouDeviceRegistrar
import com.kutv.music.direct.KugouDeviceStore
import com.kutv.music.direct.KugouDirectApi
import com.kutv.music.direct.KugouDirectAuth
import com.kutv.music.direct.KugouHttpClient
import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.SharedPreferencesDirectKeyValueStore
import com.kutv.music.direct.content.KugouBrowseApi
import com.kutv.music.direct.content.KugouCommentApi
import com.kutv.music.direct.content.KugouDetailApi
import com.kutv.music.direct.content.KugouLibraryApi
import com.kutv.music.player.KugouDirectPlaybackResolver
import com.kutv.music.player.EncryptedPlaybackResumeStore
import com.kutv.music.player.PlaybackResumeStore
import com.kutv.music.player.QueueTrackResolver
import com.kutv.music.video.PlaybackOwnership
import com.kutv.music.video.MvResolverAdmission

object AppGraph {
    @Volatile
    private var initialized = false

    lateinit var credentialStore: KugouCredentialStore
        private set

    lateinit var deviceStore: KugouDeviceStore
        private set

    lateinit var directApi: KugouDirectApi
        private set

    lateinit var httpClient: KugouHttpClient
        private set

    lateinit var sessionManager: KugouSessionManager
        private set

    lateinit var musicRepository: MusicRepository
        private set

    lateinit var tvContentRepository: KugouTvContentRepository
        private set

    lateinit var playbackResolver: QueueTrackResolver
        private set

    lateinit var playbackResumeStore: PlaybackResumeStore
        private set

    val playbackOwnership = PlaybackOwnership()
    val mvResolverAdmission = MvResolverAdmission()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            credentialStore = EncryptedKugouCredentialStore(appContext)
            deviceStore = KugouDeviceStore(SharedPreferencesDirectKeyValueStore(appContext))
            val client = KugouHttpClient(
                deviceProvider = deviceStore::getOrCreate,
                credentialProvider = credentialStore::loadCredentials,
            )
            httpClient = client
            val registrar = KugouDeviceRegistrar(deviceStore, client)
            val auth = KugouDirectAuth(client)
            sessionManager = KugouSessionManager(auth, credentialStore)
            directApi = KugouDirectApi(
                executor = client,
                credentialProvider = credentialStore::loadCredentials,
                deviceProvider = deviceStore::getOrCreate,
                ensureDeviceRegistered = { registrar.ensureRegistered() },
            )
            musicRepository = KugouDirectMusicRepository(
                gateway = directApi,
            )
            val midProvider = { KugouCrypto.deriveMid(deviceStore.getOrCreate().mid) }
            val browseDomain = KugouBrowseDomain(
                api = KugouBrowseApi(client, midProvider),
                rememberTracks = directApi::rememberTracks,
                trackLookup = directApi::track,
            )
            val detailDomain = KugouDetailDomain(
                api = KugouDetailApi(
                    executor = client,
                    credentialsProvider = credentialStore::loadCredentials,
                    midProvider = midProvider,
                ),
                commentApi = KugouCommentApi(client),
                rememberTracks = directApi::rememberTracks,
                trackLookup = directApi::track,
            )
            val libraryDomain = KugouLibraryDomain(
                api = KugouLibraryApi(
                    executor = client,
                    rawExecutor = client,
                    credentialsProvider = credentialStore::loadCredentials,
                    deviceProvider = deviceStore::getOrCreate,
                ),
                rememberTracks = directApi::rememberTracks,
                trackLookup = directApi::track,
                sharePresentation = SharePresentationUseCase(AndroidQrImageRenderer),
            )
            tvContentRepository = KugouTvContentRepository(
                browseDomain = browseDomain,
                detailDomain = detailDomain,
                libraryDomain = libraryDomain,
            )
            val tvPreferences = com.kutv.music.application.settings.TvSettingsRepository(
                com.kutv.music.direct.SharedPreferencesDirectKeyValueStore(appContext))
            playbackResolver = KugouDirectPlaybackResolver(directApi) { tvPreferences.get().defaultQuality }
            playbackResumeStore = EncryptedPlaybackResumeStore(appContext)
            initialized = true
        }
    }
}
