package com.oxxunov.voiceenhance.ui

import android.content.Context
import com.oxxunov.voiceenhance.engine.CompressorSettings
import com.oxxunov.voiceenhance.engine.DeEsserSettings
import com.oxxunov.voiceenhance.engine.DeReverbSettings
import com.oxxunov.voiceenhance.engine.EnhanceSettings
import com.oxxunov.voiceenhance.engine.EqBand
import com.oxxunov.voiceenhance.engine.EqSettings
import com.oxxunov.voiceenhance.engine.HighPassSettings
import com.oxxunov.voiceenhance.engine.LimiterSettings
import com.oxxunov.voiceenhance.engine.LoudnessSettings
import com.oxxunov.voiceenhance.engine.LoudnessTarget
import com.oxxunov.voiceenhance.engine.NoiseReductionSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UserPreset(val name: String, val settings: EnhanceSettings)

/** Пользовательские пресеты — JSON-файл во внутренней памяти приложения. */
class PresetStore(context: Context) {
    private val file = File(context.filesDir, "user_presets.json")

    fun load(): List<UserPreset> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching { UserPreset(o.getString("name"), fromJson(o.getJSONObject("settings"))) }.getOrNull()
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(list: List<UserPreset>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name).put("settings", toJson(p.settings)))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    companion object {
        fun toJson(s: EnhanceSettings): JSONObject = JSONObject()
            .put("nr", JSONObject().put("on", s.noiseReduction.enabled).put("atten", s.noiseReduction.attenLimitDb))
            .put("dr", JSONObject().put("on", s.deReverb.enabled).put("strength", s.deReverb.strength))
            .put("hp", JSONObject().put("on", s.highPass.enabled).put("freq", s.highPass.frequencyHz).put("s24", s.highPass.slope24))
            .put(
                "eq", JSONObject().put("on", s.eq.enabled).put("preset", s.eq.presetName).put(
                    "bands", JSONArray().apply {
                        s.eq.bands.forEach { put(JSONObject().put("f", it.frequencyHz).put("g", it.gainDb).put("q", it.q)) }
                    }
                )
            )
            .put(
                "comp", JSONObject().put("on", s.compressor.enabled).put("auto", s.compressor.auto)
                    .put("thr", s.compressor.thresholdDb).put("ratio", s.compressor.ratio)
                    .put("att", s.compressor.attackMs).put("rel", s.compressor.releaseMs)
                    .put("knee", s.compressor.kneeDb).put("makeup", s.compressor.makeupDb)
            )
            .put("ds", JSONObject().put("on", s.deEsser.enabled).put("amount", s.deEsser.amount).put("freq", s.deEsser.frequencyHz))
            .put(
                "lim", JSONObject().put("on", s.limiter.enabled).put("ceil", s.limiter.ceilingDbTp)
                    .put("out", s.limiter.outputLevelDb).put("rel", s.limiter.releaseMs)
            )
            .put("loud", JSONObject().put("on", s.loudness.enabled).put("target", s.loudness.target.name))

        fun fromJson(o: JSONObject): EnhanceSettings {
            val d = EnhanceSettings()
            val nr = o.optJSONObject("nr")
            val dr = o.optJSONObject("dr")
            val hp = o.optJSONObject("hp")
            val eq = o.optJSONObject("eq")
            val c = o.optJSONObject("comp")
            val ds = o.optJSONObject("ds")
            val l = o.optJSONObject("lim")
            val ld = o.optJSONObject("loud")
            return EnhanceSettings(
                noiseReduction = if (nr == null) d.noiseReduction else NoiseReductionSettings(
                    nr.optBoolean("on", false), nr.optDouble("atten", 100.0)
                ),
                deReverb = if (dr == null) d.deReverb else DeReverbSettings(
                    dr.optBoolean("on", false), dr.optDouble("strength", 70.0)
                ),
                highPass = if (hp == null) d.highPass else HighPassSettings(
                    hp.optBoolean("on", true), hp.optDouble("freq", 80.0), hp.optBoolean("s24", true)
                ),
                eq = if (eq == null) d.eq else EqSettings(
                    eq.optBoolean("on", true), eq.optString("preset", "Своя"),
                    eq.optJSONArray("bands")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val b = arr.getJSONObject(i)
                            EqBand(b.getDouble("f"), b.optDouble("g", 0.0), b.optDouble("q", 1.0))
                        }
                    } ?: d.eq.bands
                ),
                compressor = if (c == null) d.compressor else CompressorSettings(
                    c.optBoolean("on", true), c.optBoolean("auto", true), c.optDouble("thr", -24.0),
                    c.optDouble("ratio", 3.0), c.optDouble("att", 10.0), c.optDouble("rel", 120.0),
                    c.optDouble("knee", 6.0), c.optDouble("makeup", 0.0)
                ),
                deEsser = if (ds == null) d.deEsser else DeEsserSettings(
                    ds.optBoolean("on", true), ds.optDouble("amount", 40.0), ds.optDouble("freq", 5500.0)
                ),
                limiter = if (l == null) d.limiter else LimiterSettings(
                    enabled = l.optBoolean("on", true), ceilingDbTp = l.optDouble("ceil", -1.0),
                    outputLevelDb = l.optDouble("out", 0.0), releaseMs = l.optDouble("rel", 80.0)
                ),
                loudness = if (ld == null) d.loudness else LoudnessSettings(
                    ld.optBoolean("on", true),
                    runCatching { LoudnessTarget.valueOf(ld.optString("target", "AUTO")) }.getOrDefault(LoudnessTarget.AUTO)
                ),
            )
        }
    }
}
