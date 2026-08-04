# Player Progressive Seek Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a progressive seek feature so that holding the left/right remote arrow on a movie or series episode accelerates the seek step (10 s → 30 s → 2 min → 5 min) based on hold duration, while a single tap continues to seek 10 s.

**Architecture:** A new pure-Kotlin `ProgressiveSeekController` measures hold duration and emits seek "ticks" via a Flow. The `PlayerViewModel` collects ticks and forwards them to `playerEngine.seekForward(ms)` / `seekBackward(ms)`. `PlayerScreen.kt` distinguishes single tap vs. hold start vs. hold release in its DPAD handlers, and `PlayerControlsChrome.SeekRepeatButton` reuses the same controller.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, kotlinx-coroutines, JUnit4 + Google Truth + kotlinx-coroutines-test, Android TV target (minSdk 21+).

**Spec reference:** `player/docs/superpowers/specs/2026-04-27-player-progressive-seek-design.md`

**Repo note:** The working tree is *not* a git repo at the time of writing (`/home/django/Projet/Fuziontv` has no `.git`). The "Commit" steps below assume a git repo is initialised first. If the user is not using git, they can skip the commit steps — the plan still works as a sequence of file changes.

**Working directory for all gradle commands:** `/home/django/Projet/Fuziontv/player`. Run `cd /home/django/Projet/Fuziontv/player` once before starting, or prefix every gradle command with that path.

---

## File structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt` | **Create** | Enum `SeekDirection`, data class `SeekTick`, pure function `tickStepMs(elapsedMs)`, class `ProgressiveSeekController` (start/stop, ticks flow). |
| `app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt` | **Create** | JVM unit tests for `tickStepMs` and the controller behaviour. |
| `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt` | **Modify** | Construct the controller, add `isProgressiveSeekActive` state, `startProgressiveSeek` / `stopProgressiveSeek`, collect ticks → call `playerEngine.seekForward(stepMs)` / `seekBackward(stepMs)`. |
| `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt` | **Modify** | Split `KEYCODE_DPAD_LEFT` / `KEYCODE_DPAD_RIGHT` handlers into 3 cases (tap / hold-start / hold-continue) + `ACTION_UP` stops the seek. `KEYCODE_BACK` and `KEYCODE_DPAD_CENTER` short-circuit to stop a running seek before running the normal logic. |
| `app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt` | **Modify** | `SeekRepeatButton` (lines ~1240–1311) replaces its hand-rolled `delay(350L) … delay(180L)` repeat loop with calls to `viewModel.startProgressiveSeek` / `stopProgressiveSeek`. The single tap path stays as-is. |
| `app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt` | **Modify (optional)** | Add a smoke test that holding `KEYCODE_DPAD_RIGHT` for 6 seconds advances the position by more than 60 s. |

---

## Task 1: Create `ProgressiveSeekController` skeleton + `tickStepMs` (TDD)

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt`

- [ ] **Step 1: Write the failing test for the lowest palier**

Create `app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt`:

```kotlin
package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressiveSeekControllerTest {

    @Test
    fun `tickStepMs returns 10s for elapsed under 2s`() {
        assertThat(tickStepMs(0L)).isEqualTo(10_000L)
        assertThat(tickStepMs(1_000L)).isEqualTo(10_000L)
        assertThat(tickStepMs(1_999L)).isEqualTo(10_000L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (function not defined)**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.ProgressiveSeekControllerTest" 2>&1 | tail -30
```

Expected: compilation error — `Unresolved reference: tickStepMs`.

- [ ] **Step 3: Create the controller file with minimal `tickStepMs` and supporting types**

Create `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt`:

```kotlin
package com.streamvault.app.ui.screens.player

enum class SeekDirection { FORWARD, BACKWARD }

data class SeekTick(val direction: SeekDirection, val stepMs: Long)

internal fun tickStepMs(elapsedMs: Long): Long = when {
    elapsedMs < 2_000L  -> 10_000L
    elapsedMs < 5_000L  -> 30_000L
    elapsedMs < 10_000L -> 120_000L
    else                -> 300_000L
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.ProgressiveSeekControllerTest" 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 5: Add tests for the other paliers and boundaries**

Append to the test class (inside the existing `class` body):

```kotlin
    @Test
    fun `tickStepMs returns 30s between 2s and 5s elapsed`() {
        assertThat(tickStepMs(2_000L)).isEqualTo(30_000L)
        assertThat(tickStepMs(3_500L)).isEqualTo(30_000L)
        assertThat(tickStepMs(4_999L)).isEqualTo(30_000L)
    }

    @Test
    fun `tickStepMs returns 2min between 5s and 10s elapsed`() {
        assertThat(tickStepMs(5_000L)).isEqualTo(120_000L)
        assertThat(tickStepMs(7_500L)).isEqualTo(120_000L)
        assertThat(tickStepMs(9_999L)).isEqualTo(120_000L)
    }

    @Test
    fun `tickStepMs returns 5min beyond 10s elapsed`() {
        assertThat(tickStepMs(10_000L)).isEqualTo(300_000L)
        assertThat(tickStepMs(60_000L)).isEqualTo(300_000L)
        assertThat(tickStepMs(Long.MAX_VALUE)).isEqualTo(300_000L)
    }
```

- [ ] **Step 6: Run all `tickStepMs` tests, expect all pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.ProgressiveSeekControllerTest" 2>&1 | tail -20
```

Expected: 4 tests PASS.

- [ ] **Step 7: Commit (skip if no git repo)**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt \
        player/app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt
git commit -m "feat(player): add tickStepMs helper for progressive seek"
```

---

## Task 2: Implement `ProgressiveSeekController` start/stop with ticks Flow (TDD)

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt`
- Modify: `app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt`

- [ ] **Step 1: Add the failing test for the first emitted tick**

Append to the test class:

```kotlin
    @Test
    fun `start emits ticks at the configured interval with the right step`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)
        advanceTimeBy(180L)
        runCurrent()

        assertThat(received).hasSize(1)
        assertThat(received[0]).isEqualTo(SeekTick(SeekDirection.FORWARD, 10_000L))

        controller.stop()
        collectJob.cancel()
    }
```

Add the imports at the top of the test file (next to existing ones):

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
```

And annotate the test class:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressiveSeekControllerTest {
    // ...
}
```

- [ ] **Step 2: Run test, expect it to fail (`ProgressiveSeekController` not defined)**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.ProgressiveSeekControllerTest" 2>&1 | tail -20
```

Expected: compilation error — `Unresolved reference: ProgressiveSeekController`.

- [ ] **Step 3: Implement `ProgressiveSeekController`**

Edit `app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt`. Add imports and the class:

```kotlin
package com.streamvault.app.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SeekDirection { FORWARD, BACKWARD }

data class SeekTick(val direction: SeekDirection, val stepMs: Long)

internal fun tickStepMs(elapsedMs: Long): Long = when {
    elapsedMs < 2_000L  -> 10_000L
    elapsedMs < 5_000L  -> 30_000L
    elapsedMs < 10_000L -> 120_000L
    else                -> 300_000L
}

class ProgressiveSeekController(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
) {
    private val _ticks = MutableSharedFlow<SeekTick>(extraBufferCapacity = 16)
    val ticks: SharedFlow<SeekTick> = _ticks.asSharedFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var job: Job? = null

    fun start(direction: SeekDirection) {
        if (_isActive.value) return
        val startedAt = clock()
        _isActive.value = true
        job = scope.launch {
            while (true) {
                delay(tickIntervalMs)
                val elapsed = clock() - startedAt
                _ticks.tryEmit(SeekTick(direction, tickStepMs(elapsed)))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _isActive.value = false
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MS: Long = 180L
    }
}
```

- [ ] **Step 4: Run test, expect it to pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.ProgressiveSeekControllerTest" 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 5: Add the test that exercises palier transitions over time**

Append to the test class:

```kotlin
    @Test
    fun `step accelerates with elapsed hold time`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)

        // Run for ~12 seconds total. We should observe at least one step
        // from each palier as elapsed hold time grows.
        advanceTimeBy(12_000L)
        runCurrent()

        val steps = received.map { it.stepMs }.toSet()
        assertThat(steps).containsAtLeast(10_000L, 30_000L, 120_000L, 300_000L)

        controller.stop()
        collectJob.cancel()
    }
```

- [ ] **Step 6: Add the test for `stop()` cancelling the job**

Append:

```kotlin
    @Test
    fun `stop halts further ticks and resets isActive`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)
        advanceTimeBy(360L)
        runCurrent()

        val countBeforeStop = received.size
        assertThat(countBeforeStop).isAtLeast(1)
        assertThat(controller.isActive.value).isTrue()

        controller.stop()
        assertThat(controller.isActive.value).isFalse()

        advanceTimeBy(2_000L)
        runCurrent()

        assertThat(received.size).isEqualTo(countBeforeStop)

        collectJob.cancel()
    }
```

- [ ] **Step 7: Add the test that double `start()` is a no-op**

Append:

```kotlin
    @Test
    fun `second start while active is a no-op`() = runTest {
        val controller = ProgressiveSeekController(
            scope = this,
            clock = { currentTime },
            tickIntervalMs = 180L,
        )
        val received = mutableListOf<SeekTick>()
        val collectJob = launch { controller.ticks.toList(received) }

        controller.start(SeekDirection.FORWARD)
        advanceTimeBy(180L)
        runCurrent()
        val firstCount = received.size

        // Second start should not spin a second job.
        controller.start(SeekDirection.BACKWARD)
        advanceTimeBy(180L)
        runCurrent()

        // Only one new tick should have been emitted, still in the original direction.
        assertThat(received.size).isEqualTo(firstCount + 1)
        assertThat(received.last().direction).isEqualTo(SeekDirection.FORWARD)

        controller.stop()
        collectJob.cancel()
    }
```

- [ ] **Step 8: Run all controller tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.ProgressiveSeekControllerTest" 2>&1 | tail -30
```

Expected: 7 tests PASS.

- [ ] **Step 9: Commit**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/main/java/com/streamvault/app/ui/screens/player/ProgressiveSeekController.kt \
        player/app/src/test/java/com/streamvault/app/ui/screens/player/ProgressiveSeekControllerTest.kt
git commit -m "feat(player): implement ProgressiveSeekController with ticks Flow"
```

---

## Task 3: Wire the controller into `PlayerViewModel`

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt`

The ViewModel doesn't have an existing unit test of its own that's relevant here (the existing `*ViewModelTest.kt` files don't test `PlayerViewModel`, which has many Hilt dependencies). We rely on the controller tests + manual verification + the optional smoke test in Task 7.

- [ ] **Step 1: Locate the bottom of the `PlayerViewModel` body and the existing seek helpers**

Open `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt`. Confirm lines 1724–1727 still match:

```kotlin
fun play() = playerEngine.play()
fun pause() = playerEngine.pause()
fun seekForward() = playerEngine.seekForward()
fun seekBackward() = playerEngine.seekBackward()
```

These four lines are the anchor. We will add the controller plumbing near them.

- [ ] **Step 2: Add the controller field, public state, and methods**

In the body of the class (anywhere after line 88 where `companion object` ends, but a good place is right after the existing `seekBackward` helper at line 1727), insert:

```kotlin
    private val progressiveSeekController = ProgressiveSeekController(scope = viewModelScope)

    val isProgressiveSeekActive: StateFlow<Boolean> = progressiveSeekController.isActive

    init {
        viewModelScope.launch {
            progressiveSeekController.ticks.collect { tick ->
                when (tick.direction) {
                    SeekDirection.FORWARD  -> playerEngine.seekForward(tick.stepMs)
                    SeekDirection.BACKWARD -> playerEngine.seekBackward(tick.stepMs)
                }
            }
        }
    }

    fun startProgressiveSeek(direction: SeekDirection) {
        if (currentContentType == ContentType.LIVE) return
        if (playerEngine.playbackState.value == PlaybackState.ERROR) return
        if (progressiveSeekController.isActive.value) return
        playerEngine.pause()
        progressiveSeekController.start(direction)
    }

    fun stopProgressiveSeek() {
        if (!progressiveSeekController.isActive.value) return
        progressiveSeekController.stop()
        playerEngine.play()
    }
```

**IMPORTANT — `init` block placement:** the `PlayerViewModel` may already have an `init { … }` block. If it does, do **not** add a second one — instead, append the `viewModelScope.launch { progressiveSeekController.ticks.collect { … } }` block to the existing `init`. To check, search for `\binit\b` in the file:

```bash
grep -n "^\s*init\s*{" /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt
```

If a match is found, edit that block. Otherwise, the new `init` is fine.

- [ ] **Step 3: Verify imports are sufficient**

`PlayerViewModel.kt` already imports `viewModelScope`, `kotlinx.coroutines.flow.*`, `kotlinx.coroutines.launch`, `PlaybackState`, and `ContentType` (lines 5, 50, 51, 42, 20). `SeekDirection` and `ProgressiveSeekController` live in the same package, so no import is needed. The `StateFlow` import is already covered by `kotlinx.coroutines.flow.*`.

If a build error reports a missing import for `ProgressiveSeekController` or `SeekDirection`, the `kotlinx.coroutines.flow.*` wildcard does not import classes from the local package; the same-package convention should still resolve them. If a tooling glitch surfaces, add the explicit imports:

```kotlin
import com.streamvault.app.ui.screens.player.ProgressiveSeekController
import com.streamvault.app.ui.screens.player.SeekDirection
```

(These would be redundant in normal Kotlin, but harmless.)

- [ ] **Step 4: Build the app to verify no compile errors**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:compileDebugKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the existing JVM unit tests to confirm nothing regresses**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt
git commit -m "feat(player): expose startProgressiveSeek/stopProgressiveSeek on PlayerViewModel"
```

---

## Task 4: Hook DPAD_LEFT / DPAD_RIGHT in `PlayerScreen` for tap vs hold

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt` (lines ~560–593, the `KEYCODE_DPAD_LEFT` and `KEYCODE_DPAD_RIGHT` branches inside the `onKeyEvent` block)

- [ ] **Step 1: Read the current handler to know exactly what we're replacing**

```bash
sed -n '540,600p' /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
```

The current shape (paraphrased) is approximately:

```kotlin
KEYCODE_DPAD_LEFT -> {
    if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) return@onKeyEvent false
    if (showControls && contentType != "LIVE") return@onKeyEvent false
    // ... LIVE-specific zap logic ...
    if (contentType != "LIVE") {
        if (isRtl) viewModel.seekForward() else viewModel.seekBackward()
        return@onKeyEvent true
    }
    return@onKeyEvent false
}
KEYCODE_DPAD_RIGHT -> {
    if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) return@onKeyEvent false
    if (showControls && contentType != "LIVE") return@onKeyEvent false
    // ... LIVE-specific zap logic ...
    if (contentType != "LIVE") {
        if (isRtl) viewModel.seekBackward() else viewModel.seekForward()
        return@onKeyEvent true
    }
    return@onKeyEvent false
}
```

Get the exact current content into your editing buffer. Confirm the file structure before changing.

- [ ] **Step 2: Refactor — for the non-LIVE seek branch, distinguish `repeatCount`**

In each of the two branches, **replace only the inner non-LIVE seek call** (the line `if (isRtl) viewModel.seekForward() else viewModel.seekBackward()` for LEFT, and the mirror for RIGHT) with a 3-way dispatch on `event.nativeKeyEvent.repeatCount`. Keep the existing `showControls`/overlay guards and the LIVE branch untouched.

For `KEYCODE_DPAD_LEFT`, replace the seek line with:

```kotlin
if (contentType != "LIVE") {
    val direction = if (isRtl) SeekDirection.FORWARD else SeekDirection.BACKWARD
    when (event.nativeKeyEvent.repeatCount) {
        0 -> {
            // Single tap: seek 10 s as today
            if (direction == SeekDirection.FORWARD) viewModel.seekForward()
            else viewModel.seekBackward()
        }
        1 -> {
            // First repeat → user is holding; start progressive seek
            viewModel.startProgressiveSeek(direction)
        }
        else -> {
            // Subsequent repeats are absorbed by the controller
        }
    }
    return@onKeyEvent true
}
```

Mirror change for `KEYCODE_DPAD_RIGHT`:

```kotlin
if (contentType != "LIVE") {
    val direction = if (isRtl) SeekDirection.BACKWARD else SeekDirection.FORWARD
    when (event.nativeKeyEvent.repeatCount) {
        0 -> {
            if (direction == SeekDirection.FORWARD) viewModel.seekForward()
            else viewModel.seekBackward()
        }
        1 -> {
            viewModel.startProgressiveSeek(direction)
        }
        else -> { /* absorbed */ }
    }
    return@onKeyEvent true
}
```

- [ ] **Step 3: Add an `ACTION_UP` handler that stops the progressive seek**

`PlayerScreen.kt`'s outer guard at line 435 currently filters out `ACTION_UP` (`if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false`). The seek logic lives in the **outer** `onKeyEvent` block (at line 470), not the inner `onPreviewKeyEvent`, so we need to handle `ACTION_UP` there.

Inspect the outer `onKeyEvent` block (line 470) to confirm whether it currently handles `ACTION_UP` for any keycodes. If it does not (most paths short-circuit on `ACTION_DOWN`-only branches further down), add at the very top of the `onKeyEvent` block, **before** any other action checks:

```kotlin
.onKeyEvent { event ->
    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
        val keyCode = event.nativeKeyEvent.keyCode
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (viewModel.isProgressiveSeekActive.value) {
                viewModel.stopProgressiveSeek()
                return@onKeyEvent true
            }
        }
        // Fall through to the existing handler so other ACTION_UP behaviour is unaffected.
    }
    // ... existing handler body unchanged from here ...
```

Place this snippet immediately after the opening `.onKeyEvent { event ->` line (or directly after any one-line preconditions that already filter `event.nativeKeyEvent.action`). Do **not** swap the existing guard that returns `false` for non-`ACTION_DOWN` events — the new block lives before that guard and only catches the LEFT/RIGHT case.

- [ ] **Step 4: Build to verify**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
git commit -m "feat(player): trigger progressive seek on DPAD hold"
```

---

## Task 5: Stop a running seek when the user presses OK or Back

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt` (the existing `KEYCODE_BACK` and `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` branches in the `onKeyEvent` block, around lines 624 and 542)

- [ ] **Step 1: Locate the `KEYCODE_DPAD_CENTER` branch (line ~542)**

```bash
sed -n '540,560p' /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
```

The branch handles toggle of the controls overlay on OK press.

- [ ] **Step 2: Insert a guard at the top of the `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` branch**

At the very beginning of the `KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { … }` block, before any existing logic, add:

```kotlin
if (viewModel.isProgressiveSeekActive.value) {
    viewModel.stopProgressiveSeek()
    return@onKeyEvent true
}
```

Net effect: if a progressive seek is running, OK confirms the position and resumes playback. The existing controls-toggle logic only runs when no seek is active.

- [ ] **Step 3: Insert the same guard at the top of the `KEYCODE_BACK` branch (line ~624)**

```bash
sed -n '622,632p' /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
```

Inside the `KEYCODE_BACK -> { … }` block, before the existing logic, add:

```kotlin
if (viewModel.isProgressiveSeekActive.value) {
    viewModel.stopProgressiveSeek()
    // Continue into the existing back-handler so the overlay still closes.
}
```

Note the difference: for OK we `return@onKeyEvent true` (we don't want to also toggle controls); for Back we **fall through** so the existing close-overlay-or-exit logic still runs.

- [ ] **Step 4: Build**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
git commit -m "feat(player): confirm progressive seek on OK/Back"
```

---

## Task 6: Replace `SeekRepeatButton`'s hand-rolled repeat with the controller

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt` (lines ~1235–1311, the `PlayerTransportButton` composable that wraps the rewind/forward buttons)

The current implementation rolls its own `delay(350L) … delay(180L)` repeat loop *and* calls `onClick()` once per tick at constant 10 s. We want the same on-screen button to feel identical to the remote arrow: tap = 10 s, hold = progressive.

The cleanest way: pass new `onSeekHoldStart` / `onSeekHoldStop` callbacks down to the chrome and have the button trigger them on long-press OK while keeping `onClick` for the single tap.

- [ ] **Step 1: Add two new optional callbacks to `PlayerControlsChrome` parameters**

`PlayerControlsChrome.kt` currently exposes `onSeekBackward: () -> Unit` and `onSeekForward: () -> Unit` as parameters (lines 115–116, 531–532, 830–831). Add four new optional parameters with `noop` defaults next to each pair:

In the top-level `PlayerControlsChrome` composable signature (around line 115):

```kotlin
onSeekBackward: () -> Unit,
onSeekForward: () -> Unit,
onSeekBackwardHoldStart: () -> Unit = {},
onSeekBackwardHoldStop: () -> Unit = {},
onSeekForwardHoldStart: () -> Unit = {},
onSeekForwardHoldStop: () -> Unit = {},
```

Mirror the same additions on the inner composables that currently receive `onSeekBackward` / `onSeekForward` (the `PlayerControlsContent` at line 531 and the `PlayerControlsTransport` at line 830, based on the current call graph). Forward the new callbacks down through each composable.

- [ ] **Step 2: Modify `PlayerTransportButton` to accept hold callbacks**

In `PlayerTransportButton` (line 1236), replace its current signature:

```kotlin
@Composable
private fun PlayerTransportButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier
)
```

with:

```kotlin
@Composable
private fun PlayerTransportButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    onHoldStart: () -> Unit = {},
    onHoldStop: () -> Unit = {},
    buttonSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier
)
```

- [ ] **Step 3: Replace the hand-rolled repeat logic inside `PlayerTransportButton`**

Inside `PlayerTransportButton`, replace this block (lines ~1243–1266 — `coroutineScope`, `latestOnClick`, `repeatJob`, `stopRepeating`, `performSeekStep`, `startRepeating`, and the trailing `DisposableEffect`):

```kotlin
val coroutineScope = rememberCoroutineScope()
val latestOnClick by rememberUpdatedState(onClick)
var repeatJob by remember { mutableStateOf<Job?>(null) }

fun stopRepeating() {
    repeatJob?.cancel()
    repeatJob = null
}

fun performSeekStep() {
    latestOnClick()
}

fun startRepeating() {
    if (repeatJob?.isActive == true) return
    performSeekStep()
    repeatJob = coroutineScope.launch {
        delay(350L)
        while (true) {
            performSeekStep()
            delay(180L)
        }
    }
}
```

with:

```kotlin
val latestOnHoldStart by rememberUpdatedState(onHoldStart)
val latestOnHoldStop by rememberUpdatedState(onHoldStop)
var holdActive by remember { mutableStateOf(false) }
```

Then replace the `onPreviewKeyEvent` body (lines ~1277–1296) — the `KEYCODE_DPAD_CENTER`/`ENTER`/`NUMPAD_ENTER` switch — with:

```kotlin
.onPreviewKeyEvent { event ->
    when (event.nativeKeyEvent.keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            when (event.nativeKeyEvent.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        // Tap: do nothing here — the surface's onClick fires for tap.
                        false
                    } else if (event.nativeKeyEvent.repeatCount == 1 && !holdActive) {
                        holdActive = true
                        latestOnHoldStart()
                        true
                    } else {
                        // Subsequent repeats absorbed.
                        true
                    }
                }
                android.view.KeyEvent.ACTION_UP -> {
                    if (holdActive) {
                        holdActive = false
                        latestOnHoldStop()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        else -> false
    }
}
```

Replace the trailing `DisposableEffect(Unit) { onDispose { stopRepeating() } }` block (lines ~1308–1310) with:

```kotlin
DisposableEffect(Unit) {
    onDispose {
        if (holdActive) {
            holdActive = false
            latestOnHoldStop()
        }
    }
}
```

Remove the now-unused imports (`Job`, possibly `delay` if no other code in the file uses them):

```bash
grep -n "import kotlinx.coroutines.Job\|import kotlinx.coroutines.delay" /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt
grep -cn "\bJob\b\|\bdelay(" /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt
```

If `Job` / `delay` are not referenced anywhere else, remove their imports. If they are still used (e.g. by other auto-repeat mechanisms in this 1391-line file), leave them.

- [ ] **Step 4: Wire the new callbacks at the call sites for the rewind/forward buttons**

Find the two `PlayerTransportButton` invocations (lines ~971 and ~1012). For the rewind button (line ~971):

```kotlin
PlayerTransportButton(
    label = "⏪",
    contentDescription = stringResource(R.string.player_rewind),
    onClick = onSeekBackward,
    onHoldStart = onSeekBackwardHoldStart,
    onHoldStop = onSeekBackwardHoldStop,
    buttonSize = transportButtonSize,
    modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
)
```

Same shape for forward (line ~1012):

```kotlin
PlayerTransportButton(
    label = "⏩",
    contentDescription = stringResource(R.string.player_forward),
    onClick = onSeekForward,
    onHoldStart = onSeekForwardHoldStart,
    onHoldStop = onSeekForwardHoldStop,
    buttonSize = transportButtonSize,
    modifier = Modifier.focusProperties { down = quickActionsFocusRequester }
)
```

- [ ] **Step 5: Pass the four new callbacks from `PlayerScreen.kt` into the chrome**

Find the call site of `PlayerControlsChrome` in `PlayerScreen.kt`:

```bash
grep -n "PlayerControlsChrome(" /home/django/Projet/Fuziontv/player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
```

In that call, pass:

```kotlin
onSeekBackward = { viewModel.seekBackward() },
onSeekForward = { viewModel.seekForward() },
onSeekBackwardHoldStart = { viewModel.startProgressiveSeek(SeekDirection.BACKWARD) },
onSeekBackwardHoldStop = { viewModel.stopProgressiveSeek() },
onSeekForwardHoldStart = { viewModel.startProgressiveSeek(SeekDirection.FORWARD) },
onSeekForwardHoldStop = { viewModel.stopProgressiveSeek() },
```

(The first two lines may already exist in some form — keep them, just add the four hold callbacks.)

If `SeekDirection` is not yet imported in `PlayerScreen.kt`, add:

```kotlin
import com.streamvault.app.ui.screens.player.SeekDirection
```

Same package, so this import is technically redundant — only add it if the build complains.

- [ ] **Step 6: Build**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:compileDebugKotlin 2>&1 | tail -25
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run all unit tests to confirm nothing regressed**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt \
        player/app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
git commit -m "feat(player): align on-screen seek buttons with progressive seek"
```

---

## Task 7: (Optional) Smoke test — hold DPAD_RIGHT advances by more than 60 s

**Files:**
- Modify: `app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt`

This test requires a connected device or emulator. Skip if you don't have one.

- [ ] **Step 1: Read the existing smoke test to understand setup conventions**

```bash
cat /home/django/Projet/Fuziontv/player/app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt
```

If the existing test does not already navigate to a VOD movie and start playback, the new test will be involved enough to be brittle. In that case, **skip this task** and rely on manual testing on real hardware (Task 8).

- [ ] **Step 2: Add the test (only if step 1 confirmed easy navigation to a VOD)**

Append to the existing class:

```kotlin
@Test
fun holdingDpadRightAdvancesPositionPastSinglePalier() {
    // Pre-conditions: user is already on a VOD movie playing from t=0.
    // (Implementation depends on the existing test scaffolding — see step 1.)

    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val before = readCurrentPositionMs()

    // Press and hold DPAD_RIGHT for ~6 seconds to cross into palier 2 (×3).
    device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) // single tap → +10 s
    Thread.sleep(200)
    repeat(50) {  // simulate hold via repeated key down events
        InstrumentationRegistry.getInstrumentation().sendKeySync(
            KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, it + 1)
        )
        Thread.sleep(120)
    }
    InstrumentationRegistry.getInstrumentation().sendKeySync(
        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
    )

    val after = readCurrentPositionMs()
    assertThat(after - before).isGreaterThan(60_000L)
}

private fun readCurrentPositionMs(): Long {
    // Resolve via the activity's exposed PlayerEngine reference, or a dedicated test hook.
    // If no such hook exists, leave this test out.
    TODO("Wire to an existing test hook in PlayerActivity / PlayerSmokeTest scaffolding.")
}
```

If the file has no existing position-reading hook, skip the test. Manual verification on hardware (Task 8) covers the same behaviour.

- [ ] **Step 3: Run the smoke test (if added)**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:connectedDebugAndroidTest --tests "*PlayerSmokeTest*" 2>&1 | tail -25
```

Expected: PASS. Otherwise debug or skip.

- [ ] **Step 4: Commit (if added)**

```bash
cd /home/django/Projet/Fuziontv
git add player/app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt
git commit -m "test(player): smoke test for progressive seek on hold"
```

---

## Task 8: Final verification — build APK, install, manual test on hardware

**Files:** none

- [ ] **Step 1: Run full unit test suite**

```bash
cd /home/django/Projet/Fuziontv/player
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Build the debug APK**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. APK is at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Install on the Amlogic S905X5M target**

```bash
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Substitute `<device-serial>` with the actual serial from `adb devices`. Because both `release` and `debug` use the release signing config when `keystore.properties` exists (per `CLAUDE.md`), this installs over an existing release build without wiping data.

- [ ] **Step 4: Manual checklist on hardware**

- [ ] Open a movie. Single tap on right arrow → seeks 10 s, lecture continues. (Unchanged behaviour.)
- [ ] Hold right arrow for 1 s → after the key-repeat threshold (~400 ms) playback pauses and the position advances by ~10 s steps.
- [ ] Continue holding for 5 s total → position now advancing by 30 s steps.
- [ ] Continue for 11 s → position advancing by 5 min steps.
- [ ] Release → playback resumes at the new position.
- [ ] Press OK during a hold → seek stops, playback resumes. Controls do **not** also toggle on.
- [ ] Press Back during a hold → seek stops, playback resumes at new position; overlay closes if it was open.
- [ ] Open the on-screen overlay, focus the rewind button, press and hold OK → same progressive behaviour as the remote.
- [ ] Switch to a LIVE channel: left/right arrows still zap channels — progressive seek does **not** trigger.
- [ ] Force engine error (e.g. cut Wi-Fi after switching to a stream that requires it) → holding right arrow does nothing, no crash.

- [ ] **Step 5: Watch logcat during testing for unexpected stack traces**

```bash
adb -s <device-serial> logcat -s "AndroidRuntime:E" "VLC:E" "ExoPlayer:E" "PlayerViewModel" 2>&1 | head -50
```

If LibVLC reports any native crash, **revert immediately** (the LibVLC engine is fragile per `CLAUDE.md`).

- [ ] **Step 6: If everything works, commit any outstanding changes (none expected) and close the task**

```bash
cd /home/django/Projet/Fuziontv
git status
```

Expected: clean working tree (all earlier tasks already committed).

---

## Self-review notes

- Spec coverage:
  - User-facing behaviour (table of paliers, OK/Back, visual feedback) → Tasks 1–6.
  - Architecture (controller, ViewModel, screen, chrome) → Tasks 1–6.
  - Edge cases (LIVE, RTL, slider focused, error, double-direction, end-of-file clamping) → preserved by reusing existing branches and engine clamps. RTL test relies on existing `isRtl` swap.
  - Tests (JVM tickStepMs + flow) → Tasks 1–2 (7 tests).
  - Out-of-scope items (timecode entry, thumbnails, badge, variable speed) → not in plan, by design.
- Type consistency: `SeekDirection`, `SeekTick`, `tickStepMs`, `ProgressiveSeekController`, `startProgressiveSeek`, `stopProgressiveSeek`, `isProgressiveSeekActive` — used identically in every task.
- Placeholders: the only TODO is in Task 7 step 2 (`readCurrentPositionMs`), and it's gated on whether the existing smoke-test scaffolding exposes a position hook. The task is explicitly marked optional and includes a "skip if not feasible" path.
