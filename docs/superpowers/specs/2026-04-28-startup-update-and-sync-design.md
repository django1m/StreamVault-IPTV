# Vérification de MAJ et sync auto au démarrage

**Date** : 2026-04-28
**Cible** : `player/` (Android TV app, package `com.streamvault.app`)
**Backend** : `webapp/` (FuzionTV admin, `https://admin.fuziontv.app`)

## Contexte

Sur la box Amlogic S905X5M (2 GB RAM, cible principale), l'app reste rarement en cold start — l'utilisateur la met en background plutôt qu'il ne la kill. Aujourd'hui :

- Le check de mise à jour appelle bien le webapp admin (`/api/v1/updates/latest` via `GitHubReleaseChecker`, nom legacy), **mais avec un throttle de 24 h** dans `StreamVaultApp.refreshCachedAppUpdateIfNeeded`. Si une release est publiée dans la journée, l'utilisateur ne la voit pas avant que les 24 h s'écoulent.
- La sync de playlist n'est **jamais déclenchée automatiquement** à l'ouverture. Seul un `forceSync` poussé par l'admin via `HeartbeatWorker` déclenche une re-sync, sinon la playlist peut stagner indéfiniment.
- Le popup `AppUpdateStartupDialog` est déjà câblé dans `AppNavigation.kt` et s'affiche dès que le cache MAJ contient une version supérieure à `BuildConfig.VERSION_CODE`. Le mécanisme d'affichage est correct, c'est le **rafraîchissement du cache** qui est trop lent.

## Objectifs

1. **MAJ** : à chaque cold start ET à chaque retour foreground, rafraîchir le cache MAJ (sans throttle 24 h) pour que le popup apparaisse immédiatement après une publication serveur.
2. **Sync 48 h** : à chaque cold start ET à chaque retour foreground, si la dernière sync du provider actif date de plus de 48 h, déclencher `SyncManager.sync(providerId, force = false, epgSyncModeOverride = BACKGROUND)` (Live + VOD + Series + EPG, EPG en arrière-plan), avec retour visuel discret (snackbar début / fin / erreur). On n'utilise **pas** `loginXtream` (réservé au cas où les credentials ont changé côté admin, ce qui n'est pas notre cas — voir section "Décisions").
3. **Renommage** : `GitHubReleaseChecker` → `AppUpdateChecker`, `GitHubReleaseInfo` → `AppUpdateInfo`. Le nom actuel est un legacy de l'origine StreamVault et a déjà induit en erreur.

## Hors scope

- Pas de check de MAJ ou de sync sur les écrans `ACTIVATION` / `WELCOME` (déjà géré par `isBlockedForUpdate` dans `AppNavigation`).
- Pas de modification des données mises en cache (la couche Room et les repositories `loginXtream` ne changent pas).
- Pas de migration Room nécessaire — `Provider.lastSyncedAt` existe déjà et est mis à jour par `SyncManager.sync(...)` à `providerDao.updateSyncTime(providerId, System.currentTimeMillis())`.
- Pas de `BroadcastReceiver BOOT_COMPLETED` — le cold start après reboot box passe déjà par `ON_START`.

## Architecture

### Composants

| Composant | Statut | Rôle |
|---|---|---|
| `app/update/AppUpdateChecker` | renommage de `GitHubReleaseChecker` | Appelle `${FUZIONTV_BASE_URL}/api/v1/updates/latest`, retourne `AppUpdateInfo` |
| `app/update/AppUpdateInfo` | renommage de `GitHubReleaseInfo` | Data class du résultat |
| `app/startup/AppStartupCoordinator` | **nouveau** `@Singleton` | Observe `ProcessLifecycleOwner.get().lifecycle`, orchestre les deux checks à chaque `ON_START` |
| `app/startup/SyncSnackbarController` | **nouveau** `@Singleton` | Expose un `SharedFlow<SyncSnackbarEvent>` consommé par `AppNavigation` (compose) |
| `app/work/HeartbeatWorker` | étendu | En plus du heartbeat, met à jour le cache MAJ via `AppUpdateChecker` à chaque exécution périodique (15 min) |
| `app/StreamVaultApp` | simplifié | Suppression de `refreshCachedAppUpdateIfNeeded` ; ajout de `ProcessLifecycleOwner.get().lifecycle.addObserver(appStartupCoordinator)` dans `onCreate()` |
| `app/ui/screens/update/AppUpdateStartupViewModel` | inchangé | Détecte le delta cache vs `BuildConfig.VERSION_CODE` |
| `app/ui/screens/update/AppUpdateStartupDialog` | inchangé | Popup déjà câblé dans `AppNavigation.kt:267-273` |
| `app/navigation/AppNavigation` | étendu | Collecte `SyncSnackbarController.events` et affiche un `Snackbar` (Compose tv-material3 ou équivalent) |
| `app/ui/screens/settings/SettingsAppUpdateActions` | mis à jour | Refs renommés |
| `app/ui/screens/settings/SettingsViewModel` | mis à jour | Refs renommés |
| `app/ui/screens/dashboard/DashboardViewModel` | mis à jour | Refs renommés (uniquement l'import / le type — pas de logique modifiée) |

### Data flow

À chaque transition `ProcessLifecycleOwner.ON_START` (cold start ou retour foreground après que toutes les Activities soient passées en background) :

```
AppStartupCoordinator.onStart()
  ├─ throttle (skip si dernière exécution < 30 s)
  │
  ├─ launch updateCheck:
  │     try { AppUpdateChecker.fetchLatestRelease() }
  │     on Success → PreferencesRepository.setCachedAppUpdateRelease(...)
  │                 → AppUpdateStartupViewModel.pendingUpdate flow → dialog s'affiche
  │     on Error   → silencieux, le cache reste intact
  │
  └─ launch syncCheck:
        val provider = providerRepository.getActiveProvider().first() ?: return
        if (syncManager.isAnySyncActive()) return
        if (System.currentTimeMillis() - provider.lastSyncedAt < 48h) return
        snackbar.emit(Started)
        val result = syncManager.sync(
            providerId = provider.id,
            force = false,
            epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND
        )
        when (result) {
            is Result.Success -> snackbar.emit(Success)
            is Result.Error   -> snackbar.emit(Failed(R.string.startup_sync_failed))
            is Result.Loading -> Unit
        }
        // SyncManager.sync() met déjà à jour `lastSyncedAt` via providerDao.updateSyncTime
        // en cas de Success ou Partial — pas besoin de l'écrire nous-mêmes.
```

`HeartbeatWorker` (15 min, déjà existant) appelle en parallèle `AppUpdateChecker.fetchLatestRelease()` et écrit aussi dans le cache. Quand l'app tourne en foreground pendant des heures, le cache MAJ reste frais sans dépendre d'une transition lifecycle.

### Branchement DI

```kotlin
// di module — pas de nouveau module nécessaire, ajouts dans les modules existants
@Provides @Singleton
fun provideAppStartupCoordinator(
    appUpdateChecker: AppUpdateChecker,
    preferencesRepository: PreferencesRepository,
    providerRepository: ProviderRepository,
    syncManager: SyncManager,
    syncSnackbar: SyncSnackbarController
): AppStartupCoordinator = AppStartupCoordinator(...)
```

`StreamVaultApp.onCreate()` :

```kotlin
@Inject lateinit var appStartupCoordinator: AppStartupCoordinator
// ...
override fun onCreate() {
    super.onCreate()
    ProcessLifecycleOwner.get().lifecycle.addObserver(appStartupCoordinator)
    // ... reste inchangé sauf retrait de refreshCachedAppUpdateIfNeeded
}
```

### Snackbar

`SyncSnackbarController` expose :

```kotlin
sealed class SyncSnackbarEvent {
    data object SyncStarted : SyncSnackbarEvent()
    data object SyncSuccess : SyncSnackbarEvent()
    data class SyncFailed(val messageRes: Int) : SyncSnackbarEvent()
}

@Singleton
class SyncSnackbarController @Inject constructor() {
    private val _events = MutableSharedFlow<SyncSnackbarEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SyncSnackbarEvent> = _events.asSharedFlow()
    suspend fun emit(event: SyncSnackbarEvent) { _events.emit(event) }
}
```

`AppNavigation` collecte et affiche via un `SnackbarHost` Compose existant ou un `LaunchedEffect` qui pilote un état local.

Strings :
- `R.string.startup_sync_started` = "Synchronisation de la playlist…"
- `R.string.startup_sync_success` = "Playlist à jour"
- `R.string.startup_sync_failed`  = "Synchronisation impossible"

## Décisions

### Pourquoi `SyncManager.sync(...)` et pas `loginXtream(...)` ?

`HeartbeatWorker` utilise `loginXtream(...)` quand le serveur retourne `forceSync = true` parce que dans ce cas l'admin **peut avoir changé les credentials Xtream** côté webapp (`/api/v1/device/credentials`). Le worker re-fetch les credentials puis appelle `loginXtream` pour ré-authentifier et resync.

Pour la sync 48 h auto côté client, **les credentials sont identiques** — on a juste besoin de rafraîchir les catalogues. Appeler `loginXtream` ferait :
- Un round-trip d'authentification Xtream supplémentaire (inutile).
- Un `providerDao.update(...)` qui réécrit le row avec `lastSyncedAt = 0` (cf. `ProviderRepositoryImpl.kt:134`).
- Pas de vraie valeur ajoutée vs `SyncManager.sync(...)`.

`SyncManager.sync(providerId, force = false, epgSyncModeOverride = BACKGROUND)` est l'appel propre pour ce cas : il fait exactement Live + VOD + Series + EPG (BACKGROUND), met à jour `lastSyncedAt` à la fin, et c'est ce qu'utilise déjà l'UI manuelle "Sync now" du settings.

### Pourquoi `force = false` ?

Le paramètre `force` du `SyncManager` bypass certains TTL internes (catégories, EPG par source). Pour une sync 48 h on n'a pas besoin de forcer — le `SyncManager` gère ses propres TTL et fera ce qu'il faut. Forcer ralentirait inutilement sur la box low-end.

## Edge cases

| Cas | Comportement |
|---|---|
| Pas de provider actif (utilisateur en activation) | `getActiveProvider().first()` retourne `null` → skip silencieux. Le popup MAJ est déjà bloqué sur les routes `ACTIVATION` / `WELCOME` par `AppNavigation.kt:266`. |
| Réseau offline | `IOException` capturée dans les deux coroutines. Pas de toast, pas de retry — la prochaine `ON_START` avec réseau réessaiera. |
| Sync déjà en cours (manuelle ou `forceSync` admin) | `SyncManager.isAnySyncActive()` → skip la sync 48 h. |
| `lastSyncedAt = 0L` (provider fraîchement créé jamais sync) | `(now - 0L) >= 48h` est trivialement vrai → sync immédiate. Comportement souhaité. |
| Multi-providers | Seul le provider actif est sync. Les autres restent figés (comportement actuel inchangé). |
| Dismiss MAJ version N puis serveur publie N+1 | Le ViewModel compare `dismissedVersionCode` à la nouvelle `versionCode` (`AppUpdateStartupViewModel.kt:62`), le popup réapparaît. Logique existante. |
| `ON_START` rapproché (drawer pop, dialog) | Throttle interne du coordinator : `lastRunAt` privé, skip si `now - lastRunAt < 30_000L`. Évite le spam quand l'OS génère plusieurs callbacks. |
| Reboot box | Cold start déclenche `ON_START` → comportement standard, pas de receiver dédié. |
| Token device révoqué (admin disable) | `loginXtream` lève une exception. Snackbar `SyncFailed` affichée. L'app reste utilisable avec le cache Room existant. |
| User force-stop l'app puis ré-ouvre | Cold start = `ON_START` → tous les checks s'exécutent. |

## Testing

### Unit tests

- `AppStartupCoordinatorTest` (JVM, mock + coroutines test) :
  - Vérifie que `onStart` appelle `appUpdateChecker.fetchLatestRelease()` à chaque déclenchement non throttlé.
  - Vérifie que `loginXtream` est appelé uniquement quand `provider.lastSyncedAt < now - 48h`.
  - Vérifie le throttle 30 s : deux `onStart` rapprochés ne déclenchent qu'une exécution.
  - Vérifie le skip si `isAnySyncActive() == true`.
  - Vérifie le skip si `getActiveProvider() == null`.
  - Vérifie que `SyncSnackbarEvent.Started` puis `Success` (ou `Failed`) sont émis dans le bon ordre.

- `HeartbeatWorkerTest` (étendu) :
  - Vérifie que `appUpdateChecker.fetchLatestRelease()` est appelé même si `forceSync == false`.
  - Vérifie que le cache MAJ est mis à jour en cas de succès.

### Tests instrumentés

Pas de nouveau test instrumenté requis — le popup et le `SyncManager` sont déjà testés indirectement par le code existant qu'on n'a pas modifié.

### Validation manuelle (obligatoire avant merge)

Sur la box Amlogic S905X5M 2 GB :

1. Publier une release dans le webapp admin → ouvrir l'app cold start → popup apparaît immédiatement.
2. Mettre l'app en background, publier une release, revenir foreground → popup apparaît au retour.
3. Forcer `lastSyncedAt = now - 49h` via debug build (ou directement en SQL via `adb shell run-as`) → reopen → snackbar "Synchronisation…" puis "Playlist à jour" sans bloquer l'UI.
4. Couper le réseau → reopen → ni popup ni sync, app utilisable normalement.
5. Reboot box, ouvrir l'app → tous les checks s'exécutent une seule fois.

## Risques & mitigations

- **Drainage batterie / data** : `ON_START` est un événement rare en pratique (la box reste rarement en standby continu). Une requête HTTP ~5 KB pour la MAJ est négligeable. La sync 48 h se déclenche au pire 1×/2 jours.
- **Doublon avec HeartbeatWorker** : volontaire (defense in depth). Le worker garantit la fraîcheur du cache MAJ pendant que l'app tourne en foreground des heures ; le coordinator garantit la détection immédiate au moment où l'utilisateur revient. Pas de coût mesurable.
- **Race condition cache MAJ** : les deux écrivains (worker + coordinator) écrivent dans le DataStore. Les opérations DataStore sont sérialisées, donc safe. Le dernier écrivain gagne, ce qui est OK puisque les deux sources de vérité sont identiques (`/api/v1/updates/latest`).
- **Sync qui bloque longtemps** : `loginXtream` peut prendre 1-3 min sur un gros catalogue. La snackbar reste affichée. Pas de timeout artificiel — on fait confiance au comportement actuel du `SyncManager` (déjà éprouvé via `forceSync`).

## Plan de migration / déploiement

- Pas de migration utilisateur. Préférences existantes (`cachedAppUpdate*`, `dismissedUpdateVersionCode`, `appUpdateDownloadId`, `lastAppUpdateCheckTimestamp`) conservées.
- `lastAppUpdateCheckTimestamp` n'est plus lu après cette PR. On laisse la clé en place (pas de nettoyage).
- `versionCode` à incrémenter dans `app/build.gradle.kts` avant release.
- Test sur la box S905X5M avec une release de bas-version dans le manager pour valider le popup.

## Références

- Endpoint serveur : `webapp/src/routes/api/updates.js` — `/api/v1/updates/latest`
- Popup existant : `player/app/.../ui/screens/update/AppUpdateStartupDialog.kt`
- ViewModel popup : `player/app/.../ui/screens/update/AppUpdateStartupViewModel.kt`
- Worker heartbeat : `player/app/.../work/HeartbeatWorker.kt`
- Sync entry point : `player/data/.../sync/SyncManager.kt:319` (`sync(providerId, force, ...)`)
- Provider repo : `player/data/.../repository/ProviderRepositoryImpl.kt:87` (`loginXtream(...)`)
- Cache MAJ existant : `player/data/.../preferences/PreferencesRepository.kt:382-440`
