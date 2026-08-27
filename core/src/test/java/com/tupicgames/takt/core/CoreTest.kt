package com.tupicgames.takt.core

import com.tupicgames.takt.core.generator.BeatmapGenerator
import com.tupicgames.takt.core.generator.HopRoad
import com.tupicgames.takt.core.generator.HopRoadGenerator
import com.tupicgames.takt.core.generator.HopTile
import com.tupicgames.takt.core.generator.RhythmEvent
import com.tupicgames.takt.core.generator.RhythmEventExtractor
import com.tupicgames.takt.core.io.AnalysisIo
import com.tupicgames.takt.core.model.*
import com.tupicgames.takt.core.play.GameEngine
import com.tupicgames.takt.core.play.GameListener
import com.tupicgames.takt.core.play.HopEngine
import com.tupicgames.takt.core.play.Result
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random

class GameEngineTest {

    private fun beatmap(times: List<Float>, lanes: Int = 4) = Beatmap(
        notes = times.mapIndexed { i, t -> Note(t, i % lanes) },
        bpm = 120f, durationSec = times.maxOrNull()?.plus(1f) ?: 1f,
        laneCount = lanes, difficulty = Difficulty.NORMAL, quantized = true
    )

    @Test
    fun `точное попадание даёт PERFECT`() {
        val e = GameEngine(beatmap(listOf(1.0f)))
        assertEquals(Judgement.PERFECT, e.onTap(0, 1.0))
        assertEquals(1, e.perfectCount)
        assertEquals(1, e.combo)
    }

    @Test
    fun `на границе окна PERFECT ещё PERFECT, за границей уже GOOD`() {
        val a = GameEngine(beatmap(listOf(1.0f)))
        assertEquals(Judgement.PERFECT, a.onTap(0, 1.0 + Windows.PERFECT_SEC - 0.001))

        val b = GameEngine(beatmap(listOf(1.0f)))
        assertEquals(Judgement.GOOD, b.onTap(0, 1.0 + Windows.PERFECT_SEC + 0.001))
    }

    @Test
    fun `касание вне окна GOOD не съедает ноту`() {
        val e = GameEngine(beatmap(listOf(1.0f)))
        assertNull(e.onTap(0, 1.0 + Windows.GOOD_SEC + 0.01))
        // Нота осталась на дорожке и всё ещё может быть взята.
        assertEquals(Judgement.PERFECT, e.onTap(0, 1.0))
    }

    @Test
    fun `касание не по своей дорожке игнорируется`() {
        val e = GameEngine(beatmap(listOf(1.0f)))
        assertNull(e.onTap(1, 1.0))
        assertEquals(0, e.combo)
    }

    @Test
    fun `нота за окном промаха сбрасывает комбо`() {
        val e = GameEngine(beatmap(listOf(1.0f, 2.0f, 3.0f, 4.0f)))
        e.onTap(0, 1.0)
        assertEquals(1, e.combo)
        e.advanceTo(2.0 + Windows.MISS_SEC + 0.01)
        assertEquals(1, e.missCount)
        assertEquals(0, e.combo)
        assertEquals(1, e.maxCombo)
    }

    @Test
    fun `advanceTo не выносит оценку дважды`() {
        val e = GameEngine(beatmap(listOf(1.0f)))
        e.advanceTo(5.0)
        e.advanceTo(6.0)
        assertEquals(1, e.missCount)
    }

    @Test
    fun `onFinished вызывается ровно один раз`() {
        var calls = 0
        val e = GameEngine(beatmap(listOf(1.0f, 2.0f)))
        e.listener = object : GameListener {
            override fun onJudged(judgement: Judgement, lane: Int, songTimeSec: Double) {}
            override fun onHoldCompleted(lane: Int, songTimeSec: Double) {}
            override fun onFinished(result: Result) { calls++ }
        }
        e.advanceTo(10.0)
        e.advanceTo(11.0)
        e.endEarly()
        assertEquals(1, calls)
    }

    @Test
    fun `точность считается по всем вынесенным оценкам`() {
        val e = GameEngine(beatmap(listOf(1.0f, 2.0f, 3.0f, 4.0f)))
        e.onTap(0, 1.0)                                  // perfect
        e.onTap(1, 2.0 + Windows.PERFECT_SEC + 0.01)     // good
        e.advanceTo(10.0)                                // два промаха
        // (1 + 0.5) / 4
        assertEquals(0.375f, e.accuracy, 0.0001f)
    }

    @Test
    fun `множитель комбо не растёт бесконечно`() {
        val times = (0 until 400).map { 1f + it * 0.2f }
        val e = GameEngine(beatmap(times))
        times.forEachIndexed { i, t -> e.onTap(i % 4, t.toDouble()) }
        // Потолок множителя 2.0 -> максимум 600 очков за ноту.
        assertTrue(e.score <= 600L * times.size)
        assertEquals(times.size, e.perfectCount)
    }

    @Test
    fun `после завершения касания не считаются`() {
        val e = GameEngine(beatmap(listOf(1.0f)))
        e.advanceTo(10.0)
        val before = e.score
        assertNull(e.onTap(0, 10.0))
        assertEquals(before, e.score)
    }
}

class HopRoadTest {

    /** Синтетический анализ с ровными долями и ударами на них. */
    private fun analysis(
        bpm: Float = 120f,
        durationSec: Float = 120f,
        loudFrom: Float = -1f
    ): Analysis {
        val step = 60f / bpm
        val beats = ArrayList<Float>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        var i = 0
        while (t < durationSec - 1f) {
            beats.add(t)
            // Центроид гуляет — музыкальная линия должна за ним следовать.
            val centroid = 400f + 3000f * (0.5f + 0.5f * kotlin.math.sin(i * 0.15).toFloat())
            onsets.add(Onset(t, 1f, 0.4f, 0.3f, 0.3f, centroid, 0.05f))
            t += step; i++
        }
        // Огибающая: вторая половина громче — там дорога должна вилять шире.
        val hz = 30f
        val points = (durationSec * hz).toInt()
        val env = FloatArray(points * 3)
        for (p in 0 until points) {
            val sec = p / hz
            val loud = loudFrom >= 0f && sec >= loudFrom
            val v = if (loud) 1f else 0.25f
            env[p * 3] = v; env[p * 3 + 1] = v; env[p * 3 + 2] = v
        }
        // beatInfo обязателен: настоящий анализатор всегда его отдаёт,
        // и генератор строит подразделения именно по нему.
        val info = beats.mapIndexed { k, t -> BeatInfo(t, 0.9f, 0.7f, k / 4, k % 4) }
        return Analysis(
            bpm, 1f, durationSec, 0.95f, onsets, beats.toFloatArray(), env, hz,
            beatInfo = info, meter = 4
        )
    }

    @Test
    fun `плитки стоят на долях или ровно посередине между ними`() {
        val a = analysis(bpm = 120f)
        val road = HopRoadGenerator.generate(a)
        assertTrue(road.tiles.isNotEmpty())
        val beats = a.beats
        val step = 60f / 120f
        assertTrue(road.tiles.all { tile ->
            beats.any { abs(tile.timeSec - it) < 0.005f || abs(tile.timeSec - (it + step / 2f)) < 0.005f }
        })
    }

    @Test
    fun `на медленном треке пульс не проваливается ниже играбельного`() {
        // На 60 BPM доля идёт раз в секунду — играть нечем. Даже если музыка
        // не даёт повода дробить, играбельность важнее точности.
        val road = HopRoadGenerator.generate(analysis(bpm = 60f))
        assertTrue("прыжков в секунду: ${road.hopsPerSecond}", road.hopsPerSecond >= 1.4f)
        assertTrue(road.hopsPerBeat >= 1.8f)
    }

    @Test
    fun `интервал между прыжками постоянный`() {
        val road = HopRoadGenerator.generate(analysis(bpm = 120f))
        val gaps = (1 until road.tiles.size).map {
            road.tiles[it].timeSec - road.tiles[it - 1].timeSec
        }
        val first = gaps.first()
        assertTrue("разброс интервалов", gaps.all { abs(it - first) < 0.005f })
    }

    @Test
    fun `частота прыжков остаётся играбельной на любом темпе`() {
        for (bpm in listOf(60f, 90f, 120f, 150f, 180f, 200f)) {
            val road = HopRoadGenerator.generate(analysis(bpm = bpm))
            assertTrue(
                "$bpm BPM дал ${road.hopsPerSecond} прыжков/сек",
                road.hopsPerSecond in 1.2f..4.6f
            )
        }
    }

    @Test
    fun `дорога не сдвигается быстрее, чем игрок успевает`() {
        for (bpm in listOf(60f, 120f, 180f)) {
            val road = HopRoadGenerator.generate(analysis(bpm = bpm))
            assertTrue(
                "$bpm BPM: максимальный сдвиг ${HopRoadGenerator.maxShift(road)}",
                HopRoadGenerator.maxShift(road) <= 0.56f
            )
        }
    }

    @Test
    fun `дорога не выходит за края`() {
        val road = HopRoadGenerator.generate(analysis())
        assertTrue(road.tiles.all { it.x in -1f..1f })
    }

    @Test
    fun `на громком участке дорога виляет шире`() {
        val road = HopRoadGenerator.generate(analysis(durationSec = 120f, loudFrom = 60f))
        val quiet = road.tiles.filter { it.timeSec < 55f }.map { abs(it.x) }
        val loud = road.tiles.filter { it.timeSec > 65f }.map { abs(it.x) }
        assertTrue(quiet.isNotEmpty() && loud.isNotEmpty())
        assertTrue(
            "тихо=${quiet.average()} громко=${loud.average()}",
            loud.average() > quiet.average()
        )
    }

    @Test
    fun `без найденных долей дорога пустая, но не падает`() {
        val a = Analysis(0f, 0f, 10f, 0f, emptyList())
        val road = HopRoadGenerator.generate(a)
        assertTrue(road.tiles.isEmpty())
    }
}

class RhythmEventTest {

    /**
     * Синтетика: доли всегда с ударом, серединки — по флагу.
     */
    private fun make(withOffbeats: Boolean, count: Int = 60): Analysis {
        val step = 0.5f
        val beats = ArrayList<Float>()
        val info = ArrayList<BeatInfo>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        for (i in 0 until count) {
            beats.add(t)
            info.add(BeatInfo(t, 0.95f, 0.8f, i / 4, i % 4))
            onsets.add(Onset(t, 1.0f, 0.6f, 0.2f, 0.2f, 900f, 0.05f))
            if (withOffbeats) {
                onsets.add(Onset(t + step / 2f, 0.6f, 0.1f, 0.3f, 0.6f, 6000f, 0.02f))
            }
            t += step
        }
        return Analysis(
            120f, 1f, t + 1f, 0.95f, onsets.sortedBy { it.timeSec },
            beats.toFloatArray(), beatInfo = info, meter = 4
        )
    }

    @Test
    fun `на каждой доле есть событие`() {
        val events = RhythmEventExtractor.extract(make(withOffbeats = false))
        assertTrue(events.count { it.onBeat } >= 58)
    }

    @Test
    fun `при пустых серединках внесеточных событий нет`() {
        val events = RhythmEventExtractor.extract(make(withOffbeats = false))
        assertEquals(0, events.count { !it.onBeat })
    }

    @Test
    fun `при ударах на серединках события появляются`() {
        val events = RhythmEventExtractor.extract(make(withOffbeats = true))
        assertTrue(events.count { !it.onBeat } >= 50)
    }

    @Test
    fun `плотный участок дробится, редкий нет`() {
        // Первая половина без серединок, вторая — с ними.
        val step = 0.5f
        val beats = ArrayList<Float>()
        val info = ArrayList<BeatInfo>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        for (i in 0 until 120) {
            beats.add(t)
            info.add(BeatInfo(t, 0.95f, 0.8f, i / 4, i % 4))
            onsets.add(Onset(t, 1.0f, 0.6f, 0.2f, 0.2f, 900f, 0.05f))
            if (t > 31f) onsets.add(Onset(t + step / 2f, 0.9f, 0.3f, 0.3f, 0.4f, 3000f, 0.03f))
            t += step
        }
        val a = Analysis(
            120f, 1f, t + 1f, 0.95f, onsets.sortedBy { it.timeSec },
            beats.toFloatArray(), beatInfo = info, meter = 4
        )
        val road = HopRoadGenerator.generate(a)
        val sparse = road.tiles.count { it.timeSec < 25f }
        val dense = road.tiles.count { it.timeSec > 40f }
        assertTrue("редкий $sparse, плотный $dense", dense > sparse * 1.5)
    }

    @Test
    fun `интервал прыжка внутри участка постоянный`() {
        val road = HopRoadGenerator.generate(make(withOffbeats = true))
        val gaps = (1 until road.tiles.size).map {
            road.tiles[it].timeSec - road.tiles[it - 1].timeSec
        }
        val median = gaps.sorted()[gaps.size / 2]
        // Допускаем переходы между участками, но не разнобой на каждом шаге.
        val odd = gaps.count { abs(it - median) > median * 0.3f }
        assertTrue("разнобойных интервалов $odd из ${gaps.size}", odd < gaps.size / 5)
    }

    @Test
    fun `дорога не дробится, если серединки пустые`() {
        // Ключевая проверка: раньше серединка ставилась всегда, и на такой
        // музыке половина плиток висела в тишине.
        val road = HopRoadGenerator.generate(make(withOffbeats = false))
        val onsetTimes = make(withOffbeats = false).onsets.map { it.timeSec }
        assertTrue(road.tiles.all { tile ->
            onsetTimes.any { abs(it - tile.timeSec) < 0.03f }
        })
    }

    @Test
    fun `события отсортированы по времени`() {
        val events = RhythmEventExtractor.extract(make(withOffbeats = true))
        for (i in 0 until events.size - 1) {
            assertTrue(events[i].timeSec <= events[i + 1].timeSec)
        }
    }

    @Test
    fun `без долей событий не будет и падения тоже`() {
        val empty = Analysis(0f, 0f, 10f, 0f, emptyList())
        assertTrue(RhythmEventExtractor.extract(empty).isEmpty())
    }

    @Test
    fun `событие на доле весит больше такого же на подразделении`() {
        val onBeat = RhythmEvent(1f, 1f, 1f, 0f, 0, onBeat = true)
        val off = RhythmEvent(1.25f, 1f, 1f, 0.5f, -1, onBeat = false)
        assertTrue(onBeat.weight > off.weight)
    }
}

class HopEngineTest {

    private fun road(vararg xs: Float) = HopRoad(
        tiles = xs.mapIndexed { i, x -> HopTile(1f + i * 0.5f, x) },
        durationSec = 20f, hopsPerBeat = 1f
    )

    @Test
    fun `шарик стартует по центру`() {
        assertEquals(0f, HopEngine(road(0f, 0.5f)).ballX, 0.001f)
    }

    @Test
    fun `приземление на плитку засчитывается`() {
        val e = HopEngine(road(0.5f))
        e.moveTo(0.5f)
        e.advanceTo(1.05)
        assertEquals(1, e.landed)
        assertFalse(e.fell)
        assertEquals(1, e.combo)
    }

    @Test
    fun `падение заканчивает забег сразу`() {
        val e = HopEngine(road(0f, 1f, 0f, 0f))
        e.moveTo(0f)
        e.advanceTo(1.05)          // попал на первую
        assertEquals(1, e.landed)
        e.advanceTo(3.0)           // вторая далеко — падение
        assertTrue(e.fell)
        assertTrue(e.isFinished())
        // Остальные плитки не обрабатываются.
        assertEquals(1, e.landed)
    }

    @Test
    fun `после падения шарик не двигается`() {
        val e = HopEngine(road(1f))
        e.advanceTo(1.05)
        assertTrue(e.fell)
        e.moveTo(0.9f)
        assertEquals(0f, e.ballX, 0.001f)
    }

    @Test
    fun `шарик не уезжает за край дороги`() {
        val e = HopEngine(road(0f))
        e.moveTo(-5f); assertEquals(-1f, e.ballX, 0.001f)
        e.moveTo(5f); assertEquals(1f, e.ballX, 0.001f)
    }

    @Test
    fun `высота прыжка ноль при приземлении и максимум посередине`() {
        val e = HopEngine(road(0f, 0f))
        e.advanceTo(1.0)
        assertEquals(0f, e.hopHeight(1.0), 0.02f)
        assertEquals(1f, e.hopHeight(1.25), 0.02f)
        assertEquals(0f, e.hopHeight(1.5), 0.02f)
    }

    @Test
    fun `завершение вызывается один раз`() {
        var calls = 0
        val e = HopEngine(road(0f, 0f))
        e.listener = object : HopEngine.Listener {
            override fun onLanded(tile: HopTile, index: Int, songTimeSec: Double) {}
            override fun onFell(tile: HopTile, index: Int, songTimeSec: Double) {}
            override fun onFinished(result: Result) { calls++ }
        }
        e.advanceTo(50.0)
        e.advanceTo(60.0)
        e.endEarly()
        assertEquals(1, calls)
    }

    @Test
    fun `точность считается от всех плиток трассы`() {
        val e = HopEngine(road(0f, 0f, 0f, 1f))
        e.advanceTo(2.6)          // три пройдены, на четвёртой падение
        assertTrue(e.fell)
        assertEquals(0.75f, e.accuracy, 0.001f)
    }
}

class HoldNoteTest {

    private fun holdMap(holdSec: Float) = Beatmap(
        notes = listOf(Note(1.0f, 0, holdSec)),
        bpm = 120f, durationSec = 10f, laneCount = 4,
        difficulty = Difficulty.NORMAL, quantized = true
    )

    @Test
    fun `удержание доводится до конца само`() {
        val e = GameEngine(holdMap(1.0f))
        assertEquals(Judgement.PERFECT, e.onTap(0, 1.0))
        assertEquals(0, e.holdsCompleted)
        e.advanceTo(2.05)
        assertEquals(1, e.holdsCompleted)
        assertEquals(0, e.holdsBroken)
    }

    @Test
    fun `отпускание в пределах запаса засчитывается`() {
        val e = GameEngine(holdMap(1.0f))
        e.onTap(0, 1.0)
        e.onRelease(0, 2.0 - Windows.HOLD_RELEASE_GRACE_SEC + 0.01)
        assertEquals(1, e.holdsCompleted)
        assertEquals(0, e.holdsBroken)
    }

    @Test
    fun `раннее отпускание срывает удержание и комбо`() {
        val e = GameEngine(holdMap(1.0f))
        e.onTap(0, 1.0)
        assertEquals(1, e.combo)
        e.onRelease(0, 1.3)
        assertEquals(1, e.holdsBroken)
        assertEquals(0, e.holdsCompleted)
        assertEquals(0, e.combo)
    }

    @Test
    fun `отпускание без активного удержания ничего не ломает`() {
        val e = GameEngine(holdMap(1.0f))
        e.onRelease(0, 1.0)
        e.onRelease(9, 1.0)
        assertEquals(0, e.holdsBroken)
    }

    @Test
    fun `длинное удержание стоит дороже короткого`() {
        val short = GameEngine(holdMap(0.4f))
        short.onTap(0, 1.0); short.advanceTo(1.5)
        val long = GameEngine(holdMap(2.0f))
        long.onTap(0, 1.0); long.advanceTo(3.1)
        assertTrue("короткое=${short.score} длинное=${long.score}", long.score > short.score)
    }

    @Test
    fun `удержания можно отключить`() {
        val beats = ArrayList<Float>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        while (t < 30f) {
            beats.add(t)
            onsets.add(Onset(t, 1.0f, 0.4f, 0.3f, 0.3f, 1200f, 1.5f))
            t += 1.0f
        }
        val a = Analysis(60f, 1f, 30f, 0.95f, onsets, beats.toFloatArray())
        val off = BeatmapGenerator.generate(a, Difficulty.EASY, 4, 1f, holds = false)
        assertTrue(off.notes.none { it.isHold })
    }

    @Test
    fun `удержаний не больше пятой части нот`() {
        // Все звуки тянущиеся — в живой музыке так и бывает.
        val beats = ArrayList<Float>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        while (t < 60f) {
            beats.add(t)
            onsets.add(Onset(t, 1.0f, 0.4f, 0.3f, 0.3f, 1200f, 2.0f))
            t += 0.5f
        }
        val a = Analysis(120f, 1f, 60f, 0.95f, onsets, beats.toFloatArray())
        val notes = BeatmapGenerator.generate(a, Difficulty.NORMAL).notes
        val holds = notes.count { it.isHold }
        assertTrue("удержаний $holds из ${notes.size}", holds <= notes.size / 5)
        assertTrue("удержаний должно быть хоть сколько-то", holds > 0)
    }

    @Test
    fun `тянущийся звук становится удержанием, короткий — нет`() {
        val beats = ArrayList<Float>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        while (t < 30f) {
            beats.add(t)
            // Чередуем: длинный звук, потом короткий.
            val sustain = if (beats.size % 2 == 0) 1.2f else 0.02f
            onsets.add(Onset(t, 1.0f, 0.4f, 0.3f, 0.3f, 1200f, sustain))
            t += 1.0f
        }
        val a = Analysis(60f, 1f, 30f, 0.95f, onsets, beats.toFloatArray())
        val bm = BeatmapGenerator.generate(a, Difficulty.EASY)
        val holds = bm.notes.count { it.isHold }
        assertTrue("удержаний: $holds из ${bm.notes.size}", holds in 1 until bm.notes.size)
    }

    @Test
    fun `удержание не налезает на следующую ноту`() {
        val beats = ArrayList<Float>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        while (t < 30f) {
            beats.add(t)
            onsets.add(Onset(t, 1.0f, 0.4f, 0.3f, 0.3f, 1200f, 5.0f))
            t += 0.5f
        }
        val a = Analysis(120f, 1f, 30f, 0.95f, onsets, beats.toFloatArray())
        val notes = BeatmapGenerator.generate(a, Difficulty.HARD).notes
        for (i in 0 until notes.size - 1) {
            assertTrue(
                "нота ${notes[i].timeSec} кончается на ${notes[i].endSec}, следующая в ${notes[i + 1].timeSec}",
                notes[i].endSec <= notes[i + 1].timeSec + 1e-4f
            )
        }
    }
}

class BeatmapGeneratorTest {

    /** Доли ровно по темпу — базовый случай без дрейфа. */
    private fun beatsOf(bpm: Float, from: Float, until: Float): FloatArray {
        val step = 60f / bpm
        val list = ArrayList<Float>()
        var t = from
        while (t < until) { list.add(t); t += step }
        return list.toFloatArray()
    }

    /** Синтетический анализ: удары строго по шестнадцатым. */
    private fun gridAnalysis(
        bpm: Float = 120f,
        durationSec: Float = 60f,
        confidence: Float = 1f,
        seed: Int = 1
    ): Analysis {
        val rnd = Random(seed)
        val step = 60f / bpm / 4f
        val onsets = ArrayList<Onset>()
        var t = 0.5f
        while (t < durationSec - 0.5f) {
            val lowShare = rnd.nextFloat() * 0.8f
            onsets.add(
                Onset(
                    timeSec = t,
                    // Разброс силы небольшой: фильтр хвостов не должен
                    // принять соседнюю ноту за затухание предыдущей.
                    strength = 0.7f + rnd.nextFloat() * 0.5f,
                    low = lowShare,
                    mid = (1f - lowShare) * 0.5f,
                    high = (1f - lowShare) * 0.5f,
                    centroid = 200f + rnd.nextFloat() * 5000f
                )
            )
            t += step
        }
        return Analysis(
            bpm, 0.5f, durationSec, confidence, onsets,
            beatsOf(bpm, 0.5f, durationSec - 0.5f)
        )
    }

    @Test
    fun `плотность держится около целевой для каждой сложности`() {
        val a = gridAnalysis()
        for (d in Difficulty.values()) {
            val bm = BeatmapGenerator.generate(a, d)
            val ratio = bm.notesPerSecond / d.targetNps
            assertTrue(
                "${d.name}: NPS ${bm.notesPerSecond} против цели ${d.targetNps}",
                ratio in 0.7f..1.25f
            )
        }
    }

    @Test
    fun `минимальный интервал между нотами соблюдается`() {
        val a = gridAnalysis()
        for (d in Difficulty.values()) {
            val notes = BeatmapGenerator.generate(a, d).notes
            for (i in 0 until notes.size - 1) {
                val gap = notes[i + 1].timeSec - notes[i].timeSec
                assertTrue("${d.name}: разрыв $gap < ${d.minGapSec}", gap >= d.minGapSec - 1e-4f)
            }
        }
    }

    @Test
    fun `ноты идут по возрастанию времени`() {
        val notes = BeatmapGenerator.generate(gridAnalysis(), Difficulty.HARD).notes
        for (i in 0 until notes.size - 1) {
            assertTrue(notes[i].timeSec <= notes[i + 1].timeSec)
        }
    }

    @Test
    fun `номера дорожек всегда в диапазоне`() {
        for (lanes in 3..5) {
            val notes = BeatmapGenerator.generate(gridAnalysis(), Difficulty.EXPERT, lanes).notes
            assertTrue(notes.all { it.lane in 0 until lanes })
        }
    }

    @Test
    fun `дорожки заполняются примерно равномерно`() {
        val notes = BeatmapGenerator.generate(gridAnalysis(seed = 7), Difficulty.HARD).notes
        val counts = IntArray(4)
        notes.forEach { counts[it.lane]++ }
        val expected = notes.size / 4.0
        counts.forEachIndexed { i, c ->
            assertTrue("дорожка $i: $c против ожидаемых $expected",
                abs(c - expected) < expected * 0.45)
        }
    }

    @Test
    fun `ползунок плотности реально меняет число нот`() {
        val a = gridAnalysis()
        val sparse = BeatmapGenerator.generate(a, Difficulty.NORMAL, 4, 0.5f)
        val dense = BeatmapGenerator.generate(a, Difficulty.NORMAL, 4, 1.5f)
        assertTrue(dense.notes.size > sparse.notes.size * 1.5)
    }

    @Test
    fun `при низкой уверенности темпа сетка отключается`() {
        val bm = BeatmapGenerator.generate(gridAnalysis(confidence = 0.1f), Difficulty.NORMAL)
        assertFalse(bm.quantized)
        assertTrue(bm.notes.isNotEmpty())
    }

    @Test
    fun `без найденных долей сетка не строится`() {
        val a = gridAnalysis().copy(beats = FloatArray(0))
        val bm = BeatmapGenerator.generate(a, Difficulty.NORMAL)
        assertFalse(bm.quantized)
    }

    @Test
    fun `сетка следует за плывущим темпом`() {
        // Темп разгоняется со 120 до 130 — жёсткая сетка от среднего темпа
        // здесь разъезжается на сотни миллисекунд к концу.
        val beats = ArrayList<Float>()
        val onsets = ArrayList<Onset>()
        var t = 1f
        val dur = 180f
        while (t < dur - 1f) {
            beats.add(t)
            onsets.add(Onset(t, 1.0f, 0.5f, 0.3f, 0.2f, 1500f))
            val step = 60f / (120f + 10f * (t / dur))
            onsets.add(Onset(t + step / 2f, 0.8f, 0.2f, 0.4f, 0.4f, 3000f))
            t += step
        }
        val a = Analysis(124f, 1f, dur, 0.95f, onsets, beats.toFloatArray())
        val bm = BeatmapGenerator.generate(a, Difficulty.NORMAL)

        // Каждая нота должна стоять вплотную к реальному удару, включая конец.
        val times = onsets.map { it.timeSec }.sorted()
        val late = bm.notes.filter { it.timeSec > dur * 0.75f }
        assertTrue("нот в последней четверти: ${late.size}", late.size > 20)
        for (n in late) {
            val nearest = times.minOf { abs(it - n.timeSec) }
            assertTrue("нота на ${n.timeSec} отстоит на $nearest с", nearest < 0.02f)
        }
    }

    @Test
    fun `хвост затухания не становится нотой`() {
        val onsets = ArrayList<Onset>()
        val beats = ArrayList<Float>()
        var t = 1f
        while (t < 40f) {
            beats.add(t)
            onsets.add(Onset(t, 1.2f, 0.6f, 0.2f, 0.2f, 800f))          // удар
            onsets.add(Onset(t + 0.06f, 0.25f, 0.6f, 0.2f, 0.2f, 800f)) // его хвост
            t += 0.5f
        }
        val a = Analysis(120f, 1f, 40f, 0.95f, onsets, beats.toFloatArray())
        val bm = BeatmapGenerator.generate(a, Difficulty.EXPERT)
        // Ни одна нота не должна оказаться на позиции хвоста.
        assertTrue(bm.notes.none { n ->
            beats.any { b -> abs(n.timeSec - (b + 0.06f)) < 0.01f }
        })
    }

    @Test
    fun `онсеты мимо сетки отбрасываются`() {
        val step = 60f / 120f / 4f          // 0.125 с
        val tolerance = minOf(step / 2f, 0.055f)
        val onGrid = (0 until 40).map {
            Onset(0.5f + it * step, 1f, 0.2f, 0.4f, 0.4f, 2000f)
        }
        // Смещение заведомо за допуском привязки, но меньше половины шага,
        // иначе онсет просто уедет в соседний узел вместо отбрасывания.
        val shift = tolerance + 0.005f
        val offGrid = (0 until 40).map {
            Onset(0.5f + it * step + shift, 0.9f, 0.2f, 0.4f, 0.4f, 2000f)
        }
        val a = Analysis(
            120f, 0.5f, 30f, 1f, (onGrid + offGrid).sortedBy { it.timeSec },
            beatsOf(120f, 0.5f, 29.5f)
        )
        val bm = BeatmapGenerator.generate(a, Difficulty.EXPERT)

        assertTrue(bm.notes.all { n ->
            val k = Math.round((n.timeSec - 0.5f) / step)
            abs(n.timeSec - (0.5f + k * step)) < 1e-4f
        })
        // Смещённые не должны добавить нот сверх сеточных.
        assertTrue("нот ${bm.notes.size}, узлов ${onGrid.size}", bm.notes.size <= onGrid.size)
    }

    @Test
    fun `пустой анализ даёт пустую раскладку без падения`() {
        val bm = BeatmapGenerator.generate(
            Analysis(0f, 0f, 0f, 0f, emptyList()), Difficulty.NORMAL
        )
        assertTrue(bm.notes.isEmpty())
        assertEquals(0f, bm.notesPerSecond, 0f)
    }

    @Test
    fun `громкий участок получает больше нот, чем тихий`() {
        val step = 0.125f
        val onsets = ArrayList<Onset>()
        var t = 0.5f
        while (t < 40f) {
            val loud = t > 20f
            onsets.add(Onset(t, if (loud) 1.2f else 0.3f, 0.3f, 0.4f, 0.3f, 2000f))
            t += step
        }
        val bm = BeatmapGenerator.generate(
            Analysis(120f, 0.5f, 40f, 1f, onsets, beatsOf(120f, 0.5f, 39.5f)),
            Difficulty.NORMAL
        )
        val quiet = bm.notes.count { it.timeSec < 20f }
        val loud = bm.notes.count { it.timeSec >= 20f }
        assertTrue("тихо=$quiet громко=$loud", loud > quiet * 1.3)
    }
}

class BeatInfoTest {

    @Test
    fun `сильная доля такта распознаётся`() {
        val down = BeatInfo(1f, 0.9f, 0.8f, barIndex = 3, positionInBar = 0)
        val weak = BeatInfo(1.5f, 0.9f, 0.4f, barIndex = 3, positionInBar = 2)
        assertTrue(down.isDownbeat)
        assertFalse(weak.isDownbeat)
    }

    @Test
    fun `без определённого размера сильных долей нет`() {
        val b = BeatInfo(1f, 0.9f, 0.8f, barIndex = -1, positionInBar = 0)
        assertFalse(b.isDownbeat)
    }
}

class AnalysisIoTest {

    @Test
    fun `запись и чтение возвращают то же самое`() {
        val onsets = (0 until 500).map {
            Onset(it * 0.1f, 0.5f + it % 7 * 0.1f, 0.2f, 0.3f, 0.5f, 1000f + it)
        }
        val beatInfo = (0 until 100).map {
            BeatInfo(0.42f + it * 0.468f, 0.8f, 0.5f, it / 4, it % 4)
        }
        val beats = FloatArray(100) { beatInfo[it].timeSec }
        val a = Analysis(
            128.5f, 0.42f, 50f, 0.93f, onsets, beats,
            beatInfo = beatInfo, meter = 4, tempoSpread = 1.2f
        )

        val buf = ByteArrayOutputStream()
        AnalysisIo.write(buf, a)
        val back = AnalysisIo.read(ByteArrayInputStream(buf.toByteArray()))

        assertNotNull(back)
        assertEquals(a.bpm, back!!.bpm, 1e-6f)
        assertEquals(a.firstBeatSec, back.firstBeatSec, 1e-6f)
        assertEquals(a.onsets.size, back.onsets.size)
        assertEquals(a.onsets[13], back.onsets[13])
        assertEquals(a.beats.size, back.beats.size)
        assertEquals(a.beats[42], back.beats[42], 1e-6f)
        assertEquals(a.meter, back.meter)
        assertEquals(a.beatInfo.size, back.beatInfo.size)
        assertEquals(a.beatInfo[42], back.beatInfo[42])
        assertTrue(back.beatInfo[8].isDownbeat)
    }

    @Test
    fun `битый файл не роняет приложение`() {
        assertNull(AnalysisIo.read(ByteArrayInputStream(ByteArray(3))))
        assertNull(AnalysisIo.read(ByteArrayInputStream(ByteArray(200) { 0xAB.toByte() })))
    }
}
