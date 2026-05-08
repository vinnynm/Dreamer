package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.SongRepository

class ToggleFavoriteUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(songId: Long) = repository.toggleFavorite(songId)
}
