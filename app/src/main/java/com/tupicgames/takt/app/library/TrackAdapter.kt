package com.tupicgames.takt.app.library

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.tupicgames.takt.app.R
import com.tupicgames.takt.core.model.Difficulty
import com.tupicgames.takt.engine.data.RecentTrack
import com.tupicgames.takt.engine.data.Records

/** Список недавних треков с лучшей оценкой по текущей сложности. */
class TrackAdapter(
    private val context: Context,
    private val records: Records
) : BaseAdapter() {

    private var items: List<RecentTrack> = emptyList()
    var difficulty: Difficulty = Difficulty.NORMAL

    fun submit(list: List<RecentTrack>, difficulty: Difficulty) {
        items = list
        this.difficulty = difficulty
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): RecentTrack = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_track, parent, false)
        val track = items[position]

        view.findViewById<TextView>(R.id.trackTitle).text = track.title

        val minutes = (track.durationSec / 60f).toInt()
        val seconds = (track.durationSec % 60f).toInt()
        view.findViewById<TextView>(R.id.trackMeta).text = context.getString(
            R.string.track_meta_fmt, track.bpm, minutes, seconds
        )

        val best = records.best(track.cacheKey, difficulty)
        view.findViewById<TextView>(R.id.trackGrade).text = best?.grade ?: "—"
        return view
    }
}
