# Dreamer — Code Audit, Bug Report & Improvement Roadmap

> Audited files: MusicService, MusicViewModel, SongRepository, DevLyricDatabase,
> SongEntity/Dao, MusicModels, all UI screens and components, Theme, MainActivity.
> Severity: 🔴 Critical · 🟠 High · 🟡 Medium · 🔵 Low / Polish

---

## Part 1 — Active Bugs & Weaknesses

---

### 🔴 B-1 · `deleteObsolete` will wipe everything on an empty scan
*(Fixed in Phase 5.1)*

### 🔴 B-2 · `NowPlayingScreen` silently exits when `currentSong == null`
*(Fixed in Phase 5.2)*

### 🔴 B-3 · `tryRestoreSession` race — called before songs are loaded on cold start
*(Fixed in Phase 5.16)*

### 🟠 B-4 · `setQueue` identity check uses reference equality, breaks on list rebuild
*(Fixed in Phase 5.3)*

### 🟠 B-5 · `MiniPlayer` swipe-right does nothing (silent dead code)
*(Fixed in Phase 5.4 / 8.2)*

### 🟠 B-6 · `observeSongs()` Flow is never collected — DB updates don't refresh UI
*(Fixed in Phase 7.3)*

### 🟠 B-7 · `LyricEditorScreen` playback position is not wired — tap-to-timestamp broken
*(Fixed in Phase 6)*

### 🟠 B-8 · `bakeLyrics` in `SongRepository` ignores the `format` parameter
*(Fixed/documented in Phase 5.12)*

### 🟡 B-9 · `dominantColor()` samples only 144 pixels — inaccurate for gradient art
*(Fixed in Phase 5.15 — AndroidX Palette)*

### 🟡 B-10 · No `WRITE_EXTERNAL_STORAGE` permission for lyric baking on API ≤ 29
*(Fixed in Phase 5.7)*

### 🟡 B-11 · `SongRepository.tryLoadLyrics` reads the entire audio file into memory
*(Fixed in Phase 5.6)*

### 🟡 B-12 · `BootReceiver` starts a foreground service on `BOOT_COMPLETED` without checking user intent
*(Fixed in Phase 5.8; updated to Room in Phase 9.5)*

### 🟡 B-13 · `positionJob` polls every 500 ms unconditionally — wastes CPU when paused
*(Fixed in Phase 5.5)*

### 🟡 B-14 · `PlaylistWithSongIds` query returns one row per song — empty playlists disappear
*(Documented as safe; no code change needed)*

### 🔵 B-15 · `TestThemes.kt` imports `android.system.Os.close` — unused dead import
*(Fixed in Phase 5.10)*

### 🔵 B-16 · `NowPlayingScreen` `@Preview` has mismatched `onSkipToQueue` lambda
*(Fixed in Phase 5.13)*

### 🔵 B-17 · `MusicService` leaks `albumArtBitmap` if service is destroyed mid-transition
*(Fixed in Phase 5.14)*

---

## Part 2 — Architecture & Design Weaknesses

### A-1 · `MusicUiState.Ready` is reused as both library state and playback state
*(Fixed in Phase 7.2 — split into LibraryState + PlayerState)*

### A-2 · `MusicViewModel` is 450+ lines — violates single-responsibility
*(Fixed in Phase 9.1/9.2/9.3 — PlaybackController, ColorExtractor, LyricController extracted)*

### A-3 · No error recovery for `scanAndSync` partial failure
*(Fixed in Phase 5.1 — @Transaction in SongDao.replaceAll)*

### A-4 · `SharedPreferences` for session persistence is synchronous on main thread in `onDestroy`
*(Fixed in Phase 5.9 — commit(); fully replaced by Room in Phase 9.5)*

---

## Part 3 — Improvement Roadmap

---

### Phase 5 — Stability & Correctness ✅ COMPLETE

| # | Task | Effort | Fixes | Status |
|---|------|--------|-------|--------|
| 5.1 | Guard `deleteObsolete` at DAO level; wrap scan DB writes in `@Transaction` | S | B-1, A-3 | ✅ |
| 5.2 | Replace blank-screen `?: return` in `NowPlayingScreen` with proper empty state | S | B-2 | ✅ |
| 5.3 | Fix `setQueue` identity check to use ID comparison | S | B-4 | ✅ |
| 5.4 | Add `onPrevious` to `MiniPlayer` and wire in `LibraryScreen` | S | B-5 | ✅ |
| 5.5 | Skip position-tick work when paused | XS | B-13 | ✅ |
| 5.6 | Switch `tryLoadLyrics` to read only first 64 KB of audio file | S | B-11 | ✅ |
| 5.7 | Add `WRITE_EXTERNAL_STORAGE` to manifest (API ≤ 28); document API 29+ limitation | M | B-10 | ✅ |
| 5.8 | Fix `BootReceiver` to check for a persisted queue before starting the service | XS | B-12 | ✅ |
| 5.9 | Use `commit()` in `MusicService.onTaskRemoved` and `onDestroy` | XS | A-4 | ✅ |
| 5.10 | Remove dead `import android.system.Os.close` from `TestThemes.kt` | XS | B-15 | ✅ |
| 5.11 | Fix `tryLoadLyrics` `TODO()` crash on API < 33 (NotImplementedError at runtime) | S | — | ✅ |
| 5.12 | Document `bakeLyrics` format-parameter contract (B-8) | XS | B-8 | ✅ |
| 5.13 | Fix `NowPlayingScreen` preview trailing-lambda ambiguity | XS | B-16 | ✅ |
| 5.14 | Fix `MusicService` bitmap leak window on concurrent destroy | XS | B-17 | ✅ |
| 5.15 | Switch `dominantColor` to AndroidX Palette | S | B-9 | ✅ |
| 5.16 | Fix `tryRestoreSession` cold-start race | S | B-3 | ✅ |

---

### Phase 6 — Lyric Editor: Playback Integration ✅ COMPLETE

| # | Task | Effort | Status |
|---|------|--------|--------|
| 6.1 | Add `currentPositionMs`, `isPlaying`, `onPlayPause`, `onSeek` parameters to `LyricEditorScreen` | S | ✅ |
| 6.2 | Wire `viewModel.uiState` playback position into `LyricEditorRoute` in `MainActivity` | S | ✅ |
| 6.3 | Add `MiniPlaybackBar` composable (play/pause + seek slider + position labels) | M | ✅ |
| 6.4 | LINE mode: `stampCursor` + Stamp button writes live position, auto-advances | M | ✅ |
| 6.5 | RAW mode: `LrcHelperBar` live-position chip inserts `currentPositionMs` | S | ✅ |
| 6.6 | Out-of-order timestamp detection: red chip on affected rows + warning icon | S | ✅ |

---

### Phase 7 — Performance ✅ COMPLETE

| # | Task | Effort | Fixes | Status |
|---|------|--------|-------|--------|
| 7.1 | Replace hand-rolled color sampler with `androidx.palette:palette-ktx` | S | B-9 | ✅ done in Phase 5 |
| 7.2 | Split `MusicUiState.Ready` into `LibraryState` + `PlayerState` | L | A-1 | ✅ |
| 7.3 | Wire `observeSongs()` as reactive complement to manual scan updates | S | B-6 | ✅ |
| 7.4 | Scope `TestThemes.kt` to `debugImplementation` in build.gradle | XS | — | ✅ (manual step) |

---

### Phase 8 — UX Polish ✅ COMPLETE

| # | Task | Effort | Status |
|---|------|--------|--------|
| 8.1 | `NowPlayingScreen`: show album title beneath artist | XS | ✅ |
| 8.2 | `MiniPlayer`: swipe gesture visual hints + `onPrevious` right-swipe | S | ✅ |
| 8.3 | `LibraryScreen`: "X songs hidden by search" banner | XS | ✅ |
| 8.4 | `LyricEditorScreen`: "Import from clipboard" in `LrcHelperBar` | S | ✅ |
| 8.5 | `QueueScreen`: drag-to-reorder (`sh.calvin.reorderable`) | L | ✅ |
| 8.6 | `PlaylistDetailScreen`: drag-to-reorder + Room persistence | L | ✅ |
| 8.7 | `NowPlayingScreen`: crossfade album art on song transition | S | ✅ |
| 8.8 | EQ / bass boost shortcut via `Equalizer` + `BassBoost` AudioEffect APIs | L | ✅ |

---

### Phase 9 — Architecture Refactor ✅ COMPLETE

| # | Task | Effort | Status | Notes |
|---|------|--------|--------|-------|
| 9.1 | Extract `PlaybackController` class from `MusicViewModel` | M | ✅ | All `musicService?.xxx` calls behind a single null-check surface |
| 9.2 | Extract `ColorExtractor` as a standalone object | S | ✅ | `dominantColor` + `contrastColor` + `extract(Uri?)` suspend API |
| 9.3 | Extract `LyricController` (lazy load + line tracking) from `MusicViewModel` | M | ✅ | `loadIfNeeded()` + `currentLine()` + `Callbacks` interface |
| 9.4 | `MediaStore` `ContentObserver` to auto-trigger `scanAndSync` on library changes | M | ✅ | `repo.observeMediaStoreChanges()` debounced 2 s in ViewModel |
| 9.5 | Replace `SharedPreferences` session persistence with Room `session` table | M | ✅ | DB v3→v4 migration; `SessionEntity` + `SessionDao`; `BootReceiver` updated |
| 9.6 | Unit tests for `SearchSongsUseCase`, `SortSongsUseCase`, `LyricController.currentLine` | M | ✅ | 22 pure JVM tests, no Android deps |

**Phase 9 manual steps:**

1. Add `SessionEntity::class` to `@Database` entities and bump version to `4` in `DevLyricDatabase`.
2. Add `MIGRATION_3_4` (see `DevLyricDatabase_v4_patch.kt`) and include it in `addMigrations()`.
3. Add `abstract fun sessionDao(): SessionDao` to `DevLyricDatabase`.
4. In `MusicService`: replace `prefs by lazy { ... }` with `sessionDao by lazy { ... }`, then swap `persistState()`, `persistStateSync()`, `tryRestoreSession()` with the versions in `MusicService_9_5_patch.kt`.
5. In `SongRepository`: add `observeMediaStoreChanges()` and `unregisterMediaStoreObserver()` members from `SongRepository_9_4_patch.kt`.
6. Copy `PlaybackController.kt`, `ColorExtractor.kt`, `LyricController.kt` into `viewmodel/`.
7. Replace `MusicViewModel.kt` with the Phase 9 version.
8. Copy `BootReceiver.kt` (Phase 9 version) over the existing one.
9. Copy `DreamerUnitTests.kt` into `app/src/test/java/com/enigma/dreamer/`.
10. Run `./gradlew :app:test` — all 22 tests should pass.

---

### Phase 10 — Platform Features

| # | Task | Effort |
|---|------|--------|
| 10.1 | Android Auto — implement `MediaBrowserServiceCompat` on `MusicService` | L |
| 10.2 | Wear OS tile / complication | XL |
| 10.3 | Home screen widget (song title + play/pause) | L |
| 10.4 | Predictive Back animation on `NowPlayingScreen` (shared element to `MiniPlayer`) | M |
| 10.5 | Per-song pitch adjustment via `ExoPlayer.setAudioPitch()` | S |

---

## Quick-Reference: Severity Summary

| ID | Severity | File | Summary | Status |
|----|----------|------|---------|--------|
| B-1 | 🔴 Critical | SongDao / SongRepository | `deleteObsolete` could wipe DB if guard is removed | ✅ Fixed Ph5 |
| B-2 | 🔴 Critical | NowPlayingScreen | Blank screen when `currentSong` is null | ✅ Fixed Ph5 |
| B-3 | 🔴 Critical | MusicViewModel | `tryRestoreSession` race on cold start | ✅ Fixed Ph5 |
| B-4 | 🟠 High | MusicService | `setQueue` identity check is O(n × lyrics) | ✅ Fixed Ph5 |
| B-5 | 🟠 High | Components | Swipe-right in MiniPlayer is dead code | ✅ Fixed Ph5/8 |
| B-6 | 🟠 High | MusicViewModel | `observeSongs()` never collected | ✅ Fixed Ph7 |
| B-7 | 🟠 High | LyricEditorScreen | Playback position not wired — tap-to-stamp broken | ✅ Fixed Ph6 |
| B-8 | 🟠 High | SongRepository | `format` dropped in `bakeLyrics` | ✅ Fixed Ph5 |
| B-9 | 🟡 Medium | MusicViewModel | Color sampler averages produce muddy colors | ✅ Fixed Ph5 |
| B-10 | 🟡 Medium | SongRepository | Missing write permission for lyric baking | ✅ Fixed Ph5 |
| B-11 | 🟡 Medium | SongRepository | Entire audio file read into RAM for tag probe | ✅ Fixed Ph5 |
| B-12 | 🟡 Medium | BootReceiver | Starts service on every boot unconditionally | ✅ Fixed Ph5/9 |
| B-13 | 🟡 Medium | MusicViewModel | Position poll runs every 500 ms even when paused | ✅ Fixed Ph5 |
| B-14 | 🟡 Medium | DevLyricDatabase | Empty-playlist LEFT JOIN edge case (safe, undocumented) | ✅ Documented |
| B-15 | 🔵 Low | TestThemes | Dead `import android.system.Os.close` | ✅ Fixed Ph5 |
| B-16 | 🔵 Low | NowPlayingScreen | Preview trailing lambda binds ambiguously | ✅ Fixed Ph5 |
| B-17 | 🔵 Low | MusicService | Bitmap leak window during concurrent destroy | ✅ Fixed Ph5 |
| A-1 | 🟠 High | MusicViewModel | Monolithic `MusicUiState.Ready` copied every 500 ms | ✅ Fixed Ph7 |
| A-2 | 🟠 High | MusicViewModel | 450-line ViewModel violates SRP | ✅ Fixed Ph9 |
| A-3 | 🟠 High | SongRepository | No atomic transaction around scan DB writes | ✅ Fixed Ph5 |
| A-4 | 🟡 Medium | MusicService | `apply()` may not flush before process kill | ✅ Fixed Ph5/9 |
