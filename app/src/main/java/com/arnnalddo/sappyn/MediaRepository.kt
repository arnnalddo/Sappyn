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

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arnnalddo.sappyn.utils.Util
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException

/**
 * Repository class for managing media items and their persistence using SharedPreferences.
 *
 * This class handles saving and retrieving lists of [MediaItem] objects,
 * the last played media item, playback position, and loop state.
 * It uses Gson for serializing and deserializing [MediaItem] objects to and from JSON,
 * with a custom [MediaItemTypeAdapter] for handling [MediaItem] specific serialization.
 *
 * The constructor is private to enforce instantiation through the [getInstance] singleton factory method,
 * which ensures only one instance of the repository exists.
 *
 * It provides methods for:
 * - Saving and retrieving the full list of media items.
 * - Saving and retrieving the last played media item, including its metadata and playback position.
 * - Saving and retrieving the loop state for playback.
 * - Retrieving a media item by its ID.
 * - Clearing the playback history (last played item and position).
 *
 * Media items are cached in memory to improve performance for frequent access.
 * Validation is performed on media items before saving to ensure data integrity,
 * specifically checking for non-blank media IDs and valid media URIs.
 *
 * @property prefs The [SharedPreferences] instance used for storing media data.
 */
class MediaRepository private constructor(private val prefs: SharedPreferences) {

    private var cachedMediaList: List<MediaItem>? = null
    private val gson = GsonBuilder()
        .registerTypeAdapter(MediaItem::class.java, MediaItemTypeAdapter())
        .create()
    companion object {
        const val TAG = "MediaRepository    ⇠"
        // -- Last media item keys --
        const val PREF_KEY_LAST_MEDIA_ID = "last_media_id"
        const val PREF_KEY_LAST_MEDIA_URI = "last_media_uri"
        const val PREF_KEY_LAST_MEDIA_NAME = "last_media_name"
        const val PREF_KEY_LAST_MEDIA_CITY = "last_media_city"
        const val PREF_KEY_LAST_MEDIA_REGION = "last_media_region"
        const val PREF_KEY_LAST_MEDIA_MODULATION = "last_media_modulation"
        const val PREF_KEY_LAST_MEDIA_MEDIUM_IMAGE_URI = "last_media_medium_image_uri"
        const val PREF_KEY_LAST_MEDIA_SMALL_IMAGE_URI = "last_media_small_image_uri"
        const val PREF_KEY_LAST_MEDIA_IS_VIDEO = "last_media_is_video"
        const val PREF_KEY_LAST_MEDIA_IS_LIVE = "last_media_is_live"
        const val PREF_KEY_LAST_MEDIA_POSITION = "last_media_position"
        // -- Other media keys --
        const val PREF_KEY_LOOP_ENABLED = "loop_enabled"
        const val PREF_KEY_FULL_MEDIA_LIST = "full_media_list"

        @Volatile private var instance: MediaRepository? = null
        fun getInstance(context: Context): MediaRepository {
            return instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }
        }

        private fun create(context: Context): MediaRepository {
            val prefs = context.getSharedPreferences(Util.PREFS_NAME, Context.MODE_PRIVATE)
            return MediaRepository(prefs)
        }
    }

    // Save the full list of MediaItems
    fun saveMediaList(items: List<MediaItem>) {
        val validItems = items.filter { item ->
            item.mediaId.isNotBlank() &&
                    Util.validate(item.localConfiguration?.uri.toString(), Util.StringFormat.MEDIA_URI)
        }.also {
            Log.d(TAG, "Saving ${it.size} valid items from ${items.size} total")
        }

        val json = gson.toJson(validItems)
        prefs.edit {
            putString(PREF_KEY_FULL_MEDIA_LIST, json)
            apply()
        }
        cachedMediaList = validItems
        Log.d(TAG, "Media list saved with ${validItems.size} items.")
    }

    fun getMediaList(): List<MediaItem> {
        return if (cachedMediaList != null) {
            Log.d(TAG, "Cached media list found with ${cachedMediaList?.size ?: 0} items")
            cachedMediaList!!
        } else {
            loadMediaList().also {
                if (it.isNotEmpty()) {
                    Log.d(TAG, "Media list loaded with ${it.size} items")
                    cachedMediaList = it
                }
            }
        }
    }

    // Deserialize Array<MediaItem> from JSON and map to List<MediaItem>
    private fun loadMediaList(): List<MediaItem> {
        Log.d(TAG, "Checking preferences for media list...")
        val json = prefs.getString(PREF_KEY_FULL_MEDIA_LIST, null)

        if (json.isNullOrEmpty() || json == "[]") {
            Log.d(TAG, "No media list found in preferences or it's empty.")
            return emptyList()
        }

        Log.d(TAG, "Found saved media list, deserializing...")
        return try {
            gson.fromJson(json, Array<MediaItem>::class.java)
                .toList()
                .filter { item ->
                    item.mediaId.isNotEmpty() &&
                    Util.validate(
                        item.localConfiguration?.uri.toString(),
                        Util.StringFormat.MEDIA_URI
                    ) // (Will verify null or empty too)
                }.also {
                    Log.d(TAG, "Media list deserialized with ${it.size} items.")
                }
        } catch (e: JsonSyntaxException) {
            // Log error and return empty list if deserialization fails
            Log.e(TAG, "JSON syntax error when getting media list from preferences.", e)
            e.printStackTrace()
            emptyList()
        } catch (e: Exception) {
            // Log other errors and return empty list
            Log.e(TAG, "Error getting media list from preferences.", e)
            e.printStackTrace()
            emptyList()
        }

    }

    // Save the last played MediaItem
    fun saveLastMediaItem(item: MediaItem) {
        // Ensure URI string is not null or empty before saving
        // Using MediaItem.RequestMetadata.mediaUri as it's the primary source URI
        val uriString = item.localConfiguration?.uri?.toString()
        if (uriString.isNullOrEmpty()) {
            // Log a warning or error if trying to save an item without a valid URI
            Log.w(TAG, "Attempting to save MediaItem with null or empty URI for: ${item.mediaMetadata.title}. Clearing playback history...")
            // Optionally clear the last saved item if a null/empty URI is encountered to prevent bad state on next launch
            clearPlaybackHistory() // Clear invalid saved state
            return
        }

        // Get isLive from the MediaItem's extras if available, otherwise use the passed value
        val isLiveToSave = item.mediaMetadata.extras?.getBoolean("is_live", false)

        prefs.edit {
            putString(PREF_KEY_LAST_MEDIA_ID, item.mediaId)
            putString(PREF_KEY_LAST_MEDIA_URI, uriString)  // <-- Save the URI string explicitly
            putString(PREF_KEY_LAST_MEDIA_NAME, item.mediaMetadata.title?.toString())
            putString(PREF_KEY_LAST_MEDIA_CITY, item.mediaMetadata.artist?.toString())
            putString(PREF_KEY_LAST_MEDIA_MEDIUM_IMAGE_URI, item.mediaMetadata.artworkUri?.toString())
            // Extra data saved in MediaItem:
            putString(PREF_KEY_LAST_MEDIA_SMALL_IMAGE_URI, item.mediaMetadata.extras?.getString("small_image_url", ""))
            putString(PREF_KEY_LAST_MEDIA_REGION, item.mediaMetadata.extras?.getString("region", ""))
            putString(PREF_KEY_LAST_MEDIA_MODULATION, item.mediaMetadata.extras?.getString("modulation", ""))
            putBoolean(PREF_KEY_LAST_MEDIA_IS_VIDEO, item.mediaMetadata.extras?.getBoolean("is_video", false) == true)
            putBoolean(PREF_KEY_LAST_MEDIA_IS_LIVE, isLiveToSave == true)
            apply() // Use apply() for asynchronous save
            Log.d(TAG, "Last media item saved for: ${item.mediaMetadata.title} (stream uri: $uriString)")
        }
    }

    // Get the last MediaItem saved
    fun getLastMediaItem(): MediaItem? {
        val uriString = prefs.getString(PREF_KEY_LAST_MEDIA_URI, null)

        // Return null if the saved URI string is null or empty
        if (uriString.isNullOrEmpty()) {
            Log.d(TAG, "No last media item URI found in preferences or it's empty.")
            return null
        }

        val mediaUri: Uri? = try {
            uriString.toUri()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing saved URI string: $uriString", e)
            clearPlaybackHistory() // Clear corrupt data
            return null // Parsing failed, treat as invalid URI
        }

        // Retrieve other metadata from preferences
        val id = prefs.getString(PREF_KEY_LAST_MEDIA_ID, "") ?: ""
        val name = prefs.getString(PREF_KEY_LAST_MEDIA_NAME, null)
        val region = prefs.getString(PREF_KEY_LAST_MEDIA_REGION, null)
        val city = prefs.getString(PREF_KEY_LAST_MEDIA_CITY, null)
        val modulation = prefs.getString(PREF_KEY_LAST_MEDIA_MODULATION, null)
        val mediumImageUriString = prefs.getString(PREF_KEY_LAST_MEDIA_MEDIUM_IMAGE_URI, null)
        val smallImageUriString = prefs.getString(PREF_KEY_LAST_MEDIA_SMALL_IMAGE_URI, null)
        val isVideo = prefs.getBoolean(PREF_KEY_LAST_MEDIA_IS_VIDEO, false)
        val isLive = prefs.getBoolean(PREF_KEY_LAST_MEDIA_IS_LIVE, false)

        // Rebuild MediaMetadata, being careful with potentially null strings converted to Uri
        val metadata = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(city)
            .setStation(name)
            // Use takeIf to convert imageUriString to Uri only if not null or empty
            .setArtworkUri(mediumImageUriString.takeIf { !it.isNullOrEmpty() }?.toUri())
            .setExtras(Bundle().apply { putString("small_image_uri", smallImageUriString) })
            .setExtras(Bundle().apply { putString("region", region) })
            .setExtras(Bundle().apply { putString("modulation", modulation) })
            .setExtras(Bundle().apply { putBoolean("is_video", isVideo) })
            .setExtras(Bundle().apply { putBoolean("is_live", isLive) })
            .build()

        Log.d(TAG, "Last media item restored: $name (URI: $uriString, isLive: $isLive)")

        return MediaItem.Builder()
            .setMediaId(id) // Set the media ID
            .setUri(mediaUri) // Set the parsed Uri object
            .setMediaMetadata(metadata)
            .build()
    }

    // Save the Media ID of the last played item
    @Suppress("unused")
    @Keep
    fun saveLastMediaItemById(mediaId: String): Boolean {
        Log.d(TAG, "Try to save last played media item with ID: $mediaId")
        val fullList = getMediaList()
        val itemToSave = fullList.firstOrNull { it.mediaId == mediaId }
        itemToSave?.let {
            saveLastMediaItem(it)
            return true
        }
        return false
    }

    fun getMediaItemById(id: String): MediaItem? {
        return getMediaList().find { it.mediaId == id }
    }

    // Helper method to get just the last played Media ID if needed by the UI
    fun getLastPlayedMediaId(): String? {
        return prefs.getString(PREF_KEY_LAST_MEDIA_ID, null)
    }

    // Save position of onDemand content
    fun saveLastPosition(position: Long) {
        prefs.edit {
            putLong(PREF_KEY_LAST_MEDIA_POSITION, position)
            apply()
        }
        Log.d(TAG, "Last position saved: $position")
    }

    // Get the last position of onDemand content
    fun getLastPosition(): Long = prefs.getLong(PREF_KEY_LAST_MEDIA_POSITION, 0L).also {
        Log.d(TAG, "Last position retrieved: $it")
    }

    fun saveLoopState(enabled: Boolean) {
        prefs.edit {
            putBoolean(PREF_KEY_LOOP_ENABLED, enabled)
            apply()
        }
        Log.d(TAG, "Loop state saved: $enabled")
    }

    fun getLoopState(): Boolean = prefs.getBoolean(PREF_KEY_LOOP_ENABLED, false).also {
        Log.d(TAG, "Loop state retrieved: $it")
    }

    // Add clearPlaybackHistory method
    fun clearPlaybackHistory() {
        prefs.edit {
            remove(PREF_KEY_LAST_MEDIA_ID)
            remove(PREF_KEY_LAST_MEDIA_URI)
            remove(PREF_KEY_LAST_MEDIA_NAME)
            remove(PREF_KEY_LAST_MEDIA_CITY)
            remove(PREF_KEY_LAST_MEDIA_REGION)
            remove(PREF_KEY_LAST_MEDIA_MODULATION)
            remove(PREF_KEY_LAST_MEDIA_MEDIUM_IMAGE_URI)
            remove(PREF_KEY_LAST_MEDIA_SMALL_IMAGE_URI)
            remove(PREF_KEY_LAST_MEDIA_IS_VIDEO)
            remove(PREF_KEY_LAST_MEDIA_IS_LIVE)
            remove(PREF_KEY_LAST_MEDIA_POSITION)
            apply()
        }
        Log.d(TAG, "Playback history cleared.")
        // Note: This only clears the *last played* item. The full media list is kept.
    }
}