/*
 * Copyright (c) 2026 Auxio Project
 * YtdlpFragment.kt is part of Auxio (yt-dlp fork additions).
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

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentYtdlpBinding
import org.oxycblt.auxio.music.resolve

/**
 * The yt-dlp tab of the home screen. Paste a link, pick a playlist, and the audio is downloaded
 * and added to that playlist.
 */
@AndroidEntryPoint
class YtdlpFragment : Fragment() {
    private val model: YtdlpViewModel by activityViewModels()
    private var binding: FragmentYtdlpBinding? = null
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterPermission
            afterPermission = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(requireContext(), R.string.err_ytdlp_no_permission, Toast.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val newBinding = FragmentYtdlpBinding.inflate(inflater, container, false)
        binding = newBinding
        return newBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        binding.ytdlpPaste.setOnClickListener {
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text =
                clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(requireContext())
                    ?.toString()
                    ?.trim()
            if (!text.isNullOrEmpty()) {
                binding.ytdlpUrl.setText(text)
                binding.ytdlpUrl.setSelection(text.length)
            }
        }

        binding.ytdlpDownload.setOnClickListener { onDownloadClicked(binding) }

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

    private fun render(state: YtdlpState) {
        val binding = binding ?: return
        binding.ytdlpDownload.isEnabled = !state.busy
        binding.ytdlpProgress.isVisible = state.busy
        if (state.busy) {
            if (state.progress >= 0) {
                binding.ytdlpProgress.isIndeterminate = false
                binding.ytdlpProgress.setProgressCompat(state.progress, true)
            } else {
                binding.ytdlpProgress.isIndeterminate = true
            }
        }
        binding.ytdlpStatus.isVisible = state.message.isNotEmpty()
        binding.ytdlpStatus.text = state.message
    }

    private fun onDownloadClicked(binding: FragmentYtdlpBinding) {
        val url = binding.ytdlpUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || !Patterns.WEB_URL.matcher(url).matches()) {
            binding.ytdlpUrlContainer.error = getString(R.string.err_ytdlp_bad_link)
            return
        }
        binding.ytdlpUrlContainer.error = null

        val proceed = { askPlaylist(url) }
        // Before Android 10 we write straight into the public Music folder.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = proceed
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            proceed()
        }
    }

    /** Ask which playlist the downloaded songs should be added to (or skip the question). */
    private fun askPlaylist(url: String) {
        val playlists = model.playlists()
        val labels =
            (listOf(
                        getString(R.string.lbl_ytdlp_skip_playlist),
                        getString(R.string.lbl_ytdlp_new_playlist),
                    ) + playlists.map { it.name.resolve(requireContext()) })
                .toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_ytdlp_pick_playlist)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> model.download(url, YtdlpTarget.None)
                    1 -> askNewPlaylistName(url)
                    else -> model.download(url, YtdlpTarget.Existing(playlists[which - 2]))
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun askNewPlaylistName(url: String) {
        val content = layoutInflater.inflate(R.layout.dialog_playlist_name, null)
        val input = content.findViewById<TextInputEditText>(R.id.playlist_name)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_ytdlp_new_playlist_title)
            .setView(content)
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    // Reuse a playlist that already has this name instead of creating a twin.
                    val existing = model.findPlaylistNamed(name)
                    model.download(
                        url,
                        if (existing != null) YtdlpTarget.Existing(existing)
                        else YtdlpTarget.New(name),
                    )
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }
}
