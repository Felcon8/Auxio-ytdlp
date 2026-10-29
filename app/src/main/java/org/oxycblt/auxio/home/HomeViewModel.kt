/*
 * Copyright (c) 2021 Auxio Project
 * HomeViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * The ViewModel for managing the tab data and lists of the home view.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val listSettings: ListSettings,
    private val playbackSettings: PlaybackSettings,
    homeGeneratorFactory: HomeGenerator.Factory,
) : ViewModel(), HomeGenerator.Invalidator {
    /** A list of [Song]s, sorted by the preferred [Sort], to be shown in the home view. */
    val songList: StateFlow<List<Song>>
        field = MutableStateFlow(listOf<Song>())

    /** Instructions for how to update [songList] in the UI. */
    val songInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [songList]. */
    val songSort: Sort
        get() = listSettings.songSort

    /** The [PlaySong] instructions to use when playing a [Song]. */
    val playWith
        get() = playbackSettings.playInListWith

    /** A list of [Album]s, sorted by the preferred [Sort], to be shown in the home view. */
    val albumList: StateFlow<List<Album>>
        field = MutableStateFlow(listOf<Album>())

    /** Instructions for how to update [albumList] in the UI. */
    val albumInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [albumList]. */
    val albumSort: Sort
        get() = listSettings.albumSort

    private val _artistList = MutableStateFlow(listOf<Artist>())
    /**
     * A list of [Artist]s, sorted by the preferred [Sort], to be shown in the home view. Note that
     * if "Hide collaborators" is on, this list will not include collaborator [Artist]s.
     */
    val artistList: MutableStateFlow<List<Artist>>
        get() = _artistList

    /** Instructions for how to update [artistList] in the UI. */
    val artistInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [artistList]. */
    val artistSort: Sort
        get() = listSettings.artistSort

    /** A list of [Genre]s, sorted by the preferred [Sort], to be shown in the home view. */
    val genreList: StateFlow<List<Genre>>
        field = MutableStateFlow(listOf<Genre>())

    /** Instructions for how to update [genreList] in the UI. */
    val genreInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [genreList]. */
    val genreSort: Sort
        get() = listSettings.genreSort

    /** A list of [Playlist]s, sorted by the preferred [Sort], to be shown in the home view. */
    val playlistList: StateFlow<List<Playlist>>
        field = MutableStateFlow(listOf<Playlist>())

    val empty: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Instructions for how to update [genreList] in the UI. */
    val playlistInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [genreList]. */
    val playlistSort: Sort
        get() = listSettings.playlistSort

    private val homeGenerator = homeGeneratorFactory.create(this)

    /**
     * A list of [MusicType] corresponding to the current [Tab] configuration, excluding invisible
     * [Tab]s.
     */
    var currentTabTypes = homeGenerator.tabs()
        private set

    private val _currentTabType = MutableStateFlow(currentTabTypes[0])
    /** The [MusicType] of the currently shown [Tab]. */
    val currentTabType: StateFlow<MusicType> = _currentTabType

    /**
     * A marker to re-create all library tabs, usually initiated by a settings change. When this
     * flag is true, all tabs (and their respective ViewPager2 fragments) will be re-created from
     * scratch.
     */
    val recreateTabs: Event<Unit>
        field = MutableEvent<Unit>()

    private val _isFastScrolling = MutableStateFlow(false)
    /** A marker for whether the user is fast-scrolling in the home view or not. */
    val isFastScrolling: StateFlow<Boolean> = _isFastScrolling

    val showOuter: Event<Outer>
        field = MutableEvent<Outer>()

    val chooseMusicLocations: Event<Unit>
        field = MutableEvent<Unit>()

    init {
        homeGenerator.attach()
    }

    override fun onCleared() {
        homeGenerator.release()
    }

    override fun invalidateEmpty() {
        empty.value = homeGenerator.empty()
    }

    override fun invalidateMusic(type: MusicType, instructions: UpdateInstructions) {
        when (type) {
            MusicType.SONGS -> {
                songInstructions.put(instructions)
                songList.value = homeGenerator.songs()
            }
            MusicType.ALBUMS -> {
                albumInstructions.put(instructions)
                albumList.value = homeGenerator.albums()
            }
            MusicType.ARTISTS -> {
                artistInstructions.put(instructions)
                _artistList.value = homeGenerator.artists()
            }
            MusicType.GENRES -> {
                genreInstructions.put(instructions)
                genreList.value = homeGenerator.genres()
            }
            MusicType.PLAYLISTS -> {
                playlistInstructions.put(instructions)
                playlistList.value = homeGenerator.playlists()
            }
            MusicType.YTDLP -> {
                // Nothing to invalidate, the yt-dlp tab manages its own state.
            }
        }
    }

    override fun invalidateTabs() {
        currentTabTypes = homeGenerator.tabs()
        recreateTabs.put(Unit)
    }

    /**
     * Apply a new [Sort] to [songList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applySongSort(sort: Sort) {
        listSettings.songSort = sort
    }

    /**
     * Apply a new [Sort] to [albumList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyAlbumSort(sort: Sort) {
        listSettings.albumSort = sort
    }

    /**
     * Apply a new [Sort] to [artistList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyArtistSort(sort: Sort) {
        listSettings.artistSort = sort
    }

    /**
     * Apply a new [Sort] to [genreList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyGenreSort(sort: Sort) {
        listSettings.genreSort = sort
    }

    /**
     * Apply a new [Sort] to [playlistList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyPlaylistSort(sort: Sort) {
        listSettings.playlistSort = sort
    }

    /**
     * Update [currentTabType] to reflect a new ViewPager2 position
     *
     * @param pagerPos The new position of the ViewPager2 instance.
     */
    fun synchronizeTabPosition(pagerPos: Int) {
        L.d("Updating current tab to ${currentTabTypes[pagerPos]}")
        _currentTabType.value = currentTabTypes[pagerPos]
    }

    /**
     * Update whether the user is fast scrolling or not in the home view.
     *
     * @param isFastScrolling true if the user is currently fast scrolling, false otherwise.
     */
    fun setFastScrolling(isFastScrolling: Boolean) {
        L.d("Updating fast scrolling state: $isFastScrolling")
        _isFastScrolling.value = isFastScrolling
    }

    fun startChooseMusicLocations() {
        chooseMusicLocations.put(Unit)
    }

    fun showSettings() {
        showOuter.put(Outer.Settings)
    }

    fun showAbout() {
        showOuter.put(Outer.About)
    }
}

sealed interface Outer {
    data object Settings : Outer

    data object About : Outer
}
