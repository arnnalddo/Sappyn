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

package com.arnnalddo.sappyn.adapters

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import com.arnnalddo.sappyn.R
import com.arnnalddo.sappyn.utils.Util
import com.arnnalddo.sappyn.utils.TypefaceUtils
import com.koushikdutta.ion.Ion

/**
 * Adapter for displaying a list of media items, which can include section headers and individual rows.
 *
 * This adapter handles different item types (headers and rows) and manages the selection state
 * of items within the list. It uses `Ion` for image loading and applies custom typefaces
 * to text elements.
 *
 * @param context The context used for accessing resources and inflating layouts.
 * @param items The list of [MediaListItem] objects to display.
 * @param itemClickListener An optional listener for item click events.
 *
 * @property primaryTypeface The font URI for primary text elements (e.g., rows).
 * @property secondaryTypeface The font URI for secondary text elements (e.g., section headers).
 * @property selectedItemId The ID of the currently selected media item. This is used to highlight
 *                          the selected item in the list.
 *
 * @see ListAdapter
 * @see MediaListItem
 * @see ItemClicklistener
 */
class MediaListAdapter(
    context: Context,
    items: ArrayList<MediaListItem>,
    itemClickListener: ItemClicklistener?
) : ListAdapter(context, items, itemClickListener) {
    //**********************************************************************************************
    // region [Properties]
    private val primaryTypeface: String = context.getString(R.string.app_primary_font_uri)
    private val secondaryTypeface: String = context.getString(R.string.app_secondary_font_uri)
    private var selectedItemId: String? = null // Flag to hold the ID of the currently selected item
    // endregion

    //**********************************************************************************************
    // region [Functions]
    override fun onCreateViewHolder(parent: ViewGroup, itemType: Int): RecyclerView.ViewHolder {
        val view: View = inflater.inflate(
            when (itemType) {
                0 -> R.layout.inc_media_list_section
                1 -> R.layout.inc_media_list_item
                else -> R.layout.inc_media_list_item_unavailable // Fallback for unknown type
            },
            parent,
            false
        )
        return when (itemType) {
            0 -> SectionHeader(view)
            1 -> Row(view, itemClicklistener) // Pass click listener
            else -> object : ViewHolder(view) {} // Basic ViewHolder for unavailable type
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bind(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "selection_update") {
            val item = items?.getOrNull(position) as? MediaListItem
            (holder as? Row)?.itemView?.isSelected = (item?.id == selectedItemId)
            return
        }
        bind(holder, position)
    }

    // Helper function to bind data to a ViewHolder
    private fun bind(holder: RecyclerView.ViewHolder, position: Int) {
        // Ensure position is valid
        if (position == RecyclerView.NO_POSITION || items == null || position >= items!!.size) return

        val item = items?.get(position) as MediaListItem
        when (item.type) {
            ItemType.HEADER -> {
                holder as SectionHeader
                holder.sectionTitle.text = item.sectionTitle
            }
            ItemType.ROW -> {
                holder as Row
                Ion.with(holder.imageView)
                    .placeholder(R.drawable.placeholder_album)
                    .error(R.drawable.placeholder_album)
                    .fadeIn(true)
                    .crossfade(true)
                    .load(item.smallImageUri)
                holder.textView.text = item.name
                holder.itemView.isSelected = (item.id == selectedItemId)
                holder.separatorView.visibility =
                    if (item.isFirstInSection == true) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    fun setSelectedItemId(itemId: String?) {
        if (selectedItemId == itemId) return // No change

        // 1. Find positions of old and new selected items
        val oldPos = items?.indexOfFirst { (it as? MediaListItem)?.id == selectedItemId } ?: -1
        val newPos = items?.indexOfFirst { (it as? MediaListItem)?.id == itemId } ?: -1

        // 2. Update the selected ID
        selectedItemId = itemId

        // 3. Notify changes WITH PAYLOAD to avoid full rebind
        val payload = "selection_update" // Custom payload to identify partial updates
        if (oldPos != -1) notifyItemChanged(oldPos, payload)
        if (newPos != -1) notifyItemChanged(newPos, payload)
    }
    // endregion

    //**********************************************************************************************
    // region [Other necessary stuff]
    private inner class SectionHeader(view: View) : ViewHolder(view) {
        val sectionTitle: TextView = view.findViewById(R.id.sectionTitleView)
        init {
            sectionTitle.let { TypefaceUtils.setTypeface(context, secondaryTypeface, it) }
        }
    }

    private inner class Row(view: View, private val itemClicklistener: ItemClicklistener?) :
        ViewHolder(view), View.OnClickListener {
        val layout: LinearLayout = view.findViewById(R.id.listLayout)
        val textView: TextView = view.findViewById(R.id.listTitleView)
        val imageView: ImageView = view.findViewById(R.id.listImageView)
        val separatorView: View = view.findViewById(R.id.separatorView)

        init {
            layout.setOnClickListener(this)
            textView.let { TypefaceUtils.setTypeface(context, primaryTypeface, it) }
        }

        override fun onClick(view: View) {
            // Use adapter position to get the correct position
            if (absoluteAdapterPosition == RecyclerView.NO_POSITION) return
            itemClicklistener?.onItemClick(absoluteAdapterPosition)
        }
    }

    data class MediaListItem(
        override val type: ItemType,
        override val sectionTitle: String? = null,
        val mediaItem: MediaItem? = null,
        val mediaId: String? = null,
        val mediaSource: String? = null
    ) : Item {
        init {
            if (type == ItemType.ROW) {
                require(mediaItem != null) { "ROW item must have a MediaItem." }
                require(!mediaId.isNullOrEmpty()) { "MediaItem must have ID." }
                require(Util.validate(mediaSource, Util.StringFormat.MEDIA_URI)) { "MediaItem must have valid Source URI." }
            } else {
                require(mediaItem == null) { "Header item should not have a MediaItem." }
                require(mediaSource == null) { "Header item should not have a media source." }
                require(mediaId == null) { "Header item should not have a media id." }
                require(!sectionTitle.isNullOrEmpty()) { "Header item must have a section title." }
            }
        }
        // Properties matching the data needed for the list item UI
        val id: String? get() = mediaId
        val source: String? get() = mediaSource
        val name: String? get() = mediaItem?.mediaMetadata?.title?.toString()
        val city: String? get() = mediaItem?.mediaMetadata?.artist?.toString() // Assuming artist is city
        val mediumImageUri: String? get() = mediaItem?.mediaMetadata?.artworkUri?.toString()

        // Extra metadata saved in MediaItem:
        val smallImageUri: String? get() = mediaItem?.mediaMetadata?.extras?.getString("small_image_uri")
        val region: String? get() = mediaItem?.mediaMetadata?.extras?.getString("region")
        val modulation: String? get() = mediaItem?.mediaMetadata?.extras?.getString("modulation")
        val isVideo: Boolean get() = mediaItem?.mediaMetadata?.extras?.getBoolean("is_video") == true
        val isLive: Boolean get() = mediaItem?.mediaMetadata?.extras?.getBoolean("is_live") == true

        var isFirstInSection: Boolean? = false  // Flag for section header logic
    }
    // endregion
}