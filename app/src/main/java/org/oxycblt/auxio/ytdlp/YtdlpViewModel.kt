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
import androidx.preference.PreferenceManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.BuildConfig
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

    private var job: Job? = null

    fun download(url: String, target: YtdlpTarget) {
        if (_state.value.busy) return
        _state.value = YtdlpState(busy = true, message = context.getString(R.string.ytdlp_starting))
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    runJob(url, target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e(e, "yt-dlp job failed")
                    _state.value = YtdlpState(message = describeFailure(e))
                }
            }
    }

    /** Cancels a running download and kills the yt-dlp process. */
    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        job?.cancel()
        _state.value = YtdlpState()
    }

    override fun onCleared() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        super.onCleared()
    }

    /** Turns a yt-dlp failure into a message the user can understand. */
    private fun describeFailure(e: Exception): String {
        val text = errorText(e)
        return when (classify(text)) {
            ErrorKind.DRM -> context.getString(R.string.ytdlp_err_drm)
            ErrorKind.PRIVATE -> context.getString(R.string.ytdlp_err_private)
            ErrorKind.UNAVAILABLE -> context.getString(R.string.ytdlp_err_unavailable)
            ErrorKind.UNSUPPORTED -> context.getString(R.string.ytdlp_err_unsupported)
            else ->
                context.getString(
                    R.string.ytdlp_failed,
                    // The last "ERROR:" line is the meaningful one, fall back to the last line.
                    text.lineSequence().lastOrNull { it.contains("ERROR", ignoreCase = true) }
                        ?: text.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: e.javaClass.simpleName,
                )
        }
    }

    private fun errorText(e: Throwable): String =
        generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")

    private enum class ErrorKind {
        DRM,
        PRIVATE,
        UNAVAILABLE,
        UNSUPPORTED,
        /** Worth retrying with another player client / without the JS runtime. */
        RETRYABLE,
        OTHER,
    }

    private fun classify(text: String): ErrorKind {
        // Judge only the error lines: verbose (-v) output is full of unrelated words.
        val t =
            text
                .lineSequence()
                .filter { it.contains("error", ignoreCase = true) }
                .joinToString("\n")
                .ifBlank { text }
                .lowercase()
        fun has(vararg k: String) = k.any { it in t }
        return when {
            has("drm") -> ErrorKind.DRM
            has("private video", "this video is private", "members-only", "members only",
                "join this channel") -> ErrorKind.PRIVATE
            has("video unavailable", "this video is not available", "has been removed",
                "not available in your country", "blocked it", "copyright", "account associated",
                "does not exist", "http error 404") -> ErrorKind.UNAVAILABLE
            has("unsupported url", "is not a valid url", "no suitable extractor") ->
                ErrorKind.UNSUPPORTED
            has("403", "forbidden", "challenge", "jsc", "javascript runtime", "format is not available",
                "sign in", "confirm you", "no such option", "unrecognized arguments",
                "permission denied", "exec format", "cannot run program", "error=13", "error=8",
                "quickjs", "unable to download video data", "no video formats") ->
                ErrorKind.RETRYABLE
            else -> ErrorKind.OTHER
        }
    }

    private suspend fun runJob(url: String, target: YtdlpTarget) {
        ensureInitialized()

        // 1. Download & convert with yt-dlp into a private scratch directory.
        val scratch = File(context.cacheDir, "ytdlp").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            downloadWithFallbacks(url, scratch)
        } catch (e: Throwable) {
            // Don't leave half-downloaded files behind on failure or cancellation.
            scratch.deleteRecursively()
            throw e
        }

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

    /** One way of running yt-dlp: a YouTube player client and whether QuickJS is enabled. */
    private data class Attempt(val client: String?, val quickJs: Boolean)

    /** The download method picked in Settings (see the ytdlp_method_* integers). */
    private fun downloadMethod(): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(
                context.getString(R.string.set_key_ytdlp_method),
                context.resources.getInteger(R.integer.ytdlp_method_new),
            )

    /** The bundled QuickJS binary, or null if it is missing or can't be executed. */
    private fun quickJsPath(): String? {
        val file = File(context.applicationInfo.nativeLibraryDir, QJS_LIB)
        return if (file.exists() && file.canExecute()) {
            file.absolutePath
        } else {
            L.w("QuickJS runtime unavailable at ${file.absolutePath} (exists=${file.exists()})")
            null
        }
    }

    /**
     * Runs yt-dlp, trying progressively different setups when YouTube refuses (403, JS challenge,
     * missing formats). Errors that retrying can't fix (DRM, private, unsupported...) stop
     * immediately. Throws the last error if nothing produced audio.
     */
    private fun downloadWithFallbacks(url: String, scratch: File) {
        val useOldMethod = downloadMethod() == context.resources.getInteger(R.integer.ytdlp_method_old)
        val qjs = if (useOldMethod) null else quickJsPath()
        val attempts =
            if (useOldMethod) {
                // The original behaviour, selectable in Settings: one run with fixed clients.
                listOf(Attempt(OLD_METHOD_CLIENTS, false))
            } else {
                // YouTube changes what works every few weeks, so first let yt-dlp pick its own
                // clients and only then fall back to specific ones. The no-QuickJS attempts come
                // last in case the bundled runtime can't run on this device.
                buildList {
                    if (qjs != null) PLAYER_CLIENTS_QJS.forEach { add(Attempt(it, true)) }
                    PLAYER_CLIENTS_PLAIN.forEach { add(Attempt(it, false)) }
                }
            }
        L.d("yt-dlp download method: ${if (useOldMethod) "old" else "new"}")

        var lastError: Exception? = null
        for (attempt in attempts) {
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
                    if (attempt.quickJs && qjs != null) {
                        // Only QuickJS: there is no deno/node on Android.
                        addOption("--no-js-runtimes")
                        addOption("--js-runtimes", "quickjs:$qjs")
                        // Fetch the challenge solver scripts if this yt-dlp build doesn't ship
                        // them. Old builds reject the flag, which falls through to the plain
                        // attempts below.
                        addOption("--remote-components", "ejs:github")
                    }
                    if (attempt.client != null) {
                        addOption("--extractor-args", "youtube:player_client=${attempt.client}")
                    }
                    if (BuildConfig.DEBUG) addOption("-v")
                    // A link to a single video that merely carries a playlist id should not pull
                    // in the whole playlist. Pure playlist links still download everything.
                    if (url.contains("watch?v=") || url.contains("youtu.be/")) {
                        addOption("--no-playlist")
                    }
                }
            L.d("yt-dlp attempt: client=${attempt.client} quickjs=${attempt.quickJs}")
            try {
                val response =
                    YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, _ ->
                        _state.value =
                            YtdlpState(
                                busy = true,
                                message = context.getString(R.string.ytdlp_downloading),
                                progress = progress.toInt().coerceIn(0, 100),
                            )
                    }
                logDiagnostics(response.err)
                lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val text = errorText(e)
                L.w("yt-dlp attempt failed (client=${attempt.client}, quickjs=${attempt.quickJs}): $text")
                logDiagnostics(text)
                // yt-dlp reports a failure if *any* playlist entry failed. Keep what we got.
                if (audioFiles(scratch).isNotEmpty()) break
                if (classify(text) != ErrorKind.RETRYABLE) break
            }
            if (audioFiles(scratch).isNotEmpty()) break
        }
        if (audioFiles(scratch).isEmpty()) lastError?.let { throw it }
    }

    /** In debug builds, log the interesting parts of yt-dlp's verbose output. */
    private fun logDiagnostics(output: String?) {
        if (!BuildConfig.DEBUG || output.isNullOrBlank()) return
        output
            .lineSequence()
            .filter {
                it.contains("yt-dlp version", ignoreCase = true) ||
                    it.contains("JS runtimes") ||
                    it.contains("[jsc") ||
                    it.contains("player") ||
                    it.startsWith("WARNING") ||
                    it.startsWith("ERROR")
            }
            .forEach { L.d("yt-dlp: $it") }
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

        private const val QJS_LIB = "libqjs.so"
        private const val OLD_METHOD_CLIENTS = "android_vr,web_safari"

        // null = let yt-dlp choose; the rest are fallbacks tried in order.
        private val PLAYER_CLIENTS_QJS = listOf(null, "tv", "android_vr")
        private val PLAYER_CLIENTS_PLAIN = listOf(null, "android_vr", "tv", "web_safari")
        private const val INDEX_TIMEOUT_MS = 180_000L
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav")

        @Volatile private var initialized = false
        @Volatile private var updated = false

        private val initLock = Any()
    }

    private fun ensureInitialized() {
        synchronized(initLock) {
            if (!initialized) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                initialized = true
            }
            // The update needs network, so a failure must not block downloads. Retry it on the next
            // download until it has worked once.
            if (!updated) {
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
                    updated = true
                } catch (e: Exception) {
                    L.w(e, "yt-dlp update failed, will retry on the next download")
                }
            }
        }
    }
}
