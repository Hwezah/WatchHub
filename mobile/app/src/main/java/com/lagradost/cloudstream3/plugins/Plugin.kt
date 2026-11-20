package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * The Android plugin base most .cs3 files extend (compat; see CloudStreamApp.kt). The cross-platform
 * [BasePlugin] comes from CloudStream's library; this adds the Context, a plugin's own resources,
 * and a settings button. `registerVideoClickAction` is left out: it registers player menu items,
 * which this app's player has no place for.
 */
abstract class Plugin : BasePlugin() {
    /** Called when the plugin is loaded. */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // A plugin that only overrides the cross-platform load().
        load()
    }

    /** The plugin's own resources, when its manifest says `requiresResources`. */
    var resources: Resources? = null

    /** Set by a plugin that has settings of its own. */
    var openSettings: ((context: Context) -> Unit)? = null
}
