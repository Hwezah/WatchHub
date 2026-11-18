package com.torrentviewer.mobile

import com.torrentviewer.core.Kv
import com.torrentviewer.core.TubeChannel
import com.torrentviewer.core.TubeVideo
import com.torrentviewer.core.channelKey
import com.torrentviewer.core.homeFeed
import com.torrentviewer.core.interleave
import com.torrentviewer.core.tubeChannels
import com.torrentviewer.core.tubeChannelsJson
import com.torrentviewer.core.tubeVideos
import com.torrentviewer.core.tubeVideosJson
import com.torrentviewer.core.youtubeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale

/** A search result or channel row that isn't a video: a channel or a playlist, opened on its own page. */
data class TubeLink(val url: String, val name: String, val thumb: String?, val line: String, val channel: Boolean)

/** One page of a list (search, a channel's videos, a playlist), and how to ask for the next. */
data class TubePage(val videos: List<TubeVideo>, val links: List<TubeLink> = emptyList(), val next: (suspend () -> TubePage)? = null)

/** One way to watch: [video] alone (picture and sound, or HLS) or picture-only with [audio] beside it. */
data class TubeQuality(val label: String, val height: Int, val video: String, val audio: String? = null, val hls: Boolean = false)

/** Everything the video page shows, and the ways to play it. */
data class TubeDetails(
    val video: TubeVideo,
    val description: String,
    val likes: Long,
    val subscribers: Long,
    val qualities: List<TubeQuality>,
    val related: List<TubeVideo>,
)

/** A channel's page: its banner, picture, numbers, and the tabs it has. */
data class TubeChannelPage(val channel: TubeChannel, val banner: String?, val subscribers: Long, val description: String, val videos: ListLinkHandler?, val playlists: ListLinkHandler?)

/**
 * The Tube tab's YouTube, read on the phone by NewPipeExtractor (as [Trailers] does): no Google account, no API key.
 * Subscriptions, History and Watch Later are kept here, per profile, in [Kv]. Network calls are off the main thread.
 */
object Tube {
    private val yt get() = ServiceList.YouTube

    /** NewPipe set up, with YouTube's results for this phone's language and country (trending differs by country). */
    private fun ready() {
        Trailers.init()
        runCatching {
            val l = Locale.getDefault()
            NewPipe.setupLocalization(Localization.fromLocale(l), ContentCountry(l.country.ifBlank { "US" }))
        }
    }

    /* -------------------------------------------------------------- reading YouTube */

    /** The best picture in [images] for a phone-wide card: the largest. */
    private fun best(images: List<Image>?): String? = images.orEmpty().maxByOrNull { it.height.coerceAtLeast(0) * 10_000 + it.width.coerceAtLeast(0) }?.url

    private fun StreamInfoItem.toVideo(channel: TubeChannel? = null): TubeVideo? {
        val id = youtubeId(url) ?: return null
        return TubeVideo(
            id, name.orEmpty(), uploaderName ?: channel?.name.orEmpty(), uploaderUrl ?: channel?.url, best(uploaderAvatars) ?: channel?.avatar, best(thumbnails),
            duration.coerceAtLeast(0), viewCount, textualUploadDate, runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0,
            streamType == StreamType.LIVE_STREAM,
        )
    }

    /** Videos (not Shorts, which want their own upright player) and the channels and playlists among [items]. */
    private fun split(items: List<InfoItem>, channel: TubeChannel? = null): Pair<List<TubeVideo>, List<TubeLink>> {
        val videos = items.filterIsInstance<StreamInfoItem>().filter { !it.isShortFormContent && "/shorts/" !in it.url }.mapNotNull { it.toVideo(channel) }
        val links = items.mapNotNull { item ->
            when (item) {
                is ChannelInfoItem -> TubeLink(item.url, item.name, best(item.thumbnails), listOfNotNull(item.subscriberCount.takeIf { it >= 0 }?.let { "${com.torrentviewer.core.compactCount(it)} subscribers" }).joinToString(), true)
                is PlaylistInfoItem -> TubeLink(item.url, item.name, best(item.thumbnails), listOfNotNull(item.uploaderName, item.streamCount.takeIf { it >= 0 }?.let { "$it videos" }).joinToString(" • "), false)
                else -> null
            }
        }
        return videos to links
    }

    /**
     * What's popular: YouTube retired its Trending page in 2025, so its charts — trending films and shows, music,
     * gaming, podcasts — woven together, whichever answer.
     */
    suspend fun trending(): List<TubeVideo> = withContext(Dispatchers.IO) {
        ready()
        val ids = listOf("trending_movies_and_shows", "trending_music", "trending_gaming", "trending_podcasts_episodes", "Trending")
        val lists = coroutineScope {
            ids.map { id ->
                async {
                    runCatching {
                        val ex = yt.kioskList.getExtractorById(id, null)
                        ex.fetchPage()
                        split(KioskInfo.getInfo(ex).relatedItems).first
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        interleave(lists)
    }

    /** A while the Subscriptions feed is kept before it's read again: dozens of channels are dozens of requests. */
    private const val FEED_MS = 20 * 60_000L
    private var feedCache: Pair<Long, List<TubeVideo>>? = null
    private var feedFor: Set<String> = emptySet()

    /**
     * The newest videos of every subscription, newest first: each channel's Videos tab, a few at a time. A channel
     * that fails (gone, renamed) is skipped. [fresh]: read again even if the last read is recent (pull to refresh).
     */
    suspend fun subscriptionFeed(fresh: Boolean = false): List<TubeVideo> = withContext(Dispatchers.IO) {
        val subs = subscriptions.value
        val keys = subs.map { channelKey(it.url) }.toSet()
        feedCache?.let { (at, list) -> if (!fresh && keys == feedFor && System.currentTimeMillis() - at < FEED_MS) return@withContext list }
        ready()
        val gate = Semaphore(6)
        val all = coroutineScope {
            subs.map { ch -> async { gate.withPermit { runCatching { channelVideos(ch) }.getOrDefault(emptyList()) } } }.awaitAll()
        }.flatten().distinctBy { it.id }.sortedByDescending { it.at }
        feedCache = System.currentTimeMillis() to all
        feedFor = keys
        all
    }

    /** One channel's latest videos (its Videos tab's first page), named and pictured as the subscription is. */
    private fun channelVideos(ch: TubeChannel): List<TubeVideo> {
        val id = yt.channelLHFactory.fromUrl(ch.url).id
        val handler = yt.channelTabLHFactory.fromQuery(id, listOf(ChannelTabs.VIDEOS), "")
        return split(ChannelTabInfo.getInfo(yt, handler).relatedItems, ch).first.map { it.copy(channel = ch.name, channelUrl = ch.url, avatar = it.avatar ?: ch.avatar) }
    }

    private var accountCache: Pair<Long, List<TubeVideo>>? = null

    /**
     * The signed-in account's own Home: YouTube's recommendations for it, then its Subscriptions feed, woven together;
     * kept as long as [subscriptionFeed]'s. A failure is kept in [YouTubeAccount.problem] for Home to say.
     */
    private suspend fun accountFeed(fresh: Boolean): List<TubeVideo> {
        accountCache?.let { (at, list) -> if (!fresh && System.currentTimeMillis() - at < FEED_MS) return list }
        val list = YouTubeAccount.noted {
            coroutineScope {
                val rec = async { runCatching { YouTubeAccount.recommended() }.getOrDefault(emptyList()) }
                val subs = async { YouTubeAccount.feed() }
                com.torrentviewer.core.interleave(listOf(rec.await(), subs.await()))
            }
        }
        return list.also { accountCache = System.currentTimeMillis() to it }
    }

    private const val SEARCHES = "tubeSearches"

    /** The last few searches, newest first (per profile). */
    fun recentSearches(): List<String> = runCatching { org.json.JSONArray(Kv.get(SEARCHES) ?: "[]") }.getOrNull()
        ?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

    /** A search kept, so Home leans towards it and the box opens on it. */
    fun remember(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        val list = (listOf(q) + recentSearches().filter { !it.equals(q, true) }).take(6)
        Kv.set(SEARCHES, org.json.JSONArray(list).toString())
    }

    fun forgetSearches() = Kv.set(SEARCHES, null)

    /** Videos from the last three searches, a few each, woven together. */
    private suspend fun fromSearches(): List<TubeVideo> = coroutineScope {
        recentSearches().take(3).map { q -> async { runCatching { search(q).videos.take(10) }.getOrDefault(emptyList()) } }.awaitAll()
            .let { com.torrentviewer.core.interleave(it) }
    }

    /** Home: subscriptions' newest first, then trending; trending alone until there are subscriptions. */
    suspend fun home(fresh: Boolean = false): List<TubeVideo> = coroutineScope {
        // Signed in to YouTube: the account's own Subscriptions feed; else the channels kept here.
        val subs = async {
            val own = if (YouTubeAccount.signedIn.value) runCatching { accountFeed(fresh) }.getOrDefault(emptyList()) else emptyList()
            own.ifEmpty { if (subscriptions.value.isEmpty()) emptyList() else runCatching { subscriptionFeed(fresh) }.getOrDefault(emptyList()) }
        }
        // What was searched lately (the owner's choice: Home follows what you look for, not YouTube's random picks).
        val searched = async { runCatching { fromSearches() }.getOrDefault(emptyList()) }
        var failure: Throwable? = null
        val hot = async { runCatching { trending() }.onFailure { failure = it }.getOrDefault(emptyList()) }
        // Yours first (account or subscriptions), then from your searches, YouTube's charts only after.
        val feed = homeFeed(subs.await(), com.torrentviewer.core.interleave(listOf(searched.await(), hot.await())).let { mixed ->
            val mine = searched.await()
            if (mine.isEmpty()) mixed else mine + mixed.filter { v -> mine.none { it.id == v.id } }
        })
        if (feed.isNotEmpty()) return@coroutineScope feed
        // YouTube's charts gave nothing (they are often empty or refused for a country): what's popular, found by
        // searching, so Home is never blank. A failure everywhere is said, with ↻, rather than an empty page.
        val found = runCatching { popular() }.onFailure { failure = failure ?: it }.getOrDefault(emptyList())
        found.ifEmpty { failure?.let { throw it } ?: emptyList() }
    }

    /** Popular videos by search, a few subjects woven together: Home's fallback when the charts are empty. */
    private suspend fun popular(): List<TubeVideo> = coroutineScope {
        val country = Locale.getDefault().displayCountry.takeIf { it.isNotBlank() }
        listOfNotNull("trending today", "new music videos", country?.let { "$it news" }, "movie trailers")
            .map { q -> async { runCatching { search(q).videos }.getOrDefault(emptyList()) } }.awaitAll()
            .let(::interleave)
    }

    /** YouTube's own search: videos, channels and playlists, a page at a time. */
    suspend fun search(query: String): TubePage = withContext(Dispatchers.IO) {
        ready()
        val handler = yt.searchQHFactory.fromQuery(query, emptyList(), "")
        val info = SearchInfo.getInfo(yt, handler)
        val (videos, links) = split(info.relatedItems)
        TubePage(videos, links, info.nextPage?.let { searchMore(handler, it) })
    }

    private fun searchMore(handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler, page: Page): suspend () -> TubePage = {
        withContext(Dispatchers.IO) {
            val more = SearchInfo.getMoreItems(yt, handler, page)
            val (videos, links) = split(more.items)
            TubePage(videos, links, more.nextPage?.let { searchMore(handler, it) })
        }
    }

    /** What YouTube suggests as a search is typed. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        ready()
        runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /** A video's page: its numbers, description, related videos, and the ways to play it. */
    suspend fun details(id: String): TubeDetails = withContext(Dispatchers.IO) {
        ready()
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id")
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val video = TubeVideo(
            id, info.name.orEmpty(), info.uploaderName.orEmpty(), info.uploaderUrl, best(info.uploaderAvatars), best(info.thumbnails),
            info.duration.coerceAtLeast(0), info.viewCount,
            // YouTube's video page gives a full date ("2026-10-05T12:15:16-07:00"): said as the lists say it, "6 weeks ago".
            (runCatching { info.uploadDate?.instant?.toEpochMilli() }.getOrNull()?.let { com.torrentviewer.core.agoText(it) } ?: info.textualUploadDate),
            runCatching { info.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0, live,
        )
        TubeDetails(video, info.description?.content.orEmpty(), info.likeCount, info.uploaderSubscriberCount, qualities(info), split(info.relatedItems).first)
    }

    /** The tallest picture played: a phone's screen doesn't need 4K, and YouTube's 1440p and up is VP9/AV1 only. */
    private const val MOST_HEIGHT = 1080

    /**
     * Every height YouTube offers, tallest first, each as picture-only H.264 (MP4) where it has one — every phone's
     * hardware decodes it — beside the original soundtrack; HLS for a live stream; the joined 360p file last.
     */
    private fun qualities(info: StreamInfo): List<TubeQuality> {
        val out = ArrayList<TubeQuality>()
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { out += TubeQuality("Auto", 0, it, hls = true) }
        val sounds = info.audioStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val original = sounds.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { sounds }
        val sound = original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate } ?: original.maxByOrNull { it.averageBitrate }
        if (sound != null) {
            info.videoOnlyStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..MOST_HEIGHT }
                .groupBy { it.height }.toSortedMap(compareByDescending { it }).forEach { (height, list) ->
                    val pick = list.firstOrNull { it.format == MediaFormat.MPEG_4 } ?: list.first()
                    out += TubeQuality("${height}p", height, pick.content, sound.content)
                }
        }
        info.videoStreams.orEmpty().filter { it.isUrl && !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }?.let { out += TubeQuality("${it.height}p", it.height, it.content) }
        return out.distinctBy { it.label }
    }

    /**
     * What a download needs, read fresh (YouTube's links last hours): the picture-only MP4 at [height] or the nearest
     * below, and the M4A soundtrack; [audioOnly]: the soundtrack alone. Null parts when YouTube has none.
     */
    suspend fun downloadParts(id: String, height: Int?, audioOnly: Boolean): Pair<String?, String?> = withContext(Dispatchers.IO) {
        ready()
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id")
        val sounds = info.audioStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
        val sound = sounds.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { sounds }.maxByOrNull { it.averageBitrate }?.content
        if (audioOnly) return@withContext null to sound
        val mp4 = info.videoOnlyStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 && it.height in 1..MOST_HEIGHT }
        val picture = (mp4.filter { it.height <= (height ?: 720) }.maxByOrNull { it.height } ?: mp4.minByOrNull { it.height })?.content
        picture to sound
    }

    /** The heights a download can be saved at, tallest first (MP4 picture-only streams). */
    suspend fun downloadHeights(id: String): List<Int> = withContext(Dispatchers.IO) {
        ready()
        StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id").videoOnlyStreams.orEmpty()
            .filter { it.isUrl && it.format == MediaFormat.MPEG_4 && it.height in 1..MOST_HEIGHT }.map { it.height }.distinct().sortedDescending()
    }

    /** A channel's page: name, picture, banner, subscribers, and its Videos and Playlists tabs. */
    suspend fun channel(url: String): TubeChannelPage = withContext(Dispatchers.IO) {
        ready()
        val info = ChannelInfo.getInfo(yt, url)
        val tabs = info.tabs.orEmpty()
        fun tab(name: String) = tabs.firstOrNull { it.contentFilters.contains(name) }
        val ch = TubeChannel(info.url ?: url, info.name.orEmpty(), best(info.avatars))
        // A picture seen: the subscription (if it is one) gets it, for the feed's cards.
        updateAvatar(ch)
        TubeChannelPage(ch, best(info.banners), info.subscriberCount, info.description.orEmpty(), tab(ChannelTabs.VIDEOS), tab(ChannelTabs.PLAYLISTS))
    }

    /** One of a channel's tabs, a page at a time. */
    suspend fun channelTab(handler: ListLinkHandler, ch: TubeChannel): TubePage = withContext(Dispatchers.IO) {
        val info = ChannelTabInfo.getInfo(yt, handler)
        val (videos, links) = split(info.relatedItems, ch)
        TubePage(videos, links, info.nextPage?.let { tabMore(handler, it, ch) })
    }

    private fun tabMore(handler: ListLinkHandler, page: Page, ch: TubeChannel): suspend () -> TubePage = {
        withContext(Dispatchers.IO) {
            val more = ChannelTabInfo.getMoreItems(yt, handler, page)
            val (videos, links) = split(more.items, ch)
            TubePage(videos, links, more.nextPage?.let { tabMore(handler, it, ch) })
        }
    }

    /** A playlist's name and its videos, a page at a time. */
    suspend fun playlist(url: String): Pair<String, TubePage> = withContext(Dispatchers.IO) {
        ready()
        val info = PlaylistInfo.getInfo(yt, url)
        info.name.orEmpty() to TubePage(split(info.relatedItems).first, emptyList(), info.nextPage?.let { playlistMore(url, it) })
    }

    private fun playlistMore(url: String, page: Page): suspend () -> TubePage = {
        withContext(Dispatchers.IO) {
            val more = PlaylistInfo.getMoreItems(yt, url, page)
            TubePage(split(more.items).first, emptyList(), more.nextPage?.let { playlistMore(url, it) })
        }
    }

    /* -------------------------------------------------------------- kept on the phone */

    private const val SUBS = "tubeSubscriptions"
    private const val HISTORY = "tubeHistory"
    private const val LATER = "tubeLater"
    private const val SPOTS = "tubeSpots"
    /** History kept: enough to scroll back weeks, small enough to write on every video. */
    private const val HISTORY_MAX = 300

    /** Set once the last video has been reopened this launch, so closing it doesn't bring it back. */
    var reopened = false

    private val _subscriptions = MutableStateFlow<List<TubeChannel>>(emptyList())
    val subscriptions: StateFlow<List<TubeChannel>> = _subscriptions
    private val _history = MutableStateFlow<List<TubeVideo>>(emptyList())
    val history: StateFlow<List<TubeVideo>> = _history
    private val _later = MutableStateFlow<List<TubeVideo>>(emptyList())
    val later: StateFlow<List<TubeVideo>> = _later

    /** Read this profile's lists: called when a profile opens, so each profile has its own. */
    fun load() {
        _subscriptions.value = tubeChannels(Kv.get(SUBS))
        _history.value = tubeVideos(Kv.get(HISTORY))
        _later.value = tubeVideos(Kv.get(LATER))
        feedCache = null
    }

    fun subscribed(url: String?): Boolean = url != null && channelKey(url).let { k -> _subscriptions.value.any { channelKey(it.url) == k } }

    fun toggleSubscription(ch: TubeChannel) {
        val k = channelKey(ch.url)
        val now = _subscriptions.value
        _subscriptions.value = if (now.any { channelKey(it.url) == k }) now.filter { channelKey(it.url) != k } else listOf(ch) + now
        Kv.set(SUBS, tubeChannelsJson(_subscriptions.value))
        feedCache = null
    }

    /** Channels from an export added to those already here (duplicates skipped); how many were new. */
    fun importSubscriptions(found: List<TubeChannel>): Int {
        val have = _subscriptions.value.map { channelKey(it.url) }.toSet()
        val fresh = found.filter { channelKey(it.url) !in have }
        _subscriptions.value = _subscriptions.value + fresh
        Kv.set(SUBS, tubeChannelsJson(_subscriptions.value))
        feedCache = null
        return fresh.size
    }

    private fun updateAvatar(ch: TubeChannel) {
        val k = channelKey(ch.url)
        if (ch.avatar == null || _subscriptions.value.none { channelKey(it.url) == k && it.avatar != ch.avatar }) return
        _subscriptions.value = _subscriptions.value.map { if (channelKey(it.url) == k) it.copy(avatar = ch.avatar, name = ch.name.ifBlank { it.name }) else it }
        Kv.set(SUBS, tubeChannelsJson(_subscriptions.value))
    }

    /** Watched: to the top of History. */
    fun watched(v: TubeVideo) {
        _history.value = (listOf(v) + _history.value.filter { it.id != v.id }).take(HISTORY_MAX)
        Kv.set(HISTORY, tubeVideosJson(_history.value))
    }

    fun forget(id: String) {
        _history.value = _history.value.filter { it.id != id }
        Kv.set(HISTORY, tubeVideosJson(_history.value))
    }

    fun clearHistory() {
        _history.value = emptyList()
        Kv.set(HISTORY, null)
        Kv.set(SPOTS, null)
    }

    fun inLater(id: String) = _later.value.any { it.id == id }

    fun toggleLater(v: TubeVideo) {
        _later.value = if (inLater(v.id)) _later.value.filter { it.id != v.id } else listOf(v) + _later.value
        Kv.set(LATER, tubeVideosJson(_later.value))
    }

    /** Where a video was left (seconds), so it carries on there; 0 from the start. */
    fun spot(id: String): Long = runCatching { JSONObject(Kv.get(SPOTS) ?: "{}").optLong(id) }.getOrDefault(0)

    /** Kept while playing; near the end it's forgotten, so a finished video starts over next time. */
    fun setSpot(id: String, seconds: Long, length: Long) {
        val o = runCatching { JSONObject(Kv.get(SPOTS) ?: "{}") }.getOrDefault(JSONObject())
        if (seconds < 10 || (length > 0 && seconds > length - 15)) o.remove(id) else o.put(id, seconds)
        // Only the history's videos keep a spot.
        val keep = _history.value.map { it.id }.toSet() + id
        o.keys().asSequence().toList().filter { it !in keep }.forEach { o.remove(it) }
        Kv.set(SPOTS, o.toString())
    }
}
