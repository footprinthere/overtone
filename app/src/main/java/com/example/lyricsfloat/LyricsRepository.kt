package com.example.lyricsfloat

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LyricsResult(
    val synced: List<LrcLine>,
    val plain: String?,
    val instrumental: Boolean
)

/** LRCLIB(lrclib.net) 공개 API에서 가사를 가져옵니다. API 키 불필요. */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"

    fun fetch(title: String, artist: String, album: String, durationSec: Int): LyricsResult? {
        val primaryArtist = artist.split(",", "&", "•", " feat.", " ft.").first().trim()

        // 1) 정확 매칭
        val q = StringBuilder("$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (album.isNotBlank()) q.append("&album_name=${enc(album)}")
        if (durationSec > 0) q.append("&duration=$durationSec")
        httpGet(q.toString())
            ?.let { runCatching { toResult(JSONObject(it)) }.getOrNull() }
            ?.let { return it }

        // 2) 검색 폴백
        val searches = listOf(
            "$BASE/search?track_name=${enc(title)}&artist_name=${enc(primaryArtist)}",
            "$BASE/search?q=${enc("$title $primaryArtist")}",
            "$BASE/search?q=${enc(title)}"
        )
        for (url in searches) {
            val body = httpGet(url) ?: continue
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
            pick(arr, durationSec)?.let { return it }
        }
        return null
    }

    private fun pick(arr: JSONArray, durationSec: Int): LyricsResult? {
        val cands = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { cands.add(it) }

        // 길이가 비슷한 버전만 싱크 가사로 신뢰
        val matched = cands.filter {
            durationSec <= 0 || Math.abs(it.optDouble("duration", -100.0) - durationSec) <= 3.0
        }
        for (c in matched) toResult(c)?.takeIf { it.synced.isNotEmpty() }?.let { return it }
        for (c in matched) toResult(c)?.let { return it }
        // 길이가 안 맞으면 싱크 없이 일반 가사만
        for (c in cands) {
            val p = str(c, "plainLyrics")
            if (p != null) return LyricsResult(emptyList(), p, false)
        }
        return null
    }

    private fun toResult(o: JSONObject): LyricsResult? {
        val synced = str(o, "syncedLyrics")?.let { LrcParser.parse(it) } ?: emptyList()
        val plain = str(o, "plainLyrics")
        val instrumental = o.optBoolean("instrumental", false)
        if (synced.isEmpty() && plain == null && !instrumental) return null
        return LyricsResult(synced, plain, instrumental)
    }

    // 안드로이드 JSONObject.optString 은 JSON null 을 "null" 문자열로 돌려주므로 직접 처리
    private fun str(o: JSONObject, k: String): String? =
        if (o.isNull(k)) null else o.getString(k).takeIf { it.isNotBlank() }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "LyricsFloat/1.0 (personal app)")
            if (c.responseCode != 200) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
