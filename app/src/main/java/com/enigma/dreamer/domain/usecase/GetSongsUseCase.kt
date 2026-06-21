package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.Song
import com.enigma.dreamer.core.SongRepository

class GetSongsUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(): List<Song> = repository.loadSongsFromCache()
}
