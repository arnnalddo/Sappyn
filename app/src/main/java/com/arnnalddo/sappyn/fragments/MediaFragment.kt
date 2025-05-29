package com.arnnalddo.sappyn.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arnnalddo.sappyn.R
import com.google.gson.JsonObject
import androidx.media3.common.util.UnstableApi
import com.arnnalddo.sappyn.utils.Util
import com.arnnalddo.sappyn.activity.MainActivity
import com.arnnalddo.sappyn.adapters.ListAdapter
import com.arnnalddo.sappyn.adapters.ListAdapter.ItemField
import com.arnnalddo.sappyn.adapters.MediaListAdapter
import com.arnnalddo.sappyn.adapters.MediaListAdapter.MediaListItem
import com.arnnalddo.sappyn.models.MediaViewModel
import com.koushikdutta.ion.Ion


/**
 * A fragment responsible for displaying a list of media items.
 * It fetches media data from an API or a local repository, handles user interactions like
 * item clicks for playback, and communicates with its hosting Activity via a [Listener] interface.
 *
 * This fragment extends [AppFragment] and implements [ListAdapter.ItemClicklistener]
 * to manage the list of media items and respond to clicks.
 *
 * Key functionalities include:
 * - Fetching media data from a specified API URL.
 * - Storing and retrieving media data from a local repository (via `MediaRepository`).
 * - Displaying media items in a `RecyclerView` using `MediaListAdapter`.
 * - Handling deep links to specific media items.
 * - Auto-playing the last played media item if configured.
 * - Notifying the hosting Activity about prepared media items and selected items for playback.
 * - Providing retry functionality for data loading failures.
 * - Sorting media items based on different criteria.
 *
 * The fragment uses a [MediaViewModel] to manage the state of the media list and sorting preferences.
 *
 * @see AppFragment
 * @see ListAdapter.ItemClicklistener
 * @see MediaListAdapter
 * @see MediaViewModel
 * @see MainActivity
 */
@OptIn(UnstableApi::class)
class MediaFragment : AppFragment(), ListAdapter.ItemClicklistener {
    interface Listener {
        fun onMediaItemsPrepared(mediaItems: List<MediaItem>)
        fun onMediaItemSelected(mediaItem: MediaItem, playNow: Boolean)
    }
    //**********************************************************************************************
    // region [Properties]
    override val items = ArrayList<MediaListItem>() // Usar MediaListItem específico
    override val apiUrl: String by lazy { arguments?.getString(ARG_API_URL) ?: "" }
    private lateinit var viewModel: MediaViewModel
    private var listener: Listener? = null
    private var shouldAutoPlay: Boolean = false
    var deepLinkMediaId: String? = null

    companion object {
        const val TAG = "MediaFragment      ▷"
        private const val MEDIA_API_ARRAY_NAME = "media"

        private const val ARG_API_URL = "api_url"
        private const val ARG_DEEP_LINK_MEDIA_ID = "deep_link_id"
        private const val ARG_SHOULD_AUTOPLAY = "should_autoplay"

        fun newInstance(
            apiUrl: String,
            deepLinkMediaId: String? = null,
            shouldAutoPlay: Boolean = false
        ): MediaFragment {
            return MediaFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_API_URL, apiUrl)
                    putString(ARG_DEEP_LINK_MEDIA_ID, deepLinkMediaId)
                    putBoolean(ARG_SHOULD_AUTOPLAY, shouldAutoPlay)
                }
            }
        }
    }
    // endregion


    //**********************************************************************************************
    // region [Fragment Lifecycle]
    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as Listener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called.")

        viewModel = ViewModelProvider(this)[MediaViewModel::class.java]

        arguments?.let {
            deepLinkMediaId = it.getString(ARG_DEEP_LINK_MEDIA_ID)
            shouldAutoPlay = it.getBoolean(ARG_SHOULD_AUTOPLAY, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        rootView = inflater.inflate(R.layout.fragment_service_media, container, false)
        // Find common UI elements and assign them to inherited properties
        rootView?.let {
            contentLoadingIndicator = it.findViewById(R.id.content_loading_indicator)
            contentLoadingText = it.findViewById(R.id.content_loading_text)
            contentLoadingRetryButton = it.findViewById(R.id.content_loading_retry_button)
            mainListView = it.findViewById(R.id.media_list)
        }
        return rootView
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated() called.")

        // Initialize the adapter once
        viewHolderAdapter = MediaListAdapter(requireContext(), items, this)
        mainListView?.apply {
            adapter = viewHolderAdapter
            setHasFixedSize(true)
        }

        // Load data only if empty
        if (items.isEmpty()) {
            Log.d(TAG, "Loading initial data...")
            loadInitialData()
        } else {
            Log.d(TAG, "There is data already. Updating UI...")
            sortItems(viewModel.currentSortField)
            viewHolderAdapter?.notifyDataSetChanged()
            handleInitialSelection()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart() called.")
        if (deepLinkMediaId != null && items.isNotEmpty()) {
            handleDeepLinkPlayback()
        } // If items is empty, drawList will handle it
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called.")
        // Ensure the correct item is selected in the list when fragment is resumed
        // This is handled in onViewCreated/handleAutoplayLastItem/handleDeepLinkPlayback
        // but a resume might also need a check if the last played item changed while in background.
        // Calling handleAutoplayLastItem again here ensures the selection is updated.
        //handleDeepLinkPlayback()
        //handleAutoplayLastItem()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView() called.")
        // Clear adapter and list references to avoid leaks
        mainListView?.adapter = null
        // items.clear() // Don't clear items here, needed if fragment view is recreated
    }
    // endregion


    //**********************************************************************************************
    // region [ListAdapter and ServiceFragment Implementation]

    // Implement onItemClick from ListAdapter.ItemClicklistener
    // This method now passes the item ID to the Activity
    override fun onItemClick(position: Int) {
        if (!isAdded || position < 0 || position >= items.size) {
            Log.w(TAG, "Invalid item position clicked: $position")
            return // Basic validation
        }

        val item = items[position]
        // Only handle clicks on ROW type items
        if (item.type == ListAdapter.ItemType.ROW) {
            Log.i(TAG, "Item clicked: ${item.name}, ID: ${item.mediaId} (isLive: ${item.isLive}).")
            item.toMediaItem()?.let { mediaItem ->
                // Inform the Activity to handle playback
                listener?.onMediaItemSelected(mediaItem, true)
            } ?: run {
                Toast.makeText(context, getString(R.string.playback_error_media_unavailable), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRetryButtonClick() {
        Log.d(TAG, "Retry button clicked. Attempting to reload data.")
        // Clear current items and fetch data again
        items.clear()
        viewHolderAdapter?.notifyDataSetChanged() // Clear the adapter display
        // Show loading indicators
        mainListView?.visibility = View.INVISIBLE
        contentLoadingText?.visibility = View.INVISIBLE
        contentLoadingRetryButton?.visibility = View.INVISIBLE
        contentLoadingIndicator?.visibility = View.VISIBLE
        // Trigger data fetching logic (from repository or API)
        loadInitialData()
    }

    override fun putItem(jsonObject: JsonObject?, jsonElementName: String?) {
        if (jsonObject == null) return

        try {
            // 1. Validate required fields
            val mediaId = jsonObject.get("id")?.asString?.takeIf {
                it.isNotEmpty()
            } ?: run {
                Log.w(TAG, "No id found, skipping item.")
                return
            }
            val sourceString = jsonObject.get("source")?.asString
            val sourceUri: Uri? = try {
                sourceString?.toUri() // Attempt to parse as Uri
            } catch (e: Exception) {
                Log.w(TAG, "Item $id: Failed to parse source URI string: $sourceString", e)
                null // Parsing failed
            }

            if (sourceUri == null || sourceString.isNullOrEmpty()) {
                Log.w(TAG, "Item $id has no valid source URI or failed to parse, skipping.")
                return
            }

            // Build the MediaItem
            val mediaItem = MediaItem.Builder().apply {
                setMediaId(mediaId)
                setUri(sourceUri) // Valid parsed Uri
                setMediaMetadata(
                    MediaMetadata.Builder().apply {
                        setTitle(jsonObject.get("name")?.asString)
                        setArtist(jsonObject.get("city")?.asString)
                        setStation(jsonObject.get("name")?.asString)
                        setArtworkUri(jsonObject.get("medium_image_uri")?.asString?.toUri())
                        // Combine all extras into a single Bundle
                        val extrasBundle = Bundle().apply {
                            jsonObject.get("small_image_uri")?.asString?.let { putString("small_image_uri", it) }
                            jsonObject.get("modulation")?.asString?.let { putString("modulation", it) }
                            jsonObject.get("region")?.asString?.let { putString("region", it) }

                            // Safely parse boolean values
                            try {
                                jsonObject.get("is_video")?.asBoolean?.let { putBoolean("is_video", it) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not parse is_video for item $id: ${e.message}")
                                putBoolean("is_video", false) // Provide a default value on error
                            }
                            try {
                                jsonObject.get("is_live")?.asBoolean?.let { putBoolean("is_live", it) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not parse is_live for item $id: ${e.message}")
                                putBoolean("is_live", false) // Provide a default value on error
                            }

                            // Add original source URI string to extras for reliable serialization
                            putString("original_source_uri_string", sourceString)
                        }
                        setExtras(extrasBundle)
                    }.build()
                )
            }.build()

            // Add the MediaItem to the list
            items.add(
                MediaListItem(
                    ListAdapter.ItemType.ROW,
                    mediaItem = mediaItem,
                    mediaId = mediaId,
                    mediaSource = sourceString
                )
            )


        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON object for item. JSON: $jsonObject", e)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun drawItems() {
        Log.d(TAG, "drawItems() called. Items count: ${items.size}")
        contentLoadingIndicator?.visibility = View.GONE // Hide loading indicator
        contentLoadingText?.visibility = View.GONE // Hide error text
        contentLoadingRetryButton?.visibility = View.GONE // Hide retry button

        if (items.isEmpty()) {
            showMessage(R.string.playback_error_no_items, null) // Show no items error
            return
        }

        sortItems(viewModel.currentSortField)
        //handleAutoplayLastItem()

        // Convert MediaListItem back to MediaItem for the Activity/Repository
        val mediaItems: List<MediaItem> = items
            .filter { it.type == ListAdapter.ItemType.ROW }
            .mapNotNull { it.toMediaItem() }

        Log.d(TAG, "Passing ${mediaItems.size} valid MediaItems to Activity for saving...")
        listener?.onMediaItemsPrepared(mediaItems)
    }

    override fun sortItems(by: ItemField) {
        val filteredItems = items.toList().filter { it.type == ListAdapter.ItemType.ROW }
        viewModel.setMediaItems(filteredItems, by)
    }

    // override fun showMessage(code: Int, extra: String?) { ... } // Implemented in ServiceFragment
    // override fun fetchItems(url: String) { ... } // Implemented in ServiceFragment
    // override fun showLoading(show: Boolean) { ... } // Implemented in ServiceFragment
    // override fun onClick(view: View?) { ... } // Implemented in ServiceFragment
    // endregion

    //**********************************************************************************************
    // region [Helper Methods]

    private fun loadInitialData() {
        Log.d(TAG, "loadInitialData() called.")
        viewModel.mediaItems.observe(viewLifecycleOwner) { items ->
            this.items.clear()
            this.items.addAll(items)
            viewHolderAdapter?.notifyDataSetChanged()
            //handleInitialSelection()
        }

        if (viewModel.mediaItems.value.isNullOrEmpty()) {
            Log.d(TAG, "No data in ViewModel. Loading from repository or API...")
            loadFromRepositoryOrApi()
        }
    }

    private fun loadFromRepositoryOrApi() {
        // Try repository first
        (activity as? MainActivity)?.mediaRepository?.getMediaList().let { savedMediaItems ->
            if (savedMediaItems != null && savedMediaItems.isNotEmpty()) {
                Log.d(TAG, "Try to use repository data.")
                // Convert MediaItem back to MediaListItem for the adapter
                items.clear()
                savedMediaItems.mapNotNull { mediaItem ->
                    // Ensure MediaItem is not null and has basic info
                    val mediaId = mediaItem.mediaId
                    val mediaSource = mediaItem.localConfiguration?.uri?.toString()
                    if (mediaId.isEmpty() || !Util.validate(mediaSource, Util.StringFormat.MEDIA_URI)) {
                        Log.w(TAG, "Skipping invalid MediaItem from repository with no ID or URI.")
                        null // Skip invalid items
                    } else {
                        MediaListItem(
                            type = ListAdapter.ItemType.ROW,
                            mediaItem = mediaItem,
                            mediaId = mediaId,
                            mediaSource = mediaSource
                        )
                    }
                }.let { validItems ->
                    items.addAll(validItems)
                    sortItems(viewModel.currentSortField) // Header
                    Log.d(TAG, "Done. Displayed ${items.filter { it.type == ListAdapter.ItemType.ROW }.size} items from repository.")
                    viewHolderAdapter?.notifyDataSetChanged()
                    handleInitialSelection()
                }
            } else {
                Log.d(TAG, "No data in repository, try to fetch from API...")
                fetchDataFromApi(apiUrl)
            }
        }

    }

    private fun handleInitialSelection() {
        // Handle deep link only if data is already loaded
        if (items.isNotEmpty()) {
            deepLinkMediaId?.let {
                handleDeepLinkPlayback()
            }
        }
        handleAutoplayLastItem()
    }

    // Add a method to specifically fetch data from the API (using Ion as suggested by commented out code in ServiceFragment)
    private fun fetchDataFromApi(url: String) {
        if (!isAdded) return
        if (!Util.validate(url, Util.StringFormat.WEB_URL)) {
            Log.w(TAG, "Invalid URL. Skipping fetch.")
            errorCode = 18
            errorMessageExtra = null
            showMessage(errorCode, null)
            return
        }

        // Show loading indicators only if list is currently empty (not just updating)
        if (items.isEmpty()) {
            mainListView?.visibility = View.INVISIBLE
            contentLoadingText?.visibility = View.INVISIBLE
            contentLoadingRetryButton?.visibility = View.INVISIBLE
            contentLoadingIndicator?.visibility = View.VISIBLE
        }

        Ion.with(this)
            .load(url)
            //.addQuery("lang", Util.getLanguage())
            .setTimeout(15000)
            .noCache() // Don't cache the API response in Ion, we handle repo caching
            .followRedirect(true)
            .asJsonArray()

            .setCallback { error, result ->
                // Hide loading indicator if it was shown
                contentLoadingIndicator?.visibility = View.INVISIBLE

                if (error != null) {
                    error.printStackTrace()
                    errorCode = 1
                    errorMessageExtra = ": ${error.message}"
                    showMessage(errorCode, errorMessageExtra)
                    return@setCallback
                }

                result?.let { jsonArray ->
                    try {
                        // Clear existing items before populating from API
                        items.clear()

                        // Get the first element in the JSONArray
                        if (jsonArray.size() > 0) {
                            val jsonObject = jsonArray[0].asJsonObject
                            val mediaArray = jsonObject.getAsJsonArray(MEDIA_API_ARRAY_NAME)

                            // Process the JsonArray and populate the 'items' list
                            mediaArray?.forEach { jsonElement ->
                                if (jsonElement.isJsonObject) {
                                    putItem(jsonElement.asJsonObject)
                                }
                            }

                            Log.d(TAG, "API response processed with ${items.size} items.")
                        }

                        // After populating 'items', call drawList to update the UI and save to repo
                        drawList()
                        handleInitialSelection()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing API response.", e)
                        errorCode = 1
                        errorMessageExtra = ": Error al procesar datos de la API: " + e.message // todo: translate message
                        showMessage(errorCode, errorMessageExtra)
                    }
                } ?: run {
                    // API returned null result
                    Log.w(TAG, "API returned null result.")
                    errorCode = 1
                    errorMessageExtra = ": No se recibieron datos de la API." // todo: translate message
                    showMessage(errorCode, errorMessageExtra)
                }
            }
    }

    // Helper function to handle deep link playback when the list is loaded
    private fun handleDeepLinkPlayback() {
        deepLinkMediaId?.let { id ->
            Log.d(TAG, "Handling deep link playback for ID: $id")
            items.find { it.id == id }?.let { item ->
                item.mediaItem?.let {
                    Log.i(TAG, "Found deep link for: ${item.name}. Initiating playback via Activity...")
                    listener?.onMediaItemSelected(item.mediaItem, playNow = true)
                }
            }
            deepLinkMediaId = null
        } ?: run {
            Log.w(TAG, "Deep link item not found in loaded list, informing user...")
            Toast.makeText(context, getString(R.string.playback_error_media_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    // Helper function to handle autoplay of the last item on first activity launch
    private fun handleAutoplayLastItem() {
        //if (!shouldAutoPlay) return

        val lastPlayedMediaId = (activity as? MainActivity)
            ?.mediaRepository
            ?.getLastPlayedMediaId()
            ?: return

        Log.d(TAG, "Found last played media ID from Activity/Repo: $lastPlayedMediaId")
        items.firstOrNull { it.id == lastPlayedMediaId }?.let { item ->
            item.mediaItem?.let {
                Log.d(
                    TAG,
                    "${if (shouldAutoPlay) "Auto-playing" else "Prepare"} last played media: ${item.name}"
                )
                listener?.onMediaItemSelected(item.mediaItem, playNow = shouldAutoPlay)
            }
        }
    }

    // Called by MainActivity to select an item in the list UI by its ID
    fun setSelectedItemIdInList(itemId: String?) {
        (viewHolderAdapter as? MediaListAdapter)?.setSelectedItemId(itemId)
        // Optional: Scroll to the item if it exists
        /*if (itemId != null) {
            val selectedItemPosition = items.indexOfFirst { it.id == itemId }
            if (selectedItemPosition != -1) {
                mainListView?.scrollToPosition(selectedItemPosition)
            }
        }*/
    }

    // Called by MainActivity to deselect all items in the list UI
    /*fun deselectAllItems() {
        Log.d(TAG, "deselectAllItems() called.")
        (viewHolderAdapter as? MediaListAdapter)?.setSelectedItemId(null)
    }*/

    fun MediaListItem.toMediaItem(): MediaItem? {
        val mediaId = this.id
        val mediaSource = this.source

        // Validate essential fields before creating MediaItem
        if (mediaId.isNullOrEmpty() || !Util.validate(mediaSource, Util.StringFormat.MEDIA_URI)) {
            Log.w(TAG, "Skipping invalid MediaListItem: ID=${mediaId}, Source=${mediaSource}")
            return null // Skip invalid items
        } else {
            try {
                val sourceUri = mediaSource?.toUri() // Convert to Uri
                return MediaItem.Builder().apply {
                    setMediaId(mediaId)
                    setUri(sourceUri)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(this@toMediaItem.name)
                            setArtist(this@toMediaItem.city)
                            setStation(this@toMediaItem.name)
                            setArtworkUri(this@toMediaItem.mediumImageUri?.toUri())
                            // Extra metadata:
                            setExtras(Bundle().apply {
                                putString("small_image_uri", this@toMediaItem.smallImageUri)
                                putString("region", this@toMediaItem.region)
                                putString("modulation", this@toMediaItem.modulation)
                                putBoolean("is_video", this@toMediaItem.isVideo)
                                putBoolean("is_live", this@toMediaItem.isLive)
                            })
                        }.build()
                    )
                }.build()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse source URI string for MediaItem: $mediaSource", e)
                return null // Skip if URI parsing fails
            }
        }
    }
    // endregion
}