package com.tupicgames.takt.engine.data

import android.content.Context

/**
 * Корень composition — единственное место, где создаются общие объекты.
 *
 * Раньше Prefs и AnalysisCache создавались заново в каждой Activity, и не
 * было ни одной точки, из которой видно состав зависимостей. Полноценный DI
 * тут избыточен: объектов мало, и ручной граф читается лучше, чем аннотации.
 */
object AppGraph {

    @Volatile private var prefsRef: Prefs? = null
    @Volatile private var cacheRef: AnalysisCache? = null
    @Volatile private var recordsRef: Records? = null
    @Volatile private var recentRef: RecentTracks? = null

    fun prefs(context: Context): Prefs =
        prefsRef ?: synchronized(this) {
            prefsRef ?: Prefs(context.applicationContext).also { prefsRef = it }
        }

    fun analysisCache(context: Context): AnalysisCache =
        cacheRef ?: synchronized(this) {
            cacheRef ?: AnalysisCache(context.applicationContext).also { cacheRef = it }
        }

    fun records(context: Context): Records =
        recordsRef ?: synchronized(this) {
            recordsRef ?: Records(context.applicationContext).also { recordsRef = it }
        }

    fun recentTracks(context: Context): RecentTracks =
        recentRef ?: synchronized(this) {
            recentRef ?: RecentTracks(context.applicationContext).also { recentRef = it }
        }
}
