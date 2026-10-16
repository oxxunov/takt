package com.oxxunov.voiceenhance.ml

import java.io.File

/**
 * JNI к libvesidon*.so (Sidon на рантайме CrispASR/ggml).
 * На arm64 есть два варианта: быстрый (armv8.2 + fp16 + dotprod) и базовый для старых процессоров.
 */
internal object SidonNative {
    /** Какой вариант загружен — для подписи в интерфейсе. */
    var variant: String = ""
        private set

    val loadError: Throwable? = load()

    private fun load(): Throwable? {
        val fast = cpuHasFastMath()
        val order = if (fast) listOf("vesidon_v82", "vesidon") else listOf("vesidon")
        var last: Throwable? = null
        for (name in order) {
            try {
                System.loadLibrary(name)
                variant = name
                return null
            } catch (t: Throwable) {
                last = t
            }
        }
        return last
    }

    /** armv8.2: fp16-арифметика (asimdhp) и dotprod (asimddp) — без них быстрая версия упадёт. */
    private fun cpuHasFastMath(): Boolean = try {
        val arch = System.getProperty("os.arch").orEmpty()
        if (!arch.contains("aarch64") && !arch.contains("arm64")) {
            false
        } else {
            val features = File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("Features") }
                .map { it.substringAfter(":").trim().split(Regex("\\s+")).toSet() }
            // на big.LITTLE строк несколько — нужны признаки у ВСЕХ ядер
            features.isNotEmpty() && features.all { "asimdhp" in it && "asimddp" in it && "fphp" in it }
        }
    } catch (_: Throwable) {
        false
    }

    @JvmStatic external fun init(modelPath: String, threads: Int, memBudgetMb: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun restore(handle: Long, samples: FloatArray, offset: Int, len: Int): FloatArray?
}
