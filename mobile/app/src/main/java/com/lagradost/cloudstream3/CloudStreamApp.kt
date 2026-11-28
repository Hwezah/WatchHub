package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import java.lang.ref.WeakReference

/*
 * Compatibility layer for CloudStream plugins (.cs3).
 *
 * A plugin is loaded with our app's class loader as its parent and calls CloudStream's own app
 * classes by their exact names and JVM signatures. Everything under com.lagradost.cloudstream3 in
 * this module is a small re-implementation of those classes, ported from CloudStream
 * (https://github.com/recloudstream/cloudstream, GPL-3.0, the same licence as this app).
 * Keep names, parameter types and default arguments as they are upstream: changing one breaks
 * every compiled plugin that calls it. The members kept are the ones phisher98's plugins call.
 */

/**
 * CloudStream's Application object. Never instantiated here: plugins only read its companion —
 * [context] (all of them) and the key store ([setKey]/[getKey], backed by [DataStore]).
 */
class CloudStreamApp {
    companion object {
        private var ref: WeakReference<Context>? = null

        /** The application context, set once at startup by the plugin host. */
        var context: Context?
            get() = ref?.get()
            set(value) {
                val app = value?.applicationContext ?: value
                ref = app?.let { WeakReference(it) }
                // The library's own copy, read by WebViewResolver and others.
                com.lagradost.api.setContext(app)
            }

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.getActivity()
            else -> null
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = context?.getKey(path, valueType = valueType)

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? = context?.removeKeys(folder)

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = context?.getKey(path, defVal)

        inline fun <reified T : Any> getKey(path: String): T? = context?.getKey<T>(path)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? = context?.getKey<T>(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? = context?.getKey(folder, path, defVal)

        fun getKeys(folder: String): List<String>? = context?.getKeys(folder)

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }
    }
}
