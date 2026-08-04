package com.streamvault.app.ui.components.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FormFactor
import com.streamvault.app.ui.design.LocalAppSpacing
import com.streamvault.app.ui.design.LocalFormFactor

/**
 * Touch counterpart of [AppScreenScaffold].
 *
 * [AppScreenScaffold] branches here for every non-TV form factor, which is why
 * none of the nine feature screens needed changing: they keep passing
 * `AppNavigationChrome.TopBar`, which is simply ignored off TV.
 *
 * Phone gets a bottom navigation bar, tablet a navigation rail. The split is by
 * form factor rather than current width so rotating a tablet never reshuffles the
 * chrome.
 */

/**
 * Bottom bars hold at most five items comfortably; the app has seven
 * destinations. The three least-used ones move behind "Plus", and Search is
 * promoted to a permanent top-bar action — on a phone it is the second most
 * frequent action, and burying it in an overflow sheet would be wrong.
 */
private val PhonePrimaryRoutes = listOf(Routes.HOME, Routes.LIVE_TV, Routes.MOVIES, Routes.SERIES)
private val PhoneOverflowRoutes = listOf(Routes.EPG, Routes.SETTINGS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileScreenScaffold(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier,
    showScreenHeader: Boolean,
    header: (@Composable ColumnScope.() -> Unit)?,
    contentPadding: PaddingValues,
    backdrop: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = LocalAppSpacing.current
    val formFactor = LocalFormFactor.current
    val allItems = remember { buildDestinationItems() }
    var showOverflow by remember { mutableStateOf(false) }

    // Back from any top-level destination returns Home rather than leaving the
    // app; only Home itself falls through to the system handler.
    BackHandler(enabled = currentRoute != Routes.HOME && isTopLevel(currentRoute)) {
        onNavigate(Routes.HOME)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate(Routes.SEARCH) }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(com.streamvault.app.R.string.search_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = AppColors.TextPrimary,
                    actionIconContentColor = AppColors.TextSecondary
                )
            )
        },
        bottomBar = {
            if (formFactor == FormFactor.Phone) {
                PhoneNavigationBar(
                    items = allItems,
                    currentRoute = currentRoute,
                    onNavigate = onNavigate,
                    onOverflow = { showOverflow = true }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(AppColors.Canvas, AppColors.CanvasElevated, AppColors.Surface)
                    )
                )
        ) {
            if (backdrop != null) {
                Box(modifier = Modifier.fillMaxSize().zIndex(-1f)) { backdrop() }
            }
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (formFactor == FormFactor.Tablet) {
                    TabletNavigationRail(
                        items = allItems,
                        currentRoute = currentRoute,
                        onNavigate = onNavigate
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = spacing.screenGutter)
                ) {
                    // The title already lives in the TopAppBar; repeating the
                    // screen header here would waste a third of a phone screen.
                    if (showScreenHeader && header != null) {
                        header()
                        Spacer(modifier = Modifier.height(spacing.sm))
                    } else if (header != null) {
                        header()
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(contentPadding),
                        content = content
                    )
                }
            }
        }
    }

    if (showOverflow) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showOverflow = false },
            sheetState = sheetState,
            containerColor = AppColors.SurfaceElevated
        ) {
            allItems.filter { it.route in PhoneOverflowRoutes || it.route == Routes.SEARCH }
                .forEach { item ->
                    OverflowRow(
                        label = stringResource(item.labelRes),
                        icon = { Icon(item.icon, contentDescription = null) },
                        onClick = {
                            showOverflow = false
                            onNavigate(item.route)
                        }
                    )
                }
        }
    }
}

@Composable
private fun PhoneNavigationBar(
    items: List<DestinationItem>,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onOverflow: () -> Unit
) {
    NavigationBar(containerColor = AppColors.SurfaceElevated) {
        items.filter { it.route in PhonePrimaryRoutes }.forEach { item ->
            val selected = currentRoute.startsWith(item.route)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AppColors.Canvas,
                    selectedTextColor = AppColors.Brand,
                    indicatorColor = AppColors.Brand,
                    unselectedIconColor = AppColors.TextTertiary,
                    unselectedTextColor = AppColors.TextTertiary
                )
            )
        }
        NavigationBarItem(
            selected = currentRoute.startsWith(Routes.EPG) || currentRoute.startsWith(Routes.SETTINGS),
            onClick = onOverflow,
            icon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
            label = { Text(stringResource(com.streamvault.app.R.string.nav_more), maxLines = 1) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AppColors.Canvas,
                selectedTextColor = AppColors.Brand,
                indicatorColor = AppColors.Brand,
                unselectedIconColor = AppColors.TextTertiary,
                unselectedTextColor = AppColors.TextTertiary
            )
        )
    }
}

@Composable
private fun TabletNavigationRail(
    items: List<DestinationItem>,
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    // A tablet has room for all seven destinations, so nothing is hidden behind
    // an overflow sheet here.
    NavigationRail(containerColor = AppColors.SurfaceElevated) {
        items.forEach { item ->
            val selected = currentRoute.startsWith(item.route)
            NavigationRailItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes), maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = AppColors.Canvas,
                    selectedTextColor = AppColors.Brand,
                    indicatorColor = AppColors.Brand,
                    unselectedIconColor = AppColors.TextTertiary,
                    unselectedTextColor = AppColors.TextTertiary
                )
            )
        }
    }
}

@Composable
private fun OverflowRow(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = icon,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = AppColors.TextPrimary,
            leadingIconColor = AppColors.TextSecondary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

/** Top-level destinations are the ones reachable from the bottom bar / rail. */
private fun isTopLevel(route: String): Boolean =
    listOf(
        Routes.HOME, Routes.LIVE_TV, Routes.MOVIES, Routes.SERIES,
        Routes.EPG, Routes.SEARCH, Routes.SETTINGS
    ).any { route.startsWith(it) }
