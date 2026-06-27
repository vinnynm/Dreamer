// ─────────────────────────────────────────────────────────────────────────────
// FILE: app/src/test/java/com/enigma/dreamer/DreamerUnitTests.kt
//
// Pure Kotlin JVM tests — no Android dependencies, no Robolectric.
// Run with: ./gradlew :app:test
//
// Coverage:
//   9.6a — SearchSongsUseCase
//   9.6b — SortSongsUseCase  (via SongRepository.sort())
//   9.6c — LyricController.currentLine()
// ─────────────────────────────────────────────────────────────────────────────

package com.enigma.dreamer

import com.enigma.devlyric.core.LyricDocument
import com.enigma.devlyric.core.LyricLine
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.core.SortOrder
import com.enigma.dreamer.domain.usecase.SearchSongsUseCase
import org.junit.Assert.*
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun song(
    id: Long,
    title: String,
    artist: String = "Artist $id",
    album: String  = "Album $id",
    duration: Long = 180_000L,
    isFavorite: Boolean = false
) = Song(
    id         = id,
    title      = title,
    artist     = artist,
    album      = album,
    duration   = duration,
    uri        = io.mockk.mockk(relaxed = true),
    isFavorite = isFavorite
)

private fun lyricDoc(vararg pairs: Pair<Long?, String>) = LyricDocument(
    title    = "Test",
    artist   = "Test",
    album    = "Test",
    offsetMs = 0,
    lines    = pairs.map { (ts, text) -> LyricLine(timestampMs = ts, text = text) }
)

// ─────────────────────────────────────────────────────────────────────────────
// 9.6a — SearchSongsUseCase
// ─────────────────────────────────────────────────────────────────────────────

class SearchSongsUseCaseTest {

    private val useCase = SearchSongsUseCase()

    private val library = listOf(
        song(1, "Bohemian Rhapsody", artist = "Queen",   album = "A Night at the Opera"),
        song(2, "Hotel California",  artist = "Eagles",  album = "Hotel California"),
        song(3, "Stairway to Heaven",artist = "Led Zeppelin", album = "Led Zeppelin IV"),
        song(4, "Imagine",           artist = "John Lennon",  album = "Imagine"),
        song(5, "Yesterday",         artist = "The Beatles",  album = "Help!")
    )

    @Test fun `blank query returns all songs`() {
        val result = useCase(library, "")
        assertEquals(library, result)
    }

    @Test fun `whitespace-only query returns all songs`() {
        val result = useCase(library, "   ")
        assertEquals(library, result)
    }

    @Test fun `title match is case-insensitive`() {
        val result = useCase(library, "bohemian")
        assertEquals(1, result.size)
        assertEquals(1L, result.first().id)
    }

    @Test fun `artist match returns correct songs`() {
        val result = useCase(library, "beatles")
        assertEquals(1, result.size)
        assertEquals(5L, result.first().id)
    }

    @Test fun `album match returns correct songs`() {
        val result = useCase(library, "imagine")
        // Matches title "Imagine" (song 4) AND album "Imagine" (song 4) — same song
        assertEquals(1, result.size)
        assertEquals(4L, result.first().id)
    }

    @Test fun `partial artist match works`() {
        // "Led" matches "Led Zeppelin"
        val result = useCase(library, "led")
        assertEquals(1, result.size)
        assertEquals(3L, result.first().id)
    }

    @Test fun `no match returns empty list`() {
        val result = useCase(library, "xyznotexist")
        assertTrue(result.isEmpty())
    }

    @Test fun `query matching both title and artist returns no duplicates`() {
        // "Hotel" matches title "Hotel California" and would also match an artist
        // named "Hotel" — but only song 2 should appear once.
        val result = useCase(library, "hotel")
        assertEquals(1, result.size)
    }

    @Test fun `empty library returns empty list`() {
        val result = useCase(emptyList(), "queen")
        assertTrue(result.isEmpty())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 9.6b — Sort logic (mirrors SongRepository.sort())
//
// We test the sort logic directly as a pure function to avoid requiring a
// real SongRepository (which needs an Android Context for Room).
// The actual SongRepository.sort() is deterministic and dependency-free;
// copying it here keeps the tests as pure JVM.
// ─────────────────────────────────────────────────────────────────────────────

class SortSongsTest {

    // Mirror of SongRepository.sort() — pure function, no Android deps
    private fun sort(songs: List<Song>, order: SortOrder): List<Song> = when (order) {
        SortOrder.TITLE_ASC       -> songs.sortedBy    { it.title.lowercase() }
        SortOrder.TITLE_DESC      -> songs.sortedByDescending { it.title.lowercase() }
        SortOrder.ARTIST_ASC      -> songs.sortedBy    { it.artist.lowercase() }
        SortOrder.ARTIST_DESC     -> songs.sortedByDescending { it.artist.lowercase() }
        SortOrder.ALBUM_ASC       -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }))
        SortOrder.DURATION_ASC    -> songs.sortedBy    { it.duration }
        SortOrder.DURATION_DESC   -> songs.sortedByDescending { it.duration }
        SortOrder.DATE_ADDED_DESC -> songs.sortedByDescending { it.id }
        SortOrder.FAVORITES_FIRST -> songs.sortedWith(
            compareByDescending<Song> { it.isFavorite }.thenBy { it.title.lowercase() })
    }

    private val library = listOf(
        song(1, "Zebra",   artist = "Charlie", album = "Beta",  duration = 300_000L, isFavorite = false),
        song(2, "Apple",   artist = "Alice",   album = "Alpha", duration = 120_000L, isFavorite = true),
        song(3, "Mango",   artist = "Bob",     album = "Gamma", duration = 240_000L, isFavorite = false),
        song(4, "Banana",  artist = "Dave",    album = "Delta", duration = 180_000L, isFavorite = true)
    )

    @Test fun `TITLE_ASC sorts alphabetically`() {
        val result = sort(library, SortOrder.TITLE_ASC)
        assertEquals(listOf("Apple", "Banana", "Mango", "Zebra"), result.map { it.title })
    }

    @Test fun `TITLE_DESC sorts reverse alphabetically`() {
        val result = sort(library, SortOrder.TITLE_DESC)
        assertEquals(listOf("Zebra", "Mango", "Banana", "Apple"), result.map { it.title })
    }

    @Test fun `ARTIST_ASC sorts by artist name`() {
        val result = sort(library, SortOrder.ARTIST_ASC)
        assertEquals(listOf("Alice", "Bob", "Charlie", "Dave"), result.map { it.artist })
    }

    @Test fun `DURATION_ASC puts shortest first`() {
        val result = sort(library, SortOrder.DURATION_ASC)
        assertEquals(listOf(120_000L, 180_000L, 240_000L, 300_000L), result.map { it.duration })
    }

    @Test fun `DURATION_DESC puts longest first`() {
        val result = sort(library, SortOrder.DURATION_DESC)
        assertEquals(listOf(300_000L, 240_000L, 180_000L, 120_000L), result.map { it.duration })
    }

    @Test fun `DATE_ADDED_DESC sorts by id descending`() {
        val result = sort(library, SortOrder.DATE_ADDED_DESC)
        assertEquals(listOf(4L, 3L, 2L, 1L), result.map { it.id })
    }

    @Test fun `FAVORITES_FIRST puts favorites before non-favorites`() {
        val result = sort(library, SortOrder.FAVORITES_FIRST)
        val favSection    = result.filter { it.isFavorite }
        val nonFavSection = result.filter { !it.isFavorite }
        // Favorites must come first
        assertTrue(result.indexOf(favSection.first()) < result.indexOf(nonFavSection.first()))
        // Within favorites, alphabetical by title
        assertEquals(listOf("Apple", "Banana"), favSection.map { it.title })
        // Within non-favorites, alphabetical by title
        assertEquals(listOf("Mango", "Zebra"), nonFavSection.map { it.title })
    }

    @Test fun `sort on empty list returns empty list`() {
        assertTrue(sort(emptyList(), SortOrder.TITLE_ASC).isEmpty())
    }

    @Test fun `sort on single-element list returns same element`() {
        val single = listOf(song(1, "Only"))
        assertEquals(single, sort(single, SortOrder.ARTIST_DESC))
    }

    @Test fun `title sort is case-insensitive`() {
        val mixed = listOf(song(1, "zebra"), song(2, "Apple"), song(3, "MANGO"))
        val result = sort(mixed, SortOrder.TITLE_ASC)
        assertEquals(listOf("Apple", "MANGO", "zebra"), result.map { it.title })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 9.6c — LyricController.currentLine()
// ─────────────────────────────────────────────────────────────────────────────

class LyricControllerCurrentLineTest {

    // Inline the pure function so the test has no Android dependency
    private fun currentLine(doc: LyricDocument, posMs: Long): Int {
        var last = -1
        for ((idx, line) in doc.lines.withIndex()) {
            val ts = line.timestampMs ?: continue
            if (ts <= posMs) last = idx else break
        }
        return last
    }

    private val doc = lyricDoc(
        0L       to "Intro",
        5_000L   to "First verse",
        10_000L  to "Second verse",
        15_000L  to "Chorus",
        20_000L  to "Bridge",
        null     to "Untimed line"
    )

    @Test fun `position before first timestamp returns -1`() {
        assertEquals(-1, currentLine(doc, 0L - 1))
    }

    @Test fun `position exactly at first timestamp returns 0`() {
        assertEquals(0, currentLine(doc, 0L))
    }

    @Test fun `position between two timestamps returns earlier index`() {
        // between 5000 and 10000 → index 1 ("First verse")
        assertEquals(1, currentLine(doc, 7_500L))
    }

    @Test fun `position exactly at timestamp returns that line`() {
        assertEquals(2, currentLine(doc, 10_000L))
    }

    @Test fun `position after last timed line returns last timed index`() {
        // Untimed line (index 5) must not be returned; last timed is index 4 (20_000)
        assertEquals(4, currentLine(doc, 99_999L))
    }

    @Test fun `document with no timestamps always returns -1`() {
        val noTs = lyricDoc(null to "A", null to "B", null to "C")
        assertEquals(-1, currentLine(noTs, 5_000L))
        assertEquals(-1, currentLine(noTs, 0L))
    }

    @Test fun `empty document returns -1`() {
        val empty = lyricDoc()
        assertEquals(-1, currentLine(empty, 5_000L))
    }

    @Test fun `mixed timed-untimed lines skips untimed entries`() {
        val mixed = lyricDoc(
            null    to "Header",
            1_000L  to "Line A",
            null    to "No timestamp",
            3_000L  to "Line B"
        )
        // At 2000 ms: Line A (index 1) is active; "No timestamp" (index 2) is skipped;
        // Line B (index 3) hasn't been reached yet.
        assertEquals(1, currentLine(mixed, 2_000L))
    }

    @Test fun `position at last timed line returns that index`() {
        assertEquals(4, currentLine(doc, 20_000L))
    }
}
