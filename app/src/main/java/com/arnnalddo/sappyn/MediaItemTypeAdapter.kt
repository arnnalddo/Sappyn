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

package com.arnnalddo.sappyn

import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * A Gson TypeAdapter for serializing and deserializing [MediaItem] objects.
 * This adapter handles the conversion between the JSON representation and the [MediaItem] object,
 * including its metadata and extras.
 *
 * It ensures that essential fields like 'id' and 'source' are present and valid during deserialization.
 * If these fields are missing or invalid, it returns [MediaItem.EMPTY].
 *
 * The adapter also handles potential null values for optional fields and skips unknown fields
 * during deserialization to maintain compatibility with evolving JSON structures.
 */
class MediaItemTypeAdapter : TypeAdapter<MediaItem>() {
    override fun write(out: JsonWriter, value: MediaItem) {
        out.beginObject()
        out.name("id").value(value.mediaId)

        // Get the original source URI string from the MediaItem
        val sourceUriString = value.localConfiguration?.uri?.toString()
            ?: value.mediaMetadata.extras?.getString("original_source_uri_string")

        out.name("source").value(sourceUriString)

        out.name("name").value(value.mediaMetadata.title?.toString())
        out.name("city").value(value.mediaMetadata.artist?.toString())
        out.name("medium_image_uri").value(value.mediaMetadata.artworkUri?.toString())
        // Serialize extra metadata
        value.mediaMetadata.extras?.let { extras ->
            out.name("small_image_uri").value(extras.getString("small_image_uri"))
            out.name("modulation").value(extras.getString("modulation"))
            out.name("region").value(extras.getString("region"))
            out.name("is_video").value(extras.getBoolean("is_video"))
            out.name("is_live").value(extras.getBoolean("is_live"))
            /*out.name("extras").beginObject()
            extras.keySet().forEach { key ->
                when (val extraValue = extras.get(key)) {
                    is String -> out.name(key).value(extraValue)
                    is Boolean -> out.name(key).value(extraValue)
                    is Uri -> out.name(key).value(extraValue.toString())
                    // Añadir más tipos según necesidad
                }
            }
            out.endObject()*/
        }
        out.endObject()
    }

    override fun read(reader: JsonReader): MediaItem {
        reader.beginObject()
        var mediaId: String? = null
        var sourceUriString: String? = null
        val metadataBuilder = MediaMetadata.Builder()
        val extras = Bundle()

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> mediaId = reader.nextString().takeIf { it.isNotEmpty() }
                "source" -> {
                    if (reader.peek() != JsonToken.NULL) {
                        sourceUriString = reader.nextString()
                    } else {
                        reader.skipValue() // Saltar valores nulos
                    }
                }
                "name" -> {
                    val value = reader.nextString()
                    metadataBuilder.setTitle(value)
                    metadataBuilder.setStation(value) // Save station name for future use
                }
                "city" -> metadataBuilder.setArtist(reader.nextString())
                "medium_image_uri" -> {
                    if (reader.peek() != JsonToken.NULL) {
                        reader.nextString()?.toUri()?.let {
                            metadataBuilder.setArtworkUri(it)
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                // Extra metadata
                "small_image_uri" -> extras.putString("small_image_uri", reader.nextString())
                "modulation" -> extras.putString("modulation", reader.nextString())
                "region" -> extras.putString("region", reader.nextString())
                "is_video" -> extras.putBoolean("is_video", reader.nextBoolean())
                "is_live" -> extras.putBoolean("is_live", reader.nextBoolean())
                else -> reader.skipValue() // Ignore unknown fields
            }
        }
        reader.endObject()

        // Validación más estricta
        if (mediaId.isNullOrEmpty() || sourceUriString.isNullOrEmpty()) {
            Log.w("MediaItemAdapter", "Invalid MediaItem - ID: $mediaId, Source: $sourceUriString")
            return MediaItem.EMPTY
        }

        return try {
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(sourceUriString.toUri())
                .setMediaMetadata(metadataBuilder.setExtras(extras).build())
                .build()
        } catch (e: Exception) {
            Log.e("MediaItemAdapter", "Error building MediaItem", e)
            MediaItem.EMPTY
        }
    }

}