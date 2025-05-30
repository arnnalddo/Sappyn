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

package com.arnnalddo.sappyn.models

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.arnnalddo.sappyn.BuildConfig
import com.arnnalddo.sappyn.R
import com.arnnalddo.sappyn.fragments.FragmentListener
import com.arnnalddo.sappyn.utils.Util
import com.arnnalddo.sappyn.utils.Util.toJson
import com.koushikdutta.async.future.Future
import com.koushikdutta.async.future.FutureCallback
import com.koushikdutta.ion.Ion

/**
 * ViewModel for the main fragment, responsible for loading and processing application data.
 *
 * This ViewModel handles:
 * - Fetching JSON data from a specified URL.
 * - Parsing JSON and JSONP data.
 * - Checking for application updates by comparing the fetched version with the current version.
 * - Managing UI state related to update availability and error messages.
 * - Storing and retrieving preferences related to update notifications and app version.
 *
 * @property application The application context, used for accessing resources and SharedPreferences.
 * @property showUpdateView LiveData indicating whether the update view should be shown.
 * @property errorMessage LiveData holding error messages to be displayed to the user.
 * @property taskFinished LiveData indicating which fragment should be displayed after a task is finished.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _showUpdateView = MutableLiveData<Boolean>()
    val showUpdateView: LiveData<Boolean> get() = _showUpdateView

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> get() = _errorMessage

    private val _taskFinished = MutableLiveData<FragmentListener.Fragment>()
    val taskFinished: LiveData<FragmentListener.Fragment> get() = _taskFinished

    private var jsonDataRequest: Future<String>? = null
    private val ionHandler = Handler(Looper.getMainLooper())
    private val preferences: SharedPreferences = application.getSharedPreferences(
        Util.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        const val TAG = "MainViewModel      ○"
    }

    init {
        // Initial check for update view visibility based on saved state or initial load
        _showUpdateView.value = preferences.getBoolean(Util.PREF_KEY_SHOW_UPDATE_VIEW, false)
    }

    fun loadData(url: String?) {
        Log.d(TAG, "Trying to load data from: $url")

        if (jsonDataRequest != null && jsonDataRequest?.isDone == false && jsonDataRequest?.isCancelled == false) return

        if (!Util.validate(url, Util.StringFormat.WEB_URL)) {
            Log.w(TAG, "Invalid URL")
            _errorMessage.value = Util.getLocalizedString(getApplication(), 18)
            return
        }

        jsonDataRequest = Ion.with(getApplication<Application>().applicationContext)
            .load(url)
            .noCache()
            .setHandler(ionHandler)
            .followRedirect(true)
            .asString()
        jsonDataRequest?.setCallback(FutureCallback { error: Exception?, jsonString: String? ->
            if (error != null) {
                Log.e(TAG, "Error loading data: $error")
                _errorMessage.value =
                    getApplication<Application>().getString(R.string.txt_error_json) +
                            if (error.message == null) " ${getApplication<Application>().getString(R.string.txt_error_unknown)}"
                            else if (error.message?.contains("unable to resolve host", true) == true) " ${getApplication<Application>().getString(R.string.txt_error_check_connection)}"
                            else " ${error.localizedMessage}"
                return@FutureCallback
            }

            // Check if the data is a jsonp
            val processedData = if (jsonString?.endsWith(")") == true || jsonString?.endsWith(");") == true) {
                try {
                    jsonString.substring(
                        jsonString.indexOf("(") + 1,
                        jsonString.lastIndexOf(")")
                    )
                } catch (errorJsonp: Exception) {
                    Log.e(TAG, "Error parsing jsonp: $errorJsonp")
                    errorJsonp.printStackTrace()
                    null
                }
            } else {
                jsonString
            }

            // Try to parse the data
            try {
                if (processedData != null) {
                    Util.mainData = processedData

                    val info = toJson(processedData).get("info")?.asJsonObject
                    if (info?.has("version") == true && !info["version"].isJsonNull) {
                        Util.appVersion = info.get("version").asLong
                    }

                    Log.d(TAG, "Verifying for app new version...")
                    if (preferences
                            .getLong(Util.PREF_KEY_APP_VERSION, (BuildConfig.VERSION_CODE).toLong())
                        < Util.appVersion && Util.appVersion > BuildConfig.VERSION_CODE) {
                        Log.d(TAG, "New version available: ${Util.appVersion}")
                        Util.isNewVersionAvailable = true
                        _showUpdateView.value = true
                    } else {
                        Log.d(TAG, "There is no new version available.")
                        Util.isNewVersionAvailable = false
                        _showUpdateView.value = false // Ensure update view is hidden
                        _taskFinished.value = FragmentListener.Fragment.MAIN_FRAGMENT
                    }
                } else {
                    _errorMessage.value = getApplication<Application>().getString(R.string.txt_error_json)
                }
            } catch (errorJson: Exception) {
                Log.e(TAG, "Error fetching data: $errorJson")
                errorJson.printStackTrace()
                _errorMessage.value = errorJson.localizedMessage
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        jsonDataRequest?.cancel()
    }
}