package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.Playlist
import com.enigma.dreamer.core.SongRepository
import kotlinx.coroutines.flow.Flow

class GetPlaylistsUseCase(private val repository: SongRepository) {
    operator fun invoke(): Flow<List<Playlist>> = repository.observePlaylists()
    suspend fun loadAll(): List<Playlist> = repository.loadPlaylists()
}
