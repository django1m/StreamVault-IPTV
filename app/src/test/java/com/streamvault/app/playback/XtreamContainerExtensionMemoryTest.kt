package com.streamvault.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamContainerExtensionMemoryTest {

    private val memory = XtreamContainerExtensionMemory()

    @Test
    fun `remembers per series`() {
        memory.remember(providerId = 1L, seriesId = 42L, extension = "mkv")

        assertThat(memory.preferred(providerId = 1L, seriesId = 42L)).isEqualTo("mkv")
    }

    @Test
    fun `falls back to the provider-wide winner for unknown series and movies`() {
        memory.remember(providerId = 1L, seriesId = 42L, extension = "mkv")

        assertThat(memory.preferred(providerId = 1L, seriesId = 99L)).isEqualTo("mkv")
        assertThat(memory.preferred(providerId = 1L, seriesId = null)).isEqualTo("mkv")
    }

    @Test
    fun `providers do not leak into each other`() {
        memory.remember(providerId = 1L, seriesId = 42L, extension = "mkv")

        assertThat(memory.preferred(providerId = 2L, seriesId = 42L)).isNull()
    }

    @Test
    fun `movie wins are remembered provider-wide without a series entry`() {
        memory.remember(providerId = 1L, seriesId = null, extension = "avi")

        assertThat(memory.preferred(providerId = 1L, seriesId = null)).isEqualTo("avi")
        assertThat(memory.preferred(providerId = 1L, seriesId = 7L)).isEqualTo("avi")
    }

    @Test
    fun `series entry wins over the provider fallback`() {
        memory.remember(providerId = 1L, seriesId = 42L, extension = "mkv")
        // A later movie win updates the provider-wide fallback…
        memory.remember(providerId = 1L, seriesId = null, extension = "avi")

        // …but the exact series entry still wins for that series.
        assertThat(memory.preferred(providerId = 1L, seriesId = 42L)).isEqualTo("mkv")
        assertThat(memory.preferred(providerId = 1L, seriesId = 8L)).isEqualTo("avi")
    }

    @Test
    fun `cap clears the series map but keeps working`() {
        repeat(300) { i ->
            memory.remember(providerId = 1L, seriesId = i.toLong(), extension = "mkv")
        }

        // Whatever survived the clear, the API keeps answering consistently.
        assertThat(memory.preferred(providerId = 1L, seriesId = 299L)).isEqualTo("mkv")
        assertThat(memory.preferred(providerId = 1L, seriesId = null)).isEqualTo("mkv")
    }
}
