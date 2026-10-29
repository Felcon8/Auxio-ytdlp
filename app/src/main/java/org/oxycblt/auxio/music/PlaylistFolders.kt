/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistFolders.kt is part of Auxio (yt-dlp fork additions).
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

package org.oxycblt.auxio.music

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import org.oxycblt.auxio.music.locations.LocationMode
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Fork feature: every playlist gets a real folder inside the main music folder (the first folder
 * the user picked in Settings -> Music folders, or the public Music folder when MediaStore mode is
 * used). Songs that are added to a playlist are put into that folder.
 *
 * All functions here do blocking I/O, call them from a background thread.
 */
class PlaylistFolders
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicSettings: MusicSettings,
) {
    /** The tree [Uri] of the main music folder, or null if the public Music folder is used. */
    private fun mainTree(): Uri? =
        if (musicSettings.locationMode == LocationMode.SAF) {
            try {
                musicSettings.safQuery.source.firstOrNull()?.uri
            } catch (e: Exception) {
                L.e(e, "Could not resolve the main music folder")
                null
            }
        } else {
            null
        }

    /** Create the folder for the playlist (only possible right away when a folder was picked). */
    fun ensureFolder(folder: String) {
        val tree = mainTree() ?: return
        try {
            ensureDir(tree, folder)
        } catch (e: Exception) {
            L.e(e, "Could not create playlist folder $folder")
        }
    }

    /** Copy the files of [songs] into the folder of the playlist called [playlistName]. */
    fun copySongs(playlistName: String, songs: List<Song>) {
        val folder = folderName(playlistName)
        ensureFolder(folder)
        for (song in songs) {
            val fileName = song.path.name ?: continue
            // Songs that already live in the playlist folder (e.g. fresh yt-dlp downloads).
            if (song.path.directory.name == folder) continue
            try {
                if (exists(folder, fileName)) continue
                save(folder, fileName) { out ->
                    context.contentResolver.openInputStream(song.uri)?.use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                L.e(e, "Could not copy $fileName to $folder")
            }
        }
    }

    /** Whether the folder already has a file with this name. */
    fun exists(folder: String, fileName: String): Boolean {
        val tree = mainTree()
        if (tree != null) {
            val dir = findChild(tree, treeRoot(tree), folder, true) ?: return false
            return findChild(tree, dir, fileName, false) != null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver
                .query(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                    arrayOf(fileName, "${Environment.DIRECTORY_MUSIC}/$folder/"),
                    null,
                )
                ?.use {
                    return it.count > 0
                }
            return false
        }
        return File(publicDir(folder), fileName).exists()
    }

    /**
     * Create a file in the folder and fill it using [write].
     *
     * @return The file name that was actually used (the system may rename on collisions).
     */
    fun save(folder: String, fileName: String, write: (OutputStream) -> Unit): String {
        val mime = mimeFor(fileName)
        val resolver = context.contentResolver
        val tree = mainTree()
        if (tree != null) {
            val dir = checkNotNull(ensureDir(tree, folder)) { "Could not create folder $folder" }
            val file =
                checkNotNull(DocumentsContract.createDocument(resolver, dir, mime, fileName)) {
                    "Could not create $fileName"
                }
            checkNotNull(resolver.openOutputStream(file)) { "Could not open $fileName" }
                .use(write)
            return queryName(file) ?: fileName
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values =
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/$folder",
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore insert failed" }
            checkNotNull(resolver.openOutputStream(uri)) { "Could not open $fileName" }.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            resolver
                .query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0) ?: fileName
                    }
                }
            return fileName
        }

        val dest = File(publicDir(folder).apply { mkdirs() }, fileName)
        dest.outputStream().use(write)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return dest.name
    }

    // --- Storage Access Framework helpers ---

    private fun treeRoot(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun ensureDir(tree: Uri, folder: String): Uri? {
        val root = treeRoot(tree)
        return findChild(tree, root, folder, true)
            ?: DocumentsContract.createDocument(
                context.contentResolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folder,
            )
    }

    private fun findChild(tree: Uri, parent: Uri, name: String, directory: Boolean): Uri? {
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(parent),
            )
        context.contentResolver
            .query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (cursor.getString(1) == name && isDir == directory) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            cursor.getString(0),
                        )
                    }
                }
            }
        return null
    }

    private fun queryName(document: Uri): String? =
        context.contentResolver
            .query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    @Suppress("DEPRECATION")
    private fun publicDir(folder: String) =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), folder)

    private fun mimeFor(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

    companion object {
        /** Turn a playlist name into something that is safe to use as a folder name. */
        fun folderName(playlistName: String): String {
            val cleaned =
                playlistName
                    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                    .trim()
                    .trimEnd('.')
                    .take(80)
            return cleaned.ifEmpty { "Playlist" }
        }
    }
}
