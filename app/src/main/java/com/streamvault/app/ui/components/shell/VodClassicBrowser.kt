package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.streamvault.app.ui.design.FormFactor
import com.streamvault.app.ui.design.LocalFormFactor
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FocusSpec
import androidx.compose.ui.res.stringResource
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton
import androidx.compose.ui.graphics.Color
import com.streamvault.app.ui.design.OsdFocus

data class VodClassicCategoryOption(
    val key: String,
    val label: String,
    val count: Int,
    val isSelected: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val isLocked: Boolean = false
)

@Composable
fun VodClassicSplitLayout(
    railTitle: String,
    railSearchValue: String,
    onRailSearchValueChange: (String) -> Unit,
    railSearchPlaceholder: String,
    categories: List<VodClassicCategoryOption>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // PHONE: the side-by-side layout is unusable here. The rail is a fixed
    // 300dp; on a 411dp phone (379dp after the shell gutters) that leaves
    // 379 - 300 - 20 = 59dp for the content pane, and 23dp once its own
    // horizontal padding is removed — the grid renders as a sliver, which reads
    // as "I pick a category and nothing shows up".
    //
    // A two-level pattern like Settings does not apply: one category is always
    // selected (the "all" entry is selected by default), so there is no
    // "nothing selected" state to show the list in. Instead the rail collapses
    // and is summoned from a header button, closing again once a category is
    // picked. CLASSIC is the DEFAULT view mode, so this path is what every
    // phone user sees on a fresh install.
    if (LocalFormFactor.current == FormFactor.Phone) {
        var railExpanded by rememberSaveable { mutableStateOf(false) }
        Column(modifier = modifier.fillMaxSize()) {
            if (railExpanded) {
                CategoryRailPanel(
                    title = railTitle,
                    searchValue = railSearchValue,
                    onSearchValueChange = onRailSearchValueChange,
                    searchPlaceholder = railSearchPlaceholder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(categories, key = { it.key }) { category ->
                        // Picking a category closes the rail, so the grid the
                        // user just asked for is what they land on.
                        VodClassicCategoryRow(
                            category = category.copy(
                                onClick = {
                                    category.onClick()
                                    railExpanded = false
                                }
                            )
                        )
                    }
                }
            } else {
                TvButton(
                    onClick = { railExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "☰  " + (categories.firstOrNull { it.isSelected }?.label ?: railTitle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    content()
                }
            }
        }
        return
    }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CategoryRailPanel(
            title = railTitle,
            searchValue = railSearchValue,
            onSearchValueChange = onRailSearchValueChange,
            searchPlaceholder = railSearchPlaceholder,
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(categories, key = { it.key }) { category ->
                VodClassicCategoryRow(category = category)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AppColors.CanvasElevated,
                            AppColors.Canvas
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun VodClassicCategoryRow(
    category: VodClassicCategoryOption,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(
                    color = if (category.isSelected) AppColors.Brand else AppColors.Brand.copy(alpha = 0f),
                    shape = RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        TvClickableSurface(
            onClick = category.onClick,
            onLongClick = category.onLongClick,
            modifier = Modifier.weight(1f),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = AppColors.Focus,
                contentColor = if (category.isSelected) AppColors.ActiveText else AppColors.TextPrimary,
                focusedContentColor = AppColors.FocusOnFill
            ),
            border = OsdFocus.noBorder(),
            scale = ClickableSurfaceDefaults.scale(focusedScale = FocusSpec.FocusedScale)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (category.isSelected) AppColors.BrandStrong else AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (category.isLocked) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_locked_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.BrandStrong
                    )
                }
                if (category.count > 0) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (category.isSelected) AppColors.Brand.copy(alpha = 0.30f) else AppColors.SurfaceAccent.copy(alpha = 0.7f),
                                shape = CircleShape
                            )
                            .padding(horizontal = 9.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = category.count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (category.isSelected) AppColors.BrandStrong else AppColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VodClassicContentHeader(
    title: String,
    subtitle: String,
    actions: List<VodActionChip>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .background(
                    color = AppColors.Brand,
                    shape = RoundedCornerShape(4.dp)
                )
        )
        Spacer(modifier = Modifier.width(2.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                VodClassicHeaderActionButton(action = action)
            }
        }
    }
}

@Composable
private fun VodClassicHeaderActionButton(
    action: VodActionChip,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = action.onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.Focus,
            focusedContentColor = AppColors.FocusOnFill,
            contentColor = AppColors.TextPrimary
        ),
        border = OsdFocus.noBorder(),
        scale = OsdFocus.noScale()
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
