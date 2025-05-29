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

package com.arnnalddo.sappyn.fragments

import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.arnnalddo.sappyn.R
import com.arnnalddo.sappyn.utils.Util
import com.arnnalddo.sappyn.databinding.FragmentLaunchBinding
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import com.arnnalddo.sappyn.models.MainViewModel
import com.arnnalddo.sappyn.utils.TypefaceUtils


/**
 * [MainFragment] is responsible for displaying the initial screen of the application.
 * It handles the loading of essential data via [MainViewModel] and presents options for
 * application updates if available. It also manages error display and user interaction
 * for update decisions.
 *
 * This fragment communicates with its hosting Activity through the [FragmentListener] interface
 * to signal task completion or the need to navigate away.
 *
 * Key functionalities include:
 * - Observing data from [MainViewModel] to update the UI (e.g., show/hide update view, display errors).
 * - Retrieving and storing application preferences, specifically the last known app version.
 * - Handling click events for update actions (update now, update later).
 * - Displaying a custom error dialog with retry and exit options.
 * - Applying custom typefaces to UI elements.
 */
class MainFragment : Fragment(), View.OnClickListener {
    //**********************************************************************************************
    // region [Properties]
    private var fragmentListener: FragmentListener? = null
    private var _binding: FragmentLaunchBinding? = null
    private val binding get() = _binding!!
    private lateinit var mainViewModel: MainViewModel
    private var preferences: SharedPreferences? = null
    private var popup: AlertDialog? = null
    private var popupBuilder: AlertDialog.Builder? = null
    private var popupTitle: CharSequence? = null
    private var popupMessage: TextView? = null
    private var popupPrimaryButton: Button? = null
    private var popupSecondaryButton: Button? = null
    private var popupTertiaryButton: Button? = null

    companion object { const val TAG = "MainFragment       ○" }
    // endregion

    //**********************************************************************************************
    // region [Fragment Lifecycle]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called.")
        preferences = context?.getSharedPreferences(
            Util.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Observe the ViewModel LiveData
        mainViewModel.showUpdateView.observe(this) { show ->
            binding.updateLayout.root.visibility = if (show) View.VISIBLE else View.GONE
        }
        mainViewModel.errorMessage.observe(this) { message ->
            mostrarError(message)
        }
        mainViewModel.taskFinished.observe(this) { fragment ->
            fragmentListener?.onTaskFinished(fragment)
        }

        // Only load data if it's the first time the ViewModel is created (not after a configuration change)
        if (savedInstanceState == null) {
            mainViewModel.loadData(getString(R.string.app_main_api_uri))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLaunchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding.updateLayout) {
            updateNowButton.setOnClickListener(this@MainFragment)
            updateLaterButton.setOnClickListener(this@MainFragment)
            // The visibility of updateLayout.root is now handled by the LiveData showUpdateView
            arrayOf(updateTitleTv, updateDescriptionTv, updateNowButton, updateLaterButton).forEach { vista ->
                context?.let { contexto ->
                    TypefaceUtils.setTypeface(
                        contexto,
                        getString(if (vista == updateTitleTv) R.string.app_secondary_font_uri else R.string.app_primary_font_uri),
                        vista
                    )
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        fragmentListener = context as FragmentListener
    }

    override fun onDetach() {
        super.onDetach()
        fragmentListener = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        if (popup?.isShowing == true)
            popup?.dismiss()
    }

    override fun onClick(view: View?) {
        when (view?.id) {
            R.id.update_now_button, R.id.update_later_button -> {
                Util.isNewVersionAvailable = false // Update global state variable
                preferences?.edit {
                    putLong(Util.PREF_KEY_APP_VERSION, Util.appVersion) // Save the current version
                }
                if (view.id == R.id.update_now_button) {
                    context?.let { Util.openMarket(it) }
                    //finish()
                } else {
                    if (isAdded && context != null) {
                        fragmentListener?.onTaskFinished(FragmentListener.Fragment.MAIN_FRAGMENT)
                    }
                }
            }
        }
    }
    // endregion

    //**********************************************************************************************
    // region [Helper Methods]
    private fun mostrarError(mensaje: String?) {
        if (context == null) return

        popupTitle = getString(R.string.title_error).uppercase()
        popupBuilder = AlertDialog.Builder(requireContext())
            .setCancelable(false)
            .setTitle(popupTitle)
            .setMessage(mensaje)
            .setPositiveButton(getString(R.string.retry)) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                // Try to download again from the ViewModel
                mainViewModel.loadData(getString(R.string.app_main_api_uri_alt))
            }
            .setNegativeButton(getString(R.string.exit)) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                activity?.finish()
            }
        try {
            popup = popupBuilder?.create()

            if (popup?.window != null) popup?.window?.attributes?.windowAnimations =
                R.style.WindowAnimation

            popup?.setOnShowListener {
                with(popup) {
                    popupMessage = this?.findViewById(android.R.id.message)
                    popupPrimaryButton = this?.getButton(AlertDialog.BUTTON_POSITIVE)
                    popupSecondaryButton = this?.getButton(AlertDialog.BUTTON_NEGATIVE)
                    popupTertiaryButton = this?.getButton(AlertDialog.BUTTON_NEUTRAL)
                    arrayOf(this?.findViewById(androidx.appcompat.R.id.alertTitle), popupMessage, popupPrimaryButton, popupSecondaryButton, popupTertiaryButton).forEach { objeto ->
                        objeto?.let {
                            TypefaceUtils.setTypeface(
                                requireContext(),
                                getString(R.string.app_primary_font_uri),
                                it
                            )
                        }
                    }
                }
            }

            popup?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error popup: $e")
            e.printStackTrace()
        }
    }
    // endregion

}