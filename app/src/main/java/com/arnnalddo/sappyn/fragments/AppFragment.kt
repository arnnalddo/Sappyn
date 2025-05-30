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

package com.arnnalddo.sappyn.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.arnnalddo.sappyn.R
import com.arnnalddo.sappyn.utils.Util
import com.arnnalddo.sappyn.adapters.ListAdapter
import com.arnnalddo.sappyn.utils.TypefaceUtils
import com.google.gson.*


/**
 * Abstract base class for fragments within the application.
 *
 * This class provides common functionality for fragments, including:
 * - Handling of common UI elements like loading indicators, retry buttons, and RecyclerViews.
 * - Basic error handling and message display.
 * - SharedPreference access.
 * - Click handling for the retry button.
 *
 * Subclasses are expected to:
 * - Inflate their own layout and assign it to [rootView] in `onCreateView`.
 * - Implement [drawItems] to populate the [items] list and update the UI.
 * - Implement [sortItems] to define sorting logic for the list items.
 * - Optionally override [onRetryButtonClick] to customize the retry behavior.
 * - Optionally override [putItem] to handle individual item processing from JSON.
 *
 * The class manages the visibility of loading indicators and error messages based on the
 * state of data loading and the presence of errors.
 */
abstract class AppFragment : Fragment(), View.OnClickListener {
    //**********************************************************************************************
    // region [Properties]
    // UI elements
    var rootView : View? = null // Main view
    var contentLoadingIndicator : ProgressBar? = null
    var contentLoadingText : TextView? = null
    var contentLoadingRetryButton : Button? = null
    var mainListView : RecyclerView? = null
    var viewHolderAdapter : RecyclerView.Adapter<*>? = null
    // Ensure items type is generic or specific to the adapter
    // The concrete fragment will provide the specific type (e.g., ArrayList<MediaListAdapter.Item>)
    open val items : ArrayList<*> = ArrayList<ListAdapter.Item>()

    // Others
    var preferences: SharedPreferences? = null
    protected var errorCode = 0
    private var errorMenssage: String? = null
    protected var errorMessageExtra: String? = null
    //protected open var fragmentListener: FragmentListener? = null
    protected open val apiUrl: String = ""
    // endregion

    //**********************************************************************************************
    // region [Fragment Lifecycle]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = context?.applicationContext?.getSharedPreferences(
            Util.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Let the concrete fragment inflate its own layout
        return rootView // Return the inflated view (should be set in the concrete fragment)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Find common UI elements *after* the concrete fragment has inflated rootView
        // This assumes the concrete fragment has assigned the inflated view to rootView
        rootView?.let {
            contentLoadingIndicator = it.findViewById(R.id.content_loading_indicator)
            contentLoadingText = it.findViewById(R.id.content_loading_text)
            contentLoadingRetryButton = it.findViewById(R.id.content_loading_retry_button)
            mainListView = it.findViewById(R.id.media_list)

            contentLoadingRetryButton?.setOnClickListener(this)
            context?.let { ctx ->
                contentLoadingText?.let { TypefaceUtils.setTypeface(ctx, getString(R.string.app_primary_font_uri), it) }
                contentLoadingRetryButton?.let { TypefaceUtils.setTypeface(ctx, getString(R.string.app_primary_font_uri), it) }
            }

            // Show error message if applicable, now that the views are initialized
            if (errorCode != 0) showMessage(errorCode, errorMessageExtra)
        }
    }

    /*
    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Ensure the context implements the listener
        if (context is FragmentListener) {
            fragmentListener = context
        } else {
            throw RuntimeException("$context must implement FragmentListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        fragmentListener = null // Release the reference to avoid memory leaks
    }*/

    override fun onClick(view: View) {
        if (view.id == R.id.content_loading_retry_button) {
            onRetryButtonClick()
        }
    }
    // endregion

    //**********************************************************************************************
    // region [Fragment Methods]
    protected open fun onRetryButtonClick() {}

    protected fun drawList() {
        // Hide initial loading indicators if visible
        contentLoadingIndicator?.visibility = View.INVISIBLE
        contentLoadingText?.visibility = View.INVISIBLE
        contentLoadingRetryButton?.visibility = View.INVISIBLE
        mainListView?.visibility = View.VISIBLE // Make list visible

        //previousTotalItems = items.size
        //items.clear()
        //viewHolderAdapter?.notifyItemRangeRemoved(0, previousTotalItems)

        try {
            // Delegate actual item processing and adapter population to the concrete fragment
            // The concrete fragment's drawItems should populate the 'items' list
            drawItems()

            // If after drawing items, the items list is still empty, show a message
            if (items.isEmpty()) {
                errorCode = 1
                errorMessageExtra = ": " +
                        if (viewHolderAdapter == null)
                            "No se encontraron ítems en la lista."
                        else
                            Util.getLocalizedString(requireActivity(), 19)
                showMessage(errorCode, errorMessageExtra)
                mainListView?.visibility = View.INVISIBLE // Hide list if empty
            } else {
                errorCode = 0 // Reset error code if successful
                // Inform the adapter about the data change.
                //viewHolderAdapter?.notifyItemRangeInserted(0, items.size)
            }
        } catch (error: Exception) {
            error.printStackTrace()
            errorCode = 1
            errorMessageExtra = ": Error al procesar la lista: " + error.message
            showMessage(errorCode, errorMessageExtra)
            mainListView?.visibility = View.INVISIBLE // Hide list on error
        }
    }

    protected abstract fun drawItems()

    protected open fun putItem(jsonObject: JsonObject?, jsonElementName: String? = null) {}

    protected abstract fun sortItems(@Suppress("SameParameterValue") by: ListAdapter.ItemField)

    // TODO: update all error notifications
    protected fun showMessage(code: Int, extra: String?) {
        errorCode = code
        if (code != 0 && context != null) {
            contentLoadingText?.visibility = View.VISIBLE
            contentLoadingRetryButton?.visibility = View.VISIBLE
            errorMenssage = "${Util.getLocalizedString(requireContext(), code)}${extra ?: ""}"
            contentLoadingText?.text = errorMenssage
            mainListView?.visibility = View.INVISIBLE // Hide list when showing error
        } else {
            // If code is 0, it means success or no error, hide error UI
            contentLoadingText?.visibility = View.INVISIBLE
            contentLoadingRetryButton?.visibility = View.INVISIBLE
            // list visibility is handled in drawList
        }
    }
    // endregion
}