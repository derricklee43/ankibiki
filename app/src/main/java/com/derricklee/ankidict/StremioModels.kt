package com.derricklee.ankidict

/**
 * Stremio Mining Console data models (docs/stremio-mining-console-design.md).
 * Shapes pinned against Stremio's own serde sources (stremio-core
 * src/types/library/library_item.rs and the addon manifest types), verified 2026-11-23;
 * live-account validation is spike S0-A (pending on-device run).
 */

/** One libraryItem.state as returned by datastoreGet collection "libraryItem". */
data class StremioItemState(
    val videoId: String?,
    /** Seconds or ms depending on writing client — S0-A measures; treat as opaque high-water hint. */
    val timeOffset: Long,
    val duration: Long,
    val lastWatched: String?,
    val timesWatched: Int,
    val flaggedWatched: Int,
    /** base64 anchor:len:zlib-blob watched map, see WatchedBitfield. */
    val watchedBlob: String?,
)

/** A library row: series/movie identity + state. baseId is imdb "tt..." style. */
data class StremioLibraryItem(
    val baseId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val state: StremioItemState?,
)

/** An installed addon from addonCollectionGet; transportUrl carries user config (incl. keys). */
data class StremioAddon(
    val addonId: String,
    val name: String,
    val version: String,
    val transportUrl: String,
    /** resources offered: "meta", "subtitle", "stream", "catalog" ... */
    val resourceTypes: Set<String>,
)

/** One subtitle candidate row from a subtitle addon resource. */
data class SubtitleChoice(
    val subId: String,
    val lang: String,
    val releaseName: String,
    val fileUrl: String,
    val addonId: String,
)

/** A parsed subtitle cue. endMs drives nothing in this design (whole-episode scope, D3/D5). */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** One video row from a meta addon resource. */
data class MetaEpisode(
    val episodeId: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
)

/** Stream candidate; infoHash+fileIdx are the §5.5 file identity. url may be absent (magnet/external). */
data class StreamCandidate(
    val name: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
)

/** RD /downloads entry fields we consume (identity spine + candidate hints only). */
data class RdDownloadEntry(
    val fileId: String,
    val filename: String,
    val filesize: Long,
    val type: String,
    val link: String,
    val downloadUrl: String,
    val host: String,
    val submitted: String?,
    val completed: String?,
)

data class RdUser(
    val username: String,
    val type: String,
    val expiration: String?,
)

/** Episode reference regex-parsed from an RD filename (S0-D2 candidate feed; NEVER authority). */
data class ParsedEpisodeRef(
    val season: Int,
    val episode: Int,
)
