package com.streamvault.app.ui.screens.player.gesture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import kotlin.math.roundToInt

/**
 * Centre overlay shown while a touch gesture is in flight: the ±10 s seek step,
 * or the current volume / brightness level.
 *
 * Deliberately non-interactive and short-lived — the caller clears [feedback]
 * shortly after the gesture ends. It sits above the video but below the player
 * controls so it never blocks a button.
 */
@Composable
fun PlayerGestureHud(
    feedback: PlayerGestureFeedback?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            when (feedback) {
                is PlayerGestureFeedback.Seek -> {
                    val seconds = feedback.deltaSeconds
                    Text(
                        text = if (seconds >= 0) "+${seconds}s" else "${seconds}s",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                is PlayerGestureFeedback.Volume -> GestureLevel(
                    label = stringResource(R.string.player_gesture_volume),
                    fraction = feedback.fraction
                )
                is PlayerGestureFeedback.Brightness -> GestureLevel(
                    label = stringResource(R.string.player_gesture_brightness),
                    fraction = feedback.fraction
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun GestureLevel(label: String, fraction: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp
        )
        Text(
            text = "${(fraction * 100).roundToInt()}%",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .width(120.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = AppColors.Brand,
            trackColor = Color.White.copy(alpha = 0.25f),
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}
