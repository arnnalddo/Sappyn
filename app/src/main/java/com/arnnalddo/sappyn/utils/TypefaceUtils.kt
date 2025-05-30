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

import android.content.Context
import android.graphics.Typeface
import android.widget.Button
import android.widget.TextView
import java.util.Hashtable

/**
 * Utility object for managing and applying custom typefaces to Android UI elements.
 *
 * This object provides a caching mechanism to avoid repeatedly loading the same typeface
 * from assets, which can be an expensive operation.
 */
object TypefaceUtils {
    private val cache = Hashtable<String, Typeface>()

    fun setTypeface(context: Context, fontPath: String, target: Any) {
        getTypeface(context, fontPath)?.let { typeface ->
            when (target) {
                is Button -> target.typeface = typeface
                is TextView -> target.typeface = typeface
                // Add more types if necesary (ex: EditText)
            }
        }
    }

    private fun getTypeface(context: Context, fontPath: String): Typeface? {
        return cache.getOrPut(fontPath) {
            try {
                Typeface.createFromAsset(context.assets, fontPath)
            } catch (_: Exception) {
                null
            }
        }
    }
}