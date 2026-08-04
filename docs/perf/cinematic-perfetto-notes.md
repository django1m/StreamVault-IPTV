# Cinematic UI — performance notes

## État après refonte Cinematic (2026-04-22)

La refonte "Cinematic Premium" a ajouté :

- **Design system étendu** : `AppGlass` (glass levels + profile low-end), `AppElevation` (shadows with fallback), `AmbientColor` (palette extraction with LRU 32), `AppMotion` (springs), `AppHeroStyles`.
- **Composants signatures** : `HeroBackdrop`, `AmbientBackground`, `HeroCarousel`, `GlassSurface`, `CinematicPosterCard`, `CinematicChip`, `SpringButton`, `ShimmerSkeleton`, `FocusGlow`, `ParallaxContainer`, `CinematicSectionHeader`, `CoilPrefetch`.
- **Shell modernisé** : top bar glass, rail avec focus glow + scale 1.08 spring, transitions NavHost fade-through 220 ms.
- **Ambient backdrop** injecté sur : Dashboard, Home (Live TV), Movies, Series, EPG, Search, Settings, Favorites, Parental.
- **Dashboard** : `HeroCarousel` auto-advance 8s avec extraction de palette ambient derrière.
- **Player** : nouveaux composants `SkipIntroButton`, `NowPlayingChip`, `PlayerOptionsPanel`, `MiniGuideFloating` — disponibles, à wirer dans PlayerScreen / PlayerControlsChrome dans une itération ciblée.

## Fondations perf appliquées

- **Compose stability config file** (`compose_compiler_config.conf`) : domain models, kotlin collections, java.time, android.net.Uri marqués stables — évite la sur-recomposition des rangs `LazyRow` qui consomment `Channel` / `Movie` / `Series`.
- **Coil disk cache** porté de 100 MB → 200 MB (catalogues plus grands sans éviction).
- **Coil memory cache** conservé à 15 % RAM (safe Fire TV Lite).
- **GlassProfile** détecte `ActivityManager.isLowRamDevice`, `Build.MANUFACTURER == "Amazon"`, RAM < 2 Go → bascule en mode sans blur / sans halo / sans shadow automatiquement.

## À faire en Phase 10 pour boucler la fluidité

### 1. Baseline profile (impact startup le plus fort)

Non généré automatiquement ici — exige un device / émulateur connecté. Étapes :

```bash
# 1. Créer module benchmark :baseline-profile (Macrobenchmark generator)
#    — génère app/src/main/baseline-prof.txt
# 2. Sur un Pixel Android 13+ (plus rapide à tourner qu'un Fire TV Lite) :
./gradlew :baseline-profile:connectedCheck
cp baseline-profile/build/outputs/managed_device_android_test_additional_output/*/BaselineProfileGenerator_startup-baseline-prof.txt \
   app/src/main/baseline-prof.txt
# 3. Ajouter au release build :
#    baselineProfile { mergeIntoMain = true } dans app/build.gradle.kts
# 4. Vérifier avec Macrobenchmark :
#    Partial(BaselineProfileMode.Require) vs None → attendre −20 % cold start
```

Parcours à scripter dans le generator :
1. Cold start → Dashboard
2. Scroll 2 rangs du LazyColumn Dashboard
3. Open Movies → scroll rang
4. Open MovieDetail
5. Open Player → wait 3s → back

### 2. Trace Perfetto sur Fire TV Lite

```bash
adb shell am start -n com.streamvault.app/.MainActivity
# Déclencher capture Perfetto (via Android Studio Profiler ou script perfetto --txt)
# Cibles : p95 frame duration < 18 ms sur Dashboard/Home/Player.
```

À mesurer :
- Dashboard cold + scroll 60 items → p95 < 18 ms attendu.
- EPG scroll horizontal 200 chaînes × 24h → p95 < 22 ms attendu (dégradation acceptable).
- Heap après 20 min usage → stable < 180 MB.

### 3. EPG — virtualisation par buckets 30 min (optionnel)

L'ambient background est déjà ajouté. La restructuration du `horizontalScroll` → `LazyRow` bucketisé (de l'ordre de 3 buckets visibles simultanés) est un chantier distinct qui peut attendre un retour utilisateur sur Fire TV Lite réel. Garder l'existant tant que la fluidité est acceptable.

### 4. Player — wiring des nouveaux composants

Les composants créés dans `ui/screens/player/overlay/components/` sont prêts mais ne sont pas encore référencés depuis `PlayerControlsChrome.kt` / `PlayerOverlays.kt`. Étape à planifier :

- `SkipIntroButton` : brancher sur `currentPositionMs in 60_000..120_000` (prédicate simple) ou sur `introStartMs`/`introEndMs` si disponibles pour l'épisode courant.
- `NowPlayingChip` : afficher au `onPlay` avec 3 s de visibilité.
- `PlayerOptionsPanel` : piloté par un state booléen `isOptionsPanelOpen` à ouvrir via un hold-OK ≥ 700 ms (parser `KeyEvent.ACTION_DOWN` / `ACTION_UP` dans `onPreviewKeyEvent`).
- `MiniGuideFloating` : peut remplacer l'ancien EPG overlay à droite, lecture des `programsAhead` depuis le ViewModel existant.

### 5. Wiring optionnel

- Ajouter `CoilPrefetchRow` sur les rangs critiques de Dashboard / Movies / Series.
- Couper `GlassLevel` → `Low` sur les dialogs pendant lecture player (vérifier absence de blur GPU avec vidéo en fond).

## Mesures initiales

À capturer avant / après la refonte sur Fire TV Lite réel (non disponible dans cette session).

| Parcours | Avant (estim.) | Après (attendu) |
|---|---|---|
| Cold start → Dashboard | ~3.5 s | ~2.5 s (après baseline profile) |
| Scroll Dashboard p95 | ~22 ms | < 18 ms |
| EPG scroll horizontal | jank visible | p95 < 22 ms |
| Heap après 20 min | ~140 MB | < 180 MB |

À reprendre quand un device Fire TV Lite est branché.
