package com.streamvault.app.ui.components.osd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.OsdFocus
import com.streamvault.app.ui.design.osdSecondaryColor
import com.streamvault.app.ui.interaction.TvClickableSurface

/**
 * The list row of the whole app: `number · leading · title / subtitle · trailing`.
 *
 * Nothing at rest, a solid light slab when focused, and the ACTIVE item marked by tinting
 * its title rather than by giving it a background — a fill would be indistinguishable from
 * focus, which is the single most common way a 10-foot list stops being readable.
 *
 * [number] is a real column, not a prefix glued onto [title]. Channel lists used to
 * concatenate the two into one string, which rendered the number in the same bold white as
 * the name and destroyed the scan pattern; the eye needs the number quiet and left-aligned
 * to use it at all.
 *
 * [leading] and [trailing] receive the current content colour so a logo tint or a glyph
 * follows the slab without each call site re-deriving it.
 */
@Composable
fun OsdRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: String? = null,
    subtitle: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    minHeight: Dp = 56.dp,
    numberWidth: Dp = 34.dp,
    shape: Shape = LocalAppShapes.current.small,
    interactionSource: MutableInteractionSource? = null,
    titleMaxLines: Int = 1,
    leading: @Composable (RowScope.(androidx.compose.ui.graphics.Color) -> Unit)? = null,
    trailing: @Composable (RowScope.(androidx.compose.ui.graphics.Color) -> Unit)? = null
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()

    TvClickableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = OsdFocus.slabColors(active = active),
        border = OsdFocus.noBorder(),
        scale = OsdFocus.noScale(),
        interactionSource = source
    ) {
        val content = LocalContentColor.current
        val secondary = osdSecondaryColor(isFocused)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (number != null) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelLarge,
                    color = secondary,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(numberWidth)
                )
            }
            leading?.invoke(this, content)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFocused) AppColors.FocusOnFillDim else AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.invoke(this, content)
        }
    }
}

/**
 * Counter / badge slot for [OsdRow.trailing]. Text only — a filled pill here would be one
 * more container in a language that has none.
 */
@Composable
fun OsdRowCount(value: String, focused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.defaultMinSize(minWidth = 28.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = osdSecondaryColor(focused),
            maxLines = 1
        )
    }
}
