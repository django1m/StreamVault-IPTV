package com.streamvault.data.sync

import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamRequestException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-provider request pacing and ban avoidance, shared by EVERY control-plane
 * caller that touches `player_api.php` — the catalogue sync AND the on-demand
 * EPG hydration the guide/home screens perform while the user browses.
 *
 * Sharing is the whole point: the provider counts requests per exit IP, not per
 * feature. A 24-channel `get_short_epg` burst fired while a catalogue sync is
 * pacing itself at ~1 req/s is, from the panel's side, exactly the abuse pattern
 * that got the VPN exit IP banned — and a banned exit IP takes every other
 * client behind that IP down with it. One instance = one pacing lane and one
 * health state per provider, so a 403 seen by either side cools down both.
 */
@Singleton
class XtreamAdaptiveSyncPolicy @Inject constructor() {
    private companion object {
        const val MAX_SEGMENTED_CONCURRENCY = 4
    }

    enum class Stage(val timeoutMs: Long?) {
        LIGHTWEIGHT(15_000L),
        CATEGORY(35_000L),
        PAGED(40_000L),
        // User-facing guide hydration. A short timeout keeps a stalled panel from
        // freezing the EPG grid, and it is paced like the segmented stages so a
        // scroll burst can never out-run the catalogue sync's cadence.
        ON_DEMAND_EPG(12_000L),
        HEAVY(null)
    }

    private data class ProviderHealth(
        val stressLevel: Int = 0,
        val backoffUntilMs: Long = 0L,
        val successStreak: Int = 0,
        val timeoutStreak: Int = 0,
        val resetStreak: Int = 0,
        val connectionFailureStreak: Int = 0
    )

    private class ProviderPacingState {
        val mutex = Mutex()
        var nextSegmentedRequestStartMs: Long = 0L
    }

    private val providerHealth = ConcurrentHashMap<Long, ProviderHealth>()
    private val providerPacing = ConcurrentHashMap<Long, ProviderPacingState>()

    suspend fun awaitTurn(providerId: Long, stage: Stage) {
        val waitMs = (providerHealth[providerId]?.backoffUntilMs ?: 0L) - System.currentTimeMillis()
        if (waitMs > 0L) {
            delay(waitMs)
        }
        val requestSpacingMs = segmentedRequestSpacingFor(providerId, stage)
        if (requestSpacingMs <= 0L) {
            return
        }
        val pacingState = providerPacing.computeIfAbsent(providerId) { ProviderPacingState() }
        pacingState.mutex.withLock {
            val now = System.currentTimeMillis()
            val pacingWaitMs = (pacingState.nextSegmentedRequestStartMs - now).coerceAtLeast(0L)
            if (pacingWaitMs > 0L) {
                delay(pacingWaitMs)
            }
            pacingState.nextSegmentedRequestStartMs = System.currentTimeMillis() + requestSpacingMs
        }
    }

    fun timeoutFor(providerId: Long, stage: Stage): Long? = stage.timeoutMs

    fun concurrencyFor(
        providerId: Long,
        workloadSize: Int,
        preferSequential: Boolean,
        stage: Stage
    ): Int {
        if (preferSequential || workloadSize <= 1) {
            return 1
        }
        val health = providerHealth[providerId] ?: ProviderHealth()
        val base = when (stage) {
            Stage.LIGHTWEIGHT -> 1
            Stage.CATEGORY -> when {
                workloadSize <= 3 -> 1
                workloadSize <= 8 -> 2
                workloadSize <= 18 -> 2
                workloadSize <= 36 -> 3
                else -> 4
            }
            Stage.PAGED -> when {
                workloadSize <= 3 -> 1
                workloadSize <= 10 -> 2
                workloadSize <= 25 -> 2
                workloadSize <= 60 -> 3
                else -> 4
            }
            // Never more than 2 in flight: the guide only needs the visible rows
            // filled, and the price of being wrong here is the exit IP, not a
            // slower scroll.
            Stage.ON_DEMAND_EPG -> if (workloadSize <= 2) 1 else 2
            Stage.HEAVY -> 1
        }
        val adjusted = when {
            health.stressLevel >= 5 -> 1
            health.stressLevel >= 4 -> (base / 3).coerceAtLeast(1)
            health.stressLevel >= 3 -> (base / 2).coerceAtLeast(1)
            health.stressLevel >= 2 -> (base - 2).coerceAtLeast(1)
            health.stressLevel >= 1 -> (base - 1).coerceAtLeast(1)
            else -> base
        }
        val boosted = if (health.stressLevel == 0 && health.successStreak >= 4 && stage != Stage.HEAVY) {
            adjusted + 1
        } else {
            adjusted
        }
        return boosted.coerceIn(1, MAX_SEGMENTED_CONCURRENCY).coerceAtMost(workloadSize)
    }

    fun recordSuccess(providerId: Long) {
        providerHealth.compute(providerId) { _, current ->
            val state = current ?: ProviderHealth()
            val nextSuccessStreak = (state.successStreak + 1).coerceAtMost(6)
            val recoveredStress = if (nextSuccessStreak >= 2) {
                (state.stressLevel - 1).coerceAtLeast(0)
            } else {
                state.stressLevel
            }
            state.copy(
                stressLevel = recoveredStress,
                backoffUntilMs = if (recoveredStress == 0) 0L else state.backoffUntilMs,
                successStreak = nextSuccessStreak,
                timeoutStreak = 0,
                resetStreak = 0,
                connectionFailureStreak = 0
            )
        }
    }

    fun recordFailure(providerId: Long, error: Throwable) {
        providerHealth.compute(providerId) { _, current ->
            val state = current ?: ProviderHealth()
            val signal = classify(error)
            val nextStress = when (signal) {
                StressSignal.THROTTLED -> (state.stressLevel + 2).coerceAtMost(5)
                StressSignal.TIMEOUT,
                StressSignal.CONNECTION_RESET,
                StressSignal.CONNECTION_FAILURE -> (state.stressLevel + 1).coerceAtMost(5)
                StressSignal.NONE -> state.stressLevel
            }
            val nextTimeoutStreak = if (signal == StressSignal.TIMEOUT) state.timeoutStreak + 1 else 0
            val nextResetStreak = if (signal == StressSignal.CONNECTION_RESET) state.resetStreak + 1 else 0
            val nextConnectionFailureStreak = if (signal == StressSignal.CONNECTION_FAILURE) state.connectionFailureStreak + 1 else 0
            val extraStress = when {
                nextTimeoutStreak >= 2 || nextResetStreak >= 2 || nextConnectionFailureStreak >= 2 -> 1
                else -> 0
            }
            val finalStress = (nextStress + extraStress).coerceAtMost(5)
            val computedBackoffMs = when {
                // A 403/429 from this provider is an exit-IP abuse ban that lasts
                // MINUTES, not milliseconds. A ~6 s backoff just lets the next sync
                // pass re-hammer the still-banned IP and re-burn live. Cool the whole
                // provider for minutes so the ban can clear before we touch it again.
                // This gates ONLY control-plane requests (via awaitTurn): catalogue
                // sync and on-demand guide hydration. Live playback uses a separate
                // HTTP client and path, so a long cooldown costs viewing nothing —
                // at worst the guide falls back to whatever is already in Room.
                signal == StressSignal.THROTTLED -> 60_000L + (60_000L * finalStress)
                signal == StressSignal.TIMEOUT -> 350L * finalStress
                signal == StressSignal.CONNECTION_RESET || signal == StressSignal.CONNECTION_FAILURE -> 250L * finalStress
                else -> 0L
            }
            // RFC 7231 §7.1.3 — when the upstream server explicitly tells us to wait
            // (Retry-After on 429/503), honour it strictly. Capped at 5 minutes so a
            // hostile or misconfigured upstream cannot pin a provider indefinitely.
            val retryAfterMs = retryAfterMsOf(error)?.coerceAtMost(5L * 60L * 1000L)
            val effectiveBackoffMs = maxOf(computedBackoffMs, retryAfterMs ?: 0L)
            state.copy(
                stressLevel = finalStress,
                backoffUntilMs = System.currentTimeMillis() + effectiveBackoffMs,
                successStreak = 0,
                timeoutStreak = nextTimeoutStreak,
                resetStreak = nextResetStreak,
                connectionFailureStreak = nextConnectionFailureStreak
            )
        }
    }

    private fun retryAfterMsOf(error: Throwable): Long? = when (error) {
        is XtreamNetworkException -> error.retryAfterMs
        is XtreamRequestException -> error.retryAfterMs
        else -> null
    }

    fun retryDelayFor(providerId: Long, attempt: Int): Long {
        val health = providerHealth[providerId] ?: ProviderHealth()
        val base = when (attempt) {
            1 -> 150L
            2 -> 500L
            else -> 1_200L
        }
        val stressExtra = when {
            health.stressLevel >= 4 -> 1_600L
            health.stressLevel >= 3 -> 900L
            health.stressLevel >= 2 -> 450L
            health.stressLevel >= 1 -> 150L
            else -> 0L
        }
        return base + stressExtra
    }

    fun forgetProvider(providerId: Long) {
        providerHealth.remove(providerId)
        providerPacing.remove(providerId)
    }

    fun isProviderStress(error: Throwable): Boolean = classify(error) != StressSignal.NONE

    /**
     * A hard throttle/ban signal: HTTP 403/429 or an auth refusal this provider
     * returns when it rate-limit-bans the exit IP. Distinct from generic stress
     * (timeouts/resets): a throttle means "you are hitting me too hard — every
     * further request deepens the ban and can burn the exit IP for live too", so
     * the caller must ABORT the segmented burst, not retry through it.
     */
    fun isThrottleSignal(error: Throwable): Boolean = classify(error) == StressSignal.THROTTLED

    private enum class StressSignal {
        NONE,
        THROTTLED,
        TIMEOUT,
        CONNECTION_RESET,
        CONNECTION_FAILURE
    }

    private fun classify(error: Throwable): StressSignal {
        return when {
            error is XtreamAuthenticationException -> StressSignal.THROTTLED
            error is XtreamRequestException && error.statusCode in setOf(403, 429) -> StressSignal.THROTTLED
            messageContains(error, "timeout") || messageContains(error, "timed out") -> StressSignal.TIMEOUT
            messageContains(error, "connection reset") || messageContains(error, "reset by peer") -> StressSignal.CONNECTION_RESET
            messageContains(error, "connection refused") ||
                messageContains(error, "failed to connect") ||
                messageContains(error, "unable to resolve host") ||
                messageContains(error, "broken pipe") -> StressSignal.CONNECTION_FAILURE
            error is XtreamNetworkException || error is IOException -> StressSignal.TIMEOUT
            else -> StressSignal.NONE
        }
    }

    private fun segmentedRequestSpacingFor(providerId: Long, stage: Stage): Long {
        if (stage != Stage.CATEGORY && stage != Stage.PAGED && stage != Stage.ON_DEMAND_EPG) {
            return 0L
        }
        val health = providerHealth[providerId] ?: ProviderHealth()
        // This provider rate-limits player_api.php hard (~1 req/IP): a 250 ms cadence
        // (up to 4 req/s) tripped its abuse ban ~40 requests into an 80-category
        // series burst and got the whole exit IP banned — live included. Space
        // segmented requests to stay at/under ~1 req/s so the burst never trips it.
        val baseSpacingMs = when (stage) {
            Stage.CATEGORY -> 800L
            Stage.PAGED -> 600L
            // Deliberately the SAME cadence as CATEGORY rather than something
            // snappier. 800 ms is the spacing that empirically stopped tripping
            // this provider's abuse ban; a user-facing burst is not a reason to
            // run 1.6x faster than the only rate we have evidence for. The guide
            // stays responsive anyway because Room data renders immediately and
            // the empty-guide marks make this burst a one-off per channel.
            Stage.ON_DEMAND_EPG -> 800L
            else -> 0L
        }
        val stressSpacingMs = when {
            health.stressLevel >= 4 -> 1_600L
            health.stressLevel >= 3 -> 1_100L
            health.stressLevel >= 2 -> 700L
            health.stressLevel >= 1 -> 350L
            else -> 0L
        }
        return baseSpacingMs + stressSpacingMs
    }

    private fun messageContains(error: Throwable, needle: String): Boolean =
        error.message.orEmpty().contains(needle, ignoreCase = true)
}
