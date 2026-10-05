package dev.footprinthere.overtone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {

    private fun cand(
        id: Long, track: String, artist: String, dur: Double,
        synced: Boolean = true, plain: Boolean = true,
    ) = Candidate("LRCLIB", id.toString(), track, artist, "", dur, if (synced) "[00:01.00]a" else null, if (plain) "a" else null, false)

    private fun meta(title: String, artist: String) = Candidate("벅스", "1", title, artist, "", -1.0, null, null, false)

    @Test
    fun `영상 제목의 꼬리표와 피처링을 뗀다`() {
        assertEquals("Love wins all", Track.from("Love wins all (Official Music Video)", "IU", "", 0).title)
        assertEquals("Hype Boy", Track.from("[MV] Hype Boy", "NewJeans", "", 0).title)
        assertEquals("Song", Track.from("Song (feat. Someone)", "A", "", 0).title)
        assertEquals("Song", Track.from("Song ft. Someone", "A", "", 0).title)
        assertEquals("Through the Night (밤편지)", Track.from("Through the Night (밤편지)", "IU", "", 0).title)
    }

    @Test
    fun `채널명 꼬리와 앞에 붙은 가수명을 뗀다`() {
        val t = Track.from("NewJeans - Ditto", "NewJeans - Topic", "", 185_000)
        assertEquals("NewJeans", t.artist)
        assertEquals("Ditto", t.title)
        assertEquals(185, t.durationSec)
        assertEquals("Love wins all", Track.from("[MV] IU(아이유) _ Love wins all", "IU", "", 0).title)
    }

    @Test
    fun `대표 가수만 뽑는다`() {
        assertEquals("A", Track("t", "A & B", "", 0).primaryArtist)
        assertEquals("A", Track("t", "A, B", "", 0).primaryArtist)
        assertEquals("A", Track("t", "A feat. B", "", 0).primaryArtist)
    }

    @Test
    fun `괄호 속 다른 언어 제목으로도 맞춘다`() {
        assertTrue(TrackMatcher.titleMatches("밤편지", "Through the Night (밤편지)"))
        assertTrue(TrackMatcher.titleMatches("Hype Boy", "hype boy"))
        assertTrue(TrackMatcher.artistMatches("아이유", "IU (아이유)"))
        assertFalse(TrackMatcher.titleMatches("Ditto", "Hype Boy"))
    }

    @Test
    fun `길이가 맞는 싱크 가사를 고른다`() {
        val track = Track("밤편지", "IU", "", 253)
        val choice = TrackMatcher.choose(
            track,
            listOf(
                cand(1, "Through the Night (밤편지)", "IU", 220.0),
                cand(2, "Through the Night (밤편지)", "IU", 252.0),
            ),
        )
        assertEquals("2", choice!!.candidate.id)
        assertTrue(choice.useSynced)
    }

    @Test
    fun `길이가 다르면 싱크 없이 일반 가사만 쓴다`() {
        val choice = TrackMatcher.choose(Track("Ditto", "NewJeans", "", 186), listOf(cand(1, "Ditto", "NewJeans", 221.0)))
        assertNotNull(choice)
        assertFalse(choice!!.useSynced)
    }

    @Test
    fun `제목만 같은 다른 노래는 고르지 않는다`() {
        assertNull(TrackMatcher.choose(Track("Ditto", "NewJeans", "", 186), listOf(cand(1, "Ditto", "Someone Else", 240.0))))
    }

    @Test
    fun `가수 표기가 달라도 제목과 길이가 맞으면 싱크 가사를 쓴다`() {
        val choice = TrackMatcher.choose(Track("밤편지", "아이유", "", 253), listOf(cand(1, "Through the Night (밤편지)", "IU", 253.0)))
        assertTrue(choice!!.useSynced)
    }

    @Test
    fun `유튜브 뮤직이 길이를 안 주면 제목만 같은 곡의 싱크 가사를 쓰지 않는다`() {
        assertNull(TrackMatcher.choose(Track("Ditto", "NewJeans", "", 0), listOf(cand(1, "Ditto", "Someone Else", 240.0))))
    }

    @Test
    fun `가사 없는 검색 결과는 제목과 가수가 모두 맞아야 고른다`() {
        val track = Track("TELL ME", "찰리빈웍스", "", 200)
        assertEquals("찰리빈웍스", TrackMatcher.matchWithoutLyrics(track, listOf(meta("Tell Me Tell Me", "레인보우"), meta("TELL ME", "찰리빈웍스")))!!.artistName)
        assertNull(TrackMatcher.matchWithoutLyrics(track, listOf(meta("TELL ME", "원더걸스"))))
        assertNotNull(TrackMatcher.matchWithoutLyrics(Track("Love wins all", "아이유", "", 271), listOf(meta("Love wins all", "아이유(IU)"))))
    }

    @Test
    fun `다른 버전보다 제목이 똑같은 원곡을 고른다`() {
        val track = Track("그때도 나", "찰리빈웍스", "", 272)
        val live = meta("그때도 나 (live)", "찰리빈웍스")
        val original = meta("그때도 나", "찰리빈웍스")
        assertEquals("그때도 나", TrackMatcher.matchWithoutLyrics(track, listOf(live, original))!!.trackName)
        assertNull(TrackMatcher.matchWithoutLyrics(track, listOf(live)))
        assertNotNull(TrackMatcher.matchWithoutLyrics(Track("그때도 나 (Live)", "찰리빈웍스", "", 0), listOf(live)))
        assertNotNull(TrackMatcher.matchWithoutLyrics(Track("밤편지", "IU", "", 0), listOf(meta("Through the Night (밤편지)", "IU"))))
    }

    @Test
    fun `벅스 싱크 가사를 LRC 로 바꿔 읽는다`() {
        val parsed = Bugs.parseSynced("12.8|걸음마를 떼고＃19.6|너를 향한＃잘못된 조각")
        assertEquals(listOf(12_800L to "걸음마를 떼고", 19_600L to "너를 향한"), parsed)
        val lines = LrcParser.parse(toLrc(parsed))
        assertEquals(listOf(12_800L, 19_600L), lines.map { it.timeMs })
        assertEquals("너를 향한", lines[1].text)
    }

    @Test
    fun `같은 곡이면 길이가 달라도 키가 같다`() {
        assertEquals(Track("Hype Boy", "NewJeans", "", 0).key, Track("hype boy", "NewJeans & X", "", 180).key)
    }

    @Test
    fun `LRC 시간표를 읽는다`() {
        val lines = LrcParser.parse("[00:12.34]first\n[01:02.5][01:10.000]second\nno stamp")
        assertEquals(listOf(12_340L, 62_500L, 70_000L), lines.map { it.timeMs })
        assertEquals("second", lines[2].text)
    }
}
