package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import com.streamvault.domain.model.ExternalRatingValue
import com.streamvault.domain.model.ExternalRatings

@Composable
fun ExternalRatingsStrip(
    ratings: ExternalRatings,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isLoading && !ratings.hasAnyAvailableValue()) {
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.vod_external_ratings_heading),
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextSecondary
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Brand colours off. Four saturated chips in a row on a detail page is the
            // loudest thing on screen for information that is secondary; the wordmark
            // carries the identity on its own. Flip BRAND_TOKENS to get them back.
            RatingBadgeCard(
                token = "IMDb",
                title = stringResource(R.string.vod_rating_imdb),
                value = ratings.imdb,
                isLoading = isLoading,
                tokenBackground = if (BRAND_TOKENS) Color(0xFFF5C449) else Color.Transparent,
                tokenContent = if (BRAND_TOKENS) Color(0xFF111111) else AppColors.TextSecondary
            )
            RatingBadgeCard(
                token = "RT",
                title = stringResource(R.string.vod_rating_rotten_tomatoes),
                value = ratings.rottenTomatoes,
                isLoading = isLoading,
                tokenBackground = if (BRAND_TOKENS) Color(0xFFE24E41) else Color.Transparent,
                tokenContent = if (BRAND_TOKENS) Color.White else AppColors.TextSecondary
            )
            RatingBadgeCard(
                token = "MC",
                title = stringResource(R.string.vod_rating_metacritic),
                value = ratings.metacritic,
                isLoading = isLoading,
                tokenBackground = if (BRAND_TOKENS) Color(0xFFD6E04B) else Color.Transparent,
                tokenContent = if (BRAND_TOKENS) Color(0xFF111111) else AppColors.TextSecondary
            )
            RatingBadgeCard(
                token = "TMDb",
                title = stringResource(R.string.vod_rating_tmdb),
                value = ratings.tmdb,
                isLoading = isLoading,
                tokenBackground = if (BRAND_TOKENS) Color(0xFF17C7A2) else Color.Transparent,
                tokenContent = if (BRAND_TOKENS) Color(0xFF072720) else AppColors.TextSecondary
            )
        }
    }
}

private fun ExternalRatings.hasAnyAvailableValue(): Boolean {
    return imdb.available || rottenTomatoes.available || metacritic.available || tmdb.available
}

@Composable
private fun RatingBadgeCard(
    token: String,
    title: String,
    value: ExternalRatingValue,
    isLoading: Boolean,
    tokenBackground: Color,
    tokenContent: Color
) {
    val displayValue = if (isLoading) "..." else value.displayValue
    val containerColor = if (value.available || isLoading) {
        AppColors.SurfaceElevated
    } else {
        AppColors.Surface
    }

    Column(
        modifier = Modifier
            .widthIn(min = 92.dp)
            .background(containerColor, RoundedCornerShape(18.dp))
            .border(1.dp, AppColors.Outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(tokenBackground, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = token,
                    color = tokenContent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = title,
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }

        Text(
            text = displayValue,
            color = if (value.available || isLoading) AppColors.TextPrimary else AppColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Set to true to restore the platforms' own brand colours on the rating chips.
 * Kept as a one-line switch rather than deleted: it is the sort of decision a product
 * owner reverses.
 */
private const val BRAND_TOKENS = false
