/*
 * Copyright (c) 2021 Auxio Project
 * Tab.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.tabs

import org.oxycblt.auxio.music.MusicType
import timber.log.Timber as L

/**
 * A representation of a library tab suitable for configuration.
 *
 * @param type The type of list in the home view this instance corresponds to.
 * @author Alexander Capehart (OxygenCobalt)
 */
sealed class Tab(open val type: MusicType) {
    /**
     * A visible tab. This will be visible in the home and tab configuration views.
     *
     * @param type The type of list in the home view this instance corresponds to.
     */
    data class Visible(override val type: MusicType) : Tab(type)

    /**
     * A visible tab. This will be visible in the tab configuration view, but not in the home view.
     *
     * @param type The type of list in the home view this instance corresponds to.
     */
    data class Invisible(override val type: MusicType) : Tab(type)

    companion object {
        // Like other IO-bound datatypes in Auxio, tabs are stored in a binary format. However, tabs
        // cannot be serialized on their own. Instead, they are saved as a sequence of tabs as shown
        // below:
        //
        // 0bTAB1_TAB2_TAB3_TAB4
        //
        // Where TABN is a chunk representing a tab at position N.
        // Each chunk in a sequence is represented as:
        //
        // VTTT
        //
        // Where V is a bit representing the visibility and T is a 3-bit integer representing the
        // MusicMode for this tab.

        /** The maximum index that a well-formed tab sequence should be. */
        const val MAX_SEQUENCE_IDX = 4

        // Fork: the Server tab made it 5 tabs. Sequences saved by older versions have only 4
        // chunks (16 bits). To tell them apart, current sequences start with a marker chunk of
        // 0b1111 in front of the tabs (mode 7 does not exist, so it never parses as a tab).
        private const val LEGACY_MAX_SEQUENCE_IDX = 3
        private const val MARKER = 0b1111
        private const val MARKER_SHIFT = (MAX_SEQUENCE_IDX + 1) * 4

        /**
         * The default tab sequence, in integer form. This fork shows "Songs", "Playlists" and
         * "yt-dlp". Albums is kept last and invisible (it is also filtered out in HomeSettings).
         */
        // Chunks: marker, Songs, Playlists, yt-dlp, Server, Albums (hidden).
        const val SEQUENCE_DEFAULT = 0b1111_1000_1010_1011_1100_0001

        /** Maps between the integer code in the tab sequence and it's [MusicType]. */
        private val MODE_TABLE =
            arrayOf(
                MusicType.SONGS,
                MusicType.ALBUMS,
                MusicType.PLAYLISTS,
                MusicType.YTDLP,
                MusicType.SERVER,
            )

        /**
         * Convert an array of [Tab]s into it's integer representation.
         *
         * @param tabs The array of [Tab]s to convert
         * @return An integer representation of the [Tab] array
         */
        fun toIntCode(tabs: Array<Tab>): Int {
            // Like when deserializing, make sure there are no duplicate tabs for whatever reason.
            val distinct = tabs.distinctBy { it.type }
            if (tabs.size != distinct.size) {
                L.w(
                    "Tab sequences should not have duplicates [old: ${tabs.size} new: ${distinct.size}]"
                )
            }

            var sequence = MARKER.shl(MARKER_SHIFT)
            var shift = MAX_SEQUENCE_IDX * 4
            for (tab in distinct) {
                val bin =
                    when (tab) {
                        is Visible -> 1.shl(3) or MODE_TABLE.indexOf(tab.type)
                        is Invisible -> MODE_TABLE.indexOf(tab.type)
                    }

                sequence = sequence or bin.shl(shift)
                shift -= 4
            }

            return sequence
        }

        /**
         * Convert a [Tab] integer representation into it's corresponding array of [Tab]s.
         *
         * @param intCode The integer representation of the [Tab]s.
         * @return An array of [Tab]s corresponding to the sequence.
         */
        fun fromIntCode(intCode: Int): Array<Tab>? {
            val tabs = mutableListOf<Tab>()
            val isCurrent = (intCode.ushr(MARKER_SHIFT) and 0b1111) == MARKER
            val maxIdx = if (isCurrent) MAX_SEQUENCE_IDX else LEGACY_MAX_SEQUENCE_IDX

            // Try to parse a mode for each chunk in the sequence.
            // If we can't parse one, just skip it.
            for (shift in (0..maxIdx * 4).reversed() step 4) {
                val chunk = intCode.shr(shift) and 0b1111
                val mode = MODE_TABLE.getOrNull(chunk and 7) ?: continue

                // Figure out the visibility
                tabs +=
                    if (chunk and 1.shl(3) != 0) {
                        Visible(mode)
                    } else {
                        Invisible(mode)
                    }
            }

            // Make sure there are no duplicate tabs
            var distinct: List<Tab> = tabs.distinctBy { it.type }
            if (tabs.size != distinct.size) {
                L.w(
                    "Tab sequences should not have duplicates [old: ${tabs.size} new: ${distinct.size}]"
                )
            }

            // Sequences saved before the Server tab existed get it appended (visible).
            if (!isCurrent && distinct.none { it.type == MusicType.SERVER }) {
                distinct = distinct + Visible(MusicType.SERVER)
            }

            // The Albums tab is hidden by HomeSettings, so a saved layout may not mention every
            // tab. Anything missing is added back as invisible instead of discarding the layout.
            val missing = MODE_TABLE.filter { mode -> distinct.none { it.type == mode } }
            distinct = distinct + missing.map<MusicType, Tab> { Invisible(it) }

            // For safety, return null if we have an empty tab array.
            if (distinct.isEmpty()) {
                L.e("Sequence was empty, which is invalid")
                return null
            }

            return distinct.toTypedArray()
        }
    }
}
