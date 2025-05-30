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

package com.arnnalddo.sappyn.utils

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerNotificationManager
import com.arnnalddo.sappyn.R
import com.koushikdutta.ion.Ion
import androidx.core.graphics.createBitmap

/**
 * Adapts media metadata for display in a media notification.
 *
 * This class implements [PlayerNotificationManager.MediaDescriptionAdapter] to provide
 * content title, text, large icon, and content intent for the notification.
 *
 * It uses a two-level caching strategy for artwork:
 * 1. **In-memory LruCache:** Optimised for notifications, stores recently used bitmaps.
 * 2. **Preloaded Fallback Icon:** Prevents bitmap recreation and ensures a default icon is always available.
 *
 * Artwork is loaded asynchronously using Ion library and cached with a "Cache-Control" header.
 *
 * @param context The application context.
 * @param pendingIntent The [PendingIntent] to be triggered when the notification is clicked.
 */
@UnstableApi
class MediaNotificationAdapter(
    private val context: Context,
    private val pendingIntent: PendingIntent?
) : PlayerNotificationManager.MediaDescriptionAdapter {

    // 1. Manual in-memory caching (optimized for notifications)
    private val memoryCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) { // 2MB
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount
        }
    }

    // 2. Preloaded Fallback (avoids recreating Bitmap)
    private val fallbackIcon: Bitmap = run {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_foreground)!!
        createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1)
        ).apply {
            val canvas = Canvas(this)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    override fun getCurrentLargeIcon(
        player: Player,
        callback: PlayerNotificationManager.BitmapCallback
    ): Bitmap? {
        val artworkUri = player.mediaMetadata.artworkUri
        artworkUri?.let { uri ->
            memoryCache.get(uri.toString())?.let {
                callback.onBitmap(it)
            }
            Ion.with(context)
                .load(uri.toString())
                .setHeader("Cache-Control", "public, max-age=604800")
                .asBitmap()
                .setCallback { _, result ->
                    result?.let { bitmap ->
                        memoryCache.put(uri.toString(), bitmap)
                        callback.onBitmap(bitmap)
                    }
                }
        }
        return fallbackIcon
    }

    override fun getCurrentContentTitle(player: Player): CharSequence =
        player.mediaMetadata.title ?: ""

    override fun createCurrentContentIntent(player: Player): PendingIntent? =
        pendingIntent

    override fun getCurrentContentText(player: Player): CharSequence =
        player.mediaMetadata.artist ?: ""

}