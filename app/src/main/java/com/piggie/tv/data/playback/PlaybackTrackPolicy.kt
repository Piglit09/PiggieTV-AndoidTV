package com.piggie.tv.data.playback

import com.piggie.tv.data.models.AudioTrack
import com.piggie.tv.data.models.SubtitleTrack

/** The player track type without taking a dependency on Media3 classes. */
enum class PlaybackTrackType {
    AUDIO,
    TEXT
}

/**
 * Describes external subtitle resources to Media3 without assuming Jellyfin always serves VTT.
 *
 * Jellyfin can expose the original subtitle through [deliveryUrl] (for example `Stream.srt`) or
 * omit that URL, in which case the player requests its own VTT conversion endpoint. The concrete
 * URL therefore takes precedence over the source codec when choosing the parser MIME type.
 */
object PlaybackSubtitleSidecarPolicy {
    const val MIME_WEBVTT = "text/vtt"
    const val MIME_SUBRIP = "application/x-subrip"
    const val MIME_SSA = "text/x-ssa"
    const val MIME_TTML = "application/ttml+xml"

    fun mimeType(codec: String?, deliveryUrl: String?): String? {
        val extension = deliveryUrl
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
        mimeFor(extension)?.let { return it }
        return mimeFor(codec?.trim()?.lowercase())
    }

    fun shouldAttemptServerFallback(
        hasServerSelection: Boolean,
        hasExplicitExternalSubtitle: Boolean,
        alreadyAttempted: Boolean
    ): Boolean =
        !hasServerSelection && hasExplicitExternalSubtitle && !alreadyAttempted

    private fun mimeFor(value: String?): String? = when (value) {
        "vtt", "webvtt" -> MIME_WEBVTT
        "srt", "subrip" -> MIME_SUBRIP
        "ass", "ssa" -> MIME_SSA
        "ttml", "dfxp" -> MIME_TTML
        else -> null
    }
}

/** Rejects results produced for an older user selection, even if the selection later cycles. */
object PlaybackTrackRouteResultPolicy {
    fun isCurrent(
        requestedGeneration: Int,
        currentGeneration: Int,
        selectionMatches: Boolean
    ): Boolean = requestedGeneration == currentGeneration && selectionMatches
}

/**
 * A track exposed by the active player item.
 *
 * [groupIndex] and [trackIndex] are deliberately opaque coordinates. The player adapter can map
 * them to Media3's group and in-group track indices after this policy has chosen a candidate.
 */
data class PlaybackTrackCandidate(
    val type: PlaybackTrackType,
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val isSupported: Boolean = true,
    val externalId: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
) {
    init {
        require(groupIndex >= 0) { "groupIndex must be non-negative" }
        require(trackIndex >= 0) { "trackIndex must be non-negative" }
        require(channelCount == null || channelCount > 0) {
            "channelCount must be positive when present"
        }
    }
}

/** Track metadata copied from the Jellyfin item selected by the user. */
data class PlaybackTrackRequest(
    val type: PlaybackTrackType,
    val sourceIndex: Int,
    val externalId: String? = sourceIndex.toString(),
    val targetOrdinal: Int? = null,
    val language: String? = null,
    val label: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
) {
    init {
        require(sourceIndex >= 0) { "sourceIndex must be non-negative" }
        require(targetOrdinal == null || targetOrdinal >= 0) {
            "targetOrdinal must be non-negative when present"
        }
        require(channelCount == null || channelCount > 0) {
            "channelCount must be positive when present"
        }
    }

    companion object {
        fun from(
            track: AudioTrack,
            externalId: String? = null,
            targetOrdinal: Int? = null
        ) =
            PlaybackTrackRequest(
                type = PlaybackTrackType.AUDIO,
                sourceIndex = track.index,
                externalId = externalId,
                targetOrdinal = targetOrdinal,
                language = track.language,
                label = track.title,
                codec = track.codec,
                channelCount = track.channels,
                isDefault = track.isDefault
            )

        fun from(
            track: SubtitleTrack,
            externalId: String? = track.takeIf { it.isExternal }
                ?.let { "ptv-subtitle-${it.index}" },
            targetOrdinal: Int? = null
        ) =
            PlaybackTrackRequest(
                type = PlaybackTrackType.TEXT,
                sourceIndex = track.index,
                externalId = externalId,
                targetOrdinal = targetOrdinal,
                language = track.language,
                label = track.title,
                codec = track.codec,
                isDefault = track.isDefault,
                isForced = track.isForced
            )
    }
}

/** A user intent that can be resolved without special-casing default/off in the player. */
sealed interface PlaybackTrackSelection {
    data object Default : PlaybackTrackSelection
    data object Off : PlaybackTrackSelection
    data class Explicit(val request: PlaybackTrackRequest) : PlaybackTrackSelection
}

enum class PlaybackTrackMatchKind {
    EXTERNAL_ID,
    METADATA
}

/** Missing and unsupported are intentionally different so the UI never silently picks a track. */
sealed interface PlaybackTrackResolution {
    data object UseDefault : PlaybackTrackResolution
    data object Disabled : PlaybackTrackResolution

    data class Selected(
        val candidate: PlaybackTrackCandidate,
        val matchedBy: PlaybackTrackMatchKind
    ) : PlaybackTrackResolution

    data class Unsupported(
        val candidate: PlaybackTrackCandidate,
        val matchedBy: PlaybackTrackMatchKind
    ) : PlaybackTrackResolution

    data class Missing(val request: PlaybackTrackRequest) : PlaybackTrackResolution
}

/**
 * Matches Jellyfin stream metadata to tracks in the current player item.
 *
 * A stable external id always wins. If the player does not expose one, metadata matching uses
 * language, label, codec/MIME type, and audio channel count. Equal matches are resolved in a
 * deterministic order, preferring a supported candidate and then the lowest group/track index.
 */
object PlaybackTrackPolicy {
    fun selectAudio(
        track: AudioTrack,
        candidates: List<PlaybackTrackCandidate>,
        externalId: String? = null,
        targetOrdinal: Int? = null
    ): PlaybackTrackResolution = resolve(
        PlaybackTrackSelection.Explicit(
            PlaybackTrackRequest.from(track, externalId, targetOrdinal)
        ),
        candidates
    )

    fun selectSubtitle(
        track: SubtitleTrack,
        candidates: List<PlaybackTrackCandidate>,
        externalId: String? = track.takeIf { it.isExternal }
            ?.let { "ptv-subtitle-${it.index}" },
        targetOrdinal: Int? = null
    ): PlaybackTrackResolution = resolve(
        PlaybackTrackSelection.Explicit(
            PlaybackTrackRequest.from(track, externalId, targetOrdinal)
        ),
        candidates
    )

    fun resolve(
        selection: PlaybackTrackSelection,
        candidates: List<PlaybackTrackCandidate>
    ): PlaybackTrackResolution = when (selection) {
        PlaybackTrackSelection.Default -> PlaybackTrackResolution.UseDefault
        PlaybackTrackSelection.Off -> PlaybackTrackResolution.Disabled
        is PlaybackTrackSelection.Explicit -> select(selection.request, candidates)
    }

    private fun select(
        request: PlaybackTrackRequest,
        candidates: List<PlaybackTrackCandidate>
    ): PlaybackTrackResolution {
        val typedCandidates = candidates.filter { it.type == request.type }
        if (typedCandidates.isEmpty()) return PlaybackTrackResolution.Missing(request)

        val requestedExternalId = normalizeExternalId(request.externalId)
        if (requestedExternalId != null) {
            val idMatch = typedCandidates
                .asSequence()
                .filter { normalizeExternalId(it.externalId) == requestedExternalId }
                .sortedWith(candidateTieBreaker(request))
                .firstOrNull()
            if (idMatch != null) {
                return idMatch.toResolution(PlaybackTrackMatchKind.EXTERNAL_ID)
            }
        }

        val best = typedCandidates
            .mapIndexedNotNull { ordinal, candidate ->
                metadataScore(request, candidate)?.let { score ->
                    ScoredCandidate(candidate, score, ordinal)
                }
            }
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenByDescending {
                        request.targetOrdinal != null && it.ordinal == request.targetOrdinal
                    }
                    .thenBy {
                        request.targetOrdinal?.let { target -> kotlin.math.abs(it.ordinal - target) }
                            ?: 0
                    }
                    .thenByDescending { it.candidate.isSupported }
                    .thenBy { it.candidate.groupIndex }
                    .thenBy { it.candidate.trackIndex }
                    .thenBy { normalizeExternalId(it.candidate.externalId).orEmpty() }
            )
            .firstOrNull()
            ?.candidate
            ?: return PlaybackTrackResolution.Missing(request)

        return best.toResolution(PlaybackTrackMatchKind.METADATA)
    }

    private fun PlaybackTrackCandidate.toResolution(
        matchKind: PlaybackTrackMatchKind
    ): PlaybackTrackResolution = if (isSupported) {
        PlaybackTrackResolution.Selected(this, matchKind)
    } else {
        PlaybackTrackResolution.Unsupported(this, matchKind)
    }

    private fun candidateTieBreaker(
        request: PlaybackTrackRequest
    ): Comparator<PlaybackTrackCandidate> =
        compareByDescending<PlaybackTrackCandidate> { it.isSupported }
            .thenByDescending { it.isDefault == request.isDefault }
            .thenByDescending { it.isForced == request.isForced }
            .thenBy { it.groupIndex }
            .thenBy { it.trackIndex }

    private fun metadataScore(
        request: PlaybackTrackRequest,
        candidate: PlaybackTrackCandidate
    ): Int? {
        var score = 0
        var hasMeaningfulMatch = false

        val requestedLanguage = normalizeLanguage(request.language)
        val candidateLanguage = normalizeLanguage(candidate.language)
        if (requestedLanguage != null && candidateLanguage != null) {
            if (requestedLanguage != candidateLanguage) return null
            score += 1_000
            hasMeaningfulMatch = true
        }

        val labelScore = textMatchScore(request.label, candidate.label)
        if (labelScore > 0) {
            score += labelScore
            hasMeaningfulMatch = true
        }

        val requestedCodec = canonicalCodec(request.codec)
        val candidateCodecs = buildSet {
            canonicalCodec(candidate.codec)?.let(::add)
            canonicalCodec(candidate.mimeType)?.let(::add)
        }
        if (requestedCodec != null && candidateCodecs.isNotEmpty()) {
            if (requestedCodec in candidateCodecs) {
                score += 240
                hasMeaningfulMatch = true
            } else {
                score -= 40
            }
        }

        if (request.type == PlaybackTrackType.AUDIO &&
            request.channelCount != null &&
            candidate.channelCount != null
        ) {
            if (request.channelCount == candidate.channelCount) {
                score += 120
                hasMeaningfulMatch = true
            } else {
                score -= 30
            }
        }

        if (!hasMeaningfulMatch) return null

        if (request.isDefault == candidate.isDefault) score += 30
        if (request.type == PlaybackTrackType.TEXT && request.isForced == candidate.isForced) {
            score += 30
        }
        return score
    }

    private fun textMatchScore(left: String?, right: String?): Int {
        val normalizedLeft = normalizeText(left) ?: return 0
        val normalizedRight = normalizeText(right) ?: return 0
        if (normalizedLeft == normalizedRight) return 420

        val leftTokens = normalizedLeft.split(' ').toSet()
        val rightTokens = normalizedRight.split(' ').toSet()
        val sharedTokens = leftTokens intersect rightTokens
        if (sharedTokens.isEmpty()) return 0

        val coverage = sharedTokens.size.toDouble() / maxOf(leftTokens.size, rightTokens.size)
        return when {
            coverage >= 0.75 -> 300
            coverage >= 0.5 -> 200
            else -> 100
        }
    }

    private fun normalizeExternalId(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    private fun normalizeText(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }

    private fun normalizeLanguage(value: String?): String? {
        val language = normalizeText(value)
            ?.substringBefore(' ')
            ?.substringBefore('-')
            ?: return null
        if (language == "und" || language == "unknown") return null
        return LANGUAGE_ALIASES[language] ?: language
    }

    private fun canonicalCodec(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase()
            ?.substringAfterLast('/')
            ?.replace(Regex("[^a-z0-9]+"), "")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return when {
            normalized.startsWith("mp4a") || normalized == "aaclatm" -> "aac"
            normalized in setOf("eac3", "ec3") -> "eac3"
            normalized in setOf("ac3", "ac3audio") -> "ac3"
            normalized in setOf("subrip", "xsubrip", "srt") -> "subrip"
            normalized in setOf("webvtt", "vtt") -> "webvtt"
            normalized in setOf("ass", "ssa", "xass", "xssa") -> "ass"
            normalized in setOf("pgs", "pgssub", "hdmvpgssub") -> "pgs"
            else -> normalized
        }
    }

    private data class ScoredCandidate(
        val candidate: PlaybackTrackCandidate,
        val score: Int,
        val ordinal: Int
    )

    private val LANGUAGE_ALIASES = mapOf(
        "ar" to "ar", "ara" to "ar", "arabic" to "ar",
        "cs" to "cs", "ces" to "cs", "cze" to "cs", "czech" to "cs",
        "da" to "da", "dan" to "da", "danish" to "da",
        "de" to "de", "deu" to "de", "ger" to "de", "german" to "de",
        "en" to "en", "eng" to "en", "english" to "en",
        "es" to "es", "spa" to "es", "spanish" to "es",
        "fi" to "fi", "fin" to "fi", "finnish" to "fi",
        "fr" to "fr", "fra" to "fr", "fre" to "fr", "french" to "fr",
        "he" to "he", "heb" to "he", "hebrew" to "he",
        "hi" to "hi", "hin" to "hi", "hindi" to "hi",
        "hu" to "hu", "hun" to "hu", "hungarian" to "hu",
        "it" to "it", "ita" to "it", "italian" to "it",
        "ja" to "ja", "jpn" to "ja", "japanese" to "ja",
        "ko" to "ko", "kor" to "ko", "korean" to "ko",
        "nl" to "nl", "nld" to "nl", "dut" to "nl", "dutch" to "nl",
        "no" to "no", "nor" to "no", "norwegian" to "no",
        "pl" to "pl", "pol" to "pl", "polish" to "pl",
        "pt" to "pt", "por" to "pt", "portuguese" to "pt",
        "ru" to "ru", "rus" to "ru", "russian" to "ru",
        "sv" to "sv", "swe" to "sv", "swedish" to "sv",
        "th" to "th", "tha" to "th", "thai" to "th",
        "tr" to "tr", "tur" to "tr", "turkish" to "tr",
        "uk" to "uk", "ukr" to "uk", "ukrainian" to "uk",
        "vi" to "vi", "vie" to "vi", "vietnamese" to "vi",
        "zh" to "zh", "zho" to "zh", "chi" to "zh", "chinese" to "zh"
    )
}
