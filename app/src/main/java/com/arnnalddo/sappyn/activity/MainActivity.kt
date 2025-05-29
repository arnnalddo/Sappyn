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

package com.arnnalddo.sappyn.activity

import android.content.*
import android.net.Uri
import android.os.*
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentTransaction
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.arnnalddo.sappyn.MediaRepository
import com.arnnalddo.sappyn.MediaService
import com.arnnalddo.sappyn.R
import com.arnnalddo.sappyn.utils.Util
import com.arnnalddo.sappyn.fragments.FragmentListener
import com.arnnalddo.sappyn.fragments.MainFragment
import com.arnnalddo.sappyn.fragments.MediaFragment
import com.arnnalddo.sappyn.utils.NotificationUtils
import com.arnnalddo.sappyn.utils.TypefaceUtils
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.koushikdutta.ion.Ion
import java.util.Calendar


/**
 * The main activity of the application.
 *
 * This activity serves as the primary entry point and container for the app's UI.
 * It manages fragment transactions, handles media playback control through a [MediaController],
 * and interacts with [MediaService] for background playback.
 *
 * Implements:
 * - [FragmentListener]: To handle callbacks from its child fragments, such as task completion
 *   and media item preparation.
 * - [MediaFragment.Listener]: To receive media item selection events from the [MediaFragment].
 * - [Player.Listener]: To listen to events from the Media3 [Player] instance, allowing UI updates
 *   based on playback state, metadata changes, and errors.
 *
 * Key Responsibilities:
 * - **Fragment Management**: Switches between [MainFragment] and [MediaFragment] based on app state.
 * - **Media Control**: Initializes and manages a [MediaController] to communicate with the
 *   [MediaService]. This includes connecting, disconnecting, and sending playback commands.
 * - **UI Updates**: Reflects the player's state (playing, paused, buffering, error) and current
 *   media metadata (title, artist, artwork) in the player control UI.
 * - **Deep Link Handling**: Processes incoming intents with specific URI schemes to potentially
 *   start playback of a specific media item.
 * - **Lifecycle Management**: Properly handles Android activity lifecycle events to manage
 *   resources, save/restore state, and ensure smooth media playback transitions (e.g., when the
 *   app goes to the background).
 * - **User Interaction**: Manages click events on player controls (play/pause/stop button) and
 *   provides touch feedback.
 * - **Error Handling**: Displays error messages to the user if playback fails.
 * - **Animation**: Uses animations for showing the player layout and for the player control button
 *   states.
 * - **Persistence**: Utilizes [MediaRepository] to save and retrieve the list of media items and
 *   the last played item, enabling playback resumption.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), FragmentListener, MediaFragment.Listener, Player.Listener {
    //**********************************************************************************************
    // region [Properties]
    companion object {
        private const val TAG = "MainActivity       ✧"

        // To temporaly save URL Scheme (case sensitive) received by intent
        private var deepLinkMediaId: String? = null

        // Flags to track first launch and autoplay preference
        var isActivityInitialRun = true // Flag to track first app launch (but not first time app)
    }

    // UI elements
    private lateinit var binding: com.arnnalddo.sappyn.databinding.ActivityMainBinding
    private var fragmentTransaction: FragmentTransaction? = null

    // Media3 properties
    private var mediaServiceIntent: Intent? = null // Intent to start the MediaService
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null // Controller to interact with the Player
    private var isPlayerBound = false // Flag to indicate if the MediaController is connected

    // Animations for player button
    private var fabRotationInAnim: Animation? = null // For button initial rotation
    private var fabRotationAnim: Animation? = null   // For indefinite rotation while buffering
    private var fabInAnim: Animation? = null         // For button initial scale when not buffering

    // Keep track of the last artwork URI to avoid unnecessary reloading
    private var lastArtworkUri: Uri? = null

    // Keep track of the last item ID to reset list item selection
    private var lastTransitionItemId: String? = null

    // Repository for saving/loading playback state and media list
    lateinit var mediaRepository: MediaRepository // Make repository accessible for fragment
    //private var currentMediaItems: List<MediaItem> = emptyList()

    // Flag to check if player layout has been shown animated for the first time
    private var isPlayerLayoutInitiallyShownAnimated = false

    // endregion

    //**********************************************************************************************
    // region [Activity Lifecycle]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = com.arnnalddo.sappyn.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize MediaRepository
        mediaRepository = MediaRepository.create(applicationContext)

        // Load animations
        fabRotationInAnim = AnimationUtils.loadAnimation(this, R.anim.rotation_in_anim)
        fabRotationAnim = AnimationUtils.loadAnimation(this, R.anim.rotation_anim)
        fabInAnim = AnimationUtils.loadAnimation(this, R.anim.in_anim)

        // Load and set typefaces
        val primaryTypeface: String = getString(R.string.app_primary_font_uri) // Regular
        val secondaryTypeface: String = getString(R.string.app_secondary_font_uri) // Bold
        binding.playerLayout.playerTitleView.let { TypefaceUtils.setTypeface(this, secondaryTypeface, it) }
        binding.playerLayout.playerSubtitleView.let { TypefaceUtils.setTypeface(this, primaryTypeface, it) }
        binding.playerLayout.playerStatusView.let { TypefaceUtils.setTypeface(this, primaryTypeface, it) }

        // Setup the footer text with links and typefaces
        binding.footerTv.apply {
            TypefaceUtils.setTypeface(this@MainActivity, primaryTypeface, this@apply)
            val footer: String = "© " + Calendar.getInstance().get(Calendar.YEAR) + " " + getString(R.string.app_name) + ". " +
                    getString(R.string.reserved_rights) +
                    ". <a href=\"" + getString(R.string.app_privacy_uri) + "\">" + getString(R.string.terms_of_use) + "</a>."
            append(HtmlCompat.fromHtml(footer, HtmlCompat.FROM_HTML_MODE_LEGACY))
            movementMethod = LinkMovementMethod.getInstance()
        }

        // Configure click listener for the player toggle button
        binding.playerLayout.playerToggleButton.apply {
            setOnClickListener {
                mediaController?.let { controller ->
                    Log.i(TAG, "Play/Pause button clicked. Sending TOGGLE_PLAYBACK custom command.")
                    // Send a custom command to the MediaSession to toggle playback
                    // The Service's onCustomCommand will receive this and call startPlayback/stopPlayback
                    controller.sendCustomCommand(SessionCommand(
                        Util.COMMAND_TOGGLE_PLAYBACK,
                        Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                } ?: run {
                    Log.w(TAG, "Play/Pause button clicked but mediaController is null. Player is not ready.")
                    // Optionally show a message to the user that the player is not ready
                    // Toast.makeText(this, "Player is not ready.", Toast.LENGTH_SHORT).show()
                }
            }
            var touchStartedInside = false
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartedInside = true
                        view.animate()
                            .scaleX(0.9f)
                            .scaleY(0.9f)
                            .alpha(0.8f)
                            .setDuration(100)
                            .start()
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // Verify if the touch is inside the button
                        val x = event.x
                        val y = event.y
                        touchStartedInside = x >= 0 && x < view.width && y >= 0 && y < view.height
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(150)
                            .withEndAction {
                                // Execute click only if the touch started inside the button
                                if (touchStartedInside) {
                                    performClick()
                                    updatePlayPauseButton(mediaController?.isPlaying == true, mediaController?.playbackState ?: Player.STATE_IDLE)
                                }
                            }
                            .start()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(100)
                            .start()
                        true
                    }
                    else -> false
                }
            }
        }

        //handleIntent(intent)

        if (savedInstanceState == null) {
            Log.d(TAG, "onCreate() - First launch detected.")
            isActivityInitialRun = true

            // Start with MainFragment to load basic settings and check for updates
            onTaskFinished()
        }

        // Handle back button press
        val callback = object : OnBackPressedCallback(true /* enabled by default */) {
            override fun handleOnBackPressed() {
                if (mediaController?.isPlaying == true || mediaController?.playbackState == Player.STATE_BUFFERING) {
                    try {
                        moveTaskToBack(true)
                        Log.i(TAG, "User press back button while player is playing or buffering, skipping back and moving to background.")
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                } else {
                    Log.i(TAG, "User press back button while player is not playing or buffering, finishing activity...")
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)

    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        //Log.d(TAG, "onStart() called.")
        /*
        For the first time that the activity is created,
        MediaFragment will handle autoplay or playback preparation,
        after loading the list and checking whether
        the item to be played is available or not.
        Then we'll try to connect to the MediaSession and start the service
        or when the user press an item to play. */
        mediaRepository.getLastPlayedMediaId()?.let {
            if (!isActivityInitialRun && !isPlayerBound) {
                Log.d(TAG, "Last played media ID: $it")
                if (it != lastTransitionItemId)
                    setSelectedItemIdInList(it) // Restore list item selection
                connectToMediaSession() // Connect only if there's a last played item
            }
        }

        handleIntent(intent)

        if (NotificationUtils.isPowerSaveModeActive(this)) {
            Toast.makeText(this, getString(R.string.txt_power_save), Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop() called.")
        // Remove listener here
        mediaController?.removeListener(this)
        isPlayerBound = false
        isActivityInitialRun = false
        lastTransitionItemId = mediaController?.currentMediaItem?.mediaId
        Log.d(TAG, "Last transition item ID: $lastTransitionItemId")
        // Don't disconnect controller here if service might continue playback in background
        // Disconnection should happen when the service is stopped or no longer needed
        // For now, let's keep it connected while the activity is in the background
        // disconnectFromMediaSession() // Call this only when truly finished with the session
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called.")
        // Disconnect from MediaSession when the activity is destroyed
        disconnectFromMediaSession()

        // Stop the service if it's not playing and not intended to run in the background
        // This logic might need refinement based on desired background playback behavior.
        // For now, let's assume the service should stop if the activity is destroyed
        // AND the player is not currently playing.
        if (mediaServiceIntent != null && mediaController?.isPlaying != true) {
            stopService(mediaServiceIntent)
            Log.d(TAG, "MediaService stopped.")
            mediaServiceIntent = null // Clear intent after stopping service
        }

        super.onDestroy()
    }
    // endregion

    //**********************************************************************************************
    // region [MediaSession and Controller Management]

    // Method to connect to the MediaSessionService
    private fun connectToMediaSession(onConnected: (() -> Unit)? = null) {
        mediaController?.let {
            Log.i(TAG, "Already connected to the Media Session, restoring UI state and executing callback immediately if provided...")
            it.addListener(this) // Restore the listener
            isPlayerBound = true
            runOnUiThread {
                showPlayerLayout(false)
                updateUiFromController() // Restore UI state
            }
            onConnected?.invoke() // Execute the optional callback
            return
        }

        Log.i(TAG, "Connecting to MediaSession...")
        mediaController = null // reset
        isPlayerBound = false // reset
        val sessionToken = SessionToken(this, ComponentName(this, MediaService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            try {
                mediaController = mediaControllerFuture?.get()
                mediaController?.addListener(this) // Add this activity as a player listener
                isPlayerBound = true
                Log.i(TAG, "MediaController connected.")

                // Update UI immediately after connection based on the current state
                runOnUiThread {
                    showPlayerLayout(true)
                    updateUiFromController() // Restore UI state
                }
                //handleInitialPlaybackOrSelection()

                // Execute the optional callback after successful connection
                onConnected?.invoke()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to MediaController.", e)
                runOnUiThread { updateUiFromController() } // Update UI on error
                mediaController = null
                isPlayerBound = false
            }
        }, MoreExecutors.directExecutor()) // Use directExecutor for immediate execution
    }

    // Method to disconnect from the MediaSession
    private fun disconnectFromMediaSession() {
        isPlayerBound = false
        mediaControllerFuture?.let { future ->
            if (!future.isDone) {
                future.cancel(true)
            }
            mediaControllerFuture = null
        }
        mediaController?.removeListener(this) // Remove this activity as a listener
        mediaController?.release() // Release the controller
        mediaController = null
        Log.d(TAG, "MediaController disconnected.")
    }
    // endregion

    //**********************************************************************************************
    // region [Fragment Callback Implementation]

    // FragmentCallback interface implementation for switching fragments
    override fun onTaskFinished(fragment: FragmentListener.Fragment?) {
        fragmentTransaction = supportFragmentManager.beginTransaction()
        //fragmentTransaction?.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        // Use replace to swap fragments in the container
        when (fragment) {
            // When MainFragment has finishes task, set MainFragment
            FragmentListener.Fragment.MAIN_FRAGMENT -> {
                // Pass arguments to MediaFragment
                val shouldAutoPlay = getAutoplayPreference()
                MediaFragment.newInstance(
                    apiUrl = getString(R.string.app_main_api_uri),
                    deepLinkMediaId = deepLinkMediaId,
                    shouldAutoPlay = isActivityInitialRun && shouldAutoPlay
                ).also {
                    deepLinkMediaId = null // Clear after handling
                }
            }
            else -> MainFragment() // Default fragment
        }.let { frag ->
            //fragmentoTransaccion?.addToBackStack(null) // To navigate back with back button
            fragmentTransaction?.replace(R.id.fragment_container, frag)
            fragmentTransaction?.commitAllowingStateLoss()
        }
    }

    // FragmentCallback interface implementation to receive list data
    override fun onMediaItemsPrepared(mediaItems: List<MediaItem>) {
        if (mediaItems.isEmpty()) {
            Log.w(TAG, "Received empty media list from fragment")
            return
        }
        Log.d(TAG, "Received media list from fragment (${mediaItems.size} items). First item URI: ${mediaItems.first().localConfiguration?.uri}")
        //this.currentMediaItems = mediaItems
        mediaRepository.saveMediaList(mediaItems)
    }

    // FragmentCallback interface implementation to receive item selection (by ID)
    override fun onMediaItemSelected(mediaItem: MediaItem, playNow: Boolean) {
        Log.d(TAG, "onMediaItemSelected() called with ID: ${mediaItem.mediaId}, playNow: $playNow")
        val currentItemId = mediaRepository.getLastPlayedMediaId()
        if (currentItemId == mediaItem.mediaId) {
            mediaController?.let {
                if (it.isPlaying || it.playbackState == Player.STATE_BUFFERING) {
                    Log.d(TAG, "Item already playing.")
                    return
                }
            }
        }
        // 2. Start the MediaService if not already running
        if (mediaServiceIntent == null) {
            mediaServiceIntent = Intent(this, MediaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(mediaServiceIntent)
            } else {
                startService(mediaServiceIntent)
            }
            // TODO: asegurar la llamada de startForeground() en el servicio
        }
        // 3. Connect to the MediaSession if not already connected
        if (!isPlayerBound || mediaController == null) {
            connectToMediaSession {
                handleMediaItemSelection(mediaItem = mediaItem, autoPlay = playNow)
            }
        } else {
            handleMediaItemSelection(mediaItem = mediaItem, autoPlay = playNow)
        }
        // 4. Update UI to show player layout soon as possible if not already shown
        /*if (!isPlayerLayoutInitiallyShownAnimated) {
            showPlayerLayout(animated = true)
        }*/
    }
    // endregion

    //**********************************************************************************************
    // region [Player Callback Implementation (Refactored UI Updates)]
    override fun onEvents(player: Player, events: Player.Events) {
        val started = player.isPlaying || player.playbackState == Player.STATE_BUFFERING

        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            Log.d(TAG, "onEvents: EVENT_IS_PLAYING_CHANGED. New state: ${player.isPlaying}")
            runOnUiThread {
                // Update player state for live content // TODO (test test and test)
                if (player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("is_live") == true)
                    updatePlaybackStatus(player.playbackState, started, player.playerError != null)
                // Update just the play/pause button for OnDemand content
                updatePlayPauseButton(started, player.playbackState)
            }
        }
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
            Log.d(TAG, "onEvents: EVENT_PLAYBACK_STATE_CHANGED. New state: ${player.playbackState}")

            // Update status text, animations, and potentially hide player on idle/ended
            runOnUiThread {
                updatePlaybackStatus(player.playbackState, started, player.playerError != null)
                updatePlayPauseButton(started, player.playbackState) // Also update button icon
                // Handle player layout visibility based on playback state and presence of a current item
                val shouldShowPlayer = /*(player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) ||*/ player.currentMediaItem != null
                if (shouldShowPlayer) {
                    // Show without animation on general state changes, animation is for first show from item click.
                    showPlayerLayout(animated = false)
                } else {
                    hidePlayerLayout()
                }
            }
        }
        if (events.contains(Player.EVENT_PLAYER_ERROR)) {
            // Log the error details for debugging
            player.playerError?.let {
                Log.e(TAG, "Playback error details:", it)
                runOnUiThread { Toast.makeText(this, "Playback Error: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            runOnUiThread {
                updatePlaybackStatus(player.playbackState, started, true) // Indicate error state in status
                // Keep player visible with error status if there was a media item, hide otherwise.
                val shouldShowPlayer = player.currentMediaItem != null
                if (shouldShowPlayer) showPlayerLayout(animated = false) else hidePlayerLayout()
            }
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            Log.d(TAG, "onEvents: EVENT_MEDIA_ITEM_TRANSITION. New item: ${player.currentMediaItem?.mediaMetadata?.title}")
            updatePlaybackStatus(player.playbackState, started, false) // false to clear error state
            setSelectedItemIdInList(player.currentMediaItem?.mediaId)  // Update list item selection
            lastTransitionItemId = player.currentMediaItem?.mediaId // Update last transition ID
            // Metadata will change, which triggers EVENT_MEDIA_METADATA_CHANGED. Handle update ↴
        }
        if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
            Log.d(TAG, "onEvents: EVENT_MEDIA_METADATA_CHANGED. Metadata: ${player.mediaMetadata.title} - ${player.mediaMetadata.artist}.")
            runOnUiThread {
                player.currentMediaItem?.mediaMetadata?.let {
                    handleMetadataDisplay(it) // Update title, artist, artwork based on new metadata
                    loadArtwork(it.artworkUri) // Load artwork for the new item
                } ?: run {
                    // If metadata becomes null (e.g., at end of playlist), clear UI
                    handleMetadataDisplay(MediaMetadata.EMPTY)
                    loadArtwork(null)
                }
            }
        }
    }
    // endregion

    //**********************************************************************************************
    // region [Helper Functions]
    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == getString(R.string.app_scheme) && uri.host == getString(R.string.app_media_host)) {
                deepLinkMediaId = uri.lastPathSegment?.takeIf { it.isNotBlank() }
                Log.i(TAG, "New deep link received with media ID: $deepLinkMediaId")

                // Handle deep link in the fragment
                (supportFragmentManager.findFragmentById(R.id.fragment_container)?.let {
                    if (it is MediaFragment && deepLinkMediaId != null) {
                        Log.d(TAG, "Telling the fragment to handle the deep link...")
                        it.deepLinkMediaId = deepLinkMediaId
                        deepLinkMediaId = null // Reset after handling
                    }
                })
            }
        }
    }

    // Helper function to handle media item selection
    private fun handleMediaItemSelection(mediaItem: MediaItem, autoPlay: Boolean = true) {
        mediaController?.let { controller ->
            val args = Bundle().apply {
                putString(Util.EXTRA_MEDIA_ID, mediaItem.mediaId)
                putBoolean(Util.EXTRA_MEDIA_AUTOPLAY, autoPlay)
            }
            controller.sendCustomCommand(SessionCommand(Util.COMMAND_START_PLAYBACK, Bundle.EMPTY), args)
        } ?: run {
            Toast.makeText(this, getString(R.string.playback_error_player_not_ready), Toast.LENGTH_SHORT).show()
        }
    }

    // Helper function to show the player layout with optional animation
    private fun showPlayerLayout(animated: Boolean) {
        if (!binding.playerLayout.root.isVisible) {
            if (animated && !isPlayerLayoutInitiallyShownAnimated) {
                Log.d(TAG, "Showing player layout with animation.")
                binding.playerLayout.root.apply {
                    alpha = 0f
                    translationY = 50f
                    visibility = View.VISIBLE
                    animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(200)
                        .withEndAction {
                            // Set the flag after the first animation
                            isPlayerLayoutInitiallyShownAnimated = true
                        }
                        .start()
                }
            } else {
                Log.d(TAG, "Showing player layout without animation.")
                binding.playerLayout.root.visibility = View.VISIBLE
            }
        }
    }

    // Helper function to hide the player layout
    private fun hidePlayerLayout() {
        if (binding.playerLayout.root.isVisible) {
            Log.d(TAG, "Hiding player layout.")
            // Optional: Add a fade-out or slide-down animation if desired
            binding.playerLayout.root.visibility = View.GONE
        }
    }

    // Simplified updateUiFromController focuses on showing/hiding layout and triggering metadata/state updates
    private fun updateUiFromController(error: Boolean = false) {
        mediaController?.let { controller ->
            // Player layout visibility is now handled directly in onPlaybackStateChanged and handleInitialPlaybackOrSelection
            // It's kept visible if there's a current item or if not in terminal idle/ended state.

            // Update metadata display based on the current item's metadata
            controller.currentMediaItem?.mediaMetadata?.let {
                handleMetadataDisplay(it) // Update title, artist, artwork
                loadArtwork(it.artworkUri) // Load artwork
            } ?: run {
                // If no current item metadata, clear metadata display
                Log.d(TAG, "No current MediaItem metadata. Clearing UI.")
                handleMetadataDisplay(MediaMetadata.EMPTY) // Pass empty metadata to clear UI
                loadArtwork(null) // Clear artwork
            }

            // Update playback controls and status based on player state
            updatePlayPauseButton(controller.isPlaying, controller.playbackState)
            updatePlaybackStatus(controller.playbackState, controller.isPlaying, error || controller.playerError != null)

        } ?: run {
            // MediaController is null (initial state or released)
            hidePlayerLayout() // Ensure player UI is hidden
            // Optionally load last saved metadata for initial display if needed before playback starts
            // This is now handled in handleInitialPlaybackOrSelection().
            // Reset playback controls to default (play icon)
            updatePlayPauseButton(false, Player.STATE_IDLE)
            updatePlaybackStatus(Player.STATE_IDLE, false, false)
        }
    }

    // New helper function to update *only* the play/pause/stop button icon
    private fun updatePlayPauseButton(isPlaying: Boolean, playbackState: Int) {
        binding.playerLayout.playerToggleButton.setImageResource(
            when {
                isPlaying || playbackState == Player.STATE_BUFFERING -> R.drawable.ic_media_stop // Show stop when playing
                else -> R.drawable.ic_media_play // Show play in other states (paused, idle, ended)
            }
        )
    }

    // Helper function to update playback status text and main button animations
    private fun updatePlaybackStatus(playbackState: Int, isPlaying: Boolean, error: Boolean) {
        // Clear existing animations to prevent conflicts
        binding.playerLayout.playerToggleButton.clearAnimation()
        fabRotationInAnim?.setAnimationListener(null)

        val hasSubtitle = !binding.playerLayout.playerSubtitleView.text.isNullOrEmpty()

        when (playbackState) {
            Player.STATE_BUFFERING -> {
                binding.playerLayout.playerStatusView.text = getString(R.string.playback_state_loading)
                binding.playerLayout.playerStatusView.visibility = View.VISIBLE
                binding.playerLayout.playerSubtitleView.visibility = View.GONE // Hide subtitle while buffering
                fabRotationInAnim?.apply {
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationRepeat(animation: Animation?) {}
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            fabRotationAnim?.let { rotate ->
                                binding.playerLayout.playerToggleButton.startAnimation(rotate) // Start indefinite rotation
                            }
                            setAnimationListener(null) // Remove this listener
                        }
                    })
                }
                binding.playerLayout.playerToggleButton.startAnimation(fabRotationInAnim) // Start fade-in rotation
            }
            Player.STATE_READY -> {
                if (isPlaying) {
                    binding.playerLayout.playerStatusView.visibility = View.GONE // Hide status text when playing
                    // Restore subtitle visibility based on whether handleMetadataDisplay sets text
                    binding.playerLayout.playerSubtitleView.visibility =
                        if (hasSubtitle) View.VISIBLE else View.GONE
                } else { // Paused state
                    binding.playerLayout.playerStatusView.visibility = View.GONE // Hide status text when paused
                    // Restore subtitle visibility
                    binding.playerLayout.playerSubtitleView.visibility =
                        if (hasSubtitle) View.VISIBLE else View.GONE
                }
                fabInAnim?.let { binding.playerLayout.playerToggleButton.startAnimation(it) } // Fade-in animation
            }
            Player.STATE_ENDED, Player.STATE_IDLE -> {
                fabInAnim?.let { binding.playerLayout.playerToggleButton.startAnimation(it) } // Fade-in animation
                binding.playerLayout.playerSubtitleView.visibility = View.GONE
                if (error) {
                    binding.playerLayout.playerStatusView.text = getString(R.string.playback_error_unexpected_end)
                    binding.playerLayout.playerStatusView.visibility = View.VISIBLE
                    //binding.playerLayout.playerSubtitleView.visibility = View.GONE // Hide subtitle on error/end
                } else {
                    // In IDLE or ENDED state without error, hide status text.
                    binding.playerLayout.playerStatusView.visibility = View.GONE
                    // Subtitle visibility will be handled by handleMetadataDisplay when metadata is cleared
                    // or reset to station name.
                    // Hide the entire player layout is now handled in onPlaybackStateChanged.
                }
            }
        }
    }

    // This function is called when metadata changes or when setting initial UI state.
    private fun handleMetadataDisplay(mediaMetadata: MediaMetadata) {
        val isLiveContent = mediaMetadata.extras?.getBoolean("is_live", false) == true
        val stationName = mediaMetadata.station?.toString()
        val songTitle = mediaMetadata.title?.toString()
        val artistName = mediaMetadata.artist?.toString()
        //val artworkUri = mediaMetadata.artworkUri

        Log.d(TAG, "handleMetadataDisplay called. Live: $isLiveContent, Station: $stationName, Title: $songTitle, Artist: $artistName")

        // Handle TITLE
        binding.playerLayout.playerTitleView.apply {
            text = when {
                !isLiveContent && !songTitle.isNullOrEmpty() -> songTitle
                !stationName.isNullOrEmpty() -> stationName
                else -> getString(R.string.app_name)
            }
            if (!isSelected) isSelected = true
        }

        // Handle SUBTITLE
        binding.playerLayout.playerSubtitleView.apply {
            text = if (isLiveContent) {
                when {
                    songTitle == stationName -> getString(R.string.playback_state_live)
                    !songTitle.isNullOrEmpty() && !artistName.isNullOrEmpty() ->
                        "$songTitle - $artistName"
                    !songTitle.isNullOrEmpty() -> songTitle
                    !artistName.isNullOrEmpty() -> artistName
                    else -> getString(R.string.playback_state_live)
                }
            } else {
                artistName ?: getString(R.string.playback_state_playing)
            }
            if (!isSelected) isSelected = true
        }

        /*binding.playerLayout.playerSubtitleView.visibility =
            if (!isLiveContent && !subtitleText.isNullOrEmpty()) View.VISIBLE
            else View.GONE*/

        // Load artwork
        //loadArtwork(artworkUri)
    }

    // Refactored loadArtwork to use Ion for loading and caching
    private fun loadArtwork(uri: Uri?) {
        // Use Ion to load and cache artwork
        if (uri == null) {
            Log.d(TAG, "loadArtwork called with null uri. Using default artwork.")
            binding.playerLayout.playerImageView.setImageResource(R.drawable.placeholder_album) // Use app logo as default
            lastArtworkUri = null // Clear last artwork URI
            return
        }

        // Avoid reloading the same artwork if it's already set
        if (uri == lastArtworkUri) {
            Log.d(TAG, "Artwork uri is the same as last loaded. Skipping load.")
            return
        }

        lastArtworkUri = uri // Update last loaded URI

        Log.d(TAG, "Loading artwork for uri: $uri")

        Ion.with(binding.playerLayout.playerImageView)
            .placeholder(R.drawable.placeholder_album) // Placeholder while loading
            .error(R.drawable.placeholder_album) // ion handles the error case
            .fadeIn(true) // FadeIn after loading
            .crossfade(true) // Crossfade between images
            .load(uri.toString())
    }

    // Helper function to access autoplay preference
    private fun getAutoplayPreference(): Boolean {
        return getSharedPreferences(Util.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(Util.PREF_KEY_AUTOPLAY_ENABLED, false)
    }

    // Called by MediaFragment to select an item by ID in the adapter UI
    private fun setSelectedItemIdInList(itemId: String?) {
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? MediaFragment)?.setSelectedItemIdInList(itemId)
    }
    // endregion
}