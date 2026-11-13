package com.lagradost.cloudstream3

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * CloudStream's activity holder (compat; see CloudStreamApp.kt). Every phisher plugin reads
 * [activity], and many show a toast — "this site needs a login", "copied". The host keeps
 * [activity] pointed at the running MainActivity.
 */
object CommonActivity {
    private var ref: WeakReference<Activity>? = null

    var activity: Activity?
        get() = ref?.get()
        set(value) {
            ref = value?.let { WeakReference(it) }
        }

    /** Plugins call this from any thread; a Toast must be made on the main one. */
    fun showToast(message: String?, duration: Int? = null) = showToast(activity, message, duration)

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        val context = act ?: CloudStreamApp.context ?: return
        if (message.isNullOrBlank()) return
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, message, duration ?: Toast.LENGTH_SHORT).show() }
        }
    }
}
