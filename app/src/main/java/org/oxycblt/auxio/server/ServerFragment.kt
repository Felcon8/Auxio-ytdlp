/*
 * Copyright (c) 2026 Auxio Project
 * ServerFragment.kt is part of Auxio (yt-dlp fork additions).
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.databinding.FragmentServerBinding

/**
 * The Server tab of the home screen: connection settings and the two-way sync with the Auxio
 * server on the local network.
 */
@AndroidEntryPoint
class ServerFragment : Fragment() {
    private val model: ServerViewModel by activityViewModels()
    private var binding: FragmentServerBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val newBinding = FragmentServerBinding.inflate(inflater, container, false)
        binding = newBinding
        return newBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        if (savedInstanceState == null) {
            binding.serverUrl.setText(model.url)
            binding.serverPassword.setText(model.password)
        }

        binding.serverSave.setOnClickListener {
            model.saveAndTest(
                binding.serverUrl.text?.toString().orEmpty(),
                binding.serverPassword.text?.toString().orEmpty(),
            )
        }
        binding.serverSync.setOnClickListener {
            // Sync with whatever is typed in, so "type, tap Sync" works without saving first.
            val url = binding.serverUrl.text?.toString().orEmpty()
            val password = binding.serverPassword.text?.toString().orEmpty()
            if (url.isNotBlank() && password.isNotEmpty() && (url != model.url || password != model.password)) {
                model.saveAndSync(url, password)
            } else {
                model.startSync()
            }
        }
        binding.serverCancel.setOnClickListener { model.cancel() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun render(state: ServerState) {
        val binding = binding ?: return
        binding.serverSave.isEnabled = !state.busy
        binding.serverSync.isEnabled = !state.busy
        binding.serverCancel.isVisible = state.busy && state.syncing
        binding.serverProgress.isVisible = state.busy
        if (state.busy) {
            if (state.progress >= 0) {
                binding.serverProgress.isIndeterminate = false
                binding.serverProgress.setProgressCompat(state.progress, true)
            } else {
                binding.serverProgress.isIndeterminate = true
            }
        }
        binding.serverStatus.isVisible = state.message.isNotEmpty()
        binding.serverStatus.text = state.message
        binding.serverDetails.isVisible = state.details.isNotEmpty()
        binding.serverDetails.text = state.details.joinToString("\n")
    }
}
