package com.lagradost.cloudstream3.ui.settings.extensions

/** A repository the viewer added, by its repo.json link (compat; see CloudStreamApp.kt). */
data class RepositoryData(
    val iconUrl: String?,
    val name: String,
    val url: String,
    val description: String? = null,
) {
    constructor(name: String, url: String) : this(null, name, url)
}
