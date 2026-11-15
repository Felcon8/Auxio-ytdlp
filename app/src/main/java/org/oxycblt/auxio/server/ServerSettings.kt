/*
 * Copyright (c) 2026 Auxio Project
 * ServerSettings.kt is part of Auxio (yt-dlp fork additions).
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
import javax.inject.Inject

/**
 * Connection settings of the Auxio server. They live in their own preferences file so they can be
 * excluded from backups (the password is stored here).
 */
class ServerSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The normalized base address, e.g. "http://192.168.1.10:8080", or "" if unset. */
    var url: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_URL, normalizeUrl(value)).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** Whether yt-dlp downloads should run on the server instead of on this phone. */
    var ytdlpOnServer: Boolean
        get() = prefs.getBoolean(KEY_YTDLP_ON_SERVER, false)
        set(value) = prefs.edit().putBoolean(KEY_YTDLP_ON_SERVER, value).apply()

    val isConfigured: Boolean
        get() = url.isNotEmpty() && password.isNotEmpty()

    companion object {
        const val FILE = "server_settings"
        private const val KEY_URL = "url"
        private const val KEY_PASSWORD = "password"
        private const val KEY_YTDLP_ON_SERVER = "ytdlp_on_server"

        /** Adds "http://" when no scheme was typed and drops trailing slashes. */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.contains("://")) u = "http://$u"
            return u
        }
    }
}
