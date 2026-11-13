// AppUtils' JSON helpers are "internal" to CloudStream's own app, which this file stands in for.
@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral

/** CloudStream's preference file: plugins keep their settings here, as JSON strings. */
const val PREFERENCES_NAME = "rebuild_preference"

/**
 * CloudStream's key store (compat; see CloudStreamApp.kt). Plugins have CloudStream's inline
 * `getKey` compiled into them, which calls [getSharedPrefs] and the library's `AppUtils.parseJson`:
 * so the file name and the "folder/path" key shape must stay exactly as upstream.
 */
object DataStore {
    private const val TAG = "DataStore"

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun Context.getSharedPrefs(): SharedPreferences = getPreferences(this)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    /** Android's default preferences file, without the deprecated PreferenceManager. */
    fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

    fun Context.getKeys(folder: String): List<String> {
        // Ends in "/" so "abc" doesn't match "abcd/…".
        val fixedFolder = folder.trimEnd('/') + "/"
        return getSharedPrefs().all.keys.filter { it.startsWith(fixedFolder) }
    }

    fun Context.removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))

    fun Context.containsKey(folder: String, path: String): Boolean = containsKey(getFolderName(folder, path))

    fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)

    fun Context.removeKey(path: String) {
        runCatching {
            val prefs = getSharedPrefs()
            if (prefs.contains(path)) prefs.edit().remove(path).apply()
        }.onFailure { Log.w(TAG, "removeKey: ${it.message}") }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys("$folder/")
        return runCatching {
            getSharedPrefs().edit().apply { keys.forEach(::remove) }.apply()
            keys.size
        }.getOrDefault(0)
    }

    fun <T> Context.setKey(path: String, value: T) {
        runCatching { getSharedPrefs().edit().putString(path, value?.toJsonLiteral()).apply() }
            .onFailure { Log.w(TAG, "setKey $path: ${it.message}") }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) = setKey(getFolderName(folder, path), value)

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? = runCatching {
        val json = getSharedPrefs().getString(path, null) ?: return null
        AppUtils.parseJson(json, valueType.kotlin)
    }.getOrNull()

    /*
     * Our own callers only. Compiled plugins carry upstream's inline body, not this one; this one goes
     * through [getKey] by class so this module needs neither Jackson nor kotlinx.serialization to compile.
     */
    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        if (!getSharedPrefs().contains(path)) return defVal
        return getKey(path, valueType = T::class.java)
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? = getKey<T>(path, null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? = getKey<T>(getFolderName(folder, path), null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
        getKey(getFolderName(folder, path), defVal) ?: defVal
}
