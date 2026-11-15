/*
 * Copyright (c) 2026 Auxio Project
 * ServerSync.kt is part of Auxio (yt-dlp fork additions).
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

package org.oxycblt.auxio.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.PlaylistFolders
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** Why a sync could not even start. */
class SyncProblem(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        /** No music folder has been picked in Settings. */
        NO_ROOT,
        /** The library has not finished loading. */
        NO_LIBRARY,
    }
}

/**
 * Two-way sync between the songs of this phone and the Auxio server.
 *
 * Files are identified by the SHA-256 of their content and compared as (hash, path) pairs, where
 * the path is relative to the main music folder. Playlist folders are folders, so they are synced
 * like everything else:
 *  - songs the server does not have are uploaded,
 *  - songs the server has with the same content but at another path are copied on the server,
 *  - songs the phone does not have are downloaded into the same path.
 *
 * Nothing is ever deleted by a sync, on either side.
 */
class ServerSync
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playlistFolders: PlaylistFolders,
) {
    enum class Phase {
        SCANNING,
        HASHING,
        COMPARING,
        UPLOADING,
        PLACING,
        DOWNLOADING,
        INDEXING,
    }

    data class Result(
        val uploaded: Int,
        val placed: Int,
        val downloaded: Int,
        /** Files that were deleted on the server but are still on this phone. */
        val deletedOnServer: Int,
        /** Same path, different content on phone and server: left untouched on both sides. */
        val conflicts: Int,
        val failed: Int,
        val problems: List<String>,
    ) {
        val nothingHappened: Boolean
            get() = uploaded + placed + downloaded + deletedOnServer + conflicts + failed == 0
    }

    private class Local(val rel: String, val song: Song)

    /** [report] is called from a background thread with the phase and its progress. */
    suspend fun sync(
        client: ServerClient,
        report: (phase: Phase, done: Int, total: Int) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val check: () -> Unit = { job?.ensureActive() }
            val problems = mutableListOf<String>()
            fun problem(text: String) {
                if (problems.size < MAX_PROBLEMS) problems += text
            }

            // 1. What does this phone have?
            report(Phase.SCANNING, 0, -1)
            val locals = localFiles()
            val byPath = locals.associateBy { it.rel }

            // 2. Hash everything (cached by path + size + modification time).
            val hashes = hashAll(locals, report, check)
            // Files that could not be read are simply left out of the comparison.
            val localRefs = locals.mapNotNull { l ->
                hashes[l.rel]?.let { ServerClient.FileRef(it, l.rel) }
            }

            // 3. Ask the server what differs.
            report(Phase.COMPARING, 0, -1)
            val diff = client.diff(localRefs)

            // The same path with different content on both sides can't be merged without renaming
            // one of them, which would make the names drift apart on every sync. Leave both alone.
            val serverPaths = diff.toDownload.map { it.path }.toSet()
            val conflictPaths =
                (diff.toUpload + diff.toPlace).map { it.path }.filter { it in serverPaths }.toSet()
            val toUpload = diff.toUpload.filter { it.path !in conflictPaths }
            val toPlace = diff.toPlace.filter { it.path !in conflictPaths }
            val toDownload = diff.toDownload.filter { it.path !in conflictPaths }
            for (path in conflictPaths.take(5)) problem("Different file with the same name: $path")

            var uploaded = 0
            var placed = 0
            var downloaded = 0
            var failed = 0

            // 4. Upload what the server has never seen.
            for ((i, ref) in toUpload.withIndex()) {
                check()
                report(Phase.UPLOADING, i, toUpload.size)
                val local = byPath[ref.path]
                if (local == null) {
                    failed++
                    continue
                }
                try {
                    val input =
                        context.contentResolver.openInputStream(local.song.uri)
                            ?: throw IOException("Could not open the file")
                    input.use {
                        client.upload(ref.path, local.song.modifiedMs / 1000, it, {}, check)
                    }
                    uploaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Upload of ${ref.path} failed")
                    failed++
                    problem("${ref.path}: ${e.message}")
                }
            }

            // 5. Songs whose content the server has (or just received) but at another path.
            for ((i, ref) in toPlace.withIndex()) {
                check()
                report(Phase.PLACING, i, toPlace.size)
                try {
                    client.place(ref.hash, ref.path)
                    placed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Placing ${ref.path} failed")
                    failed++
                    problem("${ref.path}: ${e.message}")
                }
            }

            // 6. Download what this phone doesn't have.
            for ((i, remote) in toDownload.withIndex()) {
                check()
                report(Phase.DOWNLOADING, i, toDownload.size)
                val folder = remote.path.substringBeforeLast('/', "")
                val name = remote.path.substringAfterLast('/')
                try {
                    playlistFolders.save(folder, name) { out ->
                        client.download(remote.id, out, {}, check)
                    }
                    downloaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Download of ${remote.path} failed")
                    failed++
                    problem("${remote.path}: ${e.message}")
                }
            }

            // 7. Let Auxio pick up the new files (and turn new folders into playlists).
            if (downloaded > 0) {
                report(Phase.INDEXING, 0, -1)
                musicRepository.requestIndex(true)
            }

            Result(
                uploaded,
                placed,
                downloaded,
                diff.deletedOnServer.size,
                conflictPaths.size,
                failed,
                problems,
            )
        }

    /** The songs inside the main music folder, with their path relative to it. */
    private fun localFiles(): List<Local> {
        val root = playlistFolders.rootComponents() ?: throw SyncProblem(SyncProblem.Kind.NO_ROOT)
        val library = musicRepository.library ?: throw SyncProblem(SyncProblem.Kind.NO_LIBRARY)
        val out = mutableListOf<Local>()
        val seen = HashSet<String>()
        for (song in library.songs) {
            val parts = song.path.components.components
            if (parts.size <= root.size || parts.subList(0, root.size) != root) continue
            val rel = parts.drop(root.size).joinToString("/")
            if (seen.add(rel)) out += Local(rel, song)
        }
        return out
    }

    private fun hashAll(
        locals: List<Local>,
        report: (Phase, Int, Int) -> Unit,
        check: () -> Unit,
    ): Map<String, String> {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        val cache =
            try {
                if (cacheFile.exists()) JSONObject(cacheFile.readText()) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
        val hashes = HashMap<String, String>(locals.size)
        val used = JSONObject()
        var finished = false
        try {
            for ((i, local) in locals.withIndex()) {
                check()
                report(Phase.HASHING, i, locals.size)
                val key = "${local.rel}|${local.song.size}|${local.song.modifiedMs}"
                val cached = cache.optString(key)
                val hash = if (cached.length == 64) cached else sha256(local.song, check)
                if (hash == null) continue
                hashes[local.rel] = hash
                cache.put(key, hash)
                used.put(key, hash)
            }
            finished = true
        } finally {
            // Keep the progress when cancelled; drop entries of deleted/changed files when done.
            try {
                cacheFile.writeText((if (finished) used else cache).toString())
            } catch (e: Exception) {
                L.w(e, "Could not save the hash cache")
            }
        }
        return hashes
    }

    /** SHA-256 of a song as lowercase hex, or null if the file can't be read. */
    private fun sha256(song: Song, check: () -> Unit): String? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(song.uri) ?: return null
            input.use {
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check()
                    val n = it.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w(e, "Could not hash ${song.path}")
            null
        }

    companion object {
        private const val CACHE_FILE = "server_hash_cache.json"
        private const val MAX_PROBLEMS = 20
    }
}
