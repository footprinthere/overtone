package com.example.lyricsfloat

import android.text.Html
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LRCLIB 에 없는 곡을 찾는 국내 음원 사이트들. 공개 API 가 아니라 웹페이지와 내부 주소를 읽는 방식이라
 * 사이트가 바뀌면 깨질 수 있다. 실패하면 빈 결과를 돌려주고 다음 출처로 넘어간다.
 */
interface LyricsSource {
    val name: String

    /** 가사 없이 제목·가수만 담긴 검색 결과. */
    fun search(track: Track): List<Candidate>

    /** 고른 결과의 가사를 받아 채운다. 못 받으면 null. */
    fun load(c: Candidate): Candidate?
}

object Bugs : LyricsSource {
    override val name = "벅스"
    private val ROW = Regex("<tr [^>]*trackId=\"(\\d+)\"[^>]*rowType=\"track\"[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<p class=\"title\"[^>]*>\\s*<a[^>]*title=\"([^\"]*)\"")
    private val ARTIST = Regex("<p class=\"artist\"[^>]*>\\s*<a[^>]*title=\"([^\"]*)\"")
    private val ALBUM = Regex("class=\"album\" title=\"([^\"]*)\"")

    override fun search(track: Track): List<Candidate> {
        val body = Http.get("https://music.bugs.co.kr/search/track?q=${Http.enc(query(track))}") ?: return emptyList()
        return ROW.findAll(body).mapNotNull { m ->
            val row = m.groupValues[2]
            val title = TITLE.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            meta(name, m.groupValues[1], unescape(title), unescape(ARTIST.find(row)?.groupValues?.get(1).orEmpty()),
                unescape(ALBUM.find(row)?.groupValues?.get(1).orEmpty()))
        }.toList()
    }

    override fun load(c: Candidate): Candidate? {
        val synced = Http.get("https://music.bugs.co.kr/player/lyrics/T/${c.id}")
            ?.let { runCatching { JSONObject(it).optString("lyrics") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { toLrc(parseSynced(it)) }
            ?.takeIf { it.isNotEmpty() }
        if (synced != null) return c.copy(synced = synced)
        val plain = Http.get("https://music.bugs.co.kr/player/lyrics/N/${c.id}")
            ?.let { runCatching { JSONObject(it).optString("lyrics") }.getOrNull() }
            ?.replace("\r\n", "\n")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return c.copy(plain = plain)
    }

    /** "12.8|첫 줄＃19.6|둘째 줄" 형식(초|가사, 전각 ＃ 로 구분). */
    fun parseSynced(s: String): List<Pair<Long, String>> = s.split("＃").mapNotNull { part ->
        val bar = part.indexOf('|')
        if (bar < 0) return@mapNotNull null
        val sec = part.substring(0, bar).trim().toDoubleOrNull() ?: return@mapNotNull null
        (sec * 1000).toLong() to part.substring(bar + 1).trim()
    }
}

object Genie : LyricsSource {
    override val name = "지니"
    private val ROW = Regex("<tr class=\"list\" songid=\"(\\d+)\">(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("class=\"title ellipsis\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val ARTIST = Regex("class=\"artist ellipsis\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val ALBUM = Regex("class=\"albumtitle ellipsis\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    // 타이틀곡·인기곡에 붙는 "TITLE", "HOT" 표시
    private val BADGE = Regex("<span class=\"icon[^\"]*\">[^<]*</span>")
    private val PLAIN = Regex("<pre id=\"pLyrics\"[^>]*>.*?<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

    override fun search(track: Track): List<Candidate> {
        val body = Http.get("https://www.genie.co.kr/search/searchSong?query=${Http.enc(query(track))}")
            ?: return emptyList()
        return ROW.findAll(body).mapNotNull { m ->
            val row = m.groupValues[2]
            val title = TITLE.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            meta(name, m.groupValues[1], htmlText(title.replace(BADGE, "")), htmlText(ARTIST.find(row)?.groupValues?.get(1).orEmpty()),
                htmlText(ALBUM.find(row)?.groupValues?.get(1).orEmpty()))
        }.toList()
    }

    override fun load(c: Candidate): Candidate? {
        // 싱크 가사: null({"13200":"첫 줄", ...}) 처럼 밀리초를 키로 한 JSON 이 함수 호출로 감싸져 온다.
        val synced = Http.get("https://dn.genie.co.kr/app/purchase/get_msl.asp?path=a&songid=${c.id}")
            ?.let { body ->
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start < 0 || end <= start) return@let null
                runCatching {
                    val o = JSONObject(body.substring(start, end + 1))
                    o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to o.optString(k) } }.toList()
                }.getOrNull()
            }
            ?.let { toLrc(it) }
            ?.takeIf { it.isNotEmpty() }
        if (synced != null) return c.copy(synced = synced)
        val page = Http.get("https://www.genie.co.kr/detail/songInfo?xgnm=${c.id}") ?: return null
        val plain = PLAIN.find(page)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return c.copy(plain = plain)
    }
}

object Melon : LyricsSource {
    override val name = "멜론"
    private val LYRIC = Regex("<div class=\"lyric\" id=\"d_video_summary\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

    // 자동완성 검색이라 결과가 들쑥날쑥하다. "가수 제목" 으로 못 찾으면 제목만으로 다시 찾는다.
    override fun search(track: Track): List<Candidate> =
        autocomplete(query(track)).ifEmpty { autocomplete(track.title) }

    private fun autocomplete(q: String): List<Candidate> {
        // 응답은 ({...}); 로 감싸져 온다.
        val body = Http.get("https://www.melon.com/search/keyword/index.json?jscallback=&query=${Http.enc(q)}")
            ?: return emptyList()
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val songs = runCatching { JSONObject(body.substring(start, end + 1)).optJSONArray("SONGCONTENTS") }
            .getOrNull() ?: return emptyList()
        return (0 until songs.length()).mapNotNull { i ->
            val o = songs.optJSONObject(i) ?: return@mapNotNull null
            meta(name, o.optString("SONGID"), o.optString("SONGNAME"), o.optString("ARTISTNAME"), o.optString("ALBUMNAME"))
        }
    }

    override fun load(c: Candidate): Candidate? {
        val page = Http.get("https://www.melon.com/song/detail.htm?songId=${c.id}") ?: return null
        val plain = LYRIC.find(page)?.groupValues?.get(1)
            ?.let(::htmlText)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return c.copy(plain = plain)
    }
}

private fun query(track: Track) = "${track.primaryArtist} ${track.title}".trim()

private fun meta(source: String, id: String, title: String, artist: String, album: String) =
    Candidate(source, id, title, artist, album, -1.0, null, null, false)

private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

/** 주석과 태그를 지우고 <br> 은 줄바꿈으로, &amp; 같은 표기는 원래 글자로 바꾼다. */
private fun htmlText(s: String): String =
    Html.fromHtml(s.replace(HTML_COMMENT, ""), Html.FROM_HTML_MODE_LEGACY).toString().trim()

/** 줄바꿈은 그대로 두고 &amp; 같은 표기만 원래 글자로 바꾼다. */
private fun unescape(s: String): String =
    htmlText(s.replace("\n", "<br>"))

/** (밀리초, 가사) 목록을 LRC 문자열로 바꿔 LRCLIB 결과와 같은 방식으로 다루게 한다. */
fun toLrc(lines: List<Pair<Long, String>>): String = lines.sortedBy { it.first }.joinToString("\n") { (ms, text) ->
    "[%02d:%02d.%02d]%s".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10, text)
}

object Http {
    private const val TAG = "LyricsFloat"

    // 휴대폰 브라우저로 보이면 모바일 페이지로 넘겨 버리는 사이트가 있어 PC 브라우저처럼 요청한다.
    const val BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, userAgent: String = BROWSER_UA): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", userAgent)
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
}
