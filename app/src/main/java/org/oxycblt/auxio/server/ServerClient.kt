/*
 * Copyright (c) 2026 Auxio Project
 * ServerClient.kt is part of Auxio (yt-dlp fork additions).
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

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** An error reported by (or while talking to) the server. [code] is the HTTP status, 0 if none. */
class ServerException(message: String, val code: Int = 0) : IOException(message)

/**
 * Minimal client for the Auxio server HTTP API. Everything here is blocking, call it from a
 * background thread. Uses plain [HttpURLConnection] so no extra library is needed.
 */
class ServerClient(baseUrl: String, private val password: String) {
    private val base = ServerSettings.normalizeUrl(baseUrl)

    /** A file identified by its SHA-256 and its path relative to the music folder. */
    data class FileRef(val hash: String, val path: String)

    data class Remote(val id: String, val path: String, val size: Long)

    data class Diff(
        val toUpload: List<FileRef>,
        val toPlace: List<FileRef>,
        val toDownload: List<Remote>,
        val deletedOnServer: List<FileRef>,
    )

    data class YtJob(
        val id: String,
        val status: String,
        val progress: Int,
        val error: String,
        val added: List<String>,
    )

    /** Verifies that the server is reachable and the password is accepted. */
    fun check() {
        getText("/api/health", auth = false)
        getText("/api/me")
    }

    fun diff(files: List<FileRef>): Diff {
        val array = JSONArray()
        for (f in files) array.put(JSONObject().put("hash", f.hash).put("path", f.path))
        val text = sendJson("POST", "/api/sync/diff", JSONObject().put("files", array), 180_000)
        val o = JSONObject(text)

        fun refs(name: String): List<FileRef> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return List(a.length()) {
                val x = a.getJSONObject(it)
                FileRef(x.getString("hash"), x.getString("path"))
            }
        }
        return Diff(refs("toUpload"), refs("toPlace"), remotes(o.optJSONArray("toDownload")), refs("deletedOnServer"))
    }

    /** Every file the server has (one entry per path). */
    fun manifest(): List<Remote> =
        remotes(JSONObject(getText("/api/manifest", 120_000)).optJSONArray("tracks"))

    /**
     * Uploads a file to [path] (relative to the music folder). [check] is called regularly so the
     * caller can abort by throwing, [onBytes] reports progress.
     */
    fun upload(
        path: String,
        mtimeSec: Long,
        input: InputStream,
        onBytes: (Int) -> Unit,
        check: () -> Unit,
    ) {
        val c = connect("PUT", "/api/upload?path=" + URLEncoder.encode(path, "UTF-8"), 180_000)
        try {
            c.doOutput = true
            c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("X-Mtime", mtimeSec.toString())
            c.outputStream.use { copy(input, it, onBytes, check) }
            c.checked().inputStream.close()
        } finally {
            c.disconnect()
        }
    }

    /** Makes the server copy a file it already has to another path. */
    fun place(hash: String, path: String) {
        sendJson("POST", "/api/place", JSONObject().put("id", hash).put("path", path), 120_000)
    }

    fun download(id: String, out: OutputStream, onBytes: (Int) -> Unit, check: () -> Unit) {
        val c = connect("GET", "/api/tracks/$id/download", 120_000)
        try {
            c.checked().inputStream.use { copy(it, out, onBytes, check) }
        } finally {
            c.disconnect()
        }
    }

    /** Queues a yt-dlp download on the server, returns the id of the job. */
    fun ytAdd(url: String, playlist: String?, allItems: Boolean): String {
        val body = JSONObject().put("url", url).put("format", "mp3").put("allItems", allItems)
        if (playlist != null) body.put("playlist", playlist)
        val ids = JSONObject(sendJson("POST", "/api/yt", body, 60_000)).getJSONArray("ids")
        return ids.getString(0)
    }

    fun ytJob(id: String): YtJob? {
        val jobs = JSONObject(getText("/api/yt")).optJSONArray("jobs") ?: return null
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            if (j.getString("id") != id) continue
            val added = j.optJSONArray("added")
            return YtJob(
                id,
                j.getString("status"),
                j.optDouble("progress", 0.0).toInt().coerceIn(0, 100),
                j.optString("error"),
                if (added == null) emptyList() else List(added.length()) { added.getString(it) },
            )
        }
        return null
    }

    fun ytCancel(id: String) {
        val c = connect("DELETE", "/api/yt/$id", 15_000)
        try {
            c.checked()
        } finally {
            c.disconnect()
        }
    }

    // --- plumbing ---

    private fun remotes(a: JSONArray?): List<Remote> =
        if (a == null) emptyList()
        else
            List(a.length()) {
                val x = a.getJSONObject(it)
                Remote(x.getString("id"), x.getString("path"), x.optLong("size"))
            }

    private fun connect(method: String, path: String, readTimeoutMs: Int, auth: Boolean = true) =
        run {
            if (base.isEmpty()) throw ServerException("No server address set")
            (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = readTimeoutMs
                useCaches = false
                if (auth) setRequestProperty("Authorization", "Bearer $password")
                setRequestProperty("Accept", "application/json")
            }
        }

    /** Throws a [ServerException] carrying the server's error message for non-2xx answers. */
    private fun HttpURLConnection.checked(): HttpURLConnection {
        val status = responseCode
        if (status in 200..299) return this
        val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val message =
            runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                .ifBlank { "HTTP $status" }
        disconnect()
        throw ServerException(message, status)
    }

    private fun getText(path: String, readTimeoutMs: Int = 30_000, auth: Boolean = true): String {
        val c = connect("GET", path, readTimeoutMs, auth)
        try {
            return c.checked().inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun sendJson(method: String, path: String, body: JSONObject, readTimeoutMs: Int): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val c = connect(method, path, readTimeoutMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            return c.checked().inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun copy(input: InputStream, out: OutputStream, onBytes: (Int) -> Unit, check: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            onBytes(n)
        }
    }
}
