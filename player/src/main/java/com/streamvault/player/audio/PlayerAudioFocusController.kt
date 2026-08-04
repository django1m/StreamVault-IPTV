package com.streamvault.player.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerAudioFocusController(
    context: Context,
    private val applyVolume: (Float) -> Unit,
    private val setPlayWhenReady: (Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var shouldResumeOnAudioFocusGain = false
    private var isDucked = false
    private var currentVolume = 1f
    private var volumeBeforeMute = 1f

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    var bypassAudioFocus: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                isDucked = false
                dispatchVolume()
                if (shouldResumeOnAudioFocusGain) {
                    shouldResumeOnAudioFocusGain = false
                    setPlayWhenReady(true)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                dispatchVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                isDucked = false
                shouldResumeOnAudioFocusGain = true
                setPlayWhenReady(false)
                dispatchVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                isDucked = false
                shouldResumeOnAudioFocusGain = false
                setPlayWhenReady(false)
                dispatchVolume()
            }
        }
    }

    fun requestAudioFocusIfNeeded(): Boolean {
        if (bypassAudioFocus) {
            isDucked = false
            dispatchVolume()
            return true
        }
        // Defensive: do NOT short-circuit on hasAudioFocus == true. Some
        // firmwares (notably Amlogic Android TV builds) can silently revoke
        // audio focus without firing onAudioFocusChange, leaving our cached
        // hasAudioFocus desynced from reality. requestAudioFocus is idempotent —
        // the OS returns GRANTED immediately if we already hold focus — so
        // re-requesting on every play() call is cheap and re-syncs the state.
        val request = audioFocusRequest ?: buildAudioFocusRequest().also { audioFocusRequest = it }
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        if (!granted) {
            Log.w(TAG, "audio-focus denied")
            return false
        }
        isDucked = false
        dispatchVolume()
        return true
    }

    /**
     * Called from the engine when the user presses pause. Per Android audio
     * focus best practices we KEEP the focus on pause and only release it on
     * full teardown ([release] / [onStop]) — this avoids the "pause then can't
     * resume" failure mode on Amlogic boxes where re-requesting focus
     * immediately after abandoning intermittently returns AUDIOFOCUS_REQUEST_FAILED,
     * leaving playWhenReady stuck at false and the user thinking play/pause
     * has died.
     */
    fun onPause() {
        shouldResumeOnAudioFocusGain = false
    }

    fun onStop() {
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
    }

    @Deprecated(
        "Split into onPause() (keeps focus) and onStop() (abandons focus). " +
            "Audible playback control should keep focus across a pause so " +
            "resume cannot fail on a denied re-request.",
        ReplaceWith("onPause() or onStop() depending on intent")
    )
    fun onPauseOrStop() {
        onStop()
    }

    fun onPlaybackStarted() {
        shouldResumeOnAudioFocusGain = false
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (currentVolume > 0f) {
            volumeBeforeMute = currentVolume
            _isMuted.value = false
        }
        dispatchVolume()
    }

    fun setMuted(muted: Boolean) {
        if (muted == _isMuted.value) {
            dispatchVolume()
            return
        }
        if (muted) {
            if (currentVolume > 0f) {
                volumeBeforeMute = currentVolume.coerceAtLeast(0.1f)
            }
            _isMuted.value = true
        } else {
            _isMuted.value = false
            if (currentVolume <= 0f) {
                currentVolume = volumeBeforeMute.coerceIn(0.1f, 1f)
            }
        }
        dispatchVolume()
    }

    fun toggleMute() {
        setMuted(!_isMuted.value)
    }

    fun reapplyVolume() {
        dispatchVolume()
    }

    fun release() {
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
    }

    private fun dispatchVolume() {
        applyVolume(
            when {
                _isMuted.value -> 0f
                isDucked -> (currentVolume * 0.2f).coerceAtLeast(0.05f)
                else -> currentVolume
            }
        )
    }

    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        hasAudioFocus = false
        isDucked = false
    }

    private fun buildAudioFocusRequest(): AudioFocusRequest {
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }

    companion object {
        private const val TAG = "PlayerAudioFocus"
    }
}
