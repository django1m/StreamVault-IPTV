package com.streamvault.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.streamvault.app.ui.accessibility.rememberReducedMotionEnabled
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

@Composable
fun rememberCrossfadeImageModel(data: Any?, width: Int = 400, height: Int = 225): Any? {
    val context = LocalContext.current
    val reducedMotionEnabled = rememberReducedMotionEnabled()
    return remember(context, data, reducedMotionEnabled) {
        data?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(!reducedMotionEnabled)
                .size(Size(width, height))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
}