# Changelog

All notable product changes are recorded in this document.

## 1.3.37 (538) - 13/09/2026

Stable release of the 1.3.37 cycle, same code as 1.3.37-beta13: the OSD redesign of the player and menus shipped in the TV betas 525 → 537, and every fix and change listed under the 1.3.37 betas below. First stable since 1.3.36 (524).

## 1.3.37-beta13 (537) - 13/09/2026

### Fixed
- Fixed the focused item being invisible in lists. The series episode list had no focus cue at all — the row card painted its own opaque background over the surface's focused colour — and elsewhere focus came down to a shift between two navy tints. A 2 dp white outline now marks the focused item in the episode list, favorites, the in-player guide, track selection and the resume prompt; the archive dialog's brand-blue outline turns white.
- Fixed the resume prompt swallowing the arrows. LEFT on "Resume" sought backward in the video and raised the control bar, which took focus away from the prompt, instead of moving to "Start over". The player now leaves the arrows and OK to the resume prompt and to the options panel while either is on screen.
- D-pad seeking no longer hands focus to the control bar: the first LEFT/RIGHT seeks and brings the bar up for its timecode, every following press seeks too, and OK, UP or DOWN step into the bar. Already in 536, missing from its notes.

### Changed
- Films and series follow the provider's own order in the library instead of alphabetical (rank per category, Room migrations 34 and 35); "Newest" sorts films on the provider's added date. The order shows up after the next catalog sync, which rewrites every film and series row once.

## 1.3.37-beta12 (536) - 07/09/2026

### Fixed
- Fixed the new blackhole detector turning a flaky link into rotations. A box whose Wi-Fi drops for 60-90 s every minute or two fails to start channels for the same reason a blackholed exit does — no bytes — so the detector blamed the exit and rotated it, tearing the tunnel down again and feeding the problem it thought it saw (observed on a Formuler Z Mini, twice in one evening, the second time on a region whose every server measured healthy). Two guards now separate the two cases: the box probes the backend through the tunnel before blaming an exit (a 403 needs no such check — the provider had to be reached to refuse), and a tunnel reconnect clears blackhole evidence, since it was gathered against a session that no longer exists. On-demand 403 evidence deliberately survives a reconnect: that one is the provider's decision about the exit, not an absence of bytes.

### Changed
- Live playback keeps a much larger buffer reserve (30 s on low-end boxes, 45 s mid, 60 s high, up from 10/12/15 s), capped at 24 MB on 2 GB boxes so a very-high-bitrate channel cannot blow the native heap. Only the ceiling changed — start-up and rebuffer thresholds are untouched, so zapping is exactly as fast as before; what changes is how long a network hole the picture can ride through.
- The player now holds a network wake lock during playback (`setWakeMode`), so the Wi-Fi chip cannot enter power-save mid-stream.

## 1.3.37-beta11 (535) - 06/09/2026

### Fixed
- Fixed a box sitting forever on a black screen when its VPN exit IP is blackholed by the provider's live edges. The panel accepts the stream (`200 video/mp2t`) and the origin then sends nothing, so no error is ever raised and none of the VPN supervisor's health signals — which all key on a 403 — could fire; the box stayed stuck until an admin moved it by hand (observed 05/09/2026, exit `191.96.168.95` on `amsterdam426`: live dead, VOD fine, since the two are served by different edges). A live channel that the panel accepted and that never shows a first frame before its watchdog fires is now reported to the supervisor, and TWO DISTINCT such channels — any successful playback in between clears the evidence — rotate the exit IP within the same region, under the existing budget (≤3 per 6 h, ≥5 min apart). Detection sits in the watchdogs on purpose rather than in a pre-playback body probe: lines are max=1 and a second media request steals the connection slot from the playback that follows it.
- Egress rotations are now reported as a device diagnostic, so the panel shows which box changed exit IP and on what evidence.

## 1.3.37-beta2 (526) - 27/08/2026

### Fixed
- Fixed multi-word search returning nothing: "jason bourne" found no result while "jason" alone found the film. FTS queries joined their tokens with the literal `AND` keyword, but Android's system SQLite is built without `SQLITE_ENABLE_FTS3_PARENTHESIS`, so FTS3/FTS4 falls back to the standard query syntax where "AND" is a search term rather than an operator — every multi-word search silently demanded a title containing the word "and". Tokens are now separated by a space, which means implicit AND in both syntaxes.
- Fixed accented titles being unreachable: the FTS index used the `simple` tokenizer, which folds ASCII case only, so "L'Été" was indexed under the term `Été` and neither "été" nor "ete" matched it. The three indexes now use `unicode61` (migration 31 → 32 rebuilds them on first launch), which folds case and diacritics on both the index and the query side.
- Fixed searches broken by punctuation: the query was split on whitespace only, so "l'été" looked for the term `lété`, and titles made of initials ("9-1-1", "S.W.A.T.") searched for nothing at all. The query is now split exactly the way the FTS tokenizer splits the indexed titles.

### Changed
- Search falls back to a permissive match when no title contains every word typed ("matrix reloaded vf"): the closest partial matches are shown, ranked by how many words they match, instead of an empty screen.
- Search ranking folds accents like the index does, and SQLite now orders candidates by title length before the 300-row cut, so a broad query no longer truncates the relevant title out of the window.

## 1.3.37-beta1 (mobile 100535) - 17/08/2026

### Fixed
- Fixed a box being stranded forever on the activation screen when its VPN exit IP is banned by the provider. A 403 on the panel login now reports the burned exit to the VPN supervisor, which asks the server to rotate the exit IP within the same region (bounded: ≤3 per 6 h, ≥5 min apart). Until now every 403 health signal came from playback, which a device that never gets past activation can never produce.
- Fixed the activation screen freezing on a failure that could recover on its own: only network errors retried automatically, so a panel 403 left the box waiting for a human to press "Réessayer". Server and unknown failures now retry on a 1 min → 2 min → 5 min backoff (spaced out on purpose: the panel rate-limits to roughly one request per IP, and a tight loop would re-burn the freshly rotated exit). Failures that need a human — expired subscription, device to re-pair — still stop and say so.

## 1.3.31 (519) - 04/07/2026

### Fixed
- Fixed the preview→fullscreen handoff parking an ERRORed preview engine: the fullscreen player adopted it with `skipEnginePrepare`, the replayed error was dropped as pre-session, and no automatic recovery ran. An ERRORed preview now falls back to a regular full prepare.
- Fixed advisory-mode demotion under panel rate limiting: a 403 on the announced extension stays advisory (engine still gets its real attempt) even when the back-to-back alternate probes get false 404/5xx answers, and the reported HTTP code is now the initial one.

## 1.3.30 (518) - 04/07/2026

### Fixed
- Fixed an app crash when re-entering the player screen after a playback failure: the singleton engines' error flow (`replay = 1`) replayed the previous session's error into the new `PlayerViewModel` during construction, before its fields were initialized (NPE in `tryAlternateEngineInternal`). Fixed in three layers: pre-session error guard in `handlePlaybackError`, replay-cache purge in all three engines' `release()`, and field declaration order.
- Fixed silent pre-playback probe failures: Xtream stream probe rejections (401/403/404/5xx) are now reported to the admin backend as diagnostics, with HTTP code, content type, tried extensions, and masked host.

### Changed
- Series and movie playback now retries alternate container extensions (`mkv`/`mp4`/`avi`) when the panel rejects the announced one with 403/404 — panels that omit `container_extension` in `get_series_info` used to fail on the `.mp4` default. Winning extensions are remembered per series/provider for the rest of the session.
- A fully rejected 401/403 series/movie probe is now advisory: the engine still gets one real playback attempt (its HTTP client differs from the probe's) before the provider-refusal message is shown.
- Probe and terminal playback error messages are now in French and actionable (e.g. suggesting the line's package may not include series when live/movies work).
- Playback errors are now always logged with sanitized URLs and HTTP context, not only when they become terminal.

## 1.0.4 - 11/04/2026

### Changed
- Updated VOD playback preparation to carry richer resolved stream metadata into the shared player path, improving compatibility with provider-resolved movie and episode streams.
- Updated TV Guide category selection so Favorites and custom live groups appear in the guide category list, Favorites becomes the entry default when it has channels, and Settings now lets you choose the guide's default startup category.
- Updated Settings navigation by splitting the crowded Playback tab into separate Playback and Browsing sections, moving guide, Live TV, VOD layout, and category browsing preferences into their own dedicated area.
- Updated Settings provider sync so the default action now runs a lighter quick sync, provider switching only auto-refreshes stale providers, and the sync overlay shows the current provider and live progress text.
- Updated Xtream text handling with an optional Base64 compatibility toggle in Settings so provider names and categories stay raw by default, while broad Base64 decoding can be enabled for edge-case feeds.
- Updated Xtream text compatibility toggles so changing them invalidates cached Xtream text imports and forces the next refresh for each Xtream provider once.
- Updated the About screen with official build verification based on the app id and release signing certificate fingerprint.
- Massive code refactoring to reduce large files size.

### Fixed
- Fixed EPG override candidate list stability by adding a stable Compose item key, avoiding unnecessary item churn when override search results update.
- Fixed multi-source XMLTV refresh to close the decompressed input wrapper explicitly after parsing, matching the provider-native EPG refresh path and avoiding leaked gzip/parser stream wrappers on source refreshes.
- Fixed DRM metadata validation so invalid or unsafe DRM license URLs are rejected by the shared stream-entry URL policy before they can reach player Media3 DRM configuration.
- Fixed Xtream Home and guide fallback EPG lookups to batch missing-channel requests through a shared repository path, so provider loading, credential decryption, and Xtream client setup are reused once per fallback burst instead of repeating per channel.
- Fixed Combined M3U profile creation to batch-load selected providers through `ProviderDao.getByIds(...)` instead of issuing one provider lookup per selected source.
- Fixed TV launcher recommendation syncing so preview-channel refresh now runs on `Dispatchers.IO`, serializes overlapping refresh calls, and rate-limits repeated syncs to reduce startup and playback-exit `TvContract` churn.
- Fixed backup export/checksum generation to stream JSON through a writer pipeline instead of materializing large intermediate backup strings before writing or hashing.
- Fixed EPG guide recomposition scope by moving the shared guide clock out of the top-level guide screen and into smaller hero, grid, and dialog subtrees so routine 30-second time updates no longer invalidate the full guide shell.
- Fixed `FileProvider` update-install exposure by restricting shared paths to the APK download directory only and removing the broad cache-root grant surface.
- Fixed in-app updates to require HTTPS release and APK download URLs while leaving HTTP IPTV playback compatibility unchanged.
- Fixed recurring recording rollover so future daily/weekly occurrences detect conflicts with existing manual or active recordings, mark skipped slots as `SCHEDULE_CONFLICT`, and continue searching for the next free slot.
- Fixed Cast startup for unsupported `rtsp://` and `rtmp://` streams by rejecting them early and showing a clear in-app notice instead of failing silently on the receiver.
- Fixed Xtream playback expiry handling so auth-like `401/403` stream failures now trigger a one-time URL refresh and replay attempt before surfacing a playback error.
- Fixed player teardown to clear cached seek-preview thumbnails when the player screen is destroyed, reducing stale bitmap retention across playback sessions.
- Fixed recurring DVR rollover to calculate the next daily or weekly occurrence in local time, preventing schedules from drifting by an hour across Daylight Saving Time changes.
- Fixed offline library launches so Home now shows an “Add your first provider” empty state, while Movies and Series show a clear “Sync needed” state instead of waiting on indefinite loading spinners.
- Fixed parental PIN changes to immediately clear all in-memory unlocked categories so previously unlocked content requires the new PIN right away.
- Fixed top navigation focus so switching between top-bar destinations no longer snaps focus back to the Home tab.
- Fixed in-app update downloads that could remain stuck on `Downloading...` by improving download tracking and completion handling.
- Fixed M3U importer to throw descriptive non-transient HTTP errors so CombinedM3U profiles properly skip dead sources gracefully instead of failing silently.
- Fixed player to immediately identify expired stream URLs and natively return a user-friendly expiration notice instead of triggering empty generic playback failure.
- Fixed `M3uParser` to use idiomatic Kotlin `.lineSequence()` iteration while preserving its strict one-line-in-memory footprint during massive playlist operations.
- Fixed M3U catch-up detection and replay URL expansion so archive-capable playlists using `catchup-source`, `timeshift`, and common replay placeholders are wired correctly.
- Fixed catch-up and replay handling across M3U and Xtream providers with added regression coverage for archive metadata parsing and replay URL building.
- Fixed player startup stability so normal playback no longer eagerly creates split-screen state that could crash channel and episode opens before playback begins.
- Fixed shared player initialization to defer several constructor-time playback state subscriptions, reducing intermittent crashes when opening Live TV and series episodes.
- Fixed Xtream playback startup handling so provider-auth failures such as `401` and `403` are surfaced as player errors/notices instead of continuing into a broken playback open path.
- Fixed provider setup so Xtream-backed playlist URLs such as `get.php?...type=m3u` and `get.php?...type=m3u_plus` are recognized automatically and imported through the Xtream parser instead of the plain M3U path.
- Fixed TV provider setup Back behavior so pressing Back while editing a field now cancels that field edit first, and incomplete unnamed drafts prompt before leaving the screen.
- Fixed TV long-press dialogs so the delayed DPAD/select key release that opens a dialog can no longer immediately trigger the first focused action by accident.
- Fixed in-app update version parsing to read structured build metadata from GitHub release tags such as `v1.0.4+4` instead of scraping version codes from release-note prose.
- Fixed background EPG scheduling so fast-completing refresh jobs are tracked and cleaned up reliably instead of leaving stale job entries behind.
- Fixed provider deletion cleanup so sync teardown now waits on the per-provider lock before dropping sync state and mutex tracking.
- Fixed player stats collection races by making dropped-frame, format, and bandwidth tracking safe across ExoPlayer callbacks and the polling coroutine.
- Fixed live-stream retry recovery so behind-live-window errors now wait briefly and jump back to the live edge before retrying.
- Fixed the in-app update checker to cap GitHub release API response size before JSON parsing, avoiding unbounded memory reads.
- Fixed preloaded stream reuse to compare exact normalized URLs and marked the preload coordinator as main-thread-only to avoid unsafe cross-thread access assumptions.
- Fixed FTS search query building so blank or fully stripped search text no longer reaches Room as an empty `MATCH` query and crash search flows.
- Fixed Xtream text decoding by treating channel, movie, series, episode, and category names as raw provider text by default while limiting automatic base64 decode to EPG and common metadata fields.
- Fixed database maintenance so daily VACUUM is skipped when active read transactions are open (WAL busy-page check) or a provider sync is in progress, preventing the exclusive DB lock from blocking channel loads, EPG queries, and sync writes during background maintenance.
- Fixed database maintenance logging to measure and record the actual duration of the sqlite `VACUUM` compaction.
- Fixed parental control unlock state management to use lock-free `StateFlow.update {}` instead of JVM `synchronized` blocks, eliminating the risk of thread blocking and potential ANR on the main thread when unlock, retain, or clear operations are called from coroutines.
- Fixed a dead `LINE_BREAK_REGEX` alias in `ProviderInputSanitizer` that was identical to `WHITESPACE_REGEX`; the duplicate constant was removed and the single call site updated to use the canonical name.
- Fixed a URL injection bypass in `UrlSecurityPolicy` where double-encoded newlines (`%250A`) and tabs (`%09`) could evade the single-pass `containsNewlines()` check; the decoder now runs two passes and also strips tab characters.
- Fixed `AppUpdateInstaller` to expose an `unregister()` method that safely unregisters the `DownloadManager` broadcast receiver, preventing duplicate receiver registrations when the DI graph is re-created in instrumentation tests.
- Fixed `AppUpdateInstaller` to verify downloaded APKs against expected SHA-256 hashes (if provided by the release) before launching the system package installer, preventing execution of tampered or truncated updates.
- Fixed `SyncManager.onProviderDeleted()` to log a warning when a background EPG job is still active at the time of cancellation, making mid-sync provider deletions visible in production logs.
- Fixed `SeekThumbnailProvider.loadFrame()` to wrap `MediaMetadataRetriever` usage in an 8-second `withTimeoutOrNull` guard, preventing indefinite thread blocking on live IPTV streams served as raw MPEG-TS over HTTP that pass the `.m3u8`/`.mpd` exclusion check.
- Fixed `PlayerStatsCollector` to run its polling loop on `Dispatchers.Main.immediate` instead of `Dispatchers.Default`, eliminating 2–3 needless thread context switches every 250 ms per active player (up to 12 per second in 4-player multiview). Removed the now-redundant `readPlayerSnapshot` suspend function and its `withContext` wrapper; removed `@Volatile` annotations and `AtomicInteger` usage since ExoPlayer analytics callbacks are delivered on the Main thread.
- Fixed `CredentialCrypto` so it is now an interface with a concrete Hilt-provided implementation rather than a hardcoded `object` singleton, enabling standard mock injection during viewmodel and repository tests.
- Fixed `PreloadCoordinator` state invalidation so stream headers and DRM keys are digested using content-stable deterministic SHA-256 hashes instead of JVM memory-address dependent `hashCode()` that breaks across app restarts.
- Fixed `StreamVaultDatabaseMigrationTest` so the full-chain migration verification now sequentially applies and validates the entire schema history spanning from v1 up through the current v30 database version.
- Fixed DVR scheduling from stale guide selections so recording setup now rejects programmes that have already ended and prompts the user to refresh the guide instead of building an invalid request.
- Fixed Xtream playlist auto-import validation so oversized decoded `username` and `password` query parameters are rejected before the playlist URL is converted into an Xtream login request.
- Fixed audio-only playback retry handling so radio and other no-video streams are treated as started once the player is actually ready and playing, instead of waiting forever for a first video frame callback.
- Fixed main player-engine release lifecycle so teardown no longer cancels and rebuilds its shared coroutine scope between playback sessions.
- Fixed Xtream provider setup error handling so the UI now maps login failures from typed exception causes instead of brittle substring checks against arbitrary error text.
- Fixed provider EPG cache replacement and playback-history denormalization writes to run inside Room transactions, preventing partial multi-step database updates.
- Fixed domain continue-watching, recommendation, and search flows to stop masking non-IO upstream failures as empty-state content, so critical data/query faults surface instead of silently disappearing.
- Fixed Movies and Series category browsing to memoize visible-category membership and filtered category rows, reducing repeated list scanning during large-library recompositions.
- Fixed TV Guide hero and channel-row current-program lookups to use derived state, reducing redundant per-channel program rescans on the shared 30-second guide clock.

## 1.0.3 - 09/04/2026

### Added
- Added a full DVR workflow with scheduled and background recording, conflict detection, recording persistence, repair/reconcile support, and app-managed default storage for recordings.
- Added combined M3U live source support, including merged-provider profiles, active source selection, and provider-management controls in Settings for building combined Live TV sources.
- Added an optional Live TV provider/source browser for M3U sources, with compact in-browser switching and the setting disabled by default.
- Added in-app playback actions for completed recordings, plus an on-player recording indicator so active captures remain visible during playback.
- Added broader shipped locale coverage together with locale-aware typography fallbacks to improve multilingual rendering across the TV UI.

### Changed
- Updated provider and recording settings surfaces with improved TV focus treatment, white focus strokes, fixed combined-provider dialogs, and much denser recording cards and action layouts.
- Updated local playback handling so completed recordings open through local-file-capable player data sources and are treated as local media instead of live transport streams.
- Updated recording storage and playback flows to default to safe app-managed folders while still supporting custom storage selection when users want it.

### Fixed
- Fixed Android foreground-service crashes affecting manual and scheduled recording starts on newer Android versions by correcting the recording start path and required service permissions.
- Fixed recording rows and settings controls that were oversized, not fully navigable by D-pad, or not clickable in the recording settings surface.
- Fixed combined-provider focus styling gaps so provider management buttons and add-playlist dialogs now match the rest of the TV interface.
- Fixed corrupted and poorly rendered translated strings across shipped locales by refreshing locale resources and falling back to safer typography where needed.

## 1.0.2 - 08/04/2026

### Added
- Added in-app update discovery and download support backed by GitHub Releases, including cached release metadata, dashboard update callouts, Settings update controls, and APK install handoff through a FileProvider.
- Added bulk category visibility controls in Provider Category Controls with provider-wide `Hide All` and `Unhide All` actions.
- Added per-provider M3U VOD classification controls, including setup-time configuration, a persisted provider flag, and a refresh action to rearrange imported content after toggling the rules.
- Added playback troubleshooting controls in Settings for `System Media Session`, saved decoder mode preference, clearer playback timeout labels, and direct numeric timeout entry.
- Added targeted regression coverage for M3U header BOM handling, XMLTV relaxed parsing, and broader M3U header EPG alias support.

### Changed
- Updated M3U header EPG discovery to accept additional aliases such as `x-tvg-url`, `url-tvg`, `tvg-url`, and `url-xml`, while handling comma-separated guide URLs more reliably.
- Updated M3U and manual XMLTV ingestion to use a more tolerant shared parser so malformed entity text in otherwise usable feeds no longer fails as aggressively.
- Updated the add-provider and provider settings flows so Xtream-only advanced options stay hidden for M3U sources and provider-specific M3U behavior is configured where users expect it.
- Updated playback timeout settings to use clearer live-versus-VOD wording and a simpler typed-seconds workflow instead of long option pickers.
- Updated player initialization so main playback can react live to `MediaSession` and decoder preferences, with improved decoder fallback behavior when software extensions are available.

### Fixed
- Fixed M3U playlist imports that could miss header-declared EPG URLs when the playlist started with a UTF-8 BOM.
- Fixed provider sync so hidden live categories are skipped during Xtream live refresh and ignored during EPG resolution work, reducing unnecessary sync overhead.
- Fixed provider category control management to support bulk hide and restore flows without disturbing categories that were already hidden.
- Fixed M3U provider sync and setup behavior so discovered provider EPG URLs, VOD classification, and category rearrangement stay consistent across refreshes.
- Fixed XMLTV source refreshes that previously failed on some real-world feeds with `unterminated entity ref` parser errors.

## 1.0.1 - 08/04/2026

### Added
- Added manual EPG match management directly from the full guide, including channel-level override selection and a quick return to automatic matching.
- Added EPG resolution coverage summaries and source priority controls in Settings so provider assignments can be reviewed and reordered without leaving the app.
- Added targeted regression coverage for manual EPG source assignment behavior, M3U `url-tvg` header parsing, and gzip-compressed XMLTV imports.

### Changed
- Updated live playback EPG loading to prefer resolved multi-source mappings for current, next, history, and upcoming programme data before falling back to provider-native lookups.
- Updated Home live channel now-playing badges to use the resolved EPG pipeline, aligning the home surface with the guide and player.
- Updated EPG source refresh and assignment flows to immediately re-resolve affected providers after source refresh, enable/disable, delete, assign, unassign, and priority changes.
- Updated provider XMLTV refresh to accept both standard XMLTV files and gzip-compressed `.xml.gz` feeds discovered from M3U playlist headers.

### Fixed
- Fixed M3U playlist imports so header-declared `url-tvg` and `x-tvg-url` feeds are carried through into provider EPG refresh more reliably.
- Fixed manual overrides so they persist as explicit external mappings instead of being lost during normal assignment updates.
- Fixed guide and playback consumers that previously bypassed resolved mappings and could ignore external or manually overridden EPG matches.
