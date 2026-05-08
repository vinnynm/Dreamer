package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.SongRepository
import kotlinx.coroutines.flow.Flow

class ObserveFavoritesUseCase(private val repository: SongRepository) {
    operator fun invoke(): Flow<Set<Long>> = repository.observeFavoriteIds()
}
