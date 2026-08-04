package com.streamvault.app

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.streamvault.app.cast.CastManager
import com.streamvault.app.cast.CastRouteChooserActivity
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.localization.resolveAppLocale
import com.streamvault.app.navigation.AppNavigation
import com.streamvault.app.navigation.ExternalNavigationRequest
import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.ui.pip.LocalIsInPip
import com.streamvault.app.ui.pip.PictureInPictureController
import com.streamvault.app.ui.theme.StreamVaultTheme
import dagger.hilt.android.AndroidEntryPoint

import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.core.view.WindowCompat
import java.util.Locale
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.content.res.AssetManager
import android.content.res.Resources
import android.speech.RecognizerIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PLAYER_REQUEST = "com.streamvault.app.extra.PLAYER_REQUEST"
        const val EXTRA_EXTERNAL_ROUTE = "com.streamvault.app.extra.EXTERNAL_ROUTE"
    }

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var watchNextManager: WatchNextManager

    @Inject
    lateinit var launcherRecommendationsManager: LauncherRecommendationsManager

    @Inject
    lateinit var tvInputChannelSyncManager: TvInputChannelSyncManager

    @Inject
    lateinit var castManager: CastManager

    private val _externalNavigationRequestFlow = MutableStateFlow<ExternalNavigationRequest?>(null)
    val externalNavigationRequestFlow: StateFlow<ExternalNavigationRequest?> =
        _externalNavigationRequestFlow.asStateFlow()

    // ── Picture-in-Picture (mobile only) ──────────────────────────────────
    val pipController: PictureInPictureController by lazy { PictureInPictureController(this) }

    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    /** Set by the player so PiP action buttons can drive the running playback. */
    var onPipControl: ((Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
        super.onCreate(savedInstanceState)
        // Disable legacy window-fitting so Compose receives IME insets directly.
        // This fixes keyboard-covers-input-field on API 30+ where adjustResize is
        // ignored when the theme sets windowFullscreen=true.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // NOTE: enableEdgeToEdge() was called here for the mobile variant and has
        // been removed. It was largely redundant — setDecorFitsSystemWindows(false)
        // above already hands insets to Compose for every variant, and
        // MobileScreenScaffold applies WindowInsets.systemBars itself — while
        // adding a window-level variable (transparent system bars, contrast
        // enforcement) to a build that showed "audio plays, no picture". The main
        // engine renders into a SurfaceView, which is composited below the window
        // and is sensitive to window opacity, so the mobile window is kept as
        // close to the known-good TV configuration as possible.
        if (pipController.isSupported) {
            // The window itself is left untouched — PiP needs no window flags,
            // only PictureInPictureParams. Anything that made the window
            // translucent would break the SurfaceView rendering described above.
            addOnPictureInPictureModeChangedListener { info ->
                _isInPip.value = info.isInPictureInPictureMode
            }
            pipController.registerControls { control -> onPipControl?.invoke(control) }
        }
        handleExternalIntent(intent)
        if (isTelevisionDevice()) {
            lifecycleScope.launch {
                watchNextManager.refreshWatchNext()
                launcherRecommendationsManager.refreshRecommendations()
                tvInputChannelSyncManager.refreshTvInputCatalog()
            }
        }
        setContent {
            val appLanguage by preferencesRepository.appLanguage.collectAsStateWithLifecycle(initialValue = "system")
            val currentContext = LocalContext.current
            
            val configuration = remember(appLanguage) {
                val locale = resolveAppLocale(
                    preferredLanguageTag = appLanguage,
                    baseConfiguration = this@MainActivity.resources.configuration
                )
                val conf = Configuration(this@MainActivity.resources.configuration)
                Locale.setDefault(locale)
                conf.setLocale(locale)
                conf.setLayoutDirection(locale)
                conf
            }
            val localizedContext = remember(configuration, currentContext) {
                val configurationContext = currentContext.createConfigurationContext(configuration)
                object : ContextWrapper(currentContext) {
                    override fun getResources(): Resources = configurationContext.resources
                    override fun getAssets(): AssetManager = configurationContext.assets
                    override fun getSystemService(name: String): Any? {
                        return if (name == Context.LAYOUT_INFLATER_SERVICE) {
                            configurationContext.getSystemService(name)
                        } else {
                            super.getSystemService(name)
                        }
                    }
                }
            }

            val layoutDirection = remember(configuration) {
                if (TextUtils.getLayoutDirectionFromLocale(configuration.locales[0]) == View.LAYOUT_DIRECTION_RTL) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                }
            }

            val inPip by isInPip.collectAsStateWithLifecycle()

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalLayoutDirection provides layoutDirection,
                LocalIsInPip provides inPip
            ) {
                StreamVaultTheme {
                    AppNavigation(mainActivity = this@MainActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * Pre-API-31 fallback for auto-enter.
     *
     * From API 31 the system handles this via `setAutoEnterEnabled`, and this
     * callback is NOT delivered reliably under gesture navigation — which is
     * precisely why auto-enter was introduced. Calling it on 31+ as well would
     * risk entering PiP twice.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            pipController.enterIfPlaying()
        }
    }

    override fun onDestroy() {
        pipController.unregisterControls()
        onPipControl = null
        super.onDestroy()
    }

    fun clearExternalNavigationRequest() {
        _externalNavigationRequestFlow.value = null
    }

    fun openPlayer(request: PlayerNavigationRequest) {
        _externalNavigationRequestFlow.value = ExternalNavigationRequest.Player(request)
    }

    fun openCastRouteChooser() {
        startActivity(Intent(this, CastRouteChooserActivity::class.java))
    }

    private fun handleExternalIntent(intent: Intent?) {
        val request = intent?.toExternalNavigationRequest() ?: return
        _externalNavigationRequestFlow.value = request
    }

    private fun Intent.toExternalNavigationRequest(): ExternalNavigationRequest? {
        readPlayerRequestExtra()?.let { return ExternalNavigationRequest.Player(it) }
        getStringExtra(EXTRA_EXTERNAL_ROUTE)?.let { return ExternalNavigationRequest.Route(it) }
        readImportedPlaylistUri()?.let { return ExternalNavigationRequest.ImportM3u(it) }

        val query = when (action) {
            Intent.ACTION_SEARCH,
            Intent.ACTION_ASSIST,
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE -> {
                getStringExtra(SearchManager.QUERY)
                    ?: getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            }

            else -> null
        }?.trim().orEmpty()

        return query.takeIf { it.isNotBlank() }?.let(ExternalNavigationRequest::Search)
    }

    private fun Intent.readImportedPlaylistUri(): String? {
        if (action != Intent.ACTION_VIEW) return null
        val targetUri = data ?: return null
        val normalizedPath = targetUri.toString().substringBefore('?').lowercase(Locale.ROOT)
        val mimeType = type?.lowercase(Locale.ROOT).orEmpty()
        val isPlaylistMime = mimeType in setOf(
            "audio/x-mpegurl",
            "audio/mpegurl",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/mpegurl"
        )
        val isPlaylistPath = normalizedPath.endsWith(".m3u") || normalizedPath.endsWith(".m3u8")
        if (!isPlaylistMime && !isPlaylistPath) return null
        return when (targetUri.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> targetUri.toString()
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.readPlayerRequestExtra(): PlayerNavigationRequest? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(EXTRA_PLAYER_REQUEST, PlayerNavigationRequest::class.java)
        } else {
            getSerializableExtra(EXTRA_PLAYER_REQUEST) as? PlayerNavigationRequest
        }
    }
}
