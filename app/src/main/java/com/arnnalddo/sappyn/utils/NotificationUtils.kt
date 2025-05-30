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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.BADGE_ICON_NONE
import androidx.core.app.NotificationCompat.PRIORITY_LOW
import androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
import androidx.core.content.ContextCompat
import androidx.media3.common.util.NotificationUtil.IMPORTANCE_LOW
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.arnnalddo.sappyn.BuildConfig
import com.arnnalddo.sappyn.R

/**
 * Utility object for managing notifications within the application.
 *
 * This object provides:
 * - Constant IDs for notification channels and individual notifications.
 * - Methods to create notification channels for different purposes (Player, Service, Power Saving Mode).
 * - Methods to build notifications with specific configurations.
 * - Helper methods for showing, canceling, and checking the status of notifications (e.g., power saving mode).
 * - Integration with `PlayerNotificationManager` for media playback notifications.
 */
object NotificationUtils {

    // --- IDs for channels ---
    const val SERVICE_NOTIFICATION_CHANNEL_ID: String =
        "${BuildConfig.APPLICATION_ID}.notification.channelId.SERVICE"
    const val PLAYER_NOTIFICATION_CHANNEL_ID: String =
        "${BuildConfig.APPLICATION_ID}.notification.channelId.PLAYER"
    const val POWER_SAVE_NOTIFICATION_CHANNEL_ID: String =
        "${BuildConfig.APPLICATION_ID}.notification.channelId.POWER_SAVE"
    //const val PUSH_CHANNEL_ID = "${BuildConfig.APPLICATION_ID}.notification.channelId.PUSH"

    // --- IDs for notifications ---
    const val SERVICE_NOTIFICATION_ID: Int = 999
    const val PLAYER_NOTIFICATION_ID: Int = 100 // can't be 0 (zero)
    const val POWER_SAVE_NOTIFICATION_ID: Int = 200 // can't be 0 (zero)
    //const val PUSH_NOTIFICATION_ID = 300 // can't be 0 (zero)

    // ---------------------------------------------------------------------------------------------
    // 1. Channels
    // ---------------------------------------------------------------------------------------------
    // 1.1. For Player Notification
    @RequiresApi(Build.VERSION_CODES.O)
    fun createPlayerNotificationChannel(context: Context) {
        NotificationChannel(
            PLAYER_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_player_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_player_description)
            setShowBadge(false)
            getNotificationManager(context)?.createNotificationChannel(this)
        }
    }

    // 1.2. For Service Notification
    @RequiresApi(Build.VERSION_CODES.O)
    fun createServiceNotificationChannel(context: Context) {
        NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_service_title),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.notification_service_description)
            setShowBadge(false)
            getNotificationManager(context)?.createNotificationChannel(this)
        }
    }

    // 1.3. For Power Saving Mode Notification
    @RequiresApi(Build.VERSION_CODES.O)
    fun createPowerSaveNotificationChannel(context: Context) {
        NotificationChannel(
            POWER_SAVE_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_power_save_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_power_save_description)
            setShowBadge(false)
            getNotificationManager(context)?.createNotificationChannel(this)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Builders
    // ---------------------------------------------------------------------------------------------
    // 2.1. For Power Save Mode Notification
    private fun buildPowerSaveNotification(context: Context): Notification {
        val subtitle: CharSequence = context.getString(R.string.notification_power_save_subtitle)
        return NotificationCompat.Builder(context, POWER_SAVE_NOTIFICATION_CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.notification_power_save_title))
            setContentText(subtitle)
            setTicker(context.getString(R.string.notification_power_save_title))
            setSmallIcon(R.drawable.ic_stat_logo)
            setOngoing(true)
            setStyle(
                NotificationCompat.BigTextStyle()
                .bigText(subtitle)
            )
            setColor(ContextCompat.getColor(context, R.color.md_theme_secondary))
            setVisibility(VISIBILITY_PUBLIC)
            setPriority(PRIORITY_LOW)
        }.build()
    }

    // 2.2. For Service Notification
    fun buildServiceNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name)) // Required
            .setContentText(context.getString(R.string.notification_service_title)) // Required
            .setTicker(context.getString(R.string.notification_service_title)) // Required
            .setSmallIcon(R.drawable.ic_transparent) // Required
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(false)
            .build()
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Helper Methods
    // ---------------------------------------------------------------------------------------------
    @UnstableApi
    fun getPlayerNotificationManager(
        context: Context,
        session: MediaSession,
        notificationListener: PlayerNotificationManager.NotificationListener,
        isLiveContent: Boolean
    ): PlayerNotificationManager {
        return PlayerNotificationManager.Builder(
            context,
            PLAYER_NOTIFICATION_ID,
            PLAYER_NOTIFICATION_CHANNEL_ID
        ).apply {
            setMediaDescriptionAdapter(
                MediaNotificationAdapter(context, session.sessionActivity))
            setNotificationListener(notificationListener)
            setSmallIconResourceId(R.drawable.ic_stat_logo)
            setPlayActionIconResourceId(R.drawable.ic_media_play)
            setPauseActionIconResourceId(
                if (isLiveContent) R.drawable.ic_media_stop
                else R.drawable.ic_media_pause)
            setPreviousActionIconResourceId(R.drawable.ic_media_skip_previous)
            setNextActionIconResourceId(R.drawable.ic_media_skip_next)
            setChannelNameResourceId(R.string.notification_player_title)
            setChannelDescriptionResourceId(R.string.notification_player_description)
            setChannelImportance(IMPORTANCE_LOW)
        }.build().apply {
            setPlayer(session.player)
            setMediaSessionToken(session.sessionCompatToken)
            setBadgeIconType(BADGE_ICON_NONE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                setPriority(PRIORITY_LOW) }
            setVisibility(VISIBILITY_PUBLIC)
            setUseChronometer(false)
            setUsePreviousActionInCompactView(false)
            setUseNextActionInCompactView(true)
            setUseFastForwardAction(false)
            setUseRewindAction(false)
            setUseStopAction(false)
            setColorized(true)
            setColor(ContextCompat.getColor(
                context, R.color.md_theme_inverseOnSurface))
        }
    }

    fun handlePowerSaveNotification(context: Context) {
        val isPowerSaveModeActive = isPowerSaveModeActive(context)
        val notification = buildPowerSaveNotification(context)
        if (isPowerSaveModeActive) {
            showNotification(context, POWER_SAVE_NOTIFICATION_ID, notification)
        } else {
            cancelNotification(context, POWER_SAVE_NOTIFICATION_ID)
        }
    }

    fun isPowerSaveModeActive(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode == true
    }

    fun showNotification(context: Context, notificationId: Int, notification: Notification) {
        getNotificationManager(context)?.notify(notificationId, notification) }

    fun cancelNotification(context: Context, notificationId: Int) {
        getNotificationManager(context)?.cancel(notificationId) }

    private fun getNotificationManager(context: Context): NotificationManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(NotificationManager::class.java)
        } else {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }
    }

}