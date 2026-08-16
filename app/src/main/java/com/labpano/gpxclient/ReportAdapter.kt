package com.labpano.gpxclient

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class ReportAdapter(private val context: Context) : BaseAdapter() {
    private val items = mutableListOf<ReportEntry>()

    fun replace(values: List<ReportEntry>) {
        items.clear(); items.addAll(values); notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ReportEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder: Holder
        val view = if (convertView == null) {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setBackgroundColor(Color.WHITE)
            }
            val name = TextView(context).apply { textSize = 16f; setTextColor(Color.rgb(32, 33, 36)); setTypeface(typeface, 1) }
            val time = TextView(context).apply { textSize = 12f; setTextColor(Color.rgb(95, 99, 104)) }
            val message = TextView(context).apply { textSize = 14f; setTextColor(Color.rgb(60, 64, 67)); setPadding(0, dp(4), 0, 0) }
            root.addView(name); root.addView(time); root.addView(message)
            holder = Holder(name, time, message)
            root.tag = holder
            root
        } else { holder = convertView.tag as Holder; convertView }
        val item = getItem(position)
        holder.name.text = File(item.path).name.ifBlank { item.path }
        holder.time.text = item.timestamp
        holder.message.text = item.message
        return view
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private data class Holder(val name: TextView, val time: TextView, val message: TextView)
}
