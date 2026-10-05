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

/**
 * 검색 결과 한 건. [source] 는 화면에 보이는 출처 이름이고, 길이를 모르면 [durationSec] 이 음수다.
 * 벅스·지니·멜론 검색 결과는 가사 없이 오고, 고른 뒤에 가사를 따로 받아 채운다.
 */
data class Candidate(
    val source: String,
    val id: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
) {
    val key: String get() = "$source:$id"
    val hasLyrics: Boolean get() = synced != null || plain != null || instrumental
}

/** 고른 결과. [useSynced] 가 false 면 길이가 맞지 않아 싱크 가사를 믿지 않는다는 뜻. */
data class Choice(val candidate: Candidate, val useSynced: Boolean)

object TrackMatcher {
    private const val DURATION_TOLERANCE_SEC = 3.0
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETED = Regex("[(\\[]([^)\\]]*)[)\\]]")
    private val VERSION_MARK = Regex(
        "\\b(?:live|remix|inst\\.?|instrumental|acoustic|ver\\.?|version|mr|edit|demo|sped\\s*up|slowed|cover|karaoke)\\b",
        RegexOption.IGNORE_CASE,
    )

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

    /**
     * 자동 선택에 쓸 제목 일치 정도. 2: 완전히 같음, 1: 괄호 속 표기 등으로 맞음,
     * 0: 맞지 않거나 결과에만 live·remix 같은 다른 버전 표시가 있음(박자가 달라 싱크가 틀어진다).
     */
    private fun titleScore(track: Track, name: String): Int = when {
        normalize(track.title) == normalize(name) -> 2
        !titleMatches(track.title, name) -> 0
        VERSION_MARK.containsMatchIn(name) && !VERSION_MARK.containsMatchIn(track.title) -> 0
        else -> 1
    }

    /** 제목이 맞는 결과만, 완전히 같은 것부터. */
    private fun titleMatched(track: Track, cands: List<Candidate>): List<Candidate> =
        cands.map { it to titleScore(track, it.trackName) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

    fun artistMatches(a: String, b: String): Boolean {
        val left = artistForms(a)
        return artistForms(b).any { it in left }
    }

    private fun artistForms(s: String): Set<String> =
        s.split(ARTIST_SEPARATORS)
            .flatMap { forms(it) }
            .toSet()

    private fun durationKnown(track: Track, c: Candidate) = track.durationSec > 0 && c.durationSec >= 0

    /** 양쪽 길이를 다 알고, 그 차이가 허용 범위 안이다. */
    fun durationClose(track: Track, c: Candidate): Boolean =
        durationKnown(track, c) && abs(c.durationSec - track.durationSec) <= DURATION_TOLERANCE_SEC

    /** 양쪽 길이를 다 아는데 차이가 크다. 같은 곡의 다른 버전(라이브, 리믹스 등)일 가능성이 높다. */
    fun durationConflict(track: Track, c: Candidate): Boolean =
        durationKnown(track, c) && !durationClose(track, c)

    /**
     * 자동으로 쓸 결과를 고른다. 제목이 맞고, 가수가 맞거나 길이가 확실히 맞아야 후보가 된다.
     * 길이가 어긋나면 싱크 가사를 믿지 않는다. 제목만 같은 다른 노래를 고르지 않도록 기준을 넘는 게 없으면 null.
     */
    fun choose(track: Track, cands: List<Candidate>): Choice? {
        val ok = titleMatched(track, cands.filter { it.hasLyrics })
        fun artist(c: Candidate) = artistMatches(track.artist, c.artistName)

        ok.firstOrNull { it.synced != null && artist(it) && !durationConflict(track, it) }?.let { return Choice(it, true) }
        ok.firstOrNull { it.synced != null && durationClose(track, it) }?.let { return Choice(it, true) }
        ok.firstOrNull { artist(it) && !durationConflict(track, it) }?.let { return Choice(it, false) }
        ok.firstOrNull { artist(it) }?.let { return Choice(it, false) }
        return null
    }

    /** 가사를 받기 전의 검색 결과(벅스·지니·멜론) 중에서 같은 곡으로 볼 수 있는 첫 결과. */
    fun matchWithoutLyrics(track: Track, cands: List<Candidate>): Candidate? = titleMatched(track, cands).firstOrNull {
        artistMatches(track.artist, it.artistName) && !durationConflict(track, it)
    }

    /** 직접 고르는 화면용 정렬: 잘 맞는 것부터. */
    fun rank(track: Track, cands: List<Candidate>): List<Candidate> =
        cands.sortedByDescending {
            titleScore(track, it.trackName) * 2 +
                (if (artistMatches(track.artist, it.artistName)) 2 else 0) +
                (if (durationClose(track, it)) 2 else 0) +
                (if (it.synced != null) 1 else 0)
        }
}
