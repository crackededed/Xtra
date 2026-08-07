package com.github.andreyasadchy.xtra.util.m3u8

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import kotlin.time.Instant

/**
 * Detects the ad markers Twitch currently exposes in live HLS playlists.
 *
 * Keep this separate from the player so all playback implementations make the
 * same decision when a playlist rolls over to an ad window.
 */
@androidx.media3.common.util.UnstableApi
object TwitchAdDetector {

    private val adTitleMarkers = listOf("Amazon", "Adform", "DCM")

    fun isAd(playlist: HlsMediaPlaylist): Boolean {
        val segment = playlist.segments.lastOrNull() ?: return false
        val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
        return adTitleMarkers.any { segment.title.contains(it, ignoreCase = true) }
                || playlist.interstitials.any { interstitial ->
            val startTime = interstitial.startDateUnixUs
            val endTime = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
            endTime != null
                    && (interstitial.id.startsWith("stitched-ad-")
                    || interstitial.clientDefinedAttributes.any { attribute ->
                        (attribute.name == "CLASS" && attribute.textValue == "twitch-stitched-ad")
                                || attribute.name.startsWith("X-TV-TWITCH-AD-")
                    })
                    && segmentStartTime in startTime..endTime
        }
    }

    fun isAd(playlist: MediaPlaylist): Boolean {
        val segment = playlist.segments.lastOrNull() ?: return false
        if (adTitleMarkers.any { segment.title?.contains(it, ignoreCase = true) == true }) {
            return true
        }
        val segmentStartTime = segment.programDateTime
            ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
            ?: return false
        return playlist.dateRanges.any { dateRange ->
            if (!isTwitchAd(dateRange.id, dateRange.rangeClass, dateRange.ad)) {
                return@any false
            }
            val startTime = Instant.parseOrNull(dateRange.startDate)?.toEpochMilliseconds()
                ?: return@any false
            val endTime = dateRange.endDate
                ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                ?: dateRange.duration?.let { startTime + (it * 1000f).toLong() }
                ?: dateRange.plannedDuration?.let { startTime + (it * 1000f).toLong() }
            segmentStartTime >= startTime && (endTime == null || segmentStartTime < endTime)
        }
    }

    private fun isTwitchAd(id: String, rangeClass: String?, ad: Boolean = false): Boolean {
        return ad || id.startsWith("stitched-ad-") || rangeClass == "twitch-stitched-ad"
    }

}
