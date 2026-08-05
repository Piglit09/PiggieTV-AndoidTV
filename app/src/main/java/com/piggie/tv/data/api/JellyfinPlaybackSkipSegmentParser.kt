package com.piggie.tv.data.api

import com.piggie.tv.data.models.PlaybackSkipSegment
import com.piggie.tv.data.models.PlaybackSkipSegmentType
import org.json.JSONArray
import org.json.JSONObject

/** Parses only exact ranges supplied by Jellyfin; it never invents timing heuristics. */
internal object JellyfinPlaybackSkipSegmentParser {
    fun parseMediaSegments(body: String): List<PlaybackSkipSegment> {
        val root = JSONObject(body)
        return parseMediaSegments(root.optJSONArray("Items") ?: JSONArray())
    }

    fun parseChapterMarkers(item: JSONObject, runtimeTicks: Long): List<PlaybackSkipSegment> {
        val chapters = item.optJSONArray("Chapters") ?: return emptyList()
        val markers = buildList {
            for (index in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(index) ?: continue
                val markerType = chapter.optString("MarkerType").trim()
                val ticks = chapter.optLong("StartPositionTicks", -1L)
                if (markerType.isNotEmpty() && ticks >= 0L) add(markerType to ticks)
            }
        }.sortedBy { it.second }

        val result = mutableListOf<PlaybackSkipSegment>()
        markers.forEachIndexed { index, marker ->
            val (markerType, startTicks) = marker
            when {
                markerType.equals("IntroStart", ignoreCase = true) -> {
                    val endTicks = markers.asSequence()
                        .drop(index + 1)
                        .firstOrNull { it.first.equals("IntroEnd", ignoreCase = true) }
                        ?.second
                    if (endTicks != null) {
                        PlaybackSkipSegment(PlaybackSkipSegmentType.INTRO, startTicks, endTicks)
                            .takeIf(PlaybackSkipSegment::isValid)
                            ?.let(result::add)
                    }
                }
                markerType.equals("OutroStart", ignoreCase = true) ||
                    markerType.equals("CreditsStart", ignoreCase = true) -> {
                    val endTicks = markers.asSequence()
                        .drop(index + 1)
                        .firstOrNull {
                            it.first.equals("OutroEnd", ignoreCase = true) ||
                                it.first.equals("CreditsEnd", ignoreCase = true)
                        }
                        ?.second
                        ?: runtimeTicks.takeIf { it > startTicks }
                    if (endTicks != null) {
                        PlaybackSkipSegment(PlaybackSkipSegmentType.OUTRO, startTicks, endTicks)
                            .takeIf(PlaybackSkipSegment::isValid)
                            ?.let(result::add)
                    }
                }
            }
        }
        return result.distinct().sortedBy(PlaybackSkipSegment::startTicks)
    }

    private fun parseMediaSegments(items: JSONArray): List<PlaybackSkipSegment> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val type = when {
                item.optString("Type").equals("Intro", ignoreCase = true) ->
                    PlaybackSkipSegmentType.INTRO
                item.optString("Type").equals("Outro", ignoreCase = true) ->
                    PlaybackSkipSegmentType.OUTRO
                else -> null
            } ?: continue
            PlaybackSkipSegment(
                type = type,
                startTicks = item.optLong("StartTicks", -1L),
                endTicks = item.optLong("EndTicks", -1L)
            ).takeIf(PlaybackSkipSegment::isValid)?.let(::add)
        }
    }.distinct().sortedBy(PlaybackSkipSegment::startTicks)
}
