# DevLyric — Music Player with Embedded Lyrics

A fully-featured Android music player in Jetpack Compose that integrates the **DevLyric core library** for reading, embedding, and displaying synchronized lyrics. Built without Hilt — all DI is manual via AndroidViewModel.

---

## Architecture Overview

```
com.enigma.devlyric
├── core/                      ← LyricBaker library (your existing code)
│   ├── LyricBaker.kt          ← Public API: bake / extract
│   ├── LyricParser.kt         ← LRC / SRT / PLAIN parsers
│   ├── Id3v2Writer.kt         ← MP3 ID3 USLT/SYLT frames
│   ├── Id3v2Reader.kt         ← MP3 lyric extraction
│   ├── ItunesAtomWriter.kt    ← M4A ©lyr atom writer
│   ├── ItunesAtomReader.kt    ← M4A lyric extraction
│   ├── Models.kt              ← LyricLine, LyricDocument, BakeResult
│   └── SongRepository.kt      ← MediaStore + sidecar .lrc/.srt loading
│
├── service/
│   └── MusicService.kt        ← Foreground service, MediaSession, AudioFocus
│
├── viewmodel/
│   └── MusicViewModel.kt      ← AndroidViewModel, StateFlow, all controls
│
├── ui/
│   ├── theme/Theme.kt         ← Amoled dark + Amber palette
│   ├── components/
│   │   └── Components.kt      ← AlbumArtwork, PlaybackSlider, Controls,
│   │                             SongListItem, MiniPlayer, LyricLineItem
│   └── screens/
│       ├── LibraryScreen.kt   ← Songs + Playlists tabs, Search
│       ├── NowPlayingScreen.kt← Full-screen player + synchronized lyrics
│       └── PlaylistDetailScreen.kt
│
├── MainActivity.kt            ← Compose entry point, nav, permissions
├── DevLyricApp.kt             ← Application class
├── AndroidManifest.xml
└── build.gradle
```

---

## Features

### Playback
- ▶ Play / ⏸ Pause / ⏭ Next / ⏮ Previous
- 🔀 Shuffle (queue reshuffled in-memory)
- 🔁 Repeat: None → All → One
- Seek bar with real-time position + duration
- Auto-advance to next track on completion

### Lyrics
- **Synchronized lyrics** — active line animates (scale + amber highlight)
- **Auto-scroll** — LazyColumn snaps to current line
- Loaded from:
  1. Sidecar `.lrc` file (same folder as audio)
  2. Sidecar `.srt` file
  3. Embedded ID3v2 `USLT` (MP3) or `©lyr` atom (M4A)
- Toggle lyrics / artwork view with the mic button

### Library
- Auto-loads all music files via MediaStore
- Real-time search (title / artist / album)
- Song count badge

### Playlists
- Create, rename, delete playlists
- Add songs via long-press → dialog
- Remove songs from playlist detail view
- Play entire playlist (respects shuffle mode)
- Persisted to SharedPreferences (no Room dependency)

### Background Playback
- Foreground service with notification controls
- Lock-screen controls via MediaSession
- Bluetooth / headphone buttons via MediaSession callbacks
- Audio focus: pauses on call, resumes on gain
- START_STICKY — service restarts if killed

### Notification
- Play/Pause, Next, Previous, Stop actions
- Media style with album art (when available)
- Ongoing while playing, dismissible when paused

---

## Setup

### 1. Add to your project

Copy the `core/` package (your existing library) and the files above into your app module.

### 2. `build.gradle` dependencies

```groovy
// Compose BOM
implementation platform('androidx.compose:compose-bom:2024.02.00')
implementation 'androidx.compose.ui:ui'
implementation 'androidx.compose.material3:material3'
implementation 'androidx.compose.material:material-icons-extended'
implementation 'androidx.compose.animation:animation'

// Lifecycle + ViewModel
implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'
implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'

// Media session support
implementation 'androidx.media:media:1.7.0'

// Coil for album art
implementation 'io.coil-kt:coil-compose:2.5.0'

// Coroutines
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
```

### 3. AndroidManifest.xml

The key declarations required:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".service.MusicService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false" />
```

---

## How Lyrics Work

### Loading Order

```
SongRepository.tryLoadLyrics(audioPath)
    │
    ├─ "$base.lrc" exists? → LyricParser.parse(lrc, LRC)
    ├─ "$base.srt" exists? → LyricParser.parse(srt, SRT)
    └─ MP3/M4A? → LyricBaker.extractLyrics(bytes, ext)
                     ├─ MP3: Id3v2Reader.readLyrics(bytes)  → USLT frame
                     └─ M4A: ItunesAtomReader.readLyrics(bytes) → ©lyr atom
```

### Embedding Lyrics Programmatically

```kotlin
// Embed from text
val result = LyricBaker.bake(
    audioFile  = File("/sdcard/Music/song.mp3"),
    lyricFile  = File("/sdcard/Music/song.lrc"),
    outputFile = File("/sdcard/Music/song_tagged.mp3")
)

// Via ViewModel (updates in-memory song)
viewModel.embedLyrics(song, lrcText, LyricFormat.LRC)
```

### Current Line Tracking

The ViewModel polls `MusicService.currentPosition()` every 500 ms and finds the current lyric line:

```kotlin
private fun findCurrentLyricLine(doc: LyricDocument, posMs: Long): Int {
    var last = -1
    for ((idx, line) in doc.lines.withIndex()) {
        val ts = line.timestampMs ?: continue
        if (ts <= posMs) last = idx else break
    }
    return last
}
```

---

## State Flow

```
MusicService (MediaPlayer, AudioFocus, MediaSession)
    │  playbackState: StateFlow<PlaybackState>
    ▼
MusicViewModel (AndroidViewModel)
    │  uiState: StateFlow<MusicUiState>
    ▼
Compose UI (collectAsState)
    ├── LibraryScreen
    ├── NowPlayingScreen
    └── PlaylistDetailScreen
```

No Hilt, no Dagger. The ViewModel binds to the service in `init {}` using a standard `ServiceConnection`. The service is started as a foreground service via `startService()` and bound with `bindService()`.

---

## Extending

### Add audio effects (equalizer)

```kotlin
// In MusicService after preparePlayer()
val audioSessionId = player?.audioSessionId ?: return
val eq = Equalizer(0, audioSessionId)
eq.enabled = true
```

### Add crossfade

Override `onTrackComplete()` in `MusicService` to fade out the current player while preparing a new one with `MediaPlayer.create()` at volume 0 and animate volume up.

### Room for playlists

Replace `SongRepository.savePlaylists()` / `loadPlaylists()` with a `@Dao` + `@Database`. No other changes needed — the ViewModel interface is the same.

### Android Auto

Register a `MediaBrowserServiceCompat` (extend `MusicService` or create a companion service), implement `onGetRoot()` / `onLoadChildren()` returning your song/playlist tree.

---

## Permissions Summary

| Permission | Why |
|---|---|
| `READ_MEDIA_AUDIO` (API 33+) | Load songs from MediaStore |
| `READ_EXTERNAL_STORAGE` (API ≤ 32) | Load songs from MediaStore |
| `POST_NOTIFICATIONS` (API 33+) | Show playback notification |
| `FOREGROUND_SERVICE` | Run MusicService in foreground |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Required for `foregroundServiceType="mediaPlayback"` |
| `WAKE_LOCK` | Keep CPU awake during background playback |
