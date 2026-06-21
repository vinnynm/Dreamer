// ─────────────────────────────────────────────────────────────────────────────
// PHASE 2 PATCH NOTES — Load time fix
// ─────────────────────────────────────────────────────────────────────────────
//
// ROOT CAUSE OF 1–3 MINUTE STARTUP TIME
// ──────────────────────────────────────
// SongRepository.loadSongs() was calling tryLoadLyrics() inside the MediaStore
// cursor loop — once per song. For each mp3/m4a file this read the ENTIRE file
// into memory (File.readBytes()), then passed the bytes to LyricBaker.
//
// For 1000 songs at ~50 ms per file read that's ~50 seconds of blocking I/O
// before a single song appeared on screen. There was also no DB cache, so this
// happened on every cold start.
//
// WHAT CHANGED
// ────────────────────────────────────────────────────────────────────────────
//
// File              | Change
// ──────────────────┼─────────────────────────────────────────────────────────
// SongEntity.kt     | NEW — Room entity mapping Song ↔ DB row. No lyricDocument
//                   |       column; lyrics are loaded lazily.
// SongDao.kt        | NEW — upsertAll, getAll, observeAll, deleteObsolete,
//                   |       syncFavorites, setFavorite, setLyricHint.
// DevLyricDatabase  | UPDATED — version 2→3, adds SongDao + songs table.
//                   |       MIGRATION_2_3 creates the songs table without
//                   |       touching playlists or favorites.
// SongRepository    | REWRITTEN — split into:
//                   |   loadSongsFromCache()  → reads Room, ~50–150 ms
//                   |   scanAndSync()         → MediaStore metadata only,
//                   |                            NO file reads, upserts to Room
//                   |   loadLyricsForSong()   → file I/O moved here, only
//                   |                            called when song is played
// MusicViewModel    | REWRITTEN — two-phase load:
//                   |   Phase A: loadSongsFromCache() → show library instantly
//                   |   Phase B: scanAndSync() in background coroutine
//                   |   loadLyricsIfNeeded() called on playSong / song change
//                   |   scanProgress StateFlow for UI progress bar
//                   |   rescan() for manual refresh
// LibraryScreen     | UPDATED — scan progress bar + Refresh button in top bar.
//                   |   SortOrder.values() → SortOrder.entries (Phase 1 fix).
// MainActivity      | PATCH — collect scanProgress, pass to LibraryScreen +
//                   |         wire onRescan = viewModel::rescan
//
// EXPECTED PERFORMANCE
// ────────────────────────────────────────────────────────────────────────────
//
// Cold start (DB populated from previous run):
//   Phase A — Room read:      ~50–150 ms  → library visible
//   Phase B — MediaStore scan:~200–600 ms → silent background update
//   Lyric load (per song):    ~20–100 ms  → only when song is played
//
// First-ever launch (empty DB):
//   Loading screen shown until Phase B completes (~200–600 ms)
//   No file reads during scan; lyrics deferred to playback
//
// DEPLOYMENT CHECKLIST
// ────────────────────────────────────────────────────────────────────────────
// 1. Add SongEntity.kt        to com/enigma/dreamer/core/
// 2. Add SongDao.kt           to com/enigma/dreamer/core/
// 3. Replace DevLyricDatabase to com/enigma/dreamer/core/  (v3, keeps playlists)
// 4. Replace SongRepository   to com/enigma/dreamer/core/
// 5. Replace MusicViewModel   to com/enigma/dreamer/viewmodel/
// 6. Replace LibraryScreen    to com/enigma/dreamer/ui/screens/
// 7. Apply MainActivity patch — add scanProgress + onRescan to LibraryRoute block
//
// The GetSongsUseCase is now unused (loadAll() calls repo directly).
// You can delete GetSongsUseCase.kt or leave it — it won't cause a build error.
// ─────────────────────────────────────────────────────────────────────────────
