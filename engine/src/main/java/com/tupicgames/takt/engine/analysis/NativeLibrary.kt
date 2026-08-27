package com.tupicgames.takt.engine.analysis

/** Единственная точка загрузки .so — иначе загрузка размазана по классам. */
internal object NativeLibrary {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("taktnative")
            loaded = true
        }
    }
}
