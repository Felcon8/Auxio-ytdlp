/*
 * Copyright (c) 2026 Auxio Project
 * ServerViewModel.kt is part of Auxio (yt-dlp fork additions).
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import timber.log.Timber as L

/**
 * UI state of the Server tab.
 *
 * @param busy Whether a connection test or a sync is running.
 * @param syncing Whether it is a sync (which can be cancelled).
 * @param progress 0..100, or -1 if unknown.
 * @param details Extra lines under the status, e.g. the files that failed.
 */
data class ServerState(
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val message: String = "",
    val progress: Int = -1,
    val details: List<String> = emptyList(),
)

@HiltViewModel
class ServerViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: ServerSettings,
    private val sync: ServerSync,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state

    private var job: Job? = null

    val url: String
        get() = settings.url

    val password: String
        get() = settings.password

    /** Stores the connection settings and checks that the server accepts them. */
    fun saveAndTest(url: String, password: String) {
        if (_state.value.busy) return
        val normalized = ServerSettings.normalizeUrl(url)
        if (normalized.isEmpty() || password.isEmpty()) {
            _state.value = ServerState(message = context.getString(R.string.server_not_configured))
            return
        }
        settings.url = normalized
        settings.password = password
        _state.value =
            ServerState(busy = true, message = context.getString(R.string.server_connecting))
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    ServerClient(normalized, password).check()
                    _state.value =
                        ServerState(message = context.getString(R.string.server_connected))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ServerState(message = describe(e))
                }
            }
    }

    /** Stores the settings and starts a sync right away. */
    fun saveAndSync(url: String, password: String) {
        // ServerSettings normalises the address when storing it.
        settings.url = url
        settings.password = password
        startSync()
    }

    fun startSync() {
        if (_state.value.busy) return
        if (!settings.isConfigured) {
            _state.value = ServerState(message = context.getString(R.string.server_not_configured))
            return
        }
        val client = ServerClient(settings.url, settings.password)
        _state.value =
            ServerState(
                busy = true,
                syncing = true,
                message = context.getString(R.string.server_scanning),
            )
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        sync.sync(client) { phase, done, total ->
                            _state.update {
                                it.copy(
                                    message = phaseText(phase, done, total),
                                    progress = if (total > 0) done * 100 / total else -1,
                                )
                            }
                        }
                    _state.value =
                        ServerState(message = summary(result), details = result.problems)
                } catch (e: CancellationException) {
                    _state.value =
                        ServerState(message = context.getString(R.string.server_cancelled))
                    throw e
                } catch (e: Exception) {
                    L.e(e, "Server sync failed")
                    _state.value = ServerState(message = describe(e))
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun phaseText(phase: ServerSync.Phase, done: Int, total: Int): String =
        when (phase) {
            ServerSync.Phase.SCANNING -> context.getString(R.string.server_scanning)
            ServerSync.Phase.HASHING ->
                context.getString(R.string.server_hashing, done + 1, total)
            ServerSync.Phase.COMPARING -> context.getString(R.string.server_comparing)
            ServerSync.Phase.UPLOADING ->
                context.getString(R.string.server_uploading, done + 1, total)
            ServerSync.Phase.PLACING -> context.getString(R.string.server_placing, done + 1, total)
            ServerSync.Phase.DOWNLOADING ->
                context.getString(R.string.server_downloading, done + 1, total)
            ServerSync.Phase.INDEXING -> context.getString(R.string.server_indexing)
        }

    private fun summary(r: ServerSync.Result): String {
        val lines = mutableListOf<String>()
        lines +=
            if (r.nothingHappened) {
                context.getString(R.string.server_up_to_date)
            } else {
                context.getString(R.string.server_done, r.uploaded, r.placed, r.downloaded)
            }
        if (r.deletedOnServer > 0) {
            lines += context.getString(R.string.server_done_deleted, r.deletedOnServer)
        }
        if (r.conflicts > 0) lines += context.getString(R.string.server_done_conflicts, r.conflicts)
        if (r.failed > 0) lines += context.getString(R.string.server_done_failed, r.failed)
        return lines.joinToString("\n")
    }

    private fun describe(e: Exception): String =
        when {
            e is SyncProblem && e.kind == SyncProblem.Kind.NO_ROOT ->
                context.getString(R.string.server_err_no_root)
            e is SyncProblem -> context.getString(R.string.server_err_no_library)
            e is ServerException && e.code == 401 -> context.getString(R.string.server_err_password)
            else ->
                context.getString(
                    R.string.server_err_failed,
                    e.message ?: e.javaClass.simpleName,
                )
        }
}
