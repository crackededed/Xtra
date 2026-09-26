package com.github.andreyasadchy.xtra.util.m3u8

import java.io.InputStream
import java.io.OutputStream

object PlaylistUtils {
    fun parseMediaPlaylist(input: InputStream): MediaPlaylist {
        var targetDuration = 10
        val dateRanges = mutableListOf<DateRange>()
        var programDateTime: String? = null
        var initSegmentUri: String? = null
        val segments = mutableListOf<Segment>()
        var segmentInfo: Pair<Float, String?>? = null
        var end = false
        input.bufferedReader().forEachLine { line ->
            if (line.isNotBlank()) {
                if (line.startsWith('#')) {
                    when {
                        line.startsWith("#EXT-X-TARGETDURATION") -> {
                            Regex("#EXT-X-TARGETDURATION:(\\d+)\\b").find(line)?.groups?.get(1)?.value?.toIntOrNull()?.let {
                                targetDuration = it
                            }
                        }
                        line.startsWith("#EXT-X-DATERANGE") -> {
                            val id = Regex("ID=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                            val startDate = Regex("START-DATE=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                            if (id != null && startDate != null) {
                                dateRanges.add(DateRange(
                                    id = id,
                                    className = Regex("CLASS=\"(.+?)\"").find(line)?.groups?.get(1)?.value,
                                    startDate = startDate,
                                    endDate = Regex("END-DATE=\"(.+?)\"").find(line)?.groups?.get(1)?.value,
                                    duration = Regex("DURATION=([\\d.]+)\\b").find(line)?.groups?.get(1)?.value?.toFloatOrNull(),
                                    plannedDuration = Regex("PLANNED-DURATION=([\\d.]+)\\b").find(line)?.groups?.get(1)?.value?.toFloatOrNull(),
                                    clientAttributes = Regex("\\b(X-[A-Z0-9-]+)=").findAll(line).mapNotNull { result ->
                                        val name = result.groups[1]?.value
                                        val value = Regex("${name}=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                                            ?: Regex("${name}=([\\d.]+)\\b").find(line)?.groups?.get(1)?.value
                                        if (name != null && value != null) {
                                            name to value
                                        } else null
                                    }.toList()
                                ))
                            }
                        }
                        line.startsWith("#EXT-X-PROGRAM-DATE-TIME") -> {
                            programDateTime = line.substringAfter("#EXT-X-PROGRAM-DATE-TIME:")
                        }
                        line.startsWith("#EXT-X-MAP") -> {
                            Regex("URI=\"(.+?)\"").find(line)?.groups?.get(1)?.value?.let {
                                initSegmentUri = it
                            }
                        }
                        line.startsWith("#EXTINF") -> {
                            val duration = Regex("#EXTINF:([\\d.]+)\\b").find(line)?.groups?.get(1)?.value?.toFloatOrNull()
                            if (duration != null) {
                                val title = Regex("#EXTINF:[\\d.]+\\b,(.+)").find(line)?.groups?.get(1)?.value
                                segmentInfo = Pair(duration, title)
                            }
                        }
                        line.startsWith("#EXT-X-ENDLIST") -> {
                            end = true
                        }
                    }
                } else {
                    segmentInfo?.let {
                        segments.add(Segment(line, it.first, it.second, programDateTime))
                        segmentInfo = null
                    }
                }
            }
        }
        return MediaPlaylist(targetDuration, dateRanges, initSegmentUri, segments, end)
    }

    fun writeMediaPlaylist(playlist: MediaPlaylist, output: OutputStream) {
        output.bufferedWriter().use { writer ->
            writer.write("#EXTM3U")
            writer.newLine()
            writer.write("#EXT-X-VERSION:${if (playlist.initSegmentUri != null) 6 else 3}")
            writer.newLine()
            writer.write("#EXT-X-PLAYLIST-TYPE:EVENT")
            writer.newLine()
            writer.write("#EXT-X-TARGETDURATION:${playlist.targetDuration}")
            writer.newLine()
            writer.write("#EXT-X-MEDIA-SEQUENCE:0")
            if (playlist.initSegmentUri != null) {
                writer.newLine()
                writer.write("#EXT-X-MAP:URI=\"${playlist.initSegmentUri}\"")
            }
            playlist.segments.forEach {
                writer.newLine()
                writer.write("#EXTINF:${it.duration}")
                writer.newLine()
                writer.write(it.uri)
            }
            writer.newLine()
            writer.write("#EXT-X-ENDLIST")
        }
    }
}