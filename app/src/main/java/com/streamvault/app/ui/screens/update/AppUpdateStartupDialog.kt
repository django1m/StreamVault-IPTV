package com.streamvault.app.ui.screens.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.update.AppUpdateDownloadState
import com.streamvault.app.update.AppUpdateDownloadStatus

@Composable
fun AppUpdateStartupDialog(
    update: PendingAppUpdate,
    downloadState: AppUpdateDownloadState,
    onInstall: () -> Unit,
    onLater: () -> Unit
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val isDownloading = downloadState.status == AppUpdateDownloadStatus.Downloading
    val isFailed = downloadState.status == AppUpdateDownloadStatus.Failed

    Dialog(
        onDismissRequest = onLater,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Pendant le téléchargement, on bloque la fermeture par back/clic
            // hors dialog pour éviter que l'utilisateur croie que rien ne se passe.
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            Surface(
                modifier = resolvedModifier,
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Primary.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_update_available_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnSurface
                    )
                    Text(
                        text = stringResource(R.string.app_update_available_body, update.versionName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurfaceDim
                    )
                    if (update.releaseNotes.isNotBlank()) {
                        Text(
                            text = update.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceDim,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isDownloading) {
                        DownloadProgress(downloadState)
                    } else if (isFailed) {
                        Text(
                            text = stringResource(R.string.app_update_download_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // L'utilisateur veut Installer en premier (focus initial sur TV)
                        // et Plus tard à côté. Pendant le téléchargement, le bouton
                        // Installer est désactivé pour éviter les double-clics.
                        Button(
                            onClick = onInstall,
                            modifier = Modifier.mouseClickable(onClick = onInstall),
                            enabled = !isDownloading,
                            colors = ButtonDefaults.colors(
                                containerColor = Primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (isDownloading) {
                                    stringResource(R.string.app_update_downloading)
                                } else {
                                    stringResource(R.string.app_update_install)
                                }
                            )
                        }
                        Button(
                            onClick = onLater,
                            modifier = Modifier.mouseClickable(onClick = onLater),
                            enabled = !isDownloading,
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                contentColor = OnSurface
                            )
                        ) {
                            Text(stringResource(R.string.app_update_later))
                        }
                    }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(Modifier.fillMaxWidth(0.42f))
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogModifier = when {
                    maxWidth < 700.dp -> Modifier.fillMaxWidth(0.9f)
                    maxWidth < 1280.dp -> Modifier.fillMaxWidth(0.58f)
                    else -> Modifier.fillMaxWidth(0.42f)
                }
                dialogContent(dialogModifier)
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: AppUpdateDownloadState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val total = state.totalBytes
        val downloaded = state.bytesDownloaded
        val percent = if (total > 0L) {
            (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else null

        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Primary,
                trackColor = Color.White.copy(alpha = 0.12f)
            )
            Text(
                text = "${(percent * 100f).toInt()} %  ·  ${formatBytes(downloaded)} / ${formatBytes(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Primary,
                trackColor = Color.White.copy(alpha = 0.12f)
            )
            Text(
                text = stringResource(R.string.app_update_downloading),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        "%.2f GB".format(mb / 1024.0)
    } else {
        "%.1f MB".format(mb)
    }
}
