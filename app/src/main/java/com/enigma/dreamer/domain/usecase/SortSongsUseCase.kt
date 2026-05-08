package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.Song
import com.enigma.dreamer.core.SongRepository
import com.enigma.dreamer.core.SortOrder

class SortSongsUseCase(private val repository: SongRepository) {
    operator fun invoke(songs: List<Song>, order: SortOrder): List<Song> {
        return repository.sort(songs, order)
    }
}
