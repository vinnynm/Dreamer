package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.Song

class SearchSongsUseCase {
    operator fun invoke(songs: List<Song>, query: String): List<Song> {
        return if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.album.contains(query, ignoreCase = true)
        }
    }
}
