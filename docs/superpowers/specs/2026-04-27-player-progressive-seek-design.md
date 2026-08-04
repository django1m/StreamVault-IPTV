# Progressive seek — design

**Date:** 2026-04-27
**Status:** Approved (brainstorming complete, awaiting implementation plan)
**Scope:** `player/` module (Android TV app)

## Problem

When watching a movie or series episode in the player, the only way to navigate the timeline is:

- A single press on the remote left/right arrow → seek of 10 s.
- Holding the arrow → Android repeats the key event, but at a constant rate (~55 s of seeked content per second of hold).
- Using the slider when controls are visible → fine but slow scrubbing.

To reach `01:30:00` of a movie from the beginning, the user has to either tap the right arrow ~540 times or hold it for ~1m40s. This is a friction point reported during real-world use on the primary hardware target (Amlogic S905X5M, 2 GB RAM).

## Goal

Allow the user to seek anywhere in a VOD timeline (movie or series episode) within a few seconds of remote interaction, **without** changing how short seeks work today and **without** introducing complexity in the rendering path that could destabilize the box.

LIVE TV is explicitly out of scope: left/right arrows on a live channel remain bound to channel zap.

## User-facing behavior

### On a movie or series episode

1. **Single tap on left/right arrow** — same as today: seek of 10 s. Lecture continues. No visual change.
2. **Hold on left/right arrow** — when Android signals key repeat (typically after ~400–500 ms of physical hold; this is the device's key-repeat threshold, not configurable here), the player pauses and a progressive seek begins. The seek tick step accelerates with the duration of the hold, measured **from the moment the controller starts** (i.e. from when key repeat kicks in, not from the very first key-down):

   | Hold duration | Step per tick | Effective speed |
   |---|---|---|
   | 0–2 s | 10 s | ×1 (≈55 s/s) |
   | 2–5 s | 30 s | ×3 |
   | 5–10 s | 2 min | ×12 |
   | > 10 s | 5 min | ×30 |

   Tick interval is **180 ms**, matching the existing repeat rate in `SeekRepeatButton`. To reach `01:30:00` from `00:00:00` with the schema above takes ≈12 seconds of hold (after the ~500 ms key-repeat threshold). The single tap fired at key-down (10 s) is also applied, so the user always sees an immediate response on first press.

3. **Release the arrow** — playback resumes at the new position.
4. **Press OK during a hold** — equivalent to releasing: stop the seek, resume playback at the current seeked position.
5. **Press Back during a hold** — same as OK (confirm the position), then close the overlay if visible. The original position is **not** restored.
6. **Press the on-screen +/- button** — same behavior as the remote arrow (single tap = 10 s; hold = progressive seek).

### Visual feedback during a hold

- The timeline progress bar moves in real time as ticks fire.
- The current/total timecode above the bar updates (`01:27:45 / 02:14:20`, formatted by the existing `formatDuration`).
- **No** speed-multiplier badge (`×3`, `×30`, etc.) is displayed. This is a deliberate UX choice — the user wants minimal chrome.
- **No** thumbnail preview is rendered. The existing `SeekThumbnailProvider` is not invoked during a progressive seek.

### On LIVE TV

No change. The progressive seek controller is never started for `ContentType.LIVE`. Left/right remain bound to channel zap, exactly as today.

### When the slider is focused (controls overlay visible, non-LIVE)

No change. The slider continues to handle its own seek (current branch `if (showControls && contentType != "LIVE") return@onKeyEvent false`). The user can still scrub finely via the slider when they want precision.

## Architecture

The implementation introduces a single new component and modifies four existing files. Three modules of the monorepo (`domain/`, `data/`, `player/`) are not touched.

### New: `ProgressiveSeekController`

**Location:** `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt`

**Responsibility:** measure the duration of a hold, compute the seek step for the current tick, and emit ticks. Pure Kotlin, no Android dependency, fully testable on JVM.

**Public API (sketch — final shape decided in implementation plan):**

```kotlin
enum class SeekDirection { FORWARD, BACKWARD }

class ProgressiveSeekController(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickIntervalMs: Long = 180L,
) {
    val ticks: SharedFlow<SeekTick>  // (direction, stepMs)
    val isActive: StateFlow<Boolean>

    fun start(direction: SeekDirection)
    fun stop()
}

// Pure function, exposed for unit testing.
internal fun tickStepMs(elapsedMs: Long): Long = when {
    elapsedMs < 2_000  -> 10_000
    elapsedMs < 5_000  -> 30_000
    elapsedMs < 10_000 -> 120_000
    else               -> 300_000
}
```

The controller schedules a coroutine that fires a tick every `tickIntervalMs` and computes the step based on `clock() - startTime`. `stop()` cancels the job and resets state. Re-calling `start()` while active is a no-op (defensive — keeps the state machine simple).

### Modifications

**`PlayerViewModel.kt`** — add two methods:

```kotlin
fun startProgressiveSeek(direction: SeekDirection) {
    if (currentContentType == ContentType.LIVE) return
    if (playerEngine.playbackState.value == PlaybackState.ERROR) return
    playerEngine.pause()
    progressiveSeekController.start(direction)
}

fun stopProgressiveSeek() {
    progressiveSeekController.stop()
    playerEngine.play()
}
```

The ViewModel collects `progressiveSeekController.ticks` in `init` and on each tick calls `playerEngine.seekForward(stepMs)` or `seekBackward(stepMs)`. The engine clamps to `[0, duration]` (existing behavior — `Media3PlayerEngine.seekForward` uses `coerceAtMost(duration)` and `seekBackward` uses `coerceAtLeast(0L)`; `LibVlcPlayerEngine` does the same against `player.length`).

**`PlayerScreen.kt`** — the `KEYCODE_DPAD_LEFT` / `KEYCODE_DPAD_RIGHT` handlers (lines 560–593) split into three cases driven by `event.action` and `event.repeatCount`:

| Event | Action |
|---|---|
| `ACTION_DOWN`, `repeatCount == 0` | Single tap. Call `viewModel.seekBackward()` or `seekForward()` — same as today. |
| `ACTION_DOWN`, `repeatCount == 1` | First repeat fired by Android. The user is holding. Call `viewModel.startProgressiveSeek(direction)`. |
| `ACTION_DOWN`, `repeatCount >= 2` | Subsequent repeats. Ignore (the controller is driving the ticks now). |
| `ACTION_UP` | If a progressive seek is active, call `viewModel.stopProgressiveSeek()`. |

The existing guards (`showControls && contentType != "LIVE" → return false`, RTL swap) are preserved. The OK/Back handling during a hold is added by checking `viewModel.isProgressiveSeekActive` in their existing branches and calling `stopProgressiveSeek()` before the existing logic runs.

**`PlayerControlsChrome.kt`** — `SeekRepeatButton` (lines ~1240–1311) replaces its hand-rolled repeat loop (`delay(350L) … delay(180L)`) with a call into the same `ProgressiveSeekController`. This guarantees the on-screen +/- buttons feel identical to the remote arrows.

**`PlayerScreen.kt` overlay binding** — pass `viewModel::startProgressiveSeek` and `viewModel::stopProgressiveSeek` down to the chrome via the existing `onSeekBackward` / `onSeekForward` lambdas (or new lambdas if keeping the existing ones unchanged is cleaner).

### Engine layer — unchanged

`PlayerEngine.seekForward(ms)` and `seekBackward(ms)` already accept any millisecond value. No change to `Media3PlayerEngine` or `LibVlcPlayerEngine`. This matters: the LibVLC engine is fragile (see CLAUDE.md note about `release()`) and we want to avoid touching it.

### Domain & data layers — unchanged

This is a UX-only feature. No new models, no persistence, no API calls.

## Edge cases

| Situation | Behavior |
|---|---|
| Seek goes past end of file | `seekForward` clamps to `duration` (existing). Player remains paused at that position until the user releases. |
| Seek goes past start of file | `seekBackward` clamps to `0` (existing). |
| User presses OK during a hold | `stopProgressiveSeek()` is invoked (resume play at current seeked position). The OK behavior beyond that (toggle controls, etc.) runs normally. |
| User presses Back during a hold | `stopProgressiveSeek()` is invoked, then existing Back behavior runs (close overlay or exit player). New position is preserved. |
| User reverses direction without releasing | Android emits `ACTION_UP` on the first key before `ACTION_DOWN` on the second. The controller stops, the ViewModel resumes play, then a new `start()` fires in the opposite direction. Two ~80 ms gaps in playback resumption are acceptable. |
| Engine in error state | `startProgressiveSeek` is a no-op (consistent with existing seek behavior on error). |
| LIVE TV | The handler short-circuits before reaching the progressive seek branch. Channel zap behavior is preserved. |
| RTL locale | The existing swap (lines 573, 588) is preserved: in RTL, right arrow calls `startProgressiveSeek(BACKWARD)` and vice-versa. |
| Slider focused (controls visible, non-LIVE) | Handler returns false at the existing guard. The slider's own seek logic runs. Progressive seek is not triggered. |
| Hold released within key-repeat threshold (<~400 ms) | Android only emits `ACTION_DOWN repeatCount=0` then `ACTION_UP`. `start()` is never called. Effect: just the single tap (10 s seek). |
| Hold released within 180 ms after key repeat starts | `start()` was called (on `repeatCount == 1`), then `stop()` before any tick fires. The pause/resume happens, but no extra seek beyond the initial tap. Net effect: ≈ same as a tap, with a brief pause-resume blip. Acceptable. |
| LibVLC engine active | Same code path as Media3: `seekForward(ms)` is called. LibVLC's `seekForward` (lines 206–212) uses `seekTo(currentPosition + ms)`. No new native calls beyond what's already there. |

## Performance considerations (S905X5M, 2 GB RAM)

- During a hold, the player is **paused**. No decoding happens. `seekTo` on a paused player is essentially a metadata update — no buffering pressure.
- Tick frequency is 180 ms (≈5.5 Hz). At ×30 (5 min steps), the seek positions are far apart, but the engine only loads the new position on resume. There is no chained buffering between ticks.
- The controller is a single coroutine on `viewModelScope`. No threads spawned.
- Memory: `ProgressiveSeekController` is a small object (~hundreds of bytes). Negligible.
- No additional ANR risk: all native LibVLC calls go through the existing `seekForward` path which is already wrapped in try/catch (per CLAUDE.md guidance).

## Testing

### JVM unit tests (fast, no emulator)

`app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt`:

- `tickStepMs(0) == 10_000`
- `tickStepMs(1_999) == 10_000` (boundary, palier 1)
- `tickStepMs(2_000) == 30_000` (boundary, palier 2 starts)
- `tickStepMs(4_999) == 30_000`
- `tickStepMs(5_000) == 120_000` (palier 3 starts)
- `tickStepMs(9_999) == 120_000`
- `tickStepMs(10_000) == 300_000` (palier 4 starts)
- `tickStepMs(60_000) == 300_000` (palier 4 saturates)
- Flow test with `TestScope` + `advanceTimeBy`: `start(FORWARD)` emits ticks at 180 ms intervals with steps following the palier table.
- `stop()` cancels the job; `isActive` becomes false; no further ticks emitted.
- Re-calling `start()` while active is a no-op.

### Instrumented test (existing emulator/device)

Extend `app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt`:

- Open a VOD movie. Wait for ready state.
- Hold `KEYCODE_DPAD_RIGHT` for 6 s.
- Assert `playerEngine.currentPosition.value > 60_000` (i.e. we advanced more than 60 s — proves we crossed at least into palier 2).

### Manual test on real hardware (S905X5M)

- Verify smooth visual feedback on the progress bar during hold.
- Verify no stutter or ANR when releasing after a 30 s hold.
- Verify LibVLC fallback engine also behaves correctly (test with a stream known to trigger LibVLC).

## Out of scope (deliberately)

- **Direct timecode entry** (option C from brainstorming) — not requested, adds modal UX complexity.
- **Thumbnail preview during progressive seek** — would require running `SeekThumbnailProvider` per tick, costly on S905X5M.
- **Speed multiplier badge** (`►► ×30`) — user explicitly chose minimal feedback.
- **Variable playback speed** (×2/×4 like a VHS fast-forward) — not the same UX, and Media3's playback speed has its own decoder considerations on low-end hardware.
- **Chapter markers / per-scene navigation** — not present in the metadata pipeline today.
- **LIVE TV DVR-style seek** — out of scope; LIVE keeps channel zap.
- **Customisable palier values via settings** — start with hard-coded values; a settings entry can be added later if needed.

## Deliverables

1. `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt` (new)
2. `app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt` (new)
3. Modifications to `PlayerViewModel.kt`
4. Modifications to `PlayerScreen.kt` (DPAD_LEFT/RIGHT handlers + Back/OK guards)
5. Modifications to `PlayerControlsChrome.kt` (`SeekRepeatButton` integration)
6. Possible extension of `PlayerSmokeTest.kt`
7. Build & test verification: `./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug`

## Open questions

None. All clarifications resolved during brainstorming.
