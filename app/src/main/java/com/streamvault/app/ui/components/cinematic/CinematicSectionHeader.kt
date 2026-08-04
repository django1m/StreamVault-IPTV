package com.streamvault.app.ui.components.cinematic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.LocalAppHeroStyles
import com.streamvault.app.ui.design.LocalAppSpacing

/**
 * Row section title with an optional eyebrow and a trailing action slot (usually
 * a "See all" link). Use above every shelf in Dashboard / VOD browse screens.
 */
@Composable
fun CinematicSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val spacing = LocalAppSpacing.current
    val hero = LocalAppHeroStyles.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.safeHoriz, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = hero.sectionEyebrow,
                    color = AppColors.BrandStrong
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = title,
                style = hero.sectionTitle,
                color = AppColors.TextPrimary
            )
        }
        if (trailing != null) trailing()
    }
}
