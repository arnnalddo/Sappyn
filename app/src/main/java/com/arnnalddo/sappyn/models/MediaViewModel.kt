/*
 * Copyright (c) 2025 Arnaldo Alfredo.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.arnnalddo.sappyn.models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.arnnalddo.sappyn.adapters.ListAdapter
import com.arnnalddo.sappyn.adapters.ListAdapter.ItemField
import com.arnnalddo.sappyn.adapters.MediaListAdapter

/**
 * ViewModel for managing and providing media item data to the UI.
 *
 * This class handles the storage, sorting, and presentation of media items,
 * typically for display in a list format. It uses LiveData to observe data changes
 * and update the UI accordingly.
 *
 * Key functionalities include:
 * - Storing a list of `MediaListAdapter.MediaListItem` objects.
 * - Providing a `LiveData` object (`mediaItems`) to observe changes in the media item list.
 * - Maintaining the current sort field (`currentSortField`) for the media items.
 * - Offering a method (`setMediaItems`) to update the list of media items and optionally sort them.
 * - Internally sorting media items based on a specified `ItemField` and inserting section headers.
 *
 * The sorting logic groups items by the specified field and inserts header items
 * into the list to delineate sections. It also marks the first item in each section.
 */
class MediaViewModel : ViewModel() {
    private val _mediaItems = MutableLiveData<List<MediaListAdapter.MediaListItem>>()
    val mediaItems: LiveData<List<MediaListAdapter.MediaListItem>> = _mediaItems
    var currentSortField: ItemField = ItemField.REGION

    fun setMediaItems(items: List<MediaListAdapter.MediaListItem>, sortBy: ItemField? = null) {
        currentSortField = sortBy ?: currentSortField
        _mediaItems.value = sortItemsInternal(items, currentSortField)
    }

    private fun sortItemsInternal(
        items: List<MediaListAdapter.MediaListItem>,
        by: ItemField
    ): List<MediaListAdapter.MediaListItem> {
        val sortedItems = when (by) {
            ItemField.NAME -> items.sortedBy { it.name }
            ItemField.MODULATION -> items.sortedBy { it.modulation }
            ItemField.REGION -> items.sortedBy { it.region }
            ItemField.CITY -> items.sortedBy { it.city }
        }

        val result = mutableListOf<MediaListAdapter.MediaListItem>()
        var currentSectionTitle: String? = null

        sortedItems.forEach { mediaItem ->
            val newSectionTitle = when (by) {
                ItemField.NAME -> mediaItem.name?.take(1)?.uppercase()
                ItemField.MODULATION -> mediaItem.modulation
                ItemField.REGION -> mediaItem.region
                ItemField.CITY -> mediaItem.city
            }

            if (newSectionTitle != currentSectionTitle) {
                currentSectionTitle = newSectionTitle
                result.add(MediaListAdapter.MediaListItem(
                    type = ListAdapter.ItemType.HEADER,
                    sectionTitle = currentSectionTitle ?: "Otros"
                ))
                mediaItem.isFirstInSection = true // <-- Marcar como primer item de sección
            } else {
                mediaItem.isFirstInSection = false // <-- No es primer item de sección
            }

            result.add(mediaItem)
        }

        return result
    }
}