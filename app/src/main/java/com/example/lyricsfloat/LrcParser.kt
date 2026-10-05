package com.example.lyricsfloat

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    private val TS = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    fun parse(raw: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (line in raw.lines()) {
            val stamps = TS.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val text = line.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val f = m.groupValues[3]
                val frac = when (f.length) {
                    0 -> 0L
                    1 -> f.toLong() * 100
                    2 -> f.toLong() * 10
                    else -> f.toLong()
                }
                out.add(LrcLine(min * 60_000 + sec * 1_000 + frac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
