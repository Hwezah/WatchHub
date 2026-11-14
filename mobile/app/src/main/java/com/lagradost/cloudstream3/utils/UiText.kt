package com.lagradost.cloudstream3.utils

import android.content.Context

/**
 * Text that may be a string resource or a plain string (compat; see CloudStreamApp.kt). Plugins read it
 * with [asString], e.g. a sync library list's name.
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText()

    class StringResource(val resId: Int, val args: List<Any> = emptyList()) : UiText()

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> runCatching { context.getString(resId, *args.toTypedArray()) }.getOrDefault("")
    }
}
