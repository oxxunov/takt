package com.oxxunov.voiceenhance.ml

/** JNI к libvenative.so (libsamplerate + официальный DeepFilterNet3 C API). */
internal object DeepFilterNative {
    val loadError: Throwable? = try {
        System.loadLibrary("df")
        System.loadLibrary("venative")
        null
    } catch (t: Throwable) {
        t
    }

    @JvmStatic external fun create(modelPath: String, attenLimDb: Float, sampleRate: Int): Long
    @JvmStatic external fun frameLength(handle: Long): Int
    @JvmStatic external fun setAttenLim(handle: Long, db: Float)
    @JvmStatic external fun process(handle: Long, input: FloatArray, n: Int): FloatArray?
    @JvmStatic external fun flush(handle: Long): FloatArray?
    @JvmStatic external fun destroy(handle: Long)
}
