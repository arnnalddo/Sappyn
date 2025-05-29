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

package com.arnnalddo.sappyn.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Patterns
import android.widget.Toast
import androidx.core.net.toUri
import com.arnnalddo.sappyn.BuildConfig
import com.arnnalddo.sappyn.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader


/**
 * Utility object containing various helper functions and constants for the application.
 *
 * This object provides:
 *  - String format validation (Email, Phone, URL, Media URI).
 *  - Access to preference keys and default values.
 *  - Command constants for media playback.
 *  - Helper methods for getting localized strings.
 *  - Functions for launching activities and opening the app market.
 *  - JSON parsing utilities.
 *
 * It also holds some global state variables, though some are marked as TODO for database storage.
 */
object Util {
    enum class StringFormat {
        EMAIL_ADDRESS,
        PHONE,
        WEB_URL,
        MEDIA_URI
    }
    // Preferences
    const val PREFS_NAME = "APP_PREFS"
    const val PREF_KEY_APP_VERSION = "APP_VERSION"
    const val PREF_KEY_SHOW_UPDATE_VIEW = "SHOW_UPDATE_VIEW"
    const val PREF_KEY_AUTOPLAY_ENABLED = "AUTOPLAY_ENABLED"

    // Commands
    const val COMMAND_START_PLAYBACK = "COMMAND_START_PLAYBACK"
    const val COMMAND_TOGGLE_PLAYBACK = "COMMAND_TOGGLE_PLAYBACK"
    //const val COMMAND_CANCEL_PLAYBACK_NOTIFICATION = "COMMAND_CANCEL_PLAYBACK_NOTIFICATION"

    // Command extra keys
    const val EXTRA_MEDIA_ID = "media_id"
    const val EXTRA_MEDIA_AUTOPLAY = "media_autoplay"

    var isForegroundServiceStarted: Boolean = false
    var isNewVersionAvailable: Boolean = false
    var appVersion : Long = 0L
    var mainData: String? = null // TODO: debe guardarse en base de datos

    fun validate(string: String?, type: StringFormat?): Boolean {
        if (string.isNullOrEmpty()) return false
        return when (type) {
            StringFormat.EMAIL_ADDRESS -> Patterns.EMAIL_ADDRESS.matcher(string).matches()
            StringFormat.PHONE -> Patterns.PHONE.matcher(string).matches()

            StringFormat.MEDIA_URI -> try {
                val parsedUri = string.toUri()
                val scheme = parsedUri.scheme?.lowercase()
                scheme in listOf("http", "https", "rtsp", "rtmp", "file", "content")
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            else -> Patterns.WEB_URL.matcher(string).matches()
        }
    }

    fun getLocalizedString(context: Context, code: Int): String? {
        return when (code) {
            1 -> context.getString(R.string.txt_error_loading_content)
            2 -> context.getString(R.string.txt_error_connection)
            3 -> context.getString(R.string.txt_error_network_unavailable)
            4 -> context.getString(R.string.txt_error_check_connection)
            5 -> context.getString(R.string.txt_error_json)
            6 -> context.getString(R.string.form_error_incomplete_fields)
            7 -> context.getString(R.string.form_error_invalid_name)
            8 -> context.getString(R.string.form_error_invalid_email)
            9 -> context.getString(R.string.form_error_short_message)
            10 -> context.getString(R.string.txt_error_loading_content) + ". " + context.getString(R.string.txt_error_check_connection)
            11 -> context.getString(R.string.txt_error_network_unavailable) + " " + context.getString(
                R.string.txt_error_check_connection)
            12 -> context.getString(R.string.form_state_not_sent) + " " + context.getString(R.string.txt_error_check_connection)
            13 -> context.getString(R.string.form_state_sending)
            14 -> context.getString(R.string.form_state_sent)
            15 -> context.getString(R.string.form_state_not_sent)
            16 -> context.getString(R.string.title_licenses)
            17 -> "ERROR_VALOR_MEDIO"
            18 -> "ERROR_URL"
            19 -> "ERROR_FORMATO_JSON"
            20 -> context.getString(R.string.title_error)
            21 -> context.getString(R.string.title_legal)
            else -> null
        }
    }

    private fun launchActivity(context: Context, intent: Intent?, showErrorMessage: Boolean): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (showErrorMessage) {
                Toast.makeText(context, context.getString(R.string.title_error), Toast.LENGTH_SHORT)
                    .show()
            }
            false
        }
    }

    fun openMarket(context: Context) {
        // Try to epen market app:
        var intent = Intent(
            Intent.ACTION_VIEW,
            ("market://details?id=" + BuildConfig.APPLICATION_ID).toUri()
        )
        if (!launchActivity(context, intent, false)) {
            // If market app is not installed, open browser:
            intent = Intent(
                Intent.ACTION_VIEW,
                ("https://play.google.com/store/apps/details?id=" + BuildConfig.APPLICATION_ID).toUri()
            )
            launchActivity(context, intent, true)
        }
    }

    // TODO: Beta
    fun toJson(jsonString: String?): JsonObject {
        return try {
            val parser = JsonParser()
            val reader = JsonReader(StringReader(jsonString)).apply {
                isLenient = true
            }
            parser.parse(reader).asJsonArray[0].asJsonObject
        } catch (e: Exception) {
            e.printStackTrace()
            JsonObject()  // Return an empty JsonObject if parsing fails
        }
    }

}