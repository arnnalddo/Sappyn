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

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.ui.PlayerNotificationManager
import com.arnnalddo.sappyn.utils.Util.EXTRA_MEDIA_ID
import com.arnnalddo.sappyn.activity.MainActivity
import com.arnnalddo.sappyn.utils.NotificationUtils
import com.arnnalddo.sappyn.utils.NotificationUtils.PLAYER_NOTIFICATION_ID
import com.arnnalddo.sappyn.utils.NotificationUtils.SERVICE_NOTIFICATION_ID
import com.arnnalddo.sappyn.utils.Util
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture


/**
 * Service class for managing media playback using Media3 library.
 *
 * This service handles background audio playback, manages the media session,
 * controls notifications, and responds to playback control commands. It integrates
 * with ExoPlayer for playback, MediaSession for system integration, and
 * PlayerNotificationManager for displaying notifications.
 *
 * Key Responsibilities:
 * - **Initialization**: Sets up ExoPlayer, MediaSession, and notification channels.
 * - **Playback Control**: Manages play, pause, stop, skip next/previous actions.
 * - **Media Session Management**: Provides a MediaSession for external controllers (e.g., Bluetooth, system UI).
 * - **Notification Handling**: Displays and updates playback notifications, including handling for power saving mode.
 * - **Lifecycle Management**: Properly releases resources when the service is destroyed or the task is removed.
 * - **Metadata Updates**: Handles in-stream metadata (e.g., ICY metadata for song titles) and updates the player.
 * - **Playlist Management**: Manages a playlist of media items and handles transitions between them.
 * - **Custom Commands**: Supports custom commands from the UI (e.g., starting playback of a specific item).
 * - **Headset/Media Button Events**: Responds to media button presses (play, pause, skip).
 * - **Power Save Mode Awareness**: Listens for power saving mode changes and updates notifications accordingly.
 *
 * This service implements several interfaces:
 * - [MediaSessionService]: Base class for Media3 media services.
 * - [MediaSession.Callback]: Handles callbacks from the MediaSession (e.g., custom commands, media button events).
 * - [Player.Listener]: Listens to player state changes (e.g., media item transitions, metadata updates).
 * - [MediaNotification.Provider]: Provides custom notifications for the media session.
 * - [PlayerNotificationManager.NotificationListener]: Listens to notification events from PlayerNotificationManager.
 *
 * The `@UnstableApi` annotation is used because this class utilizes Media3 APIs that are still under development
 * and may change in future versions.
 */
@UnstableApi // Required for Media3 APIs
class MediaService : MediaSessionService(), MediaSession.Callback, Player.Listener, MediaNotification.Provider, PlayerNotificationManager.NotificationListener {
    //**********************************************************************************************
    // region [Properties]
    companion object { private const val TAG = "MediaService       ▶" }

    private var playerNotificationManager: PlayerNotificationManager? = null
    private var mediaSession: MediaSession? = null
    private lateinit var mediaRepository: MediaRepository
    private lateinit var playlistManager: PlaylistManager

    private val localCommandReceiverForSystem: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Handle Power Saving Mode change
            if (intent.action == ACTION_POWER_SAVE_MODE_CHANGED) {
                NotificationUtils.handlePowerSaveNotification(this@MediaService)
            }
        }
    }

    // Handle headset/media button clicks
    private val headsetHookClickCounter = object {
        private val maxDelay = 300L
        private var lastClickTime = 0L
        private var clickCount = 0

        fun registerClick(): Int {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime > maxDelay) {
                clickCount = 0
            }
            clickCount++
            lastClickTime = currentTime
            return clickCount
        }
    }

    // endregion

    //**********************************************************************************************
    // region [Service Lifecycle]
    /**
     * This method is called when the service is being created.
     * It initializes the ExoPlayer and MediaSession instances.
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "oncreate() called.")

        // 1. Register Receiver for Power Saving Mode
        val powerSaveIntentFilter = IntentFilter(ACTION_POWER_SAVE_MODE_CHANGED)
        ContextCompat.registerReceiver(this, localCommandReceiverForSystem, powerSaveIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // 2. Initialize Repository and PlaylistManager
        mediaRepository = MediaRepository.create(applicationContext)
        playlistManager = PlaylistManager(mediaRepository, mediaRepository::saveLoopState)

        // 3. Set up AudioAttributes
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // 4. Set up ExoPlayer
        val mediaPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // true to handle AudioFocus
            .setHandleAudioBecomingNoisy(true) // Pause Playback when disconnect headset
            .setWakeMode(C.WAKE_MODE_NETWORK) // Keep CPU and Wifi awake while playing
            .build().apply {
                addListener(this@MediaService)
            }

        // 5. Set up MediaSession
        mediaSession = MediaSession.Builder(this, mediaPlayer)
            .setCallback(this) // Set MediaSession callbacks
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setId(TAG)
            .build()

        // 6. Set up Notifications
        // Delegate to NotificationUtils to create channels and build the notifications

        // 6.1. Create Notification Channels for Android Oreo+ (required here)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationUtils.createServiceNotificationChannel(this)
            NotificationUtils.createPlayerNotificationChannel(this)
            NotificationUtils.createPowerSaveNotificationChannel(this)
        }

        val isLiveContent = mediaRepository.getLastMediaItem()
            ?.mediaMetadata
            ?.extras
            ?.getBoolean("is_live", false) == true

        mediaSession?.let { session ->
            // This is to show the "real" player notification
            playerNotificationManager = NotificationUtils.getPlayerNotificationManager(
                this,
                session,
                this,
                isLiveContent
            )
        }

        // TODO ------------------------------------------------------------------------------------
        // This is to show the "Service notification"
        setMediaNotificationProvider(this)
        // "Service notification" is a temporal and alternative Notification
        // to avoid showing two player notification while calling startForeground
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        // TODO ------------------------------------------------------------------------------------
        // Show "Service notification"
        return MediaNotification(SERVICE_NOTIFICATION_ID, NotificationUtils.buildServiceNotification(this))
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // TODO ------------------------------------------------------------------------------------
        // Cancel "Service notification" automatically
        NotificationUtils.cancelNotification(this, SERVICE_NOTIFICATION_ID)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun onNotificationPosted(
        notificationId: Int,
        notification: Notification,
        ongoing: Boolean
    ) {
        // TODO ------------------------------------------------------------------------------------
        // Cancel "Service notification" automatically
        NotificationUtils.cancelNotification(this, SERVICE_NOTIFICATION_ID)

        if (!Util.isForegroundServiceStarted) {
            startForeground(notification) }
    }

    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
        //stopForeground()
        if (dismissedByUser) {
            Log.d(TAG, "Notification cancelled by user. Stopping service...")
            stopSelf()
        }
    }

    private fun startForeground(notification: Notification) {
        if (Util.isForegroundServiceStarted) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PLAYER_NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else { startForeground(PLAYER_NOTIFICATION_ID, notification) }

        Util.isForegroundServiceStarted = true
        Log.i(TAG, "Foreground service started.")
    }

    private fun stopForeground() {
        if (!Util.isForegroundServiceStarted) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        Util.isForegroundServiceStarted = false
        Log.i(TAG, "Foreground service stopped.")
    }

    /**
     * This method is called when the service is being destroyed.
     * It releases the player and the MediaSession instances.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called.")

        // Stop foreground
        if (Util.isForegroundServiceStarted) stopForeground()

        playerNotificationManager?.setPlayer(null)
        playerNotificationManager = null

        // Release player and session
        mediaSession?.let { session ->
            session.player.removeListener(this) // Remove listener
            if (session.player.isCommandAvailable(Player.COMMAND_STOP))
                session.player.stop()
            if (session.player.isCommandAvailable(Player.COMMAND_RELEASE))
                session.player.release() // Release player resources
            session.release() // Release MediaSession resources
            mediaSession = null // Set to null after releasing
            Log.d(TAG, "MediaSession and player released.")
        }

        // Unregister system broadcast receiver
        try {
            unregisterReceiver(localCommandReceiverForSystem)
            Log.d(TAG, "System broadcast receiver unregistered.")
        } catch (e: Exception) {
            Log.w(TAG, "System broadcast receiver was not registered", e)
        }

        super.onDestroy() // Call superclass onDestroy
    }

    /**
     * This method is called when the system determines that the service is no longer used and is being removed.
     * It checks the player's state and if the player is not ready to play or there are no items in the media queue, it stops the service.
     *
     * @param rootIntent The original root Intent that was used to launch the task that is being removed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop the service if playback is not active
        mediaSession?.player?.let {
            // Stop if not playing or if playback is ended/idle
            //if (!it.playWhenReady || it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED) {
                //Log.i(TAG, "Stopping service on task removed as player is not active.")
                stopForeground()
            //} else {}
        } ?: run {
            // If mediaSession is null, the service is likely already stopping or in an invalid state, so stop self.
            Log.i(TAG, "Stopping service on task removed as mediaSession is null.")
            if (Util.isForegroundServiceStarted) stopForeground()
        }
    }

    /**
     * Called when the device's configuration changes.
     *
     * Overrides the default implementation to handle configuration changes,
     * particularly for updating notification channel names based on locale
     * changes on Android O and above.
     *
     * @param newConfig The new [Configuration].
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationUtils.createServiceNotificationChannel(this)
            NotificationUtils.createPlayerNotificationChannel(this)
            NotificationUtils.createPowerSaveNotificationChannel(this)
        }
        // Re-evaluate Power Saving Mode notification state on config change
        NotificationUtils.handlePowerSaveNotification(this@MediaService)
    }
    // endregion

    //**********************************************************************************************
    // region [MediaSession and Player Callbacks]
    /**
     * This method is called when a MediaSession.ControllerInfo requests the MediaSession.
     * It returns the current MediaSession instance.
     *
     * @param controllerInfo The MediaSession.ControllerInfo that is requesting the MediaSession.
     * @return The current MediaSession instance.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession?.also {
            Log.i(TAG, "Session granted to: ${controllerInfo.packageName}")
        }
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        intent: Intent
    ): Boolean {
        val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        }
        if (keyEvent?.action != KeyEvent.ACTION_DOWN) return false

        Log.i(TAG, "Key event received: ${keyEvent.keyCode}")
        // Use player state to decide whether to play automatically after skip
        val playAutomatically = mediaSession?.player?.isPlaying == true ||
                mediaSession?.player?.playbackState == Player.STATE_BUFFERING

        return when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                startPlayback() // Call internal play function
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlayback() // Call internal stop function
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                val clicks = headsetHookClickCounter.registerClick()
                when {
                    // Two clicks to skip to next and play if was playing:
                    clicks == 2 && playlistManager.getPlaylist().size > 1 -> skipToNextItem(playAutomatically) // Skip to next and play if was playing
                    // Three clicks to skip to previous and play if was playing:
                    clicks == 3 && playlistManager.getPlaylist().size > 1 -> skipToPreviousItem(playAutomatically) // Skip to previous and play if was playing
                    // One click to toggle playback":
                    else -> togglePlayback()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                skipToNextItem(playAutomatically) // Skip to next and play if was playing
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                skipToPreviousItem(playAutomatically) // Skip to previous and play if was playing
                true
            }
            else -> false
        }
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {

        // Handle Power Saving Mode notification
        NotificationUtils.handlePowerSaveNotification(this@MediaService)

        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(Util.COMMAND_START_PLAYBACK, Bundle.EMPTY))
            .add(SessionCommand(Util.COMMAND_TOGGLE_PLAYBACK, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .build()
    }

    // Implement onCustomCommand to handle commands from the Activity button
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        mediaSession?.player?.let { player ->
            return when (customCommand.customAction) {
                Util.COMMAND_START_PLAYBACK -> {
                    val mediaId = args.getString(EXTRA_MEDIA_ID)
                    val autoPlay = args.getBoolean(Util.EXTRA_MEDIA_AUTOPLAY, true)
                    // Try to get media item from repository
                    val mediaItem: MediaItem? =
                        if (mediaId != null) mediaRepository.getMediaItemById(mediaId)
                        else null

                    if (mediaItem != null) {
                        Log.i(TAG, "Custom command START_PLAYBACK: Item received: ${mediaItem.mediaId}, autoPlay: $autoPlay")
                        startPlayback(mediaItem, autoPlay)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.w(TAG, "Custom command START_PLAYBACK received with no valid MediaItem data.")
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    }
                }
                Util.COMMAND_TOGGLE_PLAYBACK -> {
                    // Based on current player state, call the appropriate internal method
                    if (player.playbackState == Player.STATE_BUFFERING || player.isPlaying) {
                        Log.i(TAG, "Custom command TOGGLE_PLAYBACK: Player is playing/buffering. Calling stopPlayback().")
                        stopPlayback() // Call internal stopPlayback
                    } else {
                        Log.i(TAG, "Custom command TOGGLE_PLAYBACK: Player is stopped/paused/idle. Calling startPlayback().")
                        startPlayback() // Call internal startPlayback
                    }
                    // Indicate that the command was handled successfully
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // Handle other custom commands here if needed
                else -> {
                    // Return error for unsupported custom commands
                    Log.w(TAG, "Unknown custom command received: ${customCommand.customAction}")
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            }
        } ?: run {
            // If player is not available, return an error result
            Log.w(TAG, "Custom command ${customCommand.customAction} received but player is null.")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
    }


    // When a Bluetooth device or the Android system UI resume function
    // requests to resume playback, this method is called
    // (for example, from headphones, after the device has been rebooted).
    // We then retrieve the last MediaItem and play it.
    // TODO: hay que obtener la lista completa de items y reproducirla, no solo un item
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onPlaybackResumption called.")
        val lastItem = mediaRepository.getLastMediaItem() // Get last item from repository

        // Check if the last item was live or VOD to decide position handling
        val isLiveContent = lastItem?.mediaMetadata?.extras?.getBoolean("is_live", false) == true

        return if (lastItem != null) {
            Log.d(TAG, "Restoring last media item for resumption.")
            playlistManager.setPlaylist(listOf(lastItem)) // Set the last item as the playlist
            // For VOD, retrieve and set the last saved position
            val startPositionMs = if (!isLiveContent) mediaRepository.getLastPosition() else 0L

            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(lastItem), // List containing the single last item
                    0, // Start index in the list (always 0 for a list with one item)
                    startPositionMs // Start position within the media item
                )
            )
        } else {
            Log.d(TAG, "No last media item to restore for resumption.")
            // Return a failed future if no item is available
            Futures.immediateFailedFuture(IllegalStateException("No media item available for resumption."))
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        mediaItem?.let {
            // Save the current item (important for the playlist and the UI)
            mediaRepository.saveLastMediaItem(it)
        }
    }

    // THIS IS THE IMPORTANT ONE FOR ICY (actual metadata of songs being played)
    override fun onMetadata(metadata: androidx.media3.common.Metadata) {
        super.onMetadata(metadata)
        Log.d(TAG, "In-stream metadata received! Entries: ${metadata.length()}")
        for (i in 0 until metadata.length()) {
            val entry = metadata[i]
            // ICY metadata is in the form of IcyHeaders or IcyInfo.
            // For Shoutcast/Icecast, the title is usually in StreamTitle.
            if (entry is androidx.media3.extractor.metadata.icy.IcyInfo) {
                Log.d(TAG, "ICY Info: title: ${entry.title}")
                entry.title?.let { streamTitle ->
                    if (streamTitle.trim().isEmpty()) return
                    // Ensure player and current media item are available before updating metadata
                    mediaSession?.player?.currentMediaItem?.let { currentItem ->
                        updatePlayerMetadataFromIcy(streamTitle, currentItem)
                    }
                }
            } else if (entry is androidx.media3.extractor.metadata.icy.IcyHeaders) {
                Log.d(TAG, "ICY Headers: name: ${entry.name}, genre: ${entry.genre}, url: ${entry.url}")
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onSetMediaItems called with ${mediaItems.size} items, startIndex: $startIndex, startPosition: $startPositionMs")

        // Update the PlaylistManager with the new list
        playlistManager.setPlaylist(mediaItems, startIndex)

        // Save the last played item immediately when it's set as the starting item
        if (mediaItems.isNotEmpty() && startIndex in mediaItems.indices) {
            val startingItem = mediaItems[startIndex]
            //repository.saveLastMediaItem(startingItem)

            // Need to know if this item is live or OnDemand content.
            // This info should be in the MediaItem's metadata extras.
            val isLiveContent =
                startingItem.mediaMetadata.extras?.getBoolean("is_live", false) == true

            // Also save the start position for OnDemand content
            if (!isLiveContent) {
                mediaRepository.saveLastPosition(startPositionMs)
            }
        }

        // Return the list and starting position to the player
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                startIndex,
                startPositionMs
            )
        )
    }
    // endregion

    private fun updatePlayerMetadataFromIcy(streamTitle: String, currentItem: MediaItem) {
        mediaSession?.player?.let { player ->
            // 1. Parse ICY metadata (optimized for Shoutcast/Icecast)
            // Pass the current item's existing artist metadata as a fallback if needed
            val (artist, title) =
                parseIcyMetadata(streamTitle, currentItem.mediaMetadata.artist.toString())

            // 2. Verify metadata are different (avoid unnecessary updates)
            if (title == currentItem.mediaMetadata.title?.toString() &&
                artist == currentItem.mediaMetadata.artist?.toString()) return

            // 3. Create new MediaItem with updated metadata by building upon the current one
            val newMediaItem = currentItem.buildUpon()
                .setMediaMetadata(currentItem.mediaMetadata.buildUpon()
                    .setTitle(title)
                    .setArtist(artist)
                    .build())
                .build()

            // 4. Seamless transition
            player.replaceMediaItem(player.currentMediaItemIndex, newMediaItem)

            Log.d(TAG, "Metadata updated seamlessly: $artist - $title")
        }
    }

    private fun parseIcyMetadata(streamTitle: String, currentArtist: String): Pair<String, String> {
        return streamTitle.split(" - ", limit = 2).let { parts ->
            when (parts.size) {
                2 -> parts[0].trim() to parts[1].trim() // Title - Artist
                1 -> currentArtist to parts[0].trim() // Only title
                else -> currentArtist to
                        (mediaSession?.player?.currentMediaItem?.
                        mediaMetadata?.station ?:
                        getString(R.string.app_name)).toString() // Fallback
            }
        }
    }

    fun togglePlayback() {
        mediaSession?.player?.let { player ->
            if (player.isPlaying || player.playbackState == Player.STATE_BUFFERING)
                this.stopPlayback()
            else
                this.startPlayback()
        }
    }

    private fun startPlayback(initialMediaItem: MediaItem? = null, autoPlay: Boolean = true) {
        mediaSession?.player?.let { player ->
            // 1. Determinate the item to play
            val itemToPlay = initialMediaItem
                ?: playlistManager.getCurrent()
                ?: mediaRepository.getLastMediaItem()

            if (itemToPlay == null) {
                Log.w(TAG, "No item available to play, stopping playback.")
                stopPlayback()
                return
            }

            // 2. Save the item to the repository
            // to make sure that the last item played is always up to date in the repository
            //if (player.currentMediaItem?.mediaId != itemToPlay.mediaId) {
            //    repository.saveLastMediaItem(itemToPlay)
            //}

            // 3. Manage different scenarios for playback
            when {
                // Scenario 1: The item is already loaded and is the same as the current one
                player.currentMediaItem?.mediaId == itemToPlay.mediaId -> {
                    when (player.playbackState) {
                        Player.STATE_IDLE -> {
                            player.prepare()
                            if (autoPlay) player.play()
                        }
                        Player.STATE_ENDED -> {
                            player.seekTo(0)
                            if (autoPlay) player.play()
                        }
                        else -> {
                            if (autoPlay) player.play() // Resume playback if already playing
                        }
                    }
                }

                // Scenario 2: We need to load a new item or update the playlist
                else -> {
                    // Load full playlist from repository or create a new one
                    val fullPlaylist = mediaRepository.getMediaList().takeIf { it.isNotEmpty() }
                        ?: listOf(itemToPlay) // If the list is empty, add the initial item

                    val startIndex = fullPlaylist.indexOfFirst { it.mediaId == itemToPlay.mediaId }
                        .coerceAtLeast(0) // Ensure index is non-negative

                    // Update so that the internal PlaylistManager of the service
                    // always reflects the current playlist loaded in the ExoPlayer.
                    // This is key for the getNext() and getPrevious() methods
                    // of the PlaylistManager to work correctly:
                    playlistManager.setPlaylist(fullPlaylist, startIndex)

                    // Set the media items and prepare/start playback
                    player.setMediaItems(fullPlaylist, startIndex, 0L)
                    if (autoPlay) {
                        player.prepare()
                        player.play()
                    }
                }
            }
        }
    }

    private fun stopPlayback() {
        mediaSession?.player?.let { player ->
            val isLiveContent =
                player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("is_live", false) == true

            // Stop when is live, pause otherwise
            if (isLiveContent) player.stop()
            else {
                player.pause()
                // Save the current position for OnDemand content
                mediaRepository.saveLastPosition(player.currentPosition)
            }

            // If stopping completely, also clear saved position
            if (isLiveContent || player.playbackState == Player.STATE_ENDED) {
                mediaRepository.saveLastPosition(0L)
            }
        }
    }

    private fun skipToNextItem(play: Boolean = true) {
        Log.d(TAG, "skipToNextItem() called.")
        mediaSession?.player?.let {
            playlistManager.getNext()?.let { nextItem ->
                startPlayback(nextItem, play)
            } ?: run {
                // Handle end of playlist if not looping
                stopPlayback() // Stop playback at the end of the playlist
            }
            mediaRepository.saveLastPosition(0L) // Clear position
        }
    }

    private fun skipToPreviousItem(play: Boolean = true) {
        Log.d(TAG, "skipToPrevousItem() called.")
        mediaSession?.player?.let {
            playlistManager.getPrevious()?.let { prevItem ->
                startPlayback(prevItem, play)
            } ?: run {
                // Handle start of playlist if not looping backwards
                stopPlayback()
            }
            mediaRepository.saveLastPosition(0L) // Clear position
        }
    }


}