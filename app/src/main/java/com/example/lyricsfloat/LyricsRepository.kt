package com.example.lyricsfloat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class Lyrics(val choice: Choice) {
    val lines: List<LrcLine> =
        if (choice.useSynced) choice.candidate.synced?.let { LrcParser.parse(it) }.orEmpty() else emptyList()
    val plain: String? = choice.candidate.plain
    val instrumental: Boolean = choice.candidate.instrumental
}

/** LRCLIB(lrclib.net) 공개 API에서 가사를 가져오고, 고른 결과를 기기에 저장해 둔다. API 키 불필요. */
class LyricsRepository(context: Context) {
    private val cacheDir = File(context.filesDir, "lyrics").apply { mkdirs() }

    /** [isStale] 이 true 가 되면 남은 요청을 건너뛴다. 곡을 빠르게 넘길 때 지난 곡 요청이 새 곡을 막지 않게 하려는 것. */
    fun find(track: Track, isStale: () -> Boolean): Lyrics? {
        loadCached(track)?.let { return Lyrics(it) }

        val found = LinkedHashMap<Long, Candidate>()
        for (url in queries(track, broad = false)) {
            if (isStale()) return null
            fetchCandidates(url).forEach { found.putIfAbsent(it.id, it) }
            val choice = TrackMatcher.choose(track, found.values.toList())
            if (choice != null && choice.useSynced) return save(track, choice)
        }
        return TrackMatcher.choose(track, found.values.toList())?.let { save(track, it) }
    }

    /** 직접 고르기 화면용. 자동 선택보다 넓게 검색한다. */
    fun candidates(track: Track): List<Candidate> {
        val found = LinkedHashMap<Long, Candidate>()
        for (url in queries(track, broad = true)) fetchCandidates(url).forEach { found.putIfAbsent(it.id, it) }
        return TrackMatcher.rank(track, found.values.toList())
    }

    fun save(track: Track, choice: Choice): Lyrics {
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
            Choice(fromJson(o), o.optBoolean("useSynced", false))
        }.onFailure { Log.w(TAG, "저장된 가사 읽기 실패", it) }.getOrNull()
    }

    private fun cacheFile(track: Track): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(track.key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.json")
    }

    private fun queries(track: Track, broad: Boolean): List<String> {
        val t = enc(track.title)
        val a = enc(track.primaryArtist)
        val out = mutableListOf<String>()
        if (track.artist.isNotBlank()) {
            out += "$BASE/get?track_name=$t&artist_name=$a" +
                (if (track.durationSec > 0) "&duration=${track.durationSec}" else "")
            out += "$BASE/search?track_name=$t&artist_name=$a"
        }
        out += "$BASE/search?q=${enc("${track.title} ${track.primaryArtist}".trim())}"
        if (broad) out += "$BASE/search?track_name=$t"
        return out
    }

    private fun fetchCandidates(url: String): List<Candidate> {
        val body = httpGet(url) ?: return emptyList()
        return runCatching {
            if (body.trimStart().startsWith("[")) {
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
            } else {
                listOf(fromJson(JSONObject(body)))
            }
        }.onFailure { Log.w(TAG, "응답 해석 실패: $url", it) }.getOrDefault(emptyList())
    }

    private fun fromJson(o: JSONObject) = Candidate(
        id = o.optLong("id"),
        trackName = str(o, "trackName").orEmpty(),
        artistName = str(o, "artistName").orEmpty(),
        albumName = str(o, "albumName").orEmpty(),
        durationSec = o.optDouble("duration", -1.0),
        synced = str(o, "syncedLyrics"),
        plain = str(o, "plainLyrics"),
        instrumental = o.optBoolean("instrumental", false),
    )

    private fun toJson(c: Candidate) = JSONObject()
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "LyricsFloat/1.1 (personal app)")
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> {
                    Log.w(TAG, "HTTP ${c.responseCode}: $url")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "요청 실패: $url", e)
            null
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "LyricsFloat"
        private const val BASE = "https://lrclib.net/api"
    }
}
