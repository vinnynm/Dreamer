package com.enigma.dreamer.domain.usecase

import com.enigma.dreamer.core.SongRepository

class CreatePlaylistUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(name: String) = repository.createPlaylist(name)
}

class AddSongToPlaylistUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(songId: Long, playlistId: Long) = repository.addSongToPlaylist(songId, playlistId)
}

class RemoveSongFromPlaylistUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(songId: Long, playlistId: Long) = repository.removeSongFromPlaylist(songId, playlistId)
}

class DeletePlaylistUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(playlistId: Long) = repository.deletePlaylist(playlistId)
}

class RenamePlaylistUseCase(private val repository: SongRepository) {
    suspend operator fun invoke(playlistId: Long, newName: String) = repository.renamePlaylist(playlistId, newName)
}
