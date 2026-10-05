package com.example.lyricsfloat

import kotlin.math.abs

private val ARTIST_SEPARATORS =
    Regex("\\s*(?:,|&|•|/|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)

/** 유튜브 뮤직 메타데이터를 가사 검색에 맞게 정리한 곡 정보. */
data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
) {
    val primaryArtist: String
        get() = artist.split(ARTIST_SEPARATORS).first().trim()

    /** 캐시와 싱크 보정값을 저장할 때 쓰는 키. 메타데이터가 단계적으로 들어와도 같은 곡이면 같아야 해서 길이는 넣지 않는다. */
    val key: String
        get() = "${TrackMatcher.normalize(title)}|${TrackMatcher.normalize(primaryArtist)}"

    companion object {
        private val NOISE_BRACKET = Regex(
            "\\s*[(\\[【]" +
                "[^)\\]】]*?(?:official|music\\s*video|\\bm/?v\\b|lyrics?|\\baudio\\b|visuali[sz]er|" +
                "\\bfeat\\.?|\\bft\\.?|\\bprod\\.?|remaster(?:ed)?|performance\\s*video|teaser)" +
                "[^)\\]】]*[)\\]】]",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_FEAT = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
        private val TRAILING_NOISE =
            Regex("\\s+(?:official\\s+(?:music\\s+)?video|official\\s+audio|\\bm/?v)$", RegexOption.IGNORE_CASE)
        private val CHANNEL_SUFFIX = Regex("\\s*-\\s*topic$|vevo$", RegexOption.IGNORE_CASE)
        private val TITLE_SPLIT = Regex("\\s+[-_–—]\\s+")

        // 무료 계정 광고가 메타데이터에 이런 제목으로 들어오는 것으로 알려져 있다(기기에서 확인 필요).
        private val AD_TITLES = setOf("advertisement", "광고", "ad")

        fun isLikelyAd(rawTitle: String): Boolean = rawTitle.trim().lowercase() in AD_TITLES

        /** 유튜브 뮤직이 넘겨준 그대로의 값을 정리한다. 뮤직비디오는 가수 자리에 채널명이 오기도 한다. */
        fun from(rawTitle: String, rawArtist: String, album: String, durationMs: Long): Track {
            val artist = rawArtist.replace(CHANNEL_SUFFIX, "").trim()
            var title = rawTitle.replace(NOISE_BRACKET, "").trim()

            // "가수 - 제목", "[MV] 가수 _ 제목" 처럼 앞에 가수가 붙은 영상 제목
            val parts = title.split(TITLE_SPLIT, limit = 2)
            if (parts.size == 2 && artist.isNotBlank() && TrackMatcher.artistMatches(parts[0], artist)) {
                title = parts[1]
            }
            title = title.replace(TRAILING_FEAT, "").replace(TRAILING_NOISE, "").trim()

            return Track(
                title = title.ifBlank { rawTitle.trim() },
                artist = artist,
                album = album.trim(),
                durationSec = (durationMs / 1000).toInt(),
            )
        }
    }
}

/** LRCLIB 검색 결과 한 건. */
data class Candidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
) {
    val hasLyrics: Boolean get() = synced != null || plain != null || instrumental
}

/** 고른 결과. [useSynced] 가 false 면 길이가 맞지 않아 싱크 가사를 믿지 않는다는 뜻. */
data class Choice(val candidate: Candidate, val useSynced: Boolean)

object TrackMatcher {
    private const val DURATION_TOLERANCE_SEC = 3.0
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETED = Regex("[(\\[]([^)\\]]*)[)\\]]")

    fun normalize(s: String): String = s.lowercase().replace(NON_WORD, "")

    /**
     * 비교에 쓸 여러 표기. "Through the Night (밤편지)" 는 전체, 괄호 밖 "Through the Night", 괄호 안 "밤편지"
     * 세 가지로 비교해서 유튜브 뮤직이 한국어 제목만 줘도 맞출 수 있게 한다.
     */
    fun forms(s: String): Set<String> {
        val out = mutableSetOf(normalize(s), normalize(s.replace(BRACKETED, " ")))
        BRACKETED.findAll(s).forEach { out.add(normalize(it.groupValues[1])) }
        out.remove("")
        return out
    }

    fun titleMatches(a: String, b: String): Boolean = forms(a).any { it in forms(b) }

    fun artistMatches(a: String, b: String): Boolean {
        val left = artistForms(a)
        return artistForms(b).any { it in left }
    }

    private fun artistForms(s: String): Set<String> =
        s.split(ARTIST_SEPARATORS)
            .flatMap { forms(it) }
            .toSet()

    fun durationClose(track: Track, c: Candidate): Boolean =
        track.durationSec <= 0 || abs(c.durationSec - track.durationSec) <= DURATION_TOLERANCE_SEC

    /**
     * 자동으로 쓸 결과를 고른다. 제목이 맞고, 가수나 길이 중 하나 이상이 맞아야 후보가 된다.
     * 싱크 가사는 길이가 맞을 때만 믿는다. 제목만 같은 다른 노래를 고르지 않도록 기준을 넘는 게 없으면 null.
     */
    fun choose(track: Track, cands: List<Candidate>): Choice? {
        val ok = cands.filter { it.hasLyrics && titleMatches(track.title, it.trackName) }
        fun artist(c: Candidate) = artistMatches(track.artist, c.artistName)
        fun close(c: Candidate) = durationClose(track, c)

        ok.firstOrNull { it.synced != null && close(it) && artist(it) }?.let { return Choice(it, true) }
        ok.firstOrNull { it.synced != null && close(it) }?.let { return Choice(it, true) }
        ok.firstOrNull { (it.plain != null || it.instrumental) && artist(it) && close(it) }?.let { return Choice(it, false) }
        ok.firstOrNull { (it.plain != null || it.instrumental) && artist(it) }?.let { return Choice(it, false) }
        return null
    }

    /** 직접 고르는 화면용 정렬: 잘 맞는 것부터. */
    fun rank(track: Track, cands: List<Candidate>): List<Candidate> =
        cands.filter { it.hasLyrics }.sortedByDescending {
            (if (titleMatches(track.title, it.trackName)) 4 else 0) +
                (if (artistMatches(track.artist, it.artistName)) 2 else 0) +
                (if (durationClose(track, it)) 2 else 0) +
                (if (it.synced != null) 1 else 0)
        }
}
