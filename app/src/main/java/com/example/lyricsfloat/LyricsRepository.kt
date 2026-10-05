package com.example.lyricsfloat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class Lyrics(val choice: Choice) {
    private val parsed: List<LrcLine> = choice.candidate.synced?.let { LrcParser.parse(it) }.orEmpty()
    val lines: List<LrcLine> = if (choice.useSynced) parsed else emptyList()

    /** 싱크 가사만 있는데 싱크를 믿지 못할 때는 시간 정보를 뺀 가사를 보여준다. */
    val plain: String? = choice.candidate.plain
        ?: parsed.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
    val instrumental: Boolean = choice.candidate.instrumental
}

/**
 * 가사를 찾아 기기에 저장해 둔다. LRCLIB(lrclib.net) → 벅스 → 지니 → 멜론 순서로 찾고,
 * 싱크 가사를 찾으면 바로 멈춘다. 끝까지 싱크 가사가 없으면 처음 찾은 일반 가사를 쓴다.
 */
class LyricsRepository(context: Context) {
    private val cacheDir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val sources = listOf(Bugs, Genie, Melon)

    /** [isStale] 이 true 가 되면 남은 요청을 건너뛴다. 곡을 빠르게 넘길 때 지난 곡 요청이 새 곡을 막지 않게 하려는 것. */
    fun find(track: Track, isStale: () -> Boolean): Lyrics? {
        loadCached(track)?.let { return Lyrics(it) }

        val found = LinkedHashMap<String, Candidate>()
        for (url in lrclibQueries(track, broad = false)) {
            if (isStale()) return null
            fetchLrclib(url).forEach { found.putIfAbsent(it.key, it) }
            val choice = TrackMatcher.choose(track, found.values.toList())
            if (choice != null && choice.useSynced) return save(track, choice)
        }
        var fallback = TrackMatcher.choose(track, found.values.toList())

        for (src in sources) {
            if (isStale()) return null
            val match = TrackMatcher.matchWithoutLyrics(track, search(src, track)) ?: continue
            val loaded = load(src, match) ?: continue
            val choice = Choice(loaded, useSynced = loaded.synced != null)
            Log.d(TAG, "${src.name}에서 찾음: ${loaded.trackName} / ${loaded.artistName} (싱크 ${choice.useSynced})")
            if (choice.useSynced) return save(track, choice)
            if (fallback == null) fallback = choice
        }
        return fallback?.let { save(track, it) }
    }

    /** 직접 고르기 화면용. 모든 출처에서 자동 선택보다 넓게 검색한다. 벅스·지니·멜론 결과는 가사가 비어 있다. */
    fun candidates(track: Track): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        for (url in lrclibQueries(track, broad = true)) {
            fetchLrclib(url).filter { it.hasLyrics }.forEach { found.putIfAbsent(it.key, it) }
        }
        for (src in sources) search(src, track).forEach { found.putIfAbsent(it.key, it) }
        return TrackMatcher.rank(track, found.values.toList())
    }

    /** 직접 고른 결과. 가사가 아직 없으면 받아 온다. 고른 사람의 판단을 믿고 싱크가 있으면 쓴다. */
    fun pick(track: Track, c: Candidate): Lyrics? {
        val loaded = if (c.hasLyrics) c else sources.firstOrNull { it.name == c.source }?.let { load(it, c) }
        return loaded?.let { save(track, Choice(it, useSynced = it.synced != null)) }
    }

    private fun search(src: LyricsSource, track: Track): List<Candidate> =
        runCatching { src.search(track) }.onFailure { Log.w(TAG, "${src.name} 검색 실패", it) }.getOrDefault(emptyList())

    private fun load(src: LyricsSource, c: Candidate): Candidate? =
        runCatching { src.load(c) }.onFailure { Log.w(TAG, "${src.name} 가사 받기 실패", it) }.getOrNull()

    private fun save(track: Track, choice: Choice): Lyrics {
        runCatching {
            val o = toJson(choice.candidate).put("useSynced", choice.useSynced)
            cacheFile(track).writeText(o.toString())
        }.onFailure { Log.w(TAG, "가사 저장 실패", it) }
        return Lyrics(choice)
    }

    private fun loadCached(track: Track): Choice? {
        val f = cacheFile(track)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            Choice(fromJson(o, o.optString("source", LRCLIB)), o.optBoolean("useSynced", false))
        }.onFailure { Log.w(TAG, "저장된 가사 읽기 실패", it) }.getOrNull()
    }

    private fun cacheFile(track: Track): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(track.key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.json")
    }

    private fun lrclibQueries(track: Track, broad: Boolean): List<String> {
        val t = Http.enc(track.title)
        val a = Http.enc(track.primaryArtist)
        val out = mutableListOf<String>()
        if (track.artist.isNotBlank()) {
            out += "$BASE/get?track_name=$t&artist_name=$a" +
                (if (track.durationSec > 0) "&duration=${track.durationSec}" else "")
            out += "$BASE/search?track_name=$t&artist_name=$a"
        }
        out += "$BASE/search?q=${Http.enc("${track.title} ${track.primaryArtist}".trim())}"
        if (broad) out += "$BASE/search?track_name=$t"
        return out
    }

    private fun fetchLrclib(url: String): List<Candidate> {
        val body = Http.get(url, LRCLIB_UA) ?: return emptyList()
        return runCatching {
            if (body.trimStart().startsWith("[")) {
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> fromJson(o, LRCLIB) } }
            } else {
                listOf(fromJson(JSONObject(body), LRCLIB))
            }
        }.onFailure { Log.w(TAG, "응답 해석 실패: $url", it) }.getOrDefault(emptyList())
    }

    private fun fromJson(o: JSONObject, source: String) = Candidate(
        source = source,
        id = o.optString("id"),
        trackName = str(o, "trackName").orEmpty(),
        artistName = str(o, "artistName").orEmpty(),
        albumName = str(o, "albumName").orEmpty(),
        durationSec = o.optDouble("duration", -1.0),
        synced = str(o, "syncedLyrics"),
        plain = str(o, "plainLyrics"),
        instrumental = o.optBoolean("instrumental", false),
    )

    private fun toJson(c: Candidate) = JSONObject()
        .put("source", c.source)
        .put("id", c.id)
        .put("trackName", c.trackName)
        .put("artistName", c.artistName)
        .put("albumName", c.albumName)
        .put("duration", c.durationSec)
        .put("syncedLyrics", c.synced ?: JSONObject.NULL)
        .put("plainLyrics", c.plain ?: JSONObject.NULL)
        .put("instrumental", c.instrumental)

    // 안드로이드 JSONObject.optString 은 JSON null 을 "null" 문자열로 돌려주므로 직접 처리
    private fun str(o: JSONObject, k: String): String? =
        if (!o.has(k) || o.isNull(k)) null else o.getString(k).takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "LyricsFloat"
        private const val BASE = "https://lrclib.net/api"
        private const val LRCLIB = "LRCLIB"
        private const val LRCLIB_UA = "LyricsFloat/1.2 (personal app)"
    }
}
