// ─────────────────────────────────────────────────────────────────────────────
// PATCH for LyricEditorScreen.kt — top of file only
//
// CHANGE: Add import for the local toLrcText() extension.
// Everything else in the file stays identical.
// ─────────────────────────────────────────────────────────────────────────────

// Add this import alongside the existing com.enigma.devlyric.core.* imports:
//
//   import com.enigma.dreamer.core.toLrcText
//
// The line in the composable that reads:
//
//   val initialText = remember(song.id) {
//       song.lyricDocument?.toLrcText() ?: ""
//   }
//
// will then resolve correctly. No other changes needed in LyricEditorScreen.kt.

// ─────────────────────────────────────────────────────────────────────────────
// Also: LibraryScreen.kt uses SortOrder.values() in SortBottomSheet.
// FIX: same change as SettingsScreen — replace with SortOrder.entries.
//
// In LibraryScreen.kt, find these two occurrences and change both:
//
//   SortOrder.values().forEach { order ->   →   SortOrder.entries.forEach { order ->
//
// One is inside SortBottomSheet(), the other is inside SettingsScreen
// (already patched above). LibraryScreen only has the SortBottomSheet one.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// SUMMARY OF ALL SIX FIXES AND WHICH FILES TO REPLACE
// ─────────────────────────────────────────────────────────────────────────────
//
// Fix | File                        | What changed
// ────┼─────────────────────────────┼─────────────────────────────────────────
//  1  | LyricExtensions.kt (NEW)    | Adds toLrcText() extension on LyricDocument
//  1  | LyricEditorScreen.kt        | +import com.enigma.dreamer.core.toLrcText
//  2  | MusicViewModel.kt           | extractAndApplyColor gated by lastColorSongId
//     |                             | updatePlayback() only calls it on song change
//     |                             | position tick no longer triggers color extract
//  3  | NowPlayingScreen.kt         | Vinyl uses frozenAngle + LaunchedEffect pattern
//     |                             | liveAngle only copied to frozenAngle while playing
//     |                             | displayAngle = isPlaying ? liveAngle : frozenAngle
//  4  | SettingsScreen.kt           | SortOrder.values() → SortOrder.entries
//  4  | LibraryScreen.kt            | SortOrder.values() → SortOrder.entries (in SortBottomSheet)
//  5  | DevLyricDatabase.kt         | fallbackToDestructiveMigration() removed
//     |                             | MIGRATION_1_2 added (drops+recreates tables)
//     |                             | .addMigrations(MIGRATION_1_2) in builder
//  6  | NowPlayingScreen.kt         | import com.enigma.dreamer.core.RepeatMode as AppRepeatMode
//     |                             | (already in the NowPlayingScreen.kt fix above)
// ─────────────────────────────────────────────────────────────────────────────
