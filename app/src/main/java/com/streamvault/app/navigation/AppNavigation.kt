package com.streamvault.app.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.streamvault.app.ui.permissions.RequestNotificationPermission
import com.streamvault.app.ui.design.FormFactor
import com.streamvault.app.ui.design.LocalFormFactor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.startup.SyncSnackbarEvent
import com.streamvault.app.startup.SyncSnackbarHolderViewModel
import com.streamvault.app.ui.screens.dashboard.DashboardScreen
import com.streamvault.app.ui.screens.home.HomeScreen
import com.streamvault.app.ui.screens.movies.MoviesScreen
import com.streamvault.app.ui.screens.multiview.MultiViewScreen
import com.streamvault.app.ui.screens.player.PlayerScreen
import com.streamvault.app.ui.screens.provider.ProviderSetupScreen
import com.streamvault.app.ui.screens.series.SeriesScreen
import com.streamvault.app.ui.screens.activation.ActivationScreen
import com.streamvault.app.ui.screens.settings.SettingsScreen
import com.streamvault.app.ui.screens.update.AppUpdateStartupDialog
import com.streamvault.app.ui.screens.update.AppUpdateStartupViewModel
import com.streamvault.app.ui.screens.welcome.WelcomeScreen
import java.io.Serializable


private const val PLAYER_REQUEST_KEY = "player_request"

data class PlayerNavigationRequest(
    val streamUrl: String,
    val title: String,
    val channelId: String? = null,
    val internalId: Long = -1L,
    val categoryId: Long? = null,
    val providerId: Long? = null,
    val isVirtual: Boolean = false,
    val contentType: String = "LIVE",
    val artworkUrl: String? = null,
    val archiveStartMs: Long? = null,
    val archiveEndMs: Long? = null,
    val archiveTitle: String? = null,
    val returnRoute: String? = null,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) : Serializable

object Routes {
    const val PROVIDER_SETUP = "provider_setup?providerId={providerId}&importUri={importUri}"
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val EPG = "epg"
    const val EPG_DESTINATION = "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val ACTIVATION = "activation"
    const val WELCOME = "welcome"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
    const val MULTI_VIEW = "multi_view"


    fun providerSetup(providerId: Long? = null, importUri: String? = null): String {
        val encodedImportUri = Uri.encode(importUri ?: "")
        return "provider_setup?providerId=${providerId ?: -1L}&importUri=$encodedImportUri"
    }
    fun liveTv(categoryId: Long? = null) = if (categoryId == null) LIVE_TV else "$LIVE_TV?categoryId=$categoryId"
    fun epg(categoryId: Long? = null, anchorTime: Long? = null, favoritesOnly: Boolean? = null): String {
        val resolvedCategoryId = categoryId ?: -1L
        val resolvedAnchorTime = anchorTime ?: -1L
        val resolvedFavoritesOnly = favoritesOnly ?: false
        return "$EPG?categoryId=$resolvedCategoryId&anchorTime=$resolvedAnchorTime&favoritesOnly=$resolvedFavoritesOnly"
    }

    fun livePlayer(
        channel: Channel,
        categoryId: Long? = channel.categoryId,
        providerId: Long? = channel.providerId,
        isVirtual: Boolean = false,
        returnRoute: String? = null
    ): PlayerNavigationRequest {
        val effectiveCategoryId = categoryId ?: ChannelRepository.ALL_CHANNELS_ID
        return player(
            streamUrl = channel.streamUrl,
            title = channel.name,
            channelId = channel.epgChannelId,
            internalId = channel.id,
            categoryId = effectiveCategoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            contentType = "LIVE",
            returnRoute = returnRoute
        )
    }

    fun moviePlayer(movie: Movie): PlayerNavigationRequest {
        return player(
            streamUrl = movie.streamUrl,
            title = movie.name,
            internalId = movie.id,
            categoryId = movie.categoryId,
            providerId = movie.providerId,
            contentType = "MOVIE",
            artworkUrl = movie.posterUrl ?: movie.backdropUrl
        )
    }

    fun episodePlayer(episode: Episode): PlayerNavigationRequest {
        return player(
            streamUrl = episode.streamUrl,
            title = "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}",
            internalId = episode.id,
            providerId = episode.providerId,
            contentType = "SERIES_EPISODE",
            artworkUrl = episode.coverUrl,
            seriesId = episode.seriesId.takeIf { it > 0L },
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber
        )
    }

    fun search(query: String? = null): String =
        if (query.isNullOrBlank()) SEARCH else "$SEARCH?query=${Uri.encode(query)}"

    fun player(
        streamUrl: String,
        title: String,
        channelId: String? = null,
        internalId: Long = -1L,
        categoryId: Long? = null,
        providerId: Long? = null,
        isVirtual: Boolean = false,
        contentType: String = "LIVE",
        artworkUrl: String? = null,
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        returnRoute: String? = null,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): PlayerNavigationRequest {
        return PlayerNavigationRequest(
            streamUrl = streamUrl,
            title = title,
            channelId = channelId,
            internalId = internalId,
            categoryId = categoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            contentType = contentType,
            artworkUrl = artworkUrl,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            returnRoute = returnRoute,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    fun movieDetail(movieId: Long, returnRoute: String? = null) =
        "movie_detail/$movieId?returnRoute=${Uri.encode(returnRoute ?: "")}"
    fun seriesDetail(seriesId: Long, returnRoute: String? = null) =
        "series_detail/$seriesId?returnRoute=${Uri.encode(returnRoute ?: "")}"
    fun parentalControlGroups(providerId: Long) = "parental_control_groups/$providerId"
}

/** Accepts app-supported media schemes while still rejecting obviously unsafe ones. */
private fun isStreamUrlSafe(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = url.substringBefore("://").lowercase()
    return scheme in setOf("http", "https", "rtsp", "rtmp", "rtsps", "mms", "xtream", "content", "file")
}

/** Navigate only when the current destination is fully resumed – prevents double-navigation during transitions. */
private fun NavHostController.navigateIfResumed(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return
    navigate(route, builder)
}

private fun NavHostController.navigateToPlayer(request: PlayerNavigationRequest) {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
}

private fun NavHostController.navigateToExternalPlayer(request: PlayerNavigationRequest) {
    currentBackStackEntry?.savedStateHandle?.set(PLAYER_REQUEST_KEY, request)
    navigate(Routes.PLAYER) { launchSingleTop = true }
}

@Composable
fun AppNavigation(mainActivity: MainActivity) {
    val navController = rememberNavController()
    val externalNavigationRequest = mainActivity.externalNavigationRequestFlow.collectAsStateWithLifecycle().value

    // Watch the device activation token: if a background sync receives a 401
    // and the worker clears the activation, route the user back to the
    // activation screen instead of leaving them on a dead session.
    val authGate: AuthGateViewModel = hiltViewModel()
    val authState by authGate.authState.collectAsStateWithLifecycle()
    LaunchedEffect(authState) {
        if (authState != AuthGateViewModel.AuthState.REVOKED) return@LaunchedEffect
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (currentRoute == Routes.ACTIVATION || currentRoute == Routes.WELCOME) return@LaunchedEffect
        navController.navigate(Routes.ACTIVATION) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    LaunchedEffect(externalNavigationRequest) {
        when (val request = externalNavigationRequest) {
            is ExternalNavigationRequest.Player -> {
                navController.navigateToExternalPlayer(request.request)
                mainActivity.clearExternalNavigationRequest()
            }

            is ExternalNavigationRequest.Route -> {
                navController.navigate(request.route) { launchSingleTop = true }
                mainActivity.clearExternalNavigationRequest()
            }

            is ExternalNavigationRequest.ImportM3u -> {
                navController.navigate(Routes.providerSetup(importUri = request.uri)) { launchSingleTop = true }
                mainActivity.clearExternalNavigationRequest()
            }

            is ExternalNavigationRequest.Search -> {
                navController.navigate(Routes.search(request.query)) { launchSingleTop = true }
                mainActivity.clearExternalNavigationRequest()
            }

            null -> Unit
        }
    }

    // NAV-M02/NAV-H02: Single helper replacing repeated tab lambdas without serializing
    // each tab's full UI tree into saved state on every switch.
    fun tabNavigate(route: String) {
        val entry = navController.currentBackStackEntry ?: return
        if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val currentRoute = entry.destination?.route
        if (currentRoute == route || currentRoute?.startsWith("$route?") == true) return

        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val syncStartedMessage = stringResource(R.string.startup_sync_started)
    val syncSuccessMessage = stringResource(R.string.startup_sync_success)
    val syncFailedMessage = stringResource(R.string.startup_sync_failed)
    val snackbarHolderVm: SyncSnackbarHolderViewModel = hiltViewModel()

    LaunchedEffect(snackbarHolderVm) {
        snackbarHolderVm.controller.events.collect { event ->
            val message = when (event) {
                SyncSnackbarEvent.SyncStarted -> syncStartedMessage
                SyncSnackbarEvent.SyncSuccess -> syncSuccessMessage
                is SyncSnackbarEvent.SyncFailed -> syncFailedMessage
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val updateViewModel: AppUpdateStartupViewModel = hiltViewModel()
    val pendingUpdate by updateViewModel.pendingUpdate.collectAsStateWithLifecycle()
    val updateDownloadState by updateViewModel.downloadState.collectAsStateWithLifecycle()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val isBlockedForUpdate = currentRoute == Routes.ACTIVATION || currentRoute == Routes.WELCOME

    // Reuses the same gate as the update dialog: the notification prompt only
    // appears once the user is past activation and onboarding. Asking on the
    // first frame, before anything has been explained, is how you get denied
    // permanently. No-op on TV and below API 33.
    RequestNotificationPermission(
        shouldAsk = currentRoute != null && !isBlockedForUpdate
    )

    pendingUpdate?.takeUnless { isBlockedForUpdate }?.let { update ->
        AppUpdateStartupDialog(
            update = update,
            downloadState = updateDownloadState,
            onInstall = { updateViewModel.install() },
            onLater = { updateViewModel.dismissForThisVersion(update.versionCode) }
        )
    }

    // Pure fade transitions. SurfaceView (used by Media3 ExoPlayer for video
    // output) does not support Compose `graphicsLayer` transformations — any
    // scaleIn/scaleOut on the Player / MultiView routes turns the video black.
    // Fade-only is safe for all destinations including the player.
    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = Routes.ACTIVATION,
        enterTransition = { fadeIn(animationSpec = tween(durationMillis = 200)) },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 180)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) }
    ) {
        composable(Routes.ACTIVATION) {
            ActivationScreen(
                onActivated = dropUnlessResumed {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ACTIVATION) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.WELCOME) {
            WelcomeScreen(
                onNavigateToHome = dropUnlessResumed {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onNavigateToSetup = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup()) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.PROVIDER_SETUP,
            arguments = listOf(
                navArgument("providerId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("importUri") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getLong("providerId")?.takeIf { it != -1L }
            val importUri = backStackEntry.arguments?.getString("importUri")?.takeIf { it.isNotBlank() }
            
            ProviderSetupScreen(
                editProviderId = providerId,
                initialImportUri = importUri,
                onBack = { navController.popBackStack() },
                onProviderAdded = dropUnlessResumed {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PROVIDER_SETUP) { inclusive = true }
                    }
                }
            )
        }
// ...

        composable(Routes.HOME) {
            DashboardScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup(null))
                },
                onChannelClick = { channel ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            returnRoute = Routes.HOME
                        )
                    )
                },
                onFavoriteChannelClick = { channel ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
                            providerId = channel.providerId,
                            isVirtual = true,
                            returnRoute = Routes.HOME
                        )
                    )
                },
                onMovieClick = { movie ->
                    navController.navigateIfResumed(Routes.movieDetail(movie.id, Routes.HOME))
                },
                onSeriesClick = { series ->
                    navController.navigateIfResumed(Routes.seriesDetail(series.id, Routes.HOME))
                },
                onPlaybackHistoryClick = { history ->
                    val route = when (history.contentType) {
                        com.streamvault.domain.model.ContentType.LIVE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name,
                                returnRoute = Routes.HOME
                            )
                        }
                        com.streamvault.domain.model.ContentType.MOVIE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name,
                                returnRoute = Routes.HOME
                            )
                        }
                        com.streamvault.domain.model.ContentType.SERIES -> {
                            Routes.seriesDetail(history.seriesId ?: history.contentId, Routes.HOME)
                        }
                        com.streamvault.domain.model.ContentType.SERIES_EPISODE -> {
                            Routes.player(
                                streamUrl = history.streamUrl,
                                title = history.title,
                                internalId = history.contentId,
                                providerId = history.providerId,
                                contentType = history.contentType.name,
                                returnRoute = Routes.HOME,
                                seriesId = history.seriesId,
                                seasonNumber = history.seasonNumber,
                                episodeNumber = history.episodeNumber
                            )
                        }
                    }
                    if (route is PlayerNavigationRequest) {
                        navController.navigateToPlayer(route)
                    } else {
                        navController.navigateIfResumed(route as String) { launchSingleTop = true }
                    }
                },
                currentRoute = Routes.HOME
            )
        }

        composable(
            route = Routes.LIVE_TV_DESTINATION,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            val initialCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
            HomeScreen(
                onChannelClick = { channel, category, provider ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = category?.id,
                            providerId = provider?.id,
                            isVirtual = category?.isVirtual == true,
                            returnRoute = Routes.liveTv(category?.id)
                        )
                    )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.LIVE_TV,
                initialCategoryId = initialCategoryId
            )
        }
// ... (rest of file)

        composable(Routes.MOVIES) {
            MoviesScreen(
                onMovieClick = { movie ->
                    navController.navigateIfResumed(Routes.movieDetail(movie.id, Routes.MOVIES))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.MOVIES
            )
        }

        composable(Routes.SERIES) {
            SeriesScreen(
                onSeriesClick = { seriesId ->
                    navController.navigateIfResumed(Routes.seriesDetail(seriesId, Routes.SERIES))
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SERIES
            )
        }

        composable(
            route = Routes.EPG_DESTINATION,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("anchorTime") { type = NavType.LongType; defaultValue = -1L },
                navArgument("favoritesOnly") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val epgCategoryId = backStackEntry.arguments?.getLong("categoryId")?.takeIf { it != -1L }
            val epgAnchorTime = backStackEntry.arguments?.getLong("anchorTime")?.takeIf { it != -1L }
            val epgFavoritesOnly = backStackEntry.arguments?.getBoolean("favoritesOnly") ?: false
            com.streamvault.app.ui.screens.epg.FullEpgScreen(
                currentRoute = Routes.EPG,
                initialCategoryId = epgCategoryId,
                initialAnchorTime = epgAnchorTime,
                initialFavoritesOnly = epgFavoritesOnly,
                onPlayChannel = { channel, returnRoute ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            returnRoute = returnRoute
                        )
                    )
                },
                onPlayArchive = { channel, program, returnRoute ->
                    navController.navigateToPlayer(
                        Routes.player(
                            streamUrl = channel.streamUrl,
                            title = channel.name,
                            channelId = channel.epgChannelId,
                            internalId = channel.id,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            contentType = "LIVE",
                            archiveStartMs = program.startTime,
                            archiveEndMs = program.endTime,
                            archiveTitle = "${channel.name}: ${program.title}",
                            returnRoute = returnRoute
                        )
                    )
                },
                onNavigate = { route -> tabNavigate(route) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigate = { route -> tabNavigate(route) },
                onAddProvider = dropUnlessResumed {
                    navController.navigate(Routes.providerSetup(null))
                },
                onEditProvider = { provider ->
                    navController.navigateIfResumed(Routes.providerSetup(provider.id))
                },
                onNavigateToParentalControl = { providerId ->
                    navController.navigateIfResumed(Routes.parentalControlGroups(providerId))
                },
                currentRoute = Routes.SETTINGS
            )
        }

        composable(
            route = Routes.PARENTAL_CONTROL_GROUPS,
            arguments = listOf(
                navArgument("providerId") { type = NavType.LongType }
            )
        ) {
            com.streamvault.app.ui.screens.settings.parental.ParentalControlGroupScreen(
                currentRoute = Routes.SETTINGS,
                onNavigate = { route -> tabNavigate(route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SEARCH_DESTINATION,
            arguments = listOf(
                navArgument("query") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            com.streamvault.app.ui.screens.search.SearchScreen(
                initialQuery = backStackEntry.arguments?.getString("query").orEmpty(),
                onChannelClick = { channel ->
                    navController.navigateToPlayer(
                        Routes.livePlayer(
                            channel = channel,
                            categoryId = channel.categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
                            providerId = channel.providerId,
                            isVirtual = false,
                            returnRoute = Routes.search(backStackEntry.arguments?.getString("query").orEmpty())
                        )
                    )
                },
                onMovieClick = { movie ->
                     navController.navigateIfResumed(
                         Routes.movieDetail(movie.id, Routes.search(backStackEntry.arguments?.getString("query").orEmpty()))
                     )
                },
                onSeriesClick = { series ->
                     navController.navigateIfResumed(
                         Routes.seriesDetail(series.id, Routes.search(backStackEntry.arguments?.getString("query").orEmpty()))
                     )
                },
                onNavigate = { route -> tabNavigate(route) },
                currentRoute = Routes.SEARCH
            )
        }

        composable(route = Routes.PLAYER) { backStackEntry ->
            // `launchSingleTop = true` on navigateToPlayer means this composable
            // instance is reused on re-entry, and its savedStateHandle still holds
            // the PlayerNavigationRequest from the previous playback. Always prefer
            // the caller's freshly-set request (previousBackStackEntry) over our
            // own stale one. Fallback to our saved state for process-death restore.
            val callerRequest = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)
            if (callerRequest != null) {
                backStackEntry.savedStateHandle[PLAYER_REQUEST_KEY] = callerRequest
            }
            val playerRequest = backStackEntry.savedStateHandle
                .get<PlayerNavigationRequest>(PLAYER_REQUEST_KEY)
            val streamUrl = if (isStreamUrlSafe(playerRequest?.streamUrl)) playerRequest?.streamUrl.orEmpty() else ""
            PlayerScreen(
                streamUrl = streamUrl,
                title = playerRequest?.title.orEmpty(),
                epgChannelId = playerRequest?.channelId,
                internalChannelId = playerRequest?.internalId ?: -1L,
                categoryId = playerRequest?.categoryId,
                providerId = playerRequest?.providerId,
                isVirtual = playerRequest?.isVirtual ?: false,
                contentType = playerRequest?.contentType ?: "LIVE",
                artworkUrl = playerRequest?.artworkUrl,
                archiveStartMs = playerRequest?.archiveStartMs,
                archiveEndMs = playerRequest?.archiveEndMs,
                archiveTitle = playerRequest?.archiveTitle,
                returnRoute = playerRequest?.returnRoute,
                seriesId = playerRequest?.seriesId,
                seasonNumber = playerRequest?.seasonNumber,
                episodeNumber = playerRequest?.episodeNumber,
                onBack = {
                    val route = playerRequest?.returnRoute
                    if (!route.isNullOrBlank() && navController.popBackStack(route, false)) {
                        Unit
                    } else if (!route.isNullOrBlank()) {
                        navController.navigate(route) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onNavigate = { route ->
                    navController.navigateIfResumed(route) {
                        launchSingleTop = true
                        if (route == Routes.MULTI_VIEW) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = Routes.MOVIE_DETAIL,
            arguments = listOf(
                navArgument("movieId") { type = NavType.LongType },
                navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val returnRoute = backStackEntry.arguments?.getString("returnRoute").orEmpty().takeIf { it.isNotBlank() }
            val movieId = backStackEntry.arguments?.getLong("movieId") ?: -1L
            com.streamvault.app.ui.screens.movies.MovieDetailScreen(
                onPlay = { movie ->
                    navController.navigateToPlayer(
                        Routes.moviePlayer(movie).copy(
                            returnRoute = Routes.movieDetail(
                                movieId = movie.id.takeIf { it > 0L } ?: movieId,
                                returnRoute = returnRoute
                            )
                        )
                    )
                },
                onBack = {
                    if (!returnRoute.isNullOrBlank()) {
                        navController.navigate(returnRoute) {
                            popUpTo(backStackEntry.destination.route ?: Routes.MOVIE_DETAIL) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(
            route = Routes.SERIES_DETAIL,
            arguments = listOf(
                navArgument("seriesId") { type = NavType.LongType },
                navArgument("returnRoute") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val returnRoute = backStackEntry.arguments?.getString("returnRoute").orEmpty().takeIf { it.isNotBlank() }
            val seriesId = backStackEntry.arguments?.getLong("seriesId") ?: -1L
            com.streamvault.app.ui.screens.series.SeriesDetailScreen(
                onEpisodeClick = { episode ->
                     navController.navigateToPlayer(
                         Routes.episodePlayer(episode).copy(
                             returnRoute = Routes.seriesDetail(
                                 seriesId = episode.seriesId.takeIf { it > 0L } ?: seriesId,
                                 returnRoute = returnRoute
                             )
                         )
                     )
                },
                onBack = {
                    if (!returnRoute.isNullOrBlank()) {
                        navController.navigate(returnRoute) {
                            popUpTo(backStackEntry.destination.route ?: Routes.SERIES_DETAIL) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(Routes.MULTI_VIEW) {
            // Multiview drives up to four simultaneous IPTV streams. On a phone
            // that means four concurrent hardware decoders — more than many SoCs
            // expose — plus roughly 8 GB/h of data. It stays available on TV and
            // tablet; on a phone the route is a hard no-op back to where we came
            // from. This is the safety net behind the hidden entry points: even a
            // deep link or a restored back stack cannot land here.
            if (LocalFormFactor.current == FormFactor.Phone) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                MultiViewScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
