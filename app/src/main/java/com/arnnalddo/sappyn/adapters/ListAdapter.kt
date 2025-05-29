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

package com.arnnalddo.sappyn.adapters

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import java.util.ArrayList


/**
 * Abstract base class for RecyclerView adapters used in the application.
 *
 * This class provides a common structure and functionality for adapters that display lists
 * of items. It handles basic setup like item view type determination and item count,
 * and defines interfaces and enums for item types, fields, and click listeners.
 *
 * Subclasses should implement the `onCreateViewHolder` and `onBindViewHolder` methods
 * to define how items are created and bound to their views.
 *
 * @param context The Context in which the adapter is operating.
 * @param items An optional ArrayList of items to be displayed. Items must implement the [ListAdapter.Item] interface.
 * @param itemClickListener An optional listener for item click events.
 *
 * @property inflater A LayoutInflater instance for inflating item views.
 * @property itemClicklistener An optional listener for item click events.
 *
 * @see RecyclerView.Adapter
 * @see MediaListAdapter
 */
abstract class ListAdapter
internal constructor(
    protected val context: Context,
    protected var items: ArrayList<*>? = null, // Items can be of any type implementing ListAdapter.Item
    itemClickListener: ItemClicklistener?
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>() {
    //**********************************************************************************************
    // region [Properties]
    protected val inflater: LayoutInflater = LayoutInflater.from(context)
    var itemClicklistener: ItemClicklistener? = itemClickListener
    // endregion

    //**********************************************************************************************
    // region [Functions]
    override fun getItemViewType(position: Int): Int {
        // Ensure items is not null and position is valid
        val item = items?.getOrNull(position) as? Item
        return when (item?.type) {
            ItemType.HEADER -> 0
            ItemType.ROW -> 1
            else -> -1 // Handle potentially null item or unknown type
        }
    }

    override fun getItemCount(): Int {
        return items?.size ?: 0 // Return 0 if items is null
    }
    // endregion

    //**********************************************************************************************
    // region [Other necesary stuff]
    protected abstract class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    enum class ItemType {
        HEADER,
        ROW
    }

    enum class ItemField {
        NAME,
        MODULATION,
        REGION,
        CITY
    }

    interface Item {
        val type: ItemType
        val sectionTitle: String? // Section title is only relevant for HEADER type
    }

    interface ItemClicklistener {
        fun onItemClick(position: Int)
    }
    // endregion
}