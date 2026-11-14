package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.utils.UiText

/*
 * CloudStream's tracking-account sync (compat; see CloudStreamApp.kt). This app has no AniList, MAL or
 * Trakt sign-in, so these stand in as "not signed in": plugins such as StreamPlay that check for an
 * account, or read its library, find none and carry on — they would not start at all without the classes.
 */

/** A signed-in tracking account. Never one here. */
data class AuthUser(
    val name: String?,
    val id: Int,
    val profilePicture: String? = null,
    val profilePictureHeaders: Map<String, String>? = null,
)

/** A tracking service. Plugins only pass one to [SyncRepo] and read library lists. */
abstract class SyncAPI {
    open val name: String = ""

    /** The account's lists ("Watching", "Completed"…); empty with no account. */
    open suspend fun library(): LibraryMetadata? = null

    data class LibraryMetadata(val allLibraryLists: List<LibraryList>)

    data class LibraryList(val name: UiText, val items: List<LibraryItem>)

    data class LibraryItem(val name: String, val url: String, val syncId: String)
}

/** A service with its account: here always without one. */
class SyncRepo(val api: SyncAPI) {
    fun authUser(): AuthUser? = null

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = runCatching { api.library() }
}

/** The tracking services, by name. */
class AccountManager {
    companion object {
        val aniListApi = AniListApi()

        /** CineStream asks for it by this name (`getSimklApi()`). */
        val simklApi = SimklApi()
    }
}
