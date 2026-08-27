package com.tupicgames.takt.core.io

import com.tupicgames.takt.core.model.Analysis

/**
 * Выгрузка разбора в текстовый вид.
 *
 * Настроить анализатор вслепую нельзя: синтетические треки показывают, что
 * алгоритм работает в принципе, но не как он ведёт себя на настоящей музыке.
 * Здесь разбор превращается в файл, который можно посмотреть глазами или
 * загрузить в таблицу.
 */
object AnalysisExport {

    /** Сводка по треку — первое, на что смотреть. */
    fun summary(analysis: Analysis, title: String): String = buildString {
        appendLine("Такт — разбор трека")
        appendLine("файл: $title")
        appendLine("длительность: %.1f с".format(analysis.durationSec))
        appendLine("темп: %.2f BPM".format(analysis.bpm))
        appendLine("первая доля: %.3f с".format(analysis.firstBeatSec))
        appendLine("уверенность: %.2f".format(analysis.confidence))
        appendLine("размер такта: ${if (analysis.meter > 0) "${analysis.meter}/4" else "не определён"}")
        appendLine("разброс темпа: %.1f BPM".format(analysis.tempoSpread))
        appendLine("ударов: ${analysis.onsets.size} (%.2f/сек)".format(
            if (analysis.durationSec > 0) analysis.onsets.size / analysis.durationSec else 0f))
        appendLine("долей: ${analysis.beats.size} (%.2f/сек)".format(
            if (analysis.durationSec > 0) analysis.beats.size / analysis.durationSec else 0f))

        if (analysis.beatInfo.isNotEmpty()) {
            val conf = analysis.beatInfo.map { it.confidence }
            appendLine("уверенность долей: средняя %.2f, минимум %.2f".format(
                conf.average(), conf.min()))
            val weak = conf.count { it < 0.3f }
            appendLine("слабых долей (уверенность ниже 0.3): $weak")
        }

        // Ровность интервалов показывает, плывёт ли темп.
        if (analysis.beats.size > 2) {
            val gaps = (1 until analysis.beats.size).map { analysis.beats[it] - analysis.beats[it - 1] }
            appendLine("интервал долей: минимум %.4f, медиана %.4f, максимум %.4f с".format(
                gaps.min(), gaps.sorted()[gaps.size / 2], gaps.max()))
        }
    }

    /** Доли построчно: время, уверенность, сила, такт, позиция в такте. */
    fun beatsCsv(analysis: Analysis): String = buildString {
        appendLine("index,timeSec,confidence,strength,barIndex,positionInBar,isDownbeat")
        analysis.beatInfo.forEachIndexed { i, b ->
            appendLine(
                "%d,%.4f,%.3f,%.3f,%d,%d,%d".format(
                    i, b.timeSec, b.confidence, b.strength,
                    b.barIndex, b.positionInBar, if (b.isDownbeat) 1 else 0
                )
            )
        }
    }

    /** Удары построчно: время, сила, полосы, длительность звучания. */
    fun onsetsCsv(analysis: Analysis): String = buildString {
        appendLine("index,timeSec,strength,low,mid,high,centroidHz,sustainSec")
        analysis.onsets.forEachIndexed { i, o ->
            appendLine(
                "%d,%.4f,%.3f,%.3f,%.3f,%.3f,%.1f,%.3f".format(
                    i, o.timeSec, o.strength, o.low, o.mid, o.high, o.centroid, o.sustainSec
                )
            )
        }
    }
}
