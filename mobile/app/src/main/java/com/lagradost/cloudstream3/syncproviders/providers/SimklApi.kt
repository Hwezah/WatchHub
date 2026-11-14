package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * Simkl (compat; see CloudStreamApp.kt): no account here. CineStream reads `AccountManager.simklApi` to show the
 * viewer's Simkl lists, and without the class it would not start at all; with no account its lists are empty.
 */
class SimklApi : SyncAPI() {
    override val name = "Simkl"
}
