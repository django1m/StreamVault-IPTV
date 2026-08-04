# Vérification de MAJ et sync auto au démarrage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** À chaque ouverture de l'app Android (cold start ou retour foreground), vérifier immédiatement si une nouvelle version APK est publiée sur le webapp admin (popup direct), et si la dernière sync de la playlist date de plus de 48 h, déclencher une sync silencieuse en arrière-plan avec snackbar discret.

**Architecture:** Un singleton `AppStartupCoordinator` observe `ProcessLifecycleOwner` et orchestre les deux checks à chaque transition `ON_START`. Il réutilise l'infrastructure existante (`AppUpdateChecker` qui appelle déjà `/api/v1/updates/latest`, `AppUpdateStartupDialog` déjà câblé dans `AppNavigation`, `SyncManager.sync()` déjà éprouvé). Un `SyncSnackbarController` expose les événements de sync au `AppNavigation` pour affichage. Le `HeartbeatWorker` (15 min) est étendu pour rafraîchir aussi le cache MAJ en parallèle (defense in depth). On en profite pour renommer la classe legacy `GitHubReleaseChecker` → `AppUpdateChecker` (le nom GitHub est trompeur — elle pointe vers le webapp FuzionTV).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt (DI), Coroutines + Flow, ProcessLifecycleOwner (`androidx.lifecycle:lifecycle-process`), JUnit 4, mockito-kotlin, kotlinx-coroutines-test.

**Spec:** `player/docs/superpowers/specs/2026-04-28-startup-update-and-sync-design.md`

---

## File Structure

### New files
- `player/app/src/main/java/com/streamvault/app/startup/AppStartupCoordinator.kt` — singleton observant `ProcessLifecycleOwner`, orchestre check MAJ + sync 48 h
- `player/app/src/main/java/com/streamvault/app/startup/SyncSnackbarController.kt` — singleton exposant `SharedFlow<SyncSnackbarEvent>` pour le UI
- `player/app/src/test/java/com/streamvault/app/startup/AppStartupCoordinatorTest.kt`
- `player/app/src/test/java/com/streamvault/app/startup/SyncSnackbarControllerTest.kt`
- `player/app/src/test/java/com/streamvault/app/work/HeartbeatWorkerUpdateCacheTest.kt` — test ciblé sur l'extension cache MAJ

### Renamed files
- `player/app/src/main/java/com/streamvault/app/update/GitHubReleaseChecker.kt` → `AppUpdateChecker.kt` (classe et data class renommées)

### Modified files
- `player/app/build.gradle.kts` — ajout dépendance `androidx.lifecycle:lifecycle-process`
- `player/gradle/libs.versions.toml` — ajout alias `lifecycle-process`
- `player/app/src/main/java/com/streamvault/app/StreamVaultApp.kt` — retire `refreshCachedAppUpdateIfNeeded`, attache `AppStartupCoordinator` à `ProcessLifecycleOwner`
- `player/app/src/main/java/com/streamvault/app/work/HeartbeatWorker.kt` — appelle `AppUpdateChecker` à chaque exécution
- `player/app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt` — collecte les events du `SyncSnackbarController`, affiche un `SnackbarHost`
- `player/app/src/main/java/com/streamvault/app/ui/screens/update/AppUpdateStartupViewModel.kt` — import renommé
- `player/app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardViewModel.kt` — import renommé
- `player/app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsAppUpdateActions.kt` — type renommé
- `player/app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModel.kt` — type renommé
- `player/app/src/main/res/values/strings.xml` — 3 strings ajoutées (`startup_sync_started`, `startup_sync_success`, `startup_sync_failed`)

---

## Task 1: Renommer GitHubReleaseChecker → AppUpdateChecker

**Why first:** refactor pur, pas de logique modifiée. On part d'un état propre. Tous les tests existants restent verts.

**Files:**
- Rename: `player/app/src/main/java/com/streamvault/app/update/GitHubReleaseChecker.kt` → `player/app/src/main/java/com/streamvault/app/update/AppUpdateChecker.kt`
- Modify: `player/app/src/main/java/com/streamvault/app/StreamVaultApp.kt` (imports + champ)
- Modify: `player/app/src/main/java/com/streamvault/app/ui/screens/update/AppUpdateStartupViewModel.kt` (import)
- Modify: `player/app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardViewModel.kt` (import)
- Modify: `player/app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsAppUpdateActions.kt` (import + type)
- Modify: `player/app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModel.kt` (import + type)

- [ ] **Step 1: Renommer le fichier et la classe**

```bash
mv player/app/src/main/java/com/streamvault/app/update/GitHubReleaseChecker.kt \
   player/app/src/main/java/com/streamvault/app/update/AppUpdateChecker.kt
```

Puis dans `AppUpdateChecker.kt`, remplacer :
- `class GitHubReleaseChecker` → `class AppUpdateChecker`
- `data class GitHubReleaseInfo` → `data class AppUpdateInfo`
- Le commentaire en tête de classe :

```kotlin
/**
 * Fetches the latest published APK from the FuzionTV admin backend
 * (`/api/v1/updates/latest`).
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
```

Et changer toutes les références internes au fichier :
- `Result<GitHubReleaseInfo>` → `Result<AppUpdateInfo>`
- `GitHubReleaseInfo(...)` → `AppUpdateInfo(...)`

- [ ] **Step 2: Mettre à jour StreamVaultApp.kt**

Remplacer dans `player/app/src/main/java/com/streamvault/app/StreamVaultApp.kt` :

```kotlin
// Avant
import com.streamvault.app.update.GitHubReleaseChecker
// ...
@Inject
lateinit var gitHubReleaseChecker: GitHubReleaseChecker
```

Par :

```kotlin
import com.streamvault.app.update.AppUpdateChecker
// ...
@Inject
lateinit var appUpdateChecker: AppUpdateChecker
```

Et dans `refreshCachedAppUpdateIfNeeded()`, changer `gitHubReleaseChecker.fetchLatestRelease()` → `appUpdateChecker.fetchLatestRelease()`. (Cette méthode sera supprimée à la Task 7, on la garde fonctionnelle pour l'instant.)

- [ ] **Step 3: Mettre à jour les 4 autres call-sites**

Dans `AppUpdateStartupViewModel.kt`, remplacer l'import :
```kotlin
import com.streamvault.app.update.GitHubReleaseInfo
```
par :
```kotlin
import com.streamvault.app.update.AppUpdateInfo
```

Et toutes les références à `GitHubReleaseInfo(` → `AppUpdateInfo(`.

Dans `DashboardViewModel.kt` :
```kotlin
import com.streamvault.app.update.AppUpdateInstaller
```
(pas de changement — il n'utilise que `AppUpdateInstaller`, vérifier qu'aucune ref `GitHubRelease*` ne traîne, sinon les remplacer).

Dans `SettingsAppUpdateActions.kt`, remplacer :
```kotlin
import com.streamvault.app.update.GitHubReleaseChecker
// ...
private val gitHubReleaseChecker: GitHubReleaseChecker,
```
par :
```kotlin
import com.streamvault.app.update.AppUpdateChecker
// ...
private val appUpdateChecker: AppUpdateChecker,
```

Et toutes les utilisations `gitHubReleaseChecker.` → `appUpdateChecker.`.

Dans `SettingsViewModel.kt`, idem :
```kotlin
import com.streamvault.app.update.AppUpdateChecker
// ...
private val appUpdateChecker: AppUpdateChecker,
```

- [ ] **Step 4: Vérifier qu'il ne reste aucune référence**

```bash
cd player
grep -rn "GitHubReleaseChecker\|GitHubReleaseInfo" --include="*.kt" 2>/dev/null
```

Expected: aucun résultat.

- [ ] **Step 5: Compiler**

```bash
cd player
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Lancer les tests existants**

```bash
cd player
./gradlew :app:testDebugUnitTest
```

Expected: tous les tests passent (aucune logique modifiée).

- [ ] **Step 7: Commit**

```bash
cd player
git add -A
git commit -m "refactor(player): rename GitHubReleaseChecker → AppUpdateChecker

Le nom legacy GitHubReleaseChecker est trompeur — la classe pointe
vers le webapp admin FuzionTV (/api/v1/updates/latest), pas GitHub.
Renommage de la classe et de la data class GitHubReleaseInfo →
AppUpdateInfo dans tous les call-sites."
```

---

## Task 2: Ajouter la dépendance lifecycle-process

**Why:** `ProcessLifecycleOwner` n'est pas encore dans les deps du module app. Sans elle, on ne peut pas observer les transitions foreground/background du process Android.

**Files:**
- Modify: `player/gradle/libs.versions.toml`
- Modify: `player/app/build.gradle.kts`

- [ ] **Step 1: Ajouter l'alias dans libs.versions.toml**

Dans `player/gradle/libs.versions.toml`, dans la section `[libraries]` à côté des autres `lifecycle-*` (autour de la ligne 95-97), ajouter :

```toml
lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
```

(`version.ref = "lifecycle"` réutilise la version 2.10.0 déjà déclarée.)

- [ ] **Step 2: Ajouter la dépendance dans app/build.gradle.kts**

Dans `player/app/build.gradle.kts`, à côté des autres `lifecycle.*` (ligne ~174-176), ajouter :

```kotlin
implementation(libs.lifecycle.process)
```

- [ ] **Step 3: Sync Gradle**

```bash
cd player
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep lifecycle-process
```

Expected: ligne `androidx.lifecycle:lifecycle-process:2.10.0` présente.

- [ ] **Step 4: Compiler**

```bash
cd player
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd player
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(player): add androidx.lifecycle:lifecycle-process

Required for ProcessLifecycleOwner used by AppStartupCoordinator
to observe app-wide foreground/background transitions."
```

---

## Task 3: SyncSnackbarController (singleton + tests)

**Why:** mécanisme simple pour découpler la logique du coordinator de l'affichage Compose. Le coordinator émet des événements typés, le `AppNavigation` les consomme.

**Files:**
- Create: `player/app/src/main/java/com/streamvault/app/startup/SyncSnackbarController.kt`
- Test: `player/app/src/test/java/com/streamvault/app/startup/SyncSnackbarControllerTest.kt`

- [ ] **Step 1: Créer le test**

Créer `player/app/src/test/java/com/streamvault/app/startup/SyncSnackbarControllerTest.kt` :

```kotlin
package com.streamvault.app.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSnackbarControllerTest {

    @Test
    fun `emits Started Success in order`() = runTest(UnconfinedTestDispatcher()) {
        val controller = SyncSnackbarController()
        val collected = mutableListOf<SyncSnackbarEvent>()
        val job = launch {
            controller.events.toList(collected)
        }
        controller.emit(SyncSnackbarEvent.SyncStarted)
        controller.emit(SyncSnackbarEvent.SyncSuccess)
        job.cancel()

        assertEquals(
            listOf(SyncSnackbarEvent.SyncStarted, SyncSnackbarEvent.SyncSuccess),
            collected
        )
    }

    @Test
    fun `emits Failed with messageRes`() = runTest(UnconfinedTestDispatcher()) {
        val controller = SyncSnackbarController()
        val collected = mutableListOf<SyncSnackbarEvent>()
        val job = launch {
            controller.events.toList(collected)
        }
        controller.emit(SyncSnackbarEvent.SyncFailed(messageRes = 12345))
        job.cancel()

        assertEquals(
            listOf(SyncSnackbarEvent.SyncFailed(messageRes = 12345)),
            collected
        )
    }

    @Test
    fun `late subscriber does not receive prior events`() = runTest(UnconfinedTestDispatcher()) {
        val controller = SyncSnackbarController()
        controller.emit(SyncSnackbarEvent.SyncStarted)

        val collected = mutableListOf<SyncSnackbarEvent>()
        val job = launch {
            controller.events.toList(collected)
        }
        controller.emit(SyncSnackbarEvent.SyncSuccess)
        job.cancel()

        // SharedFlow with replay=0: late collector misses Started.
        assertEquals(listOf(SyncSnackbarEvent.SyncSuccess), collected)
    }
}
```

- [ ] **Step 2: Run test (doit échouer — classe pas encore créée)**

```bash
cd player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.startup.SyncSnackbarControllerTest"
```

Expected: COMPILATION FAILED (classes non trouvées).

- [ ] **Step 3: Implémenter SyncSnackbarController**

Créer `player/app/src/main/java/com/streamvault/app/startup/SyncSnackbarController.kt` :

```kotlin
package com.streamvault.app.startup

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncSnackbarEvent {
    data object SyncStarted : SyncSnackbarEvent()
    data object SyncSuccess : SyncSnackbarEvent()
    data class SyncFailed(@StringRes val messageRes: Int) : SyncSnackbarEvent()
}

@Singleton
class SyncSnackbarController @Inject constructor() {
    private val _events = MutableSharedFlow<SyncSnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val events: SharedFlow<SyncSnackbarEvent> = _events.asSharedFlow()

    suspend fun emit(event: SyncSnackbarEvent) {
        _events.emit(event)
    }
}
```

- [ ] **Step 4: Run test (doit passer)**

```bash
cd player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.startup.SyncSnackbarControllerTest"
```

Expected: 3 tests OK.

- [ ] **Step 5: Commit**

```bash
cd player
git add app/src/main/java/com/streamvault/app/startup/SyncSnackbarController.kt \
        app/src/test/java/com/streamvault/app/startup/SyncSnackbarControllerTest.kt
git commit -m "feat(player): add SyncSnackbarController

Singleton SharedFlow exposant 3 événements typés (Started, Success,
Failed) pour piloter un Snackbar discret pendant la sync auto au
démarrage. replay=0 — un consommateur tardif ne re-rejoue pas les
événements passés."
```

---

## Task 4: AppStartupCoordinator (singleton + tests)

**Why:** cœur de la fonctionnalité. Observe `ProcessLifecycleOwner`, déclenche les deux checks, applique le throttle.

**Files:**
- Create: `player/app/src/main/java/com/streamvault/app/startup/AppStartupCoordinator.kt`
- Test: `player/app/src/test/java/com/streamvault/app/startup/AppStartupCoordinatorTest.kt`

- [ ] **Step 1: Écrire le test (TDD complet — 6 cas)**

Créer `player/app/src/test/java/com/streamvault/app/startup/AppStartupCoordinatorTest.kt` :

```kotlin
package com.streamvault.app.startup

import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.app.update.AppUpdateInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupCoordinatorTest {

    private val now = 1_700_000_000_000L
    private val fortyEightHoursMs = 48L * 60 * 60 * 1000L

    private fun newCoordinator(
        scope: TestScope,
        appUpdateChecker: AppUpdateChecker = mock(),
        preferencesRepository: PreferencesRepository = mock(),
        providerRepository: ProviderRepository = mock(),
        syncManager: SyncManager = mock(),
        snackbar: SyncSnackbarController = SyncSnackbarController(),
        clock: () -> Long = { now }
    ) = AppStartupCoordinator(
        appUpdateChecker = appUpdateChecker,
        preferencesRepository = preferencesRepository,
        providerRepository = providerRepository,
        syncManager = syncManager,
        snackbar = snackbar,
        applicationScope = scope,
        clock = clock
    )

    private fun provider(lastSyncedAt: Long) = Provider(
        id = 1L,
        name = "FuzionTV",
        type = ProviderType.XTREAM_CODES,
        serverUrl = "https://example.test",
        username = "u",
        password = "p",
        lastSyncedAt = lastSyncedAt
    )

    @Test
    fun `onStart triggers update check and sync when stale`() = runTest(StandardTestDispatcher()) {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.success(
                AppUpdateInfo(
                    versionName = "4.2.0",
                    versionCode = 480,
                    releaseUrl = "https://example.test",
                    downloadUrl = "https://example.test/apk",
                    releaseNotes = "notes",
                    publishedAt = null
                )
            )
        }
        val prefs = mock<PreferencesRepository>()
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - fortyEightHoursMs - 1))
        }
        val sync = mock<SyncManager> {
            on { isAnySyncActive() } doReturn false
            onBlocking {
                sync(eq(1L), eq(false), any(), eq(ProviderEpgSyncMode.BACKGROUND), any())
            } doReturn Result.success(Unit)
        }

        val coord = newCoordinator(this, checker, prefs, provRepo, sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(checker).fetchLatestRelease()
        verify(prefs).setCachedAppUpdateRelease(
            versionName = "4.2.0",
            versionCode = 480,
            releaseUrl = "https://example.test",
            downloadUrl = "https://example.test/apk",
            releaseNotes = "notes",
            publishedAt = null
        )
        verify(sync).sync(
            providerId = eq(1L),
            force = eq(false),
            movieFastSyncOverride = any(),
            epgSyncModeOverride = eq(ProviderEpgSyncMode.BACKGROUND),
            onProgress = any()
        )
    }

    @Test
    fun `onStart skips sync when lastSyncedAt is fresh`() = runTest(StandardTestDispatcher()) {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.error("offline")
        }
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - 1000L))
        }
        val sync = mock<SyncManager> { on { isAnySyncActive() } doReturn false }

        val coord = newCoordinator(this, checker, mock(), provRepo, sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(sync, never()).sync(any(), any(), any(), any(), any())
    }

    @Test
    fun `onStart skips sync when no active provider`() = runTest(StandardTestDispatcher()) {
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(null)
        }
        val sync = mock<SyncManager>()

        val coord = newCoordinator(this, providerRepository = provRepo, syncManager = sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(sync, never()).sync(any(), any(), any(), any(), any())
    }

    @Test
    fun `onStart skips sync when sync already active`() = runTest(StandardTestDispatcher()) {
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - fortyEightHoursMs - 1))
        }
        val sync = mock<SyncManager> { on { isAnySyncActive() } doReturn true }

        val coord = newCoordinator(this, providerRepository = provRepo, syncManager = sync)
        coord.onAppStart()
        advanceUntilIdle()

        verify(sync, never()).sync(any(), any(), any(), any(), any())
    }

    @Test
    fun `onStart applies 30s throttle`() = runTest(StandardTestDispatcher()) {
        var clockNow = now
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.error("ignore")
        }
        val coord = newCoordinator(
            scope = this,
            appUpdateChecker = checker,
            clock = { clockNow }
        )

        coord.onAppStart()
        advanceUntilIdle()

        clockNow += 10_000L  // 10s plus tard
        coord.onAppStart()
        advanceUntilIdle()

        // Une seule exécution car le second onAppStart est dans la fenêtre des 30s.
        verify(checker, times(1)).fetchLatestRelease()

        clockNow += 25_000L  // total 35s depuis le premier
        coord.onAppStart()
        advanceUntilIdle()
        verify(checker, times(2)).fetchLatestRelease()
    }

    @Test
    fun `onStart emits Started then Success snackbar events`() = runTest(StandardTestDispatcher()) {
        val provRepo = mock<ProviderRepository> {
            on { getActiveProvider() } doReturn flowOf(provider(now - fortyEightHoursMs - 1))
        }
        val sync = mock<SyncManager> {
            on { isAnySyncActive() } doReturn false
            onBlocking {
                sync(any(), any(), any(), any(), any())
            } doReturn Result.success(Unit)
        }
        val snackbar = SyncSnackbarController()
        val collected = mutableListOf<SyncSnackbarEvent>()
        val collectJob = launch {
            snackbar.events.toList(collected)
        }

        val coord = newCoordinator(
            scope = this,
            providerRepository = provRepo,
            syncManager = sync,
            snackbar = snackbar
        )
        coord.onAppStart()
        advanceUntilIdle()
        collectJob.cancel()

        // Order matters: Started before Success.
        assertEquals(SyncSnackbarEvent.SyncStarted, collected.first())
        assertEquals(SyncSnackbarEvent.SyncSuccess, collected.last())
    }
}
```

> Note : le scope `launch` du dernier test utilise le `TestScope` reçu via `runTest` (le `this` implicite), pas `GlobalScope`. C'est important pour que `runTest` cancel correctement à la fin.

- [ ] **Step 2: Run test (compilation échoue — coordinator absent)**

```bash
cd player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.startup.AppStartupCoordinatorTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 3: Implémenter AppStartupCoordinator**

Créer `player/app/src/main/java/com/streamvault/app/startup/AppStartupCoordinator.kt` :

```kotlin
package com.streamvault.app.startup

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamvault.app.R
import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val FORTY_EIGHT_HOURS_MS = 48L * 60 * 60 * 1000L
private const val THROTTLE_MS = 30_000L

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val appUpdateChecker: AppUpdateChecker,
    private val preferencesRepository: PreferencesRepository,
    private val providerRepository: ProviderRepository,
    private val syncManager: SyncManager,
    private val snackbar: SyncSnackbarController,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DefaultLifecycleObserver {

    @Volatile
    private var lastRunAt = 0L

    override fun onStart(owner: LifecycleOwner) {
        onAppStart()
    }

    fun onAppStart() {
        val now = clock()
        if (now - lastRunAt < THROTTLE_MS) return
        lastRunAt = now

        applicationScope.launch { checkForAppUpdate() }
        applicationScope.launch { checkForStaleSync() }
    }

    private suspend fun checkForAppUpdate() {
        when (val result = appUpdateChecker.fetchLatestRelease()) {
            is Result.Success -> {
                val info = result.data
                preferencesRepository.setCachedAppUpdateRelease(
                    versionName = info.versionName,
                    versionCode = info.versionCode,
                    releaseUrl = info.releaseUrl,
                    downloadUrl = info.downloadUrl,
                    releaseNotes = info.releaseNotes,
                    publishedAt = info.publishedAt
                )
            }
            is Result.Error, Result.Loading -> Unit
        }
    }

    private suspend fun checkForStaleSync() {
        val provider = providerRepository.getActiveProvider().first() ?: return
        if (syncManager.isAnySyncActive()) return
        if (clock() - provider.lastSyncedAt < FORTY_EIGHT_HOURS_MS) return

        snackbar.emit(SyncSnackbarEvent.SyncStarted)
        val result = syncManager.sync(
            providerId = provider.id,
            force = false,
            epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND
        )
        when (result) {
            is Result.Success -> snackbar.emit(SyncSnackbarEvent.SyncSuccess)
            is Result.Error -> snackbar.emit(
                SyncSnackbarEvent.SyncFailed(R.string.startup_sync_failed)
            )
            Result.Loading -> Unit
        }
    }
}
```

- [ ] **Step 4: Run test (doit passer)**

```bash
cd player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.startup.AppStartupCoordinatorTest"
```

Expected: 6 tests OK.

> Note : si le test "throttle" échoue parce que `clock()` n'est pas relu lors du second appel, c'est que le coordinator stockait `now` dans une variable et la réutilisait. Vérifier que chaque `onAppStart()` lit `clock()` à neuf.

- [ ] **Step 5: Commit**

```bash
cd player
git add app/src/main/java/com/streamvault/app/startup/AppStartupCoordinator.kt \
        app/src/test/java/com/streamvault/app/startup/AppStartupCoordinatorTest.kt
git commit -m "feat(player): add AppStartupCoordinator for startup checks

Singleton qui observe ProcessLifecycleOwner et déclenche à chaque
ON_START :
- Un check de MAJ via AppUpdateChecker → met à jour le cache
  préférences → AppUpdateStartupDialog s'affiche via le flow existant.
- Une sync silencieuse via SyncManager.sync() si lastSyncedAt > 48h,
  avec snackbar Started/Success/Failed.

Throttle interne 30s pour éviter le spam sur ON_START rapprochés
(navigations OS internes, dialogs, etc.). Skip propre quand pas de
provider actif ou sync déjà en cours."
```

---

## Task 5: Strings + Snackbar host dans AppNavigation

**Why:** afficher les événements du `SyncSnackbarController` à l'utilisateur.

**Files:**
- Modify: `player/app/src/main/res/values/strings.xml`
- Modify: `player/app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`

- [ ] **Step 1: Ajouter les 3 strings**

Dans `player/app/src/main/res/values/strings.xml`, ajouter (à proximité des autres strings `app_update_*`, vers la ligne 124) :

```xml
<string name="startup_sync_started">Synchronisation de la playlist…</string>
<string name="startup_sync_success">Playlist à jour</string>
<string name="startup_sync_failed">Synchronisation impossible</string>
```

- [ ] **Step 2: Ajouter les imports dans AppNavigation**

Dans `player/app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`, ajouter en haut :

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.streamvault.app.startup.SyncSnackbarEvent
import com.streamvault.app.startup.SyncSnackbarHolderViewModel
```

(`LaunchedEffect`, `hiltViewModel`, `R` sont probablement déjà importés — vérifier et ne pas dupliquer.)

- [ ] **Step 3: Ajouter le SnackbarHost et son LaunchedEffect**

Juste **après** le bloc qui affiche l'`AppUpdateStartupDialog` (ligne 273 actuellement), avant le `NavHost`, ajouter :

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val syncStartedMessage = stringResource(R.string.startup_sync_started)
val syncSuccessMessage = stringResource(R.string.startup_sync_success)
val syncFailedMessage = stringResource(R.string.startup_sync_failed)
val snackbarHolderVm: SyncSnackbarHolderViewModel = hiltViewModel()

LaunchedEffect(snackbarHolderVm) {
    snackbarHolderVm.controller.events.collect { event ->
        val message = when (event) {
            SyncSnackbarEvent.SyncStarted -> syncStartedMessage
            SyncSnackbarEvent.SyncSuccess -> syncSuccessMessage
            is SyncSnackbarEvent.SyncFailed -> syncFailedMessage
        }
        snackbarHostState.showSnackbar(message)
    }
}
```

> **Pourquoi pré-résoudre les strings hors du `collect` :** `stringResource()` est `@Composable` et ne peut pas être appelé dans une lambda `flow.collect`. On résout les 3 strings une fois au niveau du composable, puis on les sélectionne dans le `when`. Le `messageRes` de `SyncFailed` est gardé dans le sealed class pour extension future (granularité d'erreur), mais ici on a une seule string générique, donc on l'ignore.

Puis, en fin de composable, wrapper le `NavHost` dans un `Box` avec un `SnackbarHost` aligné en bas :

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = Routes.ACTIVATION,
        enterTransition = { fadeIn(animationSpec = tween(durationMillis = 200)) },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 180)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) }
    ) {
        // ... bloc complet existant des composable() (ne pas modifier)
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}
```

- [ ] **Step 4: Créer le ViewModel holder pour le controller singleton**

> Pourquoi : `SyncSnackbarController` est `@Singleton`. Pour le récupérer dans un `Composable`, on passe par un `@HiltViewModel` qui l'injecte. C'est le pattern Hilt standard pour exposer un singleton à Compose.

Créer `player/app/src/main/java/com/streamvault/app/startup/SyncSnackbarHolderViewModel.kt` :

```kotlin
package com.streamvault.app.startup

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SyncSnackbarHolderViewModel @Inject constructor(
    val controller: SyncSnackbarController
) : ViewModel()
```

- [ ] **Step 5: Compiler**

```bash
cd player
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all unit tests**

```bash
cd player
./gradlew :app:testDebugUnitTest
```

Expected: tous les tests passent.

- [ ] **Step 7: Commit**

```bash
cd player
git add app/src/main/res/values/strings.xml \
        app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt \
        app/src/main/java/com/streamvault/app/startup/SyncSnackbarHolderViewModel.kt
git commit -m "feat(player): wire SyncSnackbarController to AppNavigation

Affiche un Snackbar Material3 en bas d'écran à chaque événement
émis par AppStartupCoordinator pendant la sync auto au démarrage.
Trois messages : Synchronisation de la playlist… / Playlist à jour
/ Synchronisation impossible.

Le controller (Singleton) est exposé à Compose via un HiltViewModel
holder léger — pattern standard pour pont singleton ↔ composable."
```

---

## Task 6: Étendre HeartbeatWorker pour rafraîchir le cache MAJ

**Why:** belt-and-suspenders. Quand l'app tourne en foreground des heures sans `ON_START`, le worker (15 min) garantit la fraîcheur du cache.

**Files:**
- Modify: `player/app/src/main/java/com/streamvault/app/work/HeartbeatWorker.kt`
- Test: `player/app/src/test/java/com/streamvault/app/work/HeartbeatWorkerUpdateCacheTest.kt`

- [ ] **Step 1: Écrire le test**

Créer `player/app/src/test/java/com/streamvault/app/work/HeartbeatWorkerUpdateCacheTest.kt` :

```kotlin
package com.streamvault.app.work

import com.streamvault.app.update.AppUpdateChecker
import com.streamvault.app.update.AppUpdateInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatWorkerUpdateCacheTest {

    @Test
    fun `refreshAppUpdateCache writes cache on success`() = runTest {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.success(
                AppUpdateInfo(
                    versionName = "4.2.0",
                    versionCode = 480,
                    releaseUrl = "https://example.test",
                    downloadUrl = "https://example.test/apk",
                    releaseNotes = "notes",
                    publishedAt = null
                )
            )
        }
        val prefs = mock<PreferencesRepository>()

        HeartbeatWorker.refreshAppUpdateCache(checker, prefs)

        verify(prefs).setCachedAppUpdateRelease(
            versionName = "4.2.0",
            versionCode = 480,
            releaseUrl = "https://example.test",
            downloadUrl = "https://example.test/apk",
            releaseNotes = "notes",
            publishedAt = null
        )
    }

    @Test
    fun `refreshAppUpdateCache silent on failure`() = runTest {
        val checker = mock<AppUpdateChecker> {
            onBlocking { fetchLatestRelease() } doReturn Result.error("offline")
        }
        val prefs = mock<PreferencesRepository>()

        HeartbeatWorker.refreshAppUpdateCache(checker, prefs)

        // No write expected on error.
        org.mockito.kotlin.verify(prefs, org.mockito.kotlin.never()).setCachedAppUpdateRelease(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
    }
}
```

- [ ] **Step 2: Run test (échec compilation — refreshAppUpdateCache absent)**

```bash
cd player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.work.HeartbeatWorkerUpdateCacheTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 3: Étendre HeartbeatWorker**

Dans `player/app/src/main/java/com/streamvault/app/work/HeartbeatWorker.kt`, ajouter au `EntryPoint` les deux nouvelles dépendances :

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HeartbeatWorkerEntryPoint {
    fun fuziontvDevice(): FuziontvDeviceRepositoryImpl
    fun providerRepository(): ProviderRepository
    fun appUpdateChecker(): com.streamvault.app.update.AppUpdateChecker
    fun preferencesRepository(): com.streamvault.data.preferences.PreferencesRepository
}
```

Puis dans le constructeur de `HeartbeatWorker`, ajouter :

```kotlin
private val appUpdateChecker = entryPoint.appUpdateChecker()
private val preferencesRepository = entryPoint.preferencesRepository()
```

Modifier `doWork()` pour appeler le refresh à chaque exécution (avant le bloc try existant ou en parallèle — version sequentielle simple) :

```kotlin
override suspend fun doWork(): Result {
    refreshAppUpdateCache(appUpdateChecker, preferencesRepository)

    val deviceId = device.deviceId()
    val token = device.deviceToken() ?: return Result.success()
    return try {
        // ... reste inchangé
    }
    // ... reste inchangé
}
```

Et ajouter dans le `companion object` la fonction utilitaire testable :

```kotlin
companion object {
    private const val TAG = "HeartbeatWorker"
    private const val UNIQUE_NAME = "fuziontv_heartbeat"

    suspend fun refreshAppUpdateCache(
        checker: com.streamvault.app.update.AppUpdateChecker,
        prefs: com.streamvault.data.preferences.PreferencesRepository
    ) {
        when (val result = checker.fetchLatestRelease()) {
            is com.streamvault.domain.model.Result.Success -> {
                val info = result.data
                prefs.setCachedAppUpdateRelease(
                    versionName = info.versionName,
                    versionCode = info.versionCode,
                    releaseUrl = info.releaseUrl,
                    downloadUrl = info.downloadUrl,
                    releaseNotes = info.releaseNotes,
                    publishedAt = info.publishedAt
                )
            }
            is com.streamvault.domain.model.Result.Error,
            com.streamvault.domain.model.Result.Loading -> Unit
        }
    }

    fun schedule(context: Context) {
        // ... inchangé
    }
}
```

- [ ] **Step 4: Run test (doit passer)**

```bash
cd player
./gradlew :app:testDebugUnitTest --tests "com.streamvault.app.work.HeartbeatWorkerUpdateCacheTest"
```

Expected: 2 tests OK.

- [ ] **Step 5: Compiler tout**

```bash
cd player
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd player
git add app/src/main/java/com/streamvault/app/work/HeartbeatWorker.kt \
        app/src/test/java/com/streamvault/app/work/HeartbeatWorkerUpdateCacheTest.kt
git commit -m "feat(player): refresh app update cache from HeartbeatWorker

Le worker périodique (15 min) appelle maintenant aussi
AppUpdateChecker.fetchLatestRelease() à chaque exécution et met à
jour le cache préférences. Garantit la fraîcheur du cache MAJ
quand l'app tourne en foreground des heures sans transition
ProcessLifecycle.ON_START.

Echecs réseau silencieux (cache reste figé), comportement
identique à AppStartupCoordinator pour cohérence."
```

---

## Task 7: Brancher AppStartupCoordinator dans StreamVaultApp et nettoyer

**Why:** rendre le coordinator effectivement actif au runtime. Supprimer la logique throttle 24h désormais redondante.

**Files:**
- Modify: `player/app/src/main/java/com/streamvault/app/StreamVaultApp.kt`

- [ ] **Step 1: Modifier StreamVaultApp.onCreate**

Dans `player/app/src/main/java/com/streamvault/app/StreamVaultApp.kt`, ajouter l'import :

```kotlin
import androidx.lifecycle.ProcessLifecycleOwner
import com.streamvault.app.startup.AppStartupCoordinator
```

Ajouter l'injection :

```kotlin
@Inject
lateinit var appStartupCoordinator: AppStartupCoordinator
```

Remplacer le bloc `applicationScope.launch { refreshCachedAppUpdateIfNeeded() }` par :

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(appStartupCoordinator)
```

Supprimer la méthode privée `refreshCachedAppUpdateIfNeeded()` complètement. Supprimer aussi l'injection `appUpdateChecker` (renommé à la Task 1) dans `StreamVaultApp` puisqu'elle n'est plus utilisée directement — le coordinator s'en charge.

Le `onCreate()` final ressemble à :

```kotlin
override fun onCreate() {
    super.onCreate()
    runtimeDiagnosticsManager.start()
    ProcessLifecycleOwner.get().lifecycle.addObserver(appStartupCoordinator)
    applicationScope.launch {
        delay(15_000)
        preloadFullLibraryInBackground()
    }
    // ... reste inchangé (workers, recording, etc.)
}
```

- [ ] **Step 2: Vérifier qu'il ne reste plus de référence à `appUpdateChecker` dans StreamVaultApp**

```bash
cd player
grep -n "appUpdateChecker\|gitHubReleaseChecker\|refreshCachedAppUpdate" app/src/main/java/com/streamvault/app/StreamVaultApp.kt
```

Expected: aucune ligne.

- [ ] **Step 3: Compiler**

```bash
cd player
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Lancer tous les tests**

```bash
cd player
./gradlew :app:testDebugUnitTest
```

Expected: tous les tests passent.

- [ ] **Step 5: Build APK debug**

```bash
cd player
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, APK généré sous `app/build/outputs/apk/debug/`.

- [ ] **Step 6: Commit**

```bash
cd player
git add app/src/main/java/com/streamvault/app/StreamVaultApp.kt
git commit -m "feat(player): wire AppStartupCoordinator at process startup

StreamVaultApp.onCreate() attache désormais le coordinator à
ProcessLifecycleOwner. Suppression de refreshCachedAppUpdateIfNeeded()
et de son throttle 24h désormais redondants — le coordinator
s'exécute à chaque ON_START avec son propre throttle de 30s,
beaucoup plus réactif pour détecter une nouvelle release."
```

---

## Task 8: Validation manuelle sur la box S905X5M

**Why:** ce projet a une cible matérielle spécifique (Amlogic S905X5M, 2 GB RAM). Les tests unitaires ne valident ni l'intégration runtime ni l'expérience utilisateur réelle.

**Files:** aucun (test manuel uniquement).

- [ ] **Step 1: Installer le debug APK sur la box**

```bash
cd player
./gradlew :app:installDebug
```

> Le keystore release signe aussi le debug build (`keystore.properties` présent), donc le sideload écrase l'install actuelle sans wiper les playlists/préférences. Cf. `CLAUDE.md`.

- [ ] **Step 2: Test scénario "popup MAJ au cold start"**

Pré-requis : avoir une release publiée dans le webapp admin avec `versionCode > BuildConfig.VERSION_CODE` actuel de la build installée. Si non, en publier une via l'interface admin ou directement en DB.

Procédure :
1. Force-stop l'app : `adb shell am force-stop com.streamvault.app`
2. Lancer l'app : `adb shell monkey -p com.streamvault.app -c android.intent.category.LAUNCHER 1`
3. Observer : popup `AppUpdateStartupDialog` doit apparaître après l'écran d'accueil dans les 2-3 s.

Expected: popup visible avec `versionName` correct et release notes.

- [ ] **Step 3: Test scénario "popup MAJ au retour foreground"**

1. App ouverte sur l'écran d'accueil.
2. Appuyer sur Home : `adb shell input keyevent KEYCODE_HOME`
3. Attendre 5s, publier une nouvelle release dans l'admin.
4. Revenir sur l'app : `adb shell am start -n com.streamvault.app/.MainActivity`
5. Observer : popup apparaît dans les 2-3 s.

Expected: popup visible.

- [ ] **Step 4: Test scénario "sync 48h au démarrage"**

Forcer artificiellement `lastSyncedAt` à plus de 48h. Via le shell ADB sur un debug build :

```bash
adb shell "run-as com.streamvault.app sqlite3 /data/data/com.streamvault.app/databases/streamvault.db \
  \"UPDATE providers SET last_synced_at = (strftime('%s','now') - 200000) * 1000 WHERE is_active = 1;\""
```

(200_000 s ≈ 55h, supérieur à 48h.)

Puis :
1. Force-stop et relancer l'app.
2. Observer : un Snackbar "Synchronisation de la playlist…" apparaît en bas de l'écran d'accueil.
3. Attendre la fin de la sync (1-3 min selon le catalogue).
4. Observer : Snackbar "Playlist à jour" apparaît.
5. L'app reste utilisable pendant toute la sync (zapper, ouvrir un live, etc.).

Expected: les deux snackbars apparaissent dans l'ordre, l'app n'est jamais bloquée.

- [ ] **Step 5: Test scénario "offline silent"**

1. Désactiver le wifi/ethernet de la box : `adb shell svc wifi disable && adb shell svc data disable`
2. Force-stop et relancer l'app.
3. Observer : aucun popup, aucun snackbar de sync. L'app fonctionne sur cache local.

Expected: aucune notification visuelle d'erreur, app utilisable.

- [ ] **Step 6: Test scénario "throttle 30s"**

1. App ouverte. Network up.
2. Logcat filtré : `adb logcat -s AppStartupCoordinator:V`
3. Mettre en background, revenir foreground 3 fois en moins de 30 s.
4. Observer logcat / monitoring réseau (ex: `adb shell dumpsys netstats`).

Expected: une seule série de requêtes `/api/v1/updates/latest` au premier `ON_START`, pas de re-déclenchement sur les suivants dans la fenêtre.

> **Note** : si `AppStartupCoordinator` ne log rien actuellement, c'est OK — vérifier via les logs du backend Express (`/api/v1/updates/latest` côté webapp) qui montrent l'IP de la box. Une seule ligne de log doit apparaître.

- [ ] **Step 7: Capture screenshot du Snackbar**

```bash
adb exec-out screencap -p > /tmp/sync-snackbar.png
```

Vérifier visuellement la lisibilité du Snackbar sur la TV (texte, position, durée d'affichage).

- [ ] **Step 8: Si tout OK, marquer le ticket comme prêt à merger**

Pas de commit nécessaire (validation manuelle).

---

## Self-review checklist

Avant de fermer le plan, vérifier :

- [ ] **Spec coverage** : chaque section du spec (Architecture, Décisions, Edge cases, Testing) a une tâche correspondante.
  - Renommage → Task 1 ✓
  - lifecycle-process dependency → Task 2 ✓
  - SyncSnackbarController → Task 3 ✓
  - AppStartupCoordinator + 6 cas tests → Task 4 ✓
  - Strings + Snackbar host UI → Task 5 ✓
  - HeartbeatWorker étendu → Task 6 ✓
  - Branchement StreamVaultApp + cleanup `refreshCachedAppUpdateIfNeeded` → Task 7 ✓
  - Validation manuelle sur S905X5M → Task 8 ✓
- [ ] **No placeholders** : aucun TBD/TODO/"appropriate error handling".
- [ ] **Type consistency** : `AppUpdateChecker`, `AppUpdateInfo`, `SyncSnackbarController`, `SyncSnackbarEvent`, `AppStartupCoordinator` cohérents partout.
- [ ] **Tous les paths absolus + précis** ✓
- [ ] **Code complet dans chaque step** ✓
- [ ] **Commits TDD-friendly** : chaque task se termine par un commit isolé.

---

## Notes pour l'exécutant

- **Toujours rebuild Docker du webapp ?** Non, ce plan ne touche que le module Android `player/`. Le webapp est déjà capable de servir `/api/v1/updates/latest`.
- **versionCode à incrémenter ?** Pas pour ces commits (ce sont des changements internes). À incrémenter dans `app/build.gradle.kts` au moment du release upload, comme d'habitude.
- **`adb` sur la box** : vérifier que ADB-over-network est activé (`adb connect <ip>:5555`) ou utiliser un câble USB OTG.
- **Risque de régression** : la suppression de `refreshCachedAppUpdateIfNeeded` à la Task 7 supprime aussi la lecture de `lastAppUpdateCheckTimestamp`. La clé reste en DataStore (pas de migration). Aucun appelant restant — vérifié à la Task 7 step 2.
- **Si le test "throttle" en Task 4 step 4 est flaky** : `runTest(StandardTestDispatcher())` peut sérialiser les coroutines de manière inattendue. Un fallback est `UnconfinedTestDispatcher()` mais il rend le test plus difficile à raisonner. Préférer `StandardTestDispatcher` avec `advanceUntilIdle()` après chaque `onAppStart()`.
