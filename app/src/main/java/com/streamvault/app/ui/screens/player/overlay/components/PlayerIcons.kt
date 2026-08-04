package com.streamvault.app.ui.screens.player.overlay.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Vector icon set for the player chrome.
 *
 * The project only pulls `material-icons-core` (via the Compose BOM), which stops at
 * ~45 glyphs: no `Pause`, `FastRewind`, `Subtitles`, `VolumeOff`, `Cast`, `Speed`,
 * `SkipNext`, `FiberManualRecord`… Adding `material-icons-extended` would drag in a
 * few thousand classes and a noticeably slower Kotlin compile for the ~20 shapes we
 * actually need, so the shapes are built here instead — zero dependency, zero APK
 * weight, and full control over the stroke weight so the whole bar reads as one set.
 *
 * Each vector is memoised in a backing field: `ImageVector.Builder` allocates a whole
 * node tree, and the player chrome recomposes on every position tick. On a 2 GB
 * S905X5M that allocation churn is not free.
 *
 * Icons already present in `material-icons-core` are deliberately NOT duplicated here —
 * use `Icons.Default.PlayArrow`, `Close`, `Search`, `Info`, `Lock`, `MoreVert`, `Star`,
 * `KeyboardArrowUp` / `KeyboardArrowDown` directly.
 */
object PlayerIcons {

    private fun icon(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White), pathBuilder = block)
    }.build()

    private var _pause: ImageVector? = null
    val Pause: ImageVector
        get() = _pause ?: icon("PlayerPause") {
            moveTo(6f, 5f); horizontalLineTo(10f); verticalLineTo(19f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); horizontalLineTo(18f); verticalLineTo(19f); horizontalLineTo(14f); close()
        }.also { _pause = it }

    private var _rewind: ImageVector? = null
    val Rewind: ImageVector
        get() = _rewind ?: icon("PlayerRewind") {
            moveTo(11f, 12f); lineTo(20f, 6f); verticalLineTo(18f); close()
            moveTo(2f, 12f); lineTo(11f, 6f); verticalLineTo(18f); close()
        }.also { _rewind = it }

    private var _forward: ImageVector? = null
    val Forward: ImageVector
        get() = _forward ?: icon("PlayerForward") {
            moveTo(13f, 12f); lineTo(4f, 18f); verticalLineTo(6f); close()
            moveTo(22f, 12f); lineTo(13f, 18f); verticalLineTo(6f); close()
        }.also { _forward = it }

    private var _skipPrevious: ImageVector? = null
    val SkipPrevious: ImageVector
        get() = _skipPrevious ?: icon("PlayerSkipPrevious") {
            moveTo(5f, 6f); horizontalLineTo(7.5f); verticalLineTo(18f); horizontalLineTo(5f); close()
            moveTo(19f, 6f); verticalLineTo(18f); lineTo(9.5f, 12f); close()
        }.also { _skipPrevious = it }

    private var _skipNext: ImageVector? = null
    val SkipNext: ImageVector
        get() = _skipNext ?: icon("PlayerSkipNext") {
            moveTo(16.5f, 6f); horizontalLineTo(19f); verticalLineTo(18f); horizontalLineTo(16.5f); close()
            moveTo(5f, 6f); lineTo(14.5f, 12f); lineTo(5f, 18f); close()
        }.also { _skipNext = it }

    private var _volumeOn: ImageVector? = null
    val VolumeOn: ImageVector
        get() = _volumeOn ?: icon("PlayerVolumeOn") {
            // Speaker body
            moveTo(3f, 9f); horizontalLineTo(7f); lineTo(12f, 4.5f); verticalLineTo(19.5f)
            lineTo(7f, 15f); horizontalLineTo(3f); close()
            // Two sound arcs, drawn as tapered wedges so they stay legible at 24 dp
            moveTo(14.5f, 8.6f); lineTo(16.1f, 7.6f)
            curveTo(17.5f, 9f, 17.5f, 15f, 16.1f, 16.4f)
            lineTo(14.5f, 15.4f); curveTo(15.6f, 14f, 15.6f, 10f, 14.5f, 8.6f); close()
            moveTo(18f, 5.6f); lineTo(19.5f, 4.6f)
            curveTo(22f, 7f, 22f, 17f, 19.5f, 19.4f)
            lineTo(18f, 18.4f); curveTo(20f, 16f, 20f, 8f, 18f, 5.6f); close()
        }.also { _volumeOn = it }

    private var _volumeOff: ImageVector? = null
    val VolumeOff: ImageVector
        get() = _volumeOff ?: icon("PlayerVolumeOff") {
            moveTo(3f, 9f); horizontalLineTo(7f); lineTo(12f, 4.5f); verticalLineTo(19.5f)
            lineTo(7f, 15f); horizontalLineTo(3f); close()
            // Cross
            moveTo(15.6f, 9.1f); lineTo(17f, 7.7f); lineTo(19.2f, 9.9f); lineTo(21.4f, 7.7f)
            lineTo(22.8f, 9.1f); lineTo(20.6f, 11.3f); lineTo(22.8f, 13.5f); lineTo(21.4f, 14.9f)
            lineTo(19.2f, 12.7f); lineTo(17f, 14.9f); lineTo(15.6f, 13.5f); lineTo(17.8f, 11.3f)
            close()
        }.also { _volumeOff = it }

    private var _subtitles: ImageVector? = null
    val Subtitles: ImageVector
        get() = _subtitles ?: icon("PlayerSubtitles") {
            // Rounded frame drawn as an outline (outer rect + inner rect, even-odd by winding)
            moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(3f); close()
            moveTo(5f, 7f); verticalLineTo(17f); horizontalLineTo(19f); verticalLineTo(7f); close()
            // Two caption lines
            moveTo(6.5f, 12.6f); horizontalLineTo(11f); verticalLineTo(14.2f); horizontalLineTo(6.5f); close()
            moveTo(13f, 12.6f); horizontalLineTo(17.5f); verticalLineTo(14.2f); horizontalLineTo(13f); close()
            moveTo(6.5f, 9.4f); horizontalLineTo(9f); verticalLineTo(11f); horizontalLineTo(6.5f); close()
            moveTo(11f, 9.4f); horizontalLineTo(17.5f); verticalLineTo(11f); horizontalLineTo(11f); close()
        }.also { _subtitles = it }

    private var _audioTrack: ImageVector? = null
    val AudioTrack: ImageVector
        get() = _audioTrack ?: icon("PlayerAudioTrack") {
            // Equaliser bars — reads as "audio track" faster than a musical note at this size
            moveTo(3.5f, 10f); horizontalLineTo(6f); verticalLineTo(14f); horizontalLineTo(3.5f); close()
            moveTo(8f, 6.5f); horizontalLineTo(10.5f); verticalLineTo(17.5f); horizontalLineTo(8f); close()
            moveTo(12.5f, 4f); horizontalLineTo(15f); verticalLineTo(20f); horizontalLineTo(12.5f); close()
            moveTo(17f, 8f); horizontalLineTo(19.5f); verticalLineTo(16f); horizontalLineTo(17f); close()
        }.also { _audioTrack = it }

    private var _quality: ImageVector? = null
    val Quality: ImageVector
        get() = _quality ?: icon("PlayerQuality") {
            // Frame
            moveTo(2.5f, 5f); horizontalLineTo(21.5f); verticalLineTo(19f); horizontalLineTo(2.5f); close()
            moveTo(4.5f, 7f); verticalLineTo(17f); horizontalLineTo(19.5f); verticalLineTo(7f); close()
            // "H"
            moveTo(6.5f, 9f); horizontalLineTo(8.2f); verticalLineTo(11.2f); horizontalLineTo(10.3f)
            verticalLineTo(9f); horizontalLineTo(12f); verticalLineTo(15f); horizontalLineTo(10.3f)
            verticalLineTo(12.8f); horizontalLineTo(8.2f); verticalLineTo(15f); horizontalLineTo(6.5f); close()
            // "D"
            moveTo(13.4f, 9f); horizontalLineTo(16f); curveTo(18f, 9f, 18f, 15f, 16f, 15f)
            horizontalLineTo(13.4f); close()
            moveTo(15.1f, 10.6f); verticalLineTo(13.4f); horizontalLineTo(15.9f)
            curveTo(16.5f, 13.4f, 16.5f, 10.6f, 15.9f, 10.6f); close()
        }.also { _quality = it }

    private var _speed: ImageVector? = null
    val Speed: ImageVector
        get() = _speed ?: icon("PlayerSpeed") {
            // Gauge arc
            moveTo(12f, 4f)
            curveTo(17.5f, 4f, 22f, 8.5f, 22f, 14f)
            horizontalLineTo(19.6f)
            curveTo(19.6f, 9.8f, 16.2f, 6.4f, 12f, 6.4f)
            curveTo(7.8f, 6.4f, 4.4f, 9.8f, 4.4f, 14f)
            horizontalLineTo(2f)
            curveTo(2f, 8.5f, 6.5f, 4f, 12f, 4f)
            close()
            // Needle
            moveTo(11f, 14.4f); lineTo(16.4f, 8.8f); lineTo(13.2f, 15.4f)
            curveTo(12.6f, 16.6f, 10.6f, 15.7f, 11f, 14.4f); close()
        }.also { _speed = it }

    private var _aspectRatio: ImageVector? = null
    val AspectRatio: ImageVector
        get() = _aspectRatio ?: icon("PlayerAspectRatio") {
            moveTo(2.5f, 5.5f); horizontalLineTo(21.5f); verticalLineTo(18.5f); horizontalLineTo(2.5f); close()
            moveTo(4.5f, 7.5f); verticalLineTo(16.5f); horizontalLineTo(19.5f); verticalLineTo(7.5f); close()
            // Corner brackets
            moveTo(6.5f, 9.5f); horizontalLineTo(10.5f); verticalLineTo(11f); horizontalLineTo(8f)
            verticalLineTo(13f); horizontalLineTo(6.5f); close()
            moveTo(17.5f, 14.5f); horizontalLineTo(13.5f); verticalLineTo(13f); horizontalLineTo(16f)
            verticalLineTo(11f); horizontalLineTo(17.5f); close()
        }.also { _aspectRatio = it }

    private var _record: ImageVector? = null
    val Record: ImageVector
        get() = _record ?: icon("PlayerRecord") {
            moveTo(12f, 5f)
            curveTo(15.9f, 5f, 19f, 8.1f, 19f, 12f)
            curveTo(19f, 15.9f, 15.9f, 19f, 12f, 19f)
            curveTo(8.1f, 19f, 5f, 15.9f, 5f, 12f)
            curveTo(5f, 8.1f, 8.1f, 5f, 12f, 5f)
            close()
        }.also { _record = it }

    private var _recordStop: ImageVector? = null
    val RecordStop: ImageVector
        get() = _recordStop ?: icon("PlayerRecordStop") {
            moveTo(6.5f, 6.5f); horizontalLineTo(17.5f); verticalLineTo(17.5f); horizontalLineTo(6.5f); close()
        }.also { _recordStop = it }

    private var _schedule: ImageVector? = null
    val Schedule: ImageVector
        get() = _schedule ?: icon("PlayerSchedule") {
            // Clock ring
            moveTo(12f, 3f)
            curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
            curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
            curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
            curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
            close()
            moveTo(12f, 5.2f)
            curveTo(8.24f, 5.2f, 5.2f, 8.24f, 5.2f, 12f)
            curveTo(5.2f, 15.76f, 8.24f, 18.8f, 12f, 18.8f)
            curveTo(15.76f, 18.8f, 18.8f, 15.76f, 18.8f, 12f)
            curveTo(18.8f, 8.24f, 15.76f, 5.2f, 12f, 5.2f)
            close()
            // Hands
            moveTo(11.1f, 6.8f); horizontalLineTo(12.9f); verticalLineTo(12.2f)
            horizontalLineTo(16.6f); verticalLineTo(14f); horizontalLineTo(11.1f); close()
        }.also { _schedule = it }

    private var _restart: ImageVector? = null
    val Restart: ImageVector
        get() = _restart ?: icon("PlayerRestart") {
            // Counter-clockwise arrow
            moveTo(12f, 4f); verticalLineTo(6.3f)
            curveTo(15.15f, 6.3f, 17.7f, 8.85f, 17.7f, 12f)
            curveTo(17.7f, 15.15f, 15.15f, 17.7f, 12f, 17.7f)
            curveTo(8.85f, 17.7f, 6.3f, 15.15f, 6.3f, 12f)
            horizontalLineTo(8.6f)
            curveTo(8.6f, 13.88f, 10.12f, 15.4f, 12f, 15.4f)
            curveTo(13.88f, 15.4f, 15.4f, 13.88f, 15.4f, 12f)
            curveTo(15.4f, 10.12f, 13.88f, 8.6f, 12f, 8.6f)
            verticalLineTo(11f); lineTo(8.2f, 7.5f); lineTo(12f, 4f)
            close()
        }.also { _restart = it }

    private var _archive: ImageVector? = null
    val Archive: ImageVector
        get() = _archive ?: icon("PlayerArchive") {
            // Clock with a rewind chevron — "catch-up / replay"
            moveTo(11.5f, 3.5f)
            curveTo(16.19f, 3.5f, 20f, 7.31f, 20f, 12f)
            curveTo(20f, 16.69f, 16.19f, 20.5f, 11.5f, 20.5f)
            curveTo(8.4f, 20.5f, 5.7f, 18.83f, 4.24f, 16.35f)
            lineTo(6.2f, 15.2f)
            curveTo(7.28f, 17.03f, 9.25f, 18.25f, 11.5f, 18.25f)
            curveTo(14.95f, 18.25f, 17.75f, 15.45f, 17.75f, 12f)
            curveTo(17.75f, 8.55f, 14.95f, 5.75f, 11.5f, 5.75f)
            curveTo(9.02f, 5.75f, 6.88f, 7.19f, 5.87f, 9.29f)
            horizontalLineTo(8.6f); verticalLineTo(11.4f); horizontalLineTo(2.4f)
            verticalLineTo(5.2f); horizontalLineTo(4.5f); verticalLineTo(7.4f)
            curveTo(6.05f, 5.06f, 8.6f, 3.5f, 11.5f, 3.5f)
            close()
            moveTo(10.6f, 7.8f); horizontalLineTo(12.4f); verticalLineTo(11.8f)
            horizontalLineTo(15.7f); verticalLineTo(13.6f); horizontalLineTo(10.6f); close()
        }.also { _archive = it }

    private var _cast: ImageVector? = null
    val Cast: ImageVector
        get() = _cast ?: icon("PlayerCast") {
            // Screen outline
            moveTo(2.5f, 4.5f); horizontalLineTo(21.5f); verticalLineTo(18f); horizontalLineTo(12.6f)
            verticalLineTo(16f); horizontalLineTo(19.5f); verticalLineTo(6.5f); horizontalLineTo(4.5f)
            verticalLineTo(8.2f); horizontalLineTo(2.5f); close()
            // Signal waves + dot
            moveTo(2.5f, 10f)
            curveTo(6.9f, 10f, 10.5f, 13.6f, 10.5f, 18f)
            horizontalLineTo(8.5f)
            curveTo(8.5f, 14.7f, 5.8f, 12f, 2.5f, 12f)
            close()
            moveTo(2.5f, 13.5f)
            curveTo(4.99f, 13.5f, 7f, 15.51f, 7f, 18f)
            horizontalLineTo(5f)
            curveTo(5f, 16.62f, 3.88f, 15.5f, 2.5f, 15.5f)
            close()
            moveTo(2.5f, 16.6f); curveTo(3.27f, 16.6f, 3.9f, 17.23f, 3.9f, 18f)
            horizontalLineTo(2.5f); close()
        }.also { _cast = it }

    private var _pip: ImageVector? = null
    val Pip: ImageVector
        get() = _pip ?: icon("PlayerPip") {
            moveTo(2.5f, 5f); horizontalLineTo(21.5f); verticalLineTo(19f); horizontalLineTo(2.5f); close()
            moveTo(4.5f, 7f); verticalLineTo(17f); horizontalLineTo(19.5f); verticalLineTo(7f); close()
            moveTo(12f, 11.5f); horizontalLineTo(18.5f); verticalLineTo(16f); horizontalLineTo(12f); close()
        }.also { _pip = it }

    private var _splitScreen: ImageVector? = null
    val SplitScreen: ImageVector
        get() = _splitScreen ?: icon("PlayerSplitScreen") {
            moveTo(3f, 5f); horizontalLineTo(11.2f); verticalLineTo(11.2f); horizontalLineTo(3f); close()
            moveTo(12.8f, 5f); horizontalLineTo(21f); verticalLineTo(11.2f); horizontalLineTo(12.8f); close()
            moveTo(3f, 12.8f); horizontalLineTo(11.2f); verticalLineTo(19f); horizontalLineTo(3f); close()
            moveTo(12.8f, 12.8f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(12.8f); close()
        }.also { _splitScreen = it }

    private var _guide: ImageVector? = null
    val Guide: ImageVector
        get() = _guide ?: icon("PlayerGuide") {
            // Grid frame
            moveTo(2.5f, 5f); horizontalLineTo(21.5f); verticalLineTo(19f); horizontalLineTo(2.5f); close()
            moveTo(4.5f, 7f); verticalLineTo(17f); horizontalLineTo(19.5f); verticalLineTo(7f); close()
            // Column separator + row separator
            moveTo(9.2f, 7f); horizontalLineTo(10.6f); verticalLineTo(17f); horizontalLineTo(9.2f); close()
            moveTo(10.6f, 11.3f); horizontalLineTo(19.5f); verticalLineTo(12.7f); horizontalLineTo(10.6f); close()
        }.also { _guide = it }

    private var _channelList: ImageVector? = null
    val ChannelList: ImageVector
        get() = _channelList ?: icon("PlayerChannelList") {
            moveTo(3f, 5.5f); horizontalLineTo(6f); verticalLineTo(8.2f); horizontalLineTo(3f); close()
            moveTo(8f, 5.5f); horizontalLineTo(21f); verticalLineTo(8.2f); horizontalLineTo(8f); close()
            moveTo(3f, 10.65f); horizontalLineTo(6f); verticalLineTo(13.35f); horizontalLineTo(3f); close()
            moveTo(8f, 10.65f); horizontalLineTo(21f); verticalLineTo(13.35f); horizontalLineTo(8f); close()
            moveTo(3f, 15.8f); horizontalLineTo(6f); verticalLineTo(18.5f); horizontalLineTo(3f); close()
            moveTo(8f, 15.8f); horizontalLineTo(21f); verticalLineTo(18.5f); horizontalLineTo(8f); close()
        }.also { _channelList = it }

    private var _diagnostics: ImageVector? = null
    val Diagnostics: ImageVector
        get() = _diagnostics ?: icon("PlayerDiagnostics") {
            // Heartbeat line
            moveTo(2.5f, 11f); horizontalLineTo(6.5f); lineTo(8.5f, 6f); lineTo(12f, 18f)
            lineTo(15f, 11f); horizontalLineTo(21.5f); verticalLineTo(13f); horizontalLineTo(16.3f)
            lineTo(12.3f, 21.5f); lineTo(8.7f, 9.6f); lineTo(7.6f, 13f); horizontalLineTo(2.5f); close()
        }.also { _diagnostics = it }

    private var _options: ImageVector? = null
    val Options: ImageVector
        get() = _options ?: icon("PlayerOptions") {
            moveTo(5.5f, 10f)
            curveTo(6.6f, 10f, 7.5f, 10.9f, 7.5f, 12f)
            curveTo(7.5f, 13.1f, 6.6f, 14f, 5.5f, 14f)
            curveTo(4.4f, 14f, 3.5f, 13.1f, 3.5f, 12f)
            curveTo(3.5f, 10.9f, 4.4f, 10f, 5.5f, 10f)
            close()
            moveTo(12f, 10f)
            curveTo(13.1f, 10f, 14f, 10.9f, 14f, 12f)
            curveTo(14f, 13.1f, 13.1f, 14f, 12f, 14f)
            curveTo(10.9f, 14f, 10f, 13.1f, 10f, 12f)
            curveTo(10f, 10.9f, 10.9f, 10f, 12f, 10f)
            close()
            moveTo(18.5f, 10f)
            curveTo(19.6f, 10f, 20.5f, 10.9f, 20.5f, 12f)
            curveTo(20.5f, 13.1f, 19.6f, 14f, 18.5f, 14f)
            curveTo(17.4f, 14f, 16.5f, 13.1f, 16.5f, 12f)
            curveTo(16.5f, 10.9f, 17.4f, 10f, 18.5f, 10f)
            close()
        }.also { _options = it }
}
