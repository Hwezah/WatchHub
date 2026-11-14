package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * Not an activity here: only the companion's events, which a few plugins (StreamPlay, StremioX)
 * subscribe to (compat; see CloudStreamApp.kt). The host fires [afterPluginsLoadedEvent] once every
 * installed plugin has loaded. The app's real activity is com.torrentviewer.mobile.MainActivity.
 */
class MainActivity {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()

        // Fired by plugins after their settings change (CineStream's Save): nothing here listens — this app has no
        // CloudStream home page — but the plugin crashes without them. Names and types are CloudStream's.
        val reloadHomeEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
    }
}
