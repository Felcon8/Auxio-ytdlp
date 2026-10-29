/*
 * Copyright (c) 2026 Auxio Project
 * YtdlpViewModel.kt is part of Auxio (yt-dlp fork additions).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.ytdlp

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.PlaylistFolders
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** Where the downloaded songs should end up. */
sealed interface YtdlpTarget {
    /** Add the songs to an already existing [Playlist]. */
    data class Existing(val playlist: Playlist) : YtdlpTarget

    /** Create a new playlist with the given name. */
    data class New(val name: String) : YtdlpTarget

    /** Do not add the songs to any playlist. */
    data object None : YtdlpTarget
}

/**
 * UI state of the yt-dlp tab.
 *
 * @param busy Whether a download is currently running.
 * @param message A status message to show, empty if none.
 * @param progress Download progress from 0 to 100, or -1 if unknown.
 */
data class YtdlpState(val busy: Boolean = false, val message: String = "", val progress: Int = -1)

/**
 * Downloads audio with yt-dlp, stores it in the shared Music folder, waits for Auxio to index it
 * and finally puts the new songs into a playlist.
 */
@HiltViewModel
class YtdlpViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playlistFolders: PlaylistFolders,
) : ViewModel() {
    private val _state = MutableStateFlow(YtdlpState())
    val state: StateFlow<YtdlpState> = _state

    /** The existing playlists, sorted by name. */
    fun playlists(): List<Playlist> {
        val library = musicRepository.library ?: return emptyList()
        return library.playlists.sortedBy { it.name.resolve(context).lowercase() }
    }

    /** Returns an existing playlist with the given name (ignoring case), or null. */
    fun findPlaylistNamed(name: String): Playlist? =
        playlists().firstOrNull { it.name.resolve(context).equals(name, ignoreCase = true) }

    fun download(url: String, target: YtdlpTarget) {
        if (_state.value.busy) return
        _state.value = YtdlpState(busy = true, message = context.getString(R.string.ytdlp_starting))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runJob(url, target)
            } catch (e: Exception) {
                L.e(e, "yt-dlp job failed")
                _state.value =
                    YtdlpState(
                        message =
                            context.getString(
                                R.string.ytdlp_failed,
                                e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }
                                    ?: e.javaClass.simpleName,
                            )
                    )
            }
        }
    }

    private suspend fun runJob(url: String, target: YtdlpTarget) {
        ensureInitialized()

        // 1. Download & convert with yt-dlp into a private scratch directory.
        val scratch = File(context.cacheDir, "ytdlp").apply {
            deleteRecursively()
            mkdirs()
        }
        // YouTube changes which player clients work every few weeks, and a hard-pinned client
        // list is a common cause of "HTTP Error 403". So first let yt-dlp pick its own clients
        // and only fall back to specific ones if that fails without producing any audio.
        var lastError: Exception? = null
        for (client in PLAYER_CLIENT_ATTEMPTS) {
            val request =
                YoutubeDLRequest(url).apply {
                    // Always end up with MP3: -x drops the video stream (so an mp4 becomes audio)
                    // and --audio-format converts whatever audio is left to mp3 with ffmpeg.
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                    addOption("--no-mtime")
                    addOption("--ignore-errors")
                    addOption("-o", "${scratch.absolutePath}/%(title).100s.%(ext)s")
                    if (client != null) {
                        addOption("--extractor-args", "youtube:player_client=$client")
                    }
                    // A link to a single video that merely carries a playlist id should not pull
                    // in the whole playlist. Pure playlist links still download everything.
                    if (url.contains("watch?v=") || url.contains("youtu.be/")) {
                        addOption("--no-playlist")
                    }
                }
            try {
                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, _ ->
                    _state.value =
                        YtdlpState(
                            busy = true,
                            message = context.getString(R.string.ytdlp_downloading),
                            progress = progress.toInt().coerceIn(0, 100),
                        )
                }
                lastError = null
            } catch (e: Exception) {
                // yt-dlp reports a failure if *any* playlist entry failed. Keep going if we still
                // got some audio out of it.
                lastError = e
                L.w(e, "yt-dlp attempt with client=$client finished with errors")
            }
            if (audioFiles(scratch).isNotEmpty()) break
        }
        if (audioFiles(scratch).isEmpty()) lastError?.let { throw it }

        val files = audioFiles(scratch)
        if (files.isEmpty()) {
            _state.value = YtdlpState(message = context.getString(R.string.ytdlp_nothing))
            return
        }

        // 2. Move the files into the shared Music folder so Auxio can see them.
        // The songs go straight into the folder of the chosen playlist (inside the main music
        // folder), or into "yt-dlp" when the playlist question was skipped.
        val folder =
            when (target) {
                is YtdlpTarget.Existing ->
                    PlaylistFolders.folderName(target.playlist.name.resolve(context))
                is YtdlpTarget.New -> PlaylistFolders.folderName(target.name)
                YtdlpTarget.None -> DEFAULT_FOLDER
            }
        _state.value =
            YtdlpState(
                busy = true,
                message = context.getString(R.string.ytdlp_saving, folder),
                progress = -1,
            )
        val names =
            files
                .map { file ->
                    playlistFolders.save(folder, file.name) { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
                .toSet()
        scratch.deleteRecursively()

        // 3. Make Auxio re-index and wait for the new songs to show up.
        _state.value =
            YtdlpState(busy = true, message = context.getString(R.string.ytdlp_indexing), progress = -1)
        musicRepository.requestIndex(true)
        val songs = awaitSongs(names, folder)
        if (songs.isEmpty()) {
            _state.value = YtdlpState(message = context.getString(R.string.ytdlp_not_indexed, folder))
            return
        }

        // 4. Put them in the playlist the user picked.
        val playlistName: String
        when (target) {
            YtdlpTarget.None -> {
                _state.value =
                    YtdlpState(
                        message = context.getString(R.string.ytdlp_done_no_playlist, songs.size, folder)
                    )
                return
            }
            is YtdlpTarget.Existing -> {
                // The library was re-created by the re-index, look the playlist up again.
                val current = musicRepository.library?.findPlaylist(target.playlist.uid)
                    ?: target.playlist
                musicRepository.addToPlaylist(songs, current)
                playlistName = current.name.resolve(context)
            }
            is YtdlpTarget.New -> {
                // The folder of a new playlist may already have been turned into a playlist by
                // the indexer, in that case just add what is missing.
                val auto =
                    musicRepository.library?.playlists?.firstOrNull {
                        it.name.resolve(context).equals(target.name, ignoreCase = true)
                    }
                if (auto != null) {
                    val missing = songs.filter { s -> auto.songs.none { it.uid == s.uid } }
                    if (missing.isNotEmpty()) musicRepository.addToPlaylist(missing, auto)
                    playlistName = auto.name.resolve(context)
                } else {
                    musicRepository.createPlaylist(target.name, songs)
                    playlistName = target.name
                }
            }
        }
        _state.value =
            YtdlpState(message = context.getString(R.string.ytdlp_done, songs.size, playlistName))
    }

    private fun audioFiles(dir: File) =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()

    private suspend fun awaitSongs(names: Set<String>, folder: String): List<Song> {
        val deadline = SystemClock.elapsedRealtime() + INDEX_TIMEOUT_MS
        var found = emptyList<Song>()
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(1000)
            val library = musicRepository.library ?: continue
            found =
                library.songs.filter { song ->
                    song.path.name?.let { it in names } == true &&
                        song.path.directory.name == folder
                }
            if (found.size >= names.size) break
        }
        return found
    }

    companion object {
        private const val PROCESS_ID = "auxio-ytdlp"
        private const val DEFAULT_FOLDER = "yt-dlp"

        // null = let yt-dlp choose; the rest are fallbacks tried in order.
        private val PLAYER_CLIENT_ATTEMPTS = listOf(null, "android_vr", "tv", "web_safari")
        private const val INDEX_TIMEOUT_MS = 180_000L
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav")

        @Volatile private var initialized = false

        private val initLock = Any()
    }

    private fun ensureInitialized() {
        synchronized(initLock) {
            if (initialized) return
            YoutubeDL.getInstance().init(context)
            try {
                YoutubeDL.getInstance()
                    .updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
            } catch (e: Exception) {
                L.w(e, "yt-dlp update failed")
            }
            FFmpeg.getInstance().init(context)
            initialized = true
        }
    }
}
