package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredPickTest {
    private val mb = 1024L * 1024

    private fun hit(name: String, quality: String?, size: Long, uri: String = "magnet:?xt=urn:btih:$name", seeders: Int = 20) =
        Hit(name, name, uri, seeders, 0, size, quality, "tv", Scope.EPISODE, null)

    private fun link(name: String, quality: String?, size: Long, uri: String) = Hit(uri, name, uri, 0, 0, size, quality, "tv", Scope.EPISODE, null, addon = "HdHub")

    private val defaults = DownloadPrefs()

    @Test fun lightest720pX265MkvByDefault() {
        val hits = listOf(
            hit("E03.2160p.x265.mkv", "4K", 4500 * mb),
            hit("E03.1080p.x265.mkv", "1080p", 1200 * mb),
            hit("E03.720p.x264.mkv", "720p", 500 * mb),
            hit("E03.720p.x265.mp4", "720p", 350 * mb),
            hit("E03.720p.x265.mkv", "720p", 420 * mb),
            hit("E03.720p.x265.mkv.small", "720p", 380 * mb),
        )
        assertEquals("E03.720p.x265.mkv.small", preferredPick(hits, defaults)!!.name)
    }

    @Test fun singleFilesBeforeHls() {
        val hls = link("Plugin 720p x265", "720p", 0, "https://cdn.x/master.m3u8")
        val file = link("E03.720p.x264.mp4", "720p", 900 * mb, "https://cdn.x/e03.mp4")
        assertEquals(file, preferredPick(listOf(hls, file), defaults))
        assertEquals(hls, preferredPick(listOf(hls), defaults))
    }

    @Test fun nearestResolutionBelowThenAbove() {
        val hits = listOf(hit("E03.1080p.x265.mkv", "1080p", 1200 * mb), hit("E03.480p.x264.mp4", "480p", 200 * mb))
        // No 720p: the next one up, before anything below.
        assertEquals("E03.1080p.x265.mkv", preferredPick(hits, defaults)!!.name)
        assertEquals("E03.480p.x264.mp4", preferredPick(hits.drop(1), defaults)!!.name)
        assertEquals("E03.1080p.x265.mkv", preferredPick(hits.take(1), defaults)!!.name)
        assertEquals("E03.1080p.x265.mkv", preferredPick(hits, defaults.copy(quality = "1080p"))!!.name)
    }

    @Test fun anyMeansLightest() {
        val hits = listOf(hit("A.1080p.x264.mp4", "1080p", 800 * mb), hit("B.720p.x265.mkv", "720p", 900 * mb))
        assertEquals("A.1080p.x264.mp4", preferredPick(hits, DownloadPrefs("", "", "", false, ""))!!.name)
    }

    @Test fun trailersNeverPicked() {
        assertNull(preferredPick(listOf(hit("E03.720p.trailer.mkv", "720p", 8 * mb)), defaults))
    }

    @Test fun distances() {
        assertEquals(0, resolutionDistance("720p", "720p"))
        assertEquals(10_240, resolutionDistance("480p", "720p"))
        assertEquals(360, resolutionDistance("1080p", "720p"))
        assertEquals(0, resolutionDistance("4K", ""))
    }

    /**
     * The smallest reasonable 720p MKV, torrents and links alike (the owner's rule): a 423 MB x265 MKV torrent over
     * PenguPlay's 589 MB HLS link; a link or a trusted site only between files of about the same size.
     */
    @Test fun lightestWhateverItCameAs() {
        val torrent = hit("Dope.Thief.S01E01.720p.x265.mkv", "720p", 423 * mb)
        val light = link("PenguPlay 720p HLS H.264 589 MB", "720p", 589 * mb, "https://cdn.x/a.m3u8")
        val heavy = link("PenguPlay 720p HLS H.264 2.02 GB", "720p", 2068 * mb, "https://cdn.x/b.m3u8")
        assertEquals(torrent, preferredPick(listOf(torrent, light, heavy), defaults))
        // Within a tenth of the lightest: MoviesMod's link before the torrent.
        val mkvLink = link("Moviesmod 720p [440MB].mkv", "720p", 440 * mb, "https://mm.x/e01.mkv")
        assertEquals(mkvLink, preferredPick(listOf(torrent, mkvLink, light), defaults))
        val link1080 = link("Plugin 1080p [1.2GB].mkv", "1080p", 1200 * mb, "https://cdn.x/e01-1080.mkv")
        assertEquals(torrent, preferredPick(listOf(torrent, link1080), defaults))
        assertEquals(torrent, preferredPick(listOf(torrent), defaults))
        assertEquals(setOf(heavy), preferableLinks(listOf(heavy)))
        assertEquals(light, batchPick(listOf(torrent, light), BatchFilter(quality = "720p"), preferDirect = true))
        assertEquals(torrent, seasonPick(listOf(torrent, light), defaults))
    }

    /** The owner's E07: HDHub's 809 MB x265 link against Torrentio's 203 MB 720p x265 MKV with 30 seeds — the torrent. */
    @Test fun smallReasonableTorrentOverHeavyLink() {
        val hub = link("Furious.S01E07.720p.HEVC.x265-HDHub4u.mkv [10Gbps Download Only]", "720p", 809 * mb, "https://h.x/e7").copy(addon = "HdHub")
        val small = hit("Furious.S01E07.720p.HEVC.x265-MeGusta.mkv", "720p", 203 * mb, seeders = 30)
        val thin = hit("Furious.S01E07.720p.x264-FENiX.mkv", "720p", 455 * mb, seeders = 11)
        assertEquals(small, seasonPick(listOf(hub, small, thin), defaults))
        // Suspiciously small for 720p (a sample): after every reasonable one.
        val sample = hit("Furious.S01E07.720p.x265.sample.mkv", "720p", 40 * mb, seeders = 50)
        assertEquals(small, seasonPick(listOf(hub, small, sample), defaults))
        // A swarm too thin to finish (under 10 seeds) waits behind a link that can download now.
        assertEquals(hub, seasonPick(listOf(hub, small.copy(seeders = 4)), defaults))
    }

    @Test fun mkvUpTheChainBeforeOtherFormats() {
        val mp4 = hit("E01.720p.x264.mp4", "720p", 300 * mb)
        val mkv1080 = hit("E01.1080p.x265.mkv", "1080p", 900 * mb)
        assertEquals(mkv1080, preferredPick(listOf(mp4, mkv1080), defaults))
        assertEquals(mp4, preferredPick(listOf(mp4), defaults))
    }

    /** The lightest wins; among the near-lightest, the well-seeded swarm. */
    @Test fun lightestThenWellSeeded() {
        val thin = hit("E01.720p.x265.mkv", "720p", 400 * mb, seeders = 12)
        val busy = hit("E01.720p.x264.mkv", "720p", 575 * mb, seeders = 41)
        assertEquals(thin, preferredPick(listOf(thin, busy), defaults))
        val busyLight = busy.copy(size = 420 * mb)
        assertEquals(busyLight, preferredPick(listOf(thin, busyLight), defaults))
        assertEquals(busy, batchPick(listOf(thin.copy(seeders = 8), busy), BatchFilter(quality = "720p")))
    }

    /** A season scan stops asking about an episode once its pick is what was asked for, as a link. */
    @Test fun whenAScanCanStop() {
        val prefs = defaults.copy(direct = true)
        val torrent = hit("E01.720p.x265.mkv", "720p", 423 * mb, seeders = 41)
        val hls = link("PenguPlay 720p 589 MB", "720p", 589 * mb, "https://cdn.x/a.m3u8")
        val mkv = link("Moviesmod 720p [420MB].mkv", "720p", 420 * mb, "https://mm.x/e01.mkv")
        assertEquals(false, goodEnough(emptyList(), prefs))
        // Torrents alone: keep asking, the links may come.
        assertEquals(false, goodEnough(listOf(torrent), defaults))
        // Only the HLS link: not MKV, keep asking.
        assertEquals(false, goodEnough(listOf(torrent, hls), prefs))
        // A 720p MKV link is the end of it; a 1080p one isn't.
        assertEquals(true, goodEnough(listOf(torrent, mkv), prefs))
        assertEquals(false, goodEnough(listOf(mkv.copy(quality = "1080p")), defaults))
    }

    /** Torrents under 10 seeds are never picked when they're hidden from the lists — unless TorBox has them cached. */
    @Test fun thinTorrentsSkipped() {
        val thin = hit("E01.720p.x265.mkv", "720p", 400 * mb, seeders = 3)
        val prefs = defaults.copy(skipFewSeeds = true)
        assertNull(preferredPick(listOf(thin), prefs))
        assertEquals(thin, preferredPick(listOf(thin), defaults))
        assertEquals(thin, preferredPick(listOf(thin), prefs.copy(cached = setOf(thin.id.lowercase()))))
        val link = link("Plugin 720p", "720p", 500 * mb, "https://cdn.x/e01.mp4")
        assertEquals(link, preferredPick(listOf(thin, link), prefs))
        assertNull(batchPick(listOf(thin), BatchFilter(quality = "720p"), prefs = prefs))
    }

    /** MoviesMod, MoviesDrive and HDHub first among links at the resolution, though another link is lighter or says MKV. */
    @Test fun trustedSitesFirst() {
        val other = link("FibWatch 720p [280MB].mkv", "720p", 280 * mb, "https://fw.x/e07.mkv").copy(addon = "CineStream")
        val mod = link("Moviesmod Driveseed [BLURAY] [305.65MB]", "720p", 306 * mb, "https://mm.x/e07").copy(addon = "StreamPlay")
        assertEquals(mod, preferredPick(listOf(other, mod), defaults))
        assertEquals(true, trustedSource(link("HDHub4u 720p", "720p", 0, "https://h.x/1")))
        assertEquals(true, trustedSource(link("x", "720p", 0, "https://h.x/2").copy(addon = "MoviesDrive")))
        // Not at the cost of the resolution.
        assertEquals(other, preferredPick(listOf(other, mod.copy(quality = "1080p")), defaults))
    }

    /** The trusted sites in the owner's order — MoviesMod, then MoviesDrive, then HDHub — among files of about the same size. */
    @Test fun trustedSitesInOrder() {
        val hub = link("HDHub4u 720p [300MB]", "720p", 300 * mb, "https://h.x/e1")
        val drive = link("MoviesDrive 720p [310MB]", "720p", 310 * mb, "https://d.x/e1").copy(addon = "StreamPlay")
        val mod = link("Moviesmod Driveseed [320MB]", "720p", 320 * mb, "https://m.x/e1").copy(addon = "StreamPlay")
        assertEquals(3, trustRank(mod))
        assertEquals(2, trustRank(drive))
        assertEquals(1, trustRank(hub))
        assertEquals(mod, preferredPick(listOf(hub, drive, mod), defaults))
        assertEquals(drive, preferredPick(listOf(hub, drive), defaults))
        // A third lighter is a different file: the smaller, whatever the site.
        assertEquals(hub.copy(size = 220 * mb), preferredPick(listOf(hub.copy(size = 220 * mb), mod), defaults))
        assertEquals(mod, batchPick(listOf(hub, drive, mod), BatchFilter(quality = "720p")))
    }

    /** The owner's E03: HDHub's 515 MB MKV and MoviesDrive's 394 MB, whose name doesn't say MKV — MoviesDrive's is taken. */
    @Test fun moviesDriveOverHdHubInASeason() {
        val hub = link("Hdhub4u [HubCloud [BuzzServer]] WEB-DL x264 720p .mkv", "720p", 515 * mb, "https://h.x/e3").copy(addon = "CineStream")
        val drive = link("MoviesDrive GDFlix [Instant Download] WEB-DL X264 [394.04MB]", "720p", 394 * mb, "https://d.x/e3").copy(addon = "StreamPlay")
        assertEquals(drive, seasonPick(listOf(hub, drive), defaults))
        // Only HDHub's: asked about again, for MoviesMod's or MoviesDrive's; with MoviesDrive's, not.
        assertTrue(wantsTrustedAgain(listOf(hub), defaults))
        assertFalse(wantsTrustedAgain(listOf(hub, drive), defaults))
        assertTrue(wantsTrustedAgain(emptyList(), defaults))
    }

    /** A link seen on a first look stays when a second look's list lacks it; a newer reading replaces the older. */
    @Test fun mergedHitsKeepEveryLink() {
        val drive = link("MoviesDrive GDFlix", "720p", 394 * mb, "https://d.x/e3")
        val hub = link("Hdhub4u", "720p", 515 * mb, "https://h.x/e3")
        val read = drive.copy(release = "Neagley.S01E03.720p.x264.mkv")
        assertEquals(listOf(hub, drive), mergedHits(listOf(drive), listOf(hub)))
        assertEquals(listOf(read, hub), mergedHits(listOf(drive, hub), listOf(read)))
    }
}
