/*
 * Copyright (c) 2025 Arnaldo Alfredo.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.arnnalddo.sappyn

import androidx.annotation.Keep
import androidx.media3.common.MediaItem

/**
 * Manages a playlist of [MediaItem] objects, providing functionality to navigate through the
 * playlist (next, previous, current), set the playlist, and toggle looping.
 *
 * This class interacts with a [MediaRepository] to persist and retrieve the looping state.
 * It also notifies a listener via [onLoopChanged] when the looping state changes.
 *
 * @property repository The [MediaRepository] used for storing and retrieving loop state.
 * @param onLoopChanged A callback function invoked when the looping state ([isLoopingEnabled]) is changed.
 *                      It receives the new loop state as a Boolean.
 */
class PlaylistManager(
    private val repository: MediaRepository,
    private val onLoopChanged: (Boolean) -> Unit
) {

    private val playlist = mutableListOf<MediaItem>()
    private var currentIndex = -1
    var isLoopingEnabled: Boolean = repository.getLoopState()
        set(value) {
            field = value
            repository.saveLoopState(value)
            onLoopChanged(value)
        }

    fun setPlaylist(items: List<MediaItem>, startIndex: Int = 0) {
        playlist.clear()
        playlist.addAll(items)
        currentIndex = startIndex.coerceIn(0, items.lastIndex)
    }

    fun getPlaylist(): List<MediaItem> = playlist.toList() // Devuelve copia inmutable

    fun getCurrent(): MediaItem? = playlist.getOrNull(currentIndex)

    @Suppress("unused")
    @Keep
    fun getCurrentIndex(): Int = currentIndex

    fun getNext(): MediaItem? {
        if (playlist.isEmpty()) return null
        currentIndex = if (currentIndex >= playlist.lastIndex) {
            if (isLoopingEnabled) 0 else -1 // Retorna null si no hay loop
        } else {
            currentIndex + 1
        }
        return playlist.getOrNull(currentIndex)
    }

    fun getPrevious(): MediaItem? {
        if (playlist.isEmpty()) return null
        currentIndex = if (currentIndex <= 0) {
            if (isLoopingEnabled) playlist.lastIndex else -1
        } else {
            currentIndex - 1
        }
        return playlist.getOrNull(currentIndex)
    }
}